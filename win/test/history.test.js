// node --test test/history.test.js
const test = require('node:test');
const assert = require('node:assert');
const H = require('../src/history.js');

const ch = [{ start: 0, end: 100 }, { start: 100, end: 300 }, { start: 300, end: 900 }];
const s = (id, start, end, updatedAt, d = {}) => ({ id, start, end, startedAt: updatedAt - 1000, updatedAt, deviceId: null, clientName: null, deviceName: null, model: null, browserName: null, ...d });

test('chapter at a position', () => {
  assert.strictEqual(H.chapterAt(ch, 0), 0);
  assert.strictEqual(H.chapterAt(ch, 150), 1);
  assert.strictEqual(H.chapterAt(ch, 899), 2);
  assert.strictEqual(H.chapterAt([], 10), null);
});

test('newest first, empty sessions left out, now and furthest', () => {
  const rows = H.build([s('old', 0, 120, 1000), s('tv', 400, 410, 5000), s('phone', 300, 800, 3000), s('empty', 50, 50.5, 9000)], ch, {}, 410);
  assert.deepStrictEqual(rows.map((r) => r.id), ['tv', 'phone', 'old']);
  assert.ok(rows[0].now && !rows[0].furthest);
  assert.ok(rows[1].furthest && !rows[1].now);
  assert.strictEqual(rows[1].endChapter, 2);
  assert.strictEqual(rows[1].endInChapter, 500);
  assert.strictEqual(rows[1].startChapter, 2);
});

test('no furthest mark when we are already the furthest', () => {
  const rows = H.build([s('a', 0, 500, 2000), s('b', 0, 200, 1000)], ch, {}, 500);
  assert.ok(rows[0].now);
  assert.ok(!rows.some((r) => r.furthest));
});

test('device names: known ids, then what the server says', () => {
  const known = { tv1: { name: 'Obývák TV', kind: 'tv' } };
  assert.deepStrictEqual(H.label(s('x', 0, 1, 1, { deviceId: 'tv1', deviceName: 'Philips' }), known), { name: 'Obývák TV', kind: 'tv' });
  assert.deepStrictEqual(H.label(s('x', 0, 1, 1, { clientName: 'Abs Web', deviceName: 'Windows 10 Chrome', browserName: 'Chrome' }), known), { name: 'Windows 10 Chrome', kind: 'web' });
  assert.deepStrictEqual(H.label(s('x', 0, 1, 1, { clientName: 'Abs Android', deviceName: 'Unknown ', model: 'SM-X910' }), known), { name: 'SM-X910', kind: 'phone' });
});

test('tolerant parse of the server page', () => {
  const list = H.parseSessions({ sessions: [
    { id: 'a', startTime: '12.5', currentTime: 99, updatedAt: 1700000000000, deviceInfo: { deviceId: 'd', deviceName: ' Google Pixel 8 ' } },
    { id: 'b' }, null, { currentTime: 3 }] });
  assert.strictEqual(list.length, 1);
  assert.strictEqual(list[0].start, 12.5);
  assert.strictEqual(list[0].deviceName, 'Google Pixel 8');
  assert.strictEqual(list[0].startedAt, 0);
  assert.deepStrictEqual(H.parseSessions(null), []);
});
