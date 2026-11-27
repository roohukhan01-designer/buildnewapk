'use strict';
/* Step B (M24): face tracking and speed ramps. Plain JS, shared by Android (WebView) and later Windows (Electron).
   Nothing here talks to the phone or to the AI. The phone only finds faces; the model only suggests ramps.
   This file turns both into safe, smooth values and is covered by shared/test/motion.test.js. */

const TRACK_MAX_POINTS = 90;
const RAMP_MIN_SPEED = 0.35, RAMP_MAX_SPEED = 2.5;
const RAMP_MAX_COUNT = 3;

function n(v) {
  if (typeof v === 'number') return Number.isFinite(v) ? v : NaN;
  if (typeof v === 'string' && v.trim() !== '') { const x = Number(v); return Number.isFinite(x) ? x : NaN; }
  return NaN;
}
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
const r3 = v => Math.round(v * 1000) / 1000;

/* raw: [[t, x|null], ...] from the phone. t = seconds from the clip start, x = face centre from -1 (left edge) to 1 (right edge),
   null = no face found in that picture. Returns { ok, coverage, track:[[t,x],...] } where track is a smooth virtual-camera path.
   The camera only moves when the face leaves a small dead zone, and it never moves faster than a person could pan by hand. */
function buildTrack(raw, opt) {
  const o = Object.assign({ dead: 0.10, tau: 0.55, vmax: 0.9, maxGap: 3.0, minCoverage: 0.3 }, opt || {});
  const out = { ok: false, coverage: 0, track: [] };
  if (!Array.isArray(raw)) return out;
  const pts = [];
  for (const p of raw) {
    if (!Array.isArray(p)) continue;
    const t = n(p[0]);
    if (!Number.isFinite(t) || t < 0) continue;
    const x = p[1] === null || p[1] === undefined ? NaN : n(p[1]);
    pts.push({ t, x: Number.isFinite(x) ? clamp(x, -1, 1) : null });
  }
  pts.sort((a, b) => a.t - b.t);
  if (pts.length < 3) return out;
  const found = pts.filter(p => p.x !== null);
  out.coverage = r3(found.length / pts.length);
  if (found.length < 3 || out.coverage < o.minCoverage) return out;

  // 1) fill the gaps: short gaps are interpolated, long gaps (or the edges) hold the last known place
  const filled = pts.map(p => p.x);
  for (let i = 0; i < pts.length; i++) {
    if (filled[i] !== null) continue;
    let l = i - 1; while (l >= 0 && pts[l].x === null) l--;
    let r = i + 1; while (r < pts.length && pts[r].x === null) r++;
    const hasL = l >= 0, hasR = r < pts.length;
    if (hasL && hasR && pts[r].t - pts[l].t <= o.maxGap) {
      const k = (pts[i].t - pts[l].t) / (pts[r].t - pts[l].t);
      filled[i] = pts[l].x + (pts[r].x - pts[l].x) * k;
    } else if (hasL && (!hasR || pts[i].t - pts[l].t <= pts[r].t - pts[i].t)) filled[i] = pts[l].x;
    else filled[i] = hasR ? pts[r].x : pts[l].x;
  }
  // 2) median of 3 removes a single wrong detection
  const med = filled.map((v, i) => {
    if (i === 0 || i === filled.length - 1) return v;
    const a = [filled[i - 1], v, filled[i + 1]].sort((p, q) => p - q);
    return a[1];
  });
  // 3) virtual camera: dead zone + smoothing + speed limit
  let cam = med[0];
  const cams = [[pts[0].t, cam]];
  for (let i = 1; i < pts.length; i++) {
    const dt = Math.max(0.001, pts[i].t - pts[i - 1].t);
    const diff = med[i] - cam;
    const want = Math.abs(diff) <= o.dead ? 0 : diff - Math.sign(diff) * o.dead;
    let step = want * (1 - Math.exp(-dt / o.tau));
    step = clamp(step, -o.vmax * dt, o.vmax * dt);
    cam = clamp(cam + step, -1, 1);
    cams.push([pts[i].t, cam]);
  }
  // 4) keep the list small
  let keep = cams;
  if (keep.length > TRACK_MAX_POINTS) {
    const stride = Math.ceil(keep.length / TRACK_MAX_POINTS);
    keep = keep.filter((_, i) => i % stride === 0 || i === keep.length - 1);
  }
  out.track = keep.map(([t, x]) => [r3(t), r3(x)]);
  out.ok = out.track.length >= 2;
  return out;
}

/* Position of the camera at time t (seconds from clip start). Linear between points, held at both ends. Null when there is no track. */
function trackAt(track, t) {
  if (!Array.isArray(track) || track.length === 0) return null;
  if (track.length === 1 || t <= track[0][0]) return track[0][1];
  const last = track[track.length - 1];
  if (t >= last[0]) return last[1];
  let lo = 0, hi = track.length - 1;
  while (hi - lo > 1) { const mid = (lo + hi) >> 1; if (track[mid][0] <= t) lo = mid; else hi = mid; }
  const a = track[lo], b = track[hi], k = (t - a[0]) / Math.max(1e-6, b[0] - a[0]);
  return a[1] + (b[1] - a[1]) * k;
}

/* Crop position for the editor preview. The preview positions its crop window with a value from -1 to 1 that is scaled by (1 - f),
   where f is the visible share of the video width. This turns "where the face is" into that value, never showing empty edges. */
function previewX(faceX, f) {
  if (!(f < 0.999)) return 0;
  return clamp(faceX / (1 - f), -1, 1);
}

/* Speed ramps from the AI: [{a, b, speed}] in seconds from the clip start. Returns a safe, sorted list.
   Rules: at most 3; speed 0.35 to 2.5; changes under 10 percent are ignored; each ramp 0.5 to 4 s (slow) or 0.5 to 6 s (fast);
   nothing in the first 0.8 s (the hook) or the last 0.5 s; no overlaps, at least 0.5 s apart; slow-motion never takes more than a quarter of the clip. */
function cleanRamps(list, dur) {
  if (!Array.isArray(list) || !(dur > 3)) return [];
  const c = [];
  for (const r of list) {
    if (!r || typeof r !== 'object') continue;
    let a = n(r.a), b = n(r.b), s = n(r.speed);
    if (!Number.isFinite(a) || !Number.isFinite(b) || !Number.isFinite(s)) continue;
    s = clamp(s, RAMP_MIN_SPEED, RAMP_MAX_SPEED);
    if (Math.abs(s - 1) < 0.1) continue;
    a = Math.max(a, 0.8); b = Math.min(b, dur - 0.5);
    const len = b - a, max = s < 1 ? 4 : 6;
    if (len < 0.5) continue;
    if (len > max) b = a + max;
    c.push({ a: r3(a), b: r3(b), speed: r3(s) });
  }
  c.sort((p, q) => p.a - q.a);
  const kept = [];
  for (const r of c) {
    const prev = kept[kept.length - 1];
    if (prev && r.a < prev.b + 0.5) continue;
    kept.push(r);
    if (kept.length >= RAMP_MAX_COUNT) break;
  }
  let slow = 0;
  const out = [];
  for (const r of kept) {
    const len = r.b - r.a;
    if (r.speed < 1) { if (slow + len > dur * 0.25) continue; slow += len; }
    out.push(r);
  }
  return out;
}

/* Where a time in the original clip lands in the finished clip. Same maths as SpeedRamp.java. */
function mapTime(ramps, t) {
  let out = 0, pos = 0;
  for (const r of ramps) {
    if (t <= r.a) return out + (t - pos);
    out += r.a - pos; pos = r.a;
    if (t <= r.b) return out + (t - pos) / r.speed;
    out += (r.b - r.a) / r.speed; pos = r.b;
  }
  return out + (t - pos);
}

function outDuration(ramps, dur) { return mapTime(ramps, dur); }

if (typeof module !== 'undefined') module.exports = { buildTrack, trackAt, previewX, cleanRamps, mapTime, outDuration };
if (typeof window !== 'undefined') window.RoohuMotion = { buildTrack, trackAt, previewX, cleanRamps, mapTime, outDuration };
