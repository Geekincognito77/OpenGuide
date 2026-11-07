// ares-package names the file after the app ID and version; releases use one fixed name.
import { readdirSync, renameSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const built = readdirSync(root).find((f) => f.startsWith('com.openguide.tv_') && f.endsWith('.ipk'));
if (!built) throw new Error('ares-package produced no IPK');
const target = join(root, 'OpenGuide.ipk');
rmSync(target, { force: true });
renameSync(join(root, built), target);
console.log(`Packaged ${target}`);
