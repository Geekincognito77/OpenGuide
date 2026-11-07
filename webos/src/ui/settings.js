import { STRETCH_LABEL, STRETCH_ORDER } from '../data/logic.js';
import { button } from './browser.js';
import { add, el, show } from './dom.js';

const CREDITS = 'Channels and streams come from the iptv-org project. Listings come from i.mjh.nz, EPGShare and EPG.PW. ' +
  'OpenGuide hosts no video and only links to publicly listed streams; some may be offline or blocked in your area.\n\n' +
  'In the guide: OK watches, and holding OK opens options (preview, favorite, CRT list). Hold Up or Down to scroll faster. ' +
  'Channel Up and Down page through the guide. Back returns to your channel and the current time, then to the tabs.\n' +
  'While watching: Channel Up and Down change channel, OK shows info, Left returns to the last channel, ' +
  'Right or holding OK opens options: subtitles and, in CRT mode, picture and crop, all saved for each channel.\n' +
  'The red button adds a favorite in the guide and opens options while watching. Play, Pause, Rewind and Fast Forward also work as shortcuts.';

const onOff = (v) => (v ? 'On' : 'Off');

/** Region, display options, refresh, and where the data comes from. Scrolls, as it outgrew a TV screen. */
export class SettingsScreen {
  constructor(repo, actions, version) {
    this.repo = repo;
    this.actions = actions;
    this.version = version;
    const { prefs } = repo;
    const toggle = (name, after) => () => {
      prefs[name] = !prefs[name];
      this.refresh();
      after();
    };

    this.root = el('div', 'settings', 'screen scroll');
    this.region = button(null, '', () => actions.pickRegion());
    this.language = button(null, '', () => actions.pickLanguage());
    this.onlyListed = button(null, '', toggle('onlyWithListings', () => actions.listsChanged()));
    this.hideDead = button(null, '', toggle('hideDead', () => actions.listsChanged()));
    this.dpadSurf = button(null, '', toggle('dpadChangesChannel', () => {}));
    this.crtMode = button(null, '', toggle('crtMode', () => actions.crtLookChanged()));
    this.crtStretch = button(null, '', () => {
      prefs.crtStretch = STRETCH_ORDER[(STRETCH_ORDER.indexOf(prefs.crtStretch) + 1) % STRETCH_ORDER.length];
      this.refresh();
      actions.crtPictureChanged();
    });
    this.refreshNow = button(null, 'Refresh channels and listings now', () => repo.refreshAll());
    this.update = button(null, '', () => actions.checkForUpdate());
    this.stats = el('div', 'stats');
    this.credits = el('div', 'credits', null, CREDITS);
    add(this.root, this.region, this.language, this.onlyListed, this.hideDead, this.dpadSurf, this.crtMode,
      this.crtStretch, this.refreshNow, this.update, this.stats, this.credits);
    // Lets the D-pad scroll down to the text below the last row.
    this.update.ogKey = (code) => {
      if (code === 40) this.root.scrollTop = this.root.scrollHeight;
      return false;
    };
    this.setUpdateText(null);
  }

  refresh() {
    const { prefs } = this.repo;
    if (!this.repo.catalog) return;
    this.region.setText(this.actions.regionLabel());
    this.language.setText(this.actions.languageLabel());
    this.onlyListed.setText(`Only channels with listings: ${onOff(prefs.onlyWithListings)}`);
    this.hideDead.setText(`Hide channels with no working source: ${onOff(prefs.hideDead)}`);
    this.dpadSurf.setText(`D-pad Up/Down change channel: ${onOff(prefs.dpadChangesChannel)}`);
    this.crtMode.setText(`CRT TV mode: ${onOff(prefs.crtMode)}`);
    this.crtStretch.setText(`Default picture: ${STRETCH_LABEL[prefs.crtStretch]}`);
    show(this.crtStretch, prefs.crtMode);
  }

  /** @param text a status to show on the update row, or null for its normal label. */
  setUpdateText(text) {
    this.update.setText(text || `Check for updates (this is version ${this.version})`);
  }

  setStats(text) {
    this.stats.textContent = text;
  }
}
