// README screenshots of the Windows app: popup (player, Continue, chapters, Play on) and the reader
// window, with English demo data and the generated demo covers. No ABS server needed.
// Run from win/:  xvfb-run -a electron ../tools/screenshots/win-shots.js <demo-library> <alice.epub> <out-dir>
'use strict';
const { app, BrowserWindow, ipcMain } = require('electron');
const fs = require('fs');
const path = require('path');
const [LIB, EPUB, OUT] = process.argv.slice(-3);
const SRC = path.join(__dirname, '..', '..', 'win', 'src');
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
fs.mkdirSync(OUT, { recursive: true });

function cover(rel) {
  const f = path.join(LIB, rel, 'cover.jpg');
  return 'data:image/jpeg;base64,' + fs.readFileSync(f).toString('base64');
}
const ALICE = "Lewis Carroll/Alice's Adventures in Wonderland";
const chNames = ['Down the Rabbit-Hole', 'The Pool of Tears', 'A Caucus-Race and a Long Tale', 'The Rabbit Sends in a Little Bill',
  'Advice from a Caterpillar', 'Pig and Pepper', 'A Mad Tea-Party', "The Queen's Croquet-Ground", "The Mock Turtle's Story",
  'The Lobster Quadrille', 'Who Stole the Tarts?', "Alice's Evidence"];
let t = 0;
const chapters = chNames.map((title, i) => { const n = (4 + (i % 3)) * 60 * 1.7; const c = { n: i + 1, title, start: t, end: t + n }; t += n; return c; });
const cur = chapters[4];
let state = {
  ready: true, loggedIn: true,
  item: { id: 'alice', title: "Alice's Adventures in Wonderland", author: 'Lewis Carroll', series: '', cover: cover(ALICE) },
  isPlaying: true, loading: false, currentTime: cur.start + 212, duration: t, rate: 1.25,
  chapter: { title: cur.title, start: cur.start, end: cur.end, index: 5, total: chapters.length },
  hasChapters: true, chaptersKey: 'alice:12', volume: 0.8, muted: false, jumpBack: 10, jumpFwd: 30, sleepRemaining: null, notice: null,
  target: { id: 'local', name: 'Office PC', remote: false }
};
const recent = [
  { id: 'alice', title: "Alice's Adventures in Wonderland", author: 'Lewis Carroll', left: 9660, ebook: true, cover: cover(ALICE) },
  { id: 'scarlet', title: 'A Study in Scarlet', author: 'Arthur Conan Doyle', left: 11100, ebook: false, cover: cover('Arthur Conan Doyle/Sherlock Holmes/A Study in Scarlet') },
  { id: 'time', title: 'The Time Machine', author: 'H. G. Wells', left: 4320, ebook: false, cover: cover('H. G. Wells/The Time Machine') }
];

ipcMain.handle('abs:state', () => state);
ipcMain.handle('abs:cmd', (_e, n) => (n === 'chapters' ? chapters : n === 'recent' ? recent : true));
ipcMain.handle('devices:get', () => ({
  current: 'local', local: { id: 'local', name: 'Office PC' },
  paired: [{ id: 'tv1', name: 'Living Room TV', kind: 'tv', host: '192.168.1.40', online: true },
    { id: 'pc2', name: 'Laptop', kind: 'pc', host: '192.168.1.52', online: true, title: 'Dracula', isPlaying: false },
    { id: 'tv2', name: 'Bedroom TV', kind: 'tv', host: '192.168.1.41', online: false }],
  discovered: [], code: '482913', port: 8765, addr: '192.168.1.20'
}));
for (const ch of ['devices:select', 'devices:unpair', 'devices:rename', 'devices:playHere', 'devices:pair', 'reader:open']) ipcMain.handle(ch, () => true);
ipcMain.handle('devices:info', () => ({ ok: false }));
ipcMain.handle('popup:cursor', () => ({ x: -100, y: -100 }));
ipcMain.on('popup:open-main', () => {});
ipcMain.on('popup:hide', () => {});
ipcMain.on('i18n:get', (e) => { e.returnValue = 'en'; });
ipcMain.on('i18n:set', () => {});
let popup;
ipcMain.handle('popup:list', (_e, open, h) => {
  const want = Math.round(Math.max(80, Math.min(Number(h) || 300, 560)));
  popup.setBounds({ x: 0, y: 0, width: 380, height: 236 + (open ? want : 0) });
  return open ? 'down' : null;
});
// reader window
ipcMain.on('reader:config', (e) => {
  e.returnValue = JSON.stringify({ title: "Alice's Adventures in Wonderland", hasAudio: true, mode: 'fromAudio',
    audioTime: chapters[4].start + 30, audioDuration: t, audioChapters: chapters.map((c) => ({ start: c.start, end: c.end, title: c.title })),
    settings: { theme: 'sepia', fontSize: 110, font: 'serif', lineHeight: 1.6 } });
});
ipcMain.handle('reader:book', () => new Uint8Array(fs.readFileSync(EPUB)));
for (const ch of ['reader:relocated', 'reader:locations', 'reader:settings', 'reader:listen']) ipcMain.on(ch, () => {});

app.whenReady().then(async () => {
  popup = new BrowserWindow({ x: 0, y: 0, width: 380, height: 236, show: true, frame: false, transparent: true,
    webPreferences: { preload: path.join(SRC, 'popup-preload.js'), contextIsolation: true, sandbox: true } });
  await popup.loadFile(path.join(SRC, 'popup.html'));
  const js = (c) => popup.webContents.executeJavaScript(c);
  const shot = async (win, name) => { await wait(400); fs.writeFileSync(path.join(OUT, name), (await win.webContents.capturePage()).toPNG()); console.log('shot', name); };
  await wait(1800);
  await shot(popup, 'win-1-player.png');
  await js("document.getElementById('chBtn').click()"); await wait(1000);
  await shot(popup, 'win-2-chapters.png');
  await js("document.getElementById('chClose').click()"); await wait(600);
  await js("document.querySelector('#player [data-act=devices]').click()"); await wait(1200);
  await shot(popup, 'win-3-play-on.png');
  await js("document.getElementById('pairOpen').click()"); await wait(600);
  await shot(popup, 'win-4-pair.png');
  await js("document.getElementById('pairCancel').click()"); await wait(300);
  await js("document.getElementById('devClose') ? document.getElementById('devClose').click() : null"); await wait(600);
  await js("document.getElementById('homeBtn').click()"); await wait(1200);
  await shot(popup, 'win-5-continue.png');

  const reader = new BrowserWindow({ width: 1280, height: 800, show: true,
    webPreferences: { preload: path.join(SRC, 'reader-preload.js'), contextIsolation: true, sandbox: true } });
  await reader.loadFile(path.join(SRC, 'reader-window.html'));
  const rj = (c) => reader.webContents.executeJavaScript(c);
  for (let i = 0; i < 100 && !(await rj("document.getElementById('wait').classList.contains('hidden')")); i++) await wait(200);
  await wait(1500);
  await shot(reader, 'win-6-reader.png');
  app.exit(0);
});
