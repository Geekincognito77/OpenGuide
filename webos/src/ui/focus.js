export const KEY = {
  OK: 13, LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, CH_UP: 33, CH_DOWN: 34,
  BACK: 461, ESC: 27, BACKSPACE: 8,
  PLAY: 415, PAUSE: 19, STOP: 413, REWIND: 412, FAST_FORWARD: 417,
  RED: 403, GREEN: 404, YELLOW: 405, BLUE: 406, INFO: 457,
};

const HOLD_MS = 600;
const DIRS = { [KEY.LEFT]: 'left', [KEY.RIGHT]: 'right', [KEY.UP]: 'up', [KEY.DOWN]: 'down' };

/** Scrolls an element into its scrolling parent, without scrollIntoView's block option, which webOS 4 lacks. */
function ensureVisible(e) {
  for (let p = e.parentElement; p; p = p.parentElement) {
    if (p.classList.contains('scroll')) {
      const margin = 16;
      if (e.offsetTop - margin < p.scrollTop) p.scrollTop = e.offsetTop - margin;
      else if (e.offsetTop + e.offsetHeight + margin > p.scrollTop + p.clientHeight) {
        p.scrollTop = e.offsetTop + e.offsetHeight + margin - p.clientHeight;
      }
      return;
    }
  }
}

function visible(e) {
  return e.offsetParent !== null;
}

/**
 * D-pad focus, with the Magic Remote's pointer alongside it. Anything with class "f" can take focus.
 * Elements can define:
 * - ogActivate() and ogHold(), for OK and holding OK.
 * - ogKey(code, repeatCount), returning true if it dealt with the key. Guide and grid handle their own
 *   movement this way, and return false to let focus leave them.
 * - ogRect(), the part of it that focus should move from.
 * - ogPointer(target), when the pointer moves over it. ogWheel(direction), when the wheel turns.
 */
export class Focus {
  constructor(root) {
    this.root = root;
    this.scope = root;
    this.scopes = [];
    this.current = null;
    this.armed = null;
    this.holdTimer = null;
    this.mouseX = -1;
    this.mouseY = -1;
    this.heldByPointer = false;
    /** Runs when focus had nowhere to return to, so the app can put it somewhere sensible. */
    this.onLost = () => {};
    this.bindPointer();
  }

  set(e) {
    if (e === this.current) return;
    if (this.current) {
      this.current.classList.remove('focused');
      if (this.current.ogBlur) this.current.ogBlur();
    }
    this.current = e;
    this.disarm();
    if (e) {
      e.classList.add('focused');
      if (e.ogFocus) e.ogFocus();
      ensureVisible(e);
    }
  }

  has(e) {
    return this.current === e;
  }

  /** Confines focus to an overlay until popScope. */
  pushScope(root) {
    this.scopes.push({ scope: this.scope, focused: this.current });
    this.scope = root;
  }

  popScope() {
    const prev = this.scopes.pop();
    if (!prev) return;
    this.scope = prev.scope;
    if (prev.focused && document.contains(prev.focused) && visible(prev.focused)) {
      this.set(prev.focused);
    } else {
      this.set(null);
      this.onLost();
    }
  }

  /** Puts focus back on a row after a rebuild replaced it, if the same row still exists. */
  refocus(parent, id) {
    const rows = parent.querySelectorAll('.f');
    for (let i = 0; i < rows.length; i++) {
      if (rows[i].dataset.id === id) {
        this.set(rows[i]);
        return true;
      }
    }
    return false;
  }

  /** Finds the nearest thing in a direction, favouring what lines up over what's merely close. */
  move(dir) {
    const from = this.current;
    if (!from) return false;
    const r = from.ogRect ? from.ogRect() : from.getBoundingClientRect();
    const horizontal = dir === 'left' || dir === 'right';
    let best = null;
    let bestScore = Infinity;
    const all = this.scope.querySelectorAll('.f');
    for (let i = 0; i < all.length; i++) {
      const c = all[i];
      if (c === from || !visible(c)) continue;
      const b = c.getBoundingClientRect();
      let dist;
      if (dir === 'left') dist = r.left - b.right;
      else if (dir === 'right') dist = b.left - r.right;
      else if (dir === 'up') dist = r.top - b.bottom;
      else dist = b.top - r.bottom;
      if (dist < -2) continue;
      const off = horizontal
        ? Math.max(0, Math.max(b.top - r.bottom, r.top - b.bottom))
        : Math.max(0, Math.max(b.left - r.right, r.left - b.right));
      const score = Math.max(dist, 0) + off * 3;
      if (score < bestScore) {
        bestScore = score;
        best = c;
      }
    }
    if (best) this.set(best);
    return !!best;
  }

  /** The D-pad and OK, for whatever has focus. */
  handleKey(code, repeat) {
    const e = this.current;
    if (code === KEY.OK) {
      if (repeat === 0 && e) {
        this.arm(() => e.ogActivate && e.ogActivate(), () => e.ogHold && e.ogHold());
      }
      return;
    }
    if (e && e.ogKey && e.ogKey(code, repeat)) return;
    if (DIRS[code]) {
      if (!e) this.onLost();
      else this.move(DIRS[code]);
    }
  }

  /** Runs onShort if OK comes up quickly and onHold once it's been held. Timed here, as key repeat varies by remote. */
  arm(onShort, onHold) {
    this.disarm();
    this.armed = onShort;
    this.holdTimer = setTimeout(() => {
      this.armed = null;
      onHold();
    }, HOLD_MS);
  }

  disarm() {
    clearTimeout(this.holdTimer);
    this.armed = null;
  }

  /** A press that opened something must not act again on release, so only an armed press counts. */
  keyUp(code) {
    if (code !== KEY.OK || !this.armed) return;
    const run = this.armed;
    this.disarm();
    run();
  }

  bindPointer() {
    const target = (ev) => (ev.target.closest ? ev.target.closest('.f') : null);
    const inScope = (e) => e && this.scope.contains(e);

    // Only real movement counts: redrawing under a still pointer also fires mouse events.
    document.addEventListener('mousemove', (ev) => {
      if (ev.clientX === this.mouseX && ev.clientY === this.mouseY) return;
      this.mouseX = ev.clientX;
      this.mouseY = ev.clientY;
      const e = target(ev);
      if (!inScope(e)) return;
      this.set(e);
      if (e.ogPointer) e.ogPointer(ev.target);
    });

    document.addEventListener('mousedown', (ev) => {
      const e = target(ev);
      if (!inScope(e)) return;
      this.set(e);
      if (e.ogPointer) e.ogPointer(ev.target);
      this.heldByPointer = false;
      clearTimeout(this.holdTimer);
      this.holdTimer = setTimeout(() => {
        this.heldByPointer = true;
        if (e.ogHold) e.ogHold();
      }, HOLD_MS);
    });

    document.addEventListener('mouseup', () => clearTimeout(this.holdTimer));

    document.addEventListener('click', (ev) => {
      if (this.heldByPointer) {
        this.heldByPointer = false;
        return;
      }
      const e = target(ev);
      if (inScope(e) && e.ogActivate) e.ogActivate();
    });

    document.addEventListener('wheel', (ev) => {
      const e = target(ev);
      if (inScope(e) && e.ogWheel) e.ogWheel(ev.deltaY > 0 ? 1 : -1);
    });
  }
}
