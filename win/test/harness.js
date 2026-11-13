// Testy bez ikony v oznamovací oblasti: bridge.js proti maketě přehrávače ABS (mock.html), popup.html a setup.html.
// Spuštění: npm test  (screenshoty se uloží do test/out/)
const { app, BrowserWindow, ipcMain } = require('electron');
const http = require('http');
const fs = require('fs');
const path = require('path');
const SRC = path.join(__dirname, '..', 'src');
const OUT = path.join(__dirname, 'out');
const readerOpened = [];
fs.mkdirSync(OUT, { recursive: true });
const MOCK = path.join(__dirname, 'mock.html');
const BRIDGE = fs.readFileSync(path.join(SRC, 'bridge.js'), 'utf8');
const I18N = require(path.join(SRC, 'i18n.js'));
let authSeen = null;
let lang = 'cs';
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
process.on('unhandledRejection', (e) => {
  console.log('UNHANDLED ' + ((e && e.stack) || e));
  if (global.__results) console.log(global.__results.join('\n'));
  app.exit(2);
});

const coverSvg = '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200"><rect width="200" height="200" fill="#2b5876"/><text x="100" y="115" font-size="40" text-anchor="middle" fill="#fff">42</text></svg>';
http.createServer((req, res) => {
  if (req.url.startsWith('/api/items/li1/cover')) {
    authSeen = req.headers.authorization;
    res.writeHead(200, { 'Content-Type': 'image/svg+xml' });
    return res.end(coverSvg);
  }
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(fs.readFileSync(MOCK));
}).listen(8765);

app.whenReady().then(async () => {
  const main = new BrowserWindow({ show: false, webPreferences: { contextIsolation: true, sandbox: true } });
  await main.loadURL('http://127.0.0.1:8765/');
  const exec = async (code) => { await main.webContents.executeJavaScript(BRIDGE, true); return main.webContents.executeJavaScript(code, true); };
  const results = [];
  global.__results = results;
  const check = (name, cond) => results.push((cond ? 'PASS ' : 'FAIL ') + name);
  const P = 'window.$nuxt.$children[0].$children[1]';

  let st = await exec('window.__absPlayer.state()');
  check('ready+item', st.ready && st.item && st.item.title.startsWith('Stopař'));
  check('cover data url + auth', st.item.cover && st.item.cover.startsWith('data:image/svg') && authSeen === 'Bearer tok');
  check('chapter index', st.chapter && st.chapter.index === 1 && st.chapter.total === 3);
  check('volume state', st.volume === 0.8 && st.muted === false);
  await exec("window.__absPlayer.cmd('volume', 0.35)");
  st = await exec('window.__absPlayer.state()');
  check('set volume', Math.abs(st.volume - 0.35) < 1e-9);
  await exec("window.__absPlayer.cmd('mute')");
  st = await exec('window.__absPlayer.state()');
  check('mute', st.muted === true && st.volume === 0);
  await exec("window.__absPlayer.cmd('mute')");
  st = await exec('window.__absPlayer.state()');
  check('unmute restores', !st.muted && Math.abs(st.volume - 0.35) < 1e-9);
  for (const [c, a] of [['playPause'], ['jumpFwd'], ['jumpBack'], ['seek', 5000], ['rate', 1.5], ['prevChapter'], ['nextChapter'], ['playItem', { id: 'li2' }]]) {
    await exec(`window.__absPlayer.cmd(${JSON.stringify(c)}, ${JSON.stringify(a ?? null)})`);
  }
  const log = (await exec('window.__log')).filter((x) => !x.startsWith('vol:') && x !== 'mute');
  check('cmd log', JSON.stringify(log) === JSON.stringify(['playPause', 'jumpFwd', 'jumpBack', 'seek:5000', 'rate:1.5', 'prevChapter', 'goToNext', 'play-item:{"libraryItemId":"li2","episodeId":null}']));
  await exec(`(() => { const p = ${P}; p.isPlaying = true; p.playerHandler.playerPlaying = true; p.currentTime = 1234; p.currentPlaybackRate = 1.25; p.$refs.audioPlayer.$refs.volumeControl.volume = 0.8; })()`);

  // i18n: všechny jazyky mají všechny texty a vlajku
  check('i18n complete', I18N.LANGS.length === 15 && I18N.LANGS.every((l) => l.flag && Object.keys(I18N.STRINGS[l.code]).length === I18N.KEYS.length));

  let targetOverride = null;
  ipcMain.handle('abs:state', async () => {
    const st = await exec('window.__absPlayer.state()');
    if (st) st.target = targetOverride || { id: 'local', name: 'NB', remote: false };
    return st;
  });
  const devCalls = [];
  ipcMain.handle('devices:get', () => ({
    current: 'local', local: { id: 'local', name: 'NB-2026' },
    paired: [{ id: 'tv1', name: 'Obývák TV', kind: 'tv', host: '10.0.0.5', online: true, title: 'Duna', isPlaying: true },
      { id: 'tv2', name: 'Ložnice TV', kind: 'tv', host: '10.0.0.6', online: false }],
    discovered: [{ name: 'Pracovna PC', kind: 'pc', host: '10.0.0.7', port: 8765 }],
    code: '123456', port: 8765
  }));
  ipcMain.handle('devices:info', (_e, host, port) => ({ ok: host === '10.0.0.7', name: 'Pracovna PC', kind: 'pc', needsPassword: false }));
  ipcMain.handle('devices:pair', (_e, p) => { devCalls.push('pair:' + p.host + ':' + p.port + ':' + p.code); return { ok: true, id: 'pc2' }; });
  ipcMain.handle('devices:select', (_e, id) => { devCalls.push('select:' + id); return true; });
  ipcMain.handle('devices:unpair', (_e, id) => { devCalls.push('unpair:' + id); return true; });
  ipcMain.handle('reader:open', (_e, id) => { readerOpened.push(id); return true; });
  ipcMain.handle('devices:rename', (_e, id, name) => { devCalls.push('rename:' + id + ':' + name); return true; });
  ipcMain.handle('devices:playHere', () => { devCalls.push('playHere'); return true; });
  ipcMain.handle('abs:cmd', (_e, n, a) => exec(`window.__absPlayer.cmd(${JSON.stringify(n)}, ${JSON.stringify(a ?? null)})`));
  ipcMain.on('popup:open-main', () => {});
  ipcMain.on('popup:hide', () => results.push('hide clicked'));
  let fakeCursor = { x: -100, y: -100 };
  ipcMain.handle('popup:cursor', () => fakeCursor);
  let listDir = null;
  ipcMain.handle('popup:list', (_e, open, h) => {
    const b = popup.getBounds();
    const want = Math.round(Math.max(80, Math.min(Number(h) || 300, 560)));
    global.__listH = open ? want : 0;
    if (open && !listDir) { listDir = global.__dir || 'up'; popup.setBounds({ x: b.x, y: b.y, width: 380, height: 236 + want }); }
    else if (open && listDir) popup.setBounds({ x: b.x, y: b.y, width: 380, height: 236 + want });
    else if (!open && listDir) { listDir = null; popup.setBounds({ x: b.x, y: b.y, width: 380, height: 236 }); }
    return listDir;
  });
  ipcMain.on('i18n:get', (e) => { e.returnValue = lang; });
  ipcMain.on('i18n:set', (_e, c) => { lang = c; });
  ipcMain.handle('setup:save', () => ({ ok: false, error: I18N.tr(lang, 'setupInvalid') }));

  const popup = new BrowserWindow({ width: 380, height: 236, show: true, frame: false, transparent: true,
    webPreferences: { preload: path.join(SRC, 'popup-preload.js'), contextIsolation: true, sandbox: true } });
  popup.webContents.on('console-message', (_e, lvl, msg) => results.push('console: ' + msg));
  await popup.loadFile(path.join(SRC, 'popup.html'));
  await wait(1500);
  const shot = async (win, name) => fs.writeFileSync(path.join(OUT, name), (await win.webContents.capturePage()).toPNG());
  await shot(popup, 'p-cs.png');

  const jsP = (c) => popup.webContents.executeJavaScript(c);
  check('no number in jump buttons', await jsP("!document.querySelector('#back .num') && document.getElementById('back').textContent.trim() === ''"));
  check('jump tooltip', await jsP("document.getElementById('back').title") === 'Zpět o 10 s');
  check('chapter label', await jsP("document.getElementById('chLabel').textContent") === 'Kapitola 1/3');
  check('chapter times', await jsP("document.getElementById('chElapsed').textContent + '|' + document.getElementById('chRemaining').textContent") === '20:34|-3:32');
  check('drag region', await jsP("getComputedStyle(document.querySelector('.card')).webkitAppRegion || getComputedStyle(document.querySelector('.card')).getPropertyValue('-webkit-app-region')") === 'drag');

  // Pokračovat v poslechu při přehrávání (tlačítko nahoře)
  await exec(`(() => { const orig = window.$nuxt.$axios.$get; window.$nuxt.$axios.$get = async (url) => url.includes('items-in-progress') ? ({ libraryItems: [
      { id: 'li1', updatedAt: 1, media: { coverPath: '/c', metadata: { title: 'Stopařův průvodce po Galaxii', authorName: 'Douglas Adams' } } },
      { id: 'li3', updatedAt: 1, media: { coverPath: null, ebookFormat: 'epub', metadata: { title: 'Saturnin', authorName: 'Zdeněk Jirotka' } } } ] }) : orig(url); })()`);
  check('home button visible', await jsP("getComputedStyle(document.getElementById('homeBtn')).display !== 'none' && document.getElementById('homeBtn').title === 'Zpět'"));
  await jsP("document.getElementById('homeBtn').click()");
  await wait(900);
  check('home page shown', await jsP("!document.getElementById('empty').classList.contains('hidden') && document.getElementById('player').classList.contains('hidden')"));
  check('home rows', await jsP("document.querySelectorAll('#list .row').length") === 2);
  check('home current first', await jsP("document.querySelector('#list .row').classList.contains('cur') && document.querySelector('#list .row.cur .a').textContent === 'Přehrává se'"));
  check('home window size', popup.getBounds().height === 236);
  check('read button only on the e-book', await jsP("document.querySelectorAll('#list .row .read-btn').length === 1 && !!document.querySelectorAll('#list .row')[1].querySelector('.read-btn')"));
  check('list title Pokračovat', await jsP("document.querySelector('[data-txt=continue]').textContent") === 'Pokračovat');
  await shot(popup, 'p-home.png');
  await jsP("document.querySelectorAll('#list .row')[1].querySelector('.read-btn').click()");
  await wait(300);
  check('read opens the reader (not playback)', readerOpened.length === 1 && readerOpened[0] === 'li3', readerOpened);
  await jsP("document.querySelector('#list .row.cur .t').click()");
  await wait(700);
  check('home back to player', await jsP("document.getElementById('empty').classList.contains('hidden') && !document.getElementById('player').classList.contains('hidden')"));

  // hlasitost: najetí → posuvník; zavření podle polohy kurzoru (bez DOM událostí)
  const r = await jsP("JSON.stringify(document.getElementById('volBtn').getBoundingClientRect())").then(JSON.parse);
  const bx = Math.round(r.x + r.width / 2), by = Math.round(r.y + r.height / 2);
  fakeCursor = { x: bx, y: by };
  popup.webContents.sendInputEvent({ type: 'mouseMove', x: bx, y: by });
  await wait(400);
  check('volume popover on hover', await jsP("document.getElementById('volWrap').classList.contains('open')"));
  await shot(popup, 'p-vol.png');
  // skutečný klik myší
  popup.webContents.sendInputEvent({ type: 'mouseDown', x: bx, y: by, button: 'left', clickCount: 1 });
  popup.webContents.sendInputEvent({ type: 'mouseUp', x: bx, y: by, button: 'left', clickCount: 1 });
  await wait(600);
  check('mute via real click', (await exec('window.__absPlayer.state()')).muted === true);
  check('click does not focus button', await jsP("document.activeElement !== document.getElementById('volBtn')"));
  await wait(800);
  check('stays open while cursor over', await jsP("document.getElementById('volWrap').classList.contains('open')"));
  // kurzor odjede na přetahovací plochu – stránka nedostane žádnou událost
  fakeCursor = { x: 200, y: 20 };
  await wait(900);
  check('closes when cursor leaves (no DOM events)', !(await jsP("document.getElementById('volWrap').classList.contains('open')")));
  // kurzor nad posuvníkem drží otevřené
  fakeCursor = { x: bx, y: by };
  popup.webContents.sendInputEvent({ type: 'mouseMove', x: bx + 1, y: by });
  await wait(300);
  const pr = await jsP("JSON.stringify(document.querySelector('.vol-pop').getBoundingClientRect())").then(JSON.parse);
  fakeCursor = { x: Math.round(pr.x + pr.width / 2), y: Math.round(pr.y + pr.height / 2) };
  await wait(900);
  check('stays open over slider', await jsP("document.getElementById('volWrap').classList.contains('open')"));
  fakeCursor = { x: 300, y: 120 };
  await wait(900);
  check('closes after leaving slider', !(await jsP("document.getElementById('volWrap').classList.contains('open')")));
  popup.webContents.sendInputEvent({ type: 'mouseDown', x: bx, y: by, button: 'left', clickCount: 1 });
  popup.webContents.sendInputEvent({ type: 'mouseUp', x: bx, y: by, button: 'left', clickCount: 1 });
  fakeCursor = { x: 300, y: 120 };
  await wait(900);

  // seek v kapitole: klik doprostřed pruhu kapitoly 1 (0–1500) → ~750
  const cb = await jsP("JSON.stringify(document.getElementById('chBar').getBoundingClientRect())").then(JSON.parse);
  const cx = Math.round(cb.x + cb.width / 2), cy = Math.round(cb.y + cb.height / 2);
  popup.webContents.sendInputEvent({ type: 'mouseDown', x: cx, y: cy, button: 'left', clickCount: 1 });
  popup.webContents.sendInputEvent({ type: 'mouseUp', x: cx, y: cy, button: 'left', clickCount: 1 });
  await wait(600);
  const ct = (await exec('window.__absPlayer.state()')).currentTime;
  check('chapter seek ~750 (' + Math.round(ct) + ')', Math.abs(ct - 750) < 20);

  // --- seznam kapitol ---
  await exec(`(() => { const p = ${P}; p.chapters = p.chapters41; p.currentTime = 17*512 + 100; })()`);
  await wait(700);
  const geo = await jsP(`(() => { const o = document.querySelector('#player [data-act=open]').getBoundingClientRect(); const c = document.getElementById('chBtn').getBoundingClientRect(); return JSON.stringify({ox: o.x, oy: o.y, cx: c.x, cy: c.y}); })()`).then(JSON.parse);
  check('list button under open-full button', Math.abs(geo.cx - geo.ox) < 2 && geo.cy > geo.oy);
  const layout = () => jsP(`(() => { const p = document.getElementById('player').getBoundingClientRect(); const r = (sel) => { const b = document.querySelector(sel).getBoundingClientRect(); return [Math.round(b.top - p.top), Math.round(b.height)]; }; return JSON.stringify({h: Math.round(p.height), head: r('.head'), prog: r('.progress'), ctl: r('.controls')}); })()`);
  const before = await layout();
  await jsP("document.getElementById('chBtn').click()");
  await wait(900);
  const after = await layout();
  check('player layout identical when expanded ' + before + ' vs ' + after, before === after);
  check('window expanded', popup.getBounds().height === 536);
  check('41 rows', await jsP("document.querySelectorAll('#chItems .ch-row').length") === 41);
  check('current row 18', await jsP("document.querySelector('#chItems .ch-row.cur').dataset.n") === '18');
  check('done rows 17', await jsP("document.querySelectorAll('#chItems .ch-row.done').length") === 17);
  await shot(popup, 'p-list.png');
  await jsP(`document.querySelector('#chItems .ch-row[data-n="5"]').click()`);
  await wait(600);
  check('seek to chapter 5 start', Math.round((await exec('window.__absPlayer.state()')).currentTime) === 4*512);
  await jsP("const i=document.getElementById('chSearch'); i.value='unik'; i.dispatchEvent(new Event('input'))");
  await wait(200);
  check('search diacritics-insensitive', await jsP("document.querySelectorAll('#chItems .ch-row').length") === 6);
  await shot(popup, 'p-search.png');
  await jsP("document.getElementById('chClose').click()");
  await wait(500);
  check('collapsed', popup.getBounds().height === 236);
  check('player layout identical after collapse', (await layout()) === before);
  global.__dir = 'down';
  await jsP("document.getElementById('chBtn').click()");
  await wait(900);
  check('player layout identical when expanded down', (await layout()) === before);
  await shot(popup, 'p-list-down.png');
  await jsP("document.getElementById('chClose').click()");
  await wait(500);
  global.__dir = 'up';
  await exec(`(() => { const p = ${P}; p.currentTime = 1234; })()`);

  // --- přehrávat na ---
  await jsP("document.querySelector('#player [data-act=devices]').click()");
  await wait(900);
  check('devices panel open', await jsP("!document.getElementById('devPanel').classList.contains('hidden')"));
  check('devices window expanded', popup.getBounds().height > 236 + 80 && popup.getBounds().height === 236 + global.__listH);
  check('device rows (this PC + 2 paired)', await jsP("document.querySelectorAll('#devRows .dev-row').length") === 3);
  check('offline device dimmed', await jsP("document.querySelectorAll('#devRows .dev-row.off').length") === 1);
  check('playing subtitle', (await jsP("document.querySelectorAll('#devRows .dev-row')[1].querySelector('.sb').textContent")) === 'Hraje: Duna');
  check('own pairing code', (await jsP("document.getElementById('myCode').textContent")).includes('123 456'));
  check('pair form hidden behind button', await jsP("document.getElementById('pairSection').classList.contains('hidden') && !document.getElementById('pairOpen').classList.contains('hidden')"));
  check('pair button label', (await jsP("document.getElementById('pairOpen').textContent")) === 'Spárovat zařízení (TV nebo PC)');
  await shot(popup, 'p-devices.png');
  const hClosed = global.__listH;
  check('devices panel fits its content (no big empty space)', hClosed > 100 && hClosed < 300);
  await jsP("document.querySelectorAll('#devRows .dev-row')[1].querySelector('.dev-tools .icon-btn').click()");
  await wait(150);
  check('rename turns the row into a field', await jsP("!!document.querySelector('#devRows .dev-row input.dev-name')"));
  await shot(popup, 'p-rename.png');
  await jsP("{ const i=document.querySelector('#devRows input.dev-name'); i.value='Obývák'; i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' })); }");
  await wait(400);
  check('rename saved', devCalls.some((c) => c.startsWith('rename:') && c.endsWith(':Obývák')));
  check('field closed after save', await jsP("!document.querySelector('#devRows input.dev-name')"));
  await jsP("document.getElementById('pairOpen').click()");
  await wait(200);
  check('pair form opens', await jsP("!document.getElementById('pairSection').classList.contains('hidden') && document.getElementById('pairOpen').classList.contains('hidden')"));
  await jsP("document.getElementById('pairCancel').click()");
  await wait(100);
  check('pair form cancel', await jsP("document.getElementById('pairSection').classList.contains('hidden')"));
  await jsP("document.getElementById('pairOpen').click()");
  await wait(200);
  await wait(200);
  await shot(popup, 'p-pair-open.png');
  check('window grows when pairing opens', global.__listH > hClosed + 60);
  check('pairing form fully visible', await jsP("(() => { const b = document.getElementById('myCode').getBoundingClientRect(); return b.bottom <= window.innerHeight; })()"));
  await jsP("document.querySelector('#devFound .dev-row').click()");
  await wait(700);
  check('found device fills IP', await jsP("document.getElementById('pHost').value") === '10.0.0.7');
  await jsP("{ const i=document.getElementById('pCode'); i.value='12a3456'; i.dispatchEvent(new Event('input')); }");
  await wait(100);
  check('code digits only', await jsP("document.getElementById('pCode').value") === '123456');
  check('pair enabled (PC needs no password)', await jsP("!document.getElementById('pBtn').disabled && document.getElementById('pPass').classList.contains('hidden')"));
  await shot(popup, 'p-pair.png');
  await jsP("document.getElementById('pBtn').click()");
  await wait(900);
  check('pair + select called', JSON.stringify(devCalls.filter((c) => !c.startsWith('rename:'))) === JSON.stringify(['pair:10.0.0.7:8765:123456', 'select:pc2']));
  check('panel closed after pairing', await jsP("document.getElementById('devPanel').classList.contains('hidden')") && popup.getBounds().height === 236);
  // přepnutí z panelu zařízení na kapitoly nezmění velikost okna
  await jsP("document.querySelector('#player [data-act=devices]').click()");
  await wait(700);
  check('pair form collapsed on reopen', await jsP("document.getElementById('pairSection').classList.contains('hidden')"));
  await jsP("document.getElementById('chBtn').click()");
  await wait(700);
  check('switch to chapters keeps window', popup.getBounds().height === 536 && await jsP("document.getElementById('devPanel').classList.contains('hidden') && !document.getElementById('chPanel').classList.contains('hidden')"));
  await jsP("document.getElementById('chClose').click()");
  await wait(500);
  check('collapsed again', popup.getBounds().height === 236);
  // vzdálený cíl: řádek „Přehrává se na“
  targetOverride = { id: 'tv1', name: 'Obývák TV', kind: 'tv', remote: true };
  await wait(900);
  check('remote target line', await jsP("!document.getElementById('target').classList.contains('hidden') && document.getElementById('targetTxt').textContent") === 'Obývák TV' && await jsP("document.getElementById('target').title") === 'Přehrává se na: Obývák TV');
  await shot(popup, 'p-remote.png');
  targetOverride = null;

  // --- synchronizace webového přehrávače ---
  const net = () => exec('window.__net.slice()');
  await exec(`(() => { const p = ${P}; p.chapters = p.chapters.slice(0, 3); p.currentTime = 1234; p.isPlaying = true; p.playerHandler.playerPlaying = true; window.__net.length = 0; })()`);
  await wait(1300);
  await exec("window.__absPlayer.cmd('playPause')"); // pauza
  await wait(1400);
  check('sync on pause ' + JSON.stringify(await net()), (await net()).includes('post:/api/session/sess1/sync:1234:12'));
  // jiné zařízení mezitím posunulo pozici → při pokračování se převezme
  await exec("window.__serverProgress = { currentTime: 5000, lastUpdate: Date.now() }; window.__net.length = 0;");
  await exec("window.__absPlayer.cmd('playPause')");
  await wait(600);
  const n1 = await net();
  check('resume takes newer server position ' + JSON.stringify(n1), n1.includes('get:/api/me/progress/li1') && n1.includes('phSeek:5000'));
  st = await exec('window.__absPlayer.state()');
  check('resume plays + notice', st.isPlaying && Math.round(st.currentTime) === 5000 && st.notice === 'moved');
  // stejná pozice na serveru → nic se nepřevezme
  await exec("window.__absPlayer.cmd('playPause')");
  await wait(1400);
  await exec("window.__serverProgress = { currentTime: 5001, lastUpdate: Date.now() }; window.__net.length = 0;");
  await exec("window.__absPlayer.cmd('playPause')");
  await wait(600);
  check('resume keeps own position', !(await net()).some((x) => x.startsWith('phSeek')));
  // pozastaveno + změna z jiného zařízení (socket → store) → převezme se hned
  await exec("window.__absPlayer.cmd('playPause')");
  await wait(1400);
  await exec("window.__storeProgress = { currentTime: 7000, lastUpdate: Date.now() + 5000 }; window.__serverProgress = { currentTime: 7000, lastUpdate: Date.now() }; window.__net.length = 0;");
  await wait(1400);
  check('follow while paused ' + JSON.stringify(await net()), (await net()).includes('phSeek:7000'));
  // předání na jiné zařízení: pauza, uložení, zavření
  await exec("window.__storeProgress = null; window.__absPlayer.cmd('playPause')");
  await wait(300);
  await exec("window.__net.length = 0");
  const t0 = Date.now();
  const h = await exec("window.__absPlayer.cmd('handoff')");
  check('handoff returns at once (sync in the background)', Date.now() - t0 < 200);
  await wait(700);
  const n2 = await net();
  check('handoff ' + JSON.stringify(h) + ' ' + JSON.stringify(n2), h && h.id === 'li1' && Math.round(h.time) === 7000 && n2.some((x) => x.startsWith('post:/api/session/sess1/sync:7000:')) && n2.includes('closePlayer'));
  await exec("window.__absPlayer.cmd('playItem', { id: 'li2', startTime: 42 })");
  check('playItem with startTime', (await exec('window.__log')).includes('play-item:{"libraryItemId":"li2","episodeId":null,"startTime":42}'));
  await exec(`(() => { const p = ${P}; p.streamLibraryItem = { id: 'li1', updatedAt: 1, media: {} }; p.currentTime = 1234; p.isPlaying = true; p.playerHandler.playerPlaying = true; })()`);

  // hide tlačítko
  await jsP("document.querySelector('[data-act=hide]').click()");
  await wait(100);

  // jazyky
  for (const l of ['en', 'ja', 'de']) {
    lang = l;
    popup.webContents.send('i18n:changed', l);
    await wait(700);
    await shot(popup, 'p-' + l + '.png');
  }

  // vypnutá TV: tlačítko Přehrát na tomto zařízení
  await jsP("busy = true; st = { ready: true, loggedIn: true, item: null, remoteError: true, waking: null, lastTitle: 'V Krvavém pásu', target: { name: 'Obývák TV', remote: true } }; render();");
  await wait(300);
  check('offline TV: play here button', await jsP("!document.getElementById('playHere').classList.contains('hidden') && document.getElementById('playHere').textContent === " + JSON.stringify(I18N.tr(lang, 'playHere'))));
  await shot(popup, 'p-offline.png');
  await jsP("document.getElementById('playHere').click()");
  await wait(300);
  check('offline TV: play here called', devCalls.includes('playHere'));
  await jsP("st = { ready: true, loggedIn: true, item: null, remoteError: true, waking: 23, target: { name: 'Obývák TV', remote: true } }; render();");
  check('waking text', (await jsP("document.getElementById('msgTxt').textContent")) === I18N.tr(lang, 'waking', { name: 'Obývák TV', s: 23 }) && await jsP("document.getElementById('playHere').classList.contains('hidden')"));
  await jsP("busy = false;");

  // prázdný stav
  await exec(`(() => { const p = ${P}; p.streamLibraryItem = null;
    window.$nuxt.$axios.$get = async () => ({ libraryItems: [
      { id: 'li1', updatedAt: 1, media: { coverPath: '/c', metadata: { title: 'Stopařův průvodce po Galaxii', authorName: 'Douglas Adams' } } },
      { id: 'li3', updatedAt: 1, media: { coverPath: null, metadata: { title: 'Saturnin', authorName: 'Zdeněk Jirotka' } } } ] }); })()`);
  lang = 'cs'; popup.webContents.send('i18n:changed', 'cs');
  await wait(1200);
  await shot(popup, 'p-empty.png');
  await jsP("document.querySelector('#list .row .t').click()");
  await wait(500);
  check('start page: click on title plays', String(await exec('window.__log[window.__log.length - 1]')).startsWith('play-item:{"libraryItemId":"li1"'));

  // setup okno
  const setup = new BrowserWindow({ width: 500, height: 340, show: true, backgroundColor: '#232323',
    webPreferences: { preload: path.join(SRC, 'setup-preload.js'), contextIsolation: true, sandbox: true } });
  setup.webContents.on('console-message', (_e, lvl, msg) => results.push('setup console: ' + msg));
  await setup.loadFile(path.join(SRC, 'setup.html'), { query: { url: '' } });
  await wait(500);
  await shot(setup, 's-cs.png');
  await setup.webContents.executeJavaScript("document.getElementById('langBtn').click()");
  await wait(300);
  await shot(setup, 's-open.png');
  await setup.webContents.executeJavaScript("document.querySelector('.opt[data-code=de]').click()");
  await wait(300);
  check('setup lang set', lang === 'de');
  await shot(setup, 's-de.png');

  console.log(results.join('\n'));
  const failed = results.filter((r) => r.startsWith('FAIL')).length;
  console.log(failed ? failed + ' FAIL' : 'OK – ' + results.filter((r) => r.startsWith('PASS')).length + ' testů');
  app.exit(failed ? 1 : 0);
});
