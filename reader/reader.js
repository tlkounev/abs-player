// ABS Player – EPUB čtečka (epub.js), sdílená Androidem (WebView) a Windows (Electron).
//
// Hostitel (window.AbsHost) dodá konfiguraci a dostává zprávy:
//   bookData()          -> (volitelně, Windows) Promise<ArrayBuffer> se souborem knihy místo bookUrl
//   config()            -> JSON: { bookUrl, startCfi, mode: 'saved'|'fromAudio'|'toAudio', audioTime,
//                                  audioDuration, audioChapters: [{start,end,title}], settings, locations }
//   relocated(json)     <- { cfi, progress, chapter, chapterIndex, chapterCount, page, pages }
//   toc(json)           <- [{ label, href, index }]
//   tap(zone)           <- 'center' (lišta), okraje listují samy
//   listen(seconds)     <- „Poslouchat od této strany“: čas v audioknize
//   saveLocations(json) <- vygenerované pozice (hostitel je uloží, příště se nepočítají znovu)
//   ready() / error(text)
//
// Hostitel volá: reader.next(), reader.prev(), reader.goto(href), reader.applySettings(json),
//                reader.listenHere(), reader.gotoAudio(seconds)
//
// Pozice = CFI začátku stránky, stejně jako webová čtečka Audiobookshelfu (ebookLocation / ebookProgress).
//
// Synchronizace s poslechem: poměr uvnitř kapitoly. % uplynulé audio kapitoly = % textu stejné kapitoly
// v e-knize (počítáno podle pozic epub.js, tj. podle znaků, ne stran). Kapitoly se přiřadí podle názvu,
// jinak podle pořadí; když to nejde, poměr přes celou knihu.
'use strict';

(function () {
  const host = window.AbsHost || {
    config: () => JSON.stringify({ bookUrl: 'book.epub' }),
    relocated() {}, toc() {}, tap() {}, listen() {}, saveLocations() {}, ready() {}, error(e) { console.error(e); }
  };
  const call = (name, arg) => { try { host[name](arg); } catch (e) { /* hostitel nepodporuje */ } };

  let cfg = {};
  try { cfg = JSON.parse(host.config()); } catch (e) { cfg = {}; }

  const THEMES = {
    dark: { bg: '#1e1e1e', fg: '#e6e1d8', link: '#e3a54d' },
    sepia: { bg: '#f4ecd8', fg: '#4a3f2c', link: '#9a5b12' },
    light: { bg: '#fafafa', fg: '#222222', link: '#9a5b12' }
  };
  const FONTS = { serif: 'Georgia, "Noto Serif", "Times New Roman", serif', sans: 'Roboto, "Segoe UI", Arial, sans-serif' };

  let book, rendition, ready = false, locationsReady = false;
  let toc = [];          // [{ label, href, index, loc }] podle pořadí v knize
  let chapterMap = null; // audio kapitola -> index v toc (null = poměr přes celou knihu)
  let current = null;    // poslední relocated
  let settings = Object.assign({ theme: 'dark', fontSize: 100, font: 'serif', lineHeight: 1.6 }, cfg.settings || {});

  const wait = document.getElementById('wait');
  const showWait = (on) => wait.classList.toggle('hidden', !on);

  function norm(s) {
    return String(s || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '')
      .replace(/[^a-z0-9]+/g, ' ').trim();
  }
  function firstNumber(s) {
    const m = norm(s).match(/\b(\d{1,4})\b/);
    return m ? Number(m[1]) : null;
  }

  // ------------------------------------------------------------------ vzhled

  function applySettings(s) {
    if (s) settings = Object.assign(settings, typeof s === 'string' ? JSON.parse(s) : s);
    const t = THEMES[settings.theme] || THEMES.dark;
    document.documentElement.style.setProperty('--bg', t.bg);
    document.body.style.background = t.bg;
    if (!rendition) return;
    rendition.themes.override('color', t.fg);
    rendition.themes.override('background', t.bg);
    rendition.themes.override('line-height', String(settings.lineHeight));
    if (settings.font === 'original') rendition.themes.override('font-family', '');
    else rendition.themes.override('font-family', FONTS[settings.font] || FONTS.serif);
    rendition.themes.fontSize(settings.fontSize + '%');
    rendition.themes.default({
      'a, a:link, a:visited': { color: t.link + ' !important' },
      'body': { 'background': t.bg + ' !important', 'color': t.fg + ' !important' },
      'p, div, span, li, h1, h2, h3, h4, h5, h6, blockquote': { 'color': t.fg + ' !important' }
    });
  }

  // ------------------------------------------------------------------ kapitoly a pozice

  function flatToc(items, out) {
    (items || []).forEach((it) => { out.push(it); if (it.subitems && it.subitems.length) flatToc(it.subitems, out); });
    return out;
  }

  /** Index pozice (epub.js locations), kde začíná sekce spine. */
  function buildToc() {
    const total = book.locations.length();
    const spineStart = {};
    const locs = book.locations._locations || [];
    for (let i = 0; i < locs.length; i++) {
      let pos = null;
      try { pos = new ePub.CFI(locs[i]).spinePos; } catch (e) { pos = null; }
      if (pos != null && spineStart[pos] === undefined) spineStart[pos] = i;
    }
    const seen = new Set();
    const list = [];
    flatToc(book.navigation ? book.navigation.toc : [], []).forEach((it) => {
      const item = book.spine.get((it.href || '').split('#')[0]);
      if (!item) return;
      let loc = spineStart[item.index];
      if (loc === undefined) {
        // sekce bez textu (obrázek): první pozice za ní
        const next = Object.keys(spineStart).map(Number).filter((k) => k > item.index).sort((a, b) => a - b)[0];
        loc = next !== undefined ? spineStart[next] : total;
      }
      if (seen.has(loc)) return;
      seen.add(loc);
      list.push({ label: (it.label || '').trim(), href: it.href, loc });
    });
    list.sort((a, b) => a.loc - b.loc);
    list.forEach((t, i) => { t.index = i; t.end = i + 1 < list.length ? list[i + 1].loc : total; });
    toc = list;
    call('toc', JSON.stringify(toc.map((t) => ({ label: t.label, href: t.href, index: t.index }))));
    chapterMap = mapChapters(cfg.audioChapters || [], toc);
  }

  /** Audio kapitola i -> kapitola e-knihy (index v toc), nebo null, když si neodpovídají. */
  function mapChapters(audio, text) {
    const n = audio.length, m = text.length;
    if (!n || !m) return null;
    const map = new Array(n).fill(null);
    // 1) podle názvu (stejný text, nebo stejné číslo kapitoly), jen dopředu
    let from = 0, matched = 0;
    for (let a = 0; a < n; a++) {
      const an = norm(audio[a].title), anum = firstNumber(audio[a].title);
      for (let t = from; t < m; t++) {
        const tn = norm(text[t].label), tnum = firstNumber(text[t].label);
        const same = an && tn && (an === tn || (an.length > 3 && tn.length > 3 && (an.includes(tn) || tn.includes(an))));
        if (same || (anum !== null && anum === tnum)) { map[a] = t; from = t + 1; matched++; break; }
      }
    }
    if (matched >= Math.max(2, Math.ceil(n * 0.5))) {
      // nepřiřazené mezi dvěma přiřazenými: po pořadí, pokud se vejdou
      for (let a = 0; a < n; a++) {
        if (map[a] !== null) continue;
        let p = a - 1; while (p >= 0 && map[p] === null) p--;
        let q = a + 1; while (q < n && map[q] === null) q++;
        const lo = p >= 0 ? map[p] : -1, hi = q < n ? map[q] : m;
        const guess = lo + (a - p);
        if (guess > lo && guess < hi) map[a] = guess;
      }
      return map;
    }
    // 2) stejný počet kapitol: po pořadí
    if (n === m) return audio.map((_, i) => i);
    return null;
  }

  function locFromAudio(seconds) {
    const total = book.locations.length();
    const audio = cfg.audioChapters || [];
    if (chapterMap) {
      let a = -1;
      for (let i = 0; i < audio.length; i++) if (audio[i].start <= seconds + 0.01) a = i;
      if (a >= 0 && chapterMap[a] !== null) {
        const ch = audio[a], t = toc[chapterMap[a]];
        const f = Math.min(1, Math.max(0, (seconds - ch.start) / Math.max(1, ch.end - ch.start)));
        return Math.floor(t.loc + f * (t.end - t.loc));
      }
    }
    const dur = cfg.audioDuration || (audio.length ? audio[audio.length - 1].end : 0);
    return dur > 0 ? Math.floor((seconds / dur) * total) : 0;
  }

  function audioFromLoc(loc) {
    const total = book.locations.length();
    const audio = cfg.audioChapters || [];
    if (chapterMap) {
      let k = -1;
      for (let i = 0; i < toc.length; i++) if (toc[i].loc <= loc) k = i;
      const a = chapterMap.indexOf(k);
      if (k >= 0 && a >= 0) {
        const t = toc[k], ch = audio[a];
        const f = Math.min(1, Math.max(0, (loc - t.loc) / Math.max(1, t.end - t.loc)));
        return ch.start + f * (ch.end - ch.start);
      }
    }
    const dur = cfg.audioDuration || (audio.length ? audio[audio.length - 1].end : 0);
    return total > 0 ? (loc / total) * dur : 0;
  }

  function chapterOf(loc) {
    let k = -1;
    for (let i = 0; i < toc.length; i++) if (toc[i].loc <= loc) k = i;
    return k;
  }

  function diag() {
    const v = document.getElementById('viewer');
    const fr = [...document.querySelectorAll('iframe')];
    const f = fr[0];
    let txt = -1, fg = '', bgc = '';
    try {
      const d = f && f.contentDocument;
      txt = d && d.body ? d.body.innerText.length : -1;
      if (d && d.body) { const cs = d.defaultView.getComputedStyle(d.body); fg = cs.color; bgc = cs.backgroundColor; }
    } catch (e) { txt = -2; }
    const r = f ? f.getBoundingClientRect() : null;
    return 'win ' + innerWidth + 'x' + innerHeight + ' viewer ' + (v ? v.clientWidth + 'x' + v.clientHeight : '-') +
      ' iframes ' + fr.length + (r ? ' [' + Math.round(r.left) + ',' + Math.round(r.top) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height) + ']' : '') +
      ' text ' + txt + ' color ' + fg + ' on ' + bgc + ' cfi ' + (current && current.start ? current.start.cfi : '-');
  }
  window.readerDiag = diag;

  function report(location) {
    if (!location || !location.start) return;
    current = location;
    const out = { cfi: location.start.cfi, progress: null, chapter: '', chapterIndex: -1, chapterCount: toc.length, page: null, pages: null };
    if (locationsReady) {
      const total = book.locations.length();
      const s = book.locations.locationFromCfi(location.start.cfi);
      const e = book.locations.locationFromCfi(location.end.cfi);
      out.progress = location.end.percentage || (total ? e / total : 0);
      // „strana“ = kolik pozic se vejde na obrazovku (mění se s písmem)
      const span = Math.max(1, e - s);
      out.page = Math.floor(s / span) + 1;
      out.pages = Math.max(out.page, Math.ceil(total / span));
      const k = chapterOf(s);
      if (k >= 0) { out.chapter = toc[k].label; out.chapterIndex = k; }
    } else {
      const item = book.spine.get(location.start.href);
      const t = toc.find((x) => item && book.spine.get((x.href || '').split('#')[0]) === item);
      if (t) { out.chapter = t.label; out.chapterIndex = t.index; }
    }
    call('relocated', JSON.stringify(out));
  }

  // ------------------------------------------------------------------ ovládání

  const api = {
    next() { if (rendition) rendition.next(); },
    prev() { if (rendition) rendition.prev(); },
    goto(href) { if (rendition) rendition.display(href); },
    /** 'none' (one page) or 'always' (two pages side by side). */
    setSpread(mode) { if (rendition) { rendition.spread(mode); relayout(); } },
    applySettings(json) { applySettings(json); },
    async gotoAudio(seconds) {
      await whenLocations();
      const loc = locFromAudio(Number(seconds) || 0);
      await rendition.display(book.locations.cfiFromLocation(loc));
    },
    async listenHere() {
      await whenLocations();
      let here = null;
      try { here = rendition.currentLocation(); } catch (e) { here = null; }
      const cfi = (here && here.start && here.start.cfi) || (current && current.start ? current.start.cfi : null);
      const loc = cfi ? book.locations.locationFromCfi(cfi) : 0;
      call('listen', String(Math.max(0, audioFromLoc(loc))));
    }
  };
  window.reader = api;

  let locationsWaiters = [];
  function whenLocations() {
    if (locationsReady) return Promise.resolve();
    return new Promise((r) => locationsWaiters.push(r));
  }

  // klepnutí: okraje listují, střed ukáže / schová lištu
  // new size / one or two pages: lay out again and return to the same place (else the columns end up
  // shifted half a page)
  let relayoutTimer = null;
  function relayout() {
    clearTimeout(relayoutTimer);
    relayoutTimer = setTimeout(async () => {
      if (!rendition) return;
      let cfi = null;
      try { const l = rendition.currentLocation(); cfi = l && l.start && l.start.cfi; } catch (e) { cfi = null; }
      if (!cfi && current && current.start) cfi = current.start.cfi;
      try { rendition.resize(); } catch (e) { /* ignore */ }
      if (cfi) { try { await rendition.display(cfi); } catch (e) { /* ignore */ } }
    }, 150);
  }

  let lastSwipe = 0;
  function onTap(ev) {
    if (Date.now() - lastSwipe < 400) return; // the end of a swipe is not a tap
    let x = ev.clientX;
    try { if (ev.view && ev.view.frameElement) x += ev.view.frameElement.getBoundingClientRect().left; } catch (e) {}
    // zones of the text area (the Windows window has the contents list on the left)
    const v = document.getElementById('viewer').getBoundingClientRect();
    const rel = (x - v.left) / Math.max(1, v.width);
    if (rel < 0.3) api.prev();
    else if (rel > 0.7) api.next();
    else call('tap', 'center');
  }

  function onKey(e) {
    const k = e.key;
    if (k === 'ArrowLeft' || k === 'PageUp') { api.prev(); e.preventDefault && e.preventDefault(); }
    else if (k === 'ArrowRight' || k === 'PageDown' || k === ' ') { api.next(); e.preventDefault && e.preventDefault(); }
  }

  async function start() {
    try {
      // Windows: the host hands the file over directly; Android: a URL served by the WebView
      let source = cfg.bookUrl || 'book.epub';
      if (typeof host.bookData === 'function') {
        const data = await host.bookData();
        if (data) source = data instanceof ArrayBuffer ? data : (data.buffer ? data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) : data);
      }
      book = typeof source === 'string' ? ePub(source, { openAs: 'epub' }) : ePub(source);
      // Android WebView: the page can start before the view has its size – wait for it
      for (let i = 0; i < 100 && (window.innerWidth < 50 || window.innerHeight < 50); i++) await new Promise((r) => setTimeout(r, 50));
      // one section at a time; swipes and taps are handled here (the "continuous" manager with snapping
      // did not turn pages on touch screens)
      rendition = book.renderTo('viewer', {
        width: '100%', height: '100%', spread: cfg.spread || 'none', flow: 'paginated',
        manager: 'default', allowScriptedContent: false
      });
      rendition.hooks.content.register((contents) => {
        const doc = contents.document;
        let sx = 0, sy = 0;
        doc.addEventListener('touchstart', (e) => { const t = e.changedTouches[0]; sx = t.clientX; sy = t.clientY; }, { passive: true });
        doc.addEventListener('touchend', (e) => {
          const t = e.changedTouches[0], dx = t.clientX - sx, dy = t.clientY - sy;
          if (Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy) * 1.5) {
            lastSwipe = Date.now();
            if (dx < 0) api.next(); else api.prev();
          }
        }, { passive: true });
        doc.addEventListener('keyup', onKey);
      });
      applySettings();
      rendition.on('relocated', report);
      rendition.on('click', onTap);
      rendition.on('keyup', onKey);
      document.addEventListener('keyup', onKey);
      window.addEventListener('resize', relayout);
      window.addEventListener('epub-resize', relayout);
      await book.ready;

      // pozice: z mezipaměti hostitele, jinak spočítat (u velkých knih několik sekund)
      let fromCache = false;
      if (cfg.locations) {
        try { book.locations.load(cfg.locations); fromCache = book.locations.length() > 0; } catch (e) { fromCache = false; }
      }
      const finishLocations = () => {
        locationsReady = true;
        buildToc();
        locationsWaiters.forEach((r) => r()); locationsWaiters = [];
        if (current) report(current);
      };
      const needLocations = cfg.mode === 'fromAudio' || cfg.mode === 'toAudio';
      if (fromCache) finishLocations();
      else {
        const gen = book.locations.generate(1024).then(() => {
          call('saveLocations', book.locations.save());
          finishLocations();
        });
        if (needLocations) await gen;
      }

      if (cfg.mode === 'fromAudio' && cfg.audioTime != null) {
        await rendition.display(book.locations.cfiFromLocation(locFromAudio(Number(cfg.audioTime))));
      } else {
        const start = cfg.startCfi && String(cfg.startCfi).startsWith('epubcfi') ? cfg.startCfi : undefined;
        await rendition.display(start);
      }
      showWait(false);
      try { rendition.resize(); } catch (e) { /* ignore */ }
      ready = true;
      call('ready', '');
      // what is on the screen (for finding display problems on a device)
      setTimeout(() => call('log', 'diag: ' + diag()), 1500);
      if (cfg.mode === 'toAudio') api.listenHere();
    } catch (e) {
      showWait(false);
      call('error', String((e && e.message) || e));
    }
  }

  window.addEventListener('error', (e) => call('log', 'error: ' + (e.message || e.type)));
  window.addEventListener('unhandledrejection', (e) => call('log', 'rejection: ' + ((e.reason && e.reason.message) || e.reason)));
  start();
})();
