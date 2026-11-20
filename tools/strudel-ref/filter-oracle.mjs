// Renders superdough's filter stage in headless Chromium and writes golden vectors for cosic.audio.Biquad and cosic.audio.FilterStage (Task 3.7):
//
//   node tools/strudel-ref/filter-oracle.mjs      # writes engine/src/test/resources/dsp/filter.json
//
// Two kinds of case, both rendered by a real OfflineAudioContext:
//   * "biquad": a plain BiquadFilterNode (type, frequency, Q, detune) on noise, which pins down Chromium's design formulas, special cases and arithmetic.
//   * "stage": the REAL `createFilter` of packages/superdough/helpers.mjs (its source text, cut out below) with the real `lfo-processor` and `ladder-processor`
//     worklets of worklets.mjs, wired like superdough.mjs:682-788 (the lpMap/hpMap/bpMap of that file, a second filter for ftype '24db'). Envelope times are multiples
//     of 1/128 s (375 frames at 48 kHz) so that every AudioParam event lands exactly on a frame, which Chromium rounds up to and the Kotlin timeline does not.
// CHROMIUM (default `chromium`), STRUDEL_DIR and DSP_OUT as in the other oracles.
import fs from 'fs';
import http from 'http';
import os from 'os';
import path from 'path';
import { spawn } from 'child_process';
import { fileURLToPath } from 'url';
import { workletModuleText } from './worklet-sources.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.STRUDEL_DIR ?? path.join(here, 'strudel');
const outDir = process.env.DSP_OUT ?? path.join(here, '../../engine/src/test/resources/dsp');
const chromium = process.env.CHROMIUM ?? 'chromium';
const COMMIT = '84ef32eec8074f90c6690ab8d6a6c204f508b182';
const sd = path.join(root, 'packages/superdough');
const read = (f) => fs.readFileSync(path.join(sd, f), 'utf8');

// ---- source text of the real code -----------------------------------------------------------------------------------------------------------------------------
const lines = (text) => text.split('\n');
const sliceBetween = (text, startPred, endPred) => {
  const L = lines(text);
  const a = L.findIndex(startPred);
  if (a < 0) throw new Error('marker not found: ' + startPred);
  const b = L.findIndex((l, i) => i > a && endPred(l));
  if (b < 0) throw new Error('end marker not found: ' + endPred);
  return L.slice(a, b).join('\n');
};
const stripExport = (s) => s.replace(/^export /gm, '');

const helpers = stripExport(
  sliceBetween(read('helpers.mjs'), (l) => l.startsWith('const getSlope'), (l) => l.startsWith('// stays 1 until .5')),
);
const utilText = read('util.mjs');
const utilPieces = stripExport(
  [
    utilText.match(/export const clamp = .*;/)[0],
    sliceBetween(utilText, (l) => l.startsWith('export function nanFallback'), (l) => l.startsWith('// modulo that works')).replace('!silent && logger(', '!silent && (() => {})('),
    utilText.match(/export const _mod = .*;/)[0],
    utilText.match(/export const pickAndRename = [\s\S]*?\n};/)[0],
  ].join('\n'),
);
const sdText = read('superdough.mjs');
const getFilterType = sdText.match(/function getFilterType\(ftype\) \{[\s\S]*?\n\}/)[0];
const maps = ['lpMap', 'hpMap', 'bpMap'].map((n) => sdText.match(new RegExp('const ' + n + ' = \\{[\\s\\S]*?\\};'))[0]).join('\n');

// worklets.mjs: the LFO (its waveshapes) on top of what worklet-sources.mjs provides
const W = read('worklets.mjs');
const lfoText = sliceBetween(W, (l) => l.startsWith('// Optimized per-voice detuner'), (l) => l.includes("registerProcessor('lfo-processor'")) + "\nregisterProcessor('lfo-processor', LFOProcessor);";
const workletModule = workletModuleText() + '\n' + lfoText;

// ---- cases ------------------------------------------------------------------------------------------------------------------------------------------------------
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
const SIGNAL_FRAMES = 6144;
const signals = { noiseA: lcg(101, SIGNAL_FRAMES, 0.5), noiseB: lcg(102, SIGNAL_FRAMES, 0.5) };

const BIQUAD_FRAMES = 1536;
const biquad = [];
const addBiquad = (type, frequency, q, extra = {}) => {
  const c = { type, frequency, q, detune: 0, sampleRate: 48000, channels: 1, frames: BIQUAD_FRAMES, ...extra };
  c.name = `${type} f${frequency} q${q}${c.detune ? ' detune' + c.detune : ''}${c.sampleRate !== 48000 ? ' ' + c.sampleRate : ''}${c.channels === 2 ? ' stereo' : ''}`;
  c.signals = c.channels === 2 ? ['noiseA', 'noiseB'] : ['noiseA'];
  biquad.push(c);
};
for (const [f, q] of [[1000, 0], [1000, 1], [1000, -3.0103], [1000, 10], [100, 30], [5000, 20], [8000, -10], [30, 40], [23999, 5], [24000, 5], [0, 3], [30000, 3], [12000, 6], [440, -20]]) addBiquad('lowpass', f, q);
for (const [f, q] of [[1000, 0], [125, 5], [4000, 15], [16000, 1], [60, 35], [0, 2], [24000, 2], [-50, 2], [1000, -6]]) addBiquad('highpass', f, q);
for (const [f, q] of [[1000, 4], [1000, 0.5], [200, 20], [8000, 1], [1000, 0], [1000, -2], [24000, 2], [0, 2], [50, 80], [23000, 10]]) addBiquad('bandpass', f, q);
for (const [f, q] of [[1000, 4], [500, 0.5], [3000, 30], [1000, 0], [24000, 3], [0, 3]]) addBiquad('notch', f, q);
addBiquad('lowpass', 1000, 1, { detune: 300 });
addBiquad('highpass', 700, 3, { detune: -700 });
addBiquad('bandpass', 1000, 4, { detune: -500 });
addBiquad('notch', 2000, 3, { detune: 1200 });
addBiquad('lowpass', 1000, 12, { sampleRate: 44100 });
addBiquad('bandpass', 3000, 6, { sampleRate: 44100 });
addBiquad('lowpass', 1500, 7, { channels: 2 });
addBiquad('highpass', 900, 2, { channels: 2 });
addBiquad('bandpass', 2000, 9, { channels: 2 });

// stage cases: times are multiples of u = 1/128 s
const u = 1 / 128;
const STAGE_FRAMES = SIGNAL_FRAMES;
const SR = 48000;
const env = { lpattack: u, lpdecay: 2 * u, lpsustain: 0.25, lprelease: 3 * u };
const hpEnv = { hpattack: u, hpdecay: 2 * u, hpsustain: 0.25, hprelease: 3 * u };
const bpEnv = { bpattack: u, bpdecay: 2 * u, bpsustain: 0.25, bprelease: 3 * u };
const stage = [];
const addStage = (name, kind, controls, extra = {}) =>
  stage.push({ name, kind, controls, sampleRate: SR, channels: 1, frames: STAGE_FRAMES, start: u, end: 9 * u, cps: 0.5, cycle: 0.0390625, loose: false, ...extra });

addStage('lp static', 'lowpass', { cutoff: 1000, resonance: 8 });
addStage('lp default q', 'lowpass', { cutoff: 2000 });
addStage('hp static', 'highpass', { hcutoff: 800, hresonance: 4 });
addStage('bp static', 'bandpass', { bandf: 1500, bandq: 3 });
addStage('lp cutoff 0 is silence', 'lowpass', { cutoff: 0 });
addStage('lp cutoff 1e9 is the identity', 'lowpass', { cutoff: 1e9 });
addStage('lp env', 'lowpass', { cutoff: 400, lpenv: 3, resonance: 6, ...env });
addStage('lp env anchor .5', 'lowpass', { cutoff: 400, lpenv: 3, fanchor: 0.5, ...env });
addStage('lp env anchor 1', 'lowpass', { cutoff: 2400, lpenv: 3, fanchor: 1, ...env });
addStage('lp env negative', 'lowpass', { cutoff: 800, lpenv: -2, resonance: 4, ...env });
addStage('lp only attack', 'lowpass', { cutoff: 500, lpattack: 2 * u, resonance: 5 }, { loose: true });
addStage('lp only env', 'lowpass', { cutoff: 600, lpenv: 2 }, { loose: true });
addStage('hp env', 'highpass', { hcutoff: 300, hpenv: 4, hresonance: 3, ...hpEnv });
addStage('bp env', 'bandpass', { bandf: 500, bpenv: 3, bandq: 4, ...bpEnv });
addStage('lp default adsr', 'lowpass', { cutoff: 300, lpenv: 4 }, { loose: true });
addStage('lp lfo depth', 'lowpass', { cutoff: 1000, lpdepth: 0.5, lprate: 40, resonance: 5 });
addStage('lp lfo shapes', 'lowpass', { cutoff: 800, lpdepth: 1, lprate: 30, lpshape: 'sine', lpskew: 0.3, lpdc: -0.2 });
addStage('lp lfo ramp number shape', 'lowpass', { cutoff: 900, lpdepth: 0.8, lprate: 25, lpshape: 2 });
addStage('lp lfo depthfrequency sync', 'lowpass', { cutoff: 1200, lpdepthfrequency: 300, lpsync: 40 }, { cps: 1, cycle: 0.015625 });
addStage('lp lfo depth 0 is no lfo', 'lowpass', { cutoff: 1000, lpdepth: 0, lprate: 40 });
addStage('lp lfo + env', 'lowpass', { cutoff: 500, lpenv: 2, lpdepth: 0.6, lprate: 35, resonance: 4, ...env });
addStage('hp lfo', 'highpass', { hcutoff: 700, hpdepth: 0.7, hprate: 33, hpshape: 'square' });
addStage('bp lfo', 'bandpass', { bandf: 1500, bpdepth: 0.4, bprate: 45, bpshape: 'saw', bandq: 3 });
addStage('lp 24db', 'lowpass', { cutoff: 1500, resonance: 3, ftype: '24db' });
addStage('lp 24db by number', 'lowpass', { cutoff: 1500, resonance: 3, ftype: 2 });
addStage('lp 24db number mod 3', 'lowpass', { cutoff: 1500, resonance: 3, ftype: 5 });
addStage('lp 24db env lfo stereo', 'lowpass', { cutoff: 600, lpenv: 3, lpdepth: 0.5, lprate: 28, ftype: '24db', ...env }, { channels: 2 });
addStage('hp 24db env', 'highpass', { hcutoff: 400, hpenv: 3, ftype: '24db', ...hpEnv });
addStage('bp 24db', 'bandpass', { bandf: 1200, bandq: 5, ftype: '24db' });
addStage('lp ftype 1 is a biquad', 'lowpass', { cutoff: 900, resonance: 5, ftype: 1 });
addStage('lp ftype 12db', 'lowpass', { cutoff: 900, resonance: 5, ftype: '12db' });
addStage('lp ftype 0', 'lowpass', { cutoff: 900, resonance: 5, ftype: 0 });
addStage('ladder lp', 'lowpass', { cutoff: 700, resonance: 3, ftype: 'ladder' });
addStage('ladder drive', 'lowpass', { cutoff: 1200, resonance: 6, ftype: 'ladder', drive: 1.5 });
addStage('ladder env', 'lowpass', { cutoff: 300, lpenv: 3, resonance: 2, ftype: 'ladder', ...env });
addStage('ladder lfo', 'lowpass', { cutoff: 1000, lpdepth: 0.5, lprate: 40, ftype: 'ladder' });
addStage('ladder on a high-pass is a low-pass', 'highpass', { hcutoff: 1000, hresonance: 2, ftype: 'ladder' });
addStage('ladder on a band-pass is a low-pass', 'bandpass', { bandf: 900, bandq: 2, ftype: 'ladder' });
addStage('ladder stereo env', 'lowpass', { cutoff: 500, lpenv: 2, ftype: 'ladder', ...env }, { channels: 2 });
addStage('lp stereo', 'lowpass', { cutoff: 1100, resonance: 7 }, { channels: 2 });

// sanity: the envelope events of the exact cases land on frames
for (const c of stage) {
  if (c.loose) continue;
  const adsr = c.kind === 'lowpass' ? [c.controls.lpattack, c.controls.lpdecay, c.controls.lprelease] : c.kind === 'highpass' ? [c.controls.hpattack, c.controls.hpdecay, c.controls.hprelease] : [c.controls.bpattack, c.controls.bpdecay, c.controls.bprelease];
  const times = [c.start, c.start + (adsr[0] ?? 0), c.start + (adsr[0] ?? 0) + (adsr[1] ?? 0), c.end, c.end + (adsr[2] ?? 0)];
  for (const t of times) if (t * SR !== Math.round(t * SR)) throw new Error(`${c.name}: event time ${t} is not on a frame`);
}

// ---- the page -----------------------------------------------------------------------------------------------------------------------------------------------------
const page = `<!doctype html><html><body><script>
const logger = () => {};
${utilPieces}
const getNodeFromPool = (key, factory) => factory();
${helpers}
${getFilterType}
${maps}
const MAPS = { lowpass: lpMap, highpass: hpMap, bandpass: bpMap };
const SIGNALS = ${JSON.stringify(Object.fromEntries(Object.entries(signals).map(([k, v]) => [k, b64(v)])))};
const BIQUAD = ${JSON.stringify(biquad)};
const STAGE = ${JSON.stringify(stage)};
const toFloats = (b64) => { const bin = atob(b64); const u = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return new Float32Array(u.buffer); };
const toB64 = (f) => { const u = new Uint8Array(f.buffer, f.byteOffset, f.byteLength); let s = ''; for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]); return btoa(s); };
const buffer = (ctx, c, zeroBefore) => {
  const b = ctx.createBuffer(c.channels, c.frames, c.sampleRate);
  for (let ch = 0; ch < c.channels; ch++) {
    const x = toFloats(SIGNALS[ch === 0 ? 'noiseA' : 'noiseB']).slice(0, c.frames);
    for (let i = 0; i < Math.min(zeroBefore, c.frames); i++) x[i] = 0;
    b.copyToChannel(x, ch);
  }
  return b;
};
const outputs = (rendered, channels) => Array.from({ length: channels }, (_, ch) => toB64(rendered.getChannelData(ch)));
(async () => {
  try {
    const results = { biquad: [], stage: [] };
    for (const c of BIQUAD) {
      const ctx = new OfflineAudioContext(c.channels, c.frames, c.sampleRate);
      const source = ctx.createBufferSource();
      source.buffer = buffer(ctx, c, 0);
      const filter = ctx.createBiquadFilter();
      filter.type = c.type;
      filter.frequency.value = c.frequency;
      filter.Q.value = c.q;
      filter.detune.value = c.detune;
      source.connect(filter);
      filter.connect(ctx.destination);
      source.start(0);
      results.biquad.push(outputs(await ctx.startRendering(), c.channels));
    }
    for (const c of STAGE) {
      const ctx = new OfflineAudioContext(c.channels, c.frames, c.sampleRate);
      await ctx.audioWorklet.addModule('/worklets.js');
      const source = ctx.createBufferSource();
      source.buffer = buffer(ctx, c, Math.round(c.start * c.sampleRate));
      const params = pickAndRename(c.controls, MAPS[c.kind]);
      params.type = c.kind;
      const first = createFilter(ctx, c.start, c.end, params, c.cps, c.cycle);
      source.connect(first.filter);
      let tail = first.filter;
      if (getFilterType(c.controls.ftype) === '24db') {
        const second = createFilter(ctx, c.start, c.end, params, c.cps, c.cycle);
        tail.connect(second.filter);
        tail = second.filter;
      }
      tail.connect(ctx.destination);
      source.start(0);
      results.stage.push(outputs(await ctx.startRendering(), c.channels));
    }
    await fetch('/result', { method: 'POST', body: JSON.stringify({ userAgent: navigator.userAgent, results }) });
  } catch (e) {
    await fetch('/result', { method: 'POST', body: JSON.stringify({ error: String(e) + (e && e.stack) }) });
  }
})();
</script></body></html>`;

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'filter-oracle-'));
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
  } else if (req.url === '/worklets.js') {
    res.setHeader('content-type', 'text/javascript');
    res.end(workletModule);
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
const { userAgent, results } = body;

const trim = (c) => {
  const { loose, ...rest } = c;
  return loose ? { ...rest, loose } : rest;
};
const doc = {
  generator: 'tools/strudel-ref/filter-oracle.mjs',
  commit: COMMIT,
  source: 'BiquadFilterNode and packages/superdough/helpers.mjs createFilter run in ' + userAgent,
  signals: Object.fromEntries(Object.entries(signals).map(([k, v]) => [k, b64(v)])),
  biquad: biquad.map((c, i) => ({ ...c, output: results.biquad[i] })),
  stage: stage.map((c, i) => ({ ...trim(c), output: results.stage[i] })),
};
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'filter.json'), JSON.stringify(doc) + '\n');
console.log('filter.json', fs.statSync(path.join(outDir, 'filter.json')).size, 'bytes,', biquad.length, 'biquad and', stage.length, 'stage cases,', userAgent);
