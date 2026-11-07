import { correctLanguages, normalizeName, taggedLanguages } from './logic.js';

const PLUTO_ID = /jmp2\.uk\/plu-([0-9a-f]+)/;
// Only sites whose IDs match the feeds we download. Others use IDs that could collide with unrelated ones.
const DIRECT_ID_SITES = new Set(['i.mjh.nz', 'pluto.tv', 'plex.tv']);

function addTo(map, key, value) {
  let list = map.get(key);
  if (!list) map.set(key, (list = []));
  list.push(value);
}

function byText(key) {
  return (a, b) => (key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : 0);
}

/**
 * Builds the catalog from the iptv-org API one file at a time. Each file is reduced to what's needed
 * before the next is fetched, because together they run to 50 MB, a lot to hold on a TV.
 */
export class CatalogBuilder {
  constructor() {
    this.streams = new Map();
    this.direct = new Map();
    this.usedFeeds = new Map();
    this.titles = new Map();
    this.blocked = new Set();
    this.raw = new Map();
    this.feeds = new Map();
    this.languageNames = new Map();
    this.logos = new Map();
    this.aliases = new Map();
    this.categories = [];
    this.countries = [];
  }

  addStreams(rows) {
    for (const r of rows) {
      if (!r.channel || !r.url) continue;
      const labels = r.labels || [];
      addTo(this.streams, r.channel, {
        url: r.url,
        quality: r.quality || null,
        geoBlocked: labels.indexOf('Geo-blocked') >= 0,
        notAlwaysOn: labels.indexOf('Not 24/7') >= 0,
        userAgent: r.user_agent || null,
        referrer: r.referrer || null,
      });
      if (!this.usedFeeds.has(r.channel)) this.usedFeeds.set(r.channel, new Set());
      this.usedFeeds.get(r.channel).add(r.feed || null);
      if (r.title) addTo(this.titles, r.channel, r.title);
      // Pluto stream URLs embed the same ID the Pluto guide feed uses.
      const pluto = PLUTO_ID.exec(r.url);
      if (pluto) addTo(this.direct, r.channel, pluto[1]);
    }
  }

  addBlocklist(rows) {
    for (const r of rows) if (r.channel) this.blocked.add(r.channel);
  }

  addChannels(rows) {
    for (const r of rows) {
      if (!r.id || !r.name || !r.country || r.is_nsfw) continue;
      if (this.blocked.has(r.id) || !this.streams.has(r.id)) continue;
      this.raw.set(r.id, { name: r.name, alt: r.alt_names || [], country: r.country, categories: r.categories || [] });
    }
  }

  addFeeds(rows) {
    for (const r of rows) {
      if (!r.channel || !r.id || !this.raw.has(r.channel)) continue;
      addTo(this.feeds, r.channel, { id: r.id, isMain: !!r.is_main, languages: r.languages || [] });
    }
  }

  addLanguages(rows) {
    for (const r of rows) if (r.code && r.name) this.languageNames.set(r.code, r.name);
  }

  addLogos(rows) {
    for (const r of rows) {
      if (!r.channel || !r.url || !this.raw.has(r.channel)) continue;
      const rank = r.feed ? 1 : 0;
      const cur = this.logos.get(r.channel);
      if (!cur || rank < cur.rank) this.logos.set(r.channel, { rank, url: r.url });
    }
  }

  addGuides(rows) {
    for (const r of rows) {
      if (!r.channel || !this.raw.has(r.channel)) continue;
      if (r.site_name) addTo(this.aliases, r.channel, r.site_name);
      if (r.site_id && DIRECT_ID_SITES.has(r.site)) {
        addTo(this.direct, r.channel, r.site_id.substring(r.site_id.lastIndexOf('#') + 1));
      }
    }
  }

  addCategories(rows) {
    for (const r of rows) if (r.id && r.name) this.categories.push({ id: r.id, name: r.name });
  }

  addCountries(rows) {
    for (const r of rows) if (r.code && r.name) this.countries.push({ code: r.code, name: r.name, flag: r.flag || '' });
  }

  /** @returns the catalog, and the rows for matching listings to channels, which are kept apart from it. */
  finish(now) {
    const channels = [];
    const epgRows = [];
    for (const [id, c] of this.raw) {
      const tagged = taggedLanguages(this.feeds.get(id) || [], this.usedFeeds.get(id) || new Set());
      const logo = this.logos.get(id);
      channels.push({
        id,
        name: c.name,
        nameLower: c.name.toLowerCase(),
        country: c.country,
        categories: c.categories,
        logo: logo ? logo.url : null,
        streams: this.streams.get(id),
        languages: correctLanguages([c.name].concat(this.titles.get(id) || []), tagged),
      });
      const names = new Set();
      for (const n of (this.aliases.get(id) || []).concat(c.alt, c.name)) {
        const key = normalizeName(n);
        if (key.length >= 2) names.add(key);
      }
      epgRows.push([id, Array.from(names), Array.from(new Set(this.direct.get(id) || []))]);
    }
    channels.sort(byText((c) => c.nameLower + '\u0000' + c.id));
    if (!channels.length) throw new Error('iptv-org returned no playable channels');

    const usedLanguages = new Set();
    for (const c of channels) for (const l of c.languages) usedLanguages.add(l);
    const languages = [];
    for (const code of usedLanguages) {
      const name = this.languageNames.get(code);
      if (name) languages.push({ code, name });
    }
    return {
      catalog: {
        channels,
        countries: this.countries.sort(byText((c) => c.name)),
        categories: this.categories.sort(byText((c) => c.name)),
        languages: languages.sort(byText((l) => l.name)),
        fetchedAt: now,
      },
      epgRows,
    };
  }
}

/** Turns the saved rows into lookup tables for just the wanted channels. */
export function epgIndexFor(rows, wanted) {
  const aliases = new Map();
  const direct = new Map();
  for (const [id, names, ids] of rows) {
    if (!wanted.has(id)) continue;
    for (const n of names) addTo(aliases, n, id);
    for (const d of ids) addTo(direct, d, id);
  }
  return { aliases, direct };
}

const FILES = [
  ['streams', 'stream list', 'addStreams'],
  ['blocklist', 'blocklist', 'addBlocklist'],
  ['channels', 'channels', 'addChannels'],
  ['feeds', 'languages', 'addFeeds'],
  ['languages', 'languages', 'addLanguages'],
  ['logos', 'logos', 'addLogos'],
  ['guides', 'guide mappings', 'addGuides'],
  ['categories', 'categories', 'addCategories'],
  ['countries', 'countries', 'addCountries'],
];

export async function downloadCatalog(getJson, progress, now) {
  const builder = new CatalogBuilder();
  for (const [file, label, add] of FILES) {
    progress(`Fetching ${label}…`);
    builder[add](await getJson(`https://iptv-org.github.io/api/${file}.json`));
  }
  return builder.finish(now);
}
