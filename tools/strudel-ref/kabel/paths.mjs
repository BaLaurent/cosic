// Shared path resolution for the kabelsalat oracle (imported by the loader hook and by render-golden.mjs).
// Looks in the pinned Strudel checkout's pnpm store first (tools/strudel-ref/strudel, or $STRUDEL_DIR), then in
// tools/strudel-ref/kabel/vendor/{core,lib}/package (filled by fetch-kabelsalat.sh when the store is missing).
// KABEL_CORE=dist maps '@kabelsalat/core' to the bundled dist/index.mjs instead of src/index.js (cross-check only).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const here = path.dirname(fileURLToPath(import.meta.url));
export const strudelDir = fs.realpathSync(process.env.STRUDEL_DIR ?? path.join(here, '..', 'strudel'));

const pnpm = (name, ver) => path.join(strudelDir, 'node_modules/.pnpm', `@kabelsalat+${name}@${ver}`, 'node_modules/@kabelsalat', name);
const vendor = (name) => path.join(here, 'vendor', name, 'package');
const pick = (name, ver) => {
  for (const d of [pnpm(name, ver), vendor(name)]) if (fs.existsSync(path.join(d, 'package.json'))) return fs.realpathSync(d);
  throw new Error(`@kabelsalat/${name}@${ver} not found in the pnpm store nor in kabel/vendor: run fetch-kabelsalat.sh`);
};

export const coreDir = pick('core', '0.4.0');
export const libDir = pick('lib', '0.4.1');
export const coreEntry = path.join(coreDir, process.env.KABEL_CORE === 'dist' ? 'dist/index.mjs' : 'src/index.js');
export const url = (p) => pathToFileURL(p).href;
export const coreEntryUrl = url(coreEntry);
