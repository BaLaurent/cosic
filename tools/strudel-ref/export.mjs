#!/usr/bin/env node
// Exports the parity oracle data from the pinned Strudel checkout. Run tools/strudel-ref/fetch.sh first.
//
//   node tools/strudel-ref/export.mjs                          examples.json, tunes.json, strudel-names.json, meta.json
//   node tools/strudel-ref/export.mjs --extra <id|a,b|all>     and extra/<id>.json from extra-snippets/<id>.json
//
// Everything is written to engine/src/test/resources/parity/. The data is deterministic: running the script again on
// the same checkout leaves git status clean. See docs/parity/README.md.
import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const COMMIT = '84ef32eec8074f90c6690ab8d6a6c204f508b182';
const EXAMPLE_CYCLES = 4; // test/examples.test.mjs:34

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');
const src = path.join(here, 'strudel');
const out = path.join(root, 'engine/src/test/resources/parity');

const fail = (message) => {
  console.error(`export.mjs: ${message}`);
  process.exit(1);
};
const warn = (message) => console.warn(`export.mjs: warning: ${message}`);

// ---- arguments and checkout -------------------------------------------------------------------------------------

let extra = null;
const args = process.argv.slice(2);
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--extra') {
    extra = args[++i] ?? fail('--extra needs a task id, a comma separated list of ids, or `all`');
  } else {
    fail(`unknown argument ${args[i]}`);
  }
}

if (!fs.existsSync(path.join(src, '.fetched'))) fail('no Strudel checkout, run tools/strudel-ref/fetch.sh first');
if (fs.readFileSync(path.join(src, '.fetched'), 'utf8').trim() !== COMMIT) fail(`the checkout is not at ${COMMIT}`);
if (!fs.existsSync(path.join(src, 'doc.json')) || !fs.existsSync(path.join(src, '.runner'))) {
  fail('the checkout is not fully prepared (doc.json or .runner missing), run tools/strudel-ref/fetch.sh');
}
const runner = fs.readFileSync(path.join(src, '.runner'), 'utf8').trim();

// ---- snapshots --------------------------------------------------------------------------------------------------

/**
 * Vitest v1 snapshot files are scripts assigning `exports[key] = <template literal>`. Evaluating one gives the
 * serialized arrays, whose lines are `  "<hap string>",` (pretty-format without string escaping) and are kept verbatim.
 */
function readSnapshot(file) {
  const exported = {};
  new Function('exports', fs.readFileSync(file, 'utf8'))(exported);
  const entries = new Map();
  for (const [key, text] of Object.entries(exported)) {
    const body = text.trim();
    if (body === '[]') {
      entries.set(key, []);
      continue;
    }
    const lines = body.split('\n');
    if (lines[0] !== '[' || lines[lines.length - 1] !== ']') fail(`${file}: ${key} is not a serialized array`);
    entries.set(
      key,
      lines.slice(1, -1).map((line) => {
        const match = /^ {2}"(.*)",?$/.exec(line);
        if (!match) fail(`${file}: unexpected line in ${key}: ${JSON.stringify(line)}`);
        if (match[1].includes('\\"')) warn(`${key} contains an escaped quote, check the exported string`);
        return match[1];
      }),
    );
  }
  return entries;
}

const examplesSnapshotFile = path.join(src, 'test/__snapshots__/examples.test.mjs.snap');
const tunesSnapshotFile = path.join(src, 'test/__snapshots__/tunes.test.mjs.snap');
const examplesSnapshot = readSnapshot(examplesSnapshotFile);
const tunesSnapshot = readSnapshot(tunesSnapshotFile);

const byName = (a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : a.index - b.index);
const writeEntries = (file, entries) =>
  fs.writeFileSync(file, `[\n${entries.map((entry) => JSON.stringify(entry)).join(',\n')}\n]\n`);
const sha256 = (data) => crypto.createHash('sha256').update(data).digest('hex');

// ---- examples.json ----------------------------------------------------------------------------------------------

const examplesTest = fs.readFileSync(path.join(src, 'test/examples.test.mjs'), 'utf8');
const skipBlock = /const skippedExamples = \[([\s\S]*?)\]/.exec(examplesTest) ?? fail('skippedExamples not found');
const skippedNames = new Set([...skipBlock[1].matchAll(/'([^']+)'/g)].map((match) => match[1]));

const { docs } = JSON.parse(fs.readFileSync(path.join(src, 'doc.json'), 'utf8'));
const usedKeys = new Set();
const occurrences = new Map();
const examples = [];
for (const doc of docs) {
  (doc.examples ?? []).forEach((code, index) => {
    if (typeof doc.name !== 'string' || !/^\S+$/.test(doc.name)) fail(`unusable doc name ${JSON.stringify(doc.name)}`);
    const skipped = skippedNames.has(doc.name);
    let haps = null;
    if (!skipped) {
      // vitest appends a counter per identical test name; some names are documented twice
      const base = `runs examples > example "${doc.name}" example index ${index}`;
      const occurrence = (occurrences.get(base) ?? 0) + 1;
      occurrences.set(base, occurrence);
      const key = `${base} ${occurrence}`;
      if (examplesSnapshot.has(key)) {
        haps = examplesSnapshot.get(key);
        usedKeys.add(key);
      } else {
        warn(`no snapshot for example "${doc.name}" index ${index}`);
      }
    }
    examples.push({ name: doc.name, index, code, cycles: EXAMPLE_CYCLES, skipped, haps });
  });
}
examples.sort(byName);
const unusedExampleKeys = [...examplesSnapshot.keys()].filter(
  (key) => key.startsWith('runs examples >') && !usedKeys.has(key),
);
for (const key of unusedExampleKeys) warn(`unused snapshot key: ${key}`);

// ---- tunes.json -------------------------------------------------------------------------------------------------

const tuneModule = await import(pathToFileURL(path.join(src, 'website/src/repl/tunes.mjs')).href);
const runtimeSource = fs.readFileSync(path.join(src, 'test/runtime.mjs'), 'utf8');
const cyclesBlock = /export const testCycles = (\{[\s\S]*?\n\});/.exec(runtimeSource) ?? fail('testCycles not found');
const testCycles = new Function(`return ${cyclesBlock[1]}`)();
const usedTuneKeys = new Set();
const tunes = Object.keys(tuneModule).map((name) => {
  const key = `renders tunes > tune: ${name} 1`;
  const haps = tunesSnapshot.get(key) ?? null;
  if (haps) usedTuneKeys.add(key);
  else warn(`no snapshot for tune ${name}`);
  return { name, index: 0, code: tuneModule[name], cycles: testCycles[name] || 1, skipped: false, haps };
});
tunes.sort(byName);
for (const key of tunesSnapshot.keys()) if (!usedTuneKeys.has(key)) warn(`unused snapshot key: ${key}`);

fs.mkdirSync(path.join(out, 'extra'), { recursive: true });
writeEntries(path.join(out, 'examples.json'), examples);
writeEntries(path.join(out, 'tunes.json'), tunes);

// ---- vitest runs inside the checkout (names, extra snippets) ------------------------------------------------------

function runVitest(testFile, env) {
  fs.copyFileSync(path.join(here, testFile), path.join(src, 'test', testFile));
  // The checkout's own config (vitest.config.mjs) is what makes `import '../website/src/repl/piano'` resolve.
  const command = `${runner} pnpm exec vitest run test/${testFile}`;
  const result = spawnSync(command, {
    shell: true,
    cwd: src,
    env: { ...process.env, ...env },
    encoding: 'utf8',
    maxBuffer: 1 << 28,
  });
  if (result.status !== 0) {
    process.stderr.write(`${result.stdout}\n${result.stderr}\n`);
    fail(`${command} failed with status ${result.status}`);
  }
  return /^\s*Tests\s+.*$/m.exec(result.stdout)?.[0].trim() ?? 'finished';
}

console.log(`names: ${runVitest('names.test.mjs', { COSIC_ROOT: root })}`);
if (extra !== null) console.log(`extra snippets (${extra}): ${runVitest('cosic-extra.test.mjs', { COSIC_ROOT: root, COSIC_EXTRA: extra })}`);

// ---- meta.json and the counts ------------------------------------------------------------------------------------

const names = JSON.parse(fs.readFileSync(path.join(out, 'strudel-names.json'), 'utf8'));
const extraFiles = fs.readdirSync(path.join(out, 'extra')).filter((file) => file.endsWith('.json')).sort();
const extraCases = extraFiles.reduce(
  (count, file) => count + JSON.parse(fs.readFileSync(path.join(out, 'extra', file), 'utf8')).length,
  0,
);
const nodeVersion = spawnSync(`${runner} node --version`, { shell: true, cwd: src, encoding: 'utf8' }).stdout.trim();

const counts = {
  docs: docs.length,
  examples: examples.length,
  examplesWithSnapshot: examples.filter((example) => example.haps !== null).length,
  examplesSkippedByStrudel: examples.filter((example) => example.skipped).length,
  examplesWithoutSnapshot: examples.filter((example) => !example.skipped && example.haps === null).length,
  tunes: tunes.length,
  tunesWithSnapshot: tunes.filter((tune) => tune.haps !== null).length,
  extraFiles: extraFiles.length,
  extraCases,
  globals: names.globals.length,
  methods: names.methods.length,
  controls: names.controls.length,
};
const meta = {
  strudelCommit: COMMIT,
  node: nodeVersion,
  counts,
  sha256: {
    // doc.json itself embeds absolute paths, so the digest covers only the data the export uses
    docExamples: sha256(JSON.stringify(examples.map((example) => [example.name, example.index, example.code]))),
    examplesSnapshot: sha256(fs.readFileSync(examplesSnapshotFile)),
    tunesSnapshot: sha256(fs.readFileSync(tunesSnapshotFile)),
  },
};
fs.writeFileSync(path.join(out, 'meta.json'), `${JSON.stringify(meta, null, 2)}\n`);

console.log(
  `examples: ${counts.examples} (${counts.examplesWithSnapshot} with snapshots, ${counts.examplesSkippedByStrudel} skipped by ` +
    `Strudel's own skip list, ${counts.examplesWithoutSnapshot} without a snapshot)`,
);
console.log(`tunes: ${counts.tunes} (${counts.tunesWithSnapshot} with snapshots)`);
console.log(`extras: ${counts.extraCases} cases in ${counts.extraFiles} files`);
console.log(`names: ${counts.globals} globals, ${counts.methods} methods, ${counts.controls} controls`);
