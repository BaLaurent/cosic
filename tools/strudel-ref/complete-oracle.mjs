// Reference data for the autocomplete of the editor (app/src/test/java/com/octopusman/cosic/reference/{FuzzyMatcherTest,CompleterTest}.kt).
//
// Strudel's editor offers the names of its documented functions as you type (packages/codemirror/autocomplete.mjs): CodeMirror's own
// `FuzzyMatcher` scores every label against the word before the cursor, and the options are sorted by score and then by `localeCompare`
// (@codemirror/autocomplete at the version the pinned lockfile installs, `sortOptions`). This runs that code, taken as it is out of the
// package, over the labels the app lists (engine/src/main/resources/cosic/reference/docs.json), so the Kotlin port can be compared with it
// for every word that is typed.
//
//   node tools/strudel-ref/complete-oracle.mjs        writes app/src/test/resources/completion/oracle.json
//
// What is stored:
//   labels      the options of the list in order: the names and synonyms of the cards, a label once (`jsdocCompletions` of autocomplete.mjs)
//   sorted      all labels by `localeCompare('en')`, the order that decides between options of one score
//   matches     [{ pattern, results: [[index into labels, score, matched ranges]...], count }]: for a word, the options in the order
//               CodeMirror lists them (the first 40; `count` is how many match). One matcher goes over the labels in order, as in
//               CodeMirror, whose matcher keeps a buffer between labels
//   explicit    the options for an empty word, which Ctrl-Space opens (the first 40)
//
// The words are all the prefixes of a few dozen labels, and strings that are no prefix of anything (`ff`, `rvb`, `LPF`).
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.COSIC_ROOT ?? path.resolve(here, '../..');
const strudel = path.join(here, 'strudel');
if (!fs.existsSync(path.join(strudel, '.fetched'))) {
  throw new Error('tools/strudel-ref/strudel is missing: run `bash tools/strudel-ref/fetch.sh` first');
}
const output = path.join(root, 'app/src/test/resources/completion/oracle.json');
const docsFile = path.join(root, 'engine/src/main/resources/cosic/reference/docs.json');
const commit = JSON.parse(fs.readFileSync(path.join(root, 'engine/src/test/resources/parity/meta.json'), 'utf8')).strudelCommit;

// ---- the matcher of CodeMirror, as it is in its package ---------------------------------------------------------------------

const pnpm = path.join(strudel, 'node_modules/.pnpm');
const autocompleteDir = fs.readdirSync(pnpm).find((d) => d.startsWith('@codemirror+autocomplete@'));
const stateDir = fs.readdirSync(pnpm).find((d) => d.startsWith('@codemirror+state@'));
const autocompleteVersion = autocompleteDir.replace('@codemirror+autocomplete@', '');
const source = fs.readFileSync(path.join(pnpm, autocompleteDir, 'node_modules/@codemirror/autocomplete/dist/index.js'), 'utf8');
const start = source.indexOf('class FuzzyMatcher {');
const end = source.indexOf('class StrictMatcher {');
if (start < 0 || end < start) throw new Error('the FuzzyMatcher of @codemirror/autocomplete is not where it was');
const { codePointAt, codePointSize, fromCodePoint } = createRequire(path.join(pnpm, stateDir, 'node_modules/@codemirror/state/'))('@codemirror/state');
const FuzzyMatcher = new Function('codePointAt', 'codePointSize', 'fromCodePoint', `${source.slice(start, end)}\nreturn FuzzyMatcher;`)(
  codePointAt,
  codePointSize,
  fromCodePoint,
);

// ---- the labels: jsdocCompletions of autocomplete.mjs, over the cards the app has --------------------------------------------

const cards = JSON.parse(fs.readFileSync(docsFile, 'utf8'));
const labels = [];
const seen = new Set();
for (const doc of cards) {
  if (doc.hidden) continue; // Strudel's editor shows the card of these, but does not offer the name
  for (const label of [doc.name, ...(doc.synonyms ?? [])]) {
    if (label && !seen.has(label)) {
      seen.add(label);
      labels.push(label);
    }
  }
}

// ---- the sort of CodeMirror: score, then localeCompare ----------------------------------------------------------------------

const compare = (a, b) => a.localeCompare(b, 'en');

function complete(pattern) {
  const matcher = new FuzzyMatcher(pattern);
  const options = [];
  labels.forEach((label, index) => {
    const match = matcher.match(label);
    if (match) options.push({ index, label, score: match.score, matched: match.matched.slice() });
  });
  options.sort((a, b) => b.score - a.score || compare(a.label, b.label));
  return options;
}

const RESULTS = 40;
const summarize = (pattern) => {
  const options = complete(pattern);
  return {
    pattern,
    count: options.length,
    results: options.slice(0, RESULTS).map((o) => [o.index, o.score, o.matched]),
  };
};

// ---- the words ----------------------------------------------------------------------------------------------------------------

const SAMPLE = ['fast', 'slow', 'note', 'lpf', 'room', 'jux', 'every', 'off', 'stack', 'euclid', 'rev', 'sound', 's', 'n', 'gain', 'pan', 'delay', 'bank', 'scale', 'sometimes', 'degradeBy', 'hurry', 'fastGap', 'chop', 'striate', 'segment', 'range', 'sine', 'perlin', 'irand', 'choose', 'pick', 'wavetable', 'cutoff', 'hcutoff', 'adsr', 'orbit', 'zoom', 'linger', 'palindrome', 'swingBy', 'chunk', 'voicing', 'arp', 'rootNotes', 'scaleTranspose', 'pianoroll', 'midi', 'setcpm', 'hush'];
const words = new Set(['', 'ff', 'rvb', 'lpf', 'bdrm', 'sz', 'dl', 'fg', 'fgap', 'FG', 'Fa', 'sGa', 'LPF', 'Hurr', 'zz', 'qq', 'xyz', 'a1', '_', '9', 'N', 'e', 'E', 'ZOOM', 'STACK', 'fa st', 'f-a', 'é', 'λ', '🎵', 'ø', 'room1', 'sine2', 'saw2', 'rand2', 'rotA', 'accX', 'oriG']);
for (const name of SAMPLE) {
  const label = labels.find((l) => l === name) ?? name;
  for (let n = 1; n <= label.length; n++) words.add(label.slice(0, n));
  words.add(label.toUpperCase());
  words.add(label[0].toUpperCase() + label.slice(1));
}
// two letters of a label that are not next to each other: the matches by word starts and by gaps
for (const name of ['setcpm', 'euclidRot', 'scaleTranspose', 'degradeBy', 'rootNotes', 'fastGap']) {
  for (let i = 0; i < name.length - 2; i += 2) words.add(name[i] + name[i + 2]);
  words.add(name.replace(/[aeiou]/g, ''));
}

const matches = [...words].filter((w) => w !== '').map(summarize);
const result = {
  strudelCommit: commit,
  node: process.version,
  codemirrorAutocomplete: autocompleteVersion,
  labels,
  sorted: [...labels].sort(compare),
  matches,
  explicit: summarize('').results,
};
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, `${JSON.stringify(result)}\n`);
console.log(`${path.relative(root, output)}: ${labels.length} labels, ${matches.length} words (${autocompleteVersion})`);
