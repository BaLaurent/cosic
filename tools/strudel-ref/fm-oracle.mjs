// Runs the REAL `applyFM` of the pinned superdough (packages/superdough/helpers.mjs, loaded as it is, with its audioContext/noise/logger modules) in headless
// Chromium against an OfflineAudioContext and writes what it renders as golden vectors for cosic.audio.sources.FmBank (Task 3.12):
//
//   node tools/strudel-ref/fm-oracle.mjs      # writes engine/src/test/resources/sources/fm.json
//
// Two kinds of case, both with the modulation graph built by `applyFM(param, value, begin)` exactly as superdough's oscillators, supersaw, pulse and
// wavetable call it (`begin` 0, `value.duration` the hold):
//   mode "sum":  `param` is the `offset` of a ConstantSourceNode(carrierHz) feeding the destination, so the rendered samples are the carrier's frequency in Hz
//                frame by frame: `carrierHz + the FM signal`, which is what FmBank.add adds to the frequency of an oscillator.
//   mode "osc":  `param` is the `frequency` of an OscillatorNode(wave) started at 0: the audio the whole FM voice makes.
// White, pink and brown noise buffers are cached for the lifetime of the page by superdough, so they are primed first with Math.random replaced by SplitMix64
// (the generator of SourceRng) seeded as NoiseBuffers seeds them; a crackle modulator is built fresh by `applyFM`, with Math.random seeded just before from the
// case's `crackleSeed` (FmBank seeds crackle operator n with `spec.seed + n`).
//
// STRUDEL_DIR (default tools/strudel-ref/strudel next to this file) is the checkout made by fetch.sh, CHROMIUM (default `chromium`) the browser and FM_OUT the
// directory of fm.json (default engine/src/test/resources/sources). The page is served from a local http server, which also receives the result.
import fs from 'fs';
import http from 'http';
import os from 'os';
import path from 'path';
import { spawn } from 'child_process';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.STRUDEL_DIR ?? path.join(here, 'strudel');
const sdDir = path.join(root, 'packages/superdough');
const outDir = process.env.FM_OUT ?? path.join(here, '../../engine/src/test/resources/sources');
const chromium = process.env.CHROMIUM ?? 'chromium';
const COMMIT = '84ef32eec8074f90c6690ab8d6a6c204f508b182';

// NoiseBuffers.WHITE_SEED / PINK_SEED / BROWN_SEED
const NOISE_SEEDS = { white: 0x57a11e01, pink: 0x57a11e02, brown: 0x57a11e03 };

const SR = 48000;
const sum = (name, carrierHz, value, extra = {}) => ({ name, mode: 'sum', sampleRate: SR, frames: 2400, carrierHz, value: { duration: 0.1, ...value }, ...extra });
const osc = (name, wave, carrierHz, value, extra = {}) => ({ name, mode: 'osc', wave, sampleRate: SR, frames: 4800, carrierHz, value: { duration: 0.1, ...value }, ...extra });

const cases = [
  // the routing loop and the gains: modulation index (fmi) times the modulator's frequency (carrier times fmh) times its output
  sum('simple', 100, { fmi: 2 }),
  sum('harmonic', 110, { fmi: 4, fmh: 2 }),
  sum('negativeAmount', 200, { fmi: -3, fmh: 1.5 }),
  sum('fractionalRatio', 331.25, { fmi: 1.5, fmh: 0.5 }),
  sum('defaultNoteCarrier', 65.40639132514966, { fmi: 3, fmh: 3 }),
  sum('chain2', 100, { fmi: 1, fmi2: 1 }),
  sum('chain3', 120, { fmi: 3, fmi2: 2, fmi3: 1.5, fmh: 1.5, fmh2: 2, fmh3: 3 }),
  sum('chain5', 80, { fmi: 2, fmi2: 1, fmi3: 1.5, fmi4: 1, fmi5: 2, fmh: 2, fmh3: 0.5, fmh5: 3 }),
  sum('chain8', 55, { fmi: 1, fmi2: 1, fmi3: 1, fmi4: 1, fmi5: 1, fmi6: 1, fmi7: 1, fmi8: 1, fmh8: 2.5 }),
  sum('matrix', 100, { fmi12: 1, fmi20: 2 }),
  sum('matrixReversedNames', 90, { fmi21: 2, fmi30: 1, fmi23: 1.5 }),
  sum('fanIn', 100, { fmi: 1, fmi20: 1.5, fmi30: 2, fmh2: 2, fmh3: 3 }),
  sum('diamond', 100, { fmi: 1, fmi12: 2, fmi20: 1, fmh2: 1.5 }),
  sum('twoChains', 150, { fmi: 1, fmi2: 2, fmi50: 1, fmi45: 1.5, fmh: 2, fmh4: 3, fmh5: 0.5 }),
  sum('noEdges', 100, { fmi23: 1, fmi21: 1, fmi00: 1, fmi01: 1 }), // none of these is read, or reaches the carrier
  sum('fmiZero', 100, { fmi: 0, fmi2: 1 }), // a zero amount is no edge: the op 2 reaches nothing
  sum('zeroRatio', 100, { fmi: 1, fmh: 0 }),
  sum('negativeRatio', 100, { fmi: 2, fmh: -1.5 }), // a negative frequency: the oscillator runs backwards and the gain of the connection is negative
  sum('negativeCarrier', -120, { fmi: 2, fmh: 1.5, fmi2: 1 }),
  sum('zeroCarrier', 0, { fmi: 3, fmi2: 1 }), // every operator at 0 Hz
  sum('numbersAsText', 100, { fmi: '2', fmh: '1.5', fmattack: '0.01', fmdecay: '0.01', fmsustain: '0.5', fmrelease: '0.02', duration: 0.03 }, { frames: 3000 }), // JS coerces
  sum('slowModulator', 20, { fmi: 10, fmh: 0.01 }, { frames: 4800 }), // 0.2 Hz: the table is read with the 5-point polynomial
  sum('fastModulatorClamped', 2000, { fmi: 1, fmi2: 20, fmh2: 4 }), // the op's frequency swings far beyond the Nyquist frequency: clamped
  sum('opAboveNyquist', 440, { fmi: 2, fmh: 100 }),
  sum('sampleRate44100', 100, { fmi: 2, fmh: 2 }, { sampleRate: 44100 }),
  sum('sampleRate22050', 100, { fmi: 2, fmi2: 1, fmh: 2 }, { sampleRate: 22050 }),
  // cycles: Chromium does not mute them, an edge that closes a cycle carries the previous render quantum's output (128 frames late)
  sum('selfLoop', 100, { fmi11: 1, fmi: 1 }, { frames: 4800 }),
  sum('cycle2', 100, { fmi12: 1, fmi2: 1, fmi: 1 }, { frames: 4800 }),
  sum('cycle2OtherWay', 100, { fmi: 1, fmi12: 1, fmi2: 1.5, fmh2: 1.5 }, { frames: 4800 }),
  sum('cycle3', 100, { fmi12: 1, fmi23: 1, fmi31: 1, fmi: 1, fmh2: 1.5, fmh3: 2 }, { frames: 4800 }),
  sum('selfLoopInChain', 100, { fmi: 1, fmi2: 1, fmi22: 0.5 }, { frames: 4800 }),
  sum('cycleBehindChain', 100, { fmi: 1, fmi2: 1, fmi3: 1, fmi23: 0.5, fmh3: 2 }, { frames: 4800 }), // op 2 and op 3 feed each other behind the chain 2 -> 1 -> carrier
  sum('cycleTwoInputs', 100, { fmi: 1, fmi20: 1, fmi12: 1, fmi2: 1 }, { frames: 4800 }), // the carrier pulls two ops that feed each other
  // the FM envelope: exponential by default (from 0.001), linear for fmenv != "exp"; every branch of getParamADSR
  sum('envAttackExp', 100, { fmi: 4, fmattack: 0.01, duration: 0.02 }, { frames: 3000 }),
  sum('envAttackLin', 100, { fmi: 4, fmattack: 0.01, fmenv: 'lin', duration: 0.02 }, { frames: 3000 }),
  sum('envAdsrExp', 100, { fmi: 3, fmattack: 0.005, fmdecay: 0.01, fmsustain: 0.4, fmrelease: 0.02, duration: 0.03 }, { frames: 3000 }),
  sum('envAdsrLin', 100, { fmi: 3, fmattack: 0.005, fmdecay: 0.01, fmsustain: 0.4, fmrelease: 0.02, fmenv: 'lin', duration: 0.03 }, { frames: 3000 }),
  sum('envAttackLongerThanHold', 100, { fmi: 3, fmattack: 0.05, fmrelease: 0.01, duration: 0.02 }, { frames: 3000 }),
  sum('envDecayCutByHold', 100, { fmi: 3, fmattack: 0.01, fmdecay: 0.05, fmsustain: 0.2, fmrelease: 0.01, duration: 0.03 }, { frames: 3000 }),
  sum('envOnlyRelease', 100, { fmi: 3, fmrelease: 0.02, duration: 0.02 }, { frames: 3000 }),
  sum('envOnlyDecay', 100, { fmi: 3, fmdecay: 0.02, duration: 0.03 }, { frames: 3000 }),
  sum('envOnlySustain', 100, { fmi: 3, fmsustain: 0.3, duration: 0.02 }, { frames: 3000 }),
  sum('envSustainZero', 100, { fmi: 3, fmattack: 0.005, fmsustain: 0, duration: 0.02 }, { frames: 3000 }),
  sum('envPerOperator', 100, { fmi: 2, fmi2: 3, fmattack: 0.01, fmattack2: 0.02, fmenv2: 'lin', fmdecay2: 0.01, fmsustain2: 0.5, duration: 0.03 }, { frames: 3000 }),
  sum('envOnSecondOnly', 100, { fmi: 2, fmi2: 3, fmrelease2: 0.02, fmattack2: 0.01, duration: 0.02 }, { frames: 3000 }),
  sum('envNonStringType', 100, { fmi: 2, fmattack: 0.01, fmenv: 0, duration: 0.02 }, { frames: 3000 }),
  // the operator's wave: sine unless a wave name is given; the names are the OscillatorNode's, not superdough's aliases
  sum('waveSquare', 100, { fmi: 2, fmwave: 'square', fmh: 2 }),
  sum('waveSawtooth', 100, { fmi: 2, fmwave: 'sawtooth', fmh: 2 }),
  sum('waveTriangle', 100, { fmi: 2, fmwave: 'triangle', fmh: 2 }),
  sum('waveInvalid', 100, { fmi: 2, fmwave: 'saw', fmh: 2 }),
  sum('waveUppercase', 100, { fmi: 2, fmwave: 'Square', fmh: 2 }),
  sum('waveOfSecondOperator', 100, { fmi: 2, fmi2: 1.5, fmwave2: 'sawtooth', fmh2: 3 }),
  // noise operators: no frequency input, the looped buffer from frame 0
  sum('noiseWhite', 100, { fmi: 3, fmwave: 'white' }),
  sum('noisePink', 100, { fmi: 30, fmwave: 'pink', fmh: 2 }),
  sum('noiseBrown', 100, { fmi: 40, fmwave: 'brown' }),
  sum('noiseCrackle', 100, { fmi: 3, fmwave: 'crackle' }, { crackleSeed: 4242 + 1 }),
  sum('noiseAsTarget', 100, { fmi: 1, fmi2: 1, fmwave: 'white' }), // the edge into the noise is dropped
  sum('noiseAsSecondOperator', 100, { fmi: 2, fmi2: 3, fmwave2: 'pink' }),
  sum('noiseWithEnvelope', 100, { fmi: 3, fmwave: 'white', fmattack: 0.01, duration: 0.02 }, { frames: 3000 }),
  sum('noiseCrackleSecondOperator', 100, { fmi: 1, fmi2: 20, fmwave2: 'crackle' }, { crackleSeed: 4242 + 2 }),
  // the whole voice
  osc('oscSine', 'sine', 220, { fmi: 3, fmh: 2 }),
  osc('oscSawChain', 'sawtooth', 110, { fmi: 2, fmi2: 1, fmwave: 'square' }),
  osc('oscEnvelope', 'triangle', 330, { fmi: 5, fmattack: 0.02, fmdecay: 0.02, fmsustain: 0.3, fmrelease: 0.02, duration: 0.05 }, { frames: 6000 }),
  osc('oscCycle', 'sine', 150, { fmi11: 2, fmi: 1.5 }, { frames: 6000 }),
];

// Random graphs from a fixed generator (the controls are stored in fm.json, so the Kotlin test replays exactly these). 2 to 8 operators, numbered at random,
// in a random order: each one is connected to an earlier one or to the carrier (so every operator reaches the carrier), and then a few more connections
// are added. In the acyclic family these only go from a later operator to an earlier one, in the general one anywhere: loops of any length and operators
// that feed themselves. Operators have their own ratio, wave (noise included) and envelope, and the connections have names of both kinds (`fmi`, `fmi2`
// and `fmi53`): this is what finds a mistake in how the graph is ordered, which operators exist and what is dropped, in combinations nobody would think of.
const lcg = (seed) => {
  let s = seed >>> 0;
  return () => (s = (Math.imul(s, 1664525) + 1013904223) >>> 0) / 4294967296;
};
const connectionName = (i, j) => (i === j + 1 ? 'fmi' + (i === 1 ? '' : i) : `fmi${i}${j}`);
function randomGraph(seed, acyclic) {
  const r = lcg(seed * 7919 + 13);
  const pick = (list) => list[Math.floor(r() * list.length)];
  const value = { duration: 0.05 };
  const m = 2 + Math.floor(r() * 7);
  const numbers = [1, 2, 3, 4, 5, 6, 7, 8];
  for (let k = numbers.length - 1; k > 0; k--) {
    const x = Math.floor(r() * (k + 1));
    [numbers[k], numbers[x]] = [numbers[x], numbers[k]];
  }
  const nodes = [0, ...numbers.slice(0, m)]; // the carrier, then the operators from the nearest to the carrier to the farthest
  const connect = (from, to) => {
    value[connectionName(nodes[from], nodes[to])] = pick([0.3, 0.5, 0.8, 1, 1.5, -1, -0.7]);
  };
  for (let k = 1; k <= m; k++) connect(k, Math.floor(r() * k));
  for (let extra = Math.floor(r() * (m + 2)); extra > 0; extra--) {
    const from = 1 + Math.floor(r() * m);
    connect(from, acyclic ? Math.floor(r() * from) : Math.floor(r() * (m + 1)));
  }
  for (const idx of nodes.slice(1)) {
    const s = idx === 1 ? '' : String(idx);
    if (r() < 0.6) value['fmh' + s] = pick([0.5, 1, 1.5, 2, 3]);
    const wave = pick([undefined, undefined, undefined, 'sine', 'triangle', 'square', 'sawtooth', 'white', 'pink', 'brown']);
    if (wave) value['fmwave' + s] = wave;
    if (r() < 0.35) {
      if (r() < 0.8) value['fmattack' + s] = pick([0.005, 0.01, 0.02]);
      if (r() < 0.5) value['fmdecay' + s] = pick([0.005, 0.01, 0.02]);
      if (r() < 0.5) value['fmsustain' + s] = pick([0.2, 0.5, 0.8]);
      if (r() < 0.4) value['fmrelease' + s] = pick([0.01, 0.02]);
      if (r() < 0.5) value['fmenv' + s] = 'lin';
    }
  }
  return { carrierHz: 55 + Math.floor(r() * 300), value };
}
for (let seed = 1; seed <= 40; seed++) {
  const { carrierHz, value } = randomGraph(seed, true);
  cases.push(sum(`randomAcyclic${seed}`, carrierHz, value, { frames: 800 }));
}
for (let seed = 101; seed <= 120; seed++) {
  const { carrierHz, value } = randomGraph(seed, false);
  cases.push(sum(`randomGeneral${seed}`, carrierHz, value, { frames: 800 }));
}

const page = `<!doctype html><html><body><script type="module">
import { setAudioContext } from '/sd/audioContext.mjs';
import { applyFM } from '/sd/helpers.mjs';
import { getNoiseBuffer } from '/sd/noise.mjs';
const CASES = ${JSON.stringify(cases)};
const NOISE_SEEDS = ${JSON.stringify(NOISE_SEEDS)};
const MASK = (1n << 64n) - 1n;
function splitMix64(seed) {
  let state = BigInt.asUintN(64, BigInt(seed));
  return () => {
    state = (state + 0x9e3779b97f4a7c15n) & MASK;
    let z = state;
    z = ((z ^ (z >> 30n)) * 0xbf58476d1ce4e5b9n) & MASK;
    z = ((z ^ (z >> 27n)) * 0x94d049bb133111ebn) & MASK;
    z = z ^ (z >> 31n);
    return Number(z >> 11n) * 2 ** -53;
  };
}
const toB64 = (f) => { const u = new Uint8Array(f.buffer, f.byteOffset, f.byteLength); let s = ''; for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]); return btoa(s); };
const logs = [];
(async () => {
  try {
    // the cached noise buffers (48 kHz), seeded as NoiseBuffers seeds them. The context is rendered afterwards so that its state is "closed": setAudioContext
    // closes the previous context, and an OfflineAudioContext that has not finished has no close() to call
    const prime = new OfflineAudioContext(1, 128, ${SR});
    setAudioContext(prime);
    for (const type of ['white', 'pink', 'brown']) {
      Math.random = splitMix64(NOISE_SEEDS[type]);
      getNoiseBuffer(type, 2);
    }
    await prime.startRendering();
    const results = [];
    for (const c of CASES) {
      const ctx = new OfflineAudioContext(1, c.frames, c.sampleRate);
      setAudioContext(ctx);
      let param;
      let source;
      if (c.mode === 'sum') {
        source = new ConstantSourceNode(ctx, { offset: c.carrierHz });
        param = source.offset;
      } else {
        source = ctx.createOscillator();
        source.type = c.wave;
        source.frequency.value = c.carrierHz;
        param = source.frequency;
      }
      if (c.crackleSeed !== undefined) Math.random = splitMix64(c.crackleSeed);
      const origLog = console.log;
      console.log = (...a) => logs.push(c.name + ': ' + a.join(' '));
      applyFM(param, c.value, 0);
      console.log = origLog;
      source.connect(ctx.destination);
      source.start(0);
      const out = (await ctx.startRendering()).getChannelData(0);
      results.push({ name: c.name, output: toB64(out) });
    }
    await fetch('/result', { method: 'POST', body: JSON.stringify({ userAgent: navigator.userAgent, results, logs }) });
  } catch (e) {
    await fetch('/result', { method: 'POST', body: JSON.stringify({ error: String(e) + (e && e.stack) }) });
  }
})();
</script></body></html>`;

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'fm-oracle-'));
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
  } else if (req.url.startsWith('/sd/')) {
    const file = path.join(sdDir, path.normalize(req.url.slice(4)).replace(/^(\.\.[/\\])+/, ''));
    try {
      const body = fs.readFileSync(file);
      res.setHeader('content-type', 'text/javascript; charset=utf-8');
      res.end(body);
    } catch {
      res.statusCode = 404;
      res.end('not found');
    }
  } else {
    res.setHeader('content-type', 'text/html; charset=utf-8');
    res.end(page);
  }
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const browser = spawn(chromium, ['--headless=new', '--no-sandbox', '--disable-gpu', '--user-data-dir=' + profile, `http://127.0.0.1:${server.address().port}/`], { stdio: 'ignore' });
const timer = setTimeout(() => deliver(JSON.stringify({ error: 'timeout: the page did not answer within 180 s' })), 180000);
const body = JSON.parse(await delivered);
clearTimeout(timer);
browser.kill('SIGKILL');
server.close();
fs.rmSync(profile, { recursive: true, force: true });
if (body.error) throw new Error(body.error);
const { userAgent, results, logs } = body;

const doc = {
  generator: 'tools/strudel-ref/fm-oracle.mjs',
  commit: COMMIT,
  source: 'packages/superdough/helpers.mjs applyFM run in ' + userAgent,
  noiseSeeds: NOISE_SEEDS,
  cases: cases.map((c, i) => ({ ...c, output: results[i].output })),
};
fs.mkdirSync(outDir, { recursive: true });
const file = path.join(outDir, 'fm.json');
fs.writeFileSync(file, JSON.stringify(doc) + '\n');
console.log(`fm.json ${fs.statSync(file).size} bytes, ${cases.length} cases, ${userAgent}`);
for (const line of logs) console.log('  log', line);
