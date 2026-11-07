// The guide's rules, free of any browser API so they can be tested in Node. A port of the Android
// app's GuideLogic.kt: when one changes, the other should too.

export const HALF_HOUR = 30 * 60000;
export const REGION_WORLD = 'ALL';
export const CATEGORY_ALL = 'all';
export const CATEGORY_FAVORITES = 'favorites';
export const CATEGORY_CRT = 'crt';
export const LANGUAGE_ALL = 'all';
export const MAX_CROP = 25;

export const STRETCH = { FIT: 'FIT', FILL: 'FILL', STRETCH: 'STRETCH' };
export const STRETCH_ORDER = [STRETCH.FIT, STRETCH.FILL, STRETCH.STRETCH];
export const STRETCH_LABEL = { FIT: 'Fit (black bars)', FILL: 'Fill (crop edges)', STRETCH: 'Stretch to fill' };

/** Rows to move for one key event. Holding speeds up; single presses still move exactly one row. */
export function rowStep(repeatCount) {
  if (repeatCount < 6) return 1;
  if (repeatCount < 20) return 3;
  return 6;
}

/** Feeds that actually have a stream come first, because a channel's other feeds aren't what plays. */
export function taggedLanguages(feeds, usedFeeds) {
  let used = feeds.filter((f) => usedFeeds.has(f.id));
  if (!used.length) used = feeds.filter((f) => f.isMain);
  if (!used.length) used = feeds;
  const out = [];
  const sorted = used.filter((f) => f.isMain).concat(used.filter((f) => !f.isMain));
  for (const f of sorted) for (const l of f.languages) if (out.indexOf(l) < 0) out.push(l);
  return out;
}

// Not followed by a letter or digit. Spelled out because webOS 4's engine has no \p{L}.
const WORD_END = '(?![0-9A-Za-z\\u00c0-\\u024f\\u0400-\\u04ff])';
const SPANISH_NAME = new RegExp(
  '\\b(en espa[nñ]ol|espa[nñ]ol|latin[oa]|latin america|telenovelas?|novelas?|cine|pel[ií]culas?|' +
    'cr[ií]menes|deportes|noticias|adrenalina|pluto tv terror)' + WORD_END, 'i');
const PORTUGUESE_NAME = new RegExp('\\b(em portugu[eê]s|brasil|filmes)' + WORD_END, 'i');

/** iptv-org tags many Spanish and Portuguese channels as English, so a channel's name overrules an English tag. */
export function correctLanguages(names, tagged) {
  if (tagged[0] !== 'eng') return tagged;
  if (names.some((n) => SPANISH_NAME.test(n))) return ['spa'];
  if (names.some((n) => PORTUGUESE_NAME.test(n))) return ['por'];
  return tagged;
}

/** Strict on purpose: a channel counts only for its main language, not every audio track it has. */
export function matchesLanguage(languages, selected) {
  return selected === LANGUAGE_ALL || languages[0] === selected;
}

/**
 * Whether a response shows a stream is usable. Null means it can't be told, as with rate limiting.
 * @param head the first bytes of the body, which for HLS must be a playlist and not an error page.
 */
export function probeVerdict(code, path, head) {
  if (code === 429) return null;
  if (code < 200 || code > 299) return false;
  const p = path.toLowerCase();
  if (/\.(mpd|ts|mp4|mkv)$/.test(p)) return true;
  return head.replace(/^[﻿ \r\n]+/, '').indexOf('#EXTM3U') === 0;
}

/** The size to draw video at inside a frame, in the frame's own pixels. */
export function crtVideoSize(frameW, frameH, tubeAspect, videoAspect, stretch) {
  if (stretch === STRETCH.STRETCH) return [frameW, frameH];
  const wider = videoAspect > tubeAspect;
  const matchWidth = stretch === STRETCH.FIT ? wider : !wider;
  if (matchWidth) return [frameW, Math.floor(frameH * tubeAspect / videoAspect + 0.5)];
  return [Math.floor(frameW * videoAspect / tubeAspect + 0.5), frameH];
}

/** The shape of a video once cropX and cropY percent are removed from each side and from top and bottom. */
export function croppedAspect(videoAspect, cropX, cropY) {
  return videoAspect * (100 - 2 * cropX) / (100 - 2 * cropY);
}

/** The size to draw a whole video at so that its cropped part comes out at w by h. */
export function uncroppedSize(w, h, cropX, cropY) {
  return [Math.floor(w * 100 / (100 - 2 * cropX) + 0.5), Math.floor(h * 100 / (100 - 2 * cropY) + 0.5)];
}

/** Reads a saved "x,y" crop. Anything unreadable means no crop. */
export function parseCrop(saved) {
  const parts = saved ? String(saved).split(',') : [];
  const x = /^-?\d+$/.test(parts[0]) ? Number(parts[0]) : null;
  const y = /^-?\d+$/.test(parts[1]) ? Number(parts[1]) : null;
  if (x === null || y === null) return [0, 0];
  const clamp = (v) => Math.min(MAX_CROP, Math.max(0, v));
  return [clamp(x), clamp(y)];
}

/** Compares dotted versions numerically, so 1.10 is newer than 1.9. A leading "v" is ignored. */
export function isNewerVersion(remote, current) {
  const parts = (v) => v.trim().replace(/^v/, '').split('.').map((n) => parseInt(n, 10) || 0);
  const a = parts(remote);
  const b = parts(current);
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const x = a[i] || 0;
    const y = b[i] || 0;
    if (x !== y) return x > y;
  }
  return false;
}

/** Rounds down to the half hour, the guide's column boundary. */
export function floorHalfHour(t) {
  return t - (((t % HALF_HOUR) + HALF_HOUR) % HALF_HOUR);
}

const NAME_NOISE = /\b(hd|fhd|uhd|sd|4k|tv|east|west|channel|the)\b/g;
// Combining marks, punctuation, symbols and spaces. Spelled out because webOS 4 has no \p{M} or \p{L}.
const MARKS = /[̀-ͯ҃-҉֑-ׇֽֿׁׂׅׄؐ-ًؚ-ٰٟۖ-ۜ۟-۪ۤۧۨ-ۭऀ-ःऺ-ॏ॑-ॗัิ-ฺ็-๎᪰-᫿᷀-᷿⃐-⃿︠-︯]+/g;
const NON_NAME = /[\u0000-/:-@[-`{-©«-´¶-¹»-¿×÷˂-˿ -⯿⸀-⹿　-〄〈-〠︐-﹯＀-／：-＠［-｀｛-･\ud800-\udfff￰-￿]/g;

/** Reduces a channel name to a key for matching across data sets, which spell the same channel differently. */
export function normalizeName(name) {
  if (!name || !name.trim()) return '';
  const folded = name.normalize('NFKD').replace(MARKS, '').toLowerCase();
  return folded.replace(NAME_NOISE, ' ').replace(NON_NAME, '');
}

/** Parses XMLTV times such as "20261106155700 +0000". Returns null if malformed. */
export function parseXmltvTime(s) {
  if (!s || s.length < 12 || !/^\d{12}/.test(s)) return null;
  const n = (a, b) => parseInt(s.substring(a, b), 10);
  const secs = s.length >= 14 && /^\d\d$/.test(s.substring(12, 14)) ? n(12, 14) : 0;
  let ms = Date.UTC(n(0, 4), n(4, 6) - 1, n(6, 8), n(8, 10), n(10, 12), secs);
  const tz = s.substring(Math.min(14, s.length)).trim();
  if (tz.length >= 5 && (tz[0] === '+' || tz[0] === '-')) {
    const offset = ((parseInt(tz.substring(1, 3), 10) || 0) * 60 + (parseInt(tz.substring(3, 5), 10) || 0)) * 60000;
    ms += tz[0] === '+' ? -offset : offset;
  }
  return ms;
}

/** Lays programmes out across [from, to): trims overlaps and fills gaps, so every moment belongs to one block. */
export function buildBlocks(programmes, from, to) {
  const out = [];
  let cursor = from;
  for (const p of programmes) {
    if (p.stop <= cursor) continue;
    if (p.start >= to) break;
    if (p.start > cursor) out.push({ start: cursor, stop: p.start, programme: null });
    const stop = Math.min(p.stop, to);
    out.push({ start: Math.max(p.start, cursor), stop, programme: p });
    cursor = stop;
  }
  if (cursor < to) out.push({ start: cursor, stop: to, programme: null });
  return out;
}

/**
 * Decides which app channel each XMLTV channel feeds. An exact ID wins over a name match, and a channel
 * takes listings from only one XMLTV channel, because a name like "ABC" can match hundreds of affiliates.
 * @param xml Map of XMLTV ID to display names, in feed order.
 * @param direct Map of XMLTV ID to channel IDs.
 * @param aliases Map of normalized name to channel IDs.
 * @param alreadyClaimed Set of channels another source already covers.
 * @returns Map of XMLTV ID to the channel IDs it feeds.
 */
export function resolveXmlChannels(xml, direct, aliases, alreadyClaimed) {
  const taken = new Set(alreadyClaimed);
  const out = new Map();
  const claim = (xmlId, ch) => {
    if (taken.has(ch)) return;
    taken.add(ch);
    if (!out.has(xmlId)) out.set(xmlId, []);
    out.get(xmlId).push(ch);
  };
  for (const xmlId of xml.keys()) for (const ch of direct.get(xmlId) || []) claim(xmlId, ch);
  for (const [xmlId, names] of xml) {
    for (const name of names) {
      const key = normalizeName(name);
      if (key.length < 2) continue;
      for (const ch of aliases.get(key) || []) claim(xmlId, ch);
    }
  }
  return out;
}

export function streamHeight(s) {
  return parseInt(s.quality || '', 10) || 0;
}

export function streamLabel(s) {
  return (s.quality || 'Auto') + (s.geoBlocked ? ' · geo-blocked' : '') + (s.notAlwaysOn ? ' · not 24/7' : '');
}

/**
 * Best stream first: remembered good one, then not geo-blocked, always-on, highest quality. Ties keep
 * their order, which Array.sort doesn't promise on webOS's older engines.
 */
export function orderStreams(streams, lastGoodUrl) {
  const key = (s) => [
    s.url === lastGoodUrl ? 0 : 1,
    s.geoBlocked ? 1 : 0,
    s.notAlwaysOn ? 1 : 0,
    -Math.min(streamHeight(s), 1080),
  ];
  return streams
    .map((s, i) => ({ s, i, k: key(s) }))
    .sort((a, b) => {
      for (let n = 0; n < a.k.length; n++) if (a.k[n] !== b.k[n]) return a.k[n] - b.k[n];
      return a.i - b.i;
    })
    .map((e) => e.s);
}

/**
 * The order to try streams in on webOS. A web app can't set request headers on video, so a stream that
 * asks for its own User-Agent or Referer often won't play. It stays in the list, but only after the rest,
 * unless it has already worked here.
 */
export function playbackOrder(streams, lastGoodUrl) {
  const ordered = orderStreams(streams, lastGoodUrl);
  const needsHeaders = (s) => (s.userAgent || s.referrer) && s.url !== lastGoodUrl;
  return ordered.filter((s) => !needsHeaders(s)).concat(ordered.filter(needsHeaders));
}
