// Mirrors the Android app's GuideLogicTest.kt, so both apps are held to the same rules.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  buildBlocks, croppedAspect, crtVideoSize, correctLanguages, guideRows, isBreakFiller, isNewerVersion, LANGUAGE_ALL,
  matchesLanguage, MAX_CROP, normalizeName, orderStreams, parseCrop, parseXmltvTime, playbackOrder, probeVerdict,
  resolveXmlChannels, rowStep, startingNext, STRETCH, taggedLanguages, uncroppedSize,
} from '../src/data/logic.js';

const prog = (start, stop) => ({ start, stop, title: 'p' });
const stream = (url, quality = null, geoBlocked = false, notAlwaysOn = false, extra = {}) => (
  { url, quality, geoBlocked, notAlwaysOn, userAgent: null, referrer: null, ...extra }
);
const map = (o) => new Map(Object.entries(o));

test('XMLTV times honour the offset', () => {
  assert.equal(parseXmltvTime('20261106155700 +0000'), 1793980620000);
  assert.equal(parseXmltvTime('20261106105700 -0500'), 1793980620000);
  assert.equal(parseXmltvTime('20261106212700 +0530'), 1793980620000);
});

test('XMLTV times without an offset are UTC', () => {
  assert.equal(parseXmltvTime('20261106155700'), 1793980620000);
});

test('XMLTV times reject junk', () => {
  assert.equal(parseXmltvTime(null), null);
  assert.equal(parseXmltvTime('soon'), null);
  assert.equal(parseXmltvTime('abcd1106155700'), null);
});

test('names match across spellings', () => {
  assert.equal(normalizeName('FOX Weather'), normalizeName('Fox Weather HD'));
  assert.equal(normalizeName('CNN'), normalizeName('CNN (East)'));
  assert.equal(normalizeName('Перви'), 'перви');
  assert.equal(normalizeName('Canal Número 1'), 'canalnumero1');
  assert.equal(normalizeName(null), '');
});

test('blocks fill gaps and cover the whole range', () => {
  const b = buildBlocks([prog(10, 20), prog(30, 40)], 0, 50);
  assert.deepEqual(b.map((x) => [x.start, x.stop]), [[0, 10], [10, 20], [20, 30], [30, 40], [40, 50]]);
  assert.deepEqual(b.map((x) => x.programme !== null), [false, true, false, true, false]);
});

test('blocks trim overlaps and clip to the range', () => {
  const b = buildBlocks([prog(0, 15), prog(10, 30), prog(25, 70)], 5, 50);
  assert.deepEqual(b.map((x) => [x.start, x.stop]), [[5, 15], [15, 30], [30, 50]]);
});

test('an empty row is one empty block', () => {
  assert.deepEqual(buildBlocks([], 0, 100), [{ start: 0, stop: 100, programme: null }]);
});

test('an exact ID beats a name match', () => {
  const r = resolveXmlChannels(
    map({ a: ['Fox'], b: ['Something'] }), map({ b: ['Fox.us'] }), map({ fox: ['Fox.us'] }), new Set(),
  );
  assert.deepEqual(Array.from(r), [['b', ['Fox.us']]]);
});

test('affiliates cannot stack on one channel', () => {
  const r = resolveXmlChannels(map({ wabc: ['ABC'], kabc: ['ABC HD'] }), new Map(), map({ abc: ['ABC.us'] }), new Set());
  assert.deepEqual(Array.from(r), [['wabc', ['ABC.us']]]);
});

test('claimed channels stay with the earlier source', () => {
  const r = resolveXmlChannels(map({ x: ['ABC'] }), new Map(), map({ abc: ['ABC.us'] }), new Set(['ABC.us']));
  assert.equal(r.size, 0);
});

test('holding speeds up but single presses move one row', () => {
  assert.equal(rowStep(0), 1);
  assert.equal(rowStep(5), 1);
  assert.equal(rowStep(6), 3);
  assert.equal(rowStep(20), 6);
});

test('streams are ordered best first', () => {
  const low = stream('low', '480p');
  const high = stream('high', '1080p');
  const geo = stream('geo', '1080p', true);
  const off = stream('off', '720p', false, true);
  assert.deepEqual(orderStreams([geo, off, low, high], null).map((s) => s.url), ['high', 'low', 'off', 'geo']);
  assert.equal(orderStreams([low, high, geo], 'geo')[0].url, 'geo');
});

test('streams wanting their own headers are tried last unless they already worked', () => {
  const plain = stream('plain', '480p');
  const fussy = stream('fussy', '1080p', false, false, { referrer: 'https://example.com' });
  assert.deepEqual(playbackOrder([fussy, plain], null).map((s) => s.url), ['plain', 'fussy']);
  assert.deepEqual(playbackOrder([plain, fussy], 'fussy').map((s) => s.url), ['fussy', 'plain']);
});

test('languages prefer feeds that have streams', () => {
  const feeds = [
    { id: 'SD', isMain: true, languages: ['eng'] },
    { id: 'Latin', isMain: false, languages: ['spa'] },
  ];
  assert.deepEqual(taggedLanguages(feeds, new Set(['Latin'])), ['spa']);
  assert.deepEqual(taggedLanguages(feeds, new Set(['SD', 'Latin'])), ['eng', 'spa']);
  assert.deepEqual(taggedLanguages(feeds, new Set([null])), ['eng']);
  assert.deepEqual(taggedLanguages([], new Set([null])), []);
});

test('names overrule an English tag', () => {
  const eng = ['eng'];
  assert.deepEqual(correctLanguages(['Cine en espanol'], eng), ['spa']);
  assert.deepEqual(correctLanguages(['CBS en español'], eng), ['spa']);
  assert.deepEqual(correctLanguages(['Crimenes Imperfectos', 'Crímenes imperfectos'], eng), ['spa']);
  assert.deepEqual(correctLanguages(['Pluto TV Terror'], eng), ['spa']);
  assert.deepEqual(correctLanguages(['Filmes de Acao'], eng), ['por']);
});

test('names leave ordinary channels alone', () => {
  const eng = ['eng'];
  for (const name of ['BET Cinema', '70s Cinema', 'Cinemax Classics', 'ToonGoggles', 'CNN']) {
    assert.deepEqual(correctLanguages([name], eng), eng, name);
  }
  assert.deepEqual(correctLanguages(['Cine en espanol'], ['fra']), ['fra']);
  assert.deepEqual(correctLanguages(['Cine'], []), []);
});

test('the language filter only counts the main language', () => {
  assert.ok(matchesLanguage(['eng', 'spa'], 'eng'));
  assert.ok(!matchesLanguage(['eng', 'spa'], 'spa'));
  assert.ok(!matchesLanguage([], 'eng'));
  assert.ok(matchesLanguage([], LANGUAGE_ALL));
});

test('a probe tells working from broken', () => {
  assert.equal(probeVerdict(200, '/live/index.m3u8', '#EXTM3U\n#EXT'), true);
  assert.equal(probeVerdict(200, '/live/index.php', '﻿#EXTM3U'), true);
  assert.equal(probeVerdict(200, '/live/index.m3u8', '<html>Not here'), false);
  assert.equal(probeVerdict(404, '/live/index.m3u8', ''), false);
  assert.equal(probeVerdict(403, '/live/index.m3u8', ''), false);
  assert.equal(probeVerdict(200, '/video.MP4', ''), true);
  assert.equal(probeVerdict(429, '/live/index.m3u8', ''), null);
});

test('widescreen on a squeezed tube is letterboxed', () => {
  assert.deepEqual(crtVideoSize(1920, 1080, 4 / 3, 16 / 9, STRETCH.FIT), [1920, 810]);
  assert.deepEqual(crtVideoSize(1920, 1080, 4 / 3, 16 / 9, STRETCH.FILL), [2560, 1080]);
});

test('tall video is pillarboxed', () => {
  assert.deepEqual(crtVideoSize(1440, 1080, 4 / 3, 1, STRETCH.FIT), [1080, 1080]);
  assert.deepEqual(crtVideoSize(1440, 1080, 4 / 3, 1, STRETCH.FILL), [1440, 1440]);
});

test('stretch ignores shape', () => {
  assert.deepEqual(crtVideoSize(1920, 1080, 4 / 3, 2.35, STRETCH.STRETCH), [1920, 1080]);
});

test('versions compare numerically', () => {
  assert.ok(isNewerVersion('v1.2.0', '1.1.0'));
  assert.ok(isNewerVersion('1.10.0', '1.9.0'));
  assert.ok(isNewerVersion('v2', '1.9.9'));
  assert.ok(isNewerVersion('1.1.1', '1.1'));
  assert.ok(!isNewerVersion('v1.1.0', '1.1.0'));
  assert.ok(!isNewerVersion('1.1', '1.1.0'));
  assert.ok(!isNewerVersion('1.0.9', '1.1.0'));
});

test('cropping bars lets the picture fill the screen', () => {
  const shape = croppedAspect(16 / 9, 0, 12);
  assert.ok(Math.abs(shape - 2.34) < 0.01);
  const fitted = crtVideoSize(1920, 1080, 4 / 3, shape, STRETCH.STRETCH);
  assert.deepEqual(fitted, [1920, 1080]);
  assert.deepEqual(uncroppedSize(fitted[0], fitted[1], 0, 12), [1920, 1421]);
});

test('no crop changes nothing', () => {
  assert.equal(croppedAspect(16 / 9, 0, 0), 16 / 9);
  assert.deepEqual(uncroppedSize(1920, 1080, 0, 0), [1920, 1080]);
});

test('a saved crop is read safely', () => {
  assert.deepEqual(parseCrop('3,12'), [3, 12]);
  assert.deepEqual(parseCrop(null), [0, 0]);
  assert.deepEqual(parseCrop('junk'), [0, 0]);
  assert.deepEqual(parseCrop('3'), [0, 0]);
  assert.deepEqual(parseCrop('99,-4'), [MAX_CROP, 0]);
});

test("the guide channel lists what's on, in list order", () => {
  const ch = (id) => ({ id, name: id.toUpperCase() });
  const show = (title, start = 0) => ({ start, stop: start + 1, title });
  const rows = guideRows(
    [ch('b'), ch('a'), ch('c')],
    map({ a: 7, b: 3, c: 9 }),
    map({ a: show('Film'), b: show('News') }),
    map({ a: show('Later', 500) }),
  );
  assert.deepEqual(rows, [
    { number: 3, name: 'B', now: 'News', nextAt: null, next: null },
    { number: 7, name: 'A', now: 'Film', nextAt: 500, next: 'Later' },
  ]);
});

test('what starts next is the first programme after now, within the window', () => {
  const times = map({
    a: { rows: [[0, 100, 'On'], [100, 200, 'Next'], [200, 300, 'After']] },
    b: { rows: [[0, 100, 'On'], [5000, 6000, 'Too late']] },
  });
  const next = startingNext(times, 50, 1000);
  assert.deepEqual(next.get('a'), { start: 100, stop: 200, title: 'Next' });
  assert.equal(next.has('b'), false);
});

test("Pluto's break filler is recognised by name", () => {
  // Real segment paths, including the different ways Pluto spells it.
  assert.ok(isBreakFiller('https://x.plutotv.net/410_Pluto_TV_OandO/clip/58e5377d_ptv_7424_ad_bumper_animation_dots_normal_10_1/1080pDRM/hls/hls_2400-00000.ts'));
  assert.ok(isBreakFiller('https://x.plutotv.net/701_pluto_tv_oando/clip/58e5397b_ptv-7424_ad_bumper_animation_dots_normal_30_1/1080pDRM/seg_1.ts'));
  assert.ok(isBreakFiller('https://x.plutotv.net/443_pluto_tv_oando/clip/58e5371a_ptv-7424adbumperanimationdotsinverted30_30fps/1080pDRM/seg_1.ts'));
  assert.ok(!isBreakFiller('https://x.plutotv.net/316_pluto/clip/61163aa1_walkertexasranger_141/720pDRM/hls/hls_600-00033.ts'));
  assert.ok(!isBreakFiller('https://x.plutotv.net/v1/mp4/c(ts)/p(0_ad%2Fcreative%2F67eeff33_ad%2F720p%2Fvideo_600.mp4)/1.ts'));
  // A show that merely has the word in its title.
  assert.ok(!isBreakFiller('https://x.plutotv.net/1_pluto/clip/abc_Road_Bumper_Cars_S1E2/720p/hls/hls_600-00001.ts'));
});
