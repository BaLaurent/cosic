// Kabelsalat oracle: renders the PCM goldens (real @kabelsalat/core+lib compile + real superdough GenericProcessor) and the compile-structure vectors.
//
// ONE command, from the repo root (Node >= 20.6, no dependencies, offline):
//   node --import ./tools/strudel-ref/kabel/register-loader.mjs tools/strudel-ref/kabel/render-golden.mjs
// writes  engine/src/test/resources/kabel/golden/<id>.{f32,json}   (one per case of cases.json)
//         engine/src/test/resources/kabel/graph-vectors.json        (one entry per case of graph-cases.json, see graph-vectors.mjs)
// Options: --only=golden | --only=graph   --cases=<cases.json>   --graph-cases=<graph-cases.json>   --out=<engine/src/test/resources/kabel>
// Exit status is non-zero if an audible-expected case is silent, a case renders NaN/Inf, or a ugen type reachable from lib.js is not
// covered by any case. Prints a table (id, frames, ugens, peak, rms). Output is deterministic: run twice and `cmp`/`diff -r`.
//
// Per case (cases.json schema, see spec 4.4): {id, sampleRate, start, duration, release?, fxRelease?, note?, freq?, octave?, workletSrc,
//   workletInputs, rngSeed, input: 'none'|'ramp', chainId, comment?, expectSilent?}. `Math.random` is replaced by mulberry32(rngSeed)
// BEFORE a fresh `ugens.js` instance (?case=<id>: own NOISE_CHANNELS and delays Map) is imported. Blocks of 128 frames, currentTime =
// blk*128/sampleRate, frames = ceil(end*sr/128)*128 + 2*128 (stops early when process() returns false, zeros afterwards).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  BLOCK, compiler, core, getFrequencyFromValue, lib, mulberry32, newProcessorClass, setCurrentTime, setSampleRate, substitute, compileKabel,
  ugenInputs,
} from './kabel-realm.mjs';
import { generateGraphVectors } from './graph-vectors.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const arg = (name, dflt) => process.argv.find((a) => a.startsWith(`--${name}=`))?.slice(name.length + 3) ?? dflt;
const outDir = path.resolve(arg('out', path.join(here, '../../../engine/src/test/resources/kabel')));
const only = arg('only', 'all');
const casesFile = path.resolve(arg('cases', path.join(here, 'cases.json')));
const graphCasesFile = path.resolve(arg('graph-cases', path.join(here, 'graph-cases.json')));
const goldenDir = path.join(outDir, 'golden');

function renderCase(c, GP) {
  const value = { note: c.note, freq: c.freq, octave: c.octave };
  const sFreq = getFrequencyFromValue(value);
  const code = substitute(c.workletSrc, c.workletInputs, value, c.chainId);
  const { src, ugens, registers } = compileKabel(code);
  const proc = new GP();
  const end = c.start + c.duration;
  const endWR = end + Math.max(c.release ?? 0.01, c.fxRelease ?? 0);
  proc.port.onmessage({ data: { src, schema: { ugens, registers }, start: c.start, gateEnd: end, end: endWR } });
  const sr = c.sampleRate;
  const frames = Math.ceil((endWR * sr) / BLOCK) * BLOCK + 2 * BLOCK;
  const out = new Float32Array(frames * 2);
  const L = new Float32Array(BLOCK), R = new Float32Array(BLOCK), inp = new Float32Array(BLOCK);
  for (let blk = 0; blk * BLOCK < frames; blk++) {
    setCurrentTime((blk * BLOCK) / sr);
    for (let i = 0; i < BLOCK; i++) inp[i] = ((blk * BLOCK + i) % 100) / 100 - 0.5; // 'ramp'
    L.fill(0); R.fill(0);
    const alive = proc.process(c.input === 'ramp' ? [[inp]] : [[]], [[L, R]]);
    for (let i = 0; i < BLOCK; i++) { out[2 * (blk * BLOCK + i)] = L[i]; out[2 * (blk * BLOCK + i) + 1] = R[i]; }
    if (alive === false) break;
  }
  let peak = 0, sumSq = 0, nonFinite = 0;
  for (const x of out) { if (!Number.isFinite(x)) { nonFinite++; continue; } peak = Math.max(peak, Math.abs(x)); sumSq += x * x; }
  const rms = Math.sqrt(sumSq / out.length);
  const meta = {
    id: c.id, sampleRate: sr, frames, block: BLOCK, start: c.start, gateEnd: end, end: endWR, duration: c.duration,
    release: c.release ?? null, fxRelease: c.fxRelease ?? null, note: c.note ?? null, freq: c.freq ?? null, octave: c.octave ?? null,
    chainId: c.chainId, workletSrc: c.workletSrc, workletInputs: c.workletInputs, sFreqText: String(sFreq), codeAfterSubstitution: code,
    compiledSrc: src, ugenTypes: ugens.map((x) => x.type), ugenInputs: ugenInputs(ugens), registers, rngSeed: c.rngSeed, input: c.input ?? 'none',
    peak, rms, comment: c.comment ?? null,
  };
  return { out, meta, nonFinite };
}

async function goldens() {
  const cases = JSON.parse(fs.readFileSync(casesFile, 'utf8'));
  fs.rmSync(goldenDir, { recursive: true, force: true });
  fs.mkdirSync(goldenDir, { recursive: true });
  const ids = new Set(), seen = new Set(), problems = [], rows = [];
  const realMathRandom = Math.random;
  for (const c of cases) {
    if (ids.has(c.id)) throw new Error(`duplicate case id ${c.id}`);
    ids.add(c.id);
    setSampleRate(c.sampleRate);
    Math.random = mulberry32(c.rngSeed);
    const GP = await newProcessorClass(c.id);
    const { out, meta, nonFinite } = renderCase(c, GP);
    meta.ugenTypes.forEach((t) => seen.add(t));
    if (nonFinite) problems.push(`${c.id}: ${nonFinite} non-finite samples`);
    if (!c.expectSilent && !(meta.peak > 1e-3)) problems.push(`${c.id}: expected audible but peak=${meta.peak}`);
    if (c.expectSilent && meta.peak > 1e-3) problems.push(`${c.id}: expected silent but peak=${meta.peak}`);
    fs.writeFileSync(path.join(goldenDir, `${c.id}.f32`), Buffer.from(out.buffer));
    fs.writeFileSync(path.join(goldenDir, `${c.id}.json`), JSON.stringify(meta, null, 1) + '\n');
    const uniq = [...new Set(meta.ugenTypes.filter((t) => t !== 'Output'))];
    rows.push([c.id, String(meta.frames), String(meta.ugenTypes.length), meta.peak.toFixed(4), meta.rms.toFixed(4), c.sampleRate === 48000 ? '' : String(c.sampleRate), uniq.join(',')]);
  }
  Math.random = realMathRandom;
  // every ugen type reachable from lib.js (Scope throws ReferenceError at evaluate: unreachable, see graph-vectors)
  const reachable = new Set([...core.nodeRegistry.values()].map((s) => s.ugen).filter(Boolean));
  reachable.delete('Scope');
  const missing = [...reachable].filter((t) => !seen.has(t)).sort();
  if (missing.length) problems.push(`ugen types not covered by any case: ${missing.join(', ')}`);
  const head = ['id', 'frames', 'ugens', 'peak', 'rms', 'sr', 'ugen types'];
  const table = [head, ...rows];
  const wd = head.map((_, i) => Math.max(...table.map((r) => r[i].length)));
  for (const r of table) console.log(r.map((x, i) => x.padEnd(wd[i])).join('  ').trimEnd());
  console.log(`\n${cases.length} golden cases, ${reachable.size} reachable ugen types covered: ${[...reachable].filter((t) => seen.has(t)).length}`);
  if (problems.length) { console.error('PROBLEMS:\n  ' + problems.join('\n  ')); process.exitCode = 1; }
}

if (only === 'all' || only === 'golden') await goldens();
if (only === 'all' || only === 'graph') {
  const vectors = await generateGraphVectors(JSON.parse(fs.readFileSync(graphCasesFile, 'utf8')));
  fs.mkdirSync(outDir, { recursive: true });
  fs.writeFileSync(path.join(outDir, 'graph-vectors.json'), JSON.stringify(vectors, null, 1) + '\n');
  console.log(`graph-vectors.json: ${vectors.length} entries, ${vectors.filter((v) => v.error).length} errors`);
}
