// Renders the effects of superdough's voice chain that are Web Audio nodes rather than worklets, in headless Chromium's OfflineAudioContext, and writes the result as golden
// vectors for cosic.audio.{CompressorStage, PhaserStage, TremoloStage} (Task 3.8):
//
//   node tools/strudel-ref/stagefx-oracle.mjs      # writes engine/src/test/resources/dsp/stagefx.json
//
// The graph of every case is built by the REAL source text of the pinned superdough, cut out by marker lines (a different checkout fails loudly instead of running something
// else): `getCompressor`, `getLfo`, `getPhaser`, `Chain` and the tremolo / compressor / panning / phaser blocks of `superdough()` itself (superdough.mjs:818-885). Only the
// context plumbing is replaced (`getAudioContext`, no node pool). The LFO is the real `lfo-processor` worklet text, loaded into the AudioWorkletGlobalScope of the browser.
//
// CHROMIUM (default `chromium`), STRUDEL_DIR and DSP_OUT as in vowel-oracle.mjs. The page is served from a local http server, which also receives the result (virtual-time
// `--dump-dom` does not wait for audio rendering).
import fs from 'fs';
import http from 'http';
import os from 'os';
import path from 'path';
import { spawn } from 'child_process';
import { fileURLToPath } from 'url';
import { prelude, superdoughDir } from './worklet-sources.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const outDir = process.env.DSP_OUT ?? path.join(here, '../../engine/src/test/resources/dsp');
const chromium = process.env.CHROMIUM ?? 'chromium';
const COMMIT = '84ef32eec8074f90c6690ab8d6a6c204f508b182';

const lines = (file) => fs.readFileSync(path.join(superdoughDir, file), 'utf8').split('\n');
const W = lines('worklets.mjs');
const H = lines('helpers.mjs');
const S = lines('superdough.mjs');
const find = (src, pred, from = 0) => {
  const i = src.findIndex((l, j) => j >= from && pred(l));
  if (i < 0) throw new Error('marker not found: ' + pred);
  return i;
};
/** From the line matching [start] through the first later line equal to [close] (default `}`), as text. */
const block = (src, start, close = '}') => {
  const a = find(src, start);
  const b = find(src, (l) => l === close, a);
  return src.slice(a, b + 1).join('\n');
};
/** The lines from the one matching [start] up to (excluding) the one matching [stop], as text. */
const until = (src, start, stop) => {
  const a = find(src, start);
  return src.slice(a, find(src, stop, a + 1)).join('\n');
};

const helperText = [
  block(H, (l) => l.startsWith('export function gainNode(')),
  block(H, (l) => l.startsWith('export function getWorklet(')),
  block(H, (l) => l.startsWith('function getModulationShapeInput(')),
  block(H, (l) => l.startsWith('export function getLfo(')),
  block(H, (l) => l.startsWith('export function getCompressor(')),
  block(S, (l) => l.startsWith('function getPhaser(')),
  block(S, (l) => l.startsWith('class Chain {')),
]
  .join('\n')
  .replaceAll('export function', 'function');

const tremoloBlock = until(S, (l) => l === '    let tremolo = fx.tremolo;', (l) => l === '    if (fx.compressor !== undefined) {');
const compressorBlock = until(S, (l) => l === '    if (fx.compressor !== undefined) {', (l) => l === '    // panning');
const panBlock = until(S, (l) => l === '    // panning', (l) => l === '    // phaser');
const phaserBlock = until(S, (l) => l === '    // phaser', (l) => l === '    // delay');

const lfoWorklet = [
  prelude,
  W.slice(
    find(W, (l) => l.startsWith('function polyBlep')),
    find(W, (l) => l.includes("registerProcessor('lfo-processor'")) + 1,
  ).join('\n'),
].join('\n');

// ------------------------------------------------------------------------------------------------ signals
// The inputs are described, not stored: the Kotlin test regenerates them with the same formulas (`StageFxGolden.signal`), which keeps the golden file small.
function lcg(seed, n, amp) {
  const out = new Float32Array(n);
  let x = seed >>> 0;
  for (let i = 0; i < n; i++) {
    x = (x * 1664525 + 1013904223) % 4294967296;
    out[i] = (x / 2147483648 - 1) * amp;
  }
  return out;
}
/** A kick-like burst train: a decaying sine of `f0` Hz restarted every `period` seconds, plus a little LCG noise. */
function bursts(n, sr, { f0 = 90, decay = 0.04, period = 0.1, amp = 0.9, noise = 0.05, seed = 1, phase = 0 }) {
  const noiseSignal = lcg(seed, n, noise);
  const out = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const tau = (i / sr) % period;
    out[i] = amp * Math.sin(2 * Math.PI * f0 * tau + phase) * Math.exp(-tau / decay) + noiseSignal[i];
  }
  return out;
}
function signal(spec, n, sr) {
  switch (spec.kind) {
    case 'lcg':
      return lcg(spec.seed, n, spec.amp);
    case 'dc':
      return new Float32Array(n).fill(spec.value);
    case 'sine':
      return Float32Array.from({ length: n }, (_, i) => spec.amp * Math.sin((2 * Math.PI * spec.f * i) / sr));
    case 'bursts':
      return bursts(n, sr, spec);
  }
  throw new Error('unknown signal ' + JSON.stringify(spec));
}
const concat = (a, b) => {
  const out = new Float32Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
};
const b64 = (a) => Buffer.from(Float32Array.from(a).buffer).toString('base64');
const dcs = (value) => ({ kind: 'dc', value });
const noise = (seed, amp) => ({ kind: 'lcg', seed, amp });
const kick = (o) => ({ kind: 'bursts', ...o });
const sines = (f, amp) => ({ kind: 'sine', f, amp });

// ------------------------------------------------------------------------------------------------ cases
// `stages` are the superdough blocks to run, in chain order; `fx` is the control map of the event as superdough() sees it (undefined keys are absent).
const cases = [];
const add = (c) => {
  const sampleRate = c.sampleRate ?? 48000;
  const frames = c.sampleRate === 44100 ? 4410 : 4800;
  const lead = c.lead ?? 0;
  const body = c.signals.map((s) => signal(s, frames, sampleRate));
  cases.push({ sampleRate, cps: 0.5, cycle: 0, t: 0, endWithRelease: 100, lead, frames, inputChannels: c.signals.length, ...c, input: body.map((x) => (lead ? concat(new Float32Array(lead), x) : x)) });
};

const hard = { compressor: -20, compressorRatio: 10, compressorKnee: 0, compressorAttack: 0.001, compressorRelease: 0.05 };
add({ name: 'compressor: DC 1.0, -20 dB 10:1 hard knee, 1 ms attack', stages: ['compressor'], signals: [dcs(1)], fx: hard });
add({ name: 'compressor: DC 0.5, same settings', stages: ['compressor'], signals: [dcs(0.5)], fx: hard });
add({ name: 'compressor: bursts mono, the defaults of getCompressor (threshold only)', stages: ['compressor'], signals: [kick({ seed: 3, period: 0.04 })], fx: { compressor: -3 } });
add({ name: 'compressor: bursts mono, -24 dB 6:1 knee 12 attack 4 ms release 80 ms', stages: ['compressor'], signals: [kick({ seed: 4, period: 0.035 })], fx: { compressor: -24, compressorRatio: 6, compressorKnee: 12, compressorAttack: 0.004, compressorRelease: 0.08 } });
add({ name: 'compressor: noise stereo, -30 dB 4:1 knee 20 attack 10 ms release 200 ms', stages: ['compressor'], signals: [noise(11, 0.8), noise(12, 0.6)], fx: { compressor: -30, compressorRatio: 4, compressorKnee: 20, compressorAttack: 0.01, compressorRelease: 0.2 } });
add({ name: 'compressor: bursts stereo, extreme settings (-100 dB 20:1 knee 40 attack 0 release 0)', stages: ['compressor'], signals: [kick({ seed: 5, period: 0.04 }), kick({ seed: 6, f0: 140, phase: 1, period: 0.04 })], fx: { compressor: -100, compressorRatio: 20, compressorKnee: 40, compressorAttack: 0, compressorRelease: 0 } });
add({ name: 'compressor: sine 440 mono, out of range settings (clamped by the AudioParams)', stages: ['compressor'], signals: [sines(440, 0.9)], fx: { compressor: 12, compressorRatio: 100, compressorKnee: -5, compressorAttack: 5, compressorRelease: 9 } });
add({ name: 'compressor: bursts mono at 44.1 kHz', stages: ['compressor'], sampleRate: 44100, signals: [kick({ seed: 7, period: 0.04 })], fx: { compressor: -20, compressorRatio: 8, compressorKnee: 6, compressorAttack: 0.003, compressorRelease: 0.1 } });
// A voice whose compressor has been fed silence for a second: the state a recycled (pooled) node is in. The golden is what follows the lead-in.
add({ name: 'compressor: bursts mono after one second of silence', stages: ['compressor'], lead: 48000, signals: [kick({ seed: 8, period: 0.04 })], fx: hard });
add({ name: 'compressor: bursts stereo after one second of silence', stages: ['compressor'], lead: 48000, signals: [kick({ seed: 9, period: 0.04 }), kick({ seed: 10, f0: 120, period: 0.04 })], fx: { compressor: -18, compressorRatio: 5, compressorKnee: 8, compressorAttack: 0.002, compressorRelease: 0.06 } });
// pan after the compressor: the compressor makes a mono voice stereo, so the stereo-input law of the panner applies (no -3 dB in the middle).
const panned = { compressor: -20, compressorRatio: 6, compressorKnee: 10, compressorAttack: 0.002, compressorRelease: 0.06 };
add({ name: 'compressor then pan 0.5 (mono in)', stages: ['compressor', 'pan'], signals: [kick({ seed: 13, period: 0.04 })], fx: { ...panned, pan: 0.5 }, bothChannels: true });
add({ name: 'compressor then pan 0.9 (mono in)', stages: ['compressor', 'pan'], signals: [kick({ seed: 14, period: 0.04 })], fx: { ...panned, pan: 0.9 }, bothChannels: true });

const T0 = 504 / 48000; // a voice that starts in the middle of a render quantum
add({ name: 'tremolo: 12 Hz, depth 1, default skew (a rising ramp) on DC', stages: ['tremolo'], t: T0, signals: [dcs(1)], fx: { tremolo: 12 } });
add({ name: 'tremolo: 9 Hz, depth 0.5 on noise', stages: ['tremolo'], t: T0, signals: [noise(21, 0.8)], fx: { tremolo: 9, tremolodepth: 0.5 } });
add({ name: 'tremolo: shape "sine", depth 0.8 (skew 0.5), cycle 1.75 at cps 0.5', stages: ['tremolo'], t: T0, cycle: 1.75, signals: [dcs(1)], fx: { tremolo: 22, tremolodepth: 0.8, tremoloshape: 'sine' } });
add({ name: 'tremolo: tremolosync 48 at cps 0.5, skew 0.25, phase 0.3, square (shape 4)', stages: ['tremolo'], t: T0, cycle: 0.3, signals: [dcs(1)], fx: { tremolosync: 48, tremoloskew: 0.25, tremolophase: 0.3, tremoloshape: 4, tremolodepth: 1 } });
add({ name: 'tremolo: stereo noise, 15 Hz, depth 1.5 (clipped), cps 0.6 cycle 3.25', stages: ['tremolo'], t: T0, cps: 0.6, cycle: 3.25, signals: [noise(22, 0.7), noise(23, 0.7)], fx: { tremolo: 15, tremolodepth: 1.5 } });
add({ name: 'tremolo: ends inside the window (endWithRelease 0.06 s), saw shape 3', stages: ['tremolo'], t: T0, endWithRelease: 0.06, signals: [dcs(1)], fx: { tremolo: 20, tremolodepth: 0.7, tremoloshape: 'saw' } });
add({ name: 'tremolo: triangle shape 0 with a given skew 0.7', stages: ['tremolo'], t: T0, signals: [dcs(1)], fx: { tremolo: 26, tremoloshape: 0, tremoloskew: 0.7 } });

add({ name: 'phaser: rate 12, defaults (depth 0.75, center 1000, sweep 2000)', stages: ['phaser'], t: T0, signals: [noise(31, 0.8)], fx: { phaserrate: 12 } });
add({ name: 'phaser: stereo noise, rate 9, depth 0.9, center 800, sweep 1000', stages: ['phaser'], t: T0, signals: [noise(32, 0.7), noise(33, 0.7)], fx: { phaserrate: 9, phaserdepth: 0.9, phasercenter: 800, phasersweep: 1000 }, phaserdepth: 0.9 });
add({ name: 'phaser: rate 30 Hz, sweep 4000 cents (the notch reaches 12 kHz)', stages: ['phaser'], t: T0, signals: [noise(34, 0.8)], fx: { phaserrate: 30, phasersweep: 4000 } });
add({ name: 'phaser: 44.1 kHz, depth 0.3 (Q 1.4), rate 14', stages: ['phaser'], sampleRate: 44100, t: T0, signals: [noise(35, 0.8)], fx: { phaserrate: 14, phaserdepth: 0.3 }, phaserdepth: 0.3 });
add({ name: 'phaser: the LFO ends inside the window (endWithRelease 0.06 s)', stages: ['phaser'], t: T0, endWithRelease: 0.06, signals: [noise(36, 0.8)], fx: { phaserrate: 16, phasersweep: 3000 } });
add({ name: 'phaser: sweep 0 and a high center (static notch at 5282 Hz)', stages: ['phaser'], t: T0, signals: [noise(37, 0.8)], fx: { phaserrate: 3, phasercenter: 5000, phasersweep: 0 } });
add({ name: 'phaser: depth 1 (Q 0.1) with a sine in the notch', stages: ['phaser'], t: T0, signals: [sines(1282, 0.8)], fx: { phaserrate: 4, phaserdepth: 1 }, phaserdepth: 1 });

// ------------------------------------------------------------------------------------------------ the page
const page = `<!doctype html><html><body><script>
const getAudioContext = () => globalThis.__ac;
const getNodeFromPool = (key, factory) => factory();
const isPoolable = () => false, releaseNodeToPool = () => {}, releaseAudioNode = () => {};
${helperText}
const BLOCKS = {
  tremolo: new Function('ac', 'chain', 'fx', 'fxNodes', 'cps', 'cycle', 't', 'endWithRelease', 'tremolodepth', 'getLfo', ${JSON.stringify(tremoloBlock)}),
  compressor: new Function('ac', 'chain', 'fx', 'fxNodes', 'getCompressor', ${JSON.stringify(compressorBlock)}),
  pan: new Function('ac', 'chain', 'fx', 'fxNodes', ${JSON.stringify(panBlock)}),
  phaser: new Function('ac', 'chain', 'fx', 'fxNodes', 't', 'endWithRelease', 'phaserdepth', 'getPhaser', ${JSON.stringify(phaserBlock)}),
};
const CASES = ${JSON.stringify(cases.map((c) => ({ ...c, input: c.input.map(b64) })))};
const toFloats = (b64) => { const bin = atob(b64); const u = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return new Float32Array(u.buffer); };
const toB64 = (f) => { const u = new Uint8Array(f.buffer, f.byteOffset, f.byteLength); let s = ''; for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000)); return btoa(s); };
async function render(c) {
  const total = c.lead + c.frames;
  const ac = new OfflineAudioContext(2, total, c.sampleRate);
  globalThis.__ac = ac;
  await ac.audioWorklet.addModule('/lfo-worklet.js');
  const chain = new Chain();
  const fxNodes = {};
  const buffer = ac.createBuffer(c.inputChannels, total, c.sampleRate);
  c.input.forEach((b, ch) => buffer.copyToChannel(toFloats(b), ch));
  const source = ac.createBufferSource();
  source.buffer = buffer;
  chain.connect(source);
  source.start(0);
  const fx = c.fx;
  for (const stage of c.stages) {
    if (stage === 'tremolo') BLOCKS.tremolo(ac, chain, fx, fxNodes, c.cps, c.cycle, c.t, c.endWithRelease, fx.tremolodepth ?? 1, getLfo);
    else if (stage === 'compressor') BLOCKS.compressor(ac, chain, fx, fxNodes, getCompressor);
    else if (stage === 'pan') BLOCKS.pan(ac, chain, fx, fxNodes);
    else if (stage === 'phaser') BLOCKS.phaser(ac, chain, fx, fxNodes, c.t, c.endWithRelease, c.phaserdepth ?? 0.75, getPhaser);
    else throw new Error('unknown stage ' + stage);
  }
  chain.tails.forEach((n) => n.connect(ac.destination));
  const rendered = await ac.startRendering();
  const left = rendered.getChannelData(0).subarray(c.lead);
  const right = rendered.getChannelData(1).subarray(c.lead);
  let identical = true;
  for (let i = 0; i < left.length; i++) if (left[i] !== right[i]) { identical = false; break; }
  const keep = c.bothChannels || c.inputChannels === 2 ? [left, right] : [left];
  return { name: c.name, lrIdentical: identical, output: keep.map(toB64) };
}
// Does a compressor with a mono input output one channel or two? (A splitter after it shows what the node itself produces.)
async function probeChannels() {
  const frames = 4800;
  const ac = new OfflineAudioContext(2, frames, 48000);
  globalThis.__ac = ac;
  const buffer = ac.createBuffer(1, frames, 48000);
  buffer.copyToChannel(Float32Array.from({ length: frames }, (_, i) => 0.5 * Math.sin(i * 0.05)), 0);
  const source = ac.createBufferSource();
  source.buffer = buffer;
  const compressor = getCompressor(ac, -20, 10, 5, 0.003, 0.05);
  const splitter = ac.createChannelSplitter(2);
  const merger = ac.createChannelMerger(2);
  source.connect(compressor);
  compressor.connect(splitter);
  splitter.connect(merger, 0, 0);
  splitter.connect(merger, 1, 1);
  merger.connect(ac.destination);
  source.start(0);
  const rendered = await ac.startRendering();
  const energy = (ch) => { let s = 0; const d = rendered.getChannelData(ch); for (let i = 0; i < d.length; i++) s += d[i] * d[i]; return s; };
  return { monoInputOutputsTwoChannels: energy(1) > 0 };
}
(async () => {
  try {
    const results = [];
    for (const c of CASES) results.push(await render(c));
    const probe = await probeChannels();
    await fetch('/result', { method: 'POST', body: JSON.stringify({ userAgent: navigator.userAgent, results, probe }) });
  } catch (e) {
    await fetch('/result', { method: 'POST', body: JSON.stringify({ error: String(e) + (e && e.stack) }) });
  }
})();
</script></body></html>`;

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'stagefx-oracle-'));
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
  } else if (req.url === '/lfo-worklet.js') {
    res.setHeader('content-type', 'text/javascript');
    res.end(lfoWorklet);
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
const { userAgent, results, probe } = body;

const doc = {
  generator: 'tools/strudel-ref/stagefx-oracle.mjs',
  commit: COMMIT,
  source: 'packages/superdough getCompressor / getLfo / getPhaser and the tremolo, compressor, panning and phaser blocks of superdough(), run in ' + userAgent,
  probe,
  cases: cases.map((c, i) => ({
    name: c.name,
    stages: c.stages,
    sampleRate: c.sampleRate,
    channels: c.inputChannels,
    frames: c.frames,
    cps: c.cps,
    cycle: c.cycle,
    t: c.t,
    endWithRelease: c.endWithRelease,
    lead: c.lead,
    fx: c.fx,
    signals: c.signals,
    lrIdentical: results[i].lrIdentical,
    output: results[i].output,
  })),
};
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'stagefx.json'), JSON.stringify(doc) + '\n');
console.log('stagefx.json', fs.statSync(path.join(outDir, 'stagefx.json')).size, 'bytes,', cases.length, 'cases,', userAgent, JSON.stringify(probe));
for (const r of results) console.log(r.lrIdentical ? 'L=R ' : 'L!=R', r.name);
