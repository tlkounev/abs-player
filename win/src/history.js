// Listening history: the server's sessions of a book turned into rows of the mini player (the same rules as
// History.kt of the Android app – keep them in step). Pure functions, tested in test/history.test.js.
'use strict';

const SAME_POS = 3;

/** 0-based index of the chapter at `sec` ([{ start, end }]), or null without chapters. */
function chapterAt(chapters, sec) {
  if (!chapters || !chapters.length) return null;
  let i = -1;
  for (let k = 0; k < chapters.length; k++) if (chapters[k].start <= sec + 0.001) i = k;
  return i < 0 ? 0 : i;
}

const num = (v) => { const n = Number(v); return Number.isFinite(n) ? n : null; };
const text = (v) => (typeof v === 'string' && v.trim() ? v.trim() : null);

/** Sessions of GET /api/me/item/listening-sessions/:id → plain objects (broken entries left out). */
function parseSessions(page) {
  const list = page && Array.isArray(page.sessions) ? page.sessions : [];
  const out = [];
  for (const o of list) {
    if (!o || !text(o.id) || num(o.currentTime) == null) continue;
    const d = o.deviceInfo || {};
    out.push({
      id: o.id,
      start: num(o.startTime) || 0,
      end: num(o.currentTime),
      startedAt: num(o.startedAt) || 0,
      updatedAt: num(o.updatedAt) || num(o.startedAt) || 0,
      deviceId: text(d.deviceId),
      clientName: text(d.clientName),
      deviceName: text(d.deviceName),
      model: text(d.model),
      browserName: text(d.browserName)
    });
  }
  return out;
}

/** known: { [absDeviceId]: { name, kind: 'phone'|'tv'|'pc'|'web' } } */
function label(s, known) {
  if (s.deviceId && known && known[s.deviceId]) return known[s.deviceId];
  const web = /^abs web$/i.test(s.clientName || '') || (!s.model && !!s.browserName);
  const dn = s.deviceName && !/^unknown/i.test(s.deviceName) ? s.deviceName : null;
  return { name: dn || s.model || s.clientName || '?', kind: web ? 'web' : 'phone' };
}

function build(sessions, chapters, known, position) {
  const rows = sessions
    .filter((s) => s.updatedAt > 0 && Math.abs(s.end - s.start) >= 1)
    .sort((a, b) => b.updatedAt - a.updatedAt)
    .map((s) => {
      const d = label(s, known);
      const ec = chapterAt(chapters, s.end);
      return {
        id: s.id, start: s.start, end: s.end,
        startedAt: s.startedAt > 0 ? s.startedAt : s.updatedAt, updatedAt: s.updatedAt,
        device: d.name, kind: d.kind,
        startChapter: chapterAt(chapters, s.start), endChapter: ec,
        endInChapter: Math.max(0, s.end - (ec != null ? chapters[ec].start : 0)),
        now: false, furthest: false
      };
    });
  if (!rows.length) return rows;
  const nowIdx = position == null ? -1 : rows.findIndex((r) => Math.abs(r.end - position) <= SAME_POS);
  let far = 0;
  rows.forEach((r, i) => { if (r.end > rows[far].end) far = i; });
  const farAhead = rows.length >= 2 && far !== nowIdx && rows[far].end > (position == null ? -1 : position) + SAME_POS;
  rows.forEach((r, i) => { r.now = i === nowIdx; r.furthest = farAhead && i === far; });
  return rows;
}

module.exports = { SAME_POS, chapterAt, parseSessions, label, build };
