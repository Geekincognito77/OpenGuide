import { isNewerVersion } from './logic.js';

const ASSET = 'OpenGuide.ipk';

/**
 * Picks the release to offer. A webOS app can't install itself, so this only tells the viewer a new
 * version exists.
 * @returns the version if the release has a webOS build newer than current, otherwise null.
 */
export function newerRelease(release, current) {
  const hasBuild = (release.assets || []).some((a) => a.name === ASSET);
  return hasBuild && release.tag_name && isNewerVersion(release.tag_name, current) ? release.tag_name : null;
}

/** @returns the newer version's tag, or null if there is none or nothing has been released yet. */
export async function latestIfNewer(getJson, api, current) {
  try {
    return newerRelease(await getJson(api, 'application/vnd.github+json'), current);
  } catch (e) {
    if (e.notFound) return null;
    throw e;
  }
}
