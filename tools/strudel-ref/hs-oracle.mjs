// Reference data for the Haskell parser and evaluator of `tidal` (engine/src/main/kotlin/cosic/script/tidal/).
//
// Strudel parses the Haskell of `tidal` with tree-sitter-haskell 0.23.1 and runs the tree with hs2js (packages/hs2js).
// This script does both for a list of programs, with the very same web-tree-sitter 0.24.7, grammar and `run` function, and
// writes what they made of each, so that the Kotlin parser can be compared with them tree for tree and result for result:
//
//   node tools/strudel-ref/hs-oracle.mjs
//
// Input   engine/src/test/resources/tidal/cases.txt   hand-written programs, one per `### <name>` header
//         and GENERATED programs: random sequences of the things Tidal code is made of, with random spacing, a part of them
//         damaged by deleting, repeating or swapping a token. The generator is seeded, so the same corpus comes out every time.
// Output  engine/src/test/resources/tidal/oracle.json {scope, cases: [{name, code, tree, eval}]}
//
//   tree  the tree-sitter tree of the code after hs2js's preprocessing, as one line: `(type child ...)`, a named leaf as
//         `type:"text"`, an anonymous token as `"text"`. `operator` is always a leaf, and the parts of the tree the Kotlin
//         parser does not look into (local binds, alternatives, guards, the type of a signature, ...) print as `…`.
//         null when the tree has an ERROR or a missing node.
//   eval  what hs2js's `run` makes of the tree over a symbolic scope: `OK <value>` or `ERR <message>`. The scope has the
//         functions named in `scope` (curried, with their arity), the operators of tidal.mjs are symbolic too: every
//         operation prints as the text it stands for, so the result tells how the operators were grouped.
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
const hs2js = path.join(strudel, 'packages/hs2js');
const require = createRequire(path.join(hs2js, 'package.json'));
const Parser = require('web-tree-sitter');
const modules = path.join(hs2js, 'node_modules');
await Parser.init({ locateFile: (name) => path.join(modules, 'web-tree-sitter', name) });
const parser = new Parser();
parser.setLanguage(await Parser.Language.load(path.join(modules, 'tree-sitter-haskell/tree-sitter-haskell.wasm')));

// hs2js's `run`, from the very source file; its parser import is the only thing replaced (the wasm loading of parser.mjs
// is made for a browser).
const runSource = fs
  .readFileSync(path.join(hs2js, 'src/hs2js.mjs'), 'utf8')
  .replace("import { parse } from './parser.mjs';", 'const parse = () => { throw new Error("not used here"); };');
if (runSource.includes('parser.mjs')) throw new Error('hs2js.mjs changed: the import of the parser was not replaced');
const { run } = await import(`data:text/javascript;base64,${Buffer.from(runSource).toString('base64')}`);

// parser.mjs:parse: "for some reason, the parser doesn't like new lines.."
const preprocess = (code) => code.replaceAll('\n\n', '~~~~').replaceAll('\n', ' ').replaceAll('~~~~', ' \n');

// ---- the tree as text ------------------------------------------------------------------------------------------------

// Nodes whose children are not compared: everything after the first `keep` children prints as `…`.
const COLLAPSED = new Map([
  ['local_binds', 0],
  ['alternatives', 0],
  ['guards', 0],
  ['qualifiers', 0],
  ['statements', 0],
  ['module', 0],
  ['do', 1],
  ['signature', 2],
  ['data_type', 1],
  ['newtype', 1],
  ['type_synomym', 1],
  ['class', 1],
  ['instance', 1],
  ['fixity', 1],
  ['default_types', 1],
  ['deriving_instance', 1],
  ['type_application', 1],
  ['th_quoted_name', 1],
  ['import', 1],
  ['header', 1],
  ['foreign_import', 1],
  ['foreign_export', 1],
  ['list_comprehension', 2],
  ['lambda_case', 2],
  ['qualified', 0],
]);

function sexp(node) {
  const keep = COLLAPSED.get(node.type);
  if (keep === 0) return `(${node.type} …)`;
  if (node.type === 'operator' || node.type === 'constructor_operator') return `${node.type}:${JSON.stringify(node.text)}`;
  if (node.childCount === 0) return node.isNamed ? `${node.type}:${JSON.stringify(node.text)}` : JSON.stringify(node.text);
  const kids = node.children.map((child, i) => (keep !== undefined && i >= keep ? null : sexp(child)));
  const shown = keep === undefined ? kids : [...kids.slice(0, keep), '…'];
  return `(${node.type} ${shown.join(' ')})`;
}

const hasProblem = (node) => node.hasError || node.isMissing || node.type === 'ERROR';

// ---- the symbolic world ----------------------------------------------------------------------------------------------

// name -> arity (0: a constant)
export const SCOPE = {
  d1: 1, d2: 1, s: 1, n: 1, note: 1, sound: 1, speed: 1, up: 1, shape: 1, id: 1, rev: 1, stack: 1, cat: 1, run: 1, irand: 1,
  fast: 2, slow: 2, jux: 2, chop: 2, overlay: 2, rot: 2, every: 3, sometimesBy: 3, hush: 0,
};

const sym = (s) => ({ __sym: s });
const show = (v) => {
  if (v === undefined) return 'undefined';
  if (typeof v === 'function') return '<fn>';
  if (v && v.__sym !== undefined) return v.__sym;
  if (Array.isArray(v)) return '[' + v.map(show).join(',') + ']';
  if (v && typeof v === 'object' && 'startIndex' in v) return `<node ${v.type}>`;
  if (typeof v === 'string') return JSON.stringify(v);
  return String(v);
};
const curried = (name, arity, have = []) => (arg) => {
  const all = [...have, arg];
  return all.length >= arity ? sym(`${name}(${all.map(show).join(', ')})`) : curried(name, arity, all);
};
const baseScope = () => {
  const scope = {};
  for (const [name, arity] of Object.entries(SCOPE)) scope[name] = arity === 0 ? sym(name) : curried(name, arity);
  return scope;
};
const ops = {};
for (const symbol of ['>', '#', '+', '-', '*', '/']) {
  ops[symbol] = (l, r) => sym(`(${show(l)} ${symbol} ${show(r)})`);
  ops[`|${symbol}`] = (l, r) => sym(`(${show(l)} |${symbol} ${show(r)})`);
  ops[`${symbol}|`] = (l, r) => sym(`(${show(l)} ${symbol}| ${show(r)})`);
  ops[`|${symbol}|`] = (l, r) => sym(`(${show(l)} |${symbol}| ${show(r)})`);
}
for (const symbol of ['~>', '<~', '<$>']) ops[symbol] = (l, r) => sym(`(${show(l)} ${symbol} ${show(r)})`);
const symbolicOps = { ...ops, string: (node) => sym('m' + node.text) };

function evaluate(tree) {
  const log = console.log;
  console.log = () => {}; // hs2js logs the right side of `&`
  try {
    return `OK ${show(run(tree.rootNode, baseScope(), symbolicOps))}`;
  } catch (e) {
    return `ERR ${e.message}`;
  } finally {
    console.log = log;
  }
}

function analyse(name, code) {
  const tree = parser.parse(preprocess(code));
  return { name, code, tree: hasProblem(tree.rootNode) ? null : sexp(tree.rootNode), eval: evaluate(tree) };
}

// ---- cases.txt ---------------------------------------------------------------------------------------------------------

// `### <name>` starts a case; its code is everything up to the next header, without the final line break. A header
// `### <name> !json` holds one JSON string literal instead. Lines starting with `## ` are comments between cases.
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
      // a comment
    } else if (current !== null) {
      current.lines.push(line);
    }
  }
  finish();
  return cases;
}

// ---- the generated programs --------------------------------------------------------------------------------------------

// mulberry32
function seeded(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function generator(seed) {
  const random = seeded(seed);
  const int = (n) => Math.floor(random() * n);
  const pick = (list) => list[int(list.length)];
  const chance = (p) => random() < p;

  const names = ['d1', 'd2', 's', 'n', 'note', 'sound', 'speed', 'up', 'shape', 'id', 'rev', 'stack', 'cat', 'run', 'irand',
    'fast', 'slow', 'jux', 'chop', 'overlay', 'rot', 'every', 'sometimesBy', 'hush', 'x', 'y', 'foo', 'bar', 'f'];
  const numbers = ['1', '2', '3', '4', '8', '0.25', '0.5', '1.5', '1e3', '2.5e-1', '0x10', '1_000', '0b11', '0o7', '16'];
  const strings = ['"bd"', '"bd sn"', '"bd*2"', '"<0 1>"', '"a b c"', '"[bd sn]*2"', '"~ hh"'];
  const operators = ['$', '$', '$', '.', '.', '#', '#', '#', '+', '-', '*', '/', '|+|', '|-|', '|*|', '|/|', '|>|', '|+', '+|', '|#',
    '#|', '<~', '~>', '<$>', '<>', '&', '|<|', '>|', '|<', '%', '++', '>>=', '==', '&&', ':', '<|>'];
  const lambdaParams = ['x', 'y', 'p', 'a', 'b'];

  // the whitespace around an operator: mostly spaced, sometimes tight
  const around = (op) => {
    const r = random();
    if (r < 0.76) return ` ${op} `;
    if (r < 0.86) return op;
    if (r < 0.92) return ` ${op}`;
    if (r < 0.96) return `${op} `;
    return `\n  ${op} `;
  };
  const gap = () => (chance(0.97) ? ' ' : pick(['', '  ', '\t', ' \n ']));

  function atom(depth) {
    const r = random();
    if (depth <= 0 || r < 0.42) return pick(names);
    if (r < 0.6) return pick(numbers);
    if (r < 0.72) return pick(strings);
    if (r < 0.8) return `(${chain(depth - 1)})`;
    if (r < 0.85) return `(${pick(operators)}${chance(0.8) ? ' ' : ''}${application(depth - 1)})`; // right section
    if (r < 0.9) return `(${application(depth - 1)}${chance(0.8) ? ' ' : ''}${pick(operators)})`; // left section
    if (r < 0.93) return `[${Array.from({ length: int(4) }, () => chain(depth - 1)).join(chance(0.85) ? ', ' : ',')}]`;
    if (r < 0.945) return `(${chain(depth - 1)}, ${chain(depth - 1)})`;
    if (r < 0.955) return pick(['()', '(-1)', '(- 1)', '(-x)', '(-1.5)']);
    if (r < 0.975) return `(\\${pick(lambdaParams)} -> ${chain(depth - 1)})`;
    if (r < 0.985) return pick(['(+)', '(#)', '($)', '(.)', '(<~)']);
    return pick(['Foo', 'Just', "x'", 'x#', '_x', '[]', "'a'", 'a.b', '#label', '?imp']);
  }

  function application(depth) {
    let out = atom(depth);
    const count = chance(0.45) ? 0 : 1 + int(3);
    for (let i = 0; i < count; i++) out += gap() + atom(depth - 1);
    return out;
  }

  function operand(depth) {
    const r = random();
    if (r < 0.04) return `- ${application(depth)}`;
    if (r < 0.06) return `-${application(depth)}`;
    if (r < 0.1) return `\\${pick(lambdaParams)} -> ${chain(depth - 1)}`;
    if (r < 0.11) return `if ${atom(depth - 1)} then ${atom(depth - 1)} else ${atom(depth - 1)}`;
    return application(depth);
  }

  function chain(depth) {
    let out = operand(depth);
    const count = chance(0.3) ? 0 : 1 + (chance(0.5) ? int(3) : 0);
    for (let i = 0; i < count; i++) out += around(pick(operators)) + operand(depth - 1);
    return out;
  }

  function statement() {
    const r = random();
    if (r < 0.56) return `d1${around('$')}${chain(3)}`;
    if (r < 0.72) return chain(3);
    if (r < 0.82) return `${pick(['x', 'y', 'foo'])} = ${chain(2)}`;
    if (r < 0.9) return `${pick(['f', 'g'])} ${pick(lambdaParams)}${chance(0.4) ? ' ' + pick(lambdaParams) : ''} = ${chain(2)}`;
    if (r < 0.93) return `d2 $ ${chain(2)} -- ${pick(['kick', 'hats', 'a comment'])}`;
    if (r < 0.95) return `${pick(['x', 'f p'])} = ${chain(1)} where y = 1`;
    if (r < 0.97) return pick(['hush', 'let x = 1 in x', 'x :: Int', 'f x | x > 1 = 1 | otherwise = 2', 'import Foo', 'data X = A']);
    return `{- note -} ${chain(2)}`;
  }

  // statements are separated by blank lines (a line break alone only joins them into one declaration); an indented line
  // after a blank line continues the declaration
  function separator() {
    const r = random();
    if (r < 0.74) return '\n\n';
    if (r < 0.88) return '\n\n\n';
    if (r < 0.95) return '\n\n  ';
    return '\n\n\n\n';
  }

  function damage(code) {
    const tokens = code.match(/\s+|[A-Za-z_][A-Za-z0-9_'#]*|[0-9][0-9a-fA-FxXoObB_.eE]*|"[^"]*"|[^\sA-Za-z0-9_"]/g) ?? [];
    const solid = tokens.map((t, i) => (/^\s+$/.test(t) ? -1 : i)).filter((i) => i >= 0);
    if (solid.length === 0) return code;
    const at = solid[int(solid.length)];
    const next = solid[Math.min(solid.indexOf(at) + 1, solid.length - 1)];
    const r = random();
    if (r < 0.35) tokens.splice(at, 1);
    else if (r < 0.6) tokens.splice(at, 0, tokens[at], ' ');
    else if (r < 0.8 && next !== at) [tokens[at], tokens[next]] = [tokens[next], tokens[at]];
    else tokens.splice(at, 0, pick(['(', ')', '[', ']', ',', '$', '#', '\\', '->', '=', '"', '-', '.', ' ']));
    return tokens.join('');
  }

  return function program() {
    const count = chance(0.5) ? 1 : 2 + int(3);
    let code = chance(0.07) ? pick(['\n', ' ', '  ']) : '';
    for (let i = 0; i < count; i++) code += (i > 0 ? separator() : '') + statement();
    if (chance(0.05)) code += pick(['\n', '\n\n']);
    return chance(0.22) ? damage(code) : code;
  };
}

// ---- the file ----------------------------------------------------------------------------------------------------------

const GENERATED = Number(process.env.HS_ORACLE_GENERATED ?? 2600);

function main() {
  const dir = path.join(root, 'engine/src/test/resources/tidal');
  fs.mkdirSync(dir, { recursive: true });
  const handwritten = readCases(path.join(dir, 'cases.txt'));
  const cases = handwritten.map(({ name, code }) => analyse(name, code));
  const program = generator(20251120);
  const seen = new Set(handwritten.map(({ code }) => code));
  for (let i = 0; i < GENERATED; i++) {
    const code = program();
    if (seen.has(code)) continue;
    seen.add(code);
    cases.push(analyse(`generated.${i}`, code));
  }
  fs.writeFileSync(path.join(dir, 'oracle.json'), `${JSON.stringify({ scope: SCOPE, cases }, null, 0).replaceAll('},{', '},\n{')}\n`);
  const trees = cases.filter((c) => c.tree !== null).length;
  console.log(`${cases.length} cases (${handwritten.length} written by hand), ${trees} with a tree without errors`);
}

main();
