import { Inflate } from 'pako';

const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };

/** Feeds regularly contain stray entities, so anything unknown is left as it is rather than rejected. */
export function decodeXml(s) {
  if (s.indexOf('<![CDATA[') >= 0) s = s.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, (_, t) => t.replace(/&/g, '&amp;'));
  if (s.indexOf('&') < 0) return s;
  return s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (all, e) => {
    if (e[0] !== '#') return ENTITIES[e] !== undefined ? ENTITIES[e] : all;
    const code = e[1] === 'x' || e[1] === 'X' ? parseInt(e.substring(2), 16) : parseInt(e.substring(1), 10);
    return code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : all;
  });
}

function attr(tag, name) {
  const m = new RegExp(`\\s${name}\\s*=\\s*("([^"]*)"|'([^']*)')`).exec(tag);
  if (!m) return null;
  return decodeXml(m[2] !== undefined ? m[2] : m[3]);
}

function firstText(inner, name) {
  const m = new RegExp(`<${name}(\\s[^>]*)?>([\\s\\S]*?)</${name}>`).exec(inner);
  return m ? decodeXml(m[2]).trim() : null;
}

/** Reads a programme's fields. Null if it has no title. */
export function parseProgrammeBody(inner) {
  const title = firstText(inner, 'title');
  if (!title) return null;
  const desc = firstText(inner, 'desc');
  const ep = /<episode-num\s[^>]*system\s*=\s*["']onscreen["'][^>]*>([\s\S]*?)<\/episode-num>/.exec(inner);
  return {
    title,
    sub: firstText(inner, 'sub-title') || null,
    desc: desc ? desc.substring(0, 600).trim() : null,
    cat: firstText(inner, 'category') || null,
    ep: ep ? decodeXml(ep[1]).trim() || null : null,
  };
}

export function parseChannelNames(inner) {
  const names = [];
  const re = /<display-name(\s[^>]*)?>([\s\S]*?)<\/display-name>/g;
  let m;
  while ((m = re.exec(inner))) names.push(decodeXml(m[2]).trim());
  return names;
}

/**
 * Finds channels and programmes in XMLTV text as it arrives, so a feed never has to fit in memory whole.
 * @param onChannel called with (id, inner XML).
 * @param onProgramme called with (channel, start, stop, inner XML). Return true to stop reading.
 */
export class XmltvScanner {
  constructor(onChannel, onProgramme) {
    this.onChannel = onChannel;
    this.onProgramme = onProgramme;
    this.buf = '';
    this.stopped = false;
  }

  push(text) {
    if (this.stopped) return;
    this.buf += text;
    const open = /<(channel|programme)[\s>]/g;
    let pos = 0;
    for (;;) {
      open.lastIndex = pos;
      const m = open.exec(this.buf);
      if (!m) {
        // A tag might be split across chunks, so keep enough of the end to find it next time.
        pos = Math.max(pos, this.buf.length - 12);
        break;
      }
      const tagEnd = this.buf.indexOf('>', m.index);
      if (tagEnd < 0) {
        pos = m.index;
        break;
      }
      const tag = this.buf.substring(m.index, tagEnd + 1);
      let inner = '';
      let end = tagEnd + 1;
      if (this.buf[tagEnd - 1] !== '/') {
        const close = `</${m[1]}>`;
        const closeAt = this.buf.indexOf(close, tagEnd);
        if (closeAt < 0) {
          pos = m.index;
          break;
        }
        inner = this.buf.substring(tagEnd + 1, closeAt);
        end = closeAt + close.length;
      }
      pos = end;
      if (m[1] === 'channel') {
        this.onChannel(attr(tag, 'id'), inner);
      } else if (this.onProgramme(attr(tag, 'channel'), attr(tag, 'start'), attr(tag, 'stop'), inner)) {
        this.stopped = true;
        break;
      }
    }
    this.buf = this.buf.substring(pos);
  }
}

const SLICE = 32 * 1024;
const BUDGET_MS = 12;

function pause() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/**
 * Feeds a downloaded file, gzipped or not, to a scanner a little at a time. Listings update while the
 * guide is in use, so it hands the TV back often enough for key presses to stay responsive. A truncated
 * or corrupt file still yields whatever came before the damage.
 */
export async function scanBytes(bytes, scanner) {
  const gzipped = bytes.length > 2 && bytes[0] === 0x1f && bytes[1] === 0x8b;
  const decoder = new TextDecoder('utf-8');
  let inflate = null;
  if (gzipped) {
    inflate = new Inflate();
    inflate.onData = (chunk) => scanner.push(decoder.decode(chunk, { stream: true }));
    inflate.onEnd = () => {};
  }
  let last = Date.now();
  for (let at = 0; at < bytes.length && !scanner.stopped; at += SLICE) {
    const slice = bytes.subarray(at, Math.min(at + SLICE, bytes.length));
    if (inflate) {
      inflate.push(slice, false);
      if (inflate.err) return;
    } else {
      scanner.push(decoder.decode(slice, { stream: true }));
    }
    if (Date.now() - last > BUDGET_MS) {
      await pause();
      last = Date.now();
    }
  }
}
