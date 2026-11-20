// Renders superdough's vowel filter (packages/superdough/vowel.mjs, the real `VowelNode` source text) in headless Chromium's OfflineAudioContext and writes the result
// as golden vectors for cosic.audio.dsp.VowelFilter (Task 3.3):
//
//   node tools/strudel-ref/vowel-oracle.mjs      # writes engine/src/test/resources/dsp/vowel.json
//
// The vowel filter is no worklet but a graph of Web Audio nodes (five band-pass BiquadFilterNodes, a GainNode each, summed through a makeup gain of 8), so the only
// faithful reference is a browser's own biquad. CHROMIUM (default `chromium`) is started headless on a page served from a local http server, which also receives the
// result (virtual-time `--dump-dom` does not wait for audio rendering); STRUDEL_DIR and DSP_OUT as in dsp-oracle.mjs.
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

// vowel.mjs without its import (releaseAudioNode only runs on disconnect) and with `export var` turned into a plain global.
const vowelSource = fs
  .readFileSync(path.join(root, 'packages/superdough/vowel.mjs'), 'utf8')
  .replace(/^import .*$/m, 'const releaseAudioNode = () => {};')
  .replace('export var vowelFormant', 'var vowelFormant');

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

const FRAMES = 2048;
const cases = [
  { name: 'a mono 48k', vowel: 'a', sampleRate: 48000, seeds: [31] },
  { name: 'e mono 48k', vowel: 'e', sampleRate: 48000, seeds: [32] },
  { name: 'i stereo 48k', vowel: 'i', sampleRate: 48000, seeds: [33, 34] },
  { name: 'o mono 48k', vowel: 'o', sampleRate: 48000, seeds: [35] },
  { name: 'u mono 48k', vowel: 'u', sampleRate: 48000, seeds: [36] },
  { name: 'ae (via the alias) mono 48k', vowel: 'æ', sampleRate: 48000, seeds: [37] },
  { name: 'on mono 48k', vowel: 'on', sampleRate: 48000, seeds: [38] },
  { name: 'a mono 44.1k', vowel: 'a', sampleRate: 44100, seeds: [39] },
  { name: 'y stereo 44.1k', vowel: 'ı', sampleRate: 44100, seeds: [40, 41] },
];
for (const c of cases) c.input = c.seeds.map((s) => b64(lcg(s, FRAMES, 0.5)));

const page = `<!doctype html><html><body><script>
${vowelSource}
const CASES = ${JSON.stringify(cases.map(({ name, vowel, sampleRate, input }) => ({ name, vowel, sampleRate, input })))};
const FRAMES = ${FRAMES};
const toFloats = (b64) => { const bin = atob(b64); const u = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return new Float32Array(u.buffer); };
const toB64 = (f) => { const u = new Uint8Array(f.buffer, f.byteOffset, f.byteLength); let s = ''; for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]); return btoa(s); };
(async () => {
  try {
    const results = [];
    for (const c of CASES) {
      const channels = c.input.length;
      const ctx = new OfflineAudioContext(channels, FRAMES, c.sampleRate);
      const buffer = ctx.createBuffer(channels, FRAMES, c.sampleRate);
      c.input.forEach((b, ch) => buffer.copyToChannel(toFloats(b), ch));
      const source = ctx.createBufferSource();
      source.buffer = buffer;
      const vowel = ctx.createVowelFilter(c.vowel);
      source.connect(vowel);
      vowel.connect(ctx.destination);
      source.start(0);
      const rendered = await ctx.startRendering();
      results.push({ name: c.name, output: Array.from({ length: channels }, (_, ch) => toB64(rendered.getChannelData(ch))) });
    }
    await fetch('/result', { method: 'POST', body: JSON.stringify({ userAgent: navigator.userAgent, results }) });
  } catch (e) {
    await fetch('/result', { method: 'POST', body: JSON.stringify({ error: String(e) + (e && e.stack) }) });
  }
})();
</script></body></html>`;

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'vowel-oracle-'));
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
const timer = setTimeout(() => deliver(JSON.stringify({ error: 'timeout: the page did not answer within 120 s' })), 120000);
const body = JSON.parse(await delivered);
clearTimeout(timer);
browser.kill('SIGKILL');
server.close();
fs.rmSync(profile, { recursive: true, force: true });
if (body.error) throw new Error(body.error);
const { userAgent, results } = body;

const doc = {
  generator: 'tools/strudel-ref/vowel-oracle.mjs',
  commit: COMMIT,
  source: 'packages/superdough/vowel.mjs run in ' + userAgent,
  cases: cases.map((c, i) => ({
    name: c.name,
    vowel: c.vowel,
    sampleRate: c.sampleRate,
    channels: c.input.length,
    input: c.input,
    output: results[i].output,
  })),
};
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'vowel.json'), JSON.stringify(doc) + '\n');
console.log('vowel.json', fs.statSync(path.join(outDir, 'vowel.json')).size, 'bytes,', userAgent);
