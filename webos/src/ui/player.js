import { GUIDE_CHANNEL_ID, isBreakFiller, playbackOrder } from '../data/logic.js';
import { isCrossOriginOpen } from '../data/net.js';

const CONNECT_TIMEOUT = 15000;
const STALL_TIMEOUT = 25000;
const REBUFFER_TIMEOUT = 10000;
const FROZEN_TIMEOUT = 10000;
const FREEZE_POLL = 2000;
const HEALTHY_AFTER = 60000;
const MAX_RESTARTS = 3;
const RETRY_DELAY = 15000;
const SUBTITLE_WAIT = 12000;

export const TUNE = { IDLE: 'idle', CONNECTING: 'connecting', PLAYING: 'playing', FAILED: 'failed' };

export const isWebOS = () => !!window.PalmSystem || /Web0S|webOS/.test(navigator.userAgent);

function streamType(url) {
  let path = '';
  try {
    path = new URL(url).pathname.toLowerCase();
  } catch (_) {
    path = url.toLowerCase();
  }
  if (/\.mpd$/.test(path)) return 'dash';
  if (/\.(ts|mp4|mkv)$/.test(path)) return 'file';
  // iptv-org also lists HLS at URLs like /index.php that say nothing about what they are.
  return 'hls';
}

const SOURCE_TYPE = { hls: 'application/x-mpegURL', dash: 'application/dash+xml' };

/** Pluto's streams, whose segment names say when an ad break is showing only its logo. iptv-org links them through jmp2.uk. */
const isPluto = (url) => /\/\/jmp2\.uk\/plu-|[/.]pluto\.tv\//i.test(url);

let hlsLoading = null;

/** hls.js stays out of the way until something needs it. */
function loadHls() {
  if (!hlsLoading) {
    // hls.js calls this, and webOS 4 lacks it.
    if (!Object.entries) Object.entries = (o) => Object.keys(o).map((k) => [k, o[k]]);
    hlsLoading = new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = 'hls.min.js';
      s.onload = () => resolve(window.Hls);
      s.onerror = () => reject(new Error('hls.js failed to load'));
      document.head.appendChild(s);
    });
  }
  return hlsLoading;
}

/**
 * Wraps the video element with what public IPTV needs: many streams are dead or stall, so each channel's
 * streams are tried in turn until one plays.
 *
 * A stream that has been playing and then stalls is restarted rather than abandoned. Stitched streams
 * keep only a short live window and hand out per-session URLs, so a hiccup can leave the player stuck on
 * segments that no longer exist, while reopening starts a fresh session.
 *
 * On webOS the TV's own player handles video: it's hardware accelerated and needs no CORS. hls.js runs
 * only where that player can't help: subtitles, desktop browsers, and Pluto where CORS allows, since its
 * ad breaks can only be spotted by segment name, which the TV's player doesn't reveal. hls.js needs CORS,
 * so a stream that fails under it is given to the TV's player before moving on.
 */
export class TvPlayer {
  constructor(video, repo, onState, onSubtitlesMissing, onBreakChanged) {
    this.video = video;
    this.repo = repo;
    this.onState = onState;
    this.onSubtitlesMissing = onSubtitlesMissing;
    this.onBreakChanged = onBreakChanged;
    /** The picture is Pluto's logo clip, which can run for minutes and looks like a stuck stream. */
    this.onBreak = false;
    this.channel = null;
    this.state = { kind: TUNE.IDLE };
    this.queue = [];
    this.attempt = -1;
    this.everPlayed = false;
    this.streamPlayed = false;
    this.restarts = 0;
    this.generation = 0;
    this.hls = null;
    this.engine = 'native';
    this.subtitlesOn = false;
    this.bind();
    this.freezeTimer = setInterval(() => this.checkFreeze(), FREEZE_POLL);
  }

  bind() {
    const v = this.video;
    v.addEventListener('playing', () => this.onPlaying());
    v.addEventListener('waiting', () => {
      if (this.channel) this.arm(this.streamPlayed ? REBUFFER_TIMEOUT : STALL_TIMEOUT);
    });
    v.addEventListener('ended', () => {
      if (this.channel) this.next(); // a live stream ending means the source dropped
    });
    v.addEventListener('error', () => this.onError());
    v.textTracks.addEventListener('addtrack', () => this.applySubtitles());
  }

  setState(next) {
    this.state = next;
    this.onState(next);
  }

  setBreak(on) {
    if (this.onBreak === on) return;
    this.onBreak = on;
    this.onBreakChanged();
  }

  onPlaying() {
    const ch = this.channel;
    if (!ch) return;
    clearTimeout(this.watchdog);
    this.everPlayed = true;
    this.streamPlayed = true;
    const stream = this.queue[this.attempt];
    if (!stream) return;
    this.repo.prefs.setGoodStream(ch.id, stream.url);
    this.repo.setDead(ch.id, false);
    this.setState({ kind: TUNE.PLAYING, channel: ch, stream });
    this.applySubtitles();
  }

  onError() {
    if (!this.channel) return;
    if (this.engine === 'hls' && !this.streamPlayed && this.canPlayHlsNatively()) {
      this.play(this.queue[this.attempt], 'native');
      this.arm(CONNECT_TIMEOUT);
      return;
    }
    this.restartOrNext();
  }

  /** Catches a player that claims to be playing while its picture has stopped. */
  checkFreeze() {
    const v = this.video;
    const now = Date.now();
    if (!this.channel || !this.streamPlayed || v.paused) {
      this.lastProgressAt = now;
      return;
    }
    const position = v.currentTime;
    if (position !== this.lastPosition) {
      this.lastPosition = position;
      this.positionMoved = true;
      // Only a long healthy run clears the count, or a flapping stream would never fail over.
      if (now - this.startedAt > HEALTHY_AFTER) this.restarts = 0;
      this.lastProgressAt = now;
    } else if (this.positionMoved && now - this.lastProgressAt > FROZEN_TIMEOUT) {
      // Never before it has moved at all, in case some TV doesn't report position for live video.
      this.lastProgressAt = now;
      this.restartOrNext();
    }
  }

  restartOrNext() {
    const ch = this.channel;
    if (!ch) return;
    const s = this.queue[this.attempt];
    if (s && this.streamPlayed && this.restarts < MAX_RESTARTS) {
      this.restarts++;
      this.setState({ kind: TUNE.CONNECTING, channel: ch, attempt: this.attempt + 1, total: this.queue.length });
      this.play(s);
      this.arm(CONNECT_TIMEOUT);
    } else {
      this.next();
    }
  }

  arm(ms) {
    clearTimeout(this.watchdog);
    this.watchdog = setTimeout(() => this.restartOrNext(), ms);
  }

  /** @param stream a specific stream to start from, when the viewer chose one. */
  tune(ch, stream) {
    clearTimeout(this.retryTimer);
    this.channel = ch;
    if (ch.id === GUIDE_CHANNEL_ID) {
      this.playNothing(ch);
      return;
    }
    const ordered = playbackOrder(ch.streams, this.repo.prefs.goodStream(ch.id));
    this.queue = stream ? [stream].concat(ordered.filter((s) => s !== stream)) : ordered;
    this.attempt = -1;
    this.everPlayed = false;
    this.repo.prefs.lastChannel = ch.id;
    this.next();
  }

  /** The guide channel has no stream, so it's "on" with the player idle. Not a failure, so never marked dead. */
  playNothing(ch) {
    clearTimeout(this.watchdog);
    this.queue = [];
    this.attempt = -1;
    this.repo.prefs.lastChannel = ch.id;
    this.reset();
    this.setState({ kind: TUNE.IDLE });
  }

  next() {
    const ch = this.channel;
    if (!ch) return;
    this.attempt++;
    this.streamPlayed = false;
    this.restarts = 0;
    const s = this.queue[this.attempt];
    if (!s) {
      clearTimeout(this.watchdog);
      this.reset();
      if (this.everPlayed) {
        // A channel that was playing is probably fine and the connection isn't, so keep trying.
        this.retryTimer = setTimeout(() => {
          this.attempt = -1;
          this.next();
        }, RETRY_DELAY);
      } else {
        this.repo.setDead(ch.id, true);
      }
      this.setState({ kind: TUNE.FAILED, channel: ch });
      return;
    }
    this.setState({ kind: TUNE.CONNECTING, channel: ch, attempt: this.attempt + 1, total: this.queue.length });
    this.play(s);
    this.arm(CONNECT_TIMEOUT);
  }

  canPlayHlsNatively() {
    return isWebOS() || this.video.canPlayType(SOURCE_TYPE.hls) !== '';
  }

  chooseEngine(s) {
    if (streamType(s.url) !== 'hls') return 'native';
    const mse = 'MediaSource' in window;
    if (this.subtitlesOn && mse) return 'hls';
    // Pluto only allows its own site cross-origin, so this is only worth trying where CORS doesn't apply.
    if (isPluto(s.url) && mse && isCrossOriginOpen()) return 'hls';
    return this.canPlayHlsNatively() || !mse ? 'native' : 'hls';
  }

  /** Empties the player, which webOS needs in order to give up its decoder before the next stream. */
  reset() {
    this.generation++;
    this.setBreak(false);
    if (this.hls) {
      this.hls.destroy();
      this.hls = null;
    }
    const v = this.video;
    v.pause();
    while (v.firstChild) v.removeChild(v.firstChild);
    v.removeAttribute('src');
    v.load();
  }

  play(s, engine) {
    this.reset();
    const generation = this.generation;
    const now = Date.now();
    this.startedAt = now;
    this.lastProgressAt = now;
    this.lastPosition = -1;
    this.positionMoved = false;
    this.subtitlesOn = !!(this.channel && this.repo.prefs.subtitles(this.channel.id));
    this.engine = engine || this.chooseEngine(s);
    if (this.engine === 'hls') {
      // hls.js wants a few things newer than webOS 4's engine, so failing here has to be survivable.
      loadHls().then((Hls) => {
        if (generation !== this.generation) return;
        if (!Hls.isSupported()) throw new Error('hls.js is not supported here');
        this.playWithHls(Hls, s, generation);
      }).catch((e) => {
        console.warn('hls.js failed', e);
        if (generation === this.generation) this.onError();
      });
    } else {
      this.playNatively(s, generation);
    }
  }

  playNatively(s, generation) {
    const v = this.video;
    const source = document.createElement('source');
    source.src = s.url;
    const type = SOURCE_TYPE[streamType(s.url)];
    if (type) source.type = type;
    // A source element reports its own failures: the video element's error event doesn't fire for them.
    source.addEventListener('error', () => {
      if (generation === this.generation) this.onError();
    });
    v.appendChild(source);
    v.load();
    this.start();
  }

  playWithHls(Hls, s, generation) {
    const hls = new Hls({
      liveDurationInfinity: true,
      maxBufferLength: 30,
      manifestLoadingMaxRetry: 1,
      levelLoadingMaxRetry: 2,
    });
    this.hls = hls;
    let recovered = false;
    hls.on(Hls.Events.ERROR, (_, data) => {
      if (!data.fatal || generation !== this.generation) return;
      if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !recovered) {
        recovered = true;
        hls.recoverMediaError();
      } else {
        this.onError();
      }
    });
    hls.on(Hls.Events.SUBTITLE_TRACKS_UPDATED, () => this.applySubtitles());
    // Fires as each segment starts to play, so it follows the picture rather than the download.
    hls.on(Hls.Events.FRAG_CHANGED, (_, data) => {
      if (generation === this.generation) this.setBreak(isBreakFiller(data.frag && data.frag.url));
    });
    hls.on(Hls.Events.MANIFEST_PARSED, () => this.start());
    hls.loadSource(s.url);
    hls.attachMedia(this.video);
  }

  start() {
    const played = this.video.play();
    if (played && played.catch) {
      played.catch((e) => {
        // Only desktop browsers refuse this, and only with sound on before any key press.
        if (e.name === 'NotAllowedError') {
          this.video.muted = true;
          this.video.play().catch(() => {});
        }
      });
    }
  }

  /** Whether the current stream offers any subtitles. Only known once it has started. */
  get hasSubtitles() {
    if (this.hls && this.hls.subtitleTracks.length) return true;
    return this.video.textTracks.length > 0;
  }

  /** Saves the choice for this channel and applies it. */
  setSubtitles(on) {
    const ch = this.channel;
    if (!ch) return;
    this.repo.prefs.setSubtitles(ch.id, on);
    this.subtitlesOn = on;
    clearTimeout(this.subtitleTimer);
    this.applySubtitles();
    if (!on) return;
    const stream = this.queue[this.attempt];
    // The TV's player may not expose subtitles, so give hls.js a turn at them, on streams it can play.
    if (!this.hasSubtitles && this.engine === 'native' && stream && streamType(stream.url) === 'hls' && 'MediaSource' in window) {
      this.setState({ kind: TUNE.CONNECTING, channel: ch, attempt: this.attempt + 1, total: this.queue.length });
      this.play(stream, 'hls');
      this.arm(CONNECT_TIMEOUT);
    }
    this.subtitleTimer = setTimeout(() => {
      if (this.channel === ch && !this.hasSubtitles) this.onSubtitlesMissing();
    }, SUBTITLE_WAIT);
  }

  /** Streams label subtitles vaguely or not at all: own language first, then anything named, then whatever there is. */
  pickSubtitle(tracks) {
    const own = (navigator.language || '').split('-')[0].toLowerCase();
    const lang = (t) => (t.language || t.lang || '').toLowerCase();
    const label = (t) => t.label || t.name;
    let i = tracks.findIndex((t) => lang(t).indexOf(own) === 0);
    if (i < 0) i = tracks.findIndex(label);
    return i < 0 ? 0 : i;
  }

  applySubtitles() {
    const on = this.subtitlesOn;
    const hls = this.hls;
    const hlsTracks = hls ? hls.subtitleTracks : [];
    if (hls) {
      hls.subtitleDisplay = on;
      hls.subtitleTrack = on && hlsTracks.length ? this.pickSubtitle(hlsTracks) : -1;
      if (hlsTracks.length) return;
    }
    const tracks = Array.from(this.video.textTracks);
    const chosen = on && tracks.length ? this.pickSubtitle(tracks) : -1;
    tracks.forEach((t, i) => { t.mode = i === chosen ? 'showing' : 'disabled'; });
  }

  togglePause() {
    const v = this.video;
    if (v.paused) {
      this.start();
    } else {
      clearTimeout(this.watchdog);
      v.pause();
    }
  }

  stop() {
    clearTimeout(this.watchdog);
    clearTimeout(this.retryTimer);
    clearTimeout(this.subtitleTimer);
    this.channel = null;
    this.reset();
    this.setState({ kind: TUNE.IDLE });
  }
}
