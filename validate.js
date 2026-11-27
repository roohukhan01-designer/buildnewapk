'use strict';
/* Shared by Android (WebView) and Windows (Electron): validates AI highlight JSON and export settings locally before
   anything is rendered. Never trusts the model: every value is type-checked, every timestamp is checked against real
   media metadata and the real transcript, and every string is cleaned and length-limited. */
const SCHEMA_VERSION = 2;
const CATEGORIES = ['gaming', 'podcast', 'interview', 'movie', 'sports', 'vlog', 'tutorial', 'reaction', 'documentary', 'general'];
const MOODS = ['intense', 'upbeat', 'calm'];
const CAPTION_STYLES = ['clean', 'pop', 'kara', 'bar', 'line', 'fire', 'ice', 'gold', 'neon', 'hype', 'reveal'];
const MAX_JSON_CHARS = 1000000;
const MAX_ITEMS = 200;

/* Strict number: only real finite numbers or numeric strings. null, '', true, [] and {} are rejected (Number(null) is 0). */
function num(v) {
  if (typeof v === 'number') return Number.isFinite(v) ? v : NaN;
  if (typeof v === 'string' && v.trim() !== '') { const n = Number(v); return Number.isFinite(n) ? n : NaN; }
  return NaN;
}

/* Plain text only: drops control characters, collapses whitespace, enforces a length limit. */
function cleanText(v, max) {
  if (typeof v !== 'string') return '';
  return v.replace(/[\u0000-\u001F\u007F]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, max);
}

/* Finds the first complete JSON array/object in a model reply by bracket matching (ignores brackets inside strings). */
function extractJson(t) {
  const s = t.search(/[\[{]/);
  if (s < 0) return null;
  const open = t[s], close = open === '[' ? ']' : '}';
  let depth = 0, inStr = false, esc = false;
  for (let i = s; i < t.length; i++) {
    const c = t[i];
    if (inStr) { if (esc) esc = false; else if (c === '\\') esc = true; else if (c === '"') inStr = false; continue; }
    if (c === '"') inStr = true;
    else if (c === open) depth++;
    else if (c === close && --depth === 0) return t.slice(s, i + 1);
  }
  return null;
}

function parseAiJson(text) {
  if (typeof text !== 'string' || !text.trim()) return { ok: false, error: 'empty response' };
  if (text.length > MAX_JSON_CHARS) return { ok: false, error: 'response too large' };
  const raw = extractJson(text.replace(/```json|```/gi, ''));
  if (!raw) return { ok: false, error: 'no JSON found' };
  let v;
  try { v = JSON.parse(raw); } catch (e) { return { ok: false, error: 'invalid JSON: ' + e.message }; }
  const arr = Array.isArray(v) ? v : (v && typeof v === 'object' && Array.isArray(v.candidates) ? v.candidates : null);
  return arr ? { ok: true, items: arr } : { ok: false, error: 'no candidates array' };
}

/* Transcript words: [{w,a,b}]. Drops malformed entries and sorts by start time. */
function cleanWords(words) {
  if (!Array.isArray(words)) return [];
  const out = [];
  for (const x of words) {
    if (!x || typeof x !== 'object') continue;
    const a = num(x.a), b = num(x.b);
    if (Number.isFinite(a) && Number.isFinite(b) && a >= 0 && b > a) out.push({ w: cleanText(x.w, 40), a, b });
  }
  return out.sort((p, q) => p.a - q.a);
}

/* Moves a boundary out of a spoken word to the nearest pause. */
function snapToPause(t, words, dir, maxShift) {
  const w = (words || []).find(x => t > x.a + 0.05 && t < x.b - 0.05);
  if (!w) return { t, snapped: false };
  const cand = dir < 0 ? w.a - 0.05 : w.b + 0.1;
  return Math.abs(cand - t) <= maxShift ? { t: Math.max(0, cand), snapped: true } : { t, snapped: false, midSpeech: true };
}

function cleanOptions(o) {
  const d = { minLen: 8, maxLen: 60, maxShift: 2.5, overlap: 0.5, max: 10, minConf: 0.35 };
  const r = Object.assign({}, d);
  if (o && typeof o === 'object') {
    const mustBePositive = { minLen: 1, maxLen: 1, max: 1 };
    for (const k of Object.keys(d)) {
      const n = num(o[k]);
      if (Number.isFinite(n) && (mustBePositive[k] ? n > 0 : n >= 0)) r[k] = n;   // zero/negative lengths and counts fall back to the default
    }
  }
  r.minLen = Math.max(1, r.minLen);
  r.maxLen = Math.max(r.minLen, Math.min(600, r.maxLen));
  r.maxShift = Math.max(0, Math.min(10, r.maxShift));
  r.overlap = Math.max(0, Math.min(1, r.overlap));
  r.minConf = Math.max(0, Math.min(1, r.minConf));
  r.max = Math.max(1, Math.min(50, Math.floor(r.max)));
  return r;
}

/* Only known style keys with known values pass through; everything else the model sent is dropped. */
function cleanStyle(s) {
  const out = {};
  if (!s || typeof s !== 'object' || Array.isArray(s)) return out;
  if (MOODS.includes(s.mood)) out.mood = s.mood;
  if (CAPTION_STYLES.includes(s.captionStyle)) out.captionStyle = s.captionStyle;
  const f = cleanText(s.font, 40);
  if (f && /^[A-Za-z0-9 ]+$/.test(f)) out.font = f;
  return out;
}

function cleanScores(s) {
  const out = {};
  if (!s || typeof s !== 'object' || Array.isArray(s)) return out;
  let n = 0;
  for (const k of Object.keys(s)) {
    if (n >= 12) break;
    const v = num(s[k]);
    if (/^[a-z_]{1,24}$/.test(k) && Number.isFinite(v)) { out[k] = Math.max(0, Math.min(1, v)); n++; }
  }
  return out;
}

/* meta: {duration:number>0 seconds}. Returns {accepted, rejected, schema, error?}. Never throws. */
function validateCandidates(items, meta, words, opt) {
  const result = { accepted: [], rejected: [], schema: SCHEMA_VERSION };
  const duration = num(meta && meta.duration);
  if (!Number.isFinite(duration) || duration <= 0) { result.error = 'invalid media duration'; return result; }
  if (!Array.isArray(items)) { result.error = 'candidates must be an array'; return result; }
  opt = cleanOptions(opt);
  words = cleanWords(words);
  const rej = (it, reason) => result.rejected.push({ item: it, reason });
  const accepted = [];
  for (const it of items.slice(0, MAX_ITEMS)) {
    if (!it || typeof it !== 'object' || Array.isArray(it)) { rej(it, 'not an object'); continue; }
    let a = num(it.start), b = num(it.end);
    if (!Number.isFinite(a) || !Number.isFinite(b)) { rej(it, 'timestamps are not numbers'); continue; }
    if (a < 0 || b > duration + 0.5 || a >= duration) { rej(it, 'timestamp outside the video (' + duration.toFixed(1) + 's)'); continue; }
    b = Math.min(b, duration);
    if (b <= a) { rej(it, 'end is not after start'); continue; }
    const sa = snapToPause(a, words, -1, opt.maxShift), sb = snapToPause(b, words, +1, opt.maxShift);
    if (sa.midSpeech || sb.midSpeech) { rej(it, 'starts or ends in the middle of speech and no nearby pause was found'); continue; }
    a = sa.t; b = Math.min(sb.t, duration);
    const len = b - a;
    if (len < opt.minLen || len > opt.maxLen) { rej(it, 'duration ' + len.toFixed(1) + 's outside ' + opt.minLen + '-' + opt.maxLen + 's'); continue; }
    const conf = num(it.confidence);
    if (!Number.isFinite(conf) || conf < 0 || conf > 1 || conf < opt.minConf) { rej(it, 'confidence missing, outside 0-1, or too low'); continue; }
    accepted.push({
      start: a, end: b, duration: len, confidence: conf,
      category: CATEGORIES.includes(it.category) ? it.category : 'general',
      explanation: cleanText(it.explanation, 300), hook: cleanText(it.hook, 140), title: cleanText(it.title, 80),
      style: cleanStyle(it.style), scores: cleanScores(it.scores), snapped: !!(sa.snapped || sb.snapped)
    });
  }
  accepted.sort((x, y) => y.confidence - x.confidence);
  const kept = [];
  for (const c of accepted) {
    const dup = kept.find(k => Math.max(0, Math.min(k.end, c.end) - Math.max(k.start, c.start)) / Math.min(k.duration, c.duration) > opt.overlap);
    if (dup) rej(c, 'overlaps a higher-ranked clip'); else if (kept.length < opt.max) kept.push(c); else rej(c, 'over the clip limit');
  }
  result.accepted = kept;
  return result;
}


/* Validates the AI "director" answer: one entry per clip index with a title, hook strength, reason, mood and caption style.
   Anything outside the allowed values is dropped, never trusted. */
function validateDirector(items, count) {
  const out = { accepted: [], rejected: [] };
  if (!Array.isArray(items)) { out.error = 'answer is not a list'; return out; }
  const n = Number.isInteger(count) && count > 0 ? count : 0, seen = new Set();
  const rej = (it, reason) => out.rejected.push({ item: it, reason });
  for (const it of items.slice(0, MAX_ITEMS)) {
    if (!it || typeof it !== 'object' || Array.isArray(it)) { rej(it, 'not an object'); continue; }
    const i = num(it.i);
    if (!Number.isInteger(i) || i < 0 || i >= n) { rej(it, 'clip index outside the list'); continue; }
    if (seen.has(i)) { rej(it, 'duplicate clip index'); continue; }
    const title = cleanText(it.title, 80).split(' ').slice(0, 10).join(' ');
    if (!title) { rej(it, 'missing title'); continue; }
    const hook = num(it.hook);
    const rawPost = cleanText(it.postCaption, 400).replace(/[<>]/g, '');
    const hashtags = [...new Set(rawPost.match(/#[\p{L}\p{N}_]{2,30}/gu) || [])].slice(0, 6);
    const postCaption = cleanText(rawPost.replace(/#[\p{L}\p{N}_]+/gu, ''), 150);
    out.accepted.push({
      postCaption, hashtags,
      i, title, hook: Number.isFinite(hook) ? Math.max(1, Math.min(10, Math.round(hook))) : 0,
      why: cleanText(it.why, 140), mood: MOODS.includes(it.mood) ? it.mood : '',
      category: CATEGORIES.includes(it.category) ? it.category : 'general',
      captionStyle: CAPTION_STYLES.includes(it.captionStyle) ? it.captionStyle : ''
    });
    seen.add(i);
  }
  return out;
}

/* File names: no paths, no reserved characters, no trailing dots, never empty. */
function sanitizeFilename(name, fallback) {
  let n = cleanText(typeof name === 'string' ? name : '', 200)
    .replace(/[\\\/:*?"<>|]/g, '_').replace(/^\.+/, '').replace(/\.+$/, '').replace(/\.(mp4|mov|mkv|avi)$/i, '').trim();
  if (/^(con|prn|aux|nul|com[1-9]|lpt[1-9])$/i.test(n)) n = '_' + n;
  n = n.slice(0, 80).trim();
  return n || fallback || 'clip';
}

/* Export settings. Returns {ok, value, errors}; value is always safe to hand to the renderer. */
function validateExportConfig(cfg) {
  const errors = [];
  const c = cfg && typeof cfg === 'object' && !Array.isArray(cfg) ? cfg : {};
  if (c !== cfg) errors.push('export settings missing');
  const even = n => Math.round(n / 2) * 2;
  let w = num(c.width), h = num(c.height);
  if (!Number.isFinite(w) || !Number.isFinite(h)) { errors.push('width/height must be numbers'); w = 720; h = 1280; }
  if (w < 64 || h < 64 || w > 4320 || h > 4320) { errors.push('size must be between 64 and 4320 pixels'); w = Math.max(64, Math.min(4320, w)); h = Math.max(64, Math.min(4320, h)); }
  let fps = num(c.fps);
  if (![24, 30, 60].includes(fps)) { if (c.fps !== undefined) errors.push('fps must be 24, 30 or 60'); fps = 30; }
  let br = num(c.bitrate);
  if (!Number.isFinite(br)) br = 0;
  else if (br < 500000 || br > 50000000) { errors.push('bitrate must be between 0.5 and 50 Mbps'); br = Math.max(500000, Math.min(50000000, br)); }
  const fmt = c.format === undefined ? 'mp4' : c.format;
  if (fmt !== 'mp4') errors.push('only mp4 is supported');
  return {
    ok: errors.length === 0,
    value: { width: even(w), height: even(h), fps, bitrate: Math.round(br), format: 'mp4', filename: sanitizeFilename(c.filename, 'clip') },
    errors
  };
}

if (typeof module !== 'undefined') module.exports = { parseAiJson, validateCandidates, validateDirector, validateExportConfig, sanitizeFilename, snapToPause, cleanText, num, SCHEMA_VERSION, CATEGORIES };
if (typeof window !== 'undefined') window.RoohuValidate = { parseAiJson, validateCandidates, validateDirector, validateExportConfig, sanitizeFilename, cleanText, num };
