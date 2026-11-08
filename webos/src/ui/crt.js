import { add, el, show, timeOf } from './dom.js';

const HEADER_H = 76;
const ROW_H = 156;
const SCROLL_PX_PER_SECOND = 56;

/**
 * The guide channel: what's on now and next, scrolling slowly up the screen for as long as you leave it on.
 * Only the rows on screen exist, moved each frame and refilled as they wrap round, because a TV can't
 * smoothly scroll hundreds of rows of text.
 */
export class GuideChannelView {
  constructor() {
    this.rows = [];
    this.scroll = 0;
    this.lastFrame = 0;
    this.frame = 0;
    this.clock = el('div', null, 'gch-clock');
    this.message = el('div', null, 'gch-message', 'Listings are still loading.');
    this.list = el('div', null, 'gch-list');
    // Enough to cover the screen while one row is half off each edge.
    this.slots = [];
    const count = Math.ceil((1080 - HEADER_H) / ROW_H) + 1;
    for (let i = 0; i < count; i++) {
      const slot = {
        root: el('div', null, 'gch-row'),
        name: el('div', null, 'gch-name ell'),
        now: el('div', null, 'gch-now ell'),
        next: el('div', null, 'gch-next ell'),
        index: -1,
      };
      add(slot.root, slot.name, slot.now, slot.next);
      this.list.appendChild(slot.root);
      this.slots.push(slot);
    }
    const head = add(el('div', null, 'gch-head'), el('div', null, 'gch-title', 'GUIDE CHANNEL'), this.clock);
    this.root = add(el('div', 'guide-channel', 'hidden'), head, this.list, this.message);
    this.step = (t) => this.draw(t);
  }

  get visible() {
    return !this.root.classList.contains('hidden');
  }

  setVisible(on) {
    if (on === this.visible) return;
    show(this.root, on);
    cancelAnimationFrame(this.frame);
    this.lastFrame = 0;
    if (on) this.frame = requestAnimationFrame(this.step);
  }

  setRows(rows) {
    this.rows = rows;
    for (const s of this.slots) s.index = -1;
    if (this.scroll >= rows.length * ROW_H) this.scroll = 0;
    if (this.visible && !this.frame) this.frame = requestAnimationFrame(this.step);
  }

  draw(t) {
    this.frame = 0;
    if (!this.visible) return;
    const n = this.rows.length;
    const total = n * ROW_H;
    const moving = total > 1080 - HEADER_H;
    if (moving) {
      if (this.lastFrame) this.scroll = (this.scroll + (t - this.lastFrame) * SCROLL_PX_PER_SECOND / 1000) % total;
      this.lastFrame = t;
    } else {
      this.scroll = 0;
    }
    this.clock.textContent = timeOf(Date.now());
    show(this.message, n === 0);
    // Whole pixels, because a line that's blended across two scan lines shimmers on a tube.
    const top = Math.floor(this.scroll);
    const first = Math.floor(top / ROW_H);
    const offset = top % ROW_H;
    this.slots.forEach((slot, k) => {
      const i = first + k;
      const on = n > 0 && (moving || i < n);
      slot.root.style.visibility = on ? '' : 'hidden';
      if (!on) return;
      const index = i % n;
      if (slot.index !== index) {
        slot.index = index;
        this.fill(slot, this.rows[index]);
        slot.root.classList.toggle('odd', index % 2 === 1);
      }
      slot.root.style.transform = `translate3d(0, ${k * ROW_H - offset}px, 0)`;
    });
    if (moving) this.frame = requestAnimationFrame(this.step);
  }

  fill(slot, row) {
    slot.name.textContent = `${row.number}  ${row.name}`;
    slot.now.textContent = `Now  ${row.now}`;
    slot.next.textContent = row.next ? `${row.nextAt ? timeOf(row.nextAt) : ''}  ${row.next}` : '';
  }
}

const NOISE_W = 192;
const NOISE_H = 144;
const FRAME_COUNT = 4;
const FRAME_MS = 50;
const SHORTEST_MS = 350;
const LONGEST_MS = 5000;

/**
 * Analog snow for a channel change, which also hides the wait for the new stream. A few coarse frames of
 * noise are made once and flicked between, scaled up without smoothing, so it costs next to nothing.
 */
export class StaticView {
  constructor() {
    this.root = el('canvas', 'static', 'hidden');
    this.root.width = NOISE_W;
    this.root.height = NOISE_H;
    this.ctx = this.root.getContext('2d');
    this.frames = null;
    this.frame = 0;
    this.earliestEnd = 0;
  }

  noiseFrame() {
    const img = this.ctx.createImageData(NOISE_W, NOISE_H);
    const px = img.data;
    for (let y = 0; y < NOISE_H; y++) {
      // Each line is a little brighter or darker than the last, as snow is on a real set.
      const band = Math.floor(Math.random() * 49) - 24;
      for (let x = 0; x < NOISE_W; x++) {
        const v = Math.max(0, Math.min(255, Math.floor(Math.random() * 256) + band));
        const o = (y * NOISE_W + x) * 4;
        px[o] = v;
        px[o + 1] = v;
        px[o + 2] = v;
        px[o + 3] = 255;
      }
    }
    return img;
  }

  draw() {
    if (!this.frames) this.frames = Array.from({ length: FRAME_COUNT }, () => this.noiseFrame());
    this.frame = (this.frame + 1 + Math.floor(Math.random() * (FRAME_COUNT - 1))) % FRAME_COUNT;
    this.ctx.putImageData(this.frames[this.frame], 0, 0);
  }

  /** Runs until finish(), but gives up after a while so a slow stream isn't hidden by snow forever. */
  start() {
    this.stopTimers();
    this.earliestEnd = Date.now() + SHORTEST_MS;
    this.draw();
    show(this.root, true);
    this.ticker = setInterval(() => this.draw(), FRAME_MS);
    this.stopTimer = setTimeout(() => this.cancel(), LONGEST_MS);
  }

  /** The picture is ready. Still runs for long enough to read as a burst rather than a glitch. */
  finish() {
    if (this.root.classList.contains('hidden')) return;
    clearTimeout(this.stopTimer);
    this.stopTimer = setTimeout(() => this.cancel(), Math.max(0, this.earliestEnd - Date.now()));
  }

  cancel() {
    this.stopTimers();
    show(this.root, false);
  }

  stopTimers() {
    clearInterval(this.ticker);
    clearTimeout(this.stopTimer);
  }
}
