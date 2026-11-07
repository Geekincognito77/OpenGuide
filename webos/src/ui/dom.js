/** Makes an element. Text always goes in as text, since names and titles come from public feeds. */
export function el(tag, id, cls, text) {
  const e = document.createElement(tag);
  if (id) e.id = id;
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

export function add(parent, ...children) {
  for (const c of children) parent.appendChild(c);
  return parent;
}

export function show(e, visible) {
  e.classList.toggle('hidden', !visible);
}

export function setProgress(bar, fraction) {
  bar.firstChild.style.width = `${Math.max(0, Math.min(1, fraction)) * 100}%`;
}

export function progressBar(id) {
  return add(el('div', id, 'progress'), el('div'));
}

/** Sets a logo, leaving the image alone if unchanged so it doesn't flicker when its row is redrawn. */
export function setLogo(img, url) {
  const next = url || '';
  if (img.dataset.src === next) return;
  img.dataset.src = next;
  img.style.visibility = 'hidden';
  if (next) {
    img.onload = () => { img.style.visibility = ''; };
    img.onerror = () => { img.style.visibility = 'hidden'; };
    img.src = next;
  } else {
    img.removeAttribute('src');
  }
}

const TIME = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' });
const DAY = new Intl.DateTimeFormat(undefined, { weekday: 'short' });
const CLOCK_DAY = new Intl.DateTimeFormat(undefined, { weekday: 'short', month: 'short', day: 'numeric' });
const STAMP = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });

export const timeOf = (t) => TIME.format(new Date(t));
export const dayOf = (t) => DAY.format(new Date(t));
export const clockDay = (t) => CLOCK_DAY.format(new Date(t));
export const stampOf = (t) => STAMP.format(new Date(t));

export function dayLabel(t) {
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  const days = Math.floor((t - start.getTime()) / 86400000);
  if (days <= 0) return 'Today';
  return days === 1 ? 'Tomorrow' : dayOf(t);
}

export function durationLeft(ms) {
  const mins = Math.ceil(ms / 60000);
  return mins >= 60 ? `${Math.floor(mins / 60)}h ${mins % 60}m left` : `${mins} min left`;
}
