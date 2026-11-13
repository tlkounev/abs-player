// node reader/test/reader.test.js <book.epub>
// Spustí čtečku v Chromiu s falešným hostitelem a ověří: načtení, obsah, přiřazení kapitol,
// skok z poslechu do textu a zpět (poměr v kapitole), uložení / načtení pozic, motivy.
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const ROOT = path.join(__dirname, '..');
const BOOK = process.argv[2];
let fails = 0;
const check = (name, ok, extra) => { console.log((ok ? 'PASS ' : 'FAIL ') + name + (extra !== undefined ? ' ' + JSON.stringify(extra) : '')); if (!ok) fails++; };

// 6 kapitol, délka audia úměrná délce textu (3, 8, 5, 12, 4, 9 odstavců → 60 s na odstavec)
const lens = [3, 8, 5, 12, 4, 9];
let t = 0;
const chapters = lens.map((n, i) => { const c = { start: t, end: t + n * 60, title: 'Kapitola ' + (i + 1) }; t += n * 60; return c; });

const server = http.createServer((q, s) => {
  const p = decodeURIComponent(q.url.split('?')[0]);
  const f = p === '/book.epub' ? BOOK : path.join(ROOT, p);
  if (!fs.existsSync(f)) { s.writeHead(404); return s.end(); }
  const type = f.endsWith('.js') ? 'text/javascript' : f.endsWith('.html') ? 'text/html' : 'application/epub+zip';
  s.writeHead(200, { 'Content-Type': type });
  fs.createReadStream(f).pipe(s);
});

async function open(browser, cfg, size) {
  const page = await browser.newPage({ viewport: size || { width: 400, height: 700 } });
  const log = [];
  await page.exposeFunction('__hostMsg', (name, arg) => log.push([name, arg]));
  await page.addInitScript((c) => {
    window.AbsHost = {
      config: () => JSON.stringify(c),
      relocated: (j) => window.__hostMsg('relocated', j),
      toc: (j) => window.__hostMsg('toc', j),
      tap: (z) => window.__hostMsg('tap', z),
      listen: (s) => window.__hostMsg('listen', s),
      saveLocations: (j) => window.__hostMsg('saveLocations', j),
      ready: () => window.__hostMsg('ready', ''),
      error: (e) => window.__hostMsg('error', e)
    };
  }, cfg);
  page.on('pageerror', (e) => log.push(['pageerror', String(e)]));
  await page.goto(`http://127.0.0.1:${server.address().port}/reader.html`);
  return { page, log };
}
const until = async (log, name, ms = 15000) => {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) { const m = log.filter((x) => x[0] === name); if (m.length) return m; await new Promise((r) => setTimeout(r, 100)); }
  return [];
};
const last = (log, name) => { const m = log.filter((x) => x[0] === name); return m.length ? m[m.length - 1][1] : null; };
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const browser = await chromium.launch();
  const base = { bookUrl: 'book.epub', audioChapters: chapters, audioDuration: t, settings: { theme: 'dark', fontSize: 100, font: 'serif', lineHeight: 1.6 } };

  // 1) normální otevření
  let { page, log } = await open(browser, Object.assign({ mode: 'saved' }, base));
  check('ready', (await until(log, 'ready')).length > 0, log.filter((x) => x[0] === 'error' || x[0] === 'pageerror'));
  const tocMsg = await until(log, 'toc');
  const toc = tocMsg.length ? JSON.parse(tocMsg[0][1]) : [];
  check('toc has 6 chapters', toc.length === 6, toc.map((x) => x.label));
  await wait(500);
  let rel = JSON.parse(last(log, 'relocated'));
  check('starts in chapter 1', rel.chapter === 'Kapitola 1' && rel.chapterIndex === 0, rel);
  check('page numbers', rel.page === 1 && rel.pages > 5, rel);
  const savedLocs = last(log, 'saveLocations');
  check('locations generated for the host', !!savedLocs && JSON.parse(savedLocs).length > 10);

  // 2) poslech -> text: polovina kapitoly 4
  const t4 = chapters[3].start + (chapters[3].end - chapters[3].start) * 0.5;
  await page.evaluate((s) => window.reader.gotoAudio(s), t4);
  await wait(600);
  rel = JSON.parse(last(log, 'relocated'));
  check('audio 50 % of ch. 4 -> chapter 4', rel.chapter === 'Kapitola 4', rel);
  // začátek stránky v CFI: /4/<2·(n+2)>/… = n-tý odstavec kapitoly (h1 je /2, první odstavec /4)
  const step = Number((rel.cfi.match(/!\/4\/(\d+)/) || [])[1]);
  const para = (step - 4) / 2;
  check('page starts in the middle of chapter 4 (paragraph 4–7 of 12)', para >= 4 && para <= 7, { cfi: rel.cfi, para });

  // 3) text -> poslech: zpátky na čas (začátek stránky, tolerance 1 odstavec = 60 s)
  log.length = 0;
  await page.evaluate(() => window.reader.listenHere());
  const back = Number((await until(log, 'listen'))[0][1]);
  check('listen here -> back near the same time', Math.abs(back - t4) < 75 && back <= t4 + 5, { back, t4 });

  // 4) motiv a písmo
  await page.evaluate(() => window.reader.applySettings(JSON.stringify({ theme: 'sepia', fontSize: 130 })));
  await wait(400);
  const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  check('sepia background', bg === 'rgb(244, 236, 216)', bg);
  await page.screenshot({ path: path.join(__dirname, 'out-sepia.png') });

  // 5) další strana
  const before = rel.cfi;
  await page.evaluate(() => window.reader.next());
  await wait(500);
  check('next page moves', JSON.parse(last(log, 'relocated')).cfi !== before);
  await page.close();

  // 6) otevření rovnou z místa poslechu, s uloženými pozicemi (bez generování)
  ({ page, log } = await open(browser, Object.assign({ mode: 'fromAudio', audioTime: chapters[5].start + 10, locations: savedLocs }, base)));
  await until(log, 'ready');
  await wait(400);
  rel = JSON.parse(last(log, 'relocated'));
  check('fromAudio opens chapter 6', rel.chapter === 'Kapitola 6', rel);
  check('cached locations: nothing generated again', !log.some((x) => x[0] === 'saveLocations'));
  await page.screenshot({ path: path.join(__dirname, 'out-dark.png') });
  await page.close();

  // 7) toAudio: otevře se na uloženém místě a hned vrátí čas
  ({ page, log } = await open(browser, Object.assign({ mode: 'toAudio', startCfi: JSON.parse(savedLocs)[Math.floor(JSON.parse(savedLocs).length * 0.6)], locations: savedLocs }, base)));
  const l7 = await until(log, 'listen');
  check('toAudio reports a time', l7.length > 0 && Number(l7[0][1]) > 0, l7);
  await page.close();

  // 8) jiné názvy, ale čísla kapitol (01, 02, …) – přiřazení podle čísla
  const numbered = chapters.map((c, i) => Object.assign({}, c, { title: String(i + 1).padStart(2, '0') }));
  ({ page, log } = await open(browser, Object.assign({}, base, { mode: 'fromAudio', audioTime: numbered[2].start + 5, audioChapters: numbered, locations: savedLocs })));
  await until(log, 'ready');
  await wait(400);
  check('numbered audio chapters map by number', JSON.parse(last(log, 'relocated')).chapter === 'Kapitola 3');
  await page.close();

  // 9) nesouhlasí nic (jiný počet, jiné názvy) – poměr přes celou knihu
  const odd = [{ start: 0, end: t / 2, title: 'Část A' }, { start: t / 2, end: t, title: 'Část B' }, { start: t, end: t + 1, title: 'Titulky' }];
  ({ page, log } = await open(browser, Object.assign({}, base, { mode: 'fromAudio', audioTime: t * 0.5, audioChapters: odd, audioDuration: t + 1, locations: savedLocs })));
  await until(log, 'ready');
  await wait(400);
  rel = JSON.parse(last(log, 'relocated'));
  check('no mapping: whole-book ratio (~50 % → ch. 4)', rel.chapter === 'Kapitola 4', rel);
  await page.close();

  // 10) dvě strany vedle sebe (Windows)
  ({ page, log } = await open(browser, Object.assign({ mode: 'saved', spread: 'auto' }, base), { width: 1100, height: 700 }));
  await until(log, 'ready');
  await wait(500);
  await page.screenshot({ path: path.join(__dirname, 'out-wide.png') });
  await page.close();

  await browser.close();
  server.close();
  console.log(fails ? fails + ' FAIL' : 'all passed');
  process.exit(fails ? 1 : 0);
})();
