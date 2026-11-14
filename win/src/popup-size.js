// Size of the mini player window when a panel (chapter list, Play on) opens or closes – used by main.js and
// the tests. The window grows up when there is room above it, otherwise down; closing restores the player.
'use strict';

const POPUP_W = 380;
const POPUP_H = 236;

/**
 * s = { dir: null | 'up' | 'down', extra } (kept by the caller), b = current bounds, want = panel height,
 * wa = work area of the display, size = the player's size (scaled). Returns { bounds | null, s } – bounds null =
 * nothing to change.
 */
function listBounds(s, b, open, want, wa, size) {
  const W = (size && size.w) || POPUP_W, H = (size && size.h) || POPUP_H;
  if (open && !s.dir) {
    const dir = b.y - want >= wa.y ? 'up' : 'down';
    return { bounds: { x: b.x, y: dir === 'up' ? b.y - want : b.y, width: W, height: H + want }, s: { dir, extra: want } };
  }
  if (open && want !== s.extra) {
    // already open, only another height (pairing expanded): the edge at the mini player stays in place
    return { bounds: { x: b.x, y: s.dir === 'up' ? b.y + s.extra - want : b.y, width: W, height: H + want }, s: { dir: s.dir, extra: want } };
  }
  if (!open && s.dir) {
    return { bounds: { x: b.x, y: s.dir === 'up' ? b.y + s.extra : b.y, width: W, height: H }, s: { dir: null, extra: 0 } };
  }
  if (!open && (Math.abs(b.height - H) > 2 || Math.abs(b.width - W) > 2)) {
    // no panel, but the window is still bigger (a late resize): back to the player's size, the edge nearer
    // the taskbar stays in place
    const bottom = b.y + b.height / 2 > wa.y + wa.height / 2;
    return { bounds: { x: b.x, y: bottom ? b.y + b.height - H : b.y, width: W, height: H }, s };
  }
  return { bounds: null, s };
}

/** Scale of the mini player when an edge / corner is dragged: the size follows the cursor, the aspect ratio
 * stays, 100–200 % and never bigger than the work area. start = { bounds, cursor, baseH }, c = cursor now;
 * baseH = the window's height at 100 % (the player + an open panel). Returns { scale, bounds } – the opposite
 * edge / corner stays in place. */
function resizeBounds(edge, start, c, wa) {
  const b = start.bounds;
  const BH = start.baseH || POPUP_H;
  const dx = c.x - start.cursor.x, dy = c.y - start.cursor.y;
  const byX = edge.includes('e') ? b.width + dx : edge.includes('w') ? b.width - dx : null;
  const byY = edge.includes('s') ? (b.height + dy) * b.width / b.height : edge.includes('n') ? (b.height - dy) * b.width / b.height : null;
  const w = byX != null && byY != null ? Math.max(byX, byY) : (byX != null ? byX : byY);
  let scale = Math.min(Math.max(w / POPUP_W, 1), 2, wa.width / POPUP_W, wa.height / BH);
  scale = Math.round(scale * 100) / 100;
  const nw = Math.round(POPUP_W * scale), nh = Math.round(BH * scale);
  let x = edge.includes('w') ? b.x + b.width - nw : edge.includes('e') ? b.x : Math.round(b.x + (b.width - nw) / 2);
  let y = edge.includes('n') ? b.y + b.height - nh : edge.includes('s') ? b.y : Math.round(b.y + (b.height - nh) / 2);
  x = Math.min(Math.max(x, wa.x), wa.x + wa.width - nw);
  y = Math.min(Math.max(y, wa.y), wa.y + wa.height - nh);
  return { scale, bounds: { x, y, width: nw, height: nh } };
}

module.exports = { POPUP_W, POPUP_H, listBounds, resizeBounds };
