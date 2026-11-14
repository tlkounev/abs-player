// Playback on another device – the same protocol as the ABS Player app (Android / Google TV).
//
//   GET  /info   -> { deviceId, name, loggedIn, server, username, version, kind, acceptsTokens }
//   POST /pair   { code, server, username, userId, accessToken?, refreshToken?, phoneName } -> { secret, deviceId, name }
//   GET  /state  -> NowPlaying
//   POST /cmd    { action, value?, itemId? } -> NowPlaying
//
// Devices announce themselves via mDNS as _absplayer._tcp with TXT { id, name, kind }.
// Everything except /info and /pair needs the X-Secret header the receiver hands out when pairing.
'use strict';

const http = require('http');
const crypto = require('crypto');

const SERVICE_TYPE = 'absplayer';
const DEFAULT_PORT = 8765;
const PROTOCOL_VERSION = 2;

// ---------------------------------------------------------------- state conversions

/** NowPlaying (protocol) → mini player state. */
function nowPlayingToPopup(np, extra) {
  const base = { ready: true, loggedIn: true, ...extra };
  if (!np || !np.itemId) return { ...base, item: null };
  const chs = Array.isArray(np.chapters) ? np.chapters : [];
  const pos = np.positionSec || 0;
  const hasCh = chs.length > 1;
  let chapter = null;
  if (hasCh) {
    let i = 0;
    for (let k = 0; k < chs.length; k++) if (chs[k].startSec <= pos + 0.001) i = k;
    const c = chs[i];
    chapter = { title: c.title, start: c.startSec, end: c.endSec, index: i + 1, total: chs.length };
  }
  return {
    ...base,
    item: { id: np.itemId, title: np.title || '', author: np.author || '', series: '', cover: (extra && extra.cover) || null },
    isPlaying: !!np.isPlaying,
    loading: !!(np.isLoading || np.isBuffering),
    currentTime: pos,
    duration: np.durationSec || 0,
    rate: np.speed || 1,
    chapter,
    hasChapters: hasCh,
    chaptersKey: np.itemId + ':' + chs.length + ':' + (chs.length ? chs[chs.length - 1].endSec : 0),
    volume: typeof np.volume === 'number' ? np.volume : 1,
    muted: !!np.muted,
    jumpBack: 30,
    jumpFwd: 30,
    sleepRemaining: typeof np.sleepSec === 'number' && np.sleepSec >= 0 ? np.sleepSec : null,
    notice: np.error || np.info || null,
    chaptersList: chs.map((c, i) => ({ n: i + 1, title: c.title, start: c.startSec, end: c.endSec }))
  };
}

/** Web player state (bridge.js) + chapters → NowPlaying (protocol). */
function popupToNowPlaying(st, chapters) {
  if (!st || !st.ready || !st.item) {
    return { itemId: null, title: '', author: '', chapters: [], durationSec: 0, positionSec: 0, isPlaying: false, isBuffering: false, isLoading: false, speed: 1, volume: 1, muted: false };
  }
  return {
    itemId: st.item.id,
    title: st.item.title || '',
    author: st.item.author || '',
    chapters: (chapters || []).map((c) => ({ title: c.title || '', startSec: c.start, endSec: c.end })),
    durationSec: st.duration || 0,
    positionSec: st.currentTime || 0,
    isPlaying: !!st.isPlaying,
    isBuffering: false,
    isLoading: !!st.loading,
    speed: st.rate || 1,
    volume: typeof st.volume === 'number' ? st.volume : 1,
    muted: !!st.muted,
    sleepSec: typeof st.sleepRemaining === 'number' ? Math.round(st.sleepRemaining) : null,
    info: st.notice === 'moved' ? 'Position taken over from another device' : null
  };
}

/** Mini player command → protocol command (for a remote device). */
function popupCmdToRemote(name, arg) {
  switch (name) {
    case 'playPause': return { action: 'toggle' };
    case 'play': return { action: 'play' };
    case 'jumpBack': return { action: 'skip', value: -30 };
    case 'jumpFwd': return { action: 'skip', value: 30 };
    case 'skip': return { action: 'skip', value: Number(arg) };
    case 'seek': return { action: 'seek', value: Number(arg) };
    case 'prevChapter': return { action: 'prev' };
    case 'nextChapter': return { action: 'next' };
    case 'rate': return { action: 'speed', value: Number(arg) };
    case 'volume': return { action: 'volume', value: Number(arg) };
    case 'mute': return { action: 'mute' };
    case 'playItem': return { action: 'playItem', itemId: arg && arg.id, value: arg && typeof arg.startTime === 'number' ? arg.startTime : null };
    default: return null;
  }
}

// ---------------------------------------------------------------- receiver (this PC)

/**
 * HTTP server through which a paired phone (or another PC) controls playback in this app.
 * opts: { deviceId, name, info(), state() -> NowPlaying, command(cmd) -> NowPlaying,
 *         getSecrets() -> string[], addSecret(s), code }
 */
function createReceiver(opts) {
  let failed = 0;
  let lockedUntil = 0;
  let server = null;
  let port = 0;

  function send(res, status, obj) {
    const body = JSON.stringify(obj);
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(body) });
    res.end(body);
  }

  function readBody(req) {
    return new Promise((resolve, reject) => {
      let data = '';
      req.setEncoding('utf8');
      req.on('data', (c) => {
        data += c;
        if (data.length > 64 * 1024) { reject(new Error('too large')); req.destroy(); }
      });
      req.on('end', () => resolve(data));
      req.on('error', reject);
    });
  }

  function authorized(req) {
    const s = req.headers['x-secret'];
    return typeof s === 'string' && opts.getSecrets().includes(s);
  }

  async function handle(req, res) {
    const url = (req.url || '').split('?')[0];
    try {
      if (req.method === 'GET' && url === '/info') {
        return send(res, 200, { deviceId: opts.deviceId, name: typeof opts.name === 'function' ? opts.name() : opts.name, version: PROTOCOL_VERSION, kind: 'pc', acceptsTokens: false, ...(await opts.info()) });
      }
      if (req.method === 'POST' && url === '/pair') {
        const now = Date.now();
        if (now < lockedUntil) return send(res, 403, { error: 'Too many attempts, try again in a minute' });
        const body = JSON.parse((await readBody(req)) || '{}');
        if (String(body.code || '').trim() !== opts.code()) {
          if (++failed >= 5) { failed = 0; lockedUntil = now + 60000; }
          return send(res, 403, { error: 'Wrong pairing code' });
        }
        failed = 0;
        const secret = crypto.randomBytes(24).toString('hex');
        opts.addSecret(secret, body.phoneName || '');
        return send(res, 200, { secret, deviceId: opts.deviceId, name: typeof opts.name === 'function' ? opts.name() : opts.name });
      }
      if (!authorized(req)) return send(res, 401, { error: 'Device is not paired' });
      if (req.method === 'GET' && url === '/state') return send(res, 200, await opts.state());
      if (req.method === 'POST' && url === '/cmd') {
        const cmd = JSON.parse((await readBody(req)) || '{}');
        return send(res, 200, await opts.command(cmd));
      }
      return send(res, 404, { error: 'Unknown request' });
    } catch (e) {
      return send(res, 500, { error: String((e && e.message) || e) });
    }
  }

  function listen(first) {
    return new Promise((resolve, reject) => {
      let p = first;
      const tryNext = () => {
        const s = http.createServer((req, res) => { handle(req, res); });
        s.once('error', (e) => {
          if (e.code === 'EADDRINUSE' && p < first + 10) { p++; tryNext(); } else reject(e);
        });
        s.listen(p, '0.0.0.0', () => { server = s; port = p; resolve(p); });
      };
      tryNext();
    });
  }

  return {
    listen,
    get port() { return port; },
    close() {
      if (server) {
        server.close();
        if (server.closeAllConnections) server.closeAllConnections();
      }
      server = null;
    }
  };
}

// ---------------------------------------------------------------- client (remote device)

async function request(method, url, body, secret, timeoutMs) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs || 5000);
  try {
    const headers = { 'Content-Type': 'application/json; charset=utf-8' };
    if (secret) headers['X-Secret'] = secret;
    const res = await fetch(url, { method, headers, body: body ? JSON.stringify(body) : undefined, signal: ctrl.signal });
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch (e) {}
    if (!res.ok) {
      const err = new Error((data && data.error) || ('HTTP ' + res.status));
      err.status = res.status;
      throw err;
    }
    return data;
  } finally {
    clearTimeout(t);
  }
}

function deviceUrl(d, path) {
  const host = d.host.includes(':') && !d.host.startsWith('[') ? `[${d.host}]` : d.host;
  return `http://${host}:${d.port}${path}`;
}

const remoteState = (d) => request('GET', deviceUrl(d, '/state'), null, d.secret, 3000);
const remoteCmd = (d, cmd) => request('POST', deviceUrl(d, '/cmd'), cmd, d.secret, 10000);
const remoteInfo = (host, port, timeoutMs) => request('GET', deviceUrl({ host, port }, '/info'), null, null, timeoutMs || 3000);

/** Ports a receiver can run on (it takes the first free one from 8765 – on a TV something else sometimes holds 8765). */
const RECEIVER_PORTS = Array.from({ length: 10 }, (_, i) => DEFAULT_PORT + i);

/**
 * Finds the receiver at an address: the given port first, then the other receiver ports. With deviceId only
 * that device counts. Returns { port, info } or null.
 */
async function locate(host, port, deviceId) {
  const ok = (i) => i && i.deviceId && (!deviceId || i.deviceId === deviceId);
  try {
    const info = await remoteInfo(host, port);
    if (ok(info)) return { port: Number(port), info };
  } catch (e) {}
  const tries = RECEIVER_PORTS.filter((p) => p !== Number(port)).map((p) =>
    remoteInfo(host, p, 2000).then((info) => (ok(info) ? { port: p, info } : null), () => null));
  return (await Promise.all(tries)).find(Boolean) || null;
}

function sameServer(a, b) {
  const n = (u) => String(u || '').trim().replace(/\/+$/, '').toLowerCase();
  return n(a) === n(b);
}

/** Does the device need the password when pairing (to get its own sign-in)? */
function needsPassword(info, serverUrl, username) {
  return !!info.acceptsTokens && !(info.loggedIn && sameServer(info.server, serverUrl) && info.username === username);
}

/**
 * Pairs a remote device. A TV that is not signed in to this account gets its own pair of tokens:
 * we sign in again with the password (refresh tokens rotate, devices must not share one).
 */
async function pair({ host, port, code, password, serverUrl, username, userId, myName }) {
  // the given / found port can be stale: take the one the receiver really answers on
  const hit = await locate(host, port);
  if (!hit) { const e = new Error('noResponse'); e.code = 'noResponse'; throw e; }
  port = hit.port;
  const info = hit.info;
  const req = { code: String(code).trim(), server: serverUrl, username, userId: userId || null, phoneName: myName };
  if (needsPassword(info, serverUrl, username)) {
    if (!password) {
      const e = new Error('needPassword');
      e.code = 'needPassword';
      throw e;
    }
    const res = await fetch(serverUrl.replace(/\/+$/, '') + '/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-return-tokens': 'true' },
      body: JSON.stringify({ username, password })
    });
    if (res.status === 401) { const e = new Error('badPassword'); e.code = 'badPassword'; throw e; }
    if (!res.ok) throw new Error('Login HTTP ' + res.status);
    const data = await res.json();
    const u = data.user || {};
    req.userId = u.id || req.userId;
    req.accessToken = u.accessToken || u.token;
    req.refreshToken = u.refreshToken || null;
    if (!req.accessToken) throw new Error('The server returned no token');
  }
  let resp;
  try {
    resp = await request('POST', deviceUrl({ host, port }, '/pair'), req, null, 8000);
  } catch (e) {
    // the TV reports it is not signed in to this account → the password is needed
    if (e.status === 400 && !req.accessToken) { const err = new Error('needPassword'); err.code = 'needPassword'; throw err; }
    throw e;
  }
  return { deviceId: resp.deviceId, name: resp.name, secret: resp.secret, host, port, kind: info.kind || 'tv' };
}

// ---------------------------------------------------------------- mDNS

/** Announces this PC and looks for the others. Without the bonjour-service library (tests) it does nothing. */
function createDiscovery({ deviceId, name, onChange, Bonjour }) {
  let B = Bonjour;
  if (!B) {
    try { B = require('bonjour-service').Bonjour; } catch (e) { B = null; }
  }
  const found = new Map(); // fqdn -> device
  let bonjour = null;
  let browser = null;

  function toDevice(svc) {
    const txt = svc.txt || {};
    const addrs = (svc.addresses || []).filter((a) => /^\d+\.\d+\.\d+\.\d+$/.test(a));
    const host = addrs[0] || (svc.referer && svc.referer.address) || svc.host;
    return { deviceId: txt.id || null, name: txt.name || svc.name, kind: txt.kind || 'tv', host, port: svc.port, key: svc.fqdn || svc.name };
  }

  return {
    start(port) {
      if (!B) return;
      try {
        bonjour = new B({}, () => {});
        bonjour.publish({ name: 'ABS Player ' + name, type: SERVICE_TYPE, port, txt: { id: deviceId, name, kind: 'pc' } });
        browser = bonjour.find({ type: SERVICE_TYPE });
        browser.on('up', (svc) => {
          const d = toDevice(svc);
          if (d.deviceId === deviceId) return;
          found.set(d.key, d);
          onChange && onChange();
        });
        browser.on('down', (svc) => {
          found.delete(svc.fqdn || svc.name);
          onChange && onChange();
        });
      } catch (e) {
        bonjour = null;
      }
    },
    refresh() {
      try { if (browser) browser.update(); } catch (e) {}
    },
    list() { return [...found.values()]; },
    stop() {
      try { if (bonjour) { bonjour.unpublishAll(); bonjour.destroy(); } } catch (e) {}
      bonjour = null;
    }
  };
}

function newCode() {
  return String(100000 + crypto.randomInt(900000));
}

module.exports = {
  SERVICE_TYPE,
  DEFAULT_PORT,
  PROTOCOL_VERSION,
  nowPlayingToPopup,
  popupToNowPlaying,
  popupCmdToRemote,
  createReceiver,
  createDiscovery,
  remoteState,
  remoteCmd,
  remoteInfo,
  locate,
  RECEIVER_PORTS,
  needsPassword,
  pair,
  newCode
};
