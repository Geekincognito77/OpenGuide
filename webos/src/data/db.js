/**
 * IndexedDB, where the Android app has SQLite and files.
 * - kv: the catalog and the listing-matching rows.
 * - listings: one record per channel with its full programmes, read only when a channel comes on screen.
 * - times: the same programmes reduced to times and titles, small enough to keep in memory for working
 *   out what's on across every channel.
 */
const NAME = 'openguide';

function done(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function finished(tx) {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

function eachByCursor(req, visit) {
  req.onsuccess = () => {
    const cursor = req.result;
    if (cursor) {
      visit(cursor);
      cursor.continue();
    }
  };
}

function summarize(rows) {
  return rows.map((r) => [r.start, r.stop, r.title]);
}

export class Db {
  static open() {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open(NAME, 1);
      req.onupgradeneeded = () => {
        const d = req.result;
        d.createObjectStore('kv');
        d.createObjectStore('listings', { keyPath: 'channel' }).createIndex('source', 'source');
        d.createObjectStore('times', { keyPath: 'channel' }).createIndex('source', 'source');
      };
      req.onsuccess = () => resolve(new Db(req.result));
      req.onerror = () => reject(req.error);
    });
  }

  constructor(d) {
    this.d = d;
  }

  getKv(key) {
    return done(this.d.transaction('kv').objectStore('kv').get(key));
  }

  /** Written together, so a failure can't leave a catalog without what its listings need. */
  setKvs(values) {
    const tx = this.d.transaction('kv', 'readwrite');
    for (const key of Object.keys(values)) tx.objectStore('kv').put(values[key], key);
    return finished(tx);
  }

  async forChannel(channel, from, to) {
    const rec = await done(this.d.transaction('listings').objectStore('listings').get(channel));
    return rec ? rec.rows.filter((r) => r.stop > from && r.start < to) : [];
  }

  /** @returns Map of channel to { source, rows: [[start, stop, title]] }. */
  async loadTimes() {
    const all = await done(this.d.transaction('times').objectStore('times').getAll());
    const out = new Map();
    for (const rec of all) out.set(rec.channel, { source: rec.source, rows: rec.rows });
    return out;
  }

  /** Swaps a feed's programmes in one transaction, so the guide never sees it half updated. */
  replaceSource(source, byChannel) {
    const tx = this.d.transaction(['listings', 'times'], 'readwrite');
    for (const name of ['listings', 'times']) {
      const store = tx.objectStore(name);
      eachByCursor(store.index('source').openKeyCursor(IDBKeyRange.only(source)), (c) => store.delete(c.primaryKey));
    }
    // After the deletes in request order, which is the order IndexedDB runs them.
    const writes = tx.objectStore('times').count();
    writes.onsuccess = () => {
      for (const [channel, rows] of byChannel) {
        tx.objectStore('listings').put({ channel, source, rows });
        tx.objectStore('times').put({ channel, source, rows: summarize(rows) });
      }
    };
    return finished(tx);
  }

  prune(before) {
    const tx = this.d.transaction(['listings', 'times'], 'readwrite');
    eachByCursor(tx.objectStore('listings').openCursor(), (c) => {
      const rows = c.value.rows;
      if (rows.length && rows[0].stop >= before) return;
      const kept = rows.filter((r) => r.stop >= before);
      if (kept.length === rows.length) return;
      const channel = c.value.channel;
      if (kept.length) {
        c.update({ channel, source: c.value.source, rows: kept });
        tx.objectStore('times').put({ channel, source: c.value.source, rows: summarize(kept) });
      } else {
        c.delete();
        tx.objectStore('times').delete(channel);
      }
    });
    return finished(tx);
  }

  clearListings() {
    const tx = this.d.transaction(['listings', 'times'], 'readwrite');
    tx.objectStore('listings').clear();
    tx.objectStore('times').clear();
    return finished(tx);
  }
}
