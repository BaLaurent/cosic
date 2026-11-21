// Shared realm of the kabelsalat oracle: loads the REAL @kabelsalat/core + lib (via the loader hook), reproduces SalatRepl.evaluate,
// the superdough substitution chain, and instantiates the REAL `GenericProcessor` class text from the pinned Strudel checkout.
// Not meant to be run directly: imported by render-golden.mjs and graph-vectors.mjs (run those with `--import ./register-loader.mjs`).
import fs from 'node:fs';
import path from 'node:path';
import { coreDir, libDir, strudelDir, url, coreEntryUrl } from './paths.mjs';

export const BLOCK = 128;

export const core = await import(coreEntryUrl);
export const compiler = await import(url(path.join(coreDir, 'src/compiler.js')));
export const lib = await import(url(path.join(libDir, 'src/lib.js')));

// KW/repl.js:57-79 with localScope: true (evaluate(code) = core.evaluate(code, scope)); `audio`, `addUgen`, `repl` are web-only.
export const evaluateKabel = (code) => core.evaluate(code, { ...core, ...lib, ...compiler, audio: {}, addUgen() {}, repl: {} });
// superdough.mjs:453-459 compileKabel
export const compileKabel = (code) => evaluateKabel(code).compile({ log: false });

// The real getFrequencyFromValue (helpers.mjs:605-617) imports fine in plain Node.
export const { getFrequencyFromValue } = await import(url(path.join(strudelDir, 'packages/superdough/helpers.mjs')));

// superdough.mjs:640-643, verbatim chain (fx.workletSrc / fx.workletInputs / chainID / value are the hap's fields)
export const substitute = (workletSrc, workletInputs, value, chainId) =>
  workletSrc
    .replace(/\bpat\[(\d+)\]/g, (_, i) => workletInputs[i])
    .replaceAll('sFreq', getFrequencyFromValue(value))
    .replaceAll('sGate', `cc('strudel-gate-${chainId}')`);

// mulberry32 (spec 4.4)
export const mulberry32 = (s) => () => {
  s |= 0; s = (s + 0x6d2b79f5) | 0;
  let t = Math.imul(s ^ (s >>> 15), 1 | s);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};

// GenericProcessor verbatim from the pinned file (superdough/worklets.mjs:1496-1572)
const w = fs.readFileSync(path.join(strudelDir, 'packages/superdough/worklets.mjs'), 'utf8');
const a = w.indexOf('class GenericProcessor extends AudioWorkletProcessor');
const b = w.indexOf("registerProcessor('generic-processor', GenericProcessor);");
if (a < 0 || b < a) throw new Error('GenericProcessor not found in worklets.mjs');
export const genericProcessorSrc = w.slice(a, b);

class StubProcessor { constructor() { this.port = { onmessage: null, postMessage() {} }; } }

// Worklet globals. `currentTime` is driven by setCurrentTime(); `sampleRate` by setSampleRate().
let ct = 0;
Object.defineProperty(globalThis, 'currentTime', { get: () => ct, configurable: true });
export const setCurrentTime = (t) => { ct = t; };
export const setSampleRate = (sr) => { globalThis.sampleRate = sr; };

/** Fresh ugens.js module instance (own NOISE_CHANNELS and delays Map) and a GenericProcessor class bound to it. */
export async function newProcessorClass(tag) {
  const ugensMod = await import(url(path.join(libDir, 'src/ugens.js')) + `?case=${encodeURIComponent(tag)}`);
  return new Function('AudioWorkletProcessor', 'UGENS', 'blockSize', genericProcessorSrc + '\nreturn GenericProcessor;')(
    StubProcessor, new Map(Object.entries(ugensMod)), BLOCK);
}

/** JSON-safe ugen inputs: undefined -> null, non-finite numbers -> 'NaN' / 'Infinity' / '-Infinity' strings. */
export const jsonInput = (x) => (x === undefined ? null : typeof x === 'number' && !Number.isFinite(x) ? String(x) : x);
export const ugenInputs = (ugens) => ugens.map((u) => u.inputs.map(jsonInput));

/** Silence console.log / console.warn while fn runs; returns {result|error, logs}. */
export function captureConsole(fn) {
  const logs = [];
  const keep = { log: console.log, warn: console.warn, error: console.error };
  const fmt = (args) => args.map((x) => (typeof x === 'object' && x !== null ? JSON.stringify(x) : String(x))).join(' ');
  console.log = (...args) => logs.push('log: ' + fmt(args));
  console.warn = (...args) => logs.push('warn: ' + fmt(args));
  console.error = (...args) => logs.push('error: ' + fmt(args));
  try { return { result: fn(), error: null, logs }; }
  catch (e) { return { result: undefined, error: e, logs }; }
  finally { Object.assign(console, keep); }
}
