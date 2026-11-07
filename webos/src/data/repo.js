import { downloadCatalog, epgIndexFor } from './catalog.js';
import { Db } from './db.js';
import { epgSourcesFor, importSource } from './epg.js';
import {
  CATEGORY_ALL, CATEGORY_CRT, CATEGORY_FAVORITES, LANGUAGE_ALL, matchesLanguage, probeVerdict, REGION_WORLD,
} from './logic.js';
import { detectCrossOrigin, getBytes, getJson, isCrossOriginOpen, probe } from './net.js';
import { Prefs } from './prefs.js';

const CATALOG_MAX_AGE = 24 * 3600000;
const EPG_MAX_AGE = 6 * 3600000;
const SOURCE_CHECK_MAX_AGE = 12 * 3600000;
const PROBE_CONCURRENCY = 6;
const PROBE_BATCH = 60;
const CATALOG_FORMAT = 1;
const LISTING_CACHE = 96;

const ISO3 = {
  en: 'eng', es: 'spa', fr: 'fra', de: 'deu', it: 'ita', pt: 'por', ru: 'rus', ar: 'ara', zh: 'zho', ja: 'jpn',
  ko: 'kor', nl: 'nld', pl: 'pol', tr: 'tur', sv: 'swe', no: 'nor', nb: 'nob', da: 'dan', fi: 'fin', el: 'ell',
  he: 'heb', hi: 'hin', id: 'ind', th: 'tha', vi: 'vie', uk: 'ukr', ro: 'ron', hu: 'hun', cs: 'ces', sk: 'slk',
  bg: 'bul', hr: 'hrv', sr: 'srp',
};

/** App-wide data: the catalog, the current region's lineup, and listings. */
export class Repo {
  constructor() {
    this.prefs = new Prefs();
    this.db = null;
    this.catalog = null;
    this.byId = new Map();
    this.lineup = [];
    this.numbers = new Map();
    this.favorites = new Set(this.prefs.favorites);
    this.crtList = new Set(this.prefs.crtList);
    this.deadChannels = new Set(this.prefs.deadChannels);
    /** Channel to { source, rows }, with rows of [start, stop, title]. */
    this.times = new Map();
    /** Channel to what's on it right now. */
    this.airing = new Map();
    this.epgStatus = null;
    this.handlers = {};
    this.updatingListings = false;
    this.sourceCheckRun = 0;
  }

  on(event, fn) {
    (this.handlers[event] = this.handlers[event] || []).push(fn);
  }

  emit(event) {
    (this.handlers[event] || []).forEach((fn) => fn());
  }

  get region() {
    return this.prefs.regionOrWorld;
  }

  get language() {
    return this.prefs.languageOrAll;
  }

  /** Loads the cache, or downloads if there is none. Rejects if neither works. */
  async start(progress) {
    if (!this.db) this.db = await Db.open();
    progress('Loading channels…');
    // Not awaited first: nothing below needs the answer until the first feed or probe, far later.
    const crossOriginChecked = detectCrossOrigin();
    const cached = await this.db.getKv('catalog');
    const usable = cached && cached.format === CATALOG_FORMAT ? cached.catalog : null;
    if (usable) {
      await this.publish(usable);
      if (Date.now() - usable.fetchedAt > CATALOG_MAX_AGE) {
        this.download(() => {}).catch((e) => console.warn('Catalog refresh failed; keeping cached copy', e));
      }
    } else {
      await this.download(progress);
    }
    await crossOriginChecked;
    this.refreshListings(false);
    this.checkSources(false);
  }

  async download(progress) {
    const { catalog, epgRows } = await downloadCatalog(getJson, progress, Date.now());
    progress('Saving…');
    await this.db.setKvs({ catalog: { format: CATALOG_FORMAT, catalog }, epgRows });
    await this.publish(catalog);
  }

  async publish(c) {
    if (this.prefs.region === null) this.prefs.region = this.defaultRegion(c);
    if (this.prefs.language === null) this.prefs.language = this.defaultLanguage(c);
    this.catalog = c;
    this.byId = new Map(c.channels.map((ch) => [ch.id, ch]));
    this.times = await this.db.loadTimes();
    this.computeAiring();
    this.rebuildLineup();
  }

  defaultRegion(c) {
    const parts = (navigator.language || '').split('-');
    let code = (parts[1] || '').toUpperCase();
    if (code === 'GB') code = 'UK';
    return c.channels.some((ch) => ch.country === code) ? code : 'US';
  }

  defaultLanguage(c) {
    const own = ISO3[(navigator.language || '').split('-')[0].toLowerCase()];
    return c.languages.some((l) => l.code === own) ? own : LANGUAGE_ALL;
  }

  inRegion(region) {
    const all = this.catalog.channels;
    return region === REGION_WORLD ? all : all.filter((ch) => ch.country === region);
  }

  languageCounts() {
    const out = new Map();
    if (!this.catalog) return out;
    for (const ch of this.inRegion(this.region)) {
      if (ch.languages.length) out.set(ch.languages[0], (out.get(ch.languages[0]) || 0) + 1);
    }
    return out;
  }

  setLanguage(code) {
    if (code === this.prefs.language) return;
    this.prefs.language = code;
    this.rebuildLineup();
    this.checkSources(false);
  }

  /** Every channel in the region: those with listings first, each group A to Z. */
  rebuildLineup() {
    if (!this.catalog) return;
    const lang = this.language;
    const list = this.inRegion(this.region).filter((ch) => matchesLanguage(ch.languages, lang));
    // The catalog is already A to Z, so splitting it in two keeps each group in order.
    this.lineup = list.filter((ch) => this.times.has(ch.id)).concat(list.filter((ch) => !this.times.has(ch.id)));
    this.numbers = new Map(this.lineup.map((ch, i) => [ch.id, i + 1]));
    this.emit('lineup');
  }

  filtered(category, query) {
    // Lists someone built by hand keep what they chose; they can remove it if they want it gone.
    const picked = category === CATEGORY_FAVORITES || category === CATEGORY_CRT;
    const only = this.prefs.onlyWithListings && !picked;
    const hideDead = this.prefs.hideDead && !picked;
    const q = (query || '').trim().toLowerCase();
    return this.lineup.filter((ch) => (
      (category === CATEGORY_ALL || (category === CATEGORY_FAVORITES && this.favorites.has(ch.id)) ||
        (category === CATEGORY_CRT && this.crtList.has(ch.id)) || ch.categories.indexOf(category) >= 0) &&
      (!q || ch.nameLower.indexOf(q) >= 0) &&
      (!only || this.airing.has(ch.id)) &&
      (!hideDead || !this.deadChannels.has(ch.id))
    ));
  }

  computeAiring() {
    const now = Date.now();
    const next = new Map();
    for (const [channel, rec] of this.times) {
      for (const r of rec.rows) {
        if (r[0] > now) break;
        if (r[1] > now) {
          next.set(channel, { start: r[0], stop: r[1], title: r[2] });
          break;
        }
      }
    }
    let changed = next.size !== this.airing.size;
    if (!changed) for (const id of next.keys()) if (!this.airing.has(id)) changed = true;
    this.airing = next;
    return changed;
  }

  /** Re-reads what's on now, since channels start and stop having listings as time passes. */
  refreshAiring() {
    if (this.computeAiring() && this.prefs.onlyWithListings) this.emit('visibility');
  }

  saveDead() {
    this.prefs.deadChannels = Array.from(this.deadChannels);
    if (this.prefs.hideDead) this.emit('visibility');
  }

  setDead(id, dead) {
    if (this.deadChannels.has(id) === dead) return;
    if (dead) this.deadChannels.add(id);
    else this.deadChannels.delete(id);
    this.saveDead();
  }

  /**
   * Tries each channel's streams in the background, because a dead channel is otherwise only found by
   * tuning to it. Results that could be down to this TV being offline are thrown away.
   */
  async checkSources(force) {
    // If CORS applies to this app, a blocked request looks just like a dead server, so a check would
    // prove nothing. Channels are still marked dead when tuning to them fails.
    if (!this.catalog || !isCrossOriginOpen()) return;
    const key = `${this.region}|${this.language}`;
    const age = Date.now() - this.prefs.sourceCheckAt;
    if (!force && key === this.prefs.sourceCheckKey && age < SOURCE_CHECK_MAX_AGE) return;
    const run = ++this.sourceCheckRun;
    const channels = this.lineup;
    for (let at = 0; at < channels.length; at += PROBE_BATCH) {
      if (run !== this.sourceCheckRun) return;
      const batch = channels.slice(at, at + PROBE_BATCH);
      const results = new Map();
      let cursor = 0;
      const worker = async () => {
        while (cursor < batch.length) {
          const ch = batch[cursor++];
          const works = await this.channelWorks(ch);
          if (works !== null) results.set(ch.id, works);
        }
      };
      const workers = [];
      for (let i = 0; i < PROBE_CONCURRENCY; i++) workers.push(worker());
      await Promise.all(workers);
      if (!(await this.online())) return;
      let changed = false;
      for (const [id, works] of results) {
        if (this.deadChannels.has(id) === works) {
          changed = true;
          if (works) this.deadChannels.delete(id);
          else this.deadChannels.add(id);
        }
      }
      if (changed) this.saveDead();
    }
    if (run !== this.sourceCheckRun) return;
    this.prefs.sourceCheckKey = key;
    this.prefs.sourceCheckAt = Date.now();
  }

  /** True once a stream works, false if none do, null if that can't be told right now. */
  async channelWorks(ch) {
    let unknown = false;
    for (const s of ch.streams) {
      const r = await probe(s.url);
      const verdict = probeVerdict(r.status, r.path, r.head);
      if (verdict === true) return true;
      if (verdict === null) unknown = true;
    }
    return unknown ? null : false;
  }

  online() {
    return getJson('https://iptv-org.github.io/api/categories.json').then(() => true, () => false);
  }

  toggleFavorite(id) {
    const added = !this.favorites.has(id);
    if (added) this.favorites.add(id);
    else this.favorites.delete(id);
    this.prefs.favorites = Array.from(this.favorites);
    return added;
  }

  toggleCrt(id) {
    const added = !this.crtList.has(id);
    if (added) this.crtList.add(id);
    else this.crtList.delete(id);
    this.prefs.crtList = Array.from(this.crtList);
    return added;
  }

  async setRegion(code) {
    if (code === this.prefs.region) return;
    this.prefs.region = code;
    this.prefs.epgFetchedAt = 0;
    // Keeps the language unless this region has nothing in it.
    if (this.language !== LANGUAGE_ALL && !this.languageCounts().get(this.language)) this.prefs.language = LANGUAGE_ALL;
    this.prefs.clearEtags();
    this.times = new Map();
    this.airing = new Map();
    this.rebuildLineup();
    this.emit('epg');
    // Listings were matched for the old region's channels.
    await this.db.clearListings();
    this.refreshListings(true);
    this.checkSources(false);
  }

  async refreshAll() {
    this.setStatus('Updating channels…');
    try {
      await this.download((msg) => this.setStatus(msg));
    } catch (e) {
      console.warn('Catalog refresh failed', e);
    }
    this.setStatus(null);
    this.refreshListings(true);
    this.checkSources(true);
  }

  setStatus(text) {
    this.epgStatus = text;
    this.emit('status');
  }

  refreshListings(force) {
    if (!this.catalog || this.updatingListings) return;
    if (!force && Date.now() - this.prefs.epgFetchedAt < EPG_MAX_AGE) return;
    this.updatingListings = true;
    this.updateListings()
      .catch((e) => console.warn('Listings update failed', e))
      .then(() => {
        this.updatingListings = false;
        this.setStatus(null);
      });
  }

  channelsFromOtherSources(source) {
    const out = new Set();
    for (const [channel, rec] of this.times) if (rec.source !== source) out.add(channel);
    return out;
  }

  async replaceSource(source, byChannel) {
    await this.db.replaceSource(source, byChannel);
    for (const [channel, rec] of Array.from(this.times)) if (rec.source === source) this.times.delete(channel);
    for (const [channel, rows] of byChannel) {
      this.times.set(channel, { source, rows: rows.map((r) => [r.start, r.stop, r.title]) });
    }
  }

  async updateListings() {
    const now = Date.now();
    const region = this.region;
    const rows = await this.db.getKv('epgRows');
    if (!rows) return;
    // The whole region, not the lineup, so switching language doesn't need new listings.
    const index = epgIndexFor(rows, new Set(this.inRegion(region).map((ch) => ch.id)));
    const sources = epgSourcesFor(region);
    let anySucceeded = false;
    for (let i = 0; i < sources.length; i++) {
      if (region !== this.region) return; // the region changed, and it started its own update
      const s = sources[i];
      this.setStatus(`Updating listings ${i + 1}/${sources.length}: ${s.name}`);
      try {
        const changed = await importSource(s, index, now, { getBytes }, this, this.prefs);
        anySucceeded = true;
        if (changed && region === this.region) {
          this.computeAiring();
          this.rebuildLineup();
          this.emit('epg');
          if (this.prefs.onlyWithListings) this.emit('visibility');
        }
      } catch (e) {
        console.warn(`${s.key} failed`, e); // other feeds can still fill the guide
      }
    }
    await this.db.prune(now - 3600000);
    this.times = await this.db.loadTimes();
    if (anySucceeded) this.prefs.epgFetchedAt = now;
    this.computeAiring();
    this.emit('epg');
  }

  programmeCount() {
    let n = 0;
    for (const rec of this.times.values()) n += rec.rows.length;
    return n;
  }
}

/** Per-channel listings in memory for the rows near the screen, read from IndexedDB as needed. */
export class Listings {
  constructor(repo, onLoaded) {
    this.repo = repo;
    this.onLoaded = onLoaded;
    this.cache = new Map();
    this.pending = new Set();
    this.generation = 0;
  }

  /** @returns the programmes if loaded, otherwise null, and onLoaded runs once they are. */
  get(channel) {
    const hit = this.cache.get(channel);
    if (hit) {
      this.cache.delete(channel); // to the end, so the oldest key is the least recently used
      this.cache.set(channel, hit);
      return hit;
    }
    if (!this.pending.has(channel) && this.repo.db) {
      this.pending.add(channel);
      const generation = this.generation;
      const now = Date.now();
      this.repo.db.forChannel(channel, now - 3600000, now + 72 * 3600000).then((rows) => {
        this.pending.delete(channel);
        if (generation !== this.generation) return;
        this.cache.set(channel, rows);
        if (this.cache.size > LISTING_CACHE) this.cache.delete(this.cache.keys().next().value);
        this.onLoaded();
      }, () => this.pending.delete(channel));
    }
    return null;
  }

  clear() {
    this.cache.clear();
    this.pending.clear();
    this.generation++;
  }
}
