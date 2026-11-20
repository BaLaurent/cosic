// Runs the pure-DSP worklets of the pinned superdough in Node (the real source text, with the AudioWorklet globals mocked) and writes the
// input and output of each run as golden vectors for the Kotlin kernels of cosic.audio.dsp (Task 3.3):
//
//   node tools/strudel-ref/dsp-oracle.mjs        # writes engine/src/test/resources/dsp/{ladder,distort,shapecrushcoarse,djf,transient,phasevocoder}.json
//
// STRUDEL_DIR (default tools/strudel-ref/strudel next to this file) is the checkout made by fetch.sh; DSP_OUT overrides the output directory. The worklet sources are cut
// out of the checkout by worklet-sources.mjs; dsp-chromium-check.mjs runs the same text in a real AudioWorklet to confirm that this mock behaves like the browser.
// What runs, with its source lines at 84ef32eec8074f90c6690ab8d6a6c204f508b182:
//   ladder-processor, distort-processor, shape/crush/coarse-processor, djf-processor   packages/superdough/worklets.mjs:193-463
//   the distortion algorithms                                                            packages/superdough/helpers.mjs:509-599
//   phase-vocoder-processor + OLAProcessor + fft.js                                      worklets.mjs:580-748, ola-processor.js, fft.js
//   transient-processor                                                                  worklets.mjs:1417-1494
// Every worklet sees what a render quantum gives it: 128 frames, `currentTime` = quantum start, AudioParams as Float32Arrays of length 1 (constant over the
// quantum) or 128 (moving). The vowel filter is not a worklet: tools/strudel-ref/vowel-oracle.mjs runs it in headless Chromium.
import fs from 'fs';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';
import { algorithms, effects, prelude, superdoughDir, transient, vocoder } from './worklet-sources.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const outDir = process.env.DSP_OUT ?? path.join(here, '../../engine/src/test/resources/dsp');
const COMMIT = '84ef32eec8074f90c6690ab8d6a6c204f508b182';
const SR = 48000;
const Q = 128;

const sd = superdoughDir;

const logged = [];
globalThis.currentTime = 0;
globalThis.AudioWorkletProcessor = class {};
globalThis.logger = (m) => logged.push(String(m));
globalThis.OLAProcessor = (await import(pathToFileURL(path.join(sd, 'ola-processor.js')).href)).default;
globalThis.FFT = (await import(pathToFileURL(path.join(sd, 'fft.js')).href)).default;

/**
 * Evaluates the worklet sources for an audio context of rate `sr` (the module reads `sampleRate` once, for `INVSR`) and returns its processors by name.
 * Indirect eval: one script scope for the prelude, the algorithms and every processor, exactly as they sit in the module.
 */
function loadWorklets(sr) {
  const processors = {};
  globalThis.sampleRate = sr;
  globalThis.registerProcessor = (name, cls) => {
    processors[name] = cls;
  };
  (0, eval)([prelude, algorithms, effects, vocoder, transient, 'globalThis.__distortionAlgorithms = distortionAlgorithms; globalThis.__algoNames = _algoNames;'].join('\n'));
  return processors;
}
const registry = loadWorklets(SR);
const registry44 = loadWorklets(44100);

// ------------------------------------------------------------------------------------------------------------------ helpers
const f32 = (x) => {
  const f = Math.fround(x);
  if (Number.isNaN(f) || !Number.isFinite(f)) throw new Error('a golden value is not finite: ' + x);
  return Number(f.toPrecision(9));
};
const arr = (a) => Array.from(a, f32);
/** A float array as base64 of its little-endian float32 bytes: exact, and half the size of decimal text. */
const b64 = (a) => {
  const f = Float32Array.from(a);
  for (const v of f) if (!Number.isFinite(v)) throw new Error('a golden value is not finite');
  return Buffer.from(f.buffer).toString('base64');
};

/** Exact 32-bit LCG noise in [-amp, amp): the same few integer operations in Kotlin reproduce it, though the goldens store their inputs anyway. */
function lcg(seed, n, amp) {
  const out = new Float32Array(n);
  let x = seed >>> 0;
  for (let i = 0; i < n; i++) {
    x = (x * 1664525 + 1013904223) % 4294967296;
    out[i] = (x / 2147483648 - 1) * amp;
  }
  return out;
}
const sine = (freq, amp, n, phase = 0) => Float32Array.from({ length: n }, (_, i) => amp * Math.sin(2 * Math.PI * freq * (i / SR) + phase));

/**
 * Runs one processor for `quanta` render quanta. `params(q)` gives the AudioParam values of quantum q: a number (constant over the quantum) or a
 * Float32Array of 128 (moving). Stops when the processor returns false, as the engine does; later quanta stay silent.
 */
function run(name, { options = {}, input, quanta, params = () => ({}), processors = registry, rate = SR }) {
  globalThis.sampleRate = rate; // the djf's TwoPoleFilter reads the global when it runs, not once at load
  const processor = new processors[name](options);
  const out = input.map(() => new Float32Array(quanta * Q));
  for (let q = 0; q < quanta; q++) {
    globalThis.currentTime = (q * Q) / rate;
    const inputs = [input.map((c) => c.slice(q * Q, q * Q + Q))];
    const outputs = [input.map(() => new Float32Array(Q))];
    const p = {};
    for (const [k, v] of Object.entries(params(q))) p[k] = v instanceof Float32Array ? v : Float32Array.of(v);
    const alive = processor.process(inputs, outputs, p);
    outputs[0].forEach((c, ch) => out[ch].set(c, q * Q));
    if (alive === false) break;
  }
  return out;
}

/** The AudioParam values as the processor saw them (Float32), for the Kotlin test to pass back. */
const seen = (v) => (v instanceof Float32Array ? arr(v) : f32(v));
const record = (name, extra, input, quanta, perQuantum, output) => ({
  name,
  ...extra,
  channels: input.length,
  quanta,
  params: Array.from({ length: quanta }, (_, q) => Object.fromEntries(Object.entries(perQuantum(q)).map(([k, v]) => [k, seen(v)]))),
  input: input.map(b64),
  output: output.map(b64),
});
function write(file, doc) {
  fs.mkdirSync(outDir, { recursive: true });
  const body = {
    generator: 'tools/strudel-ref/dsp-oracle.mjs',
    commit: COMMIT,
    sampleRate: SR,
    ...doc,
  };
  fs.writeFileSync(path.join(outDir, file), JSON.stringify(body) + '\n');
  console.log(file, fs.statSync(path.join(outDir, file)).size, 'bytes');
}
const pick = (table, q) => table[q % table.length];

// ------------------------------------------------------------------------------------------------------------------ ladder
{
  const cases = [];
  const make = (name, input, quanta, perQuantum, extra = {}) =>
    cases.push(record(name, extra, input, quanta, perQuantum, run('ladder-processor', { input, quanta, params: perQuantum })));
  make('noiseDefaults', [lcg(1, 8 * Q, 0.8)], 8, () => ({ frequency: 1000, q: 10, drive: 0.69 }));
  make(
    'stereoSweep',
    [lcg(2, 8 * Q, 0.5), lcg(3, 8 * Q, 0.5)],
    8,
    (q) => ({
      frequency: [200, 400, 800, 1600, 3200, 6400, 12800, 30000][q],
      q: [0, 2, 5, 10, 20, 30, 1, 0][q],
      drive: [0, 0.69, 1, 2, 1, 0.5, -3, 0.2][q],
    }),
  );
  make('dcStep', [Float32Array.from({ length: 8 * Q }, (_, i) => (i < 2 * Q ? 0 : 0.1))], 8, () => ({ frequency: 1000, q: 0, drive: 0 }));
  make('driveOne', [Float32Array.from({ length: 8 * Q }, (_, i) => (i < 2 * Q ? 0 : 0.1))], 8, () => ({ frequency: 1000, q: 0, drive: 1 }));
  make('resonantDc', [Float32Array.from({ length: 12 * Q }, (_, i) => (i < 2 * Q ? 0 : 0.1))], 12, () => ({ frequency: 1000, q: 10, drive: 0 }));
  make('nearSelfOscillation', [lcg(4, 10 * Q, 0.05)], 10, () => ({ frequency: 3000, q: 40, drive: 0.69 }));
  {
    const input = [lcg(41, 6 * Q, 0.7)];
    const perQuantum = () => ({ frequency: 2500, q: 8, drive: 0.69 });
    cases.push(record('noise at 44.1 kHz', { sampleRate: 44100 }, input, 6, perQuantum, run('ladder-processor', { input, quanta: 6, params: perQuantum, processors: registry44, rate: 44100 })));
  }
  write('ladder.json', { source: 'packages/superdough/worklets.mjs:365-427', cases });
}

// ------------------------------------------------------------------------------------------------------------------ distort
{
  const names = globalThis.__algoNames;
  const algorithms = globalThis.__distortionAlgorithms;
  const xs = [];
  for (let i = -15; i <= 15; i++) xs.push(i / 10);
  xs.push(0.05, -0.05, 0.123456, -0.7071, 0.999, -0.999);
  const distortValues = [0, 0.3, 1, 2.5, 4];
  const grid = [];
  for (const name of names) {
    for (const d of distortValues) {
      const k = Math.expm1(d);
      grid.push({ name, distort: d, k, y: xs.map((x) => algorithms[name](x, k)) });
    }
  }
  const input = [lcg(5, 6 * Q, 0.9)];
  const processor = [];
  const algoOptions = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 'fold', 'sinefold', 'chebyshev', 'nonsense'];
  for (const algorithm of algoOptions) {
    const before = logged.length;
    const perQuantum = (q) => ({ distort: [0, 0.5, 1, 2, 3, 0.1][q], postgain: [1, 1, 0.5, 0.0001, 2, 0.8][q] });
    const out = run('distort-processor', { options: { processorOptions: { algorithm } }, input, quanta: 6, params: perQuantum });
    processor.push({ ...record(`algorithm ${JSON.stringify(algorithm)}`, { algorithm, logged: logged.length > before }, input, 6, perQuantum, out), input: undefined });
  }
  // moving (a-rate) parameters: one value per sample
  const moving = (q) => ({
    distort: Float32Array.from({ length: Q }, (_, i) => 0.5 + 0.01 * (q * Q + i)),
    postgain: Float32Array.from({ length: Q }, (_, i) => 1 - 0.004 * i),
  });
  const aRate = [];
  for (const algorithm of ['soft', 'diode', 'asym', 'chebyshev']) {
    const out = run('distort-processor', { options: { processorOptions: { algorithm } }, input, quanta: 3, params: moving });
    aRate.push({ ...record(`a-rate ${algorithm}`, { algorithm }, input, 3, moving, out), input: undefined });
  }
  write('distort.json', {
    source: 'packages/superdough/helpers.mjs:509-603 and worklets.mjs:429-463',
    names,
    xs,
    grid,
    input: b64(input[0]),
    processor,
    aRate,
  });
}

// ------------------------------------------------------------------------------------------------------------------ shape, crush, coarse
{
  const input = lcg(6, 8 * Q, 0.95);
  const input2 = lcg(7, 8 * Q, 0.6);
  const shapeParams = (q) => ({
    shape: [0, 0.5, 0.9, 1, 2, -0.5, 0.25, 0.999][q],
    postgain: [1, 1, 0.5, 1, 0.0001, 3, 0.7, 1][q],
  });
  const crushParams = (q) => ({ crush: [0, 1, 4, 8, 3.5, 16, 24, 1.5][q] });
  const coarseParams = (q) => ({ coarse: [1, 2.5, 3, 7, 0.5, 128, 200, 1.5][q] });
  const mk = (processor, name, params, channels) => {
    const ins = channels === 1 ? [input] : [input, input2];
    return record(name, {}, ins, 8, params, run(processor, { input: ins, quanta: 8, params }));
  };
  write('shapecrushcoarse.json', {
    source: 'packages/superdough/worklets.mjs:193-295',
    shape: [mk('shape-processor', 'mono', shapeParams, 1), mk('shape-processor', 'stereo', shapeParams, 2)],
    crush: [mk('crush-processor', 'mono', crushParams, 1), mk('crush-processor', 'stereo', crushParams, 2)],
    coarse: [mk('coarse-processor', 'mono', coarseParams, 1), mk('coarse-processor', 'stereo', coarseParams, 2)],
  });
}

// ------------------------------------------------------------------------------------------------------------------ djf
{
  const cases = [];
  const make = (name, input, values) => {
    const perQuantum = (q) => ({ value: values[q] });
    cases.push(record(name, {}, input, values.length, perQuantum, run('djf-processor', { input, quanta: values.length, params: perQuantum })));
  };
  make('lowpassThenNone', [lcg(8, 12 * Q, 0.7), lcg(9, 12 * Q, 0.7)], [0.5, 0.25, 0.25, 0.5, 0.75, 0.9, 0.51, 0.49, 0.52, 0.48, 0.0, 1.0]);
  make('highpassMono', [lcg(10, 8 * Q, 0.7)], [0.75, 0.75, 0.6, 0.55, 0.9, 1, 0.52, 0.8]);
  make('dcLowpass', [Float32Array.from({ length: 16 * Q }, () => 1)], Array.from({ length: 16 }, () => 0.25));
  make('dcHighpass', [Float32Array.from({ length: 16 * Q }, () => 1)], Array.from({ length: 16 }, () => 0.75));
  {
    const input = [lcg(42, 6 * Q, 0.7), lcg(43, 6 * Q, 0.7)];
    const values = [0.3, 0.3, 0.5, 0.8, 0.8, 0.2];
    const perQuantum = (q) => ({ value: values[q] });
    cases.push(record('stereo at 44.1 kHz', { sampleRate: 44100 }, input, 6, perQuantum, run('djf-processor', { input, quanta: 6, params: perQuantum, processors: registry44, rate: 44100 })));
  }
  write('djf.json', { source: 'packages/superdough/worklets.mjs:297-362', cases });
}

// ------------------------------------------------------------------------------------------------------------------ transient
{
  /** Drum-like bursts: noise with an exponential decay, every 4 quanta. */
  const bursts = (seed, quanta, amp) => {
    const noise = lcg(seed, quanta * Q, 1);
    return Float32Array.from(noise, (v, i) => amp * v * Math.exp(-(i % (4 * Q)) / 60));
  };
  const cases = [];
  const make = (name, options, input, quanta) => {
    const out = run('transient-processor', { options: { processorOptions: options }, input, quanta });
    cases.push(record(name, { options }, input, quanta, () => ({}), out));
  };
  make('attackBoost', { attack: 1, sustain: 0, begin: 0.0001, end: 0.05 }, [bursts(11, 12, 0.6)], 12);
  make('sustainCut', { attack: 0, sustain: -1, begin: 0.0001, end: 0.05 }, [bursts(12, 12, 0.6)], 12);
  make('stereoMixed', { attack: 0.5, sustain: 0.5, begin: 0.0001, end: 0.05 }, [bursts(13, 12, 0.6), bursts(14, 12, 0.4)], 12);
  make(
    'customTimes',
    { attack: -1, sustain: 1, attackTime: 0.001, sustainTime: 0.2, sensitivity: 0.5, mix: 0.5, begin: 0.0001, end: 0.05 },
    [bursts(15, 12, 0.8)],
    12,
  );
  make('defaultsOnly', { attack: 0, sustain: 0, begin: 0.0001, end: 0.05 }, [bursts(16, 12, 0.9)], 12);
  make('gated', { attack: 1, sustain: -0.5, begin: 0.003, end: 0.02 }, [bursts(17, 12, 0.6)], 12);
  make('clampedAmounts', { attack: 5, sustain: -7, begin: 0.0001, end: 0.05, attackTime: 0.00001, sustainTime: 5, sensitivity: 9, mix: 3 }, [bursts(18, 12, 0.5)], 12);
  {
    const options = { attack: 0.8, sustain: -0.6, begin: 0.0001, end: 0.2 };
    const input = [bursts(19, 12, 0.7)];
    cases.push(record('burst at 44.1 kHz', { options, sampleRate: 44100 }, input, 12, () => ({}), run('transient-processor', { options: { processorOptions: options }, input, quanta: 12, processors: registry44, rate: 44100 })));
  }
  write('transient.json', { source: 'packages/superdough/worklets.mjs:1417-1494', cases });
}

// ------------------------------------------------------------------------------------------------------------------ phase vocoder
{
  const quanta = 30;
  const n = quanta * Q;
  const sine1500 = sine(1500, 0.5, n);
  const twoTone = Float32Array.from({ length: n }, (_, i) => 0.35 * Math.sin(2 * Math.PI * 440 * (i / SR)) + 0.2 * Math.sin(2 * Math.PI * 2500 * (i / SR) + 0.3) + 0.1 * Math.sin(2 * Math.PI * 6200 * (i / SR)));
  const noise = Float32Array.from(lcg(21, n, 1), (v, i) => 0.4 * v * (0.5 + 0.5 * Math.sin((2 * Math.PI * 7 * i) / n)));
  const signals = { sine1500: b64(sine1500), twoTone: b64(twoTone), noise: b64(noise) };
  const cases = [];
  const make = (name, input, stretch, signalNames) => {
    const perQuantum = (q) => ({ pitchFactor: typeof stretch === 'function' ? stretch(q) : stretch });
    const out = run('phase-vocoder-processor', { options: { numberOfInputs: 1, numberOfOutputs: 1 }, input, quanta, params: perQuantum });
    cases.push({ ...record(name, { signals: signalNames }, input, quanta, perQuantum, out), input: undefined });
  };
  make('sine1500 stretch 0', [sine1500], 0, ['sine1500']);
  make('sine1500 stretch 1', [sine1500], 1, ['sine1500']);
  make('sine1500 stretch -2', [sine1500], -2, ['sine1500']);
  make('twoTone moving stretch (stereo, right = sine)', [twoTone, sine1500], (q) => (q < 12 ? 0 : q < 20 ? 0.5 : -1), ['twoTone', 'sine1500']);
  make('noise stretch 1.5', [noise], 1.5, ['noise']);
  // the extremes: pf = 4 (most peaks end up above Nyquist and are dropped), pf = 0 (every peak lands on bin 0), pf = 0.95
  make('noise extremes (stretch 3, then -4, then -0.2)', [noise], (q) => (q < 10 ? 3 : q < 20 ? -4 : -0.2), ['noise']);
  write('phasevocoder.json', { source: 'packages/superdough/worklets.mjs:580-748, ola-processor.js, fft.js', signals, cases });
}
