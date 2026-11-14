// Injected into the Audiobookshelf page (main world) via webContents.executeJavaScript.
// Hooks directly into the player's Vue component (MediaPlayerContainer) of the ABS web client,
// so control goes the same way as the web's buttons (including progress sync to the server).
//
// It also fixes the web player's sync (same rules as ABS Player for Android):
//  - on pause it saves the position to the server right away (the web does not; up to 20 s would be lost),
//  - before resuming after a pause it reads the server position and takes it over when another
//    device moved it meanwhile (the web would resume from its old position and overwrite it),
//  - while paused it follows changes from other devices (socket → store) and takes the position over at once.
(() => {
  if (window.__absPlayer) return true;

  const coverCache = new Map();
  let inProgressCache = { at: 0, data: null };
  const watch = {
    playing: false,
    sessionId: null,
    sessionAt: 0,      // when the current session started (ms)
    syncedPos: null,   // the position we believe the server has
    pausedAt: 0,       // when we paused (ms, PC clock)
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

  // Cover downloaded with authorization and turned into a data: URL, so the popup (another origin) can always show it.
  async function coverData(relSrc) {
    if (!relSrc) return null;
    if (coverCache.has(relSrc)) return coverCache.get(relSrc);
    coverCache.set(relSrc, null); // prevents parallel downloads
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
          ebook: !!(li.media && (String(li.media.ebookFormat || '').toLowerCase() === 'epub' ||
            (li.media.ebookFile && /epub/i.test(li.media.ebookFile.ebookFormat || '')))),
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
    } catch (e) { /* no time left */ }
    return null;
  }

  function currentTime(vm) {
    const ph = vm.playerHandler;
    try { return ph && ph.player ? ph.getCurrentTime() : (vm.currentTime || 0); } catch (e) { return vm.currentTime || 0; }
  }

  // ---------- synchronizace ----------

  /** Sends the current position to the server and waits for the answer. */
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

  /** Before resuming: take over a newer server position if another device moved it. */
  async function checkBeforeResume(vm) {
    const item = vm.streamLibraryItem;
    const ph = vm.playerHandler;
    if (!item || !ph) return;
    if (Date.now() - watch.sessionAt < 5000) return; // just loaded with the start position
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

  /** Wraps the player's play/playPause with the server check (once per PlayerHandler instance). */
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

  /** Paused: a position from another device (socket updates the store) is taken over at once. */
  function followWhilePaused(vm) {
    const item = vm.streamLibraryItem;
    const ph = vm.playerHandler;
    if (!item || !ph || !ph.player) return;
    let p = null;
    try { p = store().getters['user/getUserMediaProgress'](item.id, vm.streamEpisode ? vm.streamEpisode.id : null); } catch (e) {}
    if (!p || typeof p.currentTime !== 'number' || !p.lastUpdate) return;
    if (p.lastUpdate <= watch.pausedAt + 1500) return; // a change from before the pause (or our own)
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

  // ---------- state for the mini player / remote devices ----------

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
        // pause and wait until the server has saved the position (handoff to another device)
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
        // pause, save the position and close the session (playback continues elsewhere)
        return (async () => {
          if (!vm.streamLibraryItem) return null;
          const id = vm.streamLibraryItem.id;
          if (vm.isPlaying) vm.playPause();
          const time = currentTime(vm);
          watch.playing = false;
          // the other device can start right away; saving the position and closing the session finish here in the background
          (async () => {
            await new Promise((r) => setTimeout(r, 250));
            try { await syncNow(vm); } catch (e) {}
            try { vm.closePlayer(); } catch (e) {}
            inProgressCache.at = 0;
          })();
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
          ui.playbackRate = r; // also updates the web controls and saves it to the user settings
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
          vc.volume = v; // same path as the web's slider (also saved to localStorage)
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
      case 'readerInfo':
        // e-book reader: audio chapters (for sync), listening and reading position, auth for the download
        return (async () => {
          const li = await axios().$get('/api/items/' + arg.id + '?expanded=1');
          let p = null;
          try { p = await axios().$get('/api/me/progress/' + arg.id); } catch (e) { p = null; }
          const m = li.media || {};
          const ext = String((m.ebookFile && (m.ebookFile.ebookFormat || (m.ebookFile.metadata && m.ebookFile.metadata.ext))) || m.ebookFormat || '').replace(/^\./, '').toLowerCase();
          return {
            id: li.id,
            title: (m.metadata && m.metadata.title) || '',
            ext,
            duration: m.duration || 0,
            chapters: (m.chapters || []).map((c) => ({ start: c.start, end: c.end, title: c.title })),
            currentTime: p ? p.currentTime || 0 : 0,
            ebookLocation: p ? p.ebookLocation || null : null,
            ebookProgress: p ? p.ebookProgress || null : null,
            url: new URL(basePath() + '/api/items/' + li.id + '/ebook', location.origin).href,
            token: token()
          };
        })();
      case 'saveEbook':
        return axios().$patch('/api/me/progress/' + arg.id, Object.assign({ ebookLocation: arg.cfi }, arg.progress ? { ebookProgress: arg.progress } : {}))
          .then(() => true, () => false);
      case 'chapters':
        return (vm.chapters || []).map((c, i) => ({ n: i + 1, title: c.title, start: c.start, end: c.end }));
      case 'playItem': {
        if (!arg || !arg.id) return false;
        // the same book is already loaded (paused): ABS would not start it again – seek and play
        if (vm.streamLibraryItem && vm.streamLibraryItem.id === arg.id && !arg.episodeId && vm.playerHandler && vm.playerHandler.player) {
          if (typeof arg.startTime === 'number' && isFinite(arg.startTime)) {
            vm.seek(Math.max(0, arg.startTime));
          }
          if (!vm.isPlaying) vm.playPause();
          return true;
        }
        const payload = { libraryItemId: arg.id, episodeId: arg.episodeId || null };
        if (typeof arg.startTime === 'number' && isFinite(arg.startTime)) payload.startTime = arg.startTime;
        watch.sessionAt = Date.now(); // the start position is intended, do not check it against the server
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
