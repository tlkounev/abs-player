// xvfb-run electron test/reader-window.test.js <book.epub>
// Okno čtečky: načtení EPUB, obsah, kapitola v liště, Aa, „Poslouchat od této strany“ (čas zpět hostiteli).
'use strict';
const { app, BrowserWindow, ipcMain } = require('electron');
const fs = require('fs');
const path = require('path');
const BOOK = process.argv[process.argv.length - 1];
const OUT = path.join(__dirname, 'out');
let fails = 0;
const check = (n, ok, x) => { console.log((ok ? 'PASS ' : 'FAIL ') + n + (x !== undefined ? ' ' + JSON.stringify(x) : '')); if (!ok) fails++; };
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

const lens = [3, 8, 5, 12, 4, 9];
let t = 0;
const chapters = lens.map((n, i) => { const c = { start: t, end: t + n * 60, title: 'Kapitola ' + (i + 1) }; t += n * 60; return c; });
const got = { relocated: [], listen: [], settings: [], locations: 0 };

ipcMain.on('i18n:get', (e) => { e.returnValue = 'cs'; });
ipcMain.on('reader:config', (e) => {
  e.returnValue = JSON.stringify({ title: 'V Krvavém pásu', hasAudio: true, mode: 'fromAudio', audioTime: chapters[3].start + 360, audioDuration: t, audioChapters: chapters, settings: {} });
});
ipcMain.handle('reader:book', () => new Uint8Array(fs.readFileSync(BOOK)));
ipcMain.on('reader:relocated', (_e, j) => got.relocated.push(JSON.parse(j)));
ipcMain.on('reader:locations', () => { got.locations++; });
ipcMain.on('reader:settings', (_e, j) => got.settings.push(JSON.parse(j)));
ipcMain.on('reader:listen', (_e, s) => got.listen.push(s));

app.whenReady().then(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const win = new BrowserWindow({ width: 1000, height: 700, show: true, webPreferences: { preload: path.join(__dirname, '..', 'src', 'reader-preload.js'), contextIsolation: true, sandbox: true } });
  const errors = [];
  win.webContents.on('console-message', (_e, level, msg) => { if (level >= 3) errors.push(msg); });
  await win.loadFile(path.join(__dirname, '..', 'src', 'reader-window.html'));
  const js = (c) => win.webContents.executeJavaScript(c);
  for (let i = 0; i < 60 && !(await js("document.getElementById('wait').classList.contains('hidden')")); i++) await wait(200);
  check('loaded', await js("document.getElementById('wait').classList.contains('hidden')"), errors);
  await wait(500);
  check('contents: 6 chapters', (await js("document.querySelectorAll('.ch').length")) === 6);
  const last = got.relocated[got.relocated.length - 1] || {};
  check('opened where listening ended (chapter 4)', last.chapter === 'Kapitola 4', last);
  check('chapter in the top bar', (await js("document.getElementById('chapter').textContent")).includes('Kapitola 4'));
  check('current chapter marked in contents', (await js("[...document.querySelectorAll('.ch')].findIndex(e => e.classList.contains('cur'))")) === 3);
  check('progress + page in the bottom bar', /\d+ % · str\. \d+\/\d+/.test(await js("document.getElementById('pct').textContent")));
  check('listen button text', (await js("document.getElementById('listen').textContent")).includes('Poslouchat od této strany'));
  await (await win.webContents.capturePage()).toPNG && fs.writeFileSync(path.join(OUT, 'r-reader.png'), (await win.webContents.capturePage()).toPNG());
  // Aa: sépie
  await js("document.getElementById('aaBtn').click()");
  await wait(200);
  fs.writeFileSync(path.join(OUT, 'r-aa.png'), (await win.webContents.capturePage()).toPNG());
  await js("[...document.querySelectorAll('#theme button')][1].click()");
  await wait(400);
  check('theme saved', got.settings.length && got.settings[got.settings.length - 1].theme === 'sepia');
  // obsah: kapitola 2
  await js("document.body.click(); document.querySelectorAll('.ch')[1].click()");
  await wait(700);
  check('contents click -> chapter 2', (got.relocated[got.relocated.length - 1] || {}).chapter === 'Kapitola 2');
  fs.writeFileSync(path.join(OUT, 'r-sepia.png'), (await win.webContents.capturePage()).toPNG());
  // poslouchat od této strany: začátek kapitoly 2
  await js("document.getElementById('listen').click()");
  for (let i = 0; i < 30 && !got.listen.length; i++) await wait(100);
  check('listen from this page -> start of chapter 2', got.listen.length === 1 && Math.abs(got.listen[0] - chapters[1].start) < 5, got.listen);
  check('locations handed to the host', got.locations === 1);
  console.log(fails ? fails + ' FAIL' : 'all passed');
  app.exit(fails ? 1 : 0);
});
