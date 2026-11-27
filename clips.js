'use strict';
/* M27: safe clip windows. Free AI models often answer with times as text ("12:30"), too-short windows, or
   windows that overlap. This turns any such answer into usable clips instead of throwing the whole run away.
   Plain JS, shared by the phone page and (later) Windows. Covered by shared/test/clips.test.js. */

/* 90, "90", "1:30", "01:01:30", "90s" -> seconds. Returns NaN when it is not a time. */
function toSeconds(v) {
  if (typeof v === 'number') return Number.isFinite(v) ? v : NaN;
  if (typeof v !== 'string') return NaN;
  const s = v.trim().toLowerCase().replace(/s$/, '');
  if (s === '') return NaN;
  if (/^\d+(\.\d+)?$/.test(s)) return Number(s);
  const m = s.match(/^(?:(\d+):)?(\d{1,2}):(\d{1,2}(?:\.\d+)?)$/);
  if (m) return (m[1] ? Number(m[1]) * 3600 : 0) + Number(m[2]) * 60 + Number(m[3]);
  return NaN;
}

/* Fits a window inside the video: at least minLen, at most maxLen seconds. Too short windows are stretched, never dropped.
   Returns [start, end] or null when the video itself is too short or the input is not usable. */
function fitWindow(s, e, dur, minLen, maxLen) {
  s = toSeconds(s); e = toSeconds(e);
  if (!Number.isFinite(s) || !(dur > 1)) return null;
  const lo = Math.min(minLen, dur), hi = Math.max(lo, Math.min(maxLen, dur));
  s = Math.max(0, Math.min(s, dur - 1));
  if (!Number.isFinite(e) || e <= s) e = s + lo;
  e = Math.min(e, dur);
  if (e - s < lo) { e = Math.min(dur, s + lo); s = Math.max(0, e - lo); }
  if (e - s > hi) e = s + hi;
  if (e - s < 1) return null;
  return [Math.round(s * 100) / 100, Math.round(e * 100) / 100];
}

/* The AI sometimes counts from the start of the part it was given. If a window sits before that part but would fit inside it
   when shifted, shift it. offset = start of the part, partLen = its length (seconds). */
function unrelative(s, e, offset, partLen) {
  s = toSeconds(s); e = toSeconds(e);
  if (!Number.isFinite(s) || !Number.isFinite(e) || offset <= 0) return [s, e];
  if (s < offset - 5 && e <= partLen + 5) return [s + offset, e + offset];
  return [s, e];
}

const overlaps = (a, b) => a[0] < b[1] && a[1] > b[0];

/* Chooses up to `want` non-overlapping windows. picks = what the AI chose [{start,end,...}], pool = candidate windows [{s,e,score}]
   used to top up when the AI chose too few or none. Always returns something when the pool has anything usable. */
function chooseWindows(picks, pool, want, dur, minLen, maxLen) {
  const out = [], used = [];
  const take = (s, e, extra) => {
    const w = fitWindow(s, e, dur, minLen, maxLen);
    if (!w || used.some(u => overlaps(u, w))) return false;
    used.push(w); out.push(Object.assign({ s: w[0], e: w[1] }, extra || {})); return true;
  };
  for (const p of Array.isArray(picks) ? picks : []) { if (out.length >= want) break; if (p && typeof p === 'object') take(p.start, p.end, { pick: p }); }
  const rest = (Array.isArray(pool) ? pool : []).slice().sort((a, b) => (b.score || 0) - (a.score || 0));
  for (const m of rest) { if (out.length >= want) break; take(m.s, m.e, { fromPool: m }); }
  return out;
}

if (typeof module !== 'undefined') module.exports = { toSeconds, fitWindow, unrelative, chooseWindows };
if (typeof window !== 'undefined') window.RoohuClips = { toSeconds, fitWindow, unrelative, chooseWindows };
