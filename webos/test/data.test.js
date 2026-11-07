import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { CatalogBuilder, epgIndexFor } from '../src/data/catalog.js';
import { epgSourcesFor, importSource, readChannels, readProgrammes } from '../src/data/epg.js';
import { newerRelease } from '../src/data/updater.js';
import { decodeXml, parseProgrammeBody, scanBytes, XmltvScanner } from '../src/data/xmltv.js';

const XML = `<?xml version="1.0"?><tv>
<channel id="a.1"><display-name>Alpha &amp; Co</display-name><display-name>Alpha HD</display-name></channel>
<programme start="20261106150000 +0000" stop="20261106160000 +0000" channel="a.1">
  <title lang="en">News &#38; Views</title><sub-title>Part 2</sub-title><desc><![CDATA[Fish & chips]]></desc>
  <category>News</category><episode-num system="xmltv_ns">1.2.0</episode-num><episode-num system="onscreen">S02E03</episode-num>
</programme>
<channel id="b.2"><display-name>Bravo</display-name></channel>
<programme start="20261106160000 +0000" stop="20261106170000 +0000" channel="a.1"><title>Second</title></programme>
<programme start="20261106150000 +0000" stop="20261106160000 +0000" channel="b.2"><title>Bravo show</title></programme>
<programme start="20261106150000 +0000" stop="20261106160000 +0000" channel="b.2"><desc>No title, so skipped</desc></programme>
</tv>`;
const FROM = Date.UTC(2026, 10, 6, 14);
const TO = Date.UTC(2026, 10, 6, 18);

function bytes(s) {
  return new Uint8Array(Buffer.from(s, 'utf8'));
}

test('entities decode and unknown ones survive', () => {
  assert.equal(decodeXml('A &amp; B &#65; &#x41; &nope; &'), 'A & B A A &nope; &');
  assert.equal(decodeXml('<![CDATA[Fish & chips &amp;]]>'), 'Fish & chips &amp;');
});

test('a programme keeps its onscreen episode, not other numbering', () => {
  const p = parseProgrammeBody('<title>T</title><episode-num system="xmltv_ns">1.2</episode-num><episode-num system="onscreen">S1E2</episode-num>');
  assert.equal(p.ep, 'S1E2');
  assert.equal(parseProgrammeBody('<desc>none</desc>'), null);
});

test('the scanner copes with any chunk boundary', () => {
  const run = (size) => {
    const seen = [];
    const s = new XmltvScanner((id) => seen.push(`c:${id}`), (ch, a) => { seen.push(`p:${ch}:${a}`); return false; });
    for (let i = 0; i < XML.length; i += size) s.push(XML.substring(i, i + size));
    return seen;
  };
  const whole = run(XML.length);
  assert.equal(whole.length, 6);
  for (const size of [1, 2, 7, 13, 64, 500]) assert.deepEqual(run(size), whole, `chunk ${size}`);
});

for (const [label, encode] of [['gzipped', (s) => new Uint8Array(gzipSync(s))], ['plain', bytes]]) {
  test(`${label} feeds yield channels and programmes`, async () => {
    const data = encode(XML);
    const channels = await readChannels(data, true);
    assert.deepEqual(Array.from(channels), [['a.1', ['Alpha & Co', 'Alpha HD']], ['b.2', ['Bravo']]]);

    const rows = await readProgrammes(data, new Map([['a.1', ['A.us', 'A2.us']]]), FROM, TO);
    assert.deepEqual(Array.from(rows.keys()), ['A.us', 'A2.us']);
    const first = rows.get('A.us')[0];
    assert.equal(first.title, 'News & Views');
    assert.equal(first.sub, 'Part 2');
    assert.equal(first.desc, 'Fish & chips');
    assert.equal(first.cat, 'News');
    assert.equal(first.ep, 'S02E03');
    assert.equal(rows.get('A.us').length, 2);
  });
}

test('a feed that lists channels first stops at the first programme', async () => {
  const channels = await readChannels(bytes(XML), false);
  assert.deepEqual(Array.from(channels.keys()), ['a.1']);
});

test('a truncated download keeps what came before the damage', async () => {
  // Long enough to pass the inflater's output buffer, which is all that can be lost at the break.
  let long = '<tv><channel id="first"><display-name>F</display-name></channel>';
  for (let i = 0; i < 4000; i++) long += `<channel id="c${i}"><display-name>Channel number ${i * 7919}</display-name></channel>`;
  const zipped = gzipSync(long);
  const cut = new Uint8Array(zipped.subarray(0, Math.floor(zipped.length * 0.9)));
  const seen = [];
  await scanBytes(cut, new XmltvScanner((id) => seen.push(id), () => false));
  assert.ok(seen.includes('first'));
});

test('programmes outside the time window are dropped', async () => {
  const rows = await readProgrammes(bytes(XML), new Map([['a.1', ['A.us']]]), Date.UTC(2026, 10, 6, 16), TO);
  assert.equal(rows.get('A.us').length, 1);
  assert.equal(rows.get('A.us')[0].title, 'Second');
});

test('regions get their feeds, and UK maps to ISO GB', () => {
  const keys = (r) => epgSourcesFor(r).map((s) => s.key);
  assert.equal(keys('ALL').length, 4);
  assert.ok(keys('US').includes('epgshare-US2'));
  assert.ok(keys('US').includes('epgpw-US'));
  assert.ok(keys('UK').includes('epgpw-GB'));
  assert.ok(epgSourcesFor('ALL').every((s) => s.interleaved));
});

function fakeImport(status, etagBefore) {
  const saved = {};
  return {
    saved,
    net: { getBytes: async (_, etag) => ({ status, etag: 'new', sent: etag, bytes: bytes(XML) }) },
    store: {
      channelsFromOtherSources: () => new Set(),
      replaceSource: async (key, rows) => { saved[key] = rows; },
    },
    prefs: { etag: () => etagBefore, setEtag: (k, v) => { saved.etag = v; } },
  };
}

const INDEX = { aliases: new Map([['bravo', ['B.us']]]), direct: new Map([['a.1', ['A.us']]]) };
const SOURCE = { key: 's', url: 'u', interleaved: true };

test('an import stores matched channels and remembers the ETag', async () => {
  const f = fakeImport(200, null);
  assert.equal(await importSource(SOURCE, INDEX, FROM, f.net, f.store, f.prefs), true);
  assert.deepEqual(Array.from(f.saved.s.keys()).sort(), ['A.us', 'B.us']);
  assert.equal(f.saved.etag, 'new');
});

test('an unchanged feed changes nothing, and a failed one throws', async () => {
  const same = fakeImport(304, 'old');
  assert.equal(await importSource(SOURCE, INDEX, FROM, same.net, same.store, same.prefs), false);
  assert.equal(same.saved.s, undefined);
  const bad = fakeImport(500, null);
  await assert.rejects(importSource(SOURCE, INDEX, FROM, bad.net, bad.store, bad.prefs));
});

function builtCatalog() {
  const b = new CatalogBuilder();
  b.addStreams([
    { channel: 'Zed.us', url: 'https://x/z.m3u8', quality: '720p', labels: ['Not 24/7'], feed: 'SD' },
    { channel: 'Alpha.us', url: 'https://jmp2.uk/plu-abc123.m3u8', user_agent: 'UA', referrer: 'R', title: 'Alpha' },
    { channel: 'Adult.us', url: 'https://x/a.m3u8' },
    { channel: 'Blocked.us', url: 'https://x/b.m3u8' },
    { channel: null, url: 'https://x/orphan.m3u8' },
  ]);
  b.addBlocklist([{ channel: 'Blocked.us' }]);
  b.addChannels([
    { id: 'Zed.us', name: 'Zed', alt_names: ['Zed East'], country: 'US', categories: ['news'], is_nsfw: false },
    { id: 'Alpha.us', name: 'alpha', alt_names: [], country: 'UK', categories: [], is_nsfw: false },
    { id: 'Adult.us', name: 'Adult', country: 'US', is_nsfw: true },
    { id: 'Blocked.us', name: 'Blocked', country: 'US', is_nsfw: false },
    { id: 'NoStream.us', name: 'NoStream', country: 'US', is_nsfw: false },
  ]);
  b.addFeeds([
    { channel: 'Zed.us', id: 'SD', is_main: true, languages: ['eng'] },
    { channel: 'Zed.us', id: 'Latin', is_main: false, languages: ['spa'] },
  ]);
  b.addLanguages([{ code: 'eng', name: 'English' }, { code: 'spa', name: 'Spanish' }]);
  b.addLogos([
    { channel: 'Zed.us', feed: 'SD', url: 'https://x/feed.png' },
    { channel: 'Zed.us', feed: null, url: 'https://x/main.png' },
  ]);
  b.addGuides([{ channel: 'Zed.us', site: 'i.mjh.nz', site_id: 'x#zed-id', site_name: 'Zed Network' }, { channel: 'Zed.us', site: 'other.tv', site_id: '99', site_name: null }]);
  b.addCategories([{ id: 'news', name: 'News' }, { id: 'auto', name: 'Auto' }]);
  b.addCountries([{ code: 'US', name: 'United States', flag: 'F' }]);
  return b.finish(5);
}

test('the catalog leaves out adult, blocked and streamless channels', () => {
  const { catalog } = builtCatalog();
  assert.deepEqual(catalog.channels.map((c) => c.id), ['Alpha.us', 'Zed.us']);
  assert.equal(catalog.fetchedAt, 5);
  assert.deepEqual(catalog.categories.map((c) => c.id), ['auto', 'news']);
});

test('channels keep streams, the main logo and the languages of feeds that play', () => {
  const zed = builtCatalog().catalog.channels[1];
  assert.equal(zed.logo, 'https://x/main.png');
  assert.deepEqual(zed.languages, ['eng']);
  assert.equal(zed.streams[0].notAlwaysOn, true);
  const alpha = builtCatalog().catalog.channels[0];
  assert.equal(alpha.streams[0].userAgent, 'UA');
  assert.equal(alpha.streams[0].referrer, 'R');
});

test('the listing index holds names and direct IDs for wanted channels only', () => {
  const { epgRows } = builtCatalog();
  const index = epgIndexFor(epgRows, new Set(['Zed.us']));
  assert.deepEqual(index.direct.get('zed-id'), ['Zed.us']);
  assert.deepEqual(index.aliases.get('zed'), ['Zed.us']);
  assert.deepEqual(index.aliases.get('zednetwork'), ['Zed.us']);
  assert.equal(index.direct.get('99'), undefined);
  const pluto = epgIndexFor(epgRows, new Set(['Alpha.us']));
  assert.deepEqual(pluto.direct.get('abc123'), ['Alpha.us']);
});

test('updates are only offered when the release has a webOS build', () => {
  const release = { tag_name: 'v9.0.0', assets: [{ name: 'OpenGuide.ipk' }] };
  assert.equal(newerRelease(release, '1.2.0'), 'v9.0.0');
  assert.equal(newerRelease(release, '9.0.0'), null);
  assert.equal(newerRelease({ tag_name: 'v9.0.0', assets: [{ name: 'OpenGuide.apk' }] }, '1.2.0'), null);
});

// esbuild lowers syntax but can't add missing functions, so these would only fail on an old TV.
test('sources avoid APIs that webOS 4 (Chromium 53) lacks', () => {
  const src = join(dirname(fileURLToPath(import.meta.url)), '../src');
  const banned = [
    /\.flatMap\(/, /\.flat\(/, /Object\.(entries|values|fromEntries)\(/, /\.pad(Start|End)\(/, /\.finally\(/, /\.matchAll\(/,
    /\.replaceAll\(/, /AbortController/, /structuredClone/, /\.trim(Start|End)\(/, /\\p\{/, /\.at\(-?\d/, /\.replaceWith\(/,
    /\.(append|prepend)\(/, /\.scrollTo\(/, /\.scrollIntoView\(/, /IntersectionObserver/, /ResizeObserver/, /\bfetch\(/,
    /^\s*(export\s+)?import\s*\(/m, /\bdisplay:\s*grid/, /(^|[\s;{])gap:/, /aspect-ratio:/, /\binset:/,
  ];
  const walk = (dir) => readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
  for (const file of walk(src)) {
    const text = readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
    for (const re of banned) assert.ok(!re.test(text), `${file} uses ${re}`);
  }
});
