import {
  CATEGORY_ALL, CATEGORY_CRT, CATEGORY_FAVORITES, croppedAspect, crtVideoSize, GUIDE_CHANNEL, GUIDE_CHANNEL_ID, guideRows,
  LANGUAGE_ALL, MAX_CROP, REGION_WORLD, startingNext, STRETCH, STRETCH_LABEL, STRETCH_ORDER, streamLabel, uncroppedSize,
} from './data/logic.js';
import { getJson } from './data/net.js';
import { Listings, Repo } from './data/repo.js';
import { latestIfNewer } from './data/updater.js';
import { BrowserScreen, button } from './ui/browser.js';
import { GuideChannelView, StaticView } from './ui/crt.js';
import { add, clockDay, durationLeft, el, progressBar, setLogo, setProgress, show, stampOf, timeOf } from './ui/dom.js';
import { Focus, KEY } from './ui/focus.js';
import { GuideView } from './ui/guide.js';
import { Picker } from './ui/picker.js';
import { TUNE, TvPlayer } from './ui/player.js';
import { SettingsScreen } from './ui/settings.js';

/* global __APP_VERSION__, __UPDATE_API__ */

const FRAME_W = 1920;
const FRAME_H = 1080;
const GUIDE_NEXT_WITHIN = 12 * 3600000;
const SCREEN = { LOADING: 'loading', GUIDE: 'guide', BROWSER: 'browser', SETTINGS: 'settings', FULLSCREEN: 'fullscreen' };

// What's left for the guide and the channel grid once the rest of each screen's layout is taken out.
const guideHeight = (crt) => (crt ? 1080 - 20 - 76 - (crtWindow()[1] + 16) : 1080 - 48 - 76 - 296);
const gridHeight = (crt) => (crt ? 1080 - 20 - 76 - 44 : 1080 - 48 - 76 - 44);

/** The guide's video window in CRT mode, in the shape of a tube. */
function crtWindow() {
  const w = Math.floor(FRAME_W * 0.3);
  return [w, Math.floor(w * 3 / 4)];
}

/** Every screen lives in one page, so one video element can move between full screen and the small window. */
class App {
  constructor() {
    this.repo = new Repo();
    this.prefs = this.repo.prefs;
    this.root = document.getElementById('app');
    this.focus = new Focus(this.root);
    this.focus.onLost = () => this.defaultFocus();
    this.listings = new Listings(this.repo, () => this.onListingsLoaded());
    this.screen = SCREEN.LOADING;
    this.guideCategory = CATEGORY_ALL;
    this.surfList = [];
    this.previousChannel = null;
    this.lastExitPress = 0;
    this.uiReady = false;
    this.held = {};
    this.cropping = false;
    this.buildUi();
    this.applyLook();
    this.bindKeys();
    this.fitToWindow();
    window.addEventListener('resize', () => this.fitToWindow());
    document.addEventListener('visibilitychange', () => this.onVisibility());
    this.startLoading();
  }

  /** The TV is always 1920x1080. This is for trying the app in a browser window. */
  fitToWindow() {
    const scale = Math.min(window.innerWidth / FRAME_W, window.innerHeight / FRAME_H);
    this.root.style.transform = Math.abs(scale - 1) < 0.001 ? '' : `scale(${scale})`;
  }

  // ---- Building ----

  buildUi() {
    const { repo, focus } = this;
    this.shell = el('div', 'shell', 'hidden');
    this.buildTopBar();
    this.content = el('div', 'content');
    add(this.shell, this.topBar, this.content);

    this.guide = new GuideView(repo, this.listings, focus, guideHeight);
    this.guide.onFocusChanged = (ch, block) => {
      this.updateHero(ch, block);
      this.updateStatusText();
    };
    this.guide.onSelect = (ch) => this.tuneFullscreen(ch, this.guideList());
    this.guide.onPreview = (ch) => {
      this.surfList = this.guideList();
      this.tune(ch);
    };
    this.guide.onToggleFavorite = (ch) => this.toggleFavorite(ch);
    this.guide.onOptions = (ch) => this.showChannelOptions(ch, this.guideList());
    this.guideScreen = this.buildGuideScreen();

    this.browser = new BrowserScreen(repo, focus, gridHeight, {
      onTune: (ch, list) => this.tuneFullscreen(ch, list),
      onToggleFavorite: (ch) => this.toggleFavorite(ch),
      onOptions: (ch, list) => this.showChannelOptions(ch, list),
      pickRegion: () => this.pickRegion(),
      pickLanguage: () => this.pickLanguage(),
      regionLabel: () => this.regionLabel(),
      languageLabel: () => this.languageLabel(),
    });
    this.settings = new SettingsScreen(repo, {
      pickRegion: () => this.pickRegion(),
      pickLanguage: () => this.pickLanguage(),
      regionLabel: () => this.regionLabel(),
      languageLabel: () => this.languageLabel(),
      listsChanged: () => {
        this.surfList = [];
        this.reloadGuide();
        this.updateStats();
      },
      crtLookChanged: () => this.applyLook(),
      crtPictureChanged: () => this.placeVideo(),
      checkForUpdate: () => this.checkForUpdate(),
    }, __APP_VERSION__);
    for (const s of [this.guideScreen, this.browser.root, this.settings.root]) {
      show(s, false);
      this.content.appendChild(s);
    }

    this.pipFrame = el('div', 'pip-frame', 'hidden');
    this.video = el('video', 'video', 'hidden');
    this.videoStatus = el('div', 'video-status', 'hidden');
    this.banner = this.buildBanner();
    this.crtNumber = el('div', 'crt-number', 'hidden');
    this.breakLabel = el('div', 'break-label', 'hidden', 'Pluto ad break · show will resume');
    this.guideChannelView = new GuideChannelView();
    this.staticView = new StaticView();
    this.loadingText = el('div', 'loading-text');
    this.retryButton = button('retry', 'Try again', () => this.startLoading());
    this.loadingView = add(el('div', 'loading'), el('div', 'loading-brand', null, 'OpenGuide'), this.loadingText, this.retryButton);
    show(this.retryButton, false);
    this.picker = new Picker(focus);
    this.cropper = el('div', 'cropper', 'hidden');
    this.toastEl = el('div', 'toast', 'hidden');
    add(this.root, this.shell, this.pipFrame, this.video, this.videoStatus, this.guideChannelView.root, this.staticView.root,
      this.banner, this.crtNumber, this.breakLabel, this.loadingView, this.picker.root, this.cropper, this.toastEl);

    this.player = new TvPlayer(this.video, repo, (s) => this.onTuneState(s), () => {
      this.toast("Subtitles on, but this stream isn't offering any");
    }, () => this.updateBreakLabel());
    const placed = () => { if (this.prefs.crtMode) this.placeVideo(); };
    this.video.addEventListener('loadedmetadata', placed);
    this.video.addEventListener('resize', placed);
  }

  buildTopBar() {
    this.topBar = el('div', 'topbar');
    this.tabGuide = button(null, 'Guide', () => this.showGuide(true));
    this.tabBrowser = button(null, 'Channels', () => this.showBrowser(true));
    this.tabSettings = button(null, 'Settings', () => this.showSettings(true));
    this.categoryChip = button('chip', '', () => this.pickGuideCategory());
    this.status = el('div', 'status', 'ell');
    this.clock = el('div', 'clock');
    add(this.topBar, el('div', 'brand', null, 'OpenGuide'), this.tabGuide, this.tabBrowser, this.tabSettings,
      this.categoryChip, this.status, this.clock);
  }

  buildGuideScreen() {
    this.hero = el('div', 'hero');
    this.heroChannel = el('div', 'hero-channel', 'ell');
    this.heroTitle = el('div', 'hero-title', 'ell');
    this.heroTime = el('div', 'hero-time', 'ell');
    this.heroProgress = progressBar('hero-progress');
    this.heroDesc = el('div', 'hero-desc');
    add(this.hero, this.heroChannel, this.heroTitle, this.heroTime, this.heroProgress, this.heroDesc);
    return add(el('div', 'guide-screen', 'screen'), this.hero, this.guide.root);
  }

  buildBanner() {
    const b = el('div', 'banner', 'hidden');
    this.bannerLogo = el('img', 'banner-logo');
    this.bannerChannel = el('div', 'banner-channel', 'ell');
    this.bannerTitle = el('div', 'banner-title', 'ell');
    this.bannerTime = el('div', 'banner-time', 'ell');
    this.bannerProgress = progressBar('banner-progress');
    this.bannerNext = el('div', 'banner-next', 'ell');
    this.bannerState = el('div', 'banner-state');
    this.bannerHint = el('div', 'banner-hint', 'ell');
    const text = add(el('div', 'banner-text'), this.bannerChannel, this.bannerTitle, this.bannerTime, this.bannerProgress,
      this.bannerNext, this.bannerState, this.bannerHint);
    return add(b, this.bannerLogo, text);
  }

  /** Applies CRT mode. Colours and most sizes follow from the class; the rest is worked out here. */
  applyLook() {
    const crt = this.prefs.crtMode;
    this.root.classList.toggle('crt', crt);
    if (crt) {
      const [w, h] = crtWindow();
      this.hero.style.height = `${h}px`;
      this.hero.style.marginRight = `${w + 24}px`;
    } else {
      this.hero.style.height = '';
      this.hero.style.marginRight = '';
    }
    this.guide.setCrt(crt);
    this.browser.setCrt(crt);
    this.updateClock();
    this.reloadGuide();
    this.placeVideo();
  }

  // ---- Start ----

  startLoading() {
    this.screen = SCREEN.LOADING;
    show(this.loadingView, true);
    show(this.retryButton, false);
    this.repo.start((msg) => { this.loadingText.textContent = msg; }).then(() => this.onDataReady(), (e) => {
      this.loadingText.textContent = `Couldn't reach iptv-org. Check this TV's internet connection.\n(${e.message})`;
      show(this.retryButton, true);
      this.focus.set(this.retryButton);
    });
  }

  onDataReady() {
    this.uiReady = true;
    show(this.loadingView, false);
    show(this.shell, true);
    const { repo } = this;
    if (this.prefs.crtMode && repo.crtList.size) this.guideCategory = CATEGORY_CRT;
    this.reloadGuide();
    this.browser.refresh();
    this.settings.refresh();
    this.showGuide(true);
    this.guide.selectChannel(this.prefs.lastChannel);
    const last = this.savedChannel();
    if (last) {
      this.surfList = this.guideList();
      this.tune(last);
    }

    repo.on('lineup', () => {
      this.reloadGuide();
      if (this.screen === SCREEN.BROWSER) this.browser.refresh();
    });
    repo.on('visibility', () => {
      this.reloadGuide();
      // Not the browser: it would lose your place, and it refreshes whenever it's opened.
      this.updateStats();
    });
    repo.on('epg', () => {
      this.listings.clear();
      this.guide.refreshFocus();
      if (this.screen === SCREEN.BROWSER) this.browser.renderGrid();
      this.refreshGuideChannel();
      this.updateStats();
    });
    repo.on('status', () => {
      this.updateStatusText();
      this.updateStats();
    });
    this.tick();
    setInterval(() => this.tick(), 30000);
  }

  updateClock() {
    const now = Date.now();
    this.clock.textContent = (this.prefs.crtMode ? '' : `${clockDay(now)}  `) + timeOf(now);
  }

  tick() {
    this.updateClock();
    this.guide.tick();
    this.repo.refreshAiring();
    this.repo.refreshListings(false);
    this.repo.checkSources(false);
    if (this.screen === SCREEN.BROWSER) this.browser.renderGrid();
    this.refreshGuideChannel();
  }

  updateStatusText() {
    let text = this.repo.epgStatus;
    if (!text) {
      if (this.screen === SCREEN.SETTINGS) text = '';
      else if (this.screen === SCREEN.GUIDE && !this.guide.isHome()) text = 'Back: your channel';
      else text = 'Hold OK for options';
    }
    this.status.textContent = text;
  }

  onListingsLoaded() {
    this.guide.refreshFocus();
    if (!this.banner.classList.contains('hidden') && this.player.channel) this.updateBannerGuide(this.player.channel);
  }

  updateStats() {
    const { repo, prefs } = this;
    const c = repo.catalog;
    if (!c) return;
    const listed = repo.lineup.filter((ch) => repo.times.has(ch.id)).length;
    const dead = repo.lineup.filter((ch) => repo.deadChannels.has(ch.id)).length;
    this.settings.setStats(
      `${c.channels.length} channels worldwide, ${repo.lineup.length} in this region. ` +
      `${listed} of those have listings (${repo.programmeCount()} programmes stored).\n` +
      `${dead} have no working source right now${prefs.hideDead ? ' and are hidden.' : '.'}\n` +
      `Channels updated ${stampOf(c.fetchedAt)}. ` +
      `Listings updated ${prefs.epgFetchedAt ? stampOf(prefs.epgFetchedAt) : 'never yet'}`,
    );
  }

  /** The guide's channels. An empty CRT list stays empty, so it can explain how to add to it. */
  guideList() {
    const list = this.repo.filtered(this.guideCategory);
    const c = this.guideCategory;
    const withGuide = c === CATEGORY_ALL || (c === CATEGORY_CRT && list.length > 0);
    return this.prefs.crtMode && this.prefs.guideChannel && withGuide ? [GUIDE_CHANNEL].concat(list) : list;
  }

  /** What was last on, which the catalog can't find if it was the guide channel. */
  savedChannel() {
    const id = this.prefs.lastChannel;
    if (id === GUIDE_CHANNEL_ID) return this.prefs.crtMode && this.prefs.guideChannel ? GUIDE_CHANNEL : null;
    return this.repo.byId.get(id) || null;
  }

  reloadGuide() {
    if (!this.repo.catalog) return;
    this.guide.setChannels(this.guideList());
    this.categoryChip.firstChild.textContent = `${this.prefs.crtMode ? '' : 'Showing: '}${this.categoryName(this.guideCategory)}  ▾`;
  }

  categoryName(id) {
    if (id === CATEGORY_ALL) return 'All channels';
    if (id === CATEGORY_FAVORITES) return 'Favorites';
    if (id === CATEGORY_CRT) return 'CRT list';
    const c = this.repo.catalog.categories.find((x) => x.id === id);
    return c ? c.name : id;
  }

  regionLabel() {
    const r = this.repo.region;
    if (r === REGION_WORLD) return 'Region: Worldwide';
    const c = this.repo.catalog.countries.find((x) => x.code === r);
    return `Region: ${c ? c.name : r}`;
  }

  languageLabel() {
    const l = this.repo.language;
    if (l === LANGUAGE_ALL) return 'Language: All';
    const c = this.repo.catalog.languages.find((x) => x.code === l);
    return `Language: ${c ? c.name : l}`;
  }

  updateHero(ch, block) {
    const progress = this.heroProgress;
    if (!ch) {
      this.heroChannel.textContent = '';
      this.heroTitle.textContent = 'No channels here yet';
      if (this.guideCategory === CATEGORY_FAVORITES) this.heroTime.textContent = 'Hold OK on any channel to add it to your favorites.';
      else if (this.guideCategory === CATEGORY_CRT) this.heroTime.textContent = 'Hold OK on any channel to add it to your CRT list.';
      else this.heroTime.textContent = '';
      this.heroDesc.textContent = '';
      progress.style.visibility = 'hidden';
      return;
    }
    const number = this.repo.numbers.get(ch.id);
    this.heroChannel.textContent = `${number === undefined ? '' : number}  ${ch.name}`;
    if (ch.id === GUIDE_CHANNEL_ID) {
      this.heroTitle.textContent = ch.name;
      this.heroTime.textContent = "What's on now and next, scrolling";
      this.heroDesc.textContent = 'Press OK to watch.';
      progress.style.visibility = 'hidden';
      return;
    }
    const p = block ? block.programme : null;
    if (!p) {
      this.heroTitle.textContent = ch.name;
      this.heroTime.textContent = 'No listings for this time';
      this.heroDesc.textContent = this.repo.deadChannels.has(ch.id) ? "This channel didn't respond when last tried." : 'Press OK to watch.';
      progress.style.visibility = 'hidden';
      return;
    }
    const now = Date.now();
    const airing = now >= p.start && now < p.stop;
    this.heroTitle.textContent = p.title;
    const parts = [`${timeOf(p.start)} – ${timeOf(p.stop)}`];
    if (airing) parts.push(durationLeft(p.stop - now));
    if (p.cat) parts.push(p.cat);
    if (p.ep) parts.push(p.ep);
    this.heroTime.textContent = parts.join('  ·  ');
    this.heroDesc.textContent = [p.sub, p.desc].filter(Boolean).join(' — ');
    progress.style.visibility = airing ? 'visible' : 'hidden';
    if (airing) setProgress(progress, (now - p.start) / (p.stop - p.start));
  }

  // ---- Screens ----

  showScreen(target) {
    this.screen = target;
    const full = target === SCREEN.FULLSCREEN;
    this.root.classList.toggle('watching', full);
    show(this.shell, !full);
    show(this.guideScreen, target === SCREEN.GUIDE);
    show(this.browser.root, target === SCREEN.BROWSER);
    show(this.settings.root, target === SCREEN.SETTINGS);
    show(this.categoryChip, target === SCREEN.GUIDE);
    for (const [tab, screen] of [[this.tabGuide, SCREEN.GUIDE], [this.tabBrowser, SCREEN.BROWSER], [this.tabSettings, SCREEN.SETTINGS]]) {
      tab.classList.toggle('selected', target === screen);
    }
    if (full) this.focus.set(null);
    else this.hideBanner();
    if (!full) this.staticView.cancel();
    this.updateGuideChannel();
    this.updateBreakLabel();
    this.updateStatusText();
    this.placeVideo();
    this.guide.layout();
  }

  defaultFocus() {
    if (this.screen === SCREEN.GUIDE) this.focus.set(this.guide.root);
    else if (this.screen === SCREEN.BROWSER) this.browser.focusFirst();
    else if (this.screen === SCREEN.SETTINGS) this.focus.set(this.settings.region);
  }

  showGuide(giveFocus) {
    if (this.screen === SCREEN.SETTINGS) this.resumeIfStopped();
    this.showScreen(SCREEN.GUIDE);
    if (giveFocus) this.focus.set(this.guide.root);
  }

  showBrowser(giveFocus) {
    if (this.screen === SCREEN.SETTINGS) this.resumeIfStopped();
    this.showScreen(SCREEN.BROWSER);
    this.browser.refresh();
    if (giveFocus) this.browser.focusFirst();
  }

  showSettings(giveFocus) {
    this.showScreen(SCREEN.SETTINGS);
    // No video window on this screen, so don't leave audio playing with nothing on show.
    if (this.player.channel) this.prefs.lastChannel = this.player.channel.id;
    this.player.stop();
    this.settings.refresh();
    this.updateStats();
    if (giveFocus) this.focus.set(this.settings.region);
  }

  resumeIfStopped() {
    if (this.player.channel) return;
    const last = this.savedChannel();
    if (last) this.tune(last);
  }

  // ---- Video placement ----

  /** Where video goes on this screen, as left, top, width, height, or null if it isn't shown. */
  videoFrame() {
    const crt = this.prefs.crtMode;
    switch (this.screen) {
      case SCREEN.FULLSCREEN:
        return [0, 0, FRAME_W, FRAME_H];
      case SCREEN.GUIDE: {
        if (!crt) return [FRAME_W - 54 - 512, 104, 512, 288];
        const [w, h] = crtWindow();
        return [FRAME_W - 20 - w, 12 + 64 + 12 + 6, w, h - 12];
      }
      case SCREEN.BROWSER:
        if (!crt) return [54, FRAME_H - 20 - 224, 400, 224];
        return [12, FRAME_H - 8 - 270, 360, 270];
      default:
        return null;
    }
  }

  placeVideo() {
    if (!this.video) return;
    const f = this.videoFrame();
    const crt = this.prefs.crtMode;
    show(this.video, !!f);
    show(this.pipFrame, !!f && crt && this.screen !== SCREEN.FULLSCREEN);
    if (!f) {
      show(this.videoStatus, false);
      return;
    }
    const [left, top, fw, fh] = f;
    const place = (e, l, t, w, h) => {
      e.style.left = `${l}px`;
      e.style.top = `${t}px`;
      e.style.width = `${w}px`;
      e.style.height = `${h}px`;
    };
    place(this.videoStatus, left, top, fw, fh);
    if (crt) {
      const [w, h] = this.crtVideoBox(fw, fh);
      place(this.video, left + (fw - w) / 2, top + (fh - h) / 2, w, h);
      place(this.pipFrame, left - 6, top - 6, fw + 12, fh + 12);
    } else {
      place(this.video, left, top, fw, fh);
    }
    this.applyVideoStatus();
  }

  crtVideoBox(fw, fh) {
    const v = this.video;
    const aspect = v.videoWidth > 0 && v.videoHeight > 0 ? v.videoWidth / v.videoHeight : 16 / 9;
    const id = this.player && this.player.channel ? this.player.channel.id : null;
    const full = this.screen === SCREEN.FULLSCREEN;
    // A small window can't clip what would overflow it, so it never fills or crops.
    let stretch = id ? this.prefs.channelStretch(id) : this.prefs.crtStretch;
    if (stretch === STRETCH.FILL && !full) stretch = STRETCH.FIT;
    const [cropX, cropY] = full && id ? this.prefs.channelCrop(id) : [0, 0];
    const fitted = crtVideoSize(fw, fh, fw / fh, croppedAspect(aspect, cropX, cropY), stretch);
    return uncroppedSize(fitted[0], fitted[1], cropX, cropY);
  }

  applyVideoStatus() {
    const s = this.player ? this.player.state : { kind: TUNE.IDLE };
    let text = null;
    if (s.kind === TUNE.CONNECTING) text = s.total > 1 ? `Connecting…\nSource ${s.attempt} of ${s.total}` : 'Connecting…';
    else if (s.kind === TUNE.FAILED) text = `${s.channel.name} isn't responding right now.`;
    else if (s.kind === TUNE.IDLE && this.screen === SCREEN.GUIDE) {
      text = this.prefs.crtMode ? 'OK: watch\nHold OK: options' : 'Press OK on a channel to watch.\nHold OK to preview it here.';
    }
    this.videoStatus.textContent = text || '';
    show(this.videoStatus, !!text && !this.video.classList.contains('hidden'));
  }

  // ---- Pickers ----

  categoryCounts() {
    const counts = new Map();
    for (const ch of this.repo.filtered(CATEGORY_ALL)) {
      for (const c of ch.categories) counts.set(c, (counts.get(c) || 0) + 1);
    }
    return counts;
  }

  pickGuideCategory() {
    const c = this.repo.catalog;
    if (!c) return;
    const counts = this.categoryCounts();
    const items = [
      { id: CATEGORY_ALL, label: 'All channels' },
      { id: CATEGORY_FAVORITES, label: '★ Favorites' },
      { id: CATEGORY_CRT, label: 'CRT list' },
    ];
    for (const x of c.categories) if (counts.get(x.id)) items.push({ id: x.id, label: `${x.name} (${counts.get(x.id)})` });
    this.picker.show('Show in guide', items, this.guideCategory, null, (item) => {
      this.guideCategory = item.id;
      this.reloadGuide();
      this.focus.set(this.guide.root);
    });
  }

  pickRegion() {
    const c = this.repo.catalog;
    if (!c) return;
    const counts = new Map();
    for (const ch of c.channels) counts.set(ch.country, (counts.get(ch.country) || 0) + 1);
    const items = [{ id: REGION_WORLD, label: `Worldwide (${c.channels.length}, fewer listings)` }];
    for (const x of c.countries) if (counts.get(x.code)) items.push({ id: x.code, label: `${x.flag} ${x.name} (${counts.get(x.code)})` });
    this.picker.show('Region', items, this.repo.region, null, (item) => {
      this.repo.setRegion(item.id).catch((e) => console.warn('Region change failed', e));
      this.surfList = [];
      this.settings.refresh();
      this.browser.refresh();
    });
  }

  pickLanguage() {
    const c = this.repo.catalog;
    if (!c) return;
    const counts = this.repo.languageCounts();
    let total = 0;
    counts.forEach((n) => { total += n; });
    const items = [{ id: LANGUAGE_ALL, label: `All languages (${total})` }];
    const languages = c.languages.filter((l) => counts.get(l.code)).sort((a, b) => counts.get(b.code) - counts.get(a.code));
    for (const l of languages) items.push({ id: l.code, label: `${l.name} (${counts.get(l.code)})` });
    this.picker.show('Language', items, this.repo.language, null, (item) => {
      this.repo.setLanguage(item.id);
      this.surfList = [];
      this.settings.refresh();
      this.browser.refresh();
    });
  }

  // ---- Updates ----

  /** A webOS app can't install itself, so this says what to fetch and leaves installing to the viewer. */
  checkForUpdate() {
    if (this.updateBusy) return;
    this.updateBusy = true;
    this.settings.setUpdateText('Checking for updates…');
    latestIfNewer(getJson, __UPDATE_API__, __APP_VERSION__).then((tag) => {
      this.settings.setUpdateText(tag
        ? `${tag} is available. Install its OpenGuide.ipk from a computer`
        : `Up to date (version ${__APP_VERSION__}). Press to check again`);
    }, () => {
      this.settings.setUpdateText("Couldn't check for updates. Press to try again");
    }).then(() => { this.updateBusy = false; });
  }

  // ---- Playback ----

  tune(ch, stream) {
    const cur = this.player.channel;
    if (cur && cur !== ch) this.previousChannel = cur;
    if (this.prefs.crtMode && this.prefs.channelStatic && this.screen === SCREEN.FULLSCREEN) this.staticView.start();
    this.player.tune(ch, stream);
    // Nothing to wait for, so this is just the burst.
    if (ch.id === GUIDE_CHANNEL_ID) this.staticView.finish();
    this.updateGuideChannel();
    this.guide.tunedChannelId = ch.id;
    this.guide.invalidate();
    this.updateStatusText();
    // Each channel has its own picture settings.
    if (this.prefs.crtMode) this.placeVideo();
  }

  tuneFullscreen(ch, list) {
    this.surfList = list;
    const already = this.player.channel === ch && this.player.state.kind !== TUNE.FAILED;
    this.showScreen(SCREEN.FULLSCREEN);
    if (!already) this.tune(ch);
    this.showBanner();
  }

  updateGuideChannel() {
    const ch = this.player && this.player.channel;
    const on = this.screen === SCREEN.FULLSCREEN && !!ch && ch.id === GUIDE_CHANNEL_ID;
    this.guideChannelView.setVisible(on);
    if (on) this.refreshGuideChannel();
  }

  refreshGuideChannel() {
    if (!this.guideChannelView.visible) return;
    const list = this.surfList.length ? this.surfList : this.guideList();
    const channels = list.filter((ch) => ch.id !== GUIDE_CHANNEL_ID);
    const { repo } = this;
    repo.refreshAiring();
    this.guideChannelView.setRows(guideRows(channels, repo.numbers, repo.airing,
      startingNext(repo.times, Date.now(), GUIDE_NEXT_WITHIN)));
  }

  updateBreakLabel() {
    show(this.breakLabel, this.screen === SCREEN.FULLSCREEN && !!this.player && this.player.onBreak);
  }

  onTuneState(s) {
    if (s.kind === TUNE.PLAYING || s.kind === TUNE.FAILED) this.staticView.finish();
    this.applyVideoStatus();
    if (this.screen === SCREEN.FULLSCREEN) {
      this.updateBannerState();
      if (s.kind === TUNE.CONNECTING || s.kind === TUNE.FAILED) this.showBanner(s.kind === TUNE.FAILED);
    }
    if (s.kind === TUNE.FAILED) this.guide.refreshFocus();
  }

  hideBanner() {
    clearTimeout(this.bannerTimer);
    show(this.banner, false);
    show(this.crtNumber, false);
  }

  showBanner(persist) {
    const ch = this.player.channel;
    if (!ch) return;
    clearTimeout(this.bannerTimer);
    const number = this.repo.numbers.get(ch.id);
    if (this.prefs.crtMode) {
      const failed = this.player.state.kind === TUNE.FAILED;
      this.crtNumber.textContent = (number === undefined ? '--' : number) + (failed ? '  NO SIGNAL' : '');
      show(this.crtNumber, true);
      if (!persist) this.bannerTimer = setTimeout(() => this.hideBanner(), 4000);
      return;
    }
    show(this.banner, true);
    this.bannerChannel.textContent = `${number === undefined ? '' : number}  ${ch.name}${this.repo.favorites.has(ch.id) ? '  ★' : ''}`;
    const keys = this.prefs.dpadChangesChannel ? 'Up/Down' : 'Channel +/-';
    this.bannerHint.textContent = `${keys}: change channel   ·   Left: last channel   ·   Right or hold OK: options   ·   Back: guide`;
    setLogo(this.bannerLogo, ch.logo);
    this.updateBannerGuide(ch);
    this.updateBannerState();
    if (!persist) this.bannerTimer = setTimeout(() => this.hideBanner(), 6000);
  }

  updateBannerGuide(ch) {
    const rows = this.listings.get(ch.id) || [];
    const now = Date.now();
    const cur = rows.find((r) => now >= r.start && now < r.stop);
    const next = rows.find((r) => r.start >= now);
    if (cur) {
      this.bannerTitle.textContent = cur.title;
      this.bannerTime.textContent = `${timeOf(cur.start)} – ${timeOf(cur.stop)}  ·  ${durationLeft(cur.stop - now)}`;
      setProgress(this.bannerProgress, (now - cur.start) / (cur.stop - cur.start));
    } else {
      this.bannerTitle.textContent = ch.name;
      this.bannerTime.textContent = 'No listings for this channel';
    }
    this.bannerProgress.style.visibility = cur ? 'visible' : 'hidden';
    this.bannerNext.textContent = next ? `Next at ${timeOf(next.start)}: ${next.title}` : '';
    show(this.bannerNext, !!next);
  }

  updateBannerState() {
    const s = this.player.state;
    let text = '';
    if (s.kind === TUNE.CONNECTING) text = s.total > 1 ? `Connecting… source ${s.attempt} of ${s.total}` : 'Connecting…';
    else if (s.kind === TUNE.FAILED) {
      text = "This channel isn't responding right now. Change channel, or press Back for the guide." +
        (s.channel.streams.some((x) => x.geoBlocked) ? ' It may be blocked in your area.' : '');
    } else if (s.kind === TUNE.PLAYING) text = s.stream.quality || '';
    this.bannerState.textContent = text;
    this.bannerState.classList.toggle('bad', s.kind === TUNE.FAILED);
    show(this.bannerState, !!text);
  }

  surf(delta) {
    const list = this.surfList.length ? this.surfList : this.guideList();
    if (!list.length) return;
    const size = list.length;
    const i = list.indexOf(this.player.channel);
    let at = i < 0 ? 0 : (i + delta + size) % size;
    // Skip channels known to be dead, unless that's all there is.
    if (this.prefs.hideDead) {
      for (let left = size; left > 0 && this.repo.deadChannels.has(list[at].id); left--) at = (at + delta + size) % size;
    }
    this.tune(list[at]);
    this.guide.selectChannel(list[at].id);
    this.showBanner();
  }

  toast(text) {
    clearTimeout(this.toastTimer);
    this.toastEl.textContent = '';
    this.toastEl.appendChild(el('span', null, null, text));
    show(this.toastEl, true);
    this.toastTimer = setTimeout(() => show(this.toastEl, false), 3000);
  }

  toggleFavorite(ch) {
    if (ch.id === GUIDE_CHANNEL_ID) return;
    const added = this.repo.toggleFavorite(ch.id);
    this.toast(added ? `Added ${ch.name} to favorites` : `Removed ${ch.name} from favorites`);
    if (this.guideCategory === CATEGORY_FAVORITES) this.reloadGuide();
    else this.guide.refreshFocus();
  }

  toggleCrtList(ch) {
    const added = this.repo.toggleCrt(ch.id);
    this.toast(added ? `Added ${ch.name} to the CRT list` : `Removed ${ch.name} from the CRT list`);
    if (this.guideCategory === CATEGORY_CRT) this.reloadGuide();
  }

  crtListLabel(ch) {
    return this.repo.crtList.has(ch.id) ? 'Remove from CRT list' : 'Add to CRT list';
  }

  favoriteLabel(ch) {
    return this.repo.favorites.has(ch.id) ? 'Remove from favorites' : 'Add to favorites';
  }

  showChannelOptions(ch, list) {
    if (ch.id === GUIDE_CHANNEL_ID) {
      this.tuneFullscreen(ch, list); // it can't be previewed or added to a list, so there's nothing to choose
      return;
    }
    const items = [
      { id: 'watch', label: 'Watch' },
      { id: 'preview', label: 'Preview in small window' },
      { id: 'fav', label: this.favoriteLabel(ch) },
      { id: 'crt', label: this.crtListLabel(ch) },
    ];
    this.picker.show(ch.name, items, null, null, (item) => {
      if (item.id === 'watch') this.tuneFullscreen(ch, list);
      else if (item.id === 'preview') {
        this.surfList = list;
        this.tune(ch);
      } else {
        if (item.id === 'fav') this.toggleFavorite(ch);
        else this.toggleCrtList(ch);
        if (this.screen === SCREEN.BROWSER) this.browser.refresh();
      }
    });
  }

  showPlayerOptions() {
    const ch = this.player.channel;
    if (!ch) return;
    if (ch.id === GUIDE_CHANNEL_ID) {
      // No stream, so no picture or source settings, and it's on every list it can be on.
      const previous = this.previousChannel;
      if (previous) {
        this.picker.show(ch.name, [{ id: 'prev', label: `Last channel: ${previous.name}` }], null, 'prev',
          () => this.goToPreviousChannel());
      }
      return;
    }
    const items = [
      { id: 'fav', label: this.favoriteLabel(ch) },
      { id: 'crt', label: this.crtListLabel(ch) },
      { id: 'subs', label: `Subtitles: ${this.prefs.subtitles(ch.id) ? 'On' : 'Off'}` },
    ];
    if (this.prefs.crtMode) {
      items.push({ id: 'picture', label: `Picture: ${STRETCH_LABEL[this.prefs.channelStretch(ch.id)]}` });
      items.push({ id: 'crop', label: 'Crop black bars' });
    }
    if (this.previousChannel) items.push({ id: 'prev', label: `Last channel: ${this.previousChannel.name}` });
    ch.streams.forEach((s, i) => items.push({ id: `src${i}`, label: `Source ${i + 1}: ${streamLabel(s)}` }));
    const state = this.player.state;
    const current = state.kind === TUNE.PLAYING ? `src${ch.streams.indexOf(state.stream)}` : null;
    this.picker.show(ch.name, items, current, 'fav', (item) => {
      if (item.id === 'fav') this.toggleFavorite(ch);
      else if (item.id === 'crt') this.toggleCrtList(ch);
      else if (item.id === 'subs') this.toggleSubtitles(ch);
      else if (item.id === 'picture') this.pickPicture(ch);
      else if (item.id === 'crop') this.showCropper(ch);
      else if (item.id === 'prev') this.goToPreviousChannel();
      else if (item.id !== current) this.tune(ch, ch.streams[Number(item.id.substring(3))]); // not the one already playing
    });
  }

  pickPicture(ch) {
    const items = STRETCH_ORDER.map((id) => ({ id, label: STRETCH_LABEL[id] }));
    this.picker.show(`Picture for ${ch.name}`, items, this.prefs.channelStretch(ch.id), null, (item) => {
      this.prefs.setChannelStretch(ch.id, item.id);
      this.placeVideo();
    });
  }

  toggleSubtitles(ch) {
    const on = !this.prefs.subtitles(ch.id);
    this.player.setSubtitles(on);
    this.toast(`Subtitles ${on ? 'on' : 'off'} for ${ch.name}`);
  }

  goToPreviousChannel() {
    if (this.previousChannel) this.tune(this.previousChannel);
    this.showBanner();
  }

  showCropper(ch) {
    this.cropping = true;
    const [x, y] = this.prefs.channelCrop(ch.id);
    this.updateCropper(x, y);
    show(this.cropper, true);
  }

  updateCropper(x, y) {
    this.cropper.textContent = `Crop for this channel: top and bottom ${y}%, sides ${x}%\n` +
      'Up/Down: top and bottom   Left/Right: sides\nOK or Back: done';
  }

  handleCropKey(code) {
    const ch = this.player.channel;
    if (!ch || code === KEY.OK || this.isBack(code)) {
      this.cropping = false;
      show(this.cropper, false);
      return;
    }
    let [x, y] = this.prefs.channelCrop(ch.id);
    if (code === KEY.LEFT) x--;
    else if (code === KEY.RIGHT) x++;
    else if (code === KEY.UP) y++;
    else if (code === KEY.DOWN) y--;
    else return;
    x = Math.max(0, Math.min(MAX_CROP, x));
    y = Math.max(0, Math.min(MAX_CROP, y));
    this.prefs.setChannelCrop(ch.id, x, y);
    this.updateCropper(x, y);
    this.placeVideo();
  }

  // ---- Remote ----

  isBack(code) {
    return code === KEY.BACK || code === KEY.ESC || code === KEY.BACKSPACE;
  }

  bindKeys() {
    document.addEventListener('keydown', (e) => this.onKeyDown(e));
    document.addEventListener('keyup', (e) => {
      delete this.held[e.keyCode];
      this.focus.keyUp(e.keyCode);
    });
    // The Magic Remote's pointer, while watching: click for info, wheel to change channel.
    for (const surface of [this.video, this.guideChannelView.root]) {
      surface.addEventListener('click', () => {
        if (this.screen === SCREEN.FULLSCREEN && !this.picker.isShowing) this.toggleBanner();
      });
      surface.addEventListener('wheel', (e) => {
        if (this.screen === SCREEN.FULLSCREEN && !this.picker.isShowing) this.surf(e.deltaY > 0 ? -1 : 1);
      });
    }
  }

  /** Key repeat isn't reported the same way by every remote, so this counts it. */
  repeatCount(code) {
    const n = this.held[code] === undefined ? 0 : this.held[code] + 1;
    this.held[code] = n;
    return n;
  }

  onKeyDown(e) {
    const code = e.keyCode;
    const search = this.browser.search;
    if (document.activeElement === search) {
      // Typing goes to the field. Leaving it is the only thing to catch here.
      if (code === KEY.OK || code === KEY.BACK || code === KEY.ESC || code === KEY.UP || code === KEY.DOWN) {
        search.blur();
        e.preventDefault();
        if (code === KEY.UP || code === KEY.DOWN) this.focus.move(code === KEY.UP ? 'up' : 'down');
      }
      return;
    }
    e.preventDefault();
    const repeat = this.repeatCount(code);
    if (this.cropping) {
      this.handleCropKey(code);
    } else if (this.picker.isShowing) {
      if (this.isBack(code)) {
        if (repeat === 0) this.picker.hide();
      } else {
        this.focus.handleKey(code, repeat);
      }
    } else if (this.screen === SCREEN.FULLSCREEN) {
      this.handleFullscreenKey(code, repeat);
    } else if (this.isBack(code)) {
      if (repeat === 0) this.handleBack();
    } else {
      this.focus.handleKey(code, repeat);
    }
  }

  toggleBanner() {
    const showing = !this.banner.classList.contains('hidden') || !this.crtNumber.classList.contains('hidden');
    if (showing) this.hideBanner();
    else this.showBanner();
  }

  handleFullscreenKey(code, repeat) {
    if (repeat > 0) return;
    const surfing = this.prefs.dpadChangesChannel;
    switch (code) {
      case KEY.CH_UP: this.surf(1); break;
      case KEY.CH_DOWN: this.surf(-1); break;
      case KEY.UP: if (surfing) this.surf(1); else this.showBanner(); break;
      case KEY.DOWN: if (surfing) this.surf(-1); else this.showBanner(); break;
      case KEY.OK: this.focus.arm(() => this.toggleBanner(), () => this.showPlayerOptions()); break;
      case KEY.INFO: this.toggleBanner(); break;
      case KEY.LEFT: case KEY.REWIND: this.goToPreviousChannel(); break;
      case KEY.RIGHT: case KEY.RED: this.showPlayerOptions(); break;
      case KEY.PLAY: case KEY.PAUSE: this.player.togglePause(); break;
      case KEY.BACK: case KEY.ESC: case KEY.BACKSPACE:
        this.guide.selectChannel(this.player.channel ? this.player.channel.id : null);
        this.showGuide(true);
        break;
      default:
    }
  }

  handleBack() {
    const guideFocused = this.focus.has(this.guide.root);
    if (this.screen === SCREEN.LOADING) this.exit();
    else if (this.screen !== SCREEN.GUIDE) this.showGuide(true);
    else if (guideFocused && !this.guide.isHome()) this.guide.goHome();
    else if (guideFocused) {
      this.focus.set(this.tabGuide);
      this.confirmExit();
    } else this.confirmExit();
  }

  confirmExit() {
    const now = Date.now();
    if (now - this.lastExitPress < 2500) {
      this.exit();
    } else {
      this.lastExitPress = now;
      this.toast('Press Back again to exit');
    }
  }

  exit() {
    window.close();
  }

  // ---- Lifecycle ----

  /** A TV takes its decoder back when another app comes forward, so playback restarts on return. */
  onVisibility() {
    if (!this.uiReady) return;
    if (document.hidden) {
      if (this.player.channel) this.prefs.lastChannel = this.player.channel.id;
      this.player.stop();
    } else if (this.screen !== SCREEN.SETTINGS) {
      this.resumeIfStopped();
    }
  }
}

new App();
