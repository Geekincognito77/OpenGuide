import { LANGUAGE_ALL, parseCrop, REGION_WORLD, STRETCH } from './logic.js';

const PREFIX = 'og.';

function read(key, fallback) {
  try {
    const v = localStorage.getItem(PREFIX + key);
    return v === null ? fallback : JSON.parse(v);
  } catch (_) {
    return fallback;
  }
}

function write(key, value) {
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify(value));
  } catch (_) {
    // Storage full or blocked: the setting lasts until the app closes.
  }
}

function field(obj, name, key, fallback) {
  let cached = read(key, fallback);
  Object.defineProperty(obj, name, {
    get: () => cached,
    set: (v) => {
      cached = v;
      write(key, v);
    },
  });
}

export class Prefs {
  constructor() {
    field(this, 'region', 'region', null);
    field(this, 'favorites', 'favorites', []);
    field(this, 'lastChannel', 'last_channel', null);
    field(this, 'epgFetchedAt', 'epg_at', 0);
    field(this, 'onlyWithListings', 'only_listings', false);
    field(this, 'language', 'language', null);
    field(this, 'hideDead', 'hide_dead', true);
    field(this, 'deadChannels', 'dead_channels', []);
    field(this, 'sourceCheckKey', 'source_check_key', null);
    field(this, 'sourceCheckAt', 'source_check_at', 0);
    field(this, 'dpadChangesChannel', 'dpad_surf', false);
    field(this, 'crtMode', 'crt_mode', false);
    field(this, 'crtList', 'crt_list', []);
    field(this, 'crtStretch', 'crt_stretch', STRETCH.FIT);
    field(this, 'guideChannel', 'guide_channel', true);
    field(this, 'channelStatic', 'channel_static', true);
  }

  get regionOrWorld() {
    return this.region || REGION_WORLD;
  }

  get languageOrAll() {
    return this.language || LANGUAGE_ALL;
  }

  /** A channel uses the default picture mode until it's given its own. */
  channelStretch(channel) {
    return read(`picture_${channel}`, this.crtStretch);
  }

  setChannelStretch(channel, v) {
    write(`picture_${channel}`, v);
  }

  channelCrop(channel) {
    return parseCrop(read(`crop_${channel}`, null));
  }

  setChannelCrop(channel, x, y) {
    write(`crop_${channel}`, `${x},${y}`);
  }

  subtitles(channel) {
    return read(`subtitles_${channel}`, false);
  }

  setSubtitles(channel, on) {
    write(`subtitles_${channel}`, on);
  }

  goodStream(channel) {
    return read(`good_${channel}`, null);
  }

  setGoodStream(channel, url) {
    write(`good_${channel}`, url);
  }

  etag(key) {
    return read(`etag_${key}`, null);
  }

  setEtag(key, v) {
    write(`etag_${key}`, v);
  }

  clearEtags() {
    try {
      const keys = [];
      for (let i = 0; i < localStorage.length; i++) keys.push(localStorage.key(i));
      keys.filter((k) => k.indexOf(`${PREFIX}etag_`) === 0).forEach((k) => localStorage.removeItem(k));
    } catch (_) {
      // Storage blocked: nothing was saved to clear.
    }
  }
}
