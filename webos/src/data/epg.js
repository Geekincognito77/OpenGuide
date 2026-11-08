import { parseXmltvTime, REGION_WORLD, resolveXmlChannels } from './logic.js';
import { parseChannelNames, parseProgrammeBody, scanBytes, XmltvScanner } from './xmltv.js';

const WINDOW_MS = 48 * 3600000;
const MJH = [['PlutoTV', 'Pluto TV'], ['SamsungTVPlus', 'Samsung TV Plus'], ['Plex', 'Plex'], ['Roku', 'Roku']];
const EPGSHARE = ('AE1 AL1 AR1 AT1 AU1 BA1 BB1 BE2 BG1 BR1 BR2 CA2 CH1 CL1 CO1 CR1 CY1 CZ1 DE1 DK1 DO1 EC1 ' +
  'EG1 ES1 FI1 FR1 GR1 HK1 HR1 HU1 ID1 IE1 IL1 IN1 IN2 IN4 IT1 JM1 JP1 JP2 KE1 KR1 KZ1 LT1 LU1 LV1 MN1 MT1 MX1 ' +
  'MY1 NG1 NL1 NO1 NZ1 PA1 PE1 PH1 PH2 PK1 PL1 PT1 RO1 RO2 RS1 SA1 SA2 SE1 SG1 SK1 SV1 TH1 TR1 TR3 UA1 UK1 US2 ' +
  'UY1 VN1 ZA1').split(' ');
const EPGPW = new Set(['AU', 'BR', 'CA', 'CN', 'DE', 'FR', 'GB', 'HK', 'ID', 'IN', 'JP', 'MY', 'NZ', 'PH', 'RU', 'SG', 'TW', 'US', 'VN']);

/**
 * Public XMLTV feeds for a region. Earlier sources win when two cover the same channel.
 * @param crossOriginOpen false where CORS applies. i.mjh.nz's feeds are then fetched from where it hosts them,
 *   as its redirect passes through a GitHub host that sends no CORS header, and EPGShare, which sends none,
 *   is left out.
 */
export function epgSourcesFor(region, crossOriginOpen = true) {
  const out = MJH.map(([dir, name]) => ({
    key: `mjh-${dir}`,
    name,
    url: crossOriginOpen
      ? `https://i.mjh.nz/${dir}/all.xml.gz`
      : `https://raw.githubusercontent.com/matthuisman/i.mjh.nz/refs/heads/master/${dir}/all.xml.gz`,
    interleaved: true,
  }));
  if (region === REGION_WORLD) return out;
  for (const f of crossOriginOpen ? EPGSHARE : []) {
    if (f.indexOf(region) === 0) {
      out.push({ key: `epgshare-${f}`, name: `EPGShare ${f}`, url: `https://epgshare01.online/epgshare01/epg_ripper_${f}.xml.gz`, interleaved: false });
    }
  }
  const iso = region === 'UK' ? 'GB' : region; // iptv-org says UK where ISO says GB
  if (EPGPW.has(iso)) out.push({ key: `epgpw-${iso}`, name: `EPG.PW ${iso}`, url: `https://epg.pw/xmltv/epg_${iso}.xml.gz`, interleaved: false });
  return out;
}

/** First pass: every channel in the file. Most feeds list them before any programmes, so it can stop there. */
export async function readChannels(bytes, interleaved) {
  const xml = new Map();
  const scanner = new XmltvScanner(
    (id, inner) => {
      if (id !== null && !xml.has(id)) xml.set(id, parseChannelNames(inner));
    },
    () => !interleaved,
  );
  await scanBytes(bytes, scanner);
  return xml;
}

/**
 * Second pass: the programmes of the channels that matched, inside the time window.
 * @returns Map of channel ID to programmes, each sorted by start.
 */
export async function readProgrammes(bytes, targets, from, to) {
  const out = new Map();
  const scanner = new XmltvScanner(
    () => {},
    (xmlId, startText, stopText, inner) => {
      const dest = targets.get(xmlId);
      if (!dest) return false;
      const start = parseXmltvTime(startText);
      const stop = parseXmltvTime(stopText);
      if (start === null || stop === null || stop <= from || start >= to || stop <= start) return false;
      const body = parseProgrammeBody(inner);
      if (!body) return false;
      const row = { start, stop, title: body.title, sub: body.sub, desc: body.desc, cat: body.cat, ep: body.ep };
      for (const ch of dest) {
        if (!out.has(ch)) out.set(ch, []);
        out.get(ch).push(row);
      }
      return false;
    },
  );
  await scanBytes(bytes, scanner);
  for (const rows of out.values()) rows.sort((a, b) => a.start - b.start || a.stop - b.stop);
  return out;
}

/**
 * Downloads one feed and stores what matches.
 * @param net provides getBytes.
 * @param store provides channelsFromOtherSources and replaceSource.
 * @param prefs remembers each feed's ETag.
 * @returns true if the source's rows changed.
 */
export async function importSource(source, index, now, net, store, prefs) {
  const res = await net.getBytes(source.url, prefs.etag(source.key));
  if (res.status === 304) return false;
  if (res.status < 200 || res.status > 299) throw new Error(`${source.key}: HTTP ${res.status}`);
  const xml = await readChannels(res.bytes, source.interleaved);
  const targets = resolveXmlChannels(xml, index.direct, index.aliases, store.channelsFromOtherSources(source.key));
  const rows = await readProgrammes(res.bytes, targets, now - 3600000, now + WINDOW_MS);
  await store.replaceSource(source.key, rows);
  prefs.setEtag(source.key, res.etag);
  return true;
}
