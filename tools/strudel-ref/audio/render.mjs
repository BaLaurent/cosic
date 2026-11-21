// Renders the audio parity corpus (corpus.json and the tunes of the pinned checkout) with Strudel's own offline renderer (Task 5.2):
//
//   node tools/strudel-ref/audio/render.mjs [--only id,prefix*,tunes] [--jobs 3] [--timeout 900] [--no-build] [--list]
//
// For every entry it opens a fresh headless Chromium on a page (page.mjs, built with the checkout's own vite and plugin) that runs the REPL's prebake, evaluates the
// program and calls renderPatternAudio of packages/webaudio/webaudio.mjs (the real file, with the one change that it returns the AudioBuffer instead of downloading
// a WAV), computes the features of features.mjs on both channels and posts them back here. This process is the page's server and its only door to the network:
//   * every http(s) fetch of the page comes through /proxy and is stored under ~/.cache/cosic-samples/<host>/<path> (COSIC_SAMPLE_CACHE), the cache AudioParityTest
//     reads its samples, sample maps and soundfonts from, so the engine plays the files the reference played;
//   * what Chromium decoded from files that are not WAV (the piano's mp3, the zones of the soundfonts) is stored as a 32-bit float WAV under
//     <cache>/_decoded/<sha1 of the encoded bytes>.wav, which is what the engine's test store reads for them (no mp3 decoder on the JVM);
//   * the Dirt-Samples map prebake.mjs passes inline is written to <cache>/_prebake/dirt-samples.json;
//   * the raw PCM of every render goes to ~/.cache/cosic-audio-ref/<id>.f32 (planar float32, left then right; COSIC_AUDIO_REF) for diagnosing a failure.
// Output: engine/src/test/resources/parity/audio/<id>.json, one per entry that rendered; the features only, never the audio.
//
// Each entry runs in its own Chromium process: the sounds a program registers, the cut groups and the loaded files of one run never reach the next, and a hang kills
// only that entry. STRUDEL_DIR (default tools/strudel-ref/strudel) is the checkout of fetch.sh, CHROMIUM the browser (default `chromium`).
import crypto from 'node:crypto';
import { averageLongTerm, longTerm } from './features.mjs';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { execFileSync, spawn } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const COMMIT = '84ef32eec8074f90c6690ab8d6a6c204f508b182';
const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '../../..');
const strudel = fs.realpathSync(process.env.STRUDEL_DIR ?? path.join(here, '..', 'strudel'));
const pageDir = path.join(strudel, 'cosic-audio');
const dist = path.join(pageDir, 'dist');
let outDir = path.join(repo, 'engine/src/test/resources/parity/audio');
const cacheRoot = process.env.COSIC_SAMPLE_CACHE ?? path.join(os.homedir(), '.cache/cosic-samples');
const pcmDir = process.env.COSIC_AUDIO_REF ?? path.join(os.homedir(), '.cache/cosic-audio-ref');
const chromium = process.env.CHROMIUM ?? 'chromium';

const args = process.argv.slice(2);
const flag = (name) => args.includes(`--${name}`);
const option = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const jobs = Number(option('jobs', 3));
const timeoutSeconds = Number(option('timeout', 900));
const only = option('only', '')
  .split(',')
  .filter(Boolean);
const extraEntries = option('entries', '');
outDir = option('out', outDir);

// ------------------------------------------------------------------------------------------------------------------------------------------------------ corpus
const corpus = JSON.parse(fs.readFileSync(path.join(here, 'corpus.json'), 'utf8'));
const NOISY = /\b(white|pink|brown|crackle|rand|rand2|brand|irand|zrand|noise|z_noise)\b/;

async function loadEntries() {
  const entries = [...corpus.entries, ...(extraEntries ? JSON.parse(fs.readFileSync(extraEntries, 'utf8')) : [])].map((e) => ({ cps: 0.5, ...e, noisy: e.noisy ?? NOISY.test(e.code) }));
  const tunes = await import(pathToFileURL(path.join(strudel, 'website/src/repl/tunes.mjs')).href);
  for (const name of corpus.tunes.names) {
    if (typeof tunes[name] !== 'string') throw new Error(`tune ${name} is not in tunes.mjs`);
    if (corpus.tunes.skip?.[name]) continue;
    entries.push({ id: `tune-${name}`, group: 'tunes', code: tunes[name], cycles: corpus.tunes.cycles, cps: corpus.tunes.cps, noisy: NOISY.test(tunes[name]), random: corpus.tunes.random?.[name] });
  }
  const ids = new Set();
  for (const e of entries) {
    if (ids.has(e.id)) throw new Error(`duplicate id ${e.id}`);
    ids.add(e.id);
  }
  return entries;
}

function selected(entries) {
  if (!only.length) return entries;
  const matches = (e, pattern) =>
    pattern === e.group || pattern === e.id || (pattern.endsWith('*') && e.id.startsWith(pattern.slice(0, -1)));
  return entries.filter((e) => only.some((p) => matches(e, p)));
}

// ------------------------------------------------------------------------------------------------------------------------------------------------------- page
function sourceStamp() {
  const h = crypto.createHash('sha1');
  for (const f of ['index.html', 'page.mjs', 'patch.mjs', 'features.mjs', 'vite.config.mjs']) h.update(fs.readFileSync(path.join(here, f)));
  h.update(fs.readFileSync(path.join(strudel, 'packages/webaudio/webaudio.mjs')));
  return h.digest('hex');
}

/**
 * webaudio.mjs with two changes: renderPatternAudio returns the AudioBuffer it rendered instead of making a WAV download of it, and scheduleHapsChunk hands superdough the onset
 * of the hap in cycles as its `cycle` argument, which is what the live path (webaudioOutput: hap.whole.begin) passes. The export passes the onset in seconds there
 * ((hap.whole.begin - begin) / cps), so every effect whose start phase comes from `cycle / cps` (the tremolo, the LFOs of the filters) starts at another phase in an export than in the REPL.
 */
function renderModule() {
  const source = fs.readFileSync(path.join(strudel, 'packages/webaudio/webaudio.mjs'), 'utf8');
  const download = /\.then\(\(renderedBuffer\) => \{[\s\S]*?\n    \}\)\n    \.finally/;
  if (!download.test(source)) throw new Error('webaudio.mjs is not the file this script was written for (the WAV download of renderPatternAudio)');
  const cycleArgument = /(\n {10}cps,\n {10})\(hap\.whole\?\.begin\.valueOf\(\) - begin\) \/ cps(,\n {8}\);)/;
  if (!cycleArgument.test(source)) throw new Error('webaudio.mjs is not the file this script was written for (the cycle argument of scheduleHapsChunk)');
  return source.replace(download, '.then((renderedBuffer) => renderedBuffer)\n    .finally').replace(cycleArgument, '$1hap.whole.begin.valueOf()$2');
}

/** The Dirt-Samples map prebake.mjs passes to samples() inline. */
function dirtMap() {
  const text = fs.readFileSync(path.join(strudel, 'website/src/repl/prebake.mjs'), 'utf8');
  const m = /samples\(\s*(\{[\s\S]*?\n {6}\}),\s*`\$\{baseCDN\}\/Dirt-Samples\/`/.exec(text);
  if (!m) throw new Error('the Dirt-Samples map was not found in prebake.mjs');
  return new Function(`return ${m[1]}`)();
}

function buildPage() {
  fs.mkdirSync(pageDir, { recursive: true });
  for (const f of ['index.html', 'page.mjs', 'patch.mjs', 'features.mjs', 'vite.config.mjs']) fs.copyFileSync(path.join(here, f), path.join(pageDir, f));
  fs.writeFileSync(path.join(pageDir, 'webaudio-render.mjs'), renderModule());
  const dirt = dirtMap();
  fs.writeFileSync(path.join(pageDir, 'dirt-samples.json'), JSON.stringify(dirt));
  fs.mkdirSync(path.join(cacheRoot, '_prebake'), { recursive: true });
  fs.writeFileSync(path.join(cacheRoot, '_prebake', 'dirt-samples.json'), JSON.stringify(dirt));
  const stamp = sourceStamp();
  const stampFile = path.join(dist, '.stamp');
  if (!flag('no-build') && !(fs.existsSync(stampFile) && fs.readFileSync(stampFile, 'utf8') === stamp)) {
    const vite = path.join(strudel, 'packages/superdough/node_modules/vite/bin/vite.js');
    console.log('building the page with', vite);
    execFileSync(process.execPath, [vite, 'build', '--config', path.join(pageDir, 'vite.config.mjs')], { cwd: strudel, stdio: 'inherit' });
    fs.copyFileSync(path.join(pageDir, 'dirt-samples.json'), path.join(dist, 'dirt-samples.json'));
    fs.writeFileSync(stampFile, stamp);
  }
  if (!fs.existsSync(path.join(dist, 'index.html'))) throw new Error('no built page: run without --no-build');
}

// ----------------------------------------------------------------------------------------------------------------------------------------------- the cache
/** <cache>/<host>/<path and query as written>; the Kotlin store (CacheSampleStore) makes the same name of the same text. */
export function cacheFile(url) {
  const m = /^https?:\/\/([^/?#]+)([^#]*)/i.exec(url);
  if (!m) throw new Error(`not an http(s) url: ${url}`);
  let rest = m[2].replace(/ /g, '%20').replace(/\.\./g, '%2E%2E');
  if (rest === '' || rest.startsWith('?')) rest = '/' + rest;
  if (rest.endsWith('/')) rest += '_index';
  return path.join(cacheRoot, m[1], ...rest.split('/').filter(Boolean));
}

const MIME = { '.json': 'application/json', '.wav': 'audio/wav', '.mp3': 'audio/mpeg', '.ogg': 'audio/ogg', '.flac': 'audio/flac', '.js': 'text/javascript', '.mjs': 'text/javascript', '.html': 'text/html; charset=utf-8', '.css': 'text/css', '.wasm': 'application/wasm' };
const mime = (file) => MIME[path.extname(file).toLowerCase()] ?? 'application/octet-stream';

const inflight = new Map();
const downloaded = new Set();
async function download(url) {
  const file = cacheFile(url);
  if (fs.existsSync(file)) return { status: 200, file };
  const marker = `${file}.missing`;
  if (fs.existsSync(marker)) return { status: Number(fs.readFileSync(marker, 'utf8')) || 502, body: Buffer.from('missing') };
  if (!inflight.has(url)) {
    inflight.set(
      url,
      (async () => {
        // a sample that cannot be had is what the reference played as silence ("Unable to decode audio data"): the marker says so to the engine's test store
        const failed = (status, body) => {
          fs.mkdirSync(path.dirname(file), { recursive: true });
          fs.writeFileSync(marker, String(status));
          return { status, body };
        };
        let res;
        try {
          res = await fetch(url, { redirect: 'follow', signal: AbortSignal.timeout(60000) });
        } catch (e) {
          return failed(502, Buffer.from(String(e)));
        }
        if (!res.ok) return failed(res.status, Buffer.from(await res.arrayBuffer()));
        const body = Buffer.from(await res.arrayBuffer());
        fs.mkdirSync(path.dirname(file), { recursive: true });
        const tmp = `${file}.part${process.pid}`;
        fs.writeFileSync(tmp, body);
        fs.renameSync(tmp, file);
        downloaded.add(url);
        return { status: 200, file };
      })().finally(() => inflight.delete(url)),
    );
  }
  return inflight.get(url);
}

// ---------------------------------------------------------------------------------------------------------------------------------------------- the server
const waiting = new Map();
const entriesById = new Map();

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  try {
    if (req.method === 'POST') {
      const body = await readBody(req);
      if (url.pathname.startsWith('/result/')) {
        const id = decodeURIComponent(url.pathname.slice(8));
        waiting.get(id)?.(JSON.parse(body.toString('utf8')));
      } else if (url.pathname.startsWith('/pcm/')) {
        fs.mkdirSync(pcmDir, { recursive: true });
        fs.writeFileSync(path.join(pcmDir, `${decodeURIComponent(url.pathname.slice(5))}.f32`), body);
      } else if (url.pathname.startsWith('/decoded/')) {
        const file = path.join(cacheRoot, '_decoded', `${url.pathname.slice(9)}.wav`);
        if (!fs.existsSync(file)) {
          fs.mkdirSync(path.dirname(file), { recursive: true });
          fs.writeFileSync(`${file}.part${process.pid}`, body);
          fs.renameSync(`${file}.part${process.pid}`, file);
        }
      }
      res.end('ok');
    } else if (url.pathname.startsWith('/entry/')) {
      const entry = entriesById.get(decodeURIComponent(url.pathname.slice(7)));
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify(entry ?? null));
    } else if (url.pathname === '/proxy') {
      const target = url.searchParams.get('u');
      const got = await download(target);
      if (got.status !== 200) {
        res.statusCode = got.status;
        res.end(got.body);
      } else {
        res.setHeader('content-type', mime(got.file));
        fs.createReadStream(got.file).pipe(res);
      }
    } else {
      const rel = url.pathname === '/' ? '/index.html' : decodeURIComponent(url.pathname);
      const file = path.join(dist, rel);
      if (!file.startsWith(dist + path.sep) || !fs.existsSync(file)) {
        res.statusCode = 404;
        res.end('not found');
      } else {
        res.setHeader('content-type', mime(file));
        fs.createReadStream(file).pipe(res);
      }
    }
  } catch (e) {
    res.statusCode = 500;
    res.end(String((e && e.stack) || e));
  }
});

// --------------------------------------------------------------------------------------------------------------------------------------------- one entry
const CHROMIUM_FLAGS = [
  '--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage', '--mute-audio', '--no-first-run', '--no-default-browser-check',
  '--disable-extensions', '--disable-background-timer-throttling', '--disable-renderer-backgrounding', '--disable-backgrounding-occluded-windows',
  '--autoplay-policy=no-user-gesture-required',
];

async function runEntry(entry, port, extraSeed = 0) {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'cosic-audio-'));
  const result = new Promise((resolve) => waiting.set(entry.id, resolve));
  const seed = 1 + (parseInt(crypto.createHash('sha1').update(entry.id).digest('hex').slice(0, 6), 16) >>> 0) + Number(option('seed', 0)) + extraSeed;
  const url = `http://127.0.0.1:${port}/index.html?id=${encodeURIComponent(entry.id)}&seed=${seed}${extraSeed ? '&pcm=0' : ''}`;
  const child = spawn(chromium, [...CHROMIUM_FLAGS, `--user-data-dir=${profile}`, url], { stdio: 'ignore' });
  let timer;
  const timeout = new Promise((resolve) => (timer = setTimeout(() => resolve({ error: `timeout after ${timeoutSeconds} s` }), timeoutSeconds * 1000)));
  const exited = new Promise((resolve) => child.on('exit', (code) => setTimeout(() => resolve({ error: `chromium exited with ${code} before the page reported` }), 2000)));
  const outcome = await Promise.race([result, timeout, exited]);
  clearTimeout(timer);
  waiting.delete(entry.id);
  const gone = new Promise((resolve) => (child.exitCode !== null ? resolve() : child.once('exit', resolve)));
  child.kill('SIGKILL');
  await Promise.race([gone, new Promise((resolve) => setTimeout(resolve, 5000))]);
  try {
    fs.rmSync(profile, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
  } catch {
    // a helper process of the browser still writes there; the folder is in /tmp and goes with it
  }
  return outcome;
}

const interesting = (line) => /error|not found|cannot|fail|skip|warn|invalid|unexpected/i.test(line);

function store(entry, outcome, averaged) {
  if (outcome.error || !outcome.features) return false;
  const file = path.join(outDir, `${entry.id}.json`);
  const doc = {
    id: entry.id,
    group: entry.group,
    code: entry.code,
    cycles: entry.cycles,
    cps: outcome.cps,
    noisy: entry.noisy,
    random: entry.random,
    compareCycles: entry.compareCycles,
    note: entry.note,
    reference: { strudelCommit: COMMIT, userAgent: outcome.userAgent, maxPolyphony: 128, multiChannelOrbits: false },
    logs: (outcome.logs ?? []).filter(interesting),
    features: outcome.features,
    longTerm: averaged,
  };
  fs.mkdirSync(outDir, { recursive: true });
  fs.writeFileSync(file, JSON.stringify(doc) + '\n');
  return true;
}

// ------------------------------------------------------------------------------------------------------------------------------------------------- main
const all = await loadEntries();
const todo = selected(all);
if (flag('list')) {
  for (const e of todo) console.log(e.id, `${e.cycles} cycles`, e.noisy ? '(noisy)' : '');
  process.exit(0);
}
buildPage();
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const port = server.address().port;
for (const e of todo) entriesById.set(e.id, e);

const rows = [];
let next = 0;
async function worker() {
  while (next < todo.length) {
    const entry = todo[next++];
    const started = Date.now();
    let outcome = await runEntry(entry, port);
    for (let attempt = 1; attempt < 4 && outcome.error && /^(timeout|chromium exited)|cannot resume/.test(outcome.error); attempt++) outcome = await runEntry(entry, port);
    let averaged;
    if (entry.random && outcome.features) {
      // the reference of a sound that is random by design is the mean of several renders: one of them is a draw of a spread that is about a dB for a reverb tail
      const runs = [longTerm(outcome.features)];
      for (let k = 1; k < Number(option('random-seeds', 5)); k++) {
        const more = await runEntry(entry, port, k);
        if (more.features) runs.push(longTerm(more.features));
      }
      averaged = averageLongTerm(runs);
    }
    const ok = store(entry, outcome, averaged);
    const f = outcome.features;
    rows.push({ id: entry.id, ok, error: outcome.error, peak: f?.peakDb, rms: f?.rmsDb, onsets: f?.channels[0].onsets.length, logs: (outcome.logs ?? []).filter(interesting), seconds: ((Date.now() - started) / 1000).toFixed(0) });
    const r = rows[rows.length - 1];
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${entry.id.padEnd(34)} ${r.seconds.padStart(4)} s  peak ${r.peak?.toFixed?.(1) ?? '-'} dB  rms ${r.rms?.toFixed?.(1) ?? '-'} dB  onsets ${r.onsets ?? '-'}${r.error ? `  ${r.error.split('\n')[0]}` : ''}`);
    if (r.error && outcome.trace) for (const line of outcome.trace.slice(0, 60)) console.log(`       trace: ${line}`);
    for (const line of r.logs.slice(0, 4)) console.log(`       log: ${line.slice(0, 200)}`);
  }
}
await Promise.all(Array.from({ length: Math.max(1, jobs) }, worker));
server.close();
console.log(`${rows.filter((r) => r.ok).length}/${rows.length} entries rendered; ${downloaded.size} files downloaded into ${cacheRoot}`);
process.exit(rows.every((r) => r.ok) ? 0 : 1);
