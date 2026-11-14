// node --test test/popup-size.test.js
// Sizing of the mini player: panels growing up / down, the stretched-window fix, scaling by dragging an edge.
'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { POPUP_W, POPUP_H, listBounds, resizeBounds } = require('../src/popup-size.js');

const WA = { x: 0, y: 0, width: 1920, height: 1040 };

test('a panel grows the window up when there is room above, closing restores it', () => {
  const b = { x: 1500, y: 790, width: POPUP_W, height: POPUP_H };
  const open = listBounds({ dir: null, extra: 0 }, b, true, 300, WA);
  assert.deepEqual(open.bounds, { x: 1500, y: 490, width: POPUP_W, height: POPUP_H + 300 });
  assert.equal(open.s.dir, 'up');
  const close = listBounds(open.s, open.bounds, false, 0, WA);
  assert.deepEqual(close.bounds, b);
  assert.equal(close.s.dir, null);
});

test('at the top of the screen the window grows down', () => {
  const r = listBounds({ dir: null, extra: 0 }, { x: 10, y: 20, width: POPUP_W, height: POPUP_H }, true, 300, WA);
  assert.equal(r.s.dir, 'down');
  assert.equal(r.bounds.y, 20);
});

test('a window left too tall without a panel is brought back (edge nearer the taskbar stays)', () => {
  const r = listBounds({ dir: null, extra: 0 }, { x: 1500, y: 700, width: POPUP_W, height: 320 }, false, 0, WA);
  assert.deepEqual(r.bounds, { x: 1500, y: 700 + 320 - POPUP_H, width: POPUP_W, height: POPUP_H });
  assert.equal(listBounds({ dir: null, extra: 0 }, { x: 1, y: 2, width: POPUP_W, height: POPUP_H + 1 }, false, 0, WA).bounds, null);
});

test('a scaled player: panels and the fix use the scaled size', () => {
  const size = { w: 570, h: 354 };
  const b = { x: 1300, y: 640, width: 570, height: 354 };
  const open = listBounds({ dir: null, extra: 0 }, b, true, 450, WA, size);
  assert.deepEqual(open.bounds, { x: 1300, y: 190, width: 570, height: 354 + 450 });
  assert.deepEqual(listBounds(open.s, open.bounds, false, 0, WA, size).bounds, b);
  assert.equal(listBounds({ dir: null, extra: 0 }, b, false, 0, WA, size).bounds, null);
});

test('dragging the top-left corner scales up, the bottom-right corner stays, aspect ratio kept', () => {
  const start = { bounds: { x: 1500, y: 790, width: POPUP_W, height: POPUP_H }, cursor: { x: 1500, y: 790 } };
  const r = resizeBounds('nw', start, { x: 1310, y: 790 }, WA); // 190 px to the left = 1.5×
  assert.equal(r.scale, 1.5);
  assert.equal(r.bounds.width, 570);
  assert.equal(r.bounds.height, 354);
  assert.equal(r.bounds.x + r.bounds.width, 1500 + POPUP_W);
  assert.equal(r.bounds.y + r.bounds.height, 790 + POPUP_H);
});

test('dragging the bottom edge scales by height', () => {
  const start = { bounds: { x: 100, y: 100, width: POPUP_W, height: POPUP_H }, cursor: { x: 200, y: 336 } };
  const r = resizeBounds('s', start, { x: 200, y: 336 + 118 }, WA); // + half the height = 1.5×
  assert.equal(r.scale, 1.5);
  assert.equal(r.bounds.y, 100);
  assert.equal(Math.abs(r.bounds.height / r.bounds.width - POPUP_H / POPUP_W) < 0.01, true);
});

test('with an open panel the whole window scales (player + panel)', () => {
  // 100 %: player 236 + Play on 300 = 536 high, grown up from y 790
  const start = { bounds: { x: 1500, y: 490, width: POPUP_W, height: 536 }, cursor: { x: 1500, y: 490 }, baseH: 536 };
  const r = resizeBounds('nw', start, { x: 1310, y: 490 }, WA);
  assert.equal(r.scale, 1.5);
  assert.deepEqual(r.bounds, { x: 1500 + POPUP_W - 570, y: 490 + 536 - 804, width: 570, height: 804 });
  // never higher than the work area
  const big = resizeBounds('nw', start, { x: 0, y: 0 }, WA);
  assert.ok(big.bounds.height <= WA.height && big.scale <= WA.height / 536);
});

test('the scale stays between 100 and 200 % and inside the work area', () => {
  const start = { bounds: { x: 100, y: 100, width: POPUP_W, height: POPUP_H }, cursor: { x: 480, y: 336 } };
  assert.equal(resizeBounds('se', start, { x: 100, y: 100 }, WA).scale, 1);
  assert.equal(resizeBounds('se', start, { x: 3000, y: 2000 }, WA).scale, 2);
  const small = { x: 0, y: 0, width: 1280, height: 400 };
  const r = resizeBounds('se', start, { x: 3000, y: 2000 }, small);
  assert.ok(r.bounds.height <= 400 && r.bounds.y >= 0 && r.bounds.y + r.bounds.height <= 400);
});
