// Renders the pieces of superdough's orbit output (the real source text of feedbackdelay.mjs, reverbGen.mjs and reverb.mjs, plus the Web Audio nodes it
// is built on: DelayNode, ConvolverNode, AnalyserNode and AudioParam automation) in headless Chromium's OfflineAudioContext and writes the results as golden
// vectors for the orbit buses of the engine (Task 3.9: FeedbackDelay, Convolver, ReverbGen, AnalyserTap, the duck of OrbitSet):
//
//   node tools/strudel-ref/orbit-oracle.mjs      # writes engine/src/test/resources/dsp/orbit-{delay,convolver,reverbgen,analyser,automation}.json
//
// Inputs are not stored: they are noise from the 32-bit LCG below, which the Kotlin tests run again (`lcg(seed, n, amp)`), so the files hold the outputs only.
// CHROMIUM (default `chromium`) is started headless on a page served from a local http server, which also receives the result (virtual-time `--dump-dom` does
// not wait for audio rendering); STRUDEL_DIR and DSP_OUT as in dsp-oracle.mjs. Findings the tests rely on (all measured here, none in the Web Audio spec text):
//   * a DelayNode in the feedback loop of FeedbackDelayNode adds one render quantum to the way round: echoes come at T, 2T + 128, 3T + 256 frames, not at kT;
//   * the delay time is a fractional read with linear interpolation whose weight is rounded to 1/256, and it changes abruptly (no crossfade) at an event;
//   * ConvolverNode is plain convolution of each channel with its impulse response channel, scaled by Blink's normalization (the golden checks the formula).
import fs from 'fs';
import http from 'http';
import os from 'os';
import path from 'path';
import { spawn } from 'child_process';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.STRUDEL_DIR ?? path.join(here, 'strudel');
const outDir = process.env.DSP_OUT ?? path.join(here, '../../engine/src/test/resources/dsp');
const chromium = process.env.CHROMIUM ?? 'chromium';
const COMMIT = '84ef32eec8074f90c6690ab8d6a6c204f508b182';
const sd = path.join(root, 'packages/superdough');

// The real sources, without their imports (and `export default`).
const strip = (file) => fs.readFileSync(path.join(sd, file), 'utf8').replace(/^import .*$/gm, '').replace('export default reverbGen;', '');
const feedbackDelaySource = strip('feedbackdelay.mjs');
const reverbGenSource = strip('reverbGen.mjs');
const reverbSource = strip('reverb.mjs');

const SR8K = 8192; // a power of two: frame / sampleRate is exact, so event times land on frames without any fudge

// ------------------------------------------------------------------------------------------------------------------------------------------------ the cases
const delayCases = [
  { name: 'impulse T=2048 frames fb .5', sr: SR8K, frames: 8192, channels: 1, input: [{ impulse: [0] }], delayTime: 0.25, feedback: 0.5, events: [] },
  { name: 'impulse T=48 frames (0.001 s at 48k)', sr: 48000, frames: 2048, channels: 1, input: [{ impulse: [0] }], delayTime: 0.001, feedback: 0.5, events: [] },
  { name: 'impulse T=0', sr: 48000, frames: 1024, channels: 1, input: [{ impulse: [0] }], delayTime: 0, feedback: 0.5, events: [] },
  { name: 'impulse T=590.4 frames (fractional)', sr: 48000, frames: 4096, channels: 1, input: [{ impulse: [0] }], delayTime: 0.0123, feedback: 0.5, events: [] },
  { name: 'impulse T=100.1 frames fb 0', sr: 48000, frames: 1024, channels: 1, input: [{ impulse: [0] }], delayTime: 100.1 / 48000, feedback: 0, events: [] },
  { name: 'impulse T=3 s clamps to 1 s', sr: 4096, frames: 8500, channels: 1, input: [{ impulse: [0] }], delayTime: 3, feedback: 0.5, events: [] },
  { name: 'stereo impulses are independent lines', sr: 48000, frames: 2048, channels: 2, input: [{ impulse: [0] }, { impulse: [100] }], delayTime: 160 / 48000, feedback: 0.5, events: [] },
  {
    name: 'stereo noise with time and feedback events', sr: SR8K, frames: 8192, channels: 2, input: [{ noise: 5 }, { noise: 6 }], delayTime: 0.05, feedback: 0.7,
    events: [
      { frame: 1500, delayTime: 0.0375, feedback: 0.4 },
      { frame: 3000, delayTime: 0.1013 },
      { frame: 5000, feedback: 0.9 },
      { frame: 6000, delayTime: 0.2 },
    ],
  },
  { name: 'mono noise, time jump 0.9 s to 0.05 s', sr: SR8K, frames: 12000, channels: 1, input: [{ noise: 9 }], delayTime: 0.9, feedback: 0.6, events: [{ frame: 5000, delayTime: 0.05 }] },
];

const convolverCases = [
  { name: 'ir 2ch x in 1ch', sr: 48000, irChannels: 2, inChannels: 1, irLength: 3000, frames: 4608 },
  { name: 'ir 2ch x in 2ch', sr: 48000, irChannels: 2, inChannels: 2, irLength: 3000, frames: 4608 },
  { name: 'ir 1ch x in 1ch', sr: 48000, irChannels: 1, inChannels: 1, irLength: 3000, frames: 4608 },
  { name: 'ir 1ch x in 2ch', sr: 48000, irChannels: 1, inChannels: 2, irLength: 3000, frames: 4608 },
  { name: 'ir 2ch x in 2ch, ir 2200', sr: 48000, irChannels: 2, inChannels: 2, irLength: 2200, frames: 4608 },
  { name: 'ir 2ch x in 2ch at 44.1k', sr: 44100, irChannels: 2, inChannels: 2, irLength: 3000, frames: 4608 },
  { name: 'ir 2ch x in 1ch, ir 100 (shorter than the direct part)', sr: 48000, irChannels: 2, inChannels: 1, irLength: 100, frames: 1024 },
  { name: 'ir 2ch x in 2ch, ir 300', sr: 48000, irChannels: 2, inChannels: 2, irLength: 300, frames: 1024 },
];

const reverbGenCases = [
  { name: 'd .1 fade .01 lp 15000 dim 1000 at 48k', sr: 48000, d: 0.1, fade: 0.01, lp: 15000, dim: 1000, seed: 12345 },
  { name: 'd .05 default fade lp 8000 dim 500 at 44.1k', sr: 44100, d: 0.05, fade: 0.1, lp: 8000, dim: 500, seed: 777 },
  { name: 'd .05 lp 0 (no filter)', sr: 48000, d: 0.05, fade: 0.1, lp: 0, dim: 1000, seed: 99 },
  { name: 'd .05 lp 30000 dim 30000 (above Nyquist)', sr: 48000, d: 0.05, fade: 0.01, lp: 30000, dim: 30000, seed: 5 },
  { name: 'd .06 lp 200 dim 15000 (opening filter)', sr: 22050, d: 0.06, fade: 0, lp: 200, dim: 15000, seed: 31337 },
];

const adjustCases = [
  { name: 'adjust .02 speed 1', duration: 0.02, speed: 1, offset: 0 },
  { name: 'adjust .02 speed .5 (reversed)', duration: 0.02, speed: 0.5, offset: 0 },
  { name: 'adjust .02 speed 2 offset .3', duration: 0.02, speed: 2, offset: 0.3 },
  { name: 'adjust .02 speed 1.5', duration: 0.02, speed: 1.5, offset: 0 },
  { name: 'adjust 1 speed 1 offset .5 (longer than the buffer)', duration: 1, speed: 1, offset: 0.5 },
  { name: 'adjust .02 speed .25 offset .1', duration: 0.02, speed: 0.25, offset: 0.1 },
  { name: 'adjust .02 speed -1', duration: 0.02, speed: -1, offset: 0 },
];

const analyserCases = [
  { name: 'sine bin 37 a .8 fft 2048', sr: 48000, fftSize: 2048, input: [{ sine: { bin: 37, amp: 0.8 } }], times: [2048, 2560, 3072, 3584, 4096, 4608, 5120, 5632, 6144, 6656, 7168] },
  { name: 'stereo noise fft 2048', sr: 48000, fftSize: 2048, input: [{ noise: 41, amp: 0.5 }, { noise: 42, amp: 0.25 }], times: [2048, 2304, 2560, 3072, 4096, 8192] },
  { name: 'sine bin 100 a 1 fft 8192', sr: 48000, fftSize: 8192, input: [{ sine: { bin: 100, amp: 1 } }], times: [8192, 9216, 10240, 11264, 12288, 13312, 14336, 15360, 16384, 17408], keep: [0, 9] },
];

// events: set / lin / exp take `value` and `frame`; cancel takes `frame`
const duckEvents = (now, t0, onset, attack, depth, cur = 1) => {
  const ducked = Math.min(Math.max(1 - Math.sqrt(depth), 0.01), cur);
  return [
    { type: 'cancel', frame: now },
    { type: 'set', value: cur, frame: now },
    { type: 'exp', value: ducked, frame: t0 + onset },
    { type: 'exp', value: 1, frame: t0 + onset + attack },
  ];
};
const automationCases = [
  { name: 'duck, now 82 frames before t0, onset 0, attack 1638', sr: SR8K, frames: 8192, base: 1, events: duckEvents(4014, 4096, 0, 1638, 1) },
  { name: 'duck, now = t0 (a ramp of no length)', sr: SR8K, frames: 8192, base: 1, events: duckEvents(4096, 4096, 0, 1638, 1) },
  { name: 'duck, onset 410, attack 819, depth .5', sr: SR8K, frames: 8192, base: 1, events: duckEvents(4014, 4096, 410, 819, 0.5) },
  (() => {
    // a second duck begins while the first one is coming back up: its `cur` is where the first ramp is at that frame
    const first = duckEvents(1000, 1000, 0, 2000, 1);
    const now = 2000;
    const cur = 0.01 * Math.pow(100, (now - 1000) / 2000);
    return { name: 'second duck during the first', sr: SR8K, frames: 8192, base: 1, events: [...first, ...duckEvents(now, now, 100, 1000, 0.75, cur)] };
  })(),
  { name: 'set and linear ramp', sr: SR8K, frames: 4096, base: 0.5, events: [{ type: 'set', value: 0.25, frame: 500 }, { type: 'lin', value: 1.5, frame: 2500 }, { type: 'set', value: 0, frame: 3000 }] },
];

// ------------------------------------------------------------------------------------------------------------------------------------- the page
const page = `<!doctype html><html><body><script>
const SR8K = ${SR8K};
const lcg = (seed, n, amp = 1) => { const o = new Float32Array(n); let x = seed >>> 0; for (let i = 0; i < n; i++) { x = (x * 1664525 + 1013904223) % 4294967296; o[i] = (x / 2147483648 - 1) * amp; } return o; };
const toB64 = (f) => { const u = new Uint8Array(f.buffer, f.byteOffset, f.byteLength); let s = ''; for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]); return btoa(s); };
const clamp = (v, min, max) => Math.min(Math.max(v, min), max);
// feedbackdelay.mjs (registers BaseAudioContext.prototype.createFeedbackDelay)
${feedbackDelaySource}
// reverbGen.mjs and reverb.mjs (adjustLength, createReverb)
const releaseAudioNode = () => {};
const reverbGen = (() => { ${reverbGenSource}; return reverbGen; })();
(() => { ${reverbSource} })();

const inputSignal = (spec, frames, sr) => {
  if (spec.noise !== undefined) return lcg(spec.noise, frames, spec.amp ?? 1);
  if (spec.sine) { const o = new Float32Array(frames); const f = spec.sine.bin * sr / spec.fftSize; for (let i = 0; i < frames; i++) o[i] = spec.sine.amp * Math.sin(2 * Math.PI * f * i / sr); return o; }
  const o = new Float32Array(frames); for (const at of spec.impulse) o[at] = 1; return o;
};

async function delayCase(c) {
  const ctx = new OfflineAudioContext(c.channels, c.frames, c.sr);
  const buffer = ctx.createBuffer(c.channels, c.frames, c.sr);
  c.input.forEach((spec, ch) => buffer.copyToChannel(inputSignal(spec, c.frames, c.sr), ch));
  const source = ctx.createBufferSource(); source.buffer = buffer;
  const delay = ctx.createFeedbackDelay(1, c.delayTime, c.feedback);
  source.connect(delay); delay.connect(ctx.destination); source.start(0);
  for (const e of c.events) {
    const t = e.frame / c.sr;
    if (e.delayTime !== undefined) delay.delayTime.setValueAtTime(e.delayTime, t);
    if (e.feedback !== undefined) delay.feedback.setValueAtTime(e.feedback, t);
  }
  const rendered = await ctx.startRendering();
  return { output: Array.from({ length: c.channels }, (_, ch) => toB64(rendered.getChannelData(ch))) };
}

const sumSquares = (a) => { let s = 0; for (const v of a) s += v * v; return s; };
async function convolverCase(c) {
  const ir = Array.from({ length: c.irChannels }, (_, i) => lcg(11 + i, c.irLength));
  const inputs = Array.from({ length: c.inChannels }, (_, i) => lcg(21 + i, c.frames, 0.5));
  const ctx = new OfflineAudioContext(2, c.frames, c.sr);
  const irBuffer = ctx.createBuffer(c.irChannels, c.irLength, c.sr); ir.forEach((x, i) => irBuffer.copyToChannel(x, i));
  const inBuffer = ctx.createBuffer(c.inChannels, c.frames, c.sr); inputs.forEach((x, i) => inBuffer.copyToChannel(x, i));
  const source = ctx.createBufferSource(); source.buffer = inBuffer;
  const convolver = ctx.createConvolver(); convolver.buffer = irBuffer;
  source.connect(convolver); convolver.connect(ctx.destination); source.start(0);
  const rendered = await ctx.startRendering();
  // the scale of reverb.cc, computed here only to be written down next to the output
  let power = 0; for (const x of ir) power += sumSquares(x);
  power = Math.sqrt(power / (c.irChannels * c.irLength));
  const scale = (1 / Math.max(power, 0.000125)) * Math.pow(10, -58 / 20) * (44100 / c.sr);
  return { scale, output: [toB64(rendered.getChannelData(0)), toB64(rendered.getChannelData(1))] };
}

async function reverbGenCase(c) {
  let seed = c.seed >>> 0;
  Math.random = () => { seed = (seed * 1664525 + 1013904223) % 4294967296; return seed / 4294967296; };
  const ctx = new OfflineAudioContext(1, 10, c.sr);
  const buffer = await new Promise((resolve) => reverbGen.generateReverb({ audioContext: ctx, numChannels: 2, decayTime: c.d, fadeInTime: c.fade, lpFreqStart: c.lp, lpFreqEnd: c.dim }, resolve));
  return { length: buffer.length, output: [toB64(buffer.getChannelData(0)), toB64(buffer.getChannelData(1))] };
}

async function adjustCase(c) {
  const sr = 48000, length = 1500;
  const ctx = new OfflineAudioContext(2, 10, sr);
  const buffer = ctx.createBuffer(2, length, sr); buffer.copyToChannel(lcg(31, length), 0); buffer.copyToChannel(lcg(32, length), 1);
  const adjusted = ctx.adjustLength(c.duration, buffer, c.speed, c.offset);
  return { length, output: [toB64(adjusted.getChannelData(0)), toB64(adjusted.getChannelData(1))] };
}

async function analyserCase(c) {
  const channels = c.input.length;
  const frames = c.times[c.times.length - 1] + 128;
  const ctx = new OfflineAudioContext(channels, frames, c.sr);
  const buffer = ctx.createBuffer(channels, frames, c.sr);
  c.input.forEach((spec, ch) => buffer.copyToChannel(inputSignal({ ...spec, fftSize: c.fftSize }, frames, c.sr), ch));
  const source = ctx.createBufferSource(); source.buffer = buffer; source.start(0);
  const analyser = ctx.createAnalyser(); analyser.fftSize = c.fftSize; analyser.smoothingTimeConstant = 0.5;
  source.connect(analyser);
  const snapshots = [];
  const freq = new Float32Array(analyser.frequencyBinCount), time = new Float32Array(analyser.frequencyBinCount);
  for (const frame of c.times) {
    ctx.suspend(frame / c.sr).then(() => {
      analyser.getFloatFrequencyData(freq); analyser.getFloatTimeDomainData(time);
      snapshots.push({ frame, frequency: toB64(freq.slice()), time: toB64(time.slice()) });
      ctx.resume();
    });
  }
  await ctx.startRendering();
  // a case that lists keep only writes those snapshots (the others still ran, which is what the smoothing needs)
  return { snapshots: c.keep ? c.keep.map((i) => snapshots[i]) : snapshots };
}

async function automationCase(c) {
  const ctx = new OfflineAudioContext(1, c.frames, c.sr);
  const constant = new ConstantSourceNode(ctx, { offset: 1 });
  const gain = new GainNode(ctx, { gain: c.base });
  constant.connect(gain); gain.connect(ctx.destination); constant.start(0);
  for (const e of c.events) {
    const t = e.frame / c.sr;
    if (e.type === 'cancel') gain.gain.cancelScheduledValues(t);
    else if (e.type === 'set') gain.gain.setValueAtTime(e.value, t);
    else if (e.type === 'lin') gain.gain.linearRampToValueAtTime(e.value, t);
    else if (e.type === 'exp') gain.gain.exponentialRampToValueAtTime(e.value, t);
  }
  const rendered = await ctx.startRendering();
  return { output: [toB64(rendered.getChannelData(0))] };
}

(async () => {
  try {
    const run = async (cases, fn) => { const out = []; for (const c of cases) out.push({ ...c, ...(await fn(c)) }); return out; };
    const result = {
      userAgent: navigator.userAgent,
      delay: await run(${JSON.stringify(delayCases)}, delayCase),
      convolver: await run(${JSON.stringify(convolverCases)}, convolverCase),
      reverbgen: await run(${JSON.stringify(reverbGenCases)}, reverbGenCase),
      adjust: await run(${JSON.stringify(adjustCases)}, adjustCase),
      analyser: await run(${JSON.stringify(analyserCases)}, analyserCase),
      automation: await run(${JSON.stringify(automationCases)}, automationCase),
    };
    await fetch('/result', { method: 'POST', body: JSON.stringify(result) });
  } catch (e) {
    await fetch('/result', { method: 'POST', body: JSON.stringify({ error: String(e) + (e && e.stack) }) });
  }
})();
</script></body></html>`;

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'orbit-oracle-'));
let deliver;
const delivered = new Promise((resolve) => (deliver = resolve));
const server = http.createServer((req, res) => {
  if (req.method === 'POST') {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      res.end('ok');
      deliver(Buffer.concat(chunks).toString('utf8'));
    });
  } else {
    res.setHeader('content-type', 'text/html; charset=utf-8');
    res.end(page);
  }
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const browser = spawn(chromium, ['--headless=new', '--no-sandbox', '--disable-gpu', '--user-data-dir=' + profile, `http://127.0.0.1:${server.address().port}/`], { stdio: 'ignore' });
const timer = setTimeout(() => deliver(JSON.stringify({ error: 'timeout: the page did not answer within 300 s' })), 300000);
const body = JSON.parse(await delivered);
clearTimeout(timer);
browser.kill('SIGKILL');
server.close();
fs.rmSync(profile, { recursive: true, force: true });
if (body.error) throw new Error(body.error);

fs.mkdirSync(outDir, { recursive: true });
const header = { generator: 'tools/strudel-ref/orbit-oracle.mjs', commit: COMMIT, source: 'packages/superdough/{feedbackdelay,reverbGen,reverb}.mjs and Web Audio nodes run in ' + body.userAgent };
const write = (name, cases) => {
  const file = path.join(outDir, `orbit-${name}.json`);
  fs.writeFileSync(file, JSON.stringify({ ...header, cases }) + '\n');
  console.log(path.basename(file), fs.statSync(file).size, 'bytes,', cases.length, 'cases');
};
write('delay', body.delay);
write('convolver', body.convolver);
write('reverbgen', body.reverbgen);
write('adjust', body.adjust);
write('analyser', body.analyser);
write('automation', body.automation);
