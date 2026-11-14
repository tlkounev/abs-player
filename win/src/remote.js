// Playback on another device – the same protocol as the ABS Player app (Android / Google TV).
//
//   GET    /info         -> { deviceId, name, loggedIn, server, username, version, kind, acceptsTokens, time }
//   POST   /pair/start   { clientPub } -> { pairId, receiverPub }
//   POST   /pair/finish  { pairId, proof, box: { server, username, userId, accessToken?, refreshToken?, name } }
//                        -> { proof, box: { clientId, deviceId, name } }
//   GET    /state        -> NowPlaying                    (signed)
//   POST   /cmd          { action, value?, itemId? } -> NowPlaying   (signed)
//   DELETE /pair         -> { ok }  forget this controller (signed)
//
// Devices announce themselves via mDNS as _absplayer._tcp with TXT { id, name, kind }.
// Pairing and request signing: see linkcrypto.js. No secret ever travels after pairing, and the receiver
// signs its answers, so a device that only claims a paired deviceId gets nothing and is not believed.
'use strict';

const http = require('http');
const crypto = require('crypto');
const L = require('./linkcrypto');

const SERVICE_TYPE = 'absplayer';
const DEFAULT_PORT = 8765;
const PROTOCOL_VERSION = 3;
/** Allowed difference of the request time from the receiver's clock (the client follows /info's time). */
const MAX_SKEW = 120000;

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
 *         getLinks() -> [{ clientId, key }], addLink({ clientId, key, name }), removeLink(clientId), code() }
 */
function createReceiver(opts) {
  let failed = 0;
  let lockedUntil = 0;
  let server = null;
  let port = 0;
  const pending = new Map(); // pairId -> { kp, clientPub, expires }
  const nonces = new Map(); // nonce -> expiry (replay protection)
  const clock = opts.now || Date.now; // (tests: a receiver with another time)

  function send(res, status, obj, sign) {
    const body = JSON.stringify(obj);
    const headers = { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(body) };
    if (sign) headers['X-Sig'] = L.responseSig(L.unb64(sign.key), sign.nonce, status, body);
    res.writeHead(status, headers);
    res.end(body);
  }
  const nameNow = () => (typeof opts.name === 'function' ? opts.name() : opts.name);

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

  function pairStart(body) {
    const now = clock();
    for (const [id, p] of pending) if (p.expires < now) pending.delete(id);
    while (pending.size >= 16) pending.delete(pending.keys().next().value);
    const kp = L.newKeyPair();
    L.sharedSecret(kp, body.clientPub); // validates the key
    const pairId = L.randomHex(12);
    pending.set(pairId, { kp, clientPub: String(body.clientPub), expires: now + 120000 });
    return { pairId, receiverPub: kp.pub };
  }

  function pairFinish(res, body) {
    const now = clock();
    if (now < lockedUntil) return send(res, 403, { error: 'Too many attempts, try again in a minute' });
    const p = pending.get(body.pairId);
    if (!p || p.expires < now) return send(res, 400, { error: 'Pairing expired, try again', code: 'expired' });
    pending.delete(body.pairId);
    const keys = L.pairKeys(L.sharedSecret(p.kp, p.clientPub), body.pairId, p.clientPub, p.kp.pub, opts.deviceId);
    if (!L.sameSig(body.proof, L.proof(keys, 'client', opts.code()))) {
      if (++failed >= 5) { failed = 0; lockedUntil = now + 60000; }
      return send(res, 403, { error: 'Wrong pairing code' });
    }
    failed = 0;
    const payload = JSON.parse(L.open(keys.enc, 'client', body.box));
    const clientId = L.randomHex(8);
    opts.addLink({ clientId, key: L.b64(keys.link), name: String(payload.name || '') });
    return send(res, 200, {
      proof: L.proof(keys, 'receiver', opts.code()),
      box: L.seal(keys.enc, 'receiver', JSON.stringify({ clientId, deviceId: opts.deviceId, name: nameNow() }))
    });
  }

  /** Checks the request signature; returns { link, nonce } or sends the refusal and returns null. */
  function verify(req, res, body) {
    const h = req.headers;
    if (h['x-secret'] && !h['x-client']) { send(res, 426, { error: 'updateNeeded' }); return null; }
    const link = opts.getLinks().find((l) => l.clientId === h['x-client']);
    if (!link) { send(res, 401, { error: 'Device is not paired' }); return null; }
    const now = clock();
    const time = Number(h['x-time']);
    if (!Number.isFinite(time) || Math.abs(time - now) > MAX_SKEW) { send(res, 401, { error: 'clock', time: now }); return null; }
    const nonce = String(h['x-nonce'] || '');
    for (const [n, exp] of nonces) if (exp < now) nonces.delete(n);
    if (nonce.length < 16 || nonces.has(nonce)) { send(res, 401, { error: 'Request repeated' }); return null; }
    const want = L.requestSig(L.unb64(link.key), req.method, req.url || '', time, nonce, body);
    if (!L.sameSig(h['x-sig'], want)) { send(res, 401, { error: 'Device is not paired' }); return null; }
    nonces.set(nonce, now + 2 * MAX_SKEW);
    return { link, nonce };
  }

  async function handle(req, res) {
    const url = (req.url || '').split('?')[0];
    try {
      if (req.method === 'GET' && url === '/info') {
        return send(res, 200, { deviceId: opts.deviceId, name: nameNow(), version: PROTOCOL_VERSION, kind: 'pc', acceptsTokens: false, time: clock(), ...(await opts.info()) });
      }
      // a controller with the old protocol (v2)
      if (req.method === 'POST' && url === '/pair') return send(res, 426, { error: 'updateNeeded' });
      const text = req.method === 'POST' || req.method === 'DELETE' ? await readBody(req) : '';
      if (req.method === 'POST' && url === '/pair/start') return send(res, 200, pairStart(JSON.parse(text || '{}')));
      if (req.method === 'POST' && url === '/pair/finish') return pairFinish(res, JSON.parse(text || '{}'));
      const ok = verify(req, res, text);
      if (!ok) return;
      const sign = { key: ok.link.key, nonce: ok.nonce };
      try {
        if (req.method === 'GET' && url === '/state') return send(res, 200, await opts.state(), sign);
        if (req.method === 'POST' && url === '/cmd') return send(res, 200, await opts.command(JSON.parse(text || '{}')), sign);
        if (req.method === 'DELETE' && url === '/pair') { opts.removeLink(ok.link.clientId); return send(res, 200, { ok: true }, sign); }
        return send(res, 404, { error: 'Unknown request' }, sign);
      } catch (e) {
        return send(res, 500, { error: String((e && e.message) || e) }, sign);
      }
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

function codedError(code, message, status) {
  const e = new Error(message || code);
  e.code = code;
  if (status) e.status = status;
  return e;
}

/** Signing key of a paired device: { clientId, key } (pairings from protocol v2 keep working, derived from their secret). */
function linkOf(d) {
  if (d.clientId && d.key) return { clientId: d.clientId, key: d.key };
  if (d.secret) return L.legacyLink(d.secret);
  return null;
}

/** Receiver clock minus ours, per device (requests carry the receiver's time). */
const clockOffset = new Map();

/**
 * One HTTP request. With link: signed, and the answer must carry the receiver's signature – otherwise
 * it is not the paired device (error code 'identity').
 */
async function request(method, url, body, link, timeoutMs, deviceKey) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs || 5000);
  try {
    const text = body ? JSON.stringify(body) : '';
    const headers = { 'Content-Type': 'application/json; charset=utf-8' };
    let nonce = null;
    if (link) {
      const u = new URL(url);
      const time = Date.now() + (clockOffset.get(deviceKey) || 0);
      nonce = L.randomHex(16);
      Object.assign(headers, {
        'X-Client': link.clientId, 'X-Time': String(time), 'X-Nonce': nonce,
        'X-Sig': L.requestSig(L.unb64(link.key), method, u.pathname + u.search, time, nonce, text)
      });
    }
    const res = await fetch(url, { method, headers, body: text || undefined, signal: ctrl.signal });
    const answer = await res.text();
    let data = null;
    try { data = answer ? JSON.parse(answer) : null; } catch (e) {}
    if (link && res.status !== 401 && res.status !== 426) {
      const sig = res.headers.get('x-sig');
      if (!L.sameSig(sig, L.responseSig(L.unb64(link.key), nonce, res.status, answer))) {
        throw codedError('identity', 'The device did not prove it is the paired one');
      }
    }
    if (!res.ok) {
      if (res.status === 426) throw codedError('peerUpdate', 'updateNeeded', 426);
      const err = new Error((data && data.error) || ('HTTP ' + res.status));
      err.status = res.status;
      if (data && data.code) err.code = data.code;
      if (data && data.error === 'clock' && Number.isFinite(data.time)) err.receiverTime = data.time;
      throw err;
    }
    return data;
  } finally {
    clearTimeout(t);
  }
}

/** Signed request to a paired device; a clock difference is learned from the refusal and the request repeated once. */
async function signed(d, method, path, body, timeoutMs) {
  const link = linkOf(d);
  if (!link) throw codedError('repair', 'Device is not paired', 401);
  const key = d.deviceId || d.host;
  try {
    return await request(method, deviceUrl(d, path), body, link, timeoutMs, key);
  } catch (e) {
    if (e.status === 401 && e.receiverTime == null) {
      // refused: an old version of the app on the other device does not know signed requests
      const info = await remoteInfo(d.host, d.port, 2000).catch(() => null);
      if (info && !(info.version >= 3)) throw codedError('peerUpdate', 'updateNeeded', 426);
      throw e;
    }
    if (e.receiverTime == null) throw e;
    clockOffset.set(key, e.receiverTime - Date.now());
    return request(method, deviceUrl(d, path), body, link, timeoutMs, key);
  }
}

function deviceUrl(d, path) {
  const host = d.host.includes(':') && !d.host.startsWith('[') ? `[${d.host}]` : d.host;
  return `http://${host}:${d.port}${path}`;
}

const remoteState = (d) => signed(d, 'GET', '/state', null, 3000);
const remoteCmd = (d, cmd) => signed(d, 'POST', '/cmd', cmd, 10000);
const remoteInfo = (host, port, timeoutMs) => request('GET', deviceUrl({ host, port }, '/info'), null, null, timeoutMs || 3000);
/** Tells the receiver to forget this controller (best effort – it may be off). */
const remoteUnpair = (d) => signed(d, 'DELETE', '/pair', null, 3000);

/** Does the paired device really answer at host:port (its signature, not just its public deviceId)? */
async function verifyAt(d, host, port) {
  try {
    await signed({ ...d, host, port }, 'GET', '/state', null, 3000);
    return true;
  } catch (e) {
    return false;
  }
}

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
  if (!(info.version >= 3)) throw codedError('peerUpdate', 'updateNeeded');
  if (Number.isFinite(info.time)) clockOffset.set(info.deviceId, info.time - Date.now());
  const req = { server: serverUrl, username, userId: userId || null, name: myName };
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
  const kp = L.newKeyPair();
  const start = await request('POST', deviceUrl({ host, port }, '/pair/start'), { clientPub: kp.pub }, null, 5000);
  const keys = L.pairKeys(L.sharedSecret(kp, start.receiverPub), start.pairId, kp.pub, start.receiverPub, info.deviceId);
  let resp;
  try {
    resp = await request('POST', deviceUrl({ host, port }, '/pair/finish'), {
      pairId: start.pairId, proof: L.proof(keys, 'client', code), box: L.seal(keys.enc, 'client', JSON.stringify(req))
    }, null, 8000);
  } catch (e) {
    // the TV reports it is not signed in to this account → the password is needed
    if (e.code === 'needPassword' && !req.accessToken) throw codedError('needPassword');
    throw e;
  }
  // the receiver proves it knows the code too (and so is the device whose code was typed)
  if (!L.sameSig(resp.proof, L.proof(keys, 'receiver', code))) throw codedError('identity', 'The device did not prove it is the paired one');
  const r = JSON.parse(L.open(keys.enc, 'receiver', resp.box));
  if (r.deviceId !== info.deviceId) throw codedError('identity', 'The device did not prove it is the paired one');
  return { deviceId: r.deviceId, name: r.name, clientId: r.clientId, key: L.b64(keys.link), host, port, kind: info.kind || 'tv' };
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
  remoteUnpair,
  verifyAt,
  linkOf,
  locate,
  RECEIVER_PORTS,
  needsPassword,
  pair,
  newCode
};
