// XHR rather than fetch: webOS 4's engine can't abort a fetch, which probing streams depends on.

const JSON_TIMEOUT = 120000;
const BYTES_TIMEOUT = 180000;
const PROBE_TIMEOUT = 10000;

/** A host that sends no CORS headers, so reaching it shows whether this app is subject to them. */
const NO_CORS_HOST = 'https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz';

/**
 * Whether requests to servers without CORS headers go through. LG's documents don't settle this for
 * packaged apps, so the app finds out rather than assuming.
 */
let crossOriginOpen = false;

export function isCrossOriginOpen() {
  return crossOriginOpen;
}

export function detectCrossOrigin() {
  return new Promise((resolve) => {
    const xhr = new XMLHttpRequest();
    const finish = (open) => {
      crossOriginOpen = open;
      resolve(open);
    };
    xhr.open('HEAD', NO_CORS_HOST);
    xhr.timeout = 8000;
    xhr.onload = () => finish(true);
    xhr.onerror = () => finish(false);
    xhr.ontimeout = () => finish(false);
    xhr.send();
  });
}

function request(url, type, timeout, headers) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('GET', url);
    xhr.responseType = type;
    xhr.timeout = timeout;
    if (headers) for (const k of Object.keys(headers)) xhr.setRequestHeader(k, headers[k]);
    xhr.onload = () => resolve(xhr);
    xhr.onerror = () => reject(new Error(`Couldn't reach ${url}`));
    xhr.ontimeout = () => reject(new Error(`Timed out on ${url}`));
    xhr.send();
  });
}

export async function getJson(url, accept) {
  const xhr = await request(url, 'text', JSON_TIMEOUT, accept ? { Accept: accept } : null);
  if (xhr.status === 404) {
    const err = new Error(`${url} returned HTTP 404`);
    err.notFound = true;
    throw err;
  }
  if (xhr.status < 200 || xhr.status > 299) throw new Error(`${url} returned HTTP ${xhr.status}`);
  return JSON.parse(xhr.responseText);
}

export async function getBytes(url, etag) {
  // Custom headers need a preflight, which servers with no CORS support would fail.
  const headers = etag && crossOriginOpen ? { 'If-None-Match': etag } : null;
  const xhr = await request(url, 'arraybuffer', BYTES_TIMEOUT, headers);
  return {
    status: xhr.status,
    // Under CORS this header can't be read, and asking logs an error. It wouldn't be sent back then anyway.
    etag: crossOriginOpen ? xhr.getResponseHeader('ETag') : null,
    bytes: xhr.status >= 200 && xhr.status <= 299 ? new Uint8Array(xhr.response) : null,
  };
}

/**
 * Starts loading a stream and stops as soon as its first bytes show what it is.
 * @returns { status, path, head }, with status 0 if the request failed.
 */
export function probe(url) {
  return new Promise((resolve) => {
    let xhr;
    let settled = false;
    const finish = (status, head) => {
      if (settled) return;
      settled = true;
      let path = '';
      try {
        path = new URL(xhr.responseURL || url).pathname;
      } catch (_) {
        // A malformed URL in the data; the status already says it failed.
      }
      resolve({ status, path, head });
      xhr.abort();
    };
    try {
      xhr = new XMLHttpRequest();
      xhr.open('GET', url);
      xhr.timeout = PROBE_TIMEOUT;
      xhr.onprogress = () => {
        if (xhr.responseText.length >= 16) finish(xhr.status, xhr.responseText.substring(0, 16));
      };
      xhr.onload = () => finish(xhr.status, xhr.responseText.substring(0, 16));
      xhr.onerror = () => finish(0, '');
      xhr.ontimeout = () => finish(0, '');
      xhr.send();
    } catch (_) {
      xhr = xhr || { abort() {} };
      finish(0, '');
    }
  });
}
