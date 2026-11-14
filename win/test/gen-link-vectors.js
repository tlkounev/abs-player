// Generates android/app/src/test/resources/link-vectors.json: fixed keys and the results of linkcrypto.js,
// which LinkCryptoTest.kt (Android) must reproduce – so both implementations stay compatible.
// Run: node test/gen-link-vectors.js
'use strict';
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const L = require('../src/linkcrypto');

function fixedKey(seedHex) {
  const ecdh = crypto.createECDH('prime256v1');
  ecdh.setPrivateKey(Buffer.from(seedHex, 'hex'));
  return { ecdh, pub: L.b64(ecdh.getPublicKey()), priv: seedHex };
}
const client = fixedKey('1f'.repeat(32));
const receiver = fixedKey('2e'.repeat(32));
const pairId = 'p-0123456789abcdef', deviceId = 'dev-42', code = '482913';
const z = L.sharedSecret(receiver, client.pub);
const keys = L.pairKeys(z, pairId, client.pub, receiver.pub, deviceId);
const body = JSON.stringify({ action: 'seek', value: 1234.5 });
const legacy = L.legacyLink('0123456789abcdef0123456789abcdef0123456789abcdef');
const v = {
  clientPriv: client.priv, clientPub: client.pub,
  receiverPriv: receiver.priv, receiverPub: receiver.pub,
  pairId, deviceId, code,
  z: z.toString('hex'),
  confirm: keys.confirm.toString('hex'), enc: keys.enc.toString('hex'), link: keys.link.toString('hex'),
  clientProof: L.proof(keys, 'client', code), receiverProof: L.proof(keys, 'receiver', code),
  box: L.seal(keys.enc, 'client', JSON.stringify({ server: 'http://abs.local', username: 'ondra', accessToken: 'tok' })),
  boxPlain: JSON.stringify({ server: 'http://abs.local', username: 'ondra', accessToken: 'tok' }),
  body, time: 1763150000000, nonce: 'aabbccddeeff00112233445566778899',
  requestSig: L.requestSig(keys.link, 'post', '/cmd', 1763150000000, 'aabbccddeeff00112233445566778899', body),
  responseSig: L.responseSig(keys.link, 'aabbccddeeff00112233445566778899', 200, '{"ok":true}'),
  legacySecret: '0123456789abcdef0123456789abcdef0123456789abcdef', legacyClientId: legacy.clientId, legacyKey: legacy.key
};
const out = path.join(__dirname, '..', '..', 'android', 'app', 'src', 'test', 'resources', 'link-vectors.json');
fs.writeFileSync(out, JSON.stringify(v, null, 2) + '\n');
console.log('written', out);
