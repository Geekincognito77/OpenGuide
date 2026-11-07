import { button } from './browser.js';
import { add, el, show } from './dom.js';

/** A centred list over a dimmed screen. Every choice in the app (region, category, source) uses it. */
export class Picker {
  constructor(focus) {
    this.focus = focus;
    this.root = el('div', 'picker', 'hidden');
    const panel = el('div', 'picker-panel');
    this.title = el('div', 'picker-title', 'ell');
    this.list = el('div', 'picker-list', 'scroll');
    add(panel, this.title, this.list);
    this.root.appendChild(panel);
    this.isShowing = false;
  }

  /** @param focusId the row to start on, when that shouldn't be the one that's ticked. */
  show(heading, items, selected, focusId, onPick) {
    this.title.textContent = heading;
    while (this.list.firstChild) this.list.removeChild(this.list.firstChild);
    const start = focusId === undefined || focusId === null ? selected : focusId;
    let startRow = null;
    for (const item of items) {
      const row = button(null, (item.id === selected ? '✓  ' : '') + item.label, () => {
        this.hide();
        onPick(item);
      });
      this.list.appendChild(row);
      if (item.id === start) startRow = row;
    }
    show(this.root, true);
    this.isShowing = true;
    this.focus.pushScope(this.root);
    this.focus.set(startRow || this.list.firstChild);
  }

  hide() {
    show(this.root, false);
    this.isShowing = false;
    this.focus.popScope();
  }
}
