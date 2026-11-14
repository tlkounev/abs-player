// Crypto of the "Play on" link (protocol v3), the same as LinkCrypto.kt of the Android app – keep them in step.
//
// Pairing: ECDH P-256 (raw uncompressed points, base64) → Z. With T = "absplayer-pair-v3|pairId|clientPub|
// receiverPub|deviceId" and salt = SHA-256(T): kConfirm / kEnc / link key = HKDF-SHA256(Z, salt, "confirm" /
// "enc" / "link"). The code shown on the receiver is checked by HMAC(kConfirm, "client|code") and
// HMAC(kConfirm, "receiver|code"); credentials travel in AES-256-GCM boxes under kEnc. A passive listener
// learns nothing; the link key never travels.
//
// Requests: X-Client, X-Time (receiver clock, ms), X-Nonce, X-Sig = HMAC(link, "req\nMETHOD\npath\ntime\nnonce\n
// sha256hex(body)"); the receiver answers with X-Sig = HMAC(link, "resp\nnonce\nstatus\nsha256hex(body)"), so the
// client also knows it talks to the paired device.
'use strict';
const crypto = require('crypto');

const b64 = (buf) => Buffer.from(buf).toString('base64');
const unb64 = (s) => Buffer.from(String(s), 'base64');
const sha256 = (data) => crypto.createHash('sha256').update(data).digest();
const sha256Hex = (text) => crypto.createHash('sha256').update(String(text), 'utf8').digest('hex');
const hmac = (key, data) => crypto.createHmac('sha256', key).update(String(data), 'utf8').digest();
const hkdf = (ikm, salt, info, len = 32) => Buffer.from(crypto.hkdfSync('sha256', ikm, salt, Buffer.from(info, 'utf8'), len));
const randomHex = (n) => crypto.randomBytes(n).toString('hex');

/** Constant-time comparison of two base64 signatures. */
function sameSig(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const x = Buffer.from(a), y = Buffer.from(b);
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

/** { ecdh, pub } – pub = raw uncompressed point, base64. */
function newKeyPair() {
  const ecdh = crypto.createECDH('prime256v1');
  ecdh.generateKeys();
  return { ecdh, pub: b64(ecdh.getPublicKey()) };
}

function sharedSecret(kp, peerPub) {
  const raw = unb64(peerPub);
  if (raw.length !== 65 || raw[0] !== 4) throw new Error('bad public key');
  return kp.ecdh.computeSecret(raw);
}

function pairKeys(z, pairId, clientPub, receiverPub, deviceId) {
  const salt = sha256(Buffer.from(`absplayer-pair-v3|${pairId}|${clientPub}|${receiverPub}|${deviceId}`, 'utf8'));
  return { confirm: hkdf(z, salt, 'confirm'), enc: hkdf(z, salt, 'enc'), link: hkdf(z, salt, 'link') };
}

const proof = (keys, side, code) => b64(hmac(keys.confirm, `${side}|${String(code).trim()}`));

/** AES-256-GCM: base64(iv || ciphertext || tag); aad = "client" / "receiver". */
function seal(key, aad, plain) {
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', key, iv);
  c.setAAD(Buffer.from(aad, 'utf8'));
  const ct = Buffer.concat([c.update(String(plain), 'utf8'), c.final()]);
  return b64(Buffer.concat([iv, ct, c.getAuthTag()]));
}

function open(key, aad, box) {
  const raw = unb64(box);
  if (raw.length < 28) throw new Error('bad box');
  const d = crypto.createDecipheriv('aes-256-gcm', key, raw.subarray(0, 12));
  d.setAAD(Buffer.from(aad, 'utf8'));
  d.setAuthTag(raw.subarray(raw.length - 16));
  return Buffer.concat([d.update(raw.subarray(12, raw.length - 16)), d.final()]).toString('utf8');
}

const requestSig = (link, method, path, time, nonce, body) =>
  b64(hmac(link, `req\n${String(method).toUpperCase()}\n${path}\n${time}\n${nonce}\n${sha256Hex(body || '')}`));

const responseSig = (link, nonce, status, body) =>
  b64(hmac(link, `resp\n${nonce}\n${status}\n${sha256Hex(body || '')}`));

/** Link of a pairing made with protocol v2 (a shared secret): both sides derive the same id and key. */
function legacyLink(secret) {
  return { clientId: sha256Hex('absplayer-v2|' + secret).slice(0, 16), key: b64(hkdf(Buffer.from(secret, 'utf8'), Buffer.alloc(0), 'link-v2')) };
}

module.exports = {
  b64, unb64, sha256Hex, hmac, hkdf, randomHex, sameSig,
  newKeyPair, sharedSecret, pairKeys, proof, seal, open, requestSig, responseSig, legacyLink
};
