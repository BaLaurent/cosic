// Reference data for the transpiler of the script engine (engine/src/test/kotlin/cosic/script/next/TranspileOracleTest.kt).
//
// It runs programs through Strudel's own transpiler (packages/transpiler at the pinned commit: transpiler.mjs with the
// mini-notation, bare-samples and widget plugins, the ones the website REPL loads; not the kabelsalat plugin, whose
// `K(...)` rewrite is Cosic's own task) and stores what came out, so the Kotlin transpiler can be compared with it:
//
//   node tools/strudel-ref/transpile-oracle.mjs [--show <case or program id>]
//
// Input   engine/src/test/resources/script/transpile/cases.txt   hand-written snippets, one per `### <name>` header
//         engine/src/test/resources/parity/{examples,tunes}.json and parity/extra/*.json   the whole parity corpus
// Output  engine/src/test/resources/script/transpile/oracle.json         every snippet, run with four sets of options
//         engine/src/test/resources/script/transpile/corpus-oracle.json  every corpus program, run with the defaults
//
// Strudel prints the rewritten tree as text (escodegen) and evaluates that. The text is read back with acorn, in strict
// mode like the parser of the script engine, and printed in the tree format of parser-oracle.mjs without positions and
// without the `raw` of literals (the printer chooses the quotes), so what is compared is the tree: node types, names,
// values and operators. A program whose output does not even parse as a function body is stored with the message of
// the failure, which is the error the user gets.
//
// The mini-notation package (getLeafLocations) is what finds the atoms of a mini-notation string. The Kotlin
// transpiler is given a stand-in for it that answers from `literals`: for every `m('<text>', <offset>)` call in
// the output, the atoms of that string. The two embedded languages registered below (`mondo`, `mondolang`) answer
// `[offset, offset + length]` for their whole code, in Kotlin as well.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { dump } from './parser-oracle.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.COSIC_ROOT ?? path.resolve(here, '../..');
const strudel = path.join(here, 'strudel');
if (!fs.existsSync(path.join(strudel, '.fetched'))) {
  throw new Error('tools/strudel-ref/strudel is missing: run `bash tools/strudel-ref/fetch.sh` first');
}
const acorn = createRequire(path.join(strudel, 'packages/transpiler/package.json'))('acorn');
const pkg = (p) => pathToFileURL(path.join(strudel, 'packages', p)).href;

// @strudel/core announces itself on the console while it loads.
const log = console.log;
console.log = () => {};
const T = await import(pkg('transpiler/transpiler.mjs'));
await import(pkg('transpiler/plugin-mini.mjs'));
await import(pkg('transpiler/plugin-sample.mjs'));
const W = await import(pkg('transpiler/plugin-widgets.mjs'));
const { getLeafLocations } = await import(pkg('mini/mini.mjs'));
console.log = log;

// the widgets the codemirror package registers (packages/codemirror/widget.mjs:133-170)
const WIDGET_TYPES = ['_pianoroll', '_punchcard', '_spiral', '_scope', '_pitchwheel', '_spectrum', '_print'];
for (const type of WIDGET_TYPES) W.registerWidgetType(type);
// the language of a template is found by its tag; the locations are those the tests of both sides agree on
const LANGUAGES = ['mondo', 'mondolang'];
const languageLocations = (code, offset) => [[offset, offset + code.length]];
for (const name of LANGUAGES) T.registerLanguage(name, { getLocations: languageLocations });

class StrictParser extends acorn.Parser {
  constructor(options, input, startPos) {
    super(options, input, startPos);
    this.strict = true;
  }
}
const outputOptions = { ecmaVersion: 2022, allowAwaitOutsideFunction: true, allowReturnOutsideFunction: true, locations: false };

// ---- the tree as text, without positions ----------------------------------------------------------------------------

export function normalize(text) {
  return text
    .split('\n')
    .map((line) => {
      const m = /^(\s*)(\w+) \d+-\d+([\s\S]*)$/.exec(line); // a value may hold U+2028, which `.` does not match
      if (m === null) return line; // a hole
      let attributes = m[3];
      if (m[2] === 'Literal') attributes = attributes.replace(/^ raw="(?:[^"\\]|\\.)*"/, '');
      return `${m[1]}${m[2]}${attributes}`;
    })
    .join('\n');
}

const digestOf = (text) => crypto.createHash('sha256').update(text, 'utf8').digest('hex').slice(0, 16);

// ---- one run -----------------------------------------------------------------------------------------------------------

// every `m('<text>', <offset>)` and `<language>('<code>', <offset>)` call of the output, in the order of the text
function embeddedCalls(ast) {
  const calls = [];
  const visit = (node) => {
    if (node === null || typeof node !== 'object') return;
    if (Array.isArray(node)) return node.forEach(visit);
    if (typeof node.type !== 'string') return;
    if (node.type === 'CallExpression' && node.callee.type === 'Identifier' && node.arguments.length === 2) {
      const [code, offset] = node.arguments;
      const name = node.callee.name;
      if (code.type === 'Literal' && typeof code.value === 'string' && offset.type === 'Literal' && typeof offset.value === 'number') {
        if (name === 'm') calls.push({ at: node.start, kind: 'mini', code: code.value, offset: offset.value });
        else if (LANGUAGES.includes(name)) calls.push({ at: node.start, kind: 'language', code: code.value, offset: offset.value });
      }
    }
    for (const key of Object.keys(node)) visit(node[key]);
  };
  visit(ast);
  return calls.sort((a, b) => a.at - b.at);
}

export function run(code, options, { withOutput }) {
  let r;
  try {
    r = T.transpiler(code, { ...options });
  } catch (e) {
    return { error: e.message.replace(/ \(\d+:\d+\)$/, '') };
  }
  // what the REPL does with the output (packages/core/evaluate.mjs:29-38): it must make a function
  try {
    new Function(`"use strict";return ((async ()=>{${r.output}})())`);
  } catch (e) {
    return { evalError: e.message, ...(withOutput ? { output: r.output } : {}) };
  }
  const ast = StrictParser.parse(r.output, outputOptions);
  const { text, unsupported } = dump(ast);
  if (unsupported.length > 0) return { unsupported };
  const tree = normalize(text);
  const out = { tree: withOutput ? tree : undefined, digest: digestOf(tree) };
  if (withOutput) out.output = r.output;
  // with emitMiniLocations off the transpiler returns the output alone; otherwise a list the plugins never touched is missing
  if (options.emitMiniLocations !== false) {
    const miniLocations = r.miniLocations ?? [];
    out.miniLocations = miniLocations;
    const calls = embeddedCalls(ast);
    out.literals = calls.filter((c) => c.kind === 'mini').map((c) => {
      const quoted = `"${c.code}"`;
      return [c.offset, quoted, getLeafLocations(quoted, c.offset)];
    });
    // the locations in the order of the walk must be those of the calls in the order of the text
    const inTextOrder = calls.flatMap((c) => (c.kind === 'mini' ? getLeafLocations(`"${c.code}"`, c.offset) : languageLocations(c.code, c.offset)));
    if (JSON.stringify(inTextOrder) !== JSON.stringify(miniLocations)) out.locationOrderDiffers = true;
    out.widgets = (r.widgets ?? []).filter((w) => w.type !== 'slider');
    out.sliders = r.sliders ?? [];
  }
  return out;
}

// ---- cases.txt ---------------------------------------------------------------------------------------------------------

// `### <name>` starts a case; its code is everything up to the next header, without the final line break. A header
// `### <name> !json` holds one JSON string literal instead, for code that needs escapes. Lines starting with `## ` are
// comments between cases.
function readCases(file) {
  const cases = [];
  let current = null;
  const finish = () => {
    if (current === null) return;
    const body = current.lines.join('\n');
    cases.push({ name: current.name, code: current.json ? JSON.parse(body) : body });
  };
  const text = fs.readFileSync(file, 'utf8').replace(/\n$/, '');
  for (const line of text.split('\n')) {
    const header = /^### (\S+)( !json)?$/.exec(line);
    if (header) {
      finish();
      current = { name: header[1], json: Boolean(header[2]), lines: [] };
    } else if (line.startsWith('## ')) {
      // comment
    } else if (current !== null) {
      current.lines.push(line);
    }
  }
  finish();
  const names = new Set();
  for (const c of cases) {
    if (names.has(c.name)) throw new Error(`duplicate case name ${c.name}`);
    names.add(c.name);
  }
  return cases;
}

// the programs of the parity corpus, with the ids parser-oracle.mjs gives them
export function corpus() {
  const dir = path.join(root, 'engine/src/test/resources/parity');
  const entries = [];
  for (const e of JSON.parse(fs.readFileSync(path.join(dir, 'examples.json'), 'utf8'))) {
    entries.push({ id: `examples:${e.name}#${e.index}`, code: e.code });
  }
  for (const e of JSON.parse(fs.readFileSync(path.join(dir, 'tunes.json'), 'utf8'))) {
    entries.push({ id: `tunes:${e.name}`, code: e.code });
  }
  const extraDir = path.join(dir, 'extra');
  for (const file of fs.readdirSync(extraDir).filter((f) => f.endsWith('.json')).sort()) {
    for (const e of JSON.parse(fs.readFileSync(path.join(extraDir, file), 'utf8'))) {
      if (e.mode === 'repl') e.codes.forEach((code, i) => entries.push({ id: `extra:${e.name}/${i}`, code }));
      else entries.push({ id: `extra:${e.name}`, code: e.code });
    }
  }
  return entries;
}

// The options the hand-written snippets are run with: the defaults, then each option changed.
const VARIANTS = [{}, { addReturn: false }, { id: 'ed', emitWidgets: false }, { emitMiniLocations: false }];

function main() {
  const dir = path.join(root, 'engine/src/test/resources/script/transpile');
  const cases = readCases(path.join(dir, 'cases.txt'));
  const show = process.argv.indexOf('--show') >= 0 ? process.argv[process.argv.indexOf('--show') + 1] : null;
  if (show !== null) {
    const found = cases.find((c) => c.name === show) ?? corpus().find((c) => c.id === show);
    if (!found) throw new Error(`no case or program ${show}`);
    const result = run(found.code, {}, { withOutput: true });
    log(found.code);
    log('---');
    log(result.output ?? JSON.stringify(result));
    log('---');
    log(result.tree ?? '');
    log('---');
    log(JSON.stringify({ miniLocations: result.miniLocations, widgets: result.widgets, sliders: result.sliders, literals: result.literals }));
    return;
  }
  const oracle = cases.map(({ name, code }) => ({
    name,
    code,
    variants: VARIANTS.map((options) => ({ options, ...run(code, options, { withOutput: true }) })),
  }));
  fs.writeFileSync(path.join(dir, 'oracle.json'), `${JSON.stringify(oracle, null, 1)}\n`);

  const programs = {};
  const kinds = { ok: 0, error: 0, evalError: 0 };
  for (const { id, code } of corpus()) {
    const result = run(code, {}, { withOutput: false });
    programs[id] = result;
    kinds[result.error ? 'error' : result.evalError ? 'evalError' : 'ok']++;
    if (result.locationOrderDiffers) log(`corpus: the order of the locations differs from the text order in ${id}`);
    if (result.error || result.evalError) log(`corpus: ${id}: ${result.error ?? result.evalError}`);
  }
  fs.writeFileSync(path.join(dir, 'corpus-oracle.json'), `${JSON.stringify(programs, null, 1)}\n`);

  let variants = 0;
  for (const o of oracle) {
    for (const v of o.variants) {
      variants++;
      if (v.locationOrderDiffers) log(`cases: the order of the locations differs from the text order in ${o.name} ${JSON.stringify(v.options)}`);
    }
  }
  log(`cases: ${oracle.length} snippets, ${variants} runs`);
  log(`corpus: ${Object.keys(programs).length} programs (${kinds.ok} ok, ${kinds.error} fail in the transpiler, ${kinds.evalError} give code that does not run)`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
