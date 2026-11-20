// Renders the synth sounds of the pinned superdough in headless Chromium and writes their output as golden vectors for the Kotlin sources of
// cosic.audio.sources (Task 3.11): the stock oscillators with partials/phases/noise mix, noise, supersaw, pulse, bytebeat, sbd, and the pitch
// modulation (vibrato and pitch envelope) every pitched source shares.
//
//   node tools/strudel-ref/sources-oracle.mjs          # writes engine/src/test/resources/sources/{oscillators,noises,worklets,sbd,pitch}.json
//
// What runs is the real thing: packages/superdough/{synth,helpers,noise,util}.mjs are served unmodified and loaded as ES modules, with an import map that
// replaces only the modules that need the application around them (audioContext, logger, nodePools, superdough: the sound registry). registerSynthSounds()
// therefore registers the real `onTrigger` of every sound, and a case calls it exactly as superdough() does, `onTrigger(t, value, onended)` with
// `value.duration` the hap duration in seconds; the node it returns is rendered by an OfflineAudioContext. The three source worklets (supersaw, pulse,
// bytebeat) and the lfo that moves the pulse width are the real classes of worklets.mjs (cut out by marker lines, as worklet-sources.mjs does), loaded
// into a real AudioWorklet module; Math.random is replaced by a seeded generator in both scopes, so the start phases of the supersaw and the noise buffers are
// known (the cases store the buffers, and the Kotlin tests play the same ones).
//
// Needs Node 22+ and a Chromium: CHROMIUM (default /usr/bin/chromium). STRUDEL_DIR (default tools/strudel-ref/strudel next to this file) is the checkout made
// by fetch.sh; SOURCES_OUT overrides the output directory. A start time is a whole number of frames that is exact in double arithmetic (the script checks
// it), so Chromium does not round an event time to the next frame and the Kotlin tests start at the same frame.
import fs from 'fs';
import http from 'http';
import os from 'os';
import path from 'path';
import { spawn } from 'child_process';
import { fileURLToPath } from 'url';
import { prelude, superdoughDir } from './worklet-sources.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const outDir = process.env.SOURCES_OUT ?? path.join(here, '../../engine/src/test/resources/sources');
const chromium = process.env.CHROMIUM ?? '/usr/bin/chromium';
const COMMIT = '84ef32eec8074f90c6690ab8d6a6c204f508b182';
const SR = 48000;
const Q = 128;

// ------------------------------------------------------------------------------------------------------------------ the worklet module
const W = fs.readFileSync(path.join(superdoughDir, 'worklets.mjs'), 'utf8').split('\n');
const find = (pred, from = 0) => {
  const i = W.findIndex((l, j) => j >= from && pred(l));
  if (i < 0) throw new Error('marker not found in worklets.mjs: ' + pred);
  return i;
};
const cut = (startMarker, endMarker) => {
  const a = find((l) => l.startsWith(startMarker));
  const b = find((l) => l.includes(endMarker), a);
  return W.slice(a, b + 1).join('\n');
};
const workletModule = [
  // a seeded Math.random: the supersaw gives each voice a random start phase
  'let __seed = 12345;',
  'Math.random = () => { __seed = (Math.imul(__seed, 1664525) + 1013904223) >>> 0; return __seed / 4294967296; };',
  prelude,
  // getDetuner, applySemitoneDetuneToFrequency, polyBlep, waveshapes, waveShapeNames
  cut('// Optimized per-voice detuner', 'const waveShapeNames'),
  cut('class LFOProcessor', "registerProcessor('lfo-processor'"),
  cut('class SuperSawOscillatorProcessor', "registerProcessor('supersaw-oscillator'"),
  cut('class PulseOscillatorProcessor', "registerProcessor('pulse-oscillator'"),
  cut('const chyx = {', "registerProcessor('byte-beat-processor'"),
].join('\n');

// ------------------------------------------------------------------------------------------------------------------ the page
const stubs = {
  'audioContext.mjs': 'export const getAudioContext = () => globalThis.__ctx;',
  'logger.mjs': "export const logger = (...a) => { (globalThis.__logs ??= []).push(a.join(' ')); };",
  'nodePools.mjs': 'export const getNodeFromPool = (name, factory) => factory(); export const releaseNodeToPool = () => {};',
  'superdough.mjs': `export const sounds = {};
export const soundMap = { get: () => sounds, set: (o) => { Object.assign(sounds, o); } };
export const registerSound = (name, onTrigger, data) => { sounds[name.toLowerCase().replace(/\\s+/g, '_')] = { onTrigger, data }; };
export const getSuperdoughAudioController = () => { throw new Error('there is no audio controller here'); };`,
};

const pageScript = (cases) => `
globalThis.process = { env: { NODE_ENV: 'production' } };
globalThis.__logs = [];
let seed = 20201120;
Math.random = () => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed / 4294967296; };
const toB64 = (f) => { const u = new Uint8Array(f.buffer, f.byteOffset, f.byteLength); let s = ''; for (let i = 0; i < u.length; i += 8192) s += String.fromCharCode(...u.subarray(i, i + 8192)); return btoa(s); };
const created = [];
const originalCreateBuffer = OfflineAudioContext.prototype.createBuffer;
OfflineAudioContext.prototype.createBuffer = function (...args) { const b = originalCreateBuffer.apply(this, args); created.push(b); return b; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
try {
  const { sounds } = await import('/stubs/superdough.mjs');
  const { registerSynthSounds } = await import('/sd/synth.mjs');
  const helpers = await import('/sd/helpers.mjs');
  registerSynthSounds();
  const noiseBuffers = {};
  const results = [];
  for (const c of ${JSON.stringify(cases)}) {
    const ctx = new OfflineAudioContext(c.kind === 'pitch' ? 1 : 2, c.startFrame + c.frames, c.sampleRate);
    await ctx.audioWorklet.addModule('/worklets.js');
    globalThis.__ctx = ctx;
    globalThis.__logs.length = 0;
    const t = c.startFrame / c.sampleRate;
    const out = { name: c.name };
    if (c.kind === 'pitch') {
      const src = new ConstantSourceNode(ctx, { offset: 0 });
      helpers.getPitchEnvelope(src.offset, c.value, t, t + c.duration);
      helpers.getVibratoOscillator(src.offset, c.value, t);
      src.connect(ctx.destination);
      src.start(0);
      const rendered = await ctx.startRendering();
      out.left = toB64(rendered.getChannelData(0).slice(c.startFrame));
    } else {
      created.length = 0;
      const handle = await sounds[c.sound].onTrigger(t, { ...c.value, duration: c.duration }, () => {});
      if (c.noiseType && created.length) noiseBuffers[c.noiseType] = Float32Array.from(created[created.length - 1].getChannelData(0));
      handle.node.connect(ctx.destination);
      await sleep(150); // a message to a worklet (the bytebeat's code) is handled on its thread before rendering starts
      const rendered = await ctx.startRendering();
      const left = rendered.getChannelData(0).slice(c.startFrame);
      const right = rendered.getChannelData(1).slice(c.startFrame);
      out.left = toB64(left);
      if (c.stereo) out.right = toB64(right);
      else for (let i = 0; i < left.length; i++) if (left[i] !== right[i]) throw new Error(c.name + ': a mono source came out stereo at frame ' + i);
      if (c.noiseType) out.noise = toB64(noiseBuffers[c.noiseType].slice(0, c.frames));
    }
    out.logs = globalThis.__logs.slice();
    results.push(out);
  }
  await fetch('/result', { method: 'POST', body: JSON.stringify({ userAgent: navigator.userAgent, results }) });
} catch (e) {
  await fetch('/result', { method: 'POST', body: JSON.stringify({ error: String(e) + (e && e.stack) }) });
}`;

// ------------------------------------------------------------------------------------------------------------------ the cases
/** The frames to render after the start: the whole envelope (hold + release + 10 ms) and a little more. */
const framesFor = (duration, value, sr) => Math.ceil((duration + Math.max(value.release ?? 0.01, 0.01) + 0.01) * sr) + 96;
function make(group, name, sound, value, opts = {}) {
  const sampleRate = opts.sampleRate ?? SR;
  const startFrame = opts.startFrame ?? 750; // t = 750 / 48000 = 5 / 320 s: a quantum offset of 110 frames
  const duration = opts.duration ?? 0.04;
  if ((startFrame / sampleRate) * sampleRate !== startFrame) throw new Error(`${name}: a start of ${startFrame} frames is not exact at ${sampleRate} Hz`);
  const frames = opts.frames ?? framesFor(duration, value, sampleRate);
  return { group, name, kind: opts.kind ?? 'sound', sound, value, duration, startFrame, frames, sampleRate, stereo: opts.stereo ?? false, noiseType: opts.noiseType ?? null };
}
const osc = (name, sound, value, opts) => make('oscillators', name, sound, value, opts);
const noiseOf = (name, sound, value, opts) => make('noises', name, sound, value, { noiseType: sound, ...opts });
const worklet = (name, sound, value, opts) => make('worklets', name, sound, value, { stereo: opts?.stereo ?? sound === 'supersaw', ...opts });
const pitch = (name, value, opts) => make('pitch', name, null, value, { kind: 'pitch', ...opts });

const cases = [
  // ---- the stock oscillators: pitch, the envelope defaults, band-limiting
  osc('sine-default', 'sine', {}),
  osc('sawtooth-a440', 'sawtooth', { note: 69 }),
  osc('square-c3', 'square', { note: 'c3' }),
  osc('triangle-e4', 'triangle', { note: 'e4' }, { startFrame: 0 }),
  osc('freq-octave', 'sawtooth', { freq: 220, octave: 1 }),
  osc('note-zero-plays-default', 'square', { note: 0 }),
  osc('saw-15000', 'sawtooth', { freq: 15000 }),
  osc('saw-above-nyquist', 'sawtooth', { freq: 30000 }),
  osc('saw-48000-aligned', 'sawtooth', { note: 60 }, { startFrame: 48000 }),
  // ---- partials: a count (`n`), a list, with phases; user, the triangle fallback, sine ignoring them
  osc('saw-n4', 'sawtooth', { n: 4, note: 57 }),
  osc('square-n7', 'square', { n: 7, note: 45 }),
  osc('triangle-n5', 'triangle', { n: 5, note: 50 }),
  osc('user-n3', 'user', { n: 3, note: 60 }),
  osc('user-partials', 'user', { partials: [1, 0, 0.3, 0.5, 0.1], note: 52 }),
  osc('saw-partials-phases', 'sawtooth', { partials: [1, 0.5, 0.25], phases: [0.25, 0, 0.5], note: 48 }),
  osc('square-partials-phases', 'square', { partials: [1, 1, 1], phases: [0.1, 0.2, 0.3], note: 55 }),
  osc('triangle-partials', 'triangle', { partials: [1, 0.5, 0.2, 0.1], note: 62 }),
  osc('user-fallback-triangle', 'user', { note: 52 }),
  osc('sine-ignores-partials', 'sine', { partials: [1, 1, 1], note: 60 }),
  osc('n-zero-is-stock', 'sawtooth', { n: 0, note: 60 }),
  osc('one', 'one', {}),
  // ---- the amplitude envelope
  osc('adsr-all', 'sawtooth', { note: 55, attack: 0.01, decay: 0.02, sustain: 0.3, release: 0.03 }),
  osc('adsr-attack-only', 'square', { note: 55, attack: 0.02 }),
  osc('adsr-decay-only', 'square', { note: 55, decay: 0.02 }),
  osc('adsr-release-only', 'triangle', { note: 55, release: 0.05 }),
  osc('adsr-attack-longer-than-note', 'triangle', { note: 55, attack: 0.1 }, { duration: 0.03 }),
  osc('adsr-decay-cut-short', 'sine', { note: 57, attack: 0.01, decay: 0.2, sustain: 0.1 }, { duration: 0.05 }),
  // ---- the noise mix
  osc('noise-mix-0.3', 'sawtooth', { note: 45, noise: 0.3 }, { noiseType: 'pink' }),
  osc('noise-mix-0.5', 'sawtooth', { note: 45, noise: 0.5 }, { noiseType: 'pink' }),
  osc('noise-mix-0.8', 'sawtooth', { note: 45, noise: 0.8 }, { noiseType: 'pink' }),
  osc('noise-mix-1', 'square', { note: 45, noise: 1 }, { noiseType: 'pink' }),
  osc('noise-mix-1.5', 'sine', { note: 57, noise: 1.5 }, { noiseType: 'pink' }),
  // ---- pitch modulation on a native oscillator
  osc('vibrato', 'sawtooth', { note: 52, vib: 5, vibmod: 1 }),
  osc('vibrato-default-depth', 'sine', { note: 57, vib: 7 }),
  // An OscillatorNode that starts in the middle of a render quantum reads its sample-accurate AudioParam values (detune and frequency automation) from the first
  // frame of the quantum instead of the frame it starts on, a quirk of Chromium: the first notes of a pitch envelope run at the parameter's value before its first
  // event for up to 127 frames, and the phase shift stays. Web Audio's algorithm has no such thing, so these cases start on a quantum boundary.
  osc('pitch-envelope', 'sawtooth', { note: 48, penv: 12 }, { startFrame: 48000 }),
  osc('pitch-envelope-adsr', 'square', { note: 52, penv: 12, panchor: 0, pattack: 0.01, pdecay: 0.02, psustain: 0.5 }, { startFrame: 48000 }),
  // ---- other sample rates choose other table sizes
  osc('saw-44100', 'sawtooth', { note: 57 }, { sampleRate: 44100, startFrame: 11025 }),
  osc('square-22050', 'square', { note: 57 }, { sampleRate: 22050, startFrame: 11025 }),

  // ---- noise sounds
  noiseOf('pink-default', 'pink', {}),
  noiseOf('white-default', 'white', {}),
  noiseOf('brown-default', 'brown', {}),
  noiseOf('crackle-default', 'crackle', {}),
  noiseOf('crackle-density-2', 'crackle', { density: 2 }),
  noiseOf('pink-adsr', 'pink', { attack: 0.01, decay: 0.01, sustain: 0.5, release: 0.02 }),

  // ---- worklets: supersaw
  worklet('supersaw-default', 'supersaw', { note: 57 }),
  worklet('supersaw-unison-1', 'supersaw', { note: 57, unison: 1 }),
  worklet('supersaw-wide', 'supersaw', { note: 45, unison: 7, spread: 1, detune: 1 }),
  worklet('supersaw-detune-from-n', 'supersaw', { note: 45, n: 0.5 }),
  worklet('supersaw-fractional-unison', 'supersaw', { note: 52, unison: 2.5, detune: 0.4 }),
  worklet('supersaw-vibrato-penv', 'supersaw', { note: 52, vib: 5, vibmod: 1, penv: 7 }),
  worklet('supersaw-adsr', 'supersaw', { note: 52, attack: 0.01, decay: 0.02, sustain: 0.4, release: 0.02 }),
  worklet('supersaw-start-at-0', 'supersaw', { note: 52 }, { startFrame: 0 }),
  worklet('supersaw-start-aligned', 'supersaw', { note: 52 }, { startFrame: 48000 }),
  // ---- worklets: pulse
  worklet('pulse-default', 'pulse', { note: 45 }),
  worklet('pulse-width', 'pulse', { note: 45, pw: 0.8 }),
  worklet('pulse-pwrate', 'pulse', { note: 45, pwrate: 2 }),
  worklet('pulse-pwsweep', 'pulse', { note: 45, pwsweep: 0.2 }),
  worklet('pulse-full', 'pulse', { note: 52, pw: 0.3, pwrate: 3, pwsweep: 0.4 }),
  worklet('pulse-high', 'pulse', { note: 96 }),
  worklet('pulse-vibrato', 'pulse', { note: 45, vib: 6, vibmod: 2 }),
  worklet('pulse-start-aligned', 'pulse', { note: 45 }, { startFrame: 48000 }),
  worklet('pulse-short', 'pulse', { note: 45 }, { duration: 0.01 }),
  // ---- worklets: bytebeat (the fifteen default beats at n = 0..14, then expressions of their own)
  ...Array.from({ length: 15 }, (_, n) => worklet(`bytebeat-default-${n}`, 'bytebeat', { n, note: 48 }, { frames: 1500 })),
  worklet('bytebeat-identity', 'bytebeat', { freq: 187.5, byteBeatExpression: 't', byteBeatStartTime: 0 }, { frames: 1200 }),
  worklet('bytebeat-start-time', 'bytebeat', { freq: 100, byteBeatExpression: 't&t>>8', byteBeatStartTime: 1000.7 }, { frames: 1200 }),
  worklet('bytebeat-math', 'bytebeat', { note: 52, byteBeatExpression: '(sin(t*PI/128)*127+127)*(t%1024<512)+max(t&63,10)' }, { frames: 1200 }),
  worklet('bytebeat-comma-ternary', 'bytebeat', { note: 52, byteBeatExpression: 't>>6 ? (t*3&t>>7) : 5, t^t>>9' }, { frames: 1200 }),
  worklet('bytebeat-syntax-error', 'bytebeat', { note: 52, byteBeatExpression: 't +* 3' }, { frames: 1200 }),
  worklet('bytebeat-unknown-name', 'bytebeat', { note: 52, byteBeatExpression: 'foo(t)' }, { frames: 1200 }),
  worklet('bytebeat-fraction-n', 'bytebeat', { n: 2.5, note: 52 }, { frames: 1200 }),
  worklet('bytebeat-start-aligned', 'bytebeat', { n: 3, note: 48 }, { startFrame: 48000, frames: 1200 }),

  // ---- sbd
  make('sbd', 'sbd-default', 'sbd', {}, { duration: 0.6, frames: 12000, startFrame: 48000, noiseType: 'brown' }),
  make('sbd', 'sbd-tuned', 'sbd', { penv: 12, pdecay: 0.2, decay: 0.3, note: 33 }, { duration: 0.4, frames: 12000, startFrame: 48000, noiseType: 'brown' }),
  make('sbd', 'sbd-clip', 'sbd', { clip: 0.3 }, { duration: 1, frames: 14500, startFrame: 48000, noiseType: 'brown' }),
  make('sbd', 'sbd-no-pitch-drop', 'sbd', { penv: 0, decay: 0.1 }, { duration: 0.3, frames: 5400, startFrame: 48000, noiseType: 'brown' }),

  // ---- the pitch traces of getPitchEnvelope and getVibratoOscillator on an AudioParam (cents, one value per frame)
  pitch('vibrato-4hz', { vib: 4 }, { duration: 0.5, frames: 6000 }),
  pitch('vibrato-depth-2', { vib: 5, vibmod: 2 }, { duration: 0.5, frames: 6000 }),
  pitch('vibrato-slow-lagrange', { vib: 0.5, vibmod: 1 }, { duration: 1, frames: 6000 }),
  pitch('vibrato-3hz', { vib: 3, vibmod: 0.5 }, { duration: 0.5, frames: 6000 }),
  pitch('vibrato-zero', { vib: 0 }, { duration: 0.1, frames: 1200 }),
  pitch('penv-default', { penv: 12 }, { duration: 0.1, frames: 4900 }),
  pitch('penv-adsr', { penv: 12, panchor: 0, pattack: 0.01, pdecay: 0.02, psustain: 0.5 }, { duration: 0.08, frames: 4100 }),
  pitch('penv-exponential', { penv: 12, pcurve: 1, panchor: 0, pattack: 0.01 }, { duration: 0.1, frames: 4900 }),
  pitch('penv-exponential-default-anchor', { penv: 7, pcurve: 1 }, { duration: 0.1, frames: 5000 }),
  pitch('penv-negative', { penv: -5, pattack: 0.02, pdecay: 0.04, psustain: 0.3, panchor: 0.2 }, { duration: 0.08, frames: 4400 }),
  pitch('penv-only-decay', { pdecay: 0.05 }, { duration: 0.1, frames: 5000 }),
  pitch('penv-attack-longer-than-note', { penv: 12, pattack: 0.2, panchor: 0 }, { duration: 0.1, frames: 5000 }),
  pitch('penv-release', { penv: 12, panchor: 0, pattack: 0.01, pdecay: 0.01, psustain: 1, prelease: 0.05 }, { duration: 0.05, frames: 4900 }),
  pitch('penv-curve-out-of-range', { penv: 12, pcurve: 2, panchor: 0, pattack: 0.02 }, { duration: 0.08, frames: 4100 }),
  pitch('vibrato-and-penv', { vib: 5, vibmod: 1, penv: 7, panchor: 0, pattack: 0.02, pdecay: 0.04, psustain: 0.5 }, { duration: 0.1, frames: 5000 }),
  pitch('penv-aligned-start', { penv: 12, panchor: 0, pattack: 0.01 }, { duration: 0.05, frames: 3000, startFrame: 48000 }),
];

// ------------------------------------------------------------------------------------------------------------------ server and browser
const page = `<!doctype html><html><body>
<script type="importmap">${JSON.stringify({ imports: Object.fromEntries(Object.keys(stubs).map((f) => [`/sd/${f}`, `/stubs/${f}`])) })}</script>
<script type="module">${pageScript(cases)}</script>
</body></html>`;

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'sources-oracle-'));
let deliver;
const delivered = new Promise((resolve) => (deliver = resolve));
const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0];
  if (req.method === 'POST') {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      res.end('ok');
      deliver(Buffer.concat(chunks).toString('utf8'));
    });
  } else if (url === '/worklets.js') {
    res.setHeader('content-type', 'text/javascript');
    res.end(workletModule);
  } else if (url.startsWith('/stubs/')) {
    res.setHeader('content-type', 'text/javascript');
    res.end(stubs[url.slice('/stubs/'.length)] ?? '');
  } else if (url.startsWith('/sd/')) {
    const file = path.join(superdoughDir, path.basename(url));
    if (!fs.existsSync(file)) {
      res.statusCode = 404;
      res.end('not found');
    } else {
      res.setHeader('content-type', 'text/javascript');
      res.end(fs.readFileSync(file));
    }
  } else {
    res.setHeader('content-type', 'text/html; charset=utf-8');
    res.end(page);
  }
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const browser = spawn(chromium, ['--headless=new', '--no-sandbox', '--disable-gpu', '--mute-audio', '--autoplay-policy=no-user-gesture-required', '--user-data-dir=' + profile, `http://127.0.0.1:${server.address().port}/`], { stdio: 'ignore' });
const timer = setTimeout(() => deliver(JSON.stringify({ error: 'timeout: the page did not answer within 600 s' })), 600000);
const body = JSON.parse(await delivered);
clearTimeout(timer);
browser.kill('SIGKILL');
server.close();
fs.rmSync(profile, { recursive: true, force: true });
if (body.error) throw new Error(body.error);

const groups = {};
body.results.forEach((r, i) => {
  const c = cases[i];
  if (r.name !== c.name) throw new Error(`result ${i} is ${r.name}, expected ${c.name}`);
  const { group, name, sound, value, duration, startFrame, frames, sampleRate, stereo, noiseType } = c;
  (groups[group] ??= []).push({ name, sound, controls: value, durationSec: duration, startFrame, frames, sampleRate, stereo, noiseType, left: r.left, right: r.right ?? null, noise: r.noise ?? null, logs: r.logs });
});
fs.mkdirSync(outDir, { recursive: true });
for (const [group, list] of Object.entries(groups)) {
  const file = path.join(outDir, `${group}.json`);
  fs.writeFileSync(file, JSON.stringify({ commit: COMMIT, userAgent: body.userAgent, quantum: Q, cases: list }));
  console.log(`${file}: ${list.length} cases, ${(fs.statSync(file).size / 1024).toFixed(0)} KB`);
}
console.log(body.userAgent);
