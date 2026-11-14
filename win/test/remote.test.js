// node --test test/remote.test.js
// Tests the "play on" protocol without Electron: receiver, pairing, commands and state conversions.
'use strict';
const test = require('node:test');
const assert = require('node:assert');
const remote = require('../src/remote.js');

function fakePlayer() {
  const st = {
    itemId: 'li_1', title: 'Kniha', author: 'Autor', durationSec: 3600, positionSec: 100,
    chapters: [{ title: 'Jedna', startSec: 0, endSec: 1800 }, { title: 'Dvě', startSec: 1800, endSec: 3600 }],
    isPlaying: false, isBuffering: false, isLoading: false, speed: 1, volume: 1, muted: false
  };
  const log = [];
  return {
    st, log,
    state: async () => ({ ...st }),
    command: async (c) => {
      log.push(c);
      if (c.action === 'toggle') st.isPlaying = !st.isPlaying;
      if (c.action === 'seek') st.positionSec = c.value;
      if (c.action === 'skip') st.positionSec += c.value;
      if (c.action === 'playItem') { st.itemId = c.itemId; st.positionSec = c.value ?? 0; st.isPlaying = true; }
      return { ...st };
    }
  };
}

let nextPort = 18765;
async function startReceiver(player, secrets) {
  const r = remote.createReceiver({
    deviceId: 'pc-1', name: 'Test PC', code: () => '123456',
    getSecrets: () => secrets, addSecret: (s) => secrets.push(s),
    info: async () => ({ loggedIn: true, server: 'https://abs.example', username: 'ondra' }),
    state: player.state, command: player.command
  });
  const port = await r.listen((nextPort += 20));
  return { r, port };
}

test('info, pairing, state and commands', async () => {
  const p = fakePlayer();
  const secrets = [];
  const { r, port } = await startReceiver(p, secrets);
  try {
    const info = await remote.remoteInfo('127.0.0.1', port);
    assert.equal(info.kind, 'pc');
    assert.equal(info.acceptsTokens, false);
    assert.equal(info.deviceId, 'pc-1');
    assert.equal(remote.needsPassword(info, 'https://abs.example', 'ondra'), false);

    // nothing without pairing
    await assert.rejects(remote.remoteState({ host: '127.0.0.1', port, secret: 'x' }), /not paired/);

    // wrong code
    await assert.rejects(remote.pair({ host: '127.0.0.1', port, code: '000000', serverUrl: 'https://abs.example', username: 'ondra', myName: 't' }), /pairing code/);

    const d = await remote.pair({ host: '127.0.0.1', port, code: '123456', serverUrl: 'https://abs.example', username: 'ondra', myName: 't' });
    assert.equal(d.deviceId, 'pc-1');
    assert.equal(d.kind, 'pc');
    assert.equal(secrets.length, 1);

    const np = await remote.remoteState(d);
    assert.equal(np.itemId, 'li_1');

    await remote.remoteCmd(d, remote.popupCmdToRemote('playPause'));
    assert.equal(p.st.isPlaying, true);
    await remote.remoteCmd(d, remote.popupCmdToRemote('jumpBack'));
    assert.equal(p.st.positionSec, 70);
    const after = await remote.remoteCmd(d, remote.popupCmdToRemote('playItem', { id: 'li_2', startTime: 42 }));
    assert.equal(after.itemId, 'li_2');
    assert.equal(after.positionSec, 42);
  } finally {
    r.close();
  }
});

test('pairing is rate limited', async () => {
  const p = fakePlayer();
  const { r, port } = await startReceiver(p, []);
  try {
    for (let i = 0; i < 5; i++) {
      await assert.rejects(remote.pair({ host: '127.0.0.1', port, code: '999999', serverUrl: 's', username: 'u', myName: 't' }));
    }
    await assert.rejects(remote.pair({ host: '127.0.0.1', port, code: '123456', serverUrl: 's', username: 'u', myName: 't' }), /Too many attempts/);
  } finally {
    r.close();
  }
});

test('a TV signed in to another account needs the password', () => {
  const tv = { acceptsTokens: true, loggedIn: true, server: 'https://abs.example/', username: 'jiny' };
  assert.equal(remote.needsPassword(tv, 'https://abs.example', 'ondra'), true);
  assert.equal(remote.needsPassword({ ...tv, username: 'ondra' }, 'https://ABS.example', 'ondra'), false);
  assert.equal(remote.needsPassword({ ...tv, loggedIn: false, username: 'ondra' }, 'https://abs.example', 'ondra'), true);
});

test('NowPlaying -> popup state', () => {
  const st = remote.nowPlayingToPopup({
    itemId: 'li_1', title: 'K', author: 'A', durationSec: 100, positionSec: 60, speed: 1.25, volume: 0.4, muted: false,
    chapters: [{ title: 'a', startSec: 0, endSec: 50 }, { title: 'b', startSec: 50, endSec: 100 }], sleepSec: -1, isPlaying: true
  }, { target: { remote: true } });
  assert.equal(st.chapter.title, 'b');
  assert.equal(st.chapter.index, 2);
  assert.equal(st.chapter.total, 2);
  assert.equal(st.hasChapters, true);
  assert.equal(st.rate, 1.25);
  assert.equal(st.sleepRemaining, null);
  assert.deepEqual(st.chaptersList[1], { n: 2, title: 'b', start: 50, end: 100 });
  // book without chapters (Android sends one pseudo-chapter)
  const one = remote.nowPlayingToPopup({ itemId: 'x', chapters: [{ title: 'x', startSec: 0, endSec: 10 }], positionSec: 1, durationSec: 10 }, {});
  assert.equal(one.chapter, null);
  assert.equal(one.hasChapters, false);
  assert.equal(remote.nowPlayingToPopup({ itemId: null }, {}).item, null);
});

test('popup state -> NowPlaying', () => {
  const np = remote.popupToNowPlaying({
    ready: true, item: { id: 'li_9', title: 'T', author: 'A' }, currentTime: 12, duration: 99, rate: 1.5,
    isPlaying: true, loading: false, volume: 0.7, muted: false, sleepRemaining: 125.4, notice: 'moved'
  }, [{ n: 1, title: 'c1', start: 0, end: 99 }]);
  assert.equal(np.itemId, 'li_9');
  assert.equal(np.speed, 1.5);
  assert.equal(np.sleepSec, 125);
  assert.deepEqual(np.chapters, [{ title: 'c1', startSec: 0, endSec: 99 }]);
  assert.ok(np.info);
  assert.equal(remote.popupToNowPlaying(null).itemId, null);
});

test('discovery without bonjour is a no-op', () => {
  const d = remote.createDiscovery({ deviceId: 'a', name: 'b', Bonjour: null });
  d.start(1); d.refresh();
  assert.deepEqual(d.list(), []);
  d.stop();
});

test('receiver on the next port (8765 taken by something else) is found, pair returns the name', async () => {
  const http = require('node:http');
  // something else runs on 8765, the receiver on 8766
  const other = http.createServer((q, s) => { s.writeHead(404); s.end('nope'); });
  await new Promise((ok, ko) => other.once('error', ko).listen(remote.DEFAULT_PORT, '0.0.0.0', ok)).catch(() => null);
  const secrets = [];
  const r = remote.createReceiver({
    deviceId: 'tv-9', name: () => 'Obývák', code: () => '123456',
    getSecrets: () => secrets, addSecret: (s) => secrets.push(s),
    info: async () => ({ loggedIn: true, server: 'https://abs.example', username: 'ondra' }),
    state: fakePlayer().state, command: fakePlayer().command
  });
  const port = await r.listen(remote.DEFAULT_PORT + 1);
  try {
    assert.equal(port, remote.DEFAULT_PORT + 1);
    const hit = await remote.locate('127.0.0.1', remote.DEFAULT_PORT);
    assert.equal(hit.port, remote.DEFAULT_PORT + 1);
    assert.equal(hit.info.name, 'Obývák');
    assert.equal(await remote.locate('127.0.0.1', remote.DEFAULT_PORT, 'jine-id'), null);
    const d = await remote.pair({ host: '127.0.0.1', port: remote.DEFAULT_PORT, code: '123456', serverUrl: 'https://abs.example', username: 'ondra', myName: 't' });
    assert.equal(d.port, remote.DEFAULT_PORT + 1);
    assert.equal(d.name, 'Obývák');
  } finally {
    r.close && r.close();
    other.close();
  }
});
