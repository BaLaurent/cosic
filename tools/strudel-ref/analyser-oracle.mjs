// Reference data for the analyser model of the visuals (engine/src/test/kotlin/cosic/visual/AnalyserChromiumTest.kt).
//
//   node tools/strudel-ref/analyser-oracle.mjs        writes engine/src/test/resources/visual/analyser.json
//
// superdough feeds `scope`, `fscope` and `spectrum` from an `AnalyserNode` (`getAnalyserById`: `fftSize = 2^(fft + 5)`, `smoothingTimeConstant = 0.5`) read with
// `getFloatTimeDomainData` and `getFloatFrequencyData` into an array of `frequencyBinCount` values. This renders signals through a real AnalyserNode in headless Chromium (an
// OfflineAudioContext suspended at chosen frames, so each read sees exactly the frames before it) and stores what the two reads give, so cosic.visual.Analyser can be compared
// with the browser's: the window, the 1/N scale, the smoothing across reads and which half of the block the waveform is.
//
// CHROMIUM (default `chromium`) as in the other oracles.
import fs from 'fs';
import http from 'http';
import os from 'os';
import path from 'path';
import { spawn } from 'child_process';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.COSIC_ROOT ?? path.resolve(here, '../..');
const chromium = process.env.CHROMIUM ?? 'chromium';
const COMMIT = '84ef32eec8074f90c6690ab8d6a6c204f508b182';
const SR = 48000;

// A signal is `{ type, ...}`; reads are frame numbers (multiples of the 128-frame render quantum, at least `fftSize` apart from the start so the block is full).
const cases = [
  { name: 'unit sine on bin 100, fft 8 (8192)', fftSize: 8192, signal: { type: 'sine', bin: 100, amp: 1 }, reads: [8192, 8320, 9216, 16384, 24576, 32768, 40960, 49152, 57344, 65536, 73728] },
  { name: 'sine between bins, fft 6 (2048)', fftSize: 2048, signal: { type: 'sine', bin: 37.5, amp: 0.5 }, reads: [2048, 2176, 4096, 6144] },
  { name: 'noise, fft 8', fftSize: 8192, signal: { type: 'noise', seed: 7, amp: 0.5 }, reads: [8192, 9216, 12288, 16384] },
  { name: 'noise, fft 5 (1024)', fftSize: 1024, signal: { type: 'noise', seed: 11, amp: 1 }, reads: [1024, 1152, 2048, 3072, 5120] },
  { name: 'noise, fft 0 (32)', fftSize: 32, signal: { type: 'noise', seed: 3, amp: 0.8 }, reads: [128, 256, 384, 1024] },
  { name: 'ramp, time domain', fftSize: 256, signal: { type: 'ramp', amp: 0.9 }, reads: [256, 384, 640] },
  { name: 'two sines, fft 7 (4096)', fftSize: 4096, signal: { type: 'two', binA: 50, binB: 300, amp: 0.4 }, reads: [4096, 4224, 8192, 12288] },
];

const page = `<!doctype html><html><body><script>
const SR = ${SR};
const CASES = ${JSON.stringify(cases)};
const generate = (c, n) => {
  const s = c.signal, N = c.fftSize, out = new Float32Array(n);
  let x = (s.seed || 0) >>> 0;
  for (let i = 0; i < n; i++) {
    if (s.type === 'sine') out[i] = s.amp * Math.sin((2 * Math.PI * s.bin * i) / N);
    else if (s.type === 'two') out[i] = s.amp * (Math.sin((2 * Math.PI * s.binA * i) / N) + Math.sin((2 * Math.PI * s.binB * i) / N)) * 0.5;
    else if (s.type === 'noise') { x = (x * 1664525 + 1013904223) % 4294967296; out[i] = (x / 2147483648 - 1) * s.amp; }
    else if (s.type === 'ramp') out[i] = s.amp * (((i % 512) / 512) * 2 - 1);
  }
  return out;
};
const toB64 = (f) => { const u = new Uint8Array(f.buffer, f.byteOffset, f.byteLength); let s = ''; for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]); return btoa(s); };
(async () => {
  try {
    const results = [];
    for (const c of CASES) {
      const total = Math.max(...c.reads) + 256;
      const ctx = new OfflineAudioContext(1, total, SR);
      const buffer = ctx.createBuffer(1, total, SR);
      buffer.copyToChannel(generate(c, total), 0);
      const src = ctx.createBufferSource();
      src.buffer = buffer;
      const analyser = ctx.createAnalyser();
      analyser.fftSize = c.fftSize;
      analyser.smoothingTimeConstant = 0.5;
      src.connect(analyser);
      analyser.connect(ctx.destination);
      src.start(0);
      const reads = [];
      for (const frame of c.reads) {
        ctx.suspend(frame / SR).then(() => {
          const td = new Float32Array(analyser.frequencyBinCount);
          const fd = new Float32Array(analyser.frequencyBinCount);
          analyser.getFloatTimeDomainData(td);
          analyser.getFloatFrequencyData(fd);
          reads.push({ frame, td: toB64(td), fd: toB64(fd) });
          ctx.resume();
        });
      }
      await ctx.startRendering();
      results.push({ ...c, reads });
    }
    await fetch('/result', { method: 'POST', body: JSON.stringify({ userAgent: navigator.userAgent, results }) });
  } catch (e) {
    await fetch('/result', { method: 'POST', body: JSON.stringify({ error: String(e) + (e && e.stack) }) });
  }
})();
</script></body></html>`;

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'analyser-oracle-'));
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

const doc = {
  generator: 'tools/strudel-ref/analyser-oracle.mjs',
  commit: COMMIT,
  source: 'AnalyserNode (fftSize 2^(fft+5), smoothingTimeConstant 0.5) of an OfflineAudioContext suspended at the frames of the reads, in ' + body.userAgent,
  sampleRate: SR,
  cases: body.results.map((c) => ({ ...c, frames: Math.max(...c.reads.map((r) => r.frame)) + 256 })),
};
const out = path.join(root, 'engine/src/test/resources/visual/analyser.json');
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, JSON.stringify(doc) + '\n');
console.log(`${path.relative(root, out)}: ${doc.cases.length} cases, ${doc.cases.reduce((n, c) => n + c.reads.length, 0)} reads (${body.userAgent})`);
