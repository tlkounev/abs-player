const { app, BrowserWindow, Tray, Menu, ipcMain, screen, shell, nativeImage, session, Notification, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const crypto = require('crypto');
const I18N = require('./i18n.js');
const remote = require('./remote.js');

const APP_NAME = 'ABS Player';
const ASSETS = path.join(__dirname, '..', 'assets');
const BRIDGE = fs.readFileSync(path.join(__dirname, 'bridge.js'), 'utf8');
const CLICK_DELAY = 280; // ms – odlišení jednoho kliknutí od dvojkliku
const POPUP_W = 380;
const POPUP_H = 236;
const LIST_H = 300;      // výška seznamu kapitol (musí odpovídat listHeight v popup-preload.js)

app.setAppUserModelId('local.abs-player');

// ---------- jediná instance ----------
if (!app.requestSingleInstanceLock()) {
  app.quit();
  process.exit(0);
}

// ---------- konfigurace ----------
// Přechod z dřívějšího názvu aplikace: převzít nastavení i přihlášení k ABS (cookies webu).
(function migrateOldData() {
  try {
    const newDir = app.getPath('userData');
    const oldDir = path.join(app.getPath('appData'), 'Audiobookshelf Tray');
    if (fs.existsSync(path.join(newDir, 'config.json')) || !fs.existsSync(path.join(oldDir, 'config.json'))) return;
    fs.mkdirSync(newDir, { recursive: true });
    fs.copyFileSync(path.join(oldDir, 'config.json'), path.join(newDir, 'config.json'));
    const part = path.join(oldDir, 'Partitions');
    if (fs.existsSync(part)) fs.cpSync(part, path.join(newDir, 'Partitions'), { recursive: true });
  } catch (e) { /* bez migrace – jen se znovu zadá server */ }
})();
const configPath = path.join(app.getPath('userData'), 'config.json');
function loadConfig() {
  try { return JSON.parse(fs.readFileSync(configPath, 'utf8')); } catch (e) { return {}; }
}
function saveConfig(cfg) {
  fs.mkdirSync(path.dirname(configPath), { recursive: true });
  fs.writeFileSync(configPath, JSON.stringify(cfg, null, 2));
}
let config = loadConfig();
if (!config.deviceId) { config.deviceId = crypto.randomUUID(); saveConfig(config); }
if (!Array.isArray(config.paired)) config.paired = [];
if (!Array.isArray(config.receiverSecrets)) config.receiverSecrets = [];

let notifyIcon = null;
let mainWin = null;
let popupWin = null;
let setupWin = null;
let clickTimer = null;
let lastState = null;
let savePosTimer = null;
let listDir = null;      // null = sbaleno, 'up' / 'down' = směr, kterým okno naroslo
let listExtra = 0;      // o kolik je okno teď vyšší (seznam kapitol / Přehrávat na)

app.on('second-instance', () => showMain());

// ---------- jazyk ----------
function lang() {
  return config.lang || I18N.normalize(app.getLocale());
}
const t = (key, vars) => I18N.tr(lang(), key, vars);

function setLang(code) {
  config.lang = I18N.normalize(code);
  saveConfig(config);
  for (const w of BrowserWindow.getAllWindows()) {
    if (w !== mainWin) w.webContents.send('i18n:changed', config.lang);
  }
  updateTooltip();
}

ipcMain.on('i18n:get', (e) => { e.returnValue = lang(); });
ipcMain.on('i18n:set', (_e, code) => setLang(code));

// ---------- hlavní okno (celý web ABS) ----------
function createMain(showOnReady) {
  const b = config.bounds || {};
  mainWin = new BrowserWindow({
    width: b.width || 1280,
    height: b.height || 860,
    x: b.x,
    y: b.y,
    minWidth: 420,
    minHeight: 500,
    show: false,
    title: APP_NAME,
    icon: path.join(ASSETS, 'app.ico'),
    backgroundColor: '#232323',
    autoHideMenuBar: true,
    webPreferences: {
      contextIsolation: true,
      sandbox: true,
      backgroundThrottling: false, // přehrávání a synchronizace i se skrytým oknem
      autoplayPolicy: 'no-user-gesture-required', // ovládání z lišty bez kliknutí do stránky
      partition: 'persist:abs'
    }
  });
  mainWin.setMenuBarVisibility(false);
  if (config.maximized) mainWin.maximize();

  mainWin.loadURL(config.serverUrl);

  const inject = () => mainWin.webContents.executeJavaScript(BRIDGE, true).catch(() => {});
  mainWin.webContents.on('did-finish-load', inject);
  mainWin.webContents.on('did-navigate-in-page', inject);

  mainWin.webContents.on('did-fail-load', (_e, code, desc, url, isMainFrame) => {
    if (!isMainFrame || code === -3) return; // -3 = přerušeno (běžné při navigaci)
    mainWin.loadFile(path.join(__dirname, 'offline.html'), { query: { url: config.serverUrl, err: desc, lang: lang() } });
  });

  // Odkazy mimo server otevírat ve výchozím prohlížeči
  const serverOrigin = new URL(config.serverUrl).origin;
  mainWin.webContents.setWindowOpenHandler(({ url }) => {
    if (!url.startsWith(serverOrigin)) shell.openExternal(url);
    return { action: 'deny' };
  });
  mainWin.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith(serverOrigin) && !url.startsWith('file:')) {
      e.preventDefault();
      shell.openExternal(url);
    }
  });

  // Zavření = schovat do lišty
  mainWin.on('close', (e) => {
    saveBounds();
    if (!app.isQuitting) {
      e.preventDefault();
      mainWin.hide();
    }
  });

  if (showOnReady) mainWin.once('ready-to-show', () => mainWin.show());
}

function saveBounds() {
  if (!mainWin || mainWin.isDestroyed()) return;
  config.maximized = mainWin.isMaximized();
  if (!config.maximized && !mainWin.isMinimized()) config.bounds = mainWin.getBounds();
  saveConfig(config);
}

function showMain() {
  if (!config.serverUrl) return showSetup();
  if (!mainWin || mainWin.isDestroyed()) createMain(false);
  if (mainWin.isMinimized()) mainWin.restore();
  mainWin.show();
  mainWin.focus();
}

// ---------- minipřehrávač ----------
// Zůstává otevřený, dokud ho uživatel neschová (klik na ikonu v liště nebo tlačítko „_“).
// Dá se přetáhnout kamkoliv, pozice se pamatuje.
function createPopup() {
  popupWin = new BrowserWindow({
    width: POPUP_W,
    height: POPUP_H,
    show: false,
    frame: false,
    resizable: false,
    movable: true,
    minimizable: false,
    maximizable: false,
    fullscreenable: false,
    skipTaskbar: true,
    alwaysOnTop: true,
    transparent: true,
    hasShadow: false,
    webPreferences: {
      preload: path.join(__dirname, 'popup-preload.js'),
      contextIsolation: true,
      sandbox: true
    }
  });
  popupWin.loadFile(path.join(__dirname, 'popup.html'));
  popupWin.on('moved', () => {
    clearTimeout(savePosTimer);
    savePosTimer = setTimeout(() => {
      if (!popupWin || popupWin.isDestroyed()) return;
      const [x, y] = popupWin.getPosition();
      // ukládá se vždy pozice sbaleného okna
      config.popupPos = { x, y: listDir === 'up' ? y + (listExtra || LIST_H) : y };
      saveConfig(config);
    }, 300);
  });
  // Zavření (např. Alt+F4) jen schová
  popupWin.on('close', (e) => {
    if (!app.isQuitting) { e.preventDefault(); hidePopup(); }
  });
}

function savedPopupPosValid() {
  const p = config.popupPos;
  if (!p || typeof p.x !== 'number' || typeof p.y !== 'number') return false;
  // aspoň střed okna musí být na některém z připojených monitorů
  const cx = p.x + POPUP_W / 2, cy = p.y + POPUP_H / 2;
  return screen.getAllDisplays().some(({ workArea: w }) => cx >= w.x && cx < w.x + w.width && cy >= w.y && cy < w.y + w.height);
}

function iconPopupPos() {
  const tb = notifyIcon.getBounds();
  const pt = { x: Math.round(tb.x + tb.width / 2), y: Math.round(tb.y + tb.height / 2) };
  const disp = screen.getDisplayNearestPoint(pt);
  const wa = disp.workArea;
  const b = disp.bounds;
  const gap = 10;
  let x, y;
  if (wa.y + wa.height < b.y + b.height) {        // lišta dole
    x = pt.x - POPUP_W / 2;
    y = wa.y + wa.height - POPUP_H - gap;
  } else if (wa.y > b.y) {                          // nahoře
    x = pt.x - POPUP_W / 2;
    y = wa.y + gap;
  } else if (wa.x > b.x) {                          // vlevo
    x = wa.x + gap;
    y = pt.y - POPUP_H / 2;
  } else {                                          // vpravo / skrytá lišta
    x = wa.x + wa.width - POPUP_W - gap;
    y = (pt.y > wa.y + wa.height / 2) ? wa.y + wa.height - POPUP_H - gap : pt.y - POPUP_H / 2;
  }
  x = Math.min(Math.max(x, wa.x + gap), wa.x + wa.width - POPUP_W - gap);
  y = Math.min(Math.max(y, wa.y + gap), wa.y + wa.height - POPUP_H - gap);
  return { x: Math.round(x), y: Math.round(y) };
}

function showPopup() {
  if (!config.serverUrl) return showSetup();
  if (!popupWin || popupWin.isDestroyed()) createPopup();
  const pos = savedPopupPosValid() ? config.popupPos : iconPopupPos();
  listDir = null;
  listExtra = 0;
  popupWin.setBounds({ x: pos.x, y: pos.y, width: POPUP_W, height: POPUP_H });
  popupWin.webContents.send('popup-shown');
  popupWin.show();
  popupWin.focus();
}

function hidePopup() {
  if (popupWin && !popupWin.isDestroyed() && popupWin.isVisible()) popupWin.hide();
}

function togglePopup() {
  if (popupWin && !popupWin.isDestroyed() && popupWin.isVisible()) hidePopup();
  else showPopup();
}

// ---------- nastavení serveru ----------
function showSetup() {
  if (setupWin && !setupWin.isDestroyed()) return setupWin.focus();
  setupWin = new BrowserWindow({
    width: 500,
    height: 340,
    resizable: false,
    maximizable: false,
    title: APP_NAME,
    icon: path.join(ASSETS, 'app.ico'),
    autoHideMenuBar: true,
    backgroundColor: '#232323',
    webPreferences: {
      preload: path.join(__dirname, 'setup-preload.js'),
      contextIsolation: true,
      sandbox: true
    }
  });
  setupWin.setMenuBarVisibility(false);
  setupWin.loadFile(path.join(__dirname, 'setup.html'), { query: { url: config.serverUrl || '' } });
  setupWin.on('closed', () => { setupWin = null; });
}

ipcMain.handle('setup:save', (_e, url) => {
  let u;
  try {
    u = new URL(/^https?:\/\//i.test(url) ? url : 'http://' + url);
  } catch (e) {
    return { ok: false, error: t('setupInvalid') };
  }
  config.serverUrl = u.href.replace(/\/+$/, '');
  saveConfig(config);
  if (mainWin && !mainWin.isDestroyed()) {
    app.isQuitting = true; mainWin.destroy(); app.isQuitting = false;
    mainWin = null;
  }
  createMain(true);
  if (setupWin) setupWin.close();
  return { ok: true };
});

// ---------- komunikace popup ↔ stránka ABS ----------
async function pageExec(code) {
  if (!mainWin || mainWin.isDestroyed()) return null;
  const wc = mainWin.webContents;
  if (wc.isLoading() && !wc.getURL().startsWith(config.serverUrl)) return null;
  try {
    await wc.executeJavaScript(BRIDGE, true);
    return await wc.executeJavaScript(code, true);
  } catch (e) {
    return null;
  }
}

const localState = () => pageExec('window.__absPlayer ? window.__absPlayer.state() : null');
const localCmd = (name, arg) => pageExec(`window.__absPlayer ? window.__absPlayer.cmd(${JSON.stringify(name)}, ${JSON.stringify(arg ?? null)}) : false`);

// ---------- cíl přehrávání: toto PC nebo spárované zařízení ----------
let currentTarget = 'local';
let lastRemoteChapters = [];
const remoteCovers = new Map();
const deviceName = () => config.deviceName || os.hostname();

function pairedDevice(id) {
  return config.paired.find((d) => d.deviceId === id) || null;
}

function targetInfo() {
  const d = currentTarget === 'local' ? null : pairedDevice(currentTarget);
  if (!d) return { id: 'local', name: deviceName(), kind: 'pc', remote: false };
  return { id: d.deviceId, name: d.name, kind: d.kind || 'tv', remote: true };
}

async function coverFor(itemId) {
  if (!itemId) return null;
  if (remoteCovers.has(itemId)) return remoteCovers.get(itemId);
  const c = await localCmd('cover', itemId);
  if (c) {
    remoteCovers.set(itemId, c);
    if (remoteCovers.size > 20) remoteCovers.delete(remoteCovers.keys().next().value);
  }
  return c || null;
}

async function getState() {
  const target = targetInfo();
  let st;
  if (!target.remote) {
    st = await localState();
    if (st) st.target = target;
  } else {
    const d = pairedDevice(target.id);
    try {
      const np = await remote.remoteState(d);
      learnMac(d);
      if (waking && waking.id === d.deviceId) wakeSucceeded(d);
      if (np && np.itemId) {
        lastRemoteItem = { id: np.itemId, title: np.title, deviceId: d.deviceId };
        st = remote.nowPlayingToPopup(np, { target, cover: await coverFor(np.itemId) });
        lastRemoteChapters = st.chaptersList || [];
      } else {
        const loc = await localState();
        st = { ready: true, loggedIn: true, item: null, inProgress: (loc && loc.inProgress) || [], target };
      }
    } catch (e) {
      const loc = await localState();
      st = {
        ready: true, loggedIn: true, item: null, inProgress: (loc && loc.inProgress) || [], target, remoteError: true,
        waking: waking && waking.id === d.deviceId ? Math.max(0, Math.ceil((waking.until - Date.now()) / 1000)) : null,
        lastTitle: lastRemoteItem && lastRemoteItem.deviceId === d.deviceId ? lastRemoteItem.title : null
      };
    }
  }
  lastState = st;
  updateTooltip();
  return st;
}

async function routedCmd(name, arg) {
  if (name === 'recent') return localCmd('recent'); // seznam „Pokračovat v poslechu“ je vždy z účtu ABS
  const target = targetInfo();
  if (!target.remote) return localCmd(name, arg);
  const d = pairedDevice(target.id);
  if (name === 'chapters') return lastRemoteChapters;
  const cmd = remote.popupCmdToRemote(name, arg);
  if (!cmd) return false;
  try {
    const np = await remote.remoteCmd(d, cmd);
    if (np && np.chapters) lastRemoteChapters = remote.nowPlayingToPopup(np, {}).chaptersList || [];
    return true;
  } catch (e) {
    return false;
  }
}

/**
 * Přepnutí cíle. Když se něco přehrává, pozastaví se, pozice se uloží na server a přehrávání
 * pokračuje na novém cíli přesně odtud (stejně jako v aplikaci ABS Player).
 */
async function selectTarget(id) {
  if (id !== 'local' && !pairedDevice(id)) return false;
  if (id === currentTarget) return true;
  const from = targetInfo();
  let hand = null;
  if (!from.remote) {
    const st = await localState();
    // kniha se přesune i pozastavená – poslech pokračuje na novém zařízení
    if (st && st.item) hand = await localCmd('handoff');
  } else if (!(lastState && lastState.remoteError)) { // vypnuté zařízení nezdržovat
    try {
      const d = pairedDevice(from.id);
      const np = await withTimeout(remote.remoteState(d), 1500);
      if (np && np.itemId) {
        const after = await withTimeout(remote.remoteCmd(d, { action: 'pause' }), 3000);
        hand = { id: np.itemId, time: (after && after.positionSec) || np.positionSec };
      }
    } catch (e) { /* zdroj nedostupný – jen přepnout */ }
  }
  cancelWaking();
  currentTarget = id;
  if (id === 'local') {
    if (hand && hand.id) await localCmd('playItem', { id: hand.id, startTime: hand.time });
    return true;
  }
  const d = pairedDevice(id);
  const online = await withTimeout(remote.remoteState(d), 3000).then(() => true, () => false);
  if (online) {
    if (hand && hand.id) await remote.remoteCmd(d, { action: 'playItem', itemId: hand.id, value: hand.time }).catch(() => {});
  } else {
    startWaking(d, hand, from.id);
  }
  return true;
}

function withTimeout(p, ms) {
  return Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), ms))]);
}

// ---------- vypnutá TV: probuzení (Wake-on-LAN) a návrat sem, když se nezapne ----------
const WAKE_MS = 30000;
let waking = null; // { id, until, hand, back, timer }
let lastRemoteItem = null;

function startWaking(d, hand, backId) {
  sendWakeOnLan(d);
  waking = { id: d.deviceId, until: Date.now() + WAKE_MS, hand, back: backId || 'local' };
  waking.timer = setInterval(async () => {
    if (!waking) return;
    try {
      await withTimeout(remote.remoteState(d), 1500);
      wakeSucceeded(d);
    } catch (e) {
      if (Date.now() < waking.until) { if ((waking.until - Date.now()) % 10000 < 2000) sendWakeOnLan(d); return; }
      const w = waking;
      cancelWaking();
      if (currentTarget !== w.id) return;
      currentTarget = 'local';
      if (w.hand && w.hand.id) await localCmd('playItem', { id: w.hand.id, startTime: w.hand.time });
      notice(APP_NAME, t('wakeFailed', { name: d.name }));
    }
  }, 2000);
}

function wakeSucceeded(d) {
  const w = waking;
  cancelWaking();
  if (w && w.hand && w.hand.id && currentTarget === d.deviceId) {
    remote.remoteCmd(d, { action: 'playItem', itemId: w.hand.id, value: w.hand.time }).catch(() => {});
  }
}

function cancelWaking() {
  if (waking && waking.timer) clearInterval(waking.timer);
  waking = null;
}

/** Magic packet na MAC adresu zařízení (zjištěnou z ARP, když bylo zapnuté). */
function sendWakeOnLan(d) {
  if (!d || !d.mac) return;
  try {
    const mac = Buffer.from(d.mac.replace(/[^0-9a-f]/gi, ''), 'hex');
    if (mac.length !== 6) return;
    const pkt = Buffer.alloc(102, 0xff);
    for (let i = 0; i < 16; i++) mac.copy(pkt, 6 + i * 6);
    const sock = require('dgram').createSocket('udp4');
    sock.bind(() => {
      sock.setBroadcast(true);
      const targets = ['255.255.255.255'];
      if (/^\d+\.\d+\.\d+\.\d+$/.test(d.host)) targets.push(d.host.replace(/\.\d+$/, '.255'));
      let left = targets.length * 2;
      const done = () => { if (--left <= 0) sock.close(); };
      for (const h of targets) for (const port of [9, 7]) sock.send(pkt, port, h, done);
    });
  } catch (e) { /* bez probuzení */ }
}

/** MAC adresa z ARP tabulky Windows – jen jednou za běh pro každé zařízení. */
const macTried = new Set();
function learnMac(d) {
  if (!d || process.platform !== 'win32' || macTried.has(d.deviceId)) return;
  macTried.add(d.deviceId);
  require('child_process').execFile('arp', ['-a', d.host], { windowsHide: true }, (err, out) => {
    if (err || !out) return;
    const m = String(out).match(/([0-9a-f]{2}[-:]){5}[0-9a-f]{2}/i);
    if (!m) return;
    const mac = m[0].toLowerCase().replace(/-/g, ':');
    const p = pairedDevice(d.deviceId);
    if (p && p.mac !== mac) { p.mac = mac; saveConfig(config); }
  });
}

ipcMain.handle('devices:playHere', async () => {
  cancelWaking();
  const it = lastRemoteItem;
  currentTarget = 'local';
  if (it && it.id) await localCmd('playItem', { id: it.id }); // pozice ze serveru
  return true;
});

ipcMain.handle('abs:state', () => getState());
ipcMain.handle('abs:cmd', (_e, name, arg) => routedCmd(name, arg));

// ---------- přijímač: telefon (nebo jiné PC) může přehrávání poslat sem ----------
const pairingCode = remote.newCode();

async function localNowPlaying() {
  const st = await localState();
  const chapters = st && st.item ? await localCmd('chapters') : [];
  return remote.popupToNowPlaying(st, Array.isArray(chapters) ? chapters : []);
}

async function receiverCommand(c) {
  const v = typeof c.value === 'number' ? c.value : null;
  switch (c.action) {
    case 'play': await localCmd('play'); break;
    case 'pause': await localCmd('pauseSync'); break;
    case 'toggle': await localCmd('playPause'); break;
    case 'seek': if (v != null) await localCmd('seek', v); break;
    case 'skip': if (v != null) await localCmd('skip', v); break;
    case 'chapter': {
      const chs = await localCmd('chapters');
      const ch = Array.isArray(chs) && v != null ? chs[Math.round(v)] : null;
      if (ch) await localCmd('seek', ch.start);
      break;
    }
    case 'next': await localCmd('nextChapter'); break;
    case 'prev': await localCmd('prevChapter'); break;
    case 'speed': if (v != null) await localCmd('rate', v); break;
    case 'volume': if (v != null) await localCmd('volume', v); break;
    case 'mute': await localCmd('mute'); break;
    case 'sleep': break; // časovač webového přehrávače se odsud nastavit nedá
    case 'playItem':
      if (!c.itemId) break;
      currentTarget = 'local'; // přehrávání se přesunulo sem
      await localCmd('playItem', { id: c.itemId, startTime: v });
      break;
    case 'stop': await localCmd('handoff'); break;
    default: throw new Error('Neznámý příkaz ' + c.action);
  }
  await new Promise((r) => setTimeout(r, c.action === 'playItem' ? 1500 : 300));
  return localNowPlaying();
}

const receiver = remote.createReceiver({
  deviceId: config.deviceId,
  name: () => deviceName(),
  code: () => pairingCode,
  getSecrets: () => config.receiverSecrets.map((s) => s.secret),
  addSecret: (secret, from) => {
    config.receiverSecrets.push({ secret, name: from || '', at: Date.now() });
    saveConfig(config);
  },
  info: async () => {
    const u = await localCmd('user');
    return { loggedIn: !!u, server: config.serverUrl || null, username: (u && u.username) || null };
  },
  state: localNowPlaying,
  command: receiverCommand
});

const discovery = remote.createDiscovery({
  deviceId: config.deviceId,
  name: deviceName(),
  onChange: () => {
    // IP adresy se mění (DHCP): aktualizovat spárovaná zařízení podle mDNS
    let changed = false;
    for (const f of discovery.list()) {
      const p = config.paired.find((d) => d.deviceId && d.deviceId === f.deviceId);
      if (p && f.host && (p.host !== f.host || p.port !== f.port)) {
        // záznam mDNS může být starý (jiný port) – převzít jen to, co opravdu odpovídá
        remote.locate(f.host, f.port, p.deviceId).then((hit) => {
          if (hit && (p.host !== f.host || p.port !== hit.port)) { p.host = f.host; p.port = hit.port; saveConfig(config); }
        });
      }
    }
  }
});

const lastLocate = new Map();

ipcMain.handle('devices:get', async () => {
  discovery.refresh();
  const paired = await Promise.all(config.paired.map(async (d) => {
    try {
      const np = await remote.remoteState(d).catch(async (e) => {
        // přijímač mohl přejít na jiný port (8766, když 8765 drží něco jiného)
        if (e.status || Date.now() - (lastLocate.get(d.deviceId) || 0) < 20000) throw e;
        lastLocate.set(d.deviceId, Date.now());
        const hit = await remote.locate(d.host, d.port, d.deviceId);
        if (!hit || hit.port === d.port) throw e;
        d.port = hit.port;
        saveConfig(config);
        return remote.remoteState(d);
      });
      return { id: d.deviceId, name: d.name, kind: d.kind || 'tv', host: d.host, online: true, title: np.itemId ? np.title : null, isPlaying: !!np.isPlaying };
    } catch (e) {
      return { id: d.deviceId, name: d.name, kind: d.kind || 'tv', host: d.host, online: false };
    }
  }));
  const discovered = discovery.list()
    .filter((f) => !config.paired.some((p) => p.deviceId && p.deviceId === f.deviceId))
    .map((f) => ({ name: f.name, kind: f.kind, host: f.host, port: f.port }));
  return { current: currentTarget, local: { id: 'local', name: deviceName() }, paired, discovered, code: pairingCode, port: receiver.port };
});

ipcMain.handle('devices:select', (_e, id) => selectTarget(id));

ipcMain.handle('devices:info', async (_e, host, port) => {
  try {
    const hit = await remote.locate(host, port);
    if (!hit) return { ok: false };
    const info = hit.info;
    const u = await localCmd('user');
    return { ok: true, port: hit.port, name: info.name, kind: info.kind || 'tv', needsPassword: remote.needsPassword(info, config.serverUrl, u && u.username) };
  } catch (e) {
    return { ok: false };
  }
});

ipcMain.handle('devices:pair', async (_e, p) => {
  try {
    const u = await localCmd('user');
    if (!u) return { ok: false, error: 'login' };
    const d = await remote.pair({
      host: p.host, port: Number(p.port) || remote.DEFAULT_PORT, code: p.code, password: p.password,
      serverUrl: config.serverUrl, username: u.username, userId: u.userId, myName: deviceName()
    });
    config.paired = config.paired.filter((x) => x.deviceId !== d.deviceId).concat(d);
    saveConfig(config);
    return { ok: true, id: d.deviceId };
  } catch (e) {
    return { ok: false, error: e.code || e.message };
  }
});

// přejmenování: tento počítač (posílá se i ostatním zařízením) nebo spárované zařízení (jen tady)
ipcMain.handle('devices:rename', (_e, id, name) => {
  const n = String(name || '').trim().slice(0, 60);
  if (!n) return false;
  if (id === 'local') config.deviceName = n;
  else {
    const d = config.paired.find((x) => x.deviceId === id);
    if (!d) return false;
    d.name = n;
  }
  saveConfig(config);
  return true;
});

ipcMain.handle('devices:unpair', (_e, id) => {
  if (currentTarget === id) currentTarget = 'local';
  config.paired = config.paired.filter((d) => d.deviceId !== id);
  saveConfig(config);
  return true;
});
ipcMain.on('popup:open-main', () => showMain());
ipcMain.on('popup:hide', () => hidePopup());

// seznam kapitol: okno naroste nahoru (pokud je nad ním místo), jinak dolů; sbalení vrátí původní polohu
// h = o kolik okno naroste (seznam kapitol pevně LIST_H, „Přehrávat na“ podle obsahu)
ipcMain.handle('popup:list', (_e, open, h) => {
  if (!popupWin || popupWin.isDestroyed()) return null;
  const b = popupWin.getBounds();
  const want = Math.round(Math.max(80, Math.min(Number(h) || LIST_H, 560)));
  if (open && !listDir) {
    const wa = screen.getDisplayMatching(b).workArea;
    listDir = b.y - want >= wa.y ? 'up' : 'down';
    listExtra = want;
    popupWin.setBounds({ x: b.x, y: listDir === 'up' ? b.y - want : b.y, width: POPUP_W, height: POPUP_H + want });
  } else if (open && listDir && want !== listExtra) {
    // už otevřené, jen jiná výška (rozbalené párování): okraj u minipřehrávače zůstane na místě
    popupWin.setBounds({ x: b.x, y: listDir === 'up' ? b.y + listExtra - want : b.y, width: POPUP_W, height: POPUP_H + want });
    listExtra = want;
  } else if (!open && listDir) {
    popupWin.setBounds({ x: b.x, y: listDir === 'up' ? b.y + listExtra : b.y, width: POPUP_W, height: POPUP_H });
    listDir = null;
    listExtra = 0;
  }
  return listDir;
});
// poloha kurzoru vůči obsahu minipřehrávače (DIP = CSS px)
ipcMain.handle('popup:cursor', () => {
  if (!popupWin || popupWin.isDestroyed()) return null;
  const p = screen.getCursorScreenPoint();
  const b = popupWin.getContentBounds();
  return { x: p.x - b.x, y: p.y - b.y };
});

// ---------- ikona v oznamovací oblasti ----------
function updateTooltip() {
  if (!notifyIcon) return;
  let tip = APP_NAME;
  if (lastState && lastState.item) {
    tip = (lastState.isPlaying ? '▶ ' : '❚❚ ') + lastState.item.title;
    if (lastState.item.author) tip += '\n' + lastState.item.author;
    if (lastState.target && lastState.target.remote) tip += '\n→ ' + lastState.target.name;
  }
  notifyIcon.setToolTip(tip.slice(0, 127));
}

function flagIcon(code) {
  const img = nativeImage.createFromPath(path.join(ASSETS, 'flags', code + '.png'));
  return img.isEmpty() ? undefined : img;
}

// ---------- aktualizace ----------
// electron-updater čte latest.yml z vydání „win-latest“ na GitHubu (adresa je v app-update.yml,
// který vytvoří electron-builder z build.publish). Nic se nestahuje bez souhlasu: když je novější
// verze, ukáže se oznámení „Je k dispozici verze …“; klik = stáhnout, nainstalovat a restartovat.
const UPDATE_EVERY = 6 * 60 * 60 * 1000;
// idle | checking | none | available | downloading | ready | error
const upd = { status: 'idle', version: null, error: null, offered: null };
let autoUpdater = null;
let manualCheck = false;

function setupUpdater() {
  if (!app.isPackaged) return; // spuštění z vývojového prostředí
  try { ({ autoUpdater } = require('electron-updater')); } catch (e) { return; }
  autoUpdater.autoDownload = false; // stahovat až po kliknutí
  autoUpdater.autoInstallOnAppQuit = false;
  autoUpdater.on('checking-for-update', () => { upd.status = 'checking'; });
  autoUpdater.on('update-not-available', () => { upd.status = 'none'; reportManual(); });
  autoUpdater.on('update-available', (info) => {
    upd.status = 'available';
    upd.version = info.version;
    // automatická kontrola nabídne stejnou verzi jen jednou za běh, ruční vždy
    if (manualCheck || upd.offered !== info.version) offerUpdate();
    manualCheck = false;
  });
  autoUpdater.on('update-downloaded', (info) => {
    upd.status = 'ready';
    upd.version = info.version;
    installUpdate();
  });
  autoUpdater.on('error', (e) => {
    const wasDownloading = upd.status === 'downloading';
    upd.status = 'error';
    upd.error = String((e && e.message) || e).split('\n')[0].slice(0, 160);
    if (wasDownloading) manualCheck = true;
    reportManual();
  });
  checkUpdates(false);
  setInterval(() => checkUpdates(false), UPDATE_EVERY);
}

function checkUpdates(manual) {
  if (!autoUpdater) return;
  if (upd.status === 'checking' || upd.status === 'downloading' || upd.status === 'ready') return;
  manualCheck = manual;
  autoUpdater.checkForUpdates().catch(() => { /* hlásí se přes 'error' */ });
}

// Oznámení Windows musí zůstat v paměti, dokud nezmizí – jinak se klik na něj ztratí.
const liveNotices = new Set();
function notice(title, body, onClick) {
  if (!Notification.isSupported()) return;
  const n = new Notification({ title, body, icon: path.join(ASSETS, 'app.ico') });
  liveNotices.add(n);
  const done = () => liveNotices.delete(n);
  n.on('close', done);
  n.on('click', () => { done(); if (onClick) onClick(); });
  n.show();
}

function offerUpdate() {
  upd.offered = upd.version;
  notice(t('updAvailable', { v: upd.version }), t('updClickInstall'), downloadAndInstall);
}

/** Po souhlasu: stáhnout, nainstalovat (tiše) a aplikaci znovu spustit. */
function downloadAndInstall() {
  if (!autoUpdater || upd.status !== 'available') return;
  upd.status = 'downloading';
  autoUpdater.downloadUpdate().catch(() => { /* hlásí se přes 'error' */ });
}

function reportManual() {
  if (!manualCheck) return;
  manualCheck = false;
  if (upd.status === 'none') notice(APP_NAME, t('updNone', { v: app.getVersion() }));
  else if (upd.status === 'error') notice(APP_NAME, t('updError', { t: upd.error }));
}

function installUpdate() {
  if (!autoUpdater || upd.status !== 'ready') return;
  app.isQuitting = true;
  autoUpdater.quitAndInstall(true, true); // tiše a po instalaci znovu spustit
}

function updateMenuItems() {
  const items = [{ label: APP_NAME + ' ' + app.getVersion(), enabled: false }];
  if (upd.status === 'available') {
    items.push({ label: t('updInstall', { v: upd.version }), click: downloadAndInstall });
  } else if (upd.status === 'downloading' || upd.status === 'ready') {
    items.push({ label: t('updDownloading', { v: upd.version }), enabled: false });
  } else {
    items.push({
      label: upd.status === 'checking' ? t('updChecking') : t('updCheck'),
      enabled: upd.status !== 'checking' && !!autoUpdater,
      click: () => checkUpdates(true)
    });
  }
  items.push({ type: 'separator' });
  return items;
}

function buildMenu() {
  const login = app.getLoginItemSettings({ args: ['--hidden'] });
  const cur = lang();
  return Menu.buildFromTemplate([
    ...updateMenuItems(),
    { label: t('menuOpen'), click: () => showMain() },
    { label: t('menuPlayPause'), click: () => routedCmd('playPause') },
    {
      label: t('playOn'),
      submenu: [{ id: 'local', name: deviceName() }].concat(config.paired.map((d) => ({ id: d.deviceId, name: d.name })))
        .map((d) => ({ label: d.name, type: 'checkbox', checked: d.id === currentTarget, click: () => selectTarget(d.id) }))
        .concat([{ type: 'separator' }, { label: t('pairCodeThis', { code: pairingCode }), enabled: false }])
    },
    { type: 'separator' },
    { label: t('menuChangeServer'), click: () => showSetup() },
    {
      label: t('menuLanguage'),
      icon: flagIcon(cur),
      submenu: I18N.LANGS.map((l) => ({
        label: l.name,
        type: 'checkbox', // háček u vybraného (radio by kreslilo kroužky)
        checked: l.code === cur,
        icon: flagIcon(l.code),
        click: () => setLang(l.code)
      }))
    },
    {
      label: t('menuAutostart'),
      type: 'checkbox',
      checked: login.openAtLogin,
      click: (mi) => app.setLoginItemSettings({ openAtLogin: mi.checked, args: ['--hidden'] })
    },
    { type: 'separator' },
    { label: t('menuQuit'), click: () => { app.isQuitting = true; app.quit(); } }
  ]);
}

function createNotifyIcon() {
  const icon = nativeImage.createFromPath(path.join(ASSETS, 'notify-icon.ico'));
  notifyIcon = new Tray(icon);
  notifyIcon.setToolTip(APP_NAME);

  notifyIcon.on('click', () => {
    clearTimeout(clickTimer);
    clickTimer = setTimeout(() => { clickTimer = null; togglePopup(); }, CLICK_DELAY);
  });
  notifyIcon.on('double-click', () => {
    clearTimeout(clickTimer);
    clickTimer = null;
    showMain();
  });
  notifyIcon.on('right-click', () => notifyIcon.popUpContextMenu(buildMenu()));
}

// ---------- start ----------
app.whenReady().then(() => {
  // povolení přehrávání zvuku a notifikací pro stránku ABS
  session.fromPartition('persist:abs').setPermissionRequestHandler((_wc, perm, cb) => {
    cb(['media', 'mediaKeySystem', 'notifications', 'clipboard-sanitized-write', 'fullscreen'].includes(perm));
  });

  createNotifyIcon();
  createPopup();

  receiver.listen(remote.DEFAULT_PORT)
    .then((port) => discovery.start(port))
    .catch(() => { /* port obsazený / zakázaný – přijímač nepoběží */ });

  const startHidden = process.argv.includes('--hidden');
  if (!config.serverUrl) showSetup();
  else createMain(!startHidden);

  // tooltip ikony s aktuální knihou
  setInterval(() => { if (!(popupWin && popupWin.isVisible())) getState(); }, 5000);

  setupUpdater();
});

app.on('window-all-closed', () => { /* zůstat v liště */ });
app.on('before-quit', () => {
  app.isQuitting = true;
  saveBounds();
  discovery.stop();
  receiver.close();
});

