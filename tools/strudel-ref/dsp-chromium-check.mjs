// Cross-checks the Node mock of dsp-oracle.mjs against a real AudioWorklet: the same worklet text (worklet-sources.mjs) is loaded into the AudioWorkletGlobalScope of
// headless Chromium, every recorded case is rendered by an OfflineAudioContext (input from the golden, AudioParams set with setValueAtTime at the quantum starts)
// and the output is compared with the golden that dsp-oracle.mjs wrote.
//
//   node tools/strudel-ref/dsp-chromium-check.mjs          # exit code 1 when a case differs by more than 1e-6
//
// It confirms what the Node harness assumes: `currentTime` is the quantum start, the AudioParams the processors see, the channel counts of a node that follows its
// input, quantum alignment, and the state each processor keeps. CHROMIUM (default `chromium`) and STRUDEL_DIR as in the other oracles; DSP_OUT is where the goldens are read from.
import fs from 'fs';
import http from 'http';
import os from 'os';
import path from 'path';
import { spawn } from 'child_process';
import { fileURLToPath } from 'url';
import { workletModuleText } from './worklet-sources.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const dir = process.env.DSP_OUT ?? path.join(here, '../../engine/src/test/resources/dsp');
const chromium = process.env.CHROMIUM ?? 'chromium';
const TOLERANCE = 1e-6;
const Q = 128;

const load = (name) => JSON.parse(fs.readFileSync(path.join(dir, name), 'utf8'));
const cases = [];
const signalsOf = (doc) => doc.signals ?? {};
const add = (processor, doc, list, extra = () => ({})) => {
  for (const c of list) {
    if (c.params.some((p) => Object.values(p).some(Array.isArray))) continue; // moving AudioParams are checked by the Kotlin tests only
    const input = c.input ?? c.signals.map((n) => signalsOf(doc)[n]);
    cases.push({
      label: `${processor}: ${c.name}`,
      processor,
      channels: c.channels ?? input.length,
      quanta: c.quanta,
      sampleRate: c.sampleRate ?? doc.sampleRate,
      options: c.options ? { processorOptions: c.options } : c.algorithm !== undefined ? { processorOptions: { algorithm: c.algorithm } } : {},
      params: c.params,
      input: input,
      expected: c.output,
      ...extra(c),
    });
  }
};
{
  const ladder = load('ladder.json');
  add('ladder-processor', ladder, ladder.cases);
  const djf = load('djf.json');
  add('djf-processor', djf, djf.cases);
  const transient = load('transient.json');
  add('transient-processor', transient, transient.cases);
  const vocoder = load('phasevocoder.json');
  add('phase-vocoder-processor', vocoder, vocoder.cases);
  const scc = load('shapecrushcoarse.json');
  add('shape-processor', scc, scc.shape);
  add('crush-processor', scc, scc.crush);
  add('coarse-processor', scc, scc.coarse);
  const distort = load('distort.json');
  add('distort-processor', distort, distort.processor.map((c) => ({ ...c, input: [distort.input] })));
}

const page = `<!doctype html><html><body><script>
const CASES = ${JSON.stringify(cases)};
const toFloats = (b64) => { const bin = atob(b64); const u = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return new Float32Array(u.buffer); };
const toB64 = (f) => { const u = new Uint8Array(f.buffer, f.byteOffset, f.byteLength); let s = ''; for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]); return btoa(s); };
(async () => {
  try {
    const results = [];
    for (const c of CASES) {
      const frames = c.quanta * ${Q};
      const ctx = new OfflineAudioContext(c.channels, frames, c.sampleRate);
      await ctx.audioWorklet.addModule('/worklets.js');
      const buffer = ctx.createBuffer(c.channels, frames, c.sampleRate);
      c.input.forEach((b, ch) => buffer.copyToChannel(toFloats(b).subarray(0, frames), ch));
      const source = ctx.createBufferSource();
      source.buffer = buffer;
      const node = new AudioWorkletNode(ctx, c.processor, c.options);
      for (let q = 0; q < c.quanta; q++) {
        // A quarter of a frame early: Chromium rounds the event time up to a frame, and (q * 128) / sampleRate * sampleRate can come out a hair above the whole number
        // (896 / 48000 * 48000 = 896.0000000000001), which would start the new value one frame into the quantum.
        for (const [key, value] of Object.entries(c.params[q])) node.parameters.get(key).setValueAtTime(value, Math.max(0, (q * ${Q} - 0.25) / c.sampleRate));
      }
      source.connect(node);
      node.connect(ctx.destination);
      source.start(0);
      const rendered = await ctx.startRendering();
      results.push({ label: c.label, output: Array.from({ length: c.channels }, (_, ch) => toB64(rendered.getChannelData(ch))) });
    }
    await fetch('/result', { method: 'POST', body: JSON.stringify({ userAgent: navigator.userAgent, results }) });
  } catch (e) {
    await fetch('/result', { method: 'POST', body: JSON.stringify({ error: String(e) + (e && e.stack) }) });
  }
})();
</script></body></html>`;

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'dsp-check-'));
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
    res.end(workletModuleText());
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

const floats = (b64) => {
  const b = Buffer.from(b64, 'base64');
  return new Float32Array(b.buffer.slice(b.byteOffset, b.byteOffset + b.length));
};
let failed = 0;
console.log(body.userAgent);
body.results.forEach((r, i) => {
  const c = cases[i];
  let worst = 0;
  r.output.forEach((out, ch) => {
    const got = floats(out);
    const want = floats(c.expected[ch]);
    for (let k = 0; k < want.length; k++) worst = Math.max(worst, Math.abs(got[k] - want[k]) || (Number.isNaN(got[k]) ? Infinity : 0));
  });
  const ok = worst <= TOLERANCE;
  if (!ok) failed++;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${c.label.padEnd(70)} worst difference ${worst.toExponential(2)}`);
  if (!ok) {
    // where it starts to differ: the first quantum and the AudioParam values that were set for it
    const got = floats(r.output[0]);
    const want = floats(c.expected[0]);
    const k = want.findIndex((v, j) => Math.abs(got[j] - v) > TOLERANCE);
    const q = Math.floor(k / Q);
    console.log(`     first difference at frame ${k} (quantum ${q}, params ${JSON.stringify(c.params[q])}): got ${got[k]}, expected ${want[k]}`);
  }
});
console.log(failed === 0 ? `all ${body.results.length} cases match` : `${failed} case(s) differ`);
process.exit(failed === 0 ? 0 : 1);
