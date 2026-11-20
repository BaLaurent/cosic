// Renders Web Audio OscillatorNodes in headless Chromium (OfflineAudioContext: the native oscillator superdough's sine/sawtooth/square/triangle and its
// partials waves run on) and writes the samples at chosen frames as a Kotlin file of test vectors:
//
//   node tools/strudel-ref/oscillator-oracle.mjs > engine/src/test/kotlin/cosic/audio/sources/OscillatorOracleVectors.kt
//
// Needs Node 22+ (global WebSocket) and a Chromium: CHROMIUM (default /usr/bin/chromium). It drives the browser over the DevTools protocol, so nothing has
// to be installed. Used by PeriodicOscillatorTest, which plays the same waves, notes and pitch inputs with PeriodicOscillator.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const FRAMES = 4800; // the default length of a render; a case may ask for more with `frames`
const f32 = (xs) => xs.map(Math.fround);
// Coefficients are exact in float32, so Chromium (which copies them to float32) and the Kotlin test see the same numbers.
const additive = (n, type) => {
  const real = [0];
  const imag = [0];
  for (let m = 1; m <= n; m++) {
    if (type === 'sawtooth') { real.push(0); imag.push(-1 / m); } // superdough's waveformN: a custom sawtooth is -sum sin(m x) / m
    else if (type === 'square') { real.push(0); imag.push(m % 2 === 0 ? 0 : 1 / m); }
    else if (type === 'triangle') { real.push(m % 2 === 0 ? 0 : 1 / (m * m)); imag.push(0); } // cosine terms: it starts at its peak
    else { real.push(0); imag.push(1); } // 'user'
  }
  return { real: f32(real), imag: f32(imag) };
};
const lcg = (seed) => () => (seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0) / 4294967296;
// n random coefficients each of real then imag, from one stream of the generator above: the Kotlin test builds the same ones from (noiseSeed, noiseCount)
const noise = (n, seed) => { const r = lcg(seed); return { noiseSeed: seed, noiseCount: n, real: f32(Array.from({ length: n }, () => r() - 0.5)), imag: f32(Array.from({ length: n }, () => r() - 0.5)) }; };

const base = { sampleRate: 48000, frames: FRAMES, detune: 0, fm: [0, 0], vibrato: [0, 0], ramp: [0, 0, 0], noiseSeed: 0, noiseCount: 0 };
const cases = [
  // the four waves at audible notes, from the default synth note to above the highest useful table
  { name: 'sine65', wave: 'sine', frequency: 65.40639132514966 },
  { name: 'sine440', wave: 'sine', frequency: 440 },
  { name: 'sine12000', wave: 'sine', frequency: 12000 },
  { name: 'triangle262', wave: 'triangle', frequency: 261.6255653005986 },
  { name: 'triangle1500', wave: 'triangle', frequency: 1500 },
  { name: 'square1000', wave: 'square', frequency: 1000.5 },
  { name: 'square9000', wave: 'square', frequency: 9000 },
  { name: 'saw65', wave: 'sawtooth', frequency: 65.40639132514966 },
  { name: 'saw440', wave: 'sawtooth', frequency: 440 },
  { name: 'saw4186', wave: 'sawtooth', frequency: 4186.009 },
  { name: 'saw15000', wave: 'sawtooth', frequency: 15000 },
  { name: 'saw22000', wave: 'sawtooth', frequency: 22000 },
  { name: 'saw30000', wave: 'sawtooth', frequency: 30000 }, // above Nyquist: silent
  { name: 'sawNegative', wave: 'sawtooth', frequency: -440 },
  { name: 'saw0', wave: 'sawtooth', frequency: 0 },
  // slower than 3.5 Hz the table is read with Lagrange polynomials: 0.16 and 0.3 table entries a frame are 1.875 Hz and 3.5156 Hz at 48 kHz. The squares run for
  // half a second to cross their steep edges, where the order of the interpolation shows
  { name: 'saw0_5', wave: 'sawtooth', frequency: 0.5 },
  { name: 'square1_8', wave: 'square', frequency: 1.8, frames: 24000 }, // 5-point, just below 0.16
  { name: 'square2_2', wave: 'square', frequency: 2.2, frames: 24000 }, // 3-point, just above it
  { name: 'square3_4', wave: 'square', frequency: 3.4, frames: 24000 }, // 3-point, just below 0.3
  { name: 'square3_6', wave: 'square', frequency: 3.6, frames: 24000 }, // linear
  // other sample rates choose other table sizes
  { name: 'saw440at22050', wave: 'sawtooth', frequency: 440, sampleRate: 22050 },
  { name: 'saw440at44100', wave: 'sawtooth', frequency: 440, sampleRate: 44100 },
  { name: 'saw440at96000', wave: 'sawtooth', frequency: 440, sampleRate: 96000 },
  { name: 'square9000at96000', wave: 'square', frequency: 9000, sampleRate: 96000 },
  // the frequency and detune AudioParams driven by other signals: what vibrato, the pitch envelope and FM do
  { name: 'detune700', wave: 'sawtooth', frequency: 440, detune: 700 },
  { name: 'detuneDown', wave: 'square', frequency: 880, detune: -1200 },
  { name: 'vibrato', wave: 'sawtooth', frequency: 330, vibrato: [5, 50] },
  { name: 'detuneRamp', wave: 'sawtooth', frequency: 262, ramp: [-1200, 1200, 0.08] },
  { name: 'fm', wave: 'sine', frequency: 440, fm: [200, 500] },
  { name: 'fmSaw', wave: 'sawtooth', frequency: 220, fm: [330, 1000] },
  { name: 'fmAndDetune', wave: 'triangle', frequency: 300, fm: [150, 300], detune: 100 },
  { name: 'fmThroughZero', wave: 'sawtooth', frequency: 100, fm: [50, 2000] }, // the frequency goes negative
  { name: 'detuneAboveNyquist', wave: 'sawtooth', frequency: 20000, detune: 1200 },
  // partials waves (createPeriodicWave): the waveformN of superdough, DC and more than size/2 components, and a slow one
  { name: 'user5', wave: 'custom', ...additive(5, 'user'), frequency: 100 },
  { name: 'sawN7', wave: 'custom', ...additive(7, 'sawtooth'), frequency: 3000 },
  { name: 'triangleN9', wave: 'custom', ...additive(9, 'triangle'), frequency: 1 },
  { name: 'squareN31', wave: 'custom', ...additive(31, 'square'), frequency: 220 },
  { name: 'phased', wave: 'custom', real: f32([0.7, 0.2, -0.3, 0.5, 0, 0.1]), imag: f32([0.9, 0.5, 0.25, 0, 0.3, -0.2]), frequency: 440 },
  { name: 'noise3000a', wave: 'custom', ...noise(3000, 12345), frequency: 80 },
  { name: 'noise3000b', wave: 'custom', ...noise(3000, 12345), frequency: 5000 },
  { name: 'noise40', wave: 'custom', ...noise(40, 777), frequency: 1700 },
].map((c) => ({ ...base, real: [], imag: [], ...c }));

async function withChromium(fn) {
  const chromium = process.env.CHROMIUM ?? '/usr/bin/chromium';
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cosic-chromium-'));
  const proc = spawn(chromium, ['--headless=new', '--no-sandbox', '--disable-gpu', '--mute-audio', '--no-first-run', '--no-default-browser-check',
    '--autoplay-policy=no-user-gesture-required', `--user-data-dir=${dir}`, '--remote-debugging-port=0', 'about:blank'], { stdio: ['ignore', 'ignore', 'pipe'] });
  let stderr = '';
  proc.stderr.on('data', (d) => { stderr += d; });
  try {
    const portFile = path.join(dir, 'DevToolsActivePort');
    for (let i = 0; i < 200 && !fs.existsSync(portFile); i++) await new Promise((r) => setTimeout(r, 50));
    if (!fs.existsSync(portFile)) throw new Error('Chromium did not start:\n' + stderr);
    const [port, wsPath] = fs.readFileSync(portFile, 'utf8').trim().split('\n');
    const ws = new WebSocket(`ws://127.0.0.1:${port}${wsPath}`);
    await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
    let id = 0;
    const pending = new Map();
    ws.onmessage = (event) => {
      const message = JSON.parse(event.data);
      const waiting = pending.get(message.id);
      if (!waiting) return;
      pending.delete(message.id);
      if (message.error) waiting.reject(new Error(JSON.stringify(message.error)));
      else waiting.resolve(message.result);
    };
    const send = (method, params = {}, sessionId) => new Promise((resolve, reject) => {
      const i = ++id;
      pending.set(i, { resolve, reject });
      ws.send(JSON.stringify({ id: i, method, params, sessionId }));
    });
    const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
    const evaluate = async (expression) => {
      const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }, sessionId);
      if (r.exceptionDetails) throw new Error('page: ' + JSON.stringify(r.exceptionDetails.exception ?? r.exceptionDetails));
      return r.result.value;
    };
    const version = await send('Browser.getVersion');
    try { return await fn(evaluate, version.product); } finally { ws.close(); }
  } finally {
    proc.kill('SIGKILL');
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

/** The page: renders every case in an OfflineAudioContext and returns the mono samples. */
const page = (cs) => `(async () => {
  const out = {};
  for (const c of ${JSON.stringify(cs)}) {
    const ctx = new OfflineAudioContext(1, c.frames, c.sampleRate);
    const osc = ctx.createOscillator();
    if (c.wave === 'custom') osc.setPeriodicWave(ctx.createPeriodicWave(Float32Array.from(c.real), Float32Array.from(c.imag)));
    else osc.type = c.wave;
    osc.frequency.value = c.frequency;
    osc.detune.value = c.detune;
    const modulate = (hz, gain, param) => {
      const m = ctx.createOscillator();
      m.frequency.value = hz;
      const g = ctx.createGain();
      g.gain.value = gain;
      m.connect(g);
      g.connect(param);
      m.start(0);
    };
    if (c.fm[1] !== 0) modulate(c.fm[0], c.fm[1], osc.frequency);
    if (c.vibrato[1] !== 0) modulate(c.vibrato[0], c.vibrato[1], osc.detune);
    if (c.ramp[2] > 0) {
      osc.detune.setValueAtTime(c.ramp[0], 0);
      osc.detune.linearRampToValueAtTime(c.ramp[1], c.ramp[2]);
    }
    osc.connect(ctx.destination);
    osc.start(0);
    out[c.name] = Array.from((await ctx.startRendering()).getChannelData(0));
  }
  return out;
})()`;

const { rendered, product } = await withChromium(async (evaluate, product) => ({ rendered: await evaluate(page(cases)), product }));

const kd = (x) => (Number.isInteger(x) ? x.toFixed(1) : String(x)); // a Kotlin Double literal
const kds = (xs) => `doubleArrayOf(${xs.map(kd).join(', ')})`;
const frameOf = (i) => (i < 12 ? i : 100 + 97 * (i - 12));
const sampledFrames = (frames) => {
  const sampled = [];
  for (let i = 0; frameOf(i) < frames; i++) sampled.push(frameOf(i));
  return sampled;
};

console.log('package cosic.audio.sources');
console.log('');
console.log(`// Generated by tools/strudel-ref/oscillator-oracle.mjs from OscillatorNodes rendered by ${product} (OfflineAudioContext). Do not edit.`);
console.log('internal class OscillatorOracleCase(');
console.log('    val name: String,');
console.log('    val sampleRate: Int,');
console.log('    /** Length of the render. */');
console.log('    val frames: Int,');
console.log('    /** "sine", "square", "sawtooth", "triangle", or "custom" (a PeriodicWave of [real] and [imag]). */');
console.log('    val wave: String,');
console.log('    val real: DoubleArray,');
console.log('    val imag: DoubleArray,');
console.log('    /** When [noiseCount] is not 0, [real] and [imag] are empty and are `noiseCount` numbers each, drawn from [noiseSeed] as in `noiseCoefficients` of the test. */');
console.log('    val noiseSeed: Long,');
console.log('    val noiseCount: Int,');
console.log('    val frequencyHz: Double,');
console.log('    /** `detune.value`. */');
console.log('    val detuneCents: Double,');
console.log('    /** A sine oscillator of [fmHz] times [fmGain] connected to `frequency` (no FM when the gain is 0). */');
console.log('    val fmHz: Double,');
console.log('    val fmGain: Double,');
console.log('    /** A sine oscillator of [vibratoHz] times [vibratoCents] connected to `detune`. */');
console.log('    val vibratoHz: Double,');
console.log('    val vibratoCents: Double,');
console.log('    /** `detune` set to [rampFromCents] at 0 and ramped linearly to [rampToCents] at [rampEndSec] (no ramp when that is 0). */');
console.log('    val rampFromCents: Double,');
console.log('    val rampToCents: Double,');
console.log('    val rampEndSec: Double,');
console.log('    /** Root mean square of the whole render. */');
console.log('    val rms: Double,');
console.log('    /** The samples at [frameOf] 0, 1, 2, ...: the first 12 frames, then every 97th frame from the hundredth. */');
console.log('    val values: FloatArray,');
console.log(') {');
console.log('    fun frameOf(index: Int): Int = if (index < 12) index else 100 + 97 * (index - 12)');
console.log('}');
console.log('');
console.log('internal object OscillatorOracleVectors {');
console.log('    val cases: List<OscillatorOracleCase> get() = listOf(');
for (const c of cases) console.log(`        ${c.name}(),`);
console.log('    )');
for (const c of cases) {
  const data = Float32Array.from(rendered[c.name]);
  if (data.length !== c.frames) throw new Error(`${c.name}: ${data.length} frames`);
  let sum = 0;
  for (const v of data) sum += v * v;
  const [real, imag] = c.noiseCount > 0 ? [[], []] : [c.real, c.imag];
  console.log('');
  console.log(`    // one function per case: the samples of all of them in one initialiser would be over the 64 KB a JVM method may have`);
  console.log(`    private fun ${c.name}() = OscillatorOracleCase("${c.name}", ${c.sampleRate}, ${c.frames}, "${c.wave}", ${kds(real)}, ${kds(imag)}, ${c.noiseSeed}L, ${c.noiseCount}, ${kd(c.frequency)}, ${kd(c.detune)},`);
  console.log(`        ${kd(c.fm[0])}, ${kd(c.fm[1])}, ${kd(c.vibrato[0])}, ${kd(c.vibrato[1])}, ${kd(c.ramp[0])}, ${kd(c.ramp[1])}, ${kd(c.ramp[2])}, ${kd(Math.sqrt(sum / c.frames))},`);
  console.log(`        floatArrayOf(${sampledFrames(c.frames).map((f) => `${data[f]}f`).join(', ')}))`);
}
console.log('}');
