// The source text of the pure-DSP worklets of the pinned superdough, cut out of packages/superdough/worklets.mjs and helpers.mjs by marker lines (so a
// different checkout fails loudly instead of running something else). Shared by dsp-oracle.mjs (which evaluates it in Node with the AudioWorklet globals
// mocked) and dsp-chromium-check.mjs (which loads it into a real AudioWorklet).
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.STRUDEL_DIR ?? path.join(here, 'strudel');
export const superdoughDir = path.join(root, 'packages/superdough');

const W = fs.readFileSync(path.join(superdoughDir, 'worklets.mjs'), 'utf8').split('\n');
const H = fs.readFileSync(path.join(superdoughDir, 'helpers.mjs'), 'utf8').split('\n');
const find = (lines, pred, from = 0) => {
  const i = lines.findIndex((l, j) => j >= from && pred(l));
  if (i < 0) throw new Error('marker not found: ' + pred);
  return i;
};
const slice = (lines, a, b) => lines.slice(a, b + 1).join('\n');

/** worklets.mjs:12-35: blockSize, PI, INVSR, the clamp/lerp/pv/ffloor/fround/fceil helpers and fast_tanh. */
export const prelude = slice(
  W,
  find(W, (l) => l.startsWith('const blockSize')),
  find(W, (l) => l === '};', find(W, (l) => l.startsWith('const fast_tanh'))),
);
/** coarse, crush, shape, the two-pole filter and djf, ladder and distort processors (worklets.mjs:193-463). */
export const effects = slice(
  W,
  find(W, (l) => l.startsWith('class CoarseProcessor')),
  find(W, (l) => l.includes("registerProcessor('distort-processor'")),
);
/** The phase vocoder (worklets.mjs:580-748); it needs OLAProcessor and FFT. */
export const vocoder = slice(
  W,
  find(W, (l) => l.startsWith('const BUFFERED_BLOCK_SIZE')),
  find(W, (l) => l.includes("registerProcessor('phase-vocoder-processor'")),
);
/** The transient processor (worklets.mjs:1417-1494). */
export const transient = slice(
  W,
  find(W, (l) => l.startsWith('class TransientProcessor')),
  find(W, (l) => l.includes("registerProcessor('transient-processor'")),
);
/** The distortion algorithms and getDistortionAlgorithm (helpers.mjs:509-599), `export` removed; they need `clamp` and `logger`. */
export const algorithms = slice(
  H,
  find(H, (l) => l.startsWith('const __squash')),
  find(H, (l) => l.startsWith('export const getDistortion = ')) - 1,
).replaceAll('export const', 'const');

const olaFile = path.join(superdoughDir, 'ola-processor.js');
const fftFile = path.join(superdoughDir, 'fft.js');
export { olaFile, fftFile };

/** One classic-script / module text with everything above and the two classes the vocoder extends, for loading into an AudioWorkletGlobalScope. */
export function workletModuleText() {
  const fft = fs.readFileSync(fftFile, 'utf8').replace('export default class FFT', 'class FFT');
  const ola = fs.readFileSync(olaFile, 'utf8').replace('export default OLAProcessor;', '');
  return ['const logger = () => {};', fft, ola, prelude, algorithms, effects, vocoder, transient].join('\n');
}
