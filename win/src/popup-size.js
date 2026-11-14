// Size of the mini player window when a panel (chapter list, Play on) opens or closes – used by main.js and
// the tests. The window grows up when there is room above it, otherwise down; closing restores the player.
'use strict';

const POPUP_W = 380;
const POPUP_H = 236;

/**
 * s = { dir: null | 'up' | 'down', extra } (kept by the caller), b = current bounds, want = panel height,
 * wa = work area of the display. Returns { bounds | null, s } – bounds null = nothing to change.
 */
function listBounds(s, b, open, want, wa) {
  if (open && !s.dir) {
    const dir = b.y - want >= wa.y ? 'up' : 'down';
    return { bounds: { x: b.x, y: dir === 'up' ? b.y - want : b.y, width: POPUP_W, height: POPUP_H + want }, s: { dir, extra: want } };
  }
  if (open && want !== s.extra) {
    // already open, only another height (pairing expanded): the edge at the mini player stays in place
    return { bounds: { x: b.x, y: s.dir === 'up' ? b.y + s.extra - want : b.y, width: POPUP_W, height: POPUP_H + want }, s: { dir: s.dir, extra: want } };
  }
  if (!open && s.dir) {
    return { bounds: { x: b.x, y: s.dir === 'up' ? b.y + s.extra : b.y, width: POPUP_W, height: POPUP_H }, s: { dir: null, extra: 0 } };
  }
  if (!open && (Math.abs(b.height - POPUP_H) > 2 || Math.abs(b.width - POPUP_W) > 2)) {
    // no panel, but the window is still bigger (a late resize): back to the player's size, the edge nearer
    // the taskbar stays in place
    const bottom = b.y + b.height / 2 > wa.y + wa.height / 2;
    return { bounds: { x: b.x, y: bottom ? b.y + b.height - POPUP_H : b.y, width: POPUP_W, height: POPUP_H }, s };
  }
  return { bounds: null, s };
}

module.exports = { POPUP_W, POPUP_H, listBounds };
