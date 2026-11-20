// Golden vectors for the modulators of a voice (Task 3.10): the REAL connectLFO / connectEnvelope / connectBusModulator of the pinned superdough
// (packages/superdough/modulators.mjs, with helpers.mjs, superdoughdata.mjs, noise.mjs, ... served unchanged) driving the REAL lfo-processor and envelope-processor
// worklets (text cut out of worklets.mjs as in worklet-oracle.mjs) in the OfflineAudioContext of headless Chromium. Each target parameter is the `offset` of a
// ConstantSourceNode, so what is rendered is the parameter's value per frame: its own value plus everything connected to it.
//
//   node tools/strudel-ref/modulators-oracle.mjs      # writes engine/src/test/resources/dsp/modulators.json
//
// Two things differ from superdough, both about the page and not about the modulators: audioContext.mjs is replaced by a stub that hands out the offline context
// (the real one also starts a ClockBridge), and the one `webAudioTimeout` of connectBusModulator, which connects the bus modulator to its targets from a
// main-thread timer at `begin`, is replaced by an immediate connection (an offline context renders without giving the main thread a turn); the bus cases use begin 0.
// The file also holds the CONTROL_TARGETS table that superdoughdata.mjs exports. CHROMIUM (default `chromium`), STRUDEL_DIR and DSP_OUT as in the other oracles.
import fs from 'fs';
import http from 'http';
import os from 'os';
import path from 'path';
import { spawn } from 'child_process';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.STRUDEL_DIR ?? path.join(here, 'strudel');
const sd = path.join(root, 'packages/superdough');
const outDir = process.env.DSP_OUT ?? path.join(here, '../../engine/src/test/resources/dsp');
const chromium = process.env.CHROMIUM ?? 'chromium';
const COMMIT = '84ef32eec8074f90c6690ab8d6a6c204f508b182';
const SR = 48000;

// ---- the worklets: the text of the lfo-processor and the envelope-processor, with the helpers they use
const wl = fs.readFileSync(path.join(sd, 'worklets.mjs'), 'utf8').split('\n');
const slice = (a, b) => wl.slice(a - 1, b).join('\n');
const find = (pred) => {
  const i = wl.findIndex(pred);
  if (i < 0) throw new Error('marker not found in worklets.mjs: ' + pred);
  return i;
};
const lfoStart = find((l) => l.startsWith('function polyBlep'));
const lfoEnd = find((l) => l.includes("registerProcessor('lfo-processor'"));
const envStart = find((l) => l.startsWith('class EnvelopeProcessor'));
const envEnd = find((l) => l.includes("registerProcessor('envelope-processor'"));
const workletText =
  `const PI = Math.PI; const TWO_PI = 2 * PI; const INVSR = 1 / sampleRate;
const clamp = (num, min, max) => Math.min(Math.max(num, min), max);
const pv = (arr, n) => arr[n] ?? arr[0];
const ffloor = (x) => x | 0;
const ffrac = (x) => x - ffloor(x);
` +
  slice(lfoStart + 1, lfoEnd + 1) + '\n' + slice(envStart + 1, envEnd + 1);

// ---- the real modulators.mjs, with its one main-thread timer made immediate
let modulatorsText = fs.readFileSync(path.join(sd, 'modulators.mjs'), 'utf8');
const timer = /webAudioTimeout\(\s*ac,\s*\(\) => \{\s*targetParams\.forEach\(\(t\) => modulator\.connect\(t\)\);\s*\},\s*0,\s*params\.begin,\s*\);/;
if (!timer.test(modulatorsText)) throw new Error('modulators.mjs is not the pinned version: the webAudioTimeout of connectBusModulator was not found');
modulatorsText = modulatorsText.replace(timer, 'targetParams.forEach((t) => modulator.connect(t));');

const controlTargets = (await import(path.join(sd, 'superdoughdata.mjs'))).getSuperdoughControlTargets();

// ---- the cases
function lcg(seed, n, amp) {
  const out = new Float32Array(n);
  let x = seed >>> 0;
  for (let i = 0; i < n; i++) {
    x = (x * 1664525 + 1013904223) % 4294967296;
    out[i] = (x / 2147483648 - 1) * amp;
  }
  return out;
}
const b64 = (a) => Buffer.from(Float32Array.from(a).buffer).toString('base64');
const FRAMES = SR / 4; // a quarter of a second of every case...
const STRIDE = 4; // ...of which every 4th frame is kept (the 128-frame structure of the worklets is well resolved by that)
const busLeft = lcg(7, FRAMES, 0.4);
const busRight = lcg(8, FRAMES, 0.3);

const lfo = (id, params) => ({ kind: 'lfo', id, params });
const env = (id, params) => ({ kind: 'env', id, params });
const bmod = (id, params) => ({ kind: 'bmod', id, params });
const cases = [
  { name: 'lfo default on a cutoff', targets: [{ node: 'lpf', param: 'frequency', base: 1000 }], cps: 0.5, cycle: 0, begin: 0, end: 10, mods: [lfo(0, { control: 'cutoff' })] },
  { name: 'lfo sine, sync, skew, curve on a gain', targets: [{ node: 'gain', param: 'gain', base: 0.5 }], cps: 0.75, cycle: 3.25, begin: 0.0123, end: 0.9, mods: [lfo(0, { control: 'gain', sync: 2, shape: 'sine', depth: 2, dcoffset: 0, skew: 0.3, curve: 1.5 })] },
  { name: 'lfo retrig, depthabs, square', targets: [{ node: 'gain', param: 'gain', base: 0.25 }], cps: 0.5, cycle: 1.7, begin: 0.05, end: 10, mods: [lfo(0, { control: 'gain', retrig: 1, depthabs: 0.7, shape: 'square', rate: 4, skew: 0.4 })] },
  { name: 'lfo shape number and phase offset', targets: [{ node: 'gain', param: 'gain', base: 1 }], cps: 0.5, cycle: 0.6, begin: 0, end: 10, mods: [lfo(0, { control: 'gain', shape: 7, rate: 2.5, phaseoffset: 0.3, depthabs: 0.5 })] },
  { name: 'lfo on a frequency is clamped to 20..24000', targets: [{ node: 'lpf', param: 'frequency', base: 1000 }], cps: 0.5, cycle: 0, begin: 0, end: 10, mods: [lfo(0, { control: 'cutoff', depthabs: 1e9, dcoffset: 0, rate: 3 })] },
  { name: 'lfo on a low frequency is not clamped', targets: [{ node: 'tremolo', param: 'frequency', base: 20 }], cps: 0.5, cycle: 0, begin: 0, end: 10, mods: [lfo(0, { control: 'tremolo', depthabs: 1000, dcoffset: 0, rate: 3 })] },
  { name: 'lfo on a target of zero', targets: [{ node: 'gain', param: 'gain', base: 0 }], cps: 0.5, cycle: 0, begin: 0, end: 10, mods: [lfo(0, { control: 'gain', rate: 2 })] },
  { name: 'lfo ending early', targets: [{ node: 'gain', param: 'gain', base: 1 }], cps: 0.5, cycle: 0, begin: 0.05, end: 0.15, mods: [lfo(0, { control: 'gain', rate: 12, dcoffset: 1, depthabs: 1 })] },
  { name: 'lfo on an lfo frequency', targets: [{ node: 'gain', param: 'gain', base: 0.5 }], cps: 0.5, cycle: 0, begin: 0, end: 10, mods: [lfo(0, { control: 'gain', rate: 2, depthabs: 0.5, shape: 'sine' }), lfo(1, { control: 'lfo_0', rate: 0.7, depthabs: 6, dcoffset: 0, shape: 'ramp' })] },
  { name: 'lfo on an lfo depth and skew', targets: [{ node: 'gain', param: 'gain', base: 0.5 }], cps: 0.5, cycle: 0, begin: 0, end: 10, mods: [lfo(0, { control: 'gain', rate: 3, shape: 'tri' }), lfo(1, { control: 'lfo_0', subControl: 'depth', rate: 1.5, depthabs: 0.4, shape: 'sine' }), lfo(2, { control: 'lfo_0', subControl: 'skew', rate: 1, depthabs: 0.2, dcoffset: 0, shape: 'sine' })] },
  { name: 'env on a cutoff', targets: [{ node: 'lpf', param: 'frequency', base: 800 }], cps: 0.5, cycle: 0, begin: 0.0123, end: 0.2, mods: [env(0, { control: 'cutoff', attack: 0.05, decay: 0.04, sustain: 0.4, release: 0.06, depth: 2 })] },
  { name: 'env with curves on a gain', targets: [{ node: 'gain', param: 'gain', base: 0.5 }], cps: 0.5, cycle: 0, begin: 0.02, end: 0.2, mods: [env(0, { control: 'gain', attack: 0.04, decay: 0.05, sustain: 0.3, release: 0.03, acurve: 0.5, dcurve: -0.3, rcurve: 0.8, depthabs: 0.4 })] },
  { name: 'env defaults', targets: [{ node: 'gain', param: 'gain', base: 0.5 }], cps: 0.5, cycle: 0, begin: 0.0003, end: 1, mods: [env(0, { control: 'gain' })] },
  // The envelope cases do not begin at exactly 0: the worklet never triggers an envelope whose begin equals its initial beginTime 0 (ModEnvelope deliberately differs there).
  { name: 'env on a frequency is clamped', targets: [{ node: 'lpf', param: 'frequency', base: 1000 }], cps: 0.5, cycle: 0, begin: 0.0001, end: 10, mods: [env(0, { control: 'cutoff', attack: 0.01, depthabs: 1e9 })] },
  { name: 'env on an lfo rate', targets: [{ node: 'gain', param: 'gain', base: 0.5 }], cps: 0.5, cycle: 0, begin: 0.0001, end: 10, mods: [lfo(0, { control: 'gain', rate: 8, depthabs: 0.5 }), env(1, { control: 'lfo_0', attack: 0.1, depthabs: 20 })] },
  { name: 'bus on a gain', targets: [{ node: 'gain', param: 'gain', base: 0.5 }], bus: true, cps: 0.5, cycle: 0, begin: 0, end: 10, mods: [bmod(0, { control: 'gain', bus: 1, dc: 0.05 })] },
  // clampWithWaveShaper returns the node it was given, not the shaper, so the real bus modulator of a frequency is NOT clamped (this case shows it)
  { name: 'bus on a frequency is not clamped', targets: [{ node: 'lpf', param: 'frequency', base: 1000 }], bus: true, cps: 0.5, cycle: 0, begin: 0, end: 10, mods: [bmod(0, { control: 'cutoff', bus: 1, depth: 2, dc: 0.1 })] },
  { name: 'bus with a negative absolute depth', targets: [{ node: 'gain', param: 'gain', base: 0.5 }], bus: true, cps: 0.5, cycle: 0, begin: 0, end: 10, mods: [bmod(0, { control: 'gain', bus: 1, depthabs: -0.6 })] },
];
for (const c of cases) c.frames = FRAMES;

const page = `<!doctype html><html><body><script type="module">
globalThis.process = { env: {} };
const CASES = ${JSON.stringify(cases)};
const SR = ${SR};
const BUS_LEFT = ${JSON.stringify(b64(busLeft))};
const BUS_RIGHT = ${JSON.stringify(b64(busRight))};
const toFloats = (b64) => { const bin = atob(b64); const u = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return new Float32Array(u.buffer); };
const toB64 = (f) => { const u = new Uint8Array(f.buffer, f.byteOffset, f.byteLength); let s = ''; for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]); return btoa(s); };
const post = (body) => fetch('/result', { method: 'POST', body: JSON.stringify(body) });
try {
  const { connectLFO, connectEnvelope, connectBusModulator } = await import('/sd/modulators.mjs');
  const results = [];
  for (const c of CASES) {
    const ctx = new OfflineAudioContext(c.targets.length, c.frames, SR);
    globalThis.__ac = ctx;
    await ctx.audioWorklet.addModule('/worklets.js');
    const merger = ctx.createChannelMerger(c.targets.length);
    merger.connect(ctx.destination);
    const nodes = { main: {} };
    c.targets.forEach((t, i) => {
      const source = new ConstantSourceNode(ctx, { offset: t.base });
      source.connect(merger, 0, i);
      source.start(0);
      (nodes.main[t.node] ??= []).push({ [t.param]: source.offset });
    });
    let controller;
    if (c.bus) {
      const bus = new GainNode(ctx, { gain: 1, channelCount: 2, channelCountMode: 'explicit' });
      const buffer = ctx.createBuffer(2, c.frames, SR);
      buffer.copyToChannel(toFloats(BUS_LEFT), 0);
      buffer.copyToChannel(toFloats(BUS_RIGHT), 1);
      const player = ctx.createBufferSource();
      player.buffer = buffer;
      player.connect(bus);
      player.start(0);
      controller = { getBus: () => bus };
    }
    for (const m of c.mods) {
      const params = { ...m.params, begin: c.begin, end: c.end };
      if (m.kind === 'lfo') connectLFO(m.id, { ...params, cps: c.cps, cycle: c.cycle }, nodes);
      else if (m.kind === 'env') connectEnvelope(m.id, params, nodes);
      else connectBusModulator(params, nodes, controller);
    }
    const rendered = await ctx.startRendering();
    results.push({ name: c.name, output: Array.from({ length: c.targets.length }, (_, ch) => toB64(rendered.getChannelData(ch).filter((_, i) => i % ${STRIDE} === 0))) });
  }
  await post({ userAgent: navigator.userAgent, results });
} catch (e) {
  await post({ error: String(e) + (e && e.stack) });
}
</script></body></html>`;

const served = new Set(['helpers.mjs', 'logger.mjs', 'noise.mjs', 'nodePools.mjs', 'util.mjs', 'superdoughdata.mjs']);
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'modulators-oracle-'));
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
    return;
  }
  const js = (text) => {
    res.setHeader('content-type', 'text/javascript');
    res.end(text);
  };
  const m = /^\/sd\/([\w.]+)$/.exec(req.url);
  if (req.url === '/worklets.js') js(workletText);
  else if (req.url === '/sd/audioContext.mjs') js('export const getAudioContext = () => globalThis.__ac;');
  else if (req.url === '/sd/modulators.mjs') js(modulatorsText);
  else if (m && served.has(m[1])) js(fs.readFileSync(path.join(sd, m[1]), 'utf8'));
  else {
    res.setHeader('content-type', 'text/html; charset=utf-8');
    res.end(page);
  }
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const browser = spawn(chromium, ['--headless=new', '--no-sandbox', '--disable-gpu', '--user-data-dir=' + profile, `http://127.0.0.1:${server.address().port}/`], { stdio: 'ignore' });
const timer2 = setTimeout(() => deliver(JSON.stringify({ error: 'timeout: the page did not answer within 300 s' })), 300000);
const body = JSON.parse(await delivered);
clearTimeout(timer2);
browser.kill('SIGKILL');
server.close();
fs.rmSync(profile, { recursive: true, force: true });
if (body.error) throw new Error(body.error);

const doc = {
  generator: 'tools/strudel-ref/modulators-oracle.mjs',
  commit: COMMIT,
  source: 'packages/superdough/modulators.mjs, helpers.mjs, superdoughdata.mjs and the lfo/envelope processors of worklets.mjs, run in ' + body.userAgent,
  sampleRate: SR,
  stride: STRIDE,
  controlTargets,
  busLeft: b64(busLeft),
  busRight: b64(busRight),
  cases: cases.map((c, i) => ({
    name: c.name,
    targets: c.targets,
    bus: c.bus ?? false,
    cps: c.cps,
    cycle: c.cycle,
    begin: c.begin,
    end: c.end,
    mods: c.mods,
    frames: c.frames,
    output: body.results[i].output,
  })),
};
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'modulators.json'), JSON.stringify(doc) + '\n');
console.log('modulators.json', fs.statSync(path.join(outDir, 'modulators.json')).size, 'bytes,', cases.length, 'cases,', Object.keys(controlTargets).length, 'control targets,', body.userAgent);
