// Bundles the app into dist/, the folder ares-package turns into an IPK.
import { build } from 'esbuild';
import { copyFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(root, 'dist');
const pkg = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));

rmSync(dist, { recursive: true, force: true });
mkdirSync(dist, { recursive: true });

await build({
  entryPoints: [join(root, 'src/main.js')],
  outfile: join(dist, 'app.js'),
  bundle: true,
  minify: true,
  // A classic script, because webOS blocks module scripts under its file:// origin.
  format: 'iife',
  // webOS 4, the oldest version supported, runs Chromium 53.
  target: 'chrome53',
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
    __UPDATE_API__: JSON.stringify(process.env.UPDATE_API || 'https://api.github.com/repos/Zapiltar-E7/OpenGuide/releases/latest'),
  },
});

const appinfo = JSON.parse(readFileSync(join(root, 'appinfo.json'), 'utf8'));
appinfo.version = pkg.version;
writeFileSync(join(dist, 'appinfo.json'), JSON.stringify(appinfo, null, 2));

copyFileSync(join(root, 'src/index.html'), join(dist, 'index.html'));
copyFileSync(join(root, 'src/styles.css'), join(dist, 'styles.css'));
copyFileSync(join(root, 'assets/icon.png'), join(dist, 'icon.png'));
copyFileSync(join(root, 'assets/largeIcon.png'), join(dist, 'largeIcon.png'));
// Kept out of the bundle: only some playback needs it, and parsing it on a TV isn't free.
copyFileSync(join(root, 'node_modules/hls.js/dist/hls.min.js'), join(dist, 'hls.min.js'));

console.log(`Built OpenGuide ${pkg.version} in ${dist}`);
