import { CATEGORY_ALL, CATEGORY_CRT, CATEGORY_FAVORITES } from '../data/logic.js';
import { add, el, progressBar, setLogo, setProgress, show } from './dom.js';
import { KEY } from './focus.js';

const CARD_H = 148;

export function button(id, text, onActivate) {
  const b = el('div', id, 'btn f');
  const span = el('span', null, null, text);
  b.appendChild(span);
  b.setText = (t) => { span.textContent = t; };
  b.ogActivate = onActivate;
  return b;
}

/** Channel browser: categories and search on the left, a grid of channels on the right. */
export class BrowserScreen {
  constructor(repo, focus, gridHeight, actions) {
    this.repo = repo;
    this.focus = focus;
    this.gridHeight = gridHeight;
    this.actions = actions;
    this.crt = false;
    this.category = CATEGORY_ALL;
    this.channels = [];
    this.index = 0;
    this.top = 0;
    this.cards = [];

    this.root = el('div', 'browser', 'screen');
    const left = el('div', 'browser-left');
    this.search = el('input', 'search', 'f');
    this.search.type = 'text';
    this.search.placeholder = 'Search channels';
    this.search.setAttribute('autocomplete', 'off');
    this.search.setAttribute('spellcheck', 'false');
    this.search.ogActivate = () => this.search.focus();
    this.search.addEventListener('input', () => this.reload());
    this.regionButton = button(null, '', () => actions.pickRegion());
    this.languageButton = button(null, '', () => actions.pickLanguage());
    this.categoryList = el('div', 'categories', 'scroll');
    add(left, this.search, this.regionButton, this.languageButton, this.categoryList);

    const right = el('div', 'browser-right');
    this.count = el('div', 'count');
    this.grid = el('div', 'grid', 'f');
    add(right, this.count, this.grid);
    add(this.root, left, right);

    this.grid.ogKey = (code) => this.onGridKey(code);
    this.grid.ogActivate = () => {
      const ch = this.channels[this.index];
      if (ch) actions.onTune(ch, this.channels);
    };
    this.grid.ogHold = () => {
      const ch = this.channels[this.index];
      if (ch) actions.onOptions(ch, this.channels);
    };
    this.grid.ogFocus = () => this.renderGrid();
    this.grid.ogBlur = () => this.renderGrid();
    this.grid.ogRect = () => {
      const card = this.cards[this.index - this.top * this.cols];
      return (card ? card.root : this.grid).getBoundingClientRect();
    };
    this.grid.ogPointer = (target) => {
      const card = target.closest('.card');
      if (card) this.setIndex(this.top * this.cols + Number(card.dataset.slot));
    };
    this.grid.ogWheel = (direction) => this.setIndex(this.index + direction * this.cols);
  }

  setCrt(crt) {
    this.crt = crt;
    this.layoutCards();
  }

  get cols() { return this.crt ? 2 : 3; }
  get rows() { return Math.max(1, Math.floor(this.gridHeight(this.crt) / CARD_H)); }

  layoutCards() {
    const count = this.cols * this.rows;
    while (this.cards.length > count) this.grid.removeChild(this.cards.pop().root);
    while (this.cards.length < count) {
      const root = el('div', null, 'card');
      const logo = el('img');
      const text = el('div', null, 'card-text');
      const name = el('div', null, 'card-name ell');
      const now = el('div', null, 'card-now ell');
      const progress = progressBar();
      add(text, name, now, progress);
      add(root, logo, text);
      this.grid.appendChild(root);
      this.cards.push({ root, logo, name, now, progress });
    }
    const width = (1920 - (this.crt ? 24 : 108) - 424) / this.cols;
    this.cards.forEach((c, i) => {
      c.root.dataset.slot = i;
      c.root.style.left = `${(i % this.cols) * width + 6}px`;
      c.root.style.top = `${Math.floor(i / this.cols) * CARD_H + 6}px`;
      c.root.style.width = `${width - 12}px`;
    });
    this.renderGrid();
  }

  focusFirst() {
    this.focus.set(this.channels.length ? this.grid : this.regionButton);
  }

  refresh() {
    const { repo } = this;
    if (!repo.catalog) return;
    this.regionButton.setText(this.actions.regionLabel());
    this.languageButton.setText(this.actions.languageLabel());
    const lineup = repo.filtered(CATEGORY_ALL); // what each row will actually show
    const counts = new Map();
    for (const ch of lineup) for (const c of ch.categories) counts.set(c, (counts.get(c) || 0) + 1);
    const rows = [
      { id: CATEGORY_ALL, label: `All channels (${lineup.length})` },
      { id: CATEGORY_FAVORITES, label: `★ Favorites (${repo.favorites.size})` },
      { id: CATEGORY_CRT, label: `CRT list (${repo.crtList.size})` },
    ];
    for (const c of repo.catalog.categories) {
      if (counts.get(c.id)) rows.push({ id: c.id, label: `${c.name} (${counts.get(c.id)})` });
    }
    this.renderCategories(rows);
    this.reload();
  }

  renderCategories(rows) {
    const list = this.categoryList;
    const focused = this.focus.current;
    const focusedId = focused && focused.parentElement === list ? focused.dataset.id : null;
    const scroll = list.scrollTop;
    while (list.firstChild) list.removeChild(list.firstChild);
    for (const item of rows) {
      const b = button(null, item.label, () => {
        this.category = item.id;
        this.refresh();
      });
      b.dataset.id = item.id;
      if (item.id === this.category) b.classList.add('selected');
      list.appendChild(b);
    }
    list.scrollTop = scroll;
    if (focusedId !== null) {
      this.focus.set(null);
      if (!this.focus.refocus(list, focusedId)) this.focus.set(this.regionButton);
    }
  }

  reload() {
    const next = this.repo.filtered(this.category, this.search.value);
    const same = next.length === this.channels.length && next.every((c, i) => c === this.channels[i]);
    if (!same) {
      this.channels = next;
      this.index = 0;
      this.top = 0;
      this.count.textContent = `${next.length} channels`;
    }
    this.renderGrid();
  }

  /** Redraws what's on now, in place, so a timer never disturbs where you are. */
  renderGrid() {
    const now = Date.now();
    const start = this.top * this.cols;
    const { repo } = this;
    const hasFocus = this.focus.has(this.grid);
    this.cards.forEach((c, i) => {
      const ch = this.channels[start + i];
      show(c.root, !!ch);
      if (!ch) return;
      const dead = repo.deadChannels.has(ch.id);
      c.root.className = `card${start + i === this.index && hasFocus ? ' current' : ''}${dead ? ' dead' : ''}`;
      const number = repo.numbers.has(ch.id) ? repo.numbers.get(ch.id) : '';
      c.name.textContent = `${repo.favorites.has(ch.id) ? '★ ' : ''}${number}  ${ch.name}`;
      setLogo(c.logo, ch.logo);
      const p = repo.airing.get(ch.id);
      c.now.textContent = p ? p.title : (dead ? 'Unavailable right now' : 'No listings');
      c.progress.style.visibility = p ? 'visible' : 'hidden';
      if (p) setProgress(c.progress, (now - p.start) / Math.max(p.stop - p.start, 1));
    });
  }

  setIndex(next) {
    const clamped = Math.max(0, Math.min(next, this.channels.length - 1));
    if (clamped === this.index || clamped < 0) return;
    this.index = clamped;
    const row = Math.floor(clamped / this.cols);
    if (row < this.top) this.top = row;
    if (row >= this.top + this.rows) this.top = row - this.rows + 1;
    this.renderGrid();
  }

  onGridKey(code) {
    const { cols, index } = this;
    switch (code) {
      case KEY.LEFT:
        if (index % cols === 0) return false;
        this.setIndex(index - 1);
        return true;
      case KEY.RIGHT:
        if (index % cols === cols - 1 || index === this.channels.length - 1) return true;
        this.setIndex(index + 1);
        return true;
      case KEY.UP:
        if (index < cols) return false;
        this.setIndex(index - cols);
        return true;
      case KEY.DOWN:
        // Onto the last row even if it's short, as long as there is one below.
        if (Math.floor(index / cols) < Math.floor((this.channels.length - 1) / cols)) this.setIndex(index + cols);
        return true;
      case KEY.CH_DOWN: case KEY.FAST_FORWARD:
        this.setIndex(index + cols * this.rows);
        return true;
      case KEY.CH_UP: case KEY.REWIND:
        this.setIndex(index - cols * this.rows);
        return true;
      case KEY.RED: {
        const ch = this.channels[index];
        if (ch) {
          this.actions.onToggleFavorite(ch);
          this.renderGrid();
        }
        return true;
      }
      default:
        return false;
    }
  }
}
