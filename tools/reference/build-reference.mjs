// Builds the reference data of the app (what its Reference panel shows for every function) from Strudel's own JSDoc.
//
//   node tools/reference/build-reference.mjs            writes engine/src/main/resources/cosic/reference/functions.json and docs.json
//   node tools/reference/build-reference.mjs --check    exits with 1 when either file is not what this script writes now
//
// Input   tools/strudel-ref/strudel/doc.json                        the JSDoc of the pinned Strudel checkout (tools/strudel-ref/fetch.sh makes it)
//         tools/strudel-ref/strudel/website/src/pages/learn/*.mdx   which guide page a function is documented on (splits the `temporal` tag)
//         engine/src/test/resources/parity/examples.json            what Strudel's own run of each example plays (see `snippet` below)
//         tools/reference/exclusions.txt                            functions Cosic implements whose example cannot run in it yet
// Output  one entry per documented function that has a description and an example, in the order the panel lists them:
//
//   name       the function, as Strudel documents it
//   aliases    its synonyms
//   category   the group the panel lists it under, from Strudel's `@tags` (see CATEGORIES)
//   form       how the first example calls it: "method" (`.fast(2)` on a pattern), "function" (`stack(a, b)`), or null (not called there)
//   params     the JSDoc parameter names, joined; "" when the example calls it without arguments and the JSDoc has none; null when unknown
//   doc        the first sentence of the JSDoc description, as plain text
//   example    the first `@example`, exactly as written
//   snippet    the example as it is inserted into a program: the final expression gets a `$: ` label (a program with labels plays only
//              labelled patterns) unless the example has labels already or Strudel's own run of it plays nothing
//   excluded   (only when set) why the panel does not list the function, from exclusions.txt
//
// The text of every entry is Strudel's. The engine decides which entries are listed (the functions a program can call) and how a
// signature reads (it knows what is a method, a function or a value there): see script/Reference.kt.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..', '..');
const checkout = path.join(root, 'tools/strudel-ref/strudel');
const output = path.join(root, 'engine/src/main/resources/cosic/reference/functions.json');
const docsOutput = path.join(root, 'engine/src/main/resources/cosic/reference/docs.json');
const acorn = createRequire(path.join(checkout, 'packages/transpiler/package.json'))('acorn');

// ---- categories ---------------------------------------------------------------------------------------------------

/** The groups of the panel, in the order it lists them: [key, heading]. */
const CATEGORIES = [
  ['combiners', 'Combining patterns'],
  ['generators', 'Signals & generators'],
  ['time', 'Time'],
  ['conditional', 'Conditional'],
  ['random', 'Random'],
  ['accumulation', 'Accumulation'],
  ['stepwise', 'Stepwise'],
  ['math', 'Value modifiers'],
  ['functional', 'Functional'],
  ['tonal', 'Tonal'],
  ['samples', 'Samples'],
  ['sound', 'Sound'],
  ['amplitude', 'Amplitude'],
  ['envelope', 'Envelopes'],
  ['filter', 'Filters'],
  ['distortion', 'Distortion'],
  ['pitch', 'Pitch'],
  ['lfo', 'Modulation'],
  ['fm', 'FM synthesis'],
  ['wavetable', 'Wavetables'],
  ['orbit', 'Delay, reverb & orbits'],
  ['visualization', 'Visual feedback'],
  ['midi', 'MIDI'],
  ['external_io', 'Input & output'],
  ['superdirt', 'SuperDirt'],
  ['internals', 'Internals'],
  ['other', 'Other'],
];

/**
 * The tag that decides the group of a function that has several, most specific first. `superdough` (every sound parameter)
 * is the last resort; `temporal` is split by the guide page that documents the function (PAGE_CATEGORY).
 */
const TAG_PRIORITY = [
  'visualization', 'midi', 'external_io', 'wavetable', 'fm', 'filter', 'lfo', 'envelope', 'distortion', 'orbit', 'samples', 'tonal',
  'pitch', 'amplitude', 'superdirt', 'stepwise', 'math', 'generators', 'combiners', 'temporal', 'functional', 'internals', 'superdough',
];

/** The group a guide page puts its functions in, for the functions whose tags do not say (`temporal`, or none). */
const PAGE_CATEGORY = {
  'time-modifiers': 'time',
  'conditional-modifiers': 'conditional',
  'random-modifiers': 'random',
  accumulation: 'accumulation',
  'visual-feedback': 'visualization',
  tonal: 'tonal',
  xen: 'tonal',
  stepwise: 'stepwise',
  signals: 'generators',
  factories: 'combiners',
};

/** Functions that Strudel gives no tag and no guide page, or whose group their description changes: `every` is `firstOf` under another name. */
const CATEGORY_OVERRIDES = { edoScale: 'tonal', every: 'conditional', juxFlip: 'time', juxFlipBy: 'time', onSchedule: 'external_io' };

/** The functions each guide page of the website documents, by page name (`learn/time-modifiers.mdx` -> `time-modifiers`). */
function pageOfEachFunction() {
  const pages = path.join(checkout, 'website/src/pages/learn');
  const pageOf = new Map();
  for (const file of fs.readdirSync(pages).filter((f) => f.endsWith('.mdx')).sort()) {
    const page = file.replace(/\.mdx$/, '');
    for (const m of fs.readFileSync(path.join(pages, file), 'utf8').matchAll(/<JsDoc[^>]*?name="([^"]+)"/gs)) {
      const name = m[1].replace(/^Pattern[.#]/, '');
      if (!pageOf.has(name) && PAGE_CATEGORY[page]) pageOf.set(name, page);
    }
  }
  return pageOf;
}

function categoryOf(doc, pageOf) {
  if (CATEGORY_OVERRIDES[doc.name]) return CATEGORY_OVERRIDES[doc.name];
  const tags = (doc.tags ?? []).filter((t) => typeof t === 'string');
  const tag = TAG_PRIORITY.find((t) => tags.includes(t));
  const page = pageOf.get(doc.name);
  if (tag === 'temporal' || tag === undefined) return (page && PAGE_CATEGORY[page]) || (tag === 'temporal' ? 'time' : 'other');
  if (tag === 'superdough') return 'sound';
  return tag;
}

// ---- text ---------------------------------------------------------------------------------------------------------

const ENTITIES = { quot: '"', amp: '&', lt: '<', gt: '>', apos: "'", nbsp: ' ' };

/** [html] as plain text, markup of the JSDoc that was never converted included (`[text](aside)`, `{name}`, a literal `\n`). */
function plainText(html) {
  return html
    .replace(/<\s*br\s*\/?>/gi, ' ')
    .replace(/<\/(p|div|li|ul|ol|pre|h\d|table|tr|td)>/gi, ' ')
    .replace(/<[^>]+>/g, '')
    .replace(/&(?:#(\d+)|#x([0-9a-f]+)|([a-z]+));/gi, (all, dec, hex, name) =>
      dec ? String.fromCodePoint(+dec) : hex ? String.fromCodePoint(parseInt(hex, 16)) : (ENTITIES[name] ?? all),
    )
    .replace(/\[([^\]]+)\]\((https?:[^)]*)\)/g, '$1')
    .replace(/\[([^\]]+)\]\(([^)]*)\)/gs, '$1 ($2)')
    .replace(/\{(\w+)\}/g, '$1')
    .replace(/\\n/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

const ABBREVIATION = /(?:^|[\s(])(?:e\.g|i\.e|vs|etc|approx|cf|incl|w\.r\.t)\.$/i;

/**
 * [text] split after its first sentence: up to the first `.`, `!` or `?` that follows a word and is followed by a capital letter
 * (or a digit, quote, bracket). `0 .. 1`, `0.5`, `e.g.` and `etc. and` do not end it.
 */
function splitSentence(text) {
  for (let i = 0; i < text.length; i++) {
    if (!'.!?'.includes(text[i])) continue;
    let end = i;
    while (end + 1 < text.length && '.!?'.includes(text[end + 1])) end++; // `..` and `?!` are one ending
    const before = text.slice(0, end + 1);
    const rest = text.slice(end + 1);
    const endsWord = i > 0 && !/\s/.test(text[i - 1]);
    if (rest.trim() === '' || (endsWord && /^\s+[A-Z0-9"'`(\[]/.test(rest) && !ABBREVIATION.test(before))) return [before, rest.trim()];
    i = end;
  }
  return [text, ''];
}

/** A sentence ends with one `.`, `!` or `?`: `nothing..` is `nothing.`, `a list:` and a fragment get a full stop. */
function normalizeEnding(sentence) {
  const s = sentence.trim().replace(/\.{2,}$/, '.').replace(/:$/, '.');
  return /[.!?]$/.test(s) ? s : `${s}.`;
}

const MARKER = /^(experimental|deprecated)\.?$/i;
const MAX_DOC = 200;

/** The one-sentence description of a function: see `doc` in the description of the file. */
function docOf(html) {
  const paragraphs = html.split(/<\/p\s*>/i).map(plainText).filter(Boolean);
  let marker = '';
  if (paragraphs.length > 1 && MARKER.test(paragraphs[0])) marker = paragraphs.shift().replace(/\.$/, '');
  let [sentence, rest] = splitSentence(paragraphs[0]);
  if (!marker && rest && MARKER.test(sentence)) {
    // "Deprecated. Like echo, but ...": the marker is no sentence of its own
    marker = sentence.replace(/\.$/, '');
    [sentence] = splitSentence(rest);
  }
  sentence = normalizeEnding(sentence);
  if (marker && !sentence.startsWith('(')) sentence = `(${marker}) ${sentence}`;
  if (sentence.length > MAX_DOC) sentence = `${sentence.slice(0, sentence.lastIndexOf(' ', MAX_DOC)).replace(/[\s,;:(]+$/, '')}…`;
  return sentence;
}

// ---- how a function is called, and the snippet --------------------------------------------------------------------

const escapeRegExp = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** The first call of [names] in [example]: `{ method, end }` (`.name(` on a pattern or `name(`; `end` is just after the parenthesis), or null. */
function firstCall(example, names) {
  let best = null;
  for (const name of names) {
    const re = new RegExp(`(^|[^\\w$])(\\.?)${escapeRegExp(name)}\\s*\\(`, 'g');
    const m = re.exec(example);
    if (m && (best === null || m.index < best.index)) best = { index: m.index, method: m[2] === '.', end: re.lastIndex };
  }
  return best;
}

const MAX_PARAMS = 6;

/** The documented parameters; options of an object parameter (`options.color`) and notes in brackets are no parameters. */
function parametersOf(doc, example, call) {
  const names = (doc.params ?? [])
    .filter((p) => /^[A-Za-z_$][\w$]*$/.test(p.name ?? ''))
    .map((p) => (p.variable ? `...${p.name}` : p.optional ? `${p.name}?` : p.name));
  if (names.length) return (names.length > MAX_PARAMS ? [...names.slice(0, MAX_PARAMS), '...'] : names).join(', ');
  return call && /^\s*\)/.test(example.slice(call.end)) ? '' : null;
}

/** [example] with `$: ` before its final expression: see `snippet` in the description of the file. */
function snippetOf(example, plays) {
  let program;
  try {
    program = acorn.parse(example, { ecmaVersion: 'latest', allowAwaitOutsideFunction: true, allowReturnOutsideFunction: true });
  } catch {
    return example;
  }
  const last = program.body.at(-1);
  if (!plays || !last || last.type !== 'ExpressionStatement' || program.body.some((s) => s.type === 'LabeledStatement')) return example;
  return `${example.slice(0, last.start)}$: ${example.slice(last.start)}`;
}

// ---- the build ----------------------------------------------------------------------------------------------------

function exclusions() {
  const file = path.join(here, 'exclusions.txt');
  const out = new Map();
  if (!fs.existsSync(file)) return out;
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    const text = line.replace(/\s*$/, '');
    if (!text || text.startsWith('#')) continue;
    const m = /^(\S+)\s+#\s*(.+)$/.exec(text);
    if (!m) throw new Error(`exclusions.txt: "${line}" is not "<name>  # reason"`);
    out.set(m[1], m[2]);
  }
  return out;
}

/** Whether Strudel's own run of a function's first example plays anything (an example such as `setcpm(60)` does not). */
function playsOf() {
  const examples = JSON.parse(fs.readFileSync(path.join(root, 'engine/src/test/resources/parity/examples.json'), 'utf8'));
  const plays = new Map();
  for (const e of examples) if (e.index === 0) plays.set(e.name, e.haps === null || e.haps.length > 0);
  return plays;
}

function build() {
  const docs = JSON.parse(fs.readFileSync(path.join(checkout, 'doc.json'), 'utf8')).docs;
  const pageOf = pageOfEachFunction();
  const plays = playsOf();
  const excluded = exclusions();
  const rank = new Map(CATEGORIES.map(([key], i) => [key, i]));
  const headings = new Map(CATEGORIES);
  const names = new Set();
  const entries = [];
  for (const doc of docs) {
    // what the reference panel of Strudel itself lists, with an example
    if (!doc.name || doc.name.startsWith('_') || !doc.description || !doc.examples?.length) continue;
    if (names.has(doc.name)) throw new Error(`${doc.name} is documented twice`);
    names.add(doc.name);
    const example = doc.examples[0].trim();
    const call = firstCall(example, [doc.name, ...(doc.synonyms ?? [])]);
    const key = categoryOf(doc, pageOf);
    const entry = {
      name: doc.name,
      aliases: doc.synonyms ?? [],
      category: headings.get(key),
      form: call ? (call.method ? 'method' : 'function') : null,
      params: parametersOf(doc, example, call),
      doc: docOf(doc.description),
      example,
      snippet: snippetOf(example, plays.get(doc.name) ?? true),
    };
    if (excluded.has(doc.name)) entry.excluded = excluded.get(doc.name);
    entries.push({ key, entry });
  }
  for (const name of excluded.keys()) if (!names.has(name)) throw new Error(`exclusions.txt: ${name} is not a documented function`);
  entries.sort((a, b) => rank.get(a.key) - rank.get(b.key) || a.entry.name.localeCompare(b.entry.name, 'en', { sensitivity: 'base' }));
  return `${JSON.stringify(entries.map((e) => e.entry), null, 1)}\n`;
}

// ---- the cards ----------------------------------------------------------------------------------------------------
//
// docs.json is what the editor's autocomplete and documentation card show: the JSDoc of the functions Strudel's own editor knows
// (packages/codemirror/autocomplete.mjs and tooltip.mjs), in the order of doc.json, which decides which of two entries of one name wins.
// One entry:
//
//   name         the function, as Strudel documents it
//   hidden       (only when set) Strudel's editor shows the card of the function but does not offer its name (`@noAutocomplete`, `@superdirtOnly`)
//   synonyms     its other names (omitted when there are none)
//   description  the JSDoc description, as the HTML the JSDoc made of its markdown (omitted when there is none)
//   params       [{ name, types, description }]: the documented parameters (omitted when there are none)
//   examples     [{ code, snippet }]: every `@example`, and the same ready to insert into a program (see `snippet` above)
//
// An entry that no program of this app can call is left out: an internal (`_euclidRot`), a class (`MidiInput`), a private function, and what
// decision 4 of the parity plan rules out (CSound).

/** Names the app does not have, although Strudel documents them (Hydra and CSound are out of scope; their entry points are errors). */
const NOT_AVAILABLE = new Set(['csoundm']);

/** Whether a program of the app can call the function [doc] documents (`isValidDoc` of Strudel's autocomplete, less the classes and what is private). */
function isCard(doc) {
  const label = doc.name || doc.longname;
  if (!label || label.startsWith('_') || doc.kind === 'package' || doc.kind === 'class') return false;
  return doc.access !== 'private' && !NOT_AVAILABLE.has(label);
}

/** `hasExcludedTags` of Strudel's autocomplete: the function has a card, but its name is not offered. */
const isHidden = (doc) => ['superdirtOnly', 'noAutocomplete'].some((tag) => doc.tags?.find((t) => t.originalTitle === tag));

/** Whether Strudel's own run of each example plays anything, by function name and example index. */
function playsByExample() {
  const examples = JSON.parse(fs.readFileSync(path.join(root, 'engine/src/test/resources/parity/examples.json'), 'utf8'));
  const plays = new Map();
  for (const e of examples) plays.set(`${e.name}#${e.index}`, e.haps === null || e.haps.length > 0);
  return plays;
}

function buildCards() {
  const docs = JSON.parse(fs.readFileSync(path.join(checkout, 'doc.json'), 'utf8')).docs;
  const plays = playsByExample();
  const cards = [];
  for (const doc of docs) {
    if (!isCard(doc)) continue;
    const name = doc.name || doc.longname;
    const card = { name };
    if (isHidden(doc)) card.hidden = true;
    if (doc.synonyms?.length) card.synonyms = doc.synonyms;
    if (doc.description) card.description = doc.description;
    const params = (doc.params ?? []).filter((p) => p.name);
    if (params.length) card.params = params.map((p) => ({ name: p.name, types: p.type?.names ?? [], ...(p.description ? { description: p.description } : {}) }));
    if (doc.examples?.length) {
      card.examples = doc.examples.map((example, i) => {
        const code = example.trim();
        return { code, snippet: snippetOf(code, plays.get(`${name}#${i}`) ?? true) };
      });
    }
    cards.push(card);
  }
  return `[\n${cards.map((c) => JSON.stringify(c)).join(',\n')}\n]\n`;
}

const outputs = [
  { file: output, text: build(), summary: (text) => `${JSON.parse(text).length} functions` },
  { file: docsOutput, text: buildCards(), summary: (text) => `${JSON.parse(text).length} cards` },
];
if (process.argv.includes('--check')) {
  let stale = false;
  for (const { file, text } of outputs) {
    const current = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
    if (current !== text) {
      console.error(`${path.relative(root, file)} is out of date: run node tools/reference/build-reference.mjs`);
      stale = true;
    }
  }
  if (stale) process.exit(1);
} else {
  for (const { file, text, summary } of outputs) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, text);
    console.log(`${path.relative(root, file)}: ${summary(text)}`);
  }
}
