// Vkládá se do stránky Audiobookshelf (main world) přes webContents.executeJavaScript.
// Napojuje se přímo na Vue komponentu přehrávače (MediaPlayerContainer) webového klienta ABS,
// takže ovládání jde stejnou cestou jako tlačítka ve webu (včetně synchronizace postupu na server).
//
// Navíc opravuje synchronizaci webového přehrávače (stejná pravidla jako aplikace ABS Player
// pro Android):
//  - při pozastavení hned uloží pozici na server (web to sám nedělá, až 20 s by se ztratilo),
//  - před pokračováním po pauze si přečte pozici ze serveru a převezme ji, když ji mezitím
//    posunulo jiné zařízení (web by jinak pokračoval ze své staré pozice a přepsal ji),
//  - když je pozastavený, sleduje změny z jiných zařízení (socket → store) a pozici převezme hned.
(() => {
  if (window.__absPlayer) return true;

  const coverCache = new Map();
  let inProgressCache = { at: 0, data: null };
  const watch = {
    playing: false,
    sessionId: null,
    sessionAt: 0,      // kdy začala aktuální relace (ms)
    syncedPos: null,   // pozice, kterou má podle nás server
    pausedAt: 0,       // kdy jsme pozastavili (ms, hodiny PC)
    notice: null,
    noticeUntil: 0
  };

  function findPlayer() {
    const cached = window.__absPlayerVm;
    if (cached && !cached._isDestroyed) return cached;
    const root = window.$nuxt;
    if (!root) return null;
    const stack = [root];
    while (stack.length) {
      const vm = stack.pop();
      if (vm && vm.playerHandler && typeof vm.playPause === 'function' && typeof vm.jumpForward === 'function') {
        window.__absPlayerVm = vm;
        return vm;
      }
      if (vm && vm.$children) stack.push(...vm.$children);
    }
    return null;
  }

  function store() {
    return window.$nuxt && window.$nuxt.$store;
  }

  function axios() {
    return window.$nuxt && window.$nuxt.$axios;
  }

  function token() {
    try { return store().getters['user/getToken'] || null; } catch (e) { return null; }
  }

  function basePath() {
    try { return (window.$nuxt.$config && window.$nuxt.$config.routerBasePath) || ''; } catch (e) { return ''; }
  }

  function notify(text) {
    watch.notice = text;
    watch.noticeUntil = Date.now() + 6000;
  }

  // Obal stažený s autorizací a převedený na data: URL, aby ho popup (jiný původ) zobrazil vždy.
  async function coverData(relSrc) {
    if (!relSrc) return null;
    if (coverCache.has(relSrc)) return coverCache.get(relSrc);
    coverCache.set(relSrc, null); // zabrání paralelnímu stahování
    try {
      const url = new URL(relSrc, location.href).href;
      const headers = {};
      const t = token();
      if (t) headers.Authorization = 'Bearer ' + t;
      const res = await fetch(url, { headers, credentials: 'include' });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const blob = await res.blob();
      if (!blob.type.startsWith('image/')) throw new Error('not an image');
      const data = await new Promise((resolve, reject) => {
        const r = new FileReader();
        r.onload = () => resolve(r.result);
        r.onerror = reject;
        r.readAsDataURL(blob);
      });
      coverCache.set(relSrc, data);
      if (coverCache.size > 30) coverCache.delete(coverCache.keys().next().value);
      return data;
    } catch (e) {
      coverCache.delete(relSrc);
      return null;
    }
  }

  function coverSrc(item) {
    try { return store().getters['globals/getLibraryItemCoverSrc'](item, null, false); } catch (e) { return null; }
  }

  async function inProgress() {
    if (Date.now() - inProgressCache.at < 30000 && inProgressCache.data) return inProgressCache.data;
    try {
      const res = await axios().$get('/api/me/items-in-progress');
      const items = (res.libraryItems || []).slice(0, 8);
      const out = [];
      for (const li of items) {
        const md = (li.media && li.media.metadata) || {};
        out.push({
          id: li.id,
          episodeId: li.recentEpisode ? li.recentEpisode.id : null,
          title: li.recentEpisode ? li.recentEpisode.title : md.title,
          author: md.authorName || md.author || '',
          left: leftOf(li),
          cover: await coverData(coverSrc(li))
        });
      }
      inProgressCache = { at: Date.now(), data: out };
      return out;
    } catch (e) {
      return [];
    }
  }

  /** Remaining seconds of an in-progress item (from the user's progress in the store), or null. */
  function leftOf(li) {
    try {
      const p = store().getters['user/getUserMediaProgress'](li.id, li.recentEpisode ? li.recentEpisode.id : null);
      if (p && p.duration > 0) return Math.max(0, p.duration - (p.currentTime || 0));
    } catch (e) { /* bez zbývajícího času */ }
    return null;
  }

  function currentTime(vm) {
    const ph = vm.playerHandler;
    try { return ph && ph.player ? ph.getCurrentTime() : (vm.currentTime || 0); } catch (e) { return vm.currentTime || 0; }
  }

  // ---------- synchronizace ----------

  /** Pošle aktuální pozici na server a počká na odpověď. */
  async function syncNow(vm) {
    const ph = vm && vm.playerHandler;
    if (!ph || !ph.currentSessionId) return false;
    const t = currentTime(vm);
    const listened = Math.max(0, Math.floor(ph.listeningTimeSinceSync || 0));
    try {
      await axios().$post(`/api/session/${ph.currentSessionId}/sync`, { currentTime: t, timeListened: listened }, { timeout: 9000, progress: false });
      ph.listeningTimeSinceSync = Math.max(0, (ph.listeningTimeSinceSync || 0) - listened);
      ph.lastSyncTime = t;
      watch.syncedPos = t;
      return true;
    } catch (e) {
      return false;
    }
  }

  function withTimeout(p, ms) {
    return Promise.race([p, new Promise((resolve) => setTimeout(() => resolve(null), ms))]);
  }

  /** Před pokračováním: převzít novější pozici ze serveru, pokud ji posunulo jiné zařízení. */
  async function checkBeforeResume(vm) {
    const item = vm.streamLibraryItem;
    const ph = vm.playerHandler;
    if (!item || !ph) return;
    if (Date.now() - watch.sessionAt < 5000) return; // právě načteno se startovní pozicí
    const p = await withTimeout(axios().$get(`/api/me/progress/${item.id}`).catch(() => null), 2500);
    if (!p || typeof p.currentTime !== 'number') return;
    const cur = currentTime(vm);
    const known = watch.syncedPos != null ? watch.syncedPos : cur;
    if (Math.abs(p.currentTime - known) > 3 && Math.abs(p.currentTime - cur) > 3) {
      ph.seek(p.currentTime, false);
      watch.syncedPos = p.currentTime;
      notify('moved');
    }
  }

  /** Obalí play/playPause přehrávače kontrolou serveru (jednou na instanci PlayerHandleru). */
  function patchHandler(vm) {
    const ph = vm.playerHandler;
    if (!ph || ph.__absPlayerPatched) return;
    ph.__absPlayerPatched = true;
    if (typeof ph.playPause === 'function') {
      const origPlayPause = ph.playPause.bind(ph);
      ph.playPause = function () {
        if (ph.playerPlaying) return origPlayPause();
        checkBeforeResume(vm).finally(() => origPlayPause());
      };
    }
    if (typeof ph.play === 'function') {
      const origPlay = ph.play.bind(ph);
      ph.play = function () {
        checkBeforeResume(vm).finally(() => origPlay());
      };
    }
  }

  /** Pozastaveno: pozice z jiného zařízení (socket aktualizuje store) se převezme hned. */
  function followWhilePaused(vm) {
    const item = vm.streamLibraryItem;
    const ph = vm.playerHandler;
    if (!item || !ph || !ph.player) return;
    let p = null;
    try { p = store().getters['user/getUserMediaProgress'](item.id, vm.streamEpisode ? vm.streamEpisode.id : null); } catch (e) {}
    if (!p || typeof p.currentTime !== 'number' || !p.lastUpdate) return;
    if (p.lastUpdate <= watch.pausedAt + 1500) return; // změna z doby před pauzou (nebo naše vlastní)
    const cur = currentTime(vm);
    if (Math.abs(p.currentTime - cur) > 3) {
      ph.seek(p.currentTime, false);
      watch.syncedPos = p.currentTime;
      notify('moved');
    }
    watch.pausedAt = p.lastUpdate;
  }

  async function tick() {
    const vm = findPlayer();
    if (!vm || !vm.streamLibraryItem || !vm.playerHandler) {
      watch.playing = false;
      return;
    }
    patchHandler(vm);
    const ph = vm.playerHandler;
    if (ph.currentSessionId !== watch.sessionId) {
      watch.sessionId = ph.currentSessionId;
      watch.sessionAt = Date.now();
      watch.syncedPos = null;
    }
    const playing = !!vm.isPlaying;
    if (watch.playing && !playing) {
      watch.pausedAt = Date.now();
      await syncNow(vm);
    } else if (!playing) {
      followWhilePaused(vm);
    }
    watch.playing = playing;
  }
  setInterval(() => { tick().catch(() => {}); }, 1000);

  // ---------- stav pro minipřehrávač / vzdálená zařízení ----------

  async function state() {
    const s = store();
    const loggedIn = !!(s && s.state.user && s.state.user.user);
    const vm = findPlayer();
    if (!vm) return { ready: false, loggedIn };
    const item = vm.streamLibraryItem;
    if (!item) {
      return { ready: true, loggedIn, item: null, inProgress: loggedIn ? (await inProgress()).slice(0, 3) : [] };
    }
    let author = '';
    try {
      author = vm.podcastAuthor || (vm.authors || []).map((a) => a.name).join(', ') || (vm.mediaMetadata && vm.mediaMetadata.authorName) || '';
    } catch (e) {}
    const ch = vm.currentChapter;
    const ph = vm.playerHandler;
    const ui = vm.$refs && vm.$refs.audioPlayer;
    const vc = ui && ui.$refs && ui.$refs.volumeControl;
    const volume = ui && typeof ui.volume === 'number' ? ui.volume : 1;
    const chapters = vm.chapters || [];
    return {
      ready: true,
      loggedIn,
      item: {
        id: item.id,
        title: vm.title,
        author,
        series: (vm.mediaMetadata && vm.mediaMetadata.seriesName) || '',
        cover: await coverData(coverSrc(item))
      },
      isPlaying: !!vm.isPlaying,
      loading: !!vm.playerLoading,
      currentTime: vm.currentTime || 0,
      duration: vm.totalDuration || 0,
      rate: vm.currentPlaybackRate || 1,
      chapter: ch ? { title: ch.title, start: ch.start, end: ch.end, index: chapters.indexOf(ch) + 1, total: chapters.length } : null,
      hasChapters: chapters.length > 0,
      chaptersKey: item.id + ':' + chapters.length + ':' + (chapters.length ? chapters[chapters.length - 1].end : 0),
      volume,
      muted: !!(vc && vc.isMute) || volume <= 0,
      jumpBack: ph.jumpBackwardAmount || 10,
      jumpFwd: ph.jumpForwardAmount || 10,
      sleepRemaining: vm.sleepTimerSet ? vm.sleepTimerRemaining : null,
      notice: Date.now() < watch.noticeUntil ? watch.notice : null
    };
  }

  function cmd(name, arg) {
    const vm = findPlayer();
    if (name === 'cover') return coverData(`${basePath()}/api/items/${encodeURIComponent(arg)}/cover`);
    if (name === 'user') {
      try {
        const u = store().state.user.user;
        return { username: u.username, userId: u.id };
      } catch (e) { return null; }
    }
    if (!vm) return false;
    const ui = vm.$refs && vm.$refs.audioPlayer;
    switch (name) {
      case 'playPause':
        if (!vm.streamLibraryItem) return false;
        vm.playPause();
        return true;
      case 'play':
        if (!vm.streamLibraryItem) return false;
        if (!vm.isPlaying) vm.playPause();
        return true;
      case 'pauseSync':
        // pozastavit a počkat, až server pozici uloží (předání na jiné zařízení)
        return (async () => {
          if (!vm.streamLibraryItem) return null;
          if (vm.isPlaying) vm.playPause();
          await new Promise((r) => setTimeout(r, 250));
          watch.playing = false;
          watch.pausedAt = Date.now();
          await syncNow(vm);
          return { id: vm.streamLibraryItem.id, time: currentTime(vm) };
        })();
      case 'handoff':
        // pozastavit, uložit pozici a zavřít relaci (přehrávání pokračuje jinde)
        return (async () => {
          if (!vm.streamLibraryItem) return null;
          const id = vm.streamLibraryItem.id;
          if (vm.isPlaying) vm.playPause();
          await new Promise((r) => setTimeout(r, 250));
          await syncNow(vm);
          const time = currentTime(vm);
          watch.playing = false;
          try { vm.closePlayer(); } catch (e) {}
          inProgressCache.at = 0;
          return { id, time };
        })();
      case 'jumpBack':
        vm.jumpBackward();
        return true;
      case 'jumpFwd':
        vm.jumpForward();
        return true;
      case 'seek':
        if (typeof arg === 'number' && isFinite(arg)) vm.seek(Math.max(0, arg));
        return true;
      case 'skip':
        if (typeof arg === 'number' && isFinite(arg)) vm.seek(Math.max(0, currentTime(vm) + arg));
        return true;
      case 'prevChapter':
        if (ui && ui.prevChapter) ui.prevChapter();
        return true;
      case 'nextChapter':
        if (ui && ui.goToNext) ui.goToNext();
        return true;
      case 'rate': {
        const r = Math.min(3, Math.max(0.5, Number(arg) || 1));
        if (ui && typeof ui.playbackRateChanged === 'function') {
          ui.playbackRate = r; // aktualizuje i ovládání ve webu a uloží do uživatelského nastavení
          ui.playbackRateChanged(r);
        } else {
          vm.setPlaybackRate(r);
        }
        return true;
      }
      case 'volume': {
        const v = Math.min(1, Math.max(0, Number(arg)));
        if (isNaN(v)) return false;
        const vc = ui && ui.$refs && ui.$refs.volumeControl;
        if (vc) {
          if (v > 0 && vc.isMute) vc.isMute = false;
          vc.volume = v; // stejná cesta jako posuvník ve webu (uloží i do localStorage)
        } else {
          if (ui) ui.volume = v;
          vm.setVolume(v);
        }
        return true;
      }
      case 'mute': {
        const vc = ui && ui.$refs && ui.$refs.volumeControl;
        if (vc && typeof vc.toggleMute === 'function') vc.toggleMute();
        else if (ui && typeof ui.toggleMute === 'function') ui.toggleMute();
        return true;
      }
      case 'recent':
        return inProgress();
      case 'chapters':
        return (vm.chapters || []).map((c, i) => ({ n: i + 1, title: c.title, start: c.start, end: c.end }));
      case 'playItem': {
        if (!arg || !arg.id) return false;
        const payload = { libraryItemId: arg.id, episodeId: arg.episodeId || null };
        if (typeof arg.startTime === 'number' && isFinite(arg.startTime)) payload.startTime = arg.startTime;
        watch.sessionAt = Date.now(); // startovní pozice je záměrná, nekontrolovat proti serveru
        window.$nuxt.$eventBus.$emit('play-item', payload);
        inProgressCache.at = 0;
        return true;
      }
      default:
        return false;
    }
  }

  window.__absPlayer = { state, cmd };
  return true;
})();
