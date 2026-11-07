import { buildBlocks, floorHalfHour, HALF_HOUR, rowStep } from '../data/logic.js';
import { add, dayLabel, el, setLogo, show, timeOf } from './dom.js';
import { KEY } from './focus.js';

const HORIZON = 96 * HALF_HOUR;
const HEAD_H = 48;
const ROW_H = 100;
const CH_W = 380;
const CRT_HEAD_H = 56;
const CRT_ROW_H = 96;
const CRT_CH_W = 400;

/** Colour-codes programme types, which old guides used so you could scan for a film or a game. */
function crtTint(category) {
  const c = (category || '').toLowerCase();
  if (!c) return '';
  if (c.indexOf('movie') >= 0 || c.indexOf('film') >= 0) return 'movie';
  if (c.indexOf('sport') >= 0) return 'sport';
  if (c.indexOf('news') >= 0) return 'news';
  if (c.indexOf('kid') >= 0 || c.indexOf('child') >= 0 || c.indexOf('anim') >= 0) return 'kids';
  return '';
}

/**
 * The programme grid. Only rows on screen exist, so a long lineup costs nothing to scroll.
 *
 * Focus works like a cable box: moving up and down keeps your place in time (anchor), and a programme
 * running past the edge of the screen scrolls time before focus leaves it.
 */
export class GuideView {
  constructor(repo, listings, focus, height) {
    this.repo = repo;
    this.listings = listings;
    this.focus = focus;
    this.height = height;
    this.crt = false;
    this.onFocusChanged = () => {};
    this.onSelect = () => {};
    this.onPreview = () => {};
    this.onToggleFavorite = () => {};
    this.onOptions = () => {};
    this.channels = [];
    this.row = 0;
    this.topRow = 0;
    this.rowChosen = false;
    this.tunedChannelId = null;
    this.minStart = floorHalfHour(Date.now());
    this.viewStart = this.minStart;
    this.anchor = Date.now();
    this.renderQueued = false;

    this.root = el('div', 'guide', 'f');
    this.head = el('div', 'guide-head');
    this.day = el('div', 'guide-day');
    add(this.head, this.day);
    this.timeEls = [];
    this.rowsEl = el('div');
    this.empty = el('div', 'guide-empty', 'hidden', 'No channels here.');
    this.lineLow = el('div', 'guide-line-low');
    this.line = el('div', 'guide-line');
    add(this.root, this.head, this.rowsEl, this.empty, this.lineLow, this.line);
    this.lineLow.style.width = '4px';
    this.slots = [];

    this.root.ogKey = (code, repeat) => this.onKey(code, repeat);
    this.root.ogActivate = () => {
      const ch = this.currentChannel;
      if (ch) this.onSelect(ch);
    };
    this.root.ogHold = () => {
      const ch = this.currentChannel;
      if (ch) this.onOptions(ch);
    };
    this.root.ogFocus = () => this.invalidate();
    this.root.ogBlur = () => this.invalidate();
    this.root.ogRect = () => this.focusedRect();
    this.root.ogPointer = (target) => this.onPointer(target);
    this.root.ogWheel = (direction) => {
      if (this.moveRows(direction)) this.moved();
    };
  }

  setCrt(crt) {
    this.crt = crt;
    this.layout();
  }

  get headH() { return this.crt ? CRT_HEAD_H : HEAD_H; }
  get rowH() { return this.crt ? CRT_ROW_H : ROW_H; }
  get channelW() { return this.crt ? CRT_CH_W : CH_W; }
  /** A CRT can't resolve as much text, so it shows fewer half hours in bigger cells. */
  get span() { return (this.crt ? 3 : 4) * HALF_HOUR; }
  get gridW() { return 1920 - this.sideMargins - this.channelW; }
  get sideMargins() { return this.crt ? 24 : 108; }
  get pxPerMs() { return this.gridW / this.span; }
  get horizon() { return this.minStart + HORIZON; }
  get currentChannel() { return this.channels[this.row] || null; }

  /** How many rows fit, which depends on the look. */
  layout() {
    const rows = Math.max(1, Math.floor((this.height(this.crt) - this.headH) / this.rowH));
    this.visibleRows = rows;
    while (this.slots.length > rows) this.rowsEl.removeChild(this.slots.pop().root);
    while (this.slots.length < rows) {
      const root = el('div', null, 'grow');
      const chEl = el('div', null, 'gch');
      const num = el('div', null, 'gnum');
      const logo = el('img', null, 'glogo');
      const name = el('div', null, 'gname ell');
      const dot = el('div', null, 'gdot');
      add(chEl, num, logo, name, dot);
      const cells = el('div', null, 'gcells');
      add(root, chEl, cells);
      this.rowsEl.appendChild(root);
      this.slots.push({ root, num, logo, name, dot, cells });
    }
    this.slots.forEach((s, i) => { s.root.dataset.slot = i; });
    this.clampScroll();
    this.invalidate();
  }

  setChannels(list) {
    // Until a row has been chosen, stay at the top: listings arriving reorder the lineup on first run.
    const keep = this.rowChosen && this.currentChannel ? this.currentChannel.id : null;
    this.channels = list;
    // If this channel just left the list, stay at the same height rather than jumping to the top.
    const at = list.findIndex((c) => c.id === keep);
    this.row = at >= 0 ? at : Math.min(this.row, Math.max(list.length - 1, 0));
    this.moved();
  }

  selectChannel(id) {
    const i = this.channels.findIndex((c) => c.id === id);
    if (i >= 0) {
      this.row = i;
      this.rowChosen = true;
      this.moved();
    }
  }

  /** Called every so often: time moves on, so the guide's left edge must too. */
  tick() {
    const now = Date.now();
    this.minStart = floorHalfHour(now);
    if (this.viewStart < this.minStart) this.viewStart = this.minStart;
    if (this.anchor < now) this.anchor = now;
    this.refreshFocus();
  }

  get tunedRow() {
    return this.channels.findIndex((c) => c.id === this.tunedChannelId);
  }

  /** On the channel being watched, at the current time. A channel not in this list can't count against it. */
  isHome() {
    const tuned = this.tunedRow;
    return this.viewStart === this.minStart && (tuned < 0 || tuned === this.row);
  }

  goHome() {
    const tuned = this.tunedRow;
    if (tuned >= 0) this.row = tuned;
    this.viewStart = this.minStart;
    this.anchor = Date.now();
    this.moved();
  }

  refreshFocus() {
    this.invalidate();
    this.onFocusChanged(this.currentChannel, this.focusedBlock());
  }

  moved() {
    this.clampScroll();
    this.refreshFocus();
  }

  clampScroll() {
    if (this.row < this.topRow) this.topRow = this.row;
    if (this.row >= this.topRow + this.visibleRows) this.topRow = this.row - this.visibleRows + 1;
    this.topRow = Math.max(0, Math.min(this.topRow, Math.max(this.channels.length - this.visibleRows, 0)));
  }

  blocks(index) {
    const ch = this.channels[index];
    if (!ch) return [];
    return buildBlocks(this.listings.get(ch.id) || [], this.minStart, this.horizon);
  }

  focusedBlock() {
    const list = this.blocks(this.row);
    return list.find((b) => this.anchor >= b.start && this.anchor < b.stop) || list[list.length - 1] || null;
  }

  onKey(code, repeat) {
    let moved;
    switch (code) {
      case KEY.DOWN: moved = this.moveRows(rowStep(repeat)); break;
      case KEY.UP:
        // A fresh press moves up to the tabs; a held key stops at the top instead of overshooting.
        if (this.row === 0) return repeat > 0;
        moved = this.moveRows(-rowStep(repeat));
        break;
      case KEY.FAST_FORWARD: case KEY.CH_DOWN: moved = this.moveRows(this.visibleRows); break;
      case KEY.REWIND: case KEY.CH_UP: moved = this.moveRows(-this.visibleRows); break;
      case KEY.RIGHT: moved = this.moveRight(); break;
      case KEY.LEFT: moved = this.moveLeft(); break;
      case KEY.PLAY: case KEY.PAUSE:
        if (repeat === 0 && this.currentChannel) this.onPreview(this.currentChannel);
        return true;
      case KEY.RED:
        if (repeat === 0 && this.currentChannel) this.onToggleFavorite(this.currentChannel);
        return true;
      default: return false;
    }
    if (moved) {
      this.clampScroll();
      this.refreshFocus();
    }
    return true;
  }

  moveRows(delta) {
    const next = Math.max(0, Math.min(this.row + delta, Math.max(this.channels.length - 1, 0)));
    if (next === this.row) return false;
    this.row = next;
    this.rowChosen = true;
    return true;
  }

  moveRight() {
    const list = this.blocks(this.row);
    const i = list.findIndex((b) => this.anchor >= b.start && this.anchor < b.stop);
    if (i < 0) return false;
    const viewEnd = this.viewStart + this.span;
    if (list[i].stop > viewEnd) {
      if (viewEnd >= this.horizon) return false;
      this.viewStart += HALF_HOUR;
      this.anchor = Math.max(this.anchor, this.viewStart);
      return true;
    }
    const next = list[i + 1];
    if (!next) return false;
    this.anchor = next.start;
    while (next.start >= this.viewStart + this.span - HALF_HOUR / 2) this.viewStart += HALF_HOUR;
    return true;
  }

  moveLeft() {
    const list = this.blocks(this.row);
    const i = list.findIndex((b) => this.anchor >= b.start && this.anchor < b.stop);
    if (i < 0) return false;
    if (list[i].start < this.viewStart && this.viewStart > this.minStart) {
      this.viewStart -= HALF_HOUR;
      this.anchor = Math.max(list[i].start, this.viewStart);
      return true;
    }
    const prev = list[i - 1];
    if (!prev) return false;
    this.anchor = Math.max(prev.start, this.minStart);
    if (this.anchor < this.viewStart) this.viewStart = Math.max(floorHalfHour(this.anchor), this.minStart);
    return true;
  }

  /** The pointer picks the row and programme under it. */
  onPointer(target) {
    const rowEl = target.closest('.grow');
    if (!rowEl) return;
    const index = this.topRow + Number(rowEl.dataset.slot);
    const cell = target.closest('.gc');
    if (cell) this.anchor = Math.max(Number(cell.dataset.start), this.viewStart);
    if (index !== this.row || cell) {
      if (index < this.channels.length) {
        this.row = index;
        this.rowChosen = true;
      }
      this.refreshFocus();
    }
  }

  focusedRect() {
    const slot = this.slots[this.row - this.topRow];
    return (slot ? slot.root : this.root).getBoundingClientRect();
  }

  invalidate() {
    if (this.renderQueued) return;
    this.renderQueued = true;
    requestAnimationFrame(() => {
      this.renderQueued = false;
      this.render();
    });
  }

  render() {
    const hasChannels = this.channels.length > 0;
    show(this.empty, !hasChannels);
    show(this.head, hasChannels);
    show(this.rowsEl, hasChannels);
    show(this.line, false);
    show(this.lineLow, false);
    if (!hasChannels) return;

    this.renderHead();
    const focusedBlock = this.focusedBlock();
    const now = Date.now();
    this.slots.forEach((slot, i) => {
      const index = this.topRow + i;
      const ch = this.channels[index];
      show(slot.root, !!ch);
      if (ch) this.renderRow(slot, ch, index, index === this.row ? focusedBlock : null, now);
    });

    // Pre-load just off screen so scrolling doesn't reveal empty rows.
    const end = Math.min(this.topRow + this.visibleRows, this.channels.length);
    for (let i = end; i < Math.min(end + 6, this.channels.length); i++) this.listings.get(this.channels[i].id);
    for (let i = Math.max(this.topRow - 6, 0); i < this.topRow; i++) this.listings.get(this.channels[i].id);

    this.renderNowLine(now);
  }

  renderHead() {
    this.day.textContent = dayLabel(this.viewStart);
    const count = this.span / HALF_HOUR;
    while (this.timeEls.length > count) this.head.removeChild(this.timeEls.pop());
    while (this.timeEls.length < count) {
      const t = el('div', null, 'guide-time');
      this.head.appendChild(t);
      this.timeEls.push(t);
    }
    this.timeEls.forEach((t, i) => {
      t.style.left = `${this.channelW + i * HALF_HOUR * this.pxPerMs}px`;
      t.textContent = timeOf(this.viewStart + i * HALF_HOUR);
    });
  }

  renderRow(slot, ch, index, focusedBlock, now) {
    const dead = this.repo.deadChannels.has(ch.id);
    slot.root.className = `grow${index === this.row ? ' current' : ''}${dead ? ' dead' : ''}`;
    slot.num.textContent = this.repo.numbers.get(ch.id) || 0;
    if (!this.crt) setLogo(slot.logo, ch.logo);
    slot.name.textContent = (this.repo.favorites.has(ch.id) ? '★ ' : '') + ch.name;
    show(slot.dot, ch.id === this.tunedChannelId);

    const cells = slot.cells;
    cells.style.left = `${this.channelW}px`;
    while (cells.firstChild) cells.removeChild(cells.firstChild);
    const viewEnd = this.viewStart + this.span;
    const loaded = this.listings.get(ch.id) !== null;
    for (const b of this.blocks(index)) {
      if (b.stop <= this.viewStart) continue;
      if (b.start >= viewEnd) break;
      cells.appendChild(this.cell(b, loaded, focusedBlock, now));
    }
  }

  cell(b, loaded, focusedBlock, now) {
    const isFocus = focusedBlock !== null && b.start === focusedBlock.start;
    const p = b.programme;
    const classes = ['gc'];
    if (isFocus) classes.push(this.focus.has(this.root) ? 'focus' : 'sel');
    if (!p) classes.push('empty');
    else if (now >= b.start && now < b.stop) classes.push('now');
    if (this.crt && p) {
      const tint = crtTint(p.cat);
      if (tint) classes.push(tint);
    }
    if (b.start < this.viewStart) classes.push('continued');

    const left = (Math.max(b.start, this.viewStart) - this.viewStart) * this.pxPerMs;
    const right = (Math.min(b.stop, this.viewStart + this.span) - this.viewStart) * this.pxPerMs;
    const e = el('div', null, classes.join(' '));
    e.dataset.start = b.start;
    e.style.left = `${left + 2}px`;
    e.style.width = `${Math.max(right - left - 4, 1)}px`;
    if (right - left < 44) return e;
    add(e, el('div', null, this.crt ? 'gtitle' : 'gtitle ell', p ? p.title : (loaded ? 'No listings' : '…')));
    if (p) add(e, el('div', null, 'gsub ell', p.sub || `${timeOf(p.start)} – ${timeOf(p.stop)}`));
    return e;
  }

  renderNowLine(now) {
    if (now < this.viewStart || now > this.viewStart + this.span) return;
    const x = this.channelW + (now - this.viewStart) * this.pxPerMs;
    // Bright in the time header, faint over the grid so it doesn't cut through titles.
    show(this.line, true);
    this.line.style.left = `${x}px`;
    this.line.style.height = this.crt ? '' : `${this.headH}px`;
    show(this.lineLow, !this.crt);
    if (!this.crt) {
      this.lineLow.style.left = `${x - 2}px`;
      this.lineLow.style.height = `${Math.min(this.visibleRows, this.channels.length) * this.rowH}px`;
    }
  }
}
