// Reference data for the JavaScript parser of the script engine (engine/src/test/kotlin/cosic/script/next/).
//
// It parses snippets with the acorn that Strudel's transpiler uses (packages/transpiler, acorn 8.14.0) and writes what
// acorn made of them, so the Kotlin parser can be compared with acorn node by node, offset by offset:
//
//   node tools/strudel-ref/parser-oracle.mjs
//
// Input   engine/src/test/resources/script/parser/cases.txt   hand-written snippets, one per `### <name>` header
//         engine/src/test/resources/parity/{examples,tunes}.json and parity/extra/*.json   the whole parity corpus
// Output  engine/src/test/resources/script/parser/oracle.json         one entry per snippet of cases.txt
//         engine/src/test/resources/script/parser/corpus-digest.json  one digest per corpus program
//
// acorn is run like the transpiler runs it (ecmaVersion 2022, allowAwaitOutsideFunction, locations), but in strict mode:
// Strudel evaluates the transpiled code as strict code, so the Kotlin parser treats every program as strict. Errors keep
// acorn's message without the " (line:col)" suffix, and its position (offset).
//
// The tree is printed as one line per node, children indented, in source order:
//   <Type> <start>-<end>[ attribute=value ...]
// tools/strudel-ref/../../engine/src/test/kotlin/cosic/script/next/AstDump.kt prints the Kotlin tree in the same format.
import crypto from 'node:crypto';
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
const acorn = createRequire(path.join(strudel, 'packages/transpiler/package.json'))('acorn');

class StrictParser extends acorn.Parser {
  constructor(options, input, startPos) {
    super(options, input, startPos);
    this.strict = true; // nothing has been tokenized yet
  }
}

const parseOptions = { ecmaVersion: 2022, allowAwaitOutsideFunction: true, locations: false };

export function parse(code) {
  try {
    return { ast: StrictParser.parse(code, parseOptions) };
  } catch (e) {
    if (!(e instanceof SyntaxError) || e.pos === undefined) throw e;
    return { error: { message: e.message.replace(/ \(\d+:\d+\)$/, ''), pos: e.pos } };
  }
}

// ---- the tree as text ---------------------------------------------------------------------------------------------

// Children per node type, in source order. A type that is not listed is not part of the subset the Kotlin parser reads.
const CHILDREN = {
  Program: ['body'],
  ExpressionStatement: ['expression'],
  BlockStatement: ['body'],
  EmptyStatement: [],
  VariableDeclaration: ['declarations'],
  VariableDeclarator: ['id', 'init'],
  FunctionDeclaration: ['id', 'params', 'body'],
  FunctionExpression: ['id', 'params', 'body'],
  ArrowFunctionExpression: ['params', 'body'],
  ReturnStatement: ['argument'],
  IfStatement: ['test', 'consequent', 'alternate'],
  ForStatement: ['init', 'test', 'update', 'body'],
  ForInStatement: ['left', 'right', 'body'],
  ForOfStatement: ['left', 'right', 'body'],
  WhileStatement: ['test', 'body'],
  DoWhileStatement: ['body', 'test'],
  BreakStatement: ['label'],
  ContinueStatement: ['label'],
  ThrowStatement: ['argument'],
  TryStatement: ['block', 'handler', 'finalizer'],
  CatchClause: ['param', 'body'],
  SwitchStatement: ['discriminant', 'cases'],
  SwitchCase: ['test', 'consequent'],
  LabeledStatement: ['label', 'body'],
  Identifier: [],
  Literal: [],
  TemplateLiteral: [], // quasis and expressions interleave, see below
  TemplateElement: [],
  TaggedTemplateExpression: ['tag', 'quasi'],
  ArrayExpression: ['elements'],
  ObjectExpression: ['properties'],
  Property: ['key', 'value'],
  UnaryExpression: ['argument'],
  UpdateExpression: ['argument'],
  BinaryExpression: ['left', 'right'],
  LogicalExpression: ['left', 'right'],
  AssignmentExpression: ['left', 'right'],
  ConditionalExpression: ['test', 'consequent', 'alternate'],
  CallExpression: ['callee', 'arguments'],
  NewExpression: ['callee', 'arguments'],
  MemberExpression: ['object', 'property'],
  ChainExpression: ['expression'],
  SequenceExpression: ['expressions'],
  SpreadElement: ['argument'],
  AwaitExpression: ['argument'],
  ThisExpression: [],
  ObjectPattern: ['properties'],
  ArrayPattern: ['elements'],
  AssignmentPattern: ['left', 'right'],
  RestElement: ['argument'],
};

const json = (value) => JSON.stringify(value);

function attributes(node) {
  switch (node.type) {
    case 'Identifier':
      return { name: node.name };
    case 'Literal': {
      if (node.regex || node.bigint !== undefined) return null;
      const value = typeof node.value === 'number' ? String(node.value) : json(node.value);
      return { raw: json(node.raw), value };
    }
    case 'TemplateElement':
      return { cooked: json(node.value.cooked), raw: json(node.value.raw), tail: node.tail };
    case 'VariableDeclaration':
      return { kind: node.kind };
    case 'FunctionDeclaration':
    case 'FunctionExpression':
      return node.generator ? null : { async: node.async };
    case 'ArrowFunctionExpression':
      return { async: node.async, expression: node.expression };
    case 'UnaryExpression':
      return { operator: json(node.operator) };
    case 'UpdateExpression':
      return { operator: json(node.operator), prefix: node.prefix };
    case 'BinaryExpression':
    case 'LogicalExpression':
    case 'AssignmentExpression':
      return { operator: json(node.operator) };
    case 'MemberExpression':
      return { computed: node.computed, optional: node.optional };
    case 'CallExpression':
      return { optional: node.optional };
    case 'Property':
      return { kind: node.kind, computed: node.computed, shorthand: node.shorthand, method: node.method };
    case 'ForOfStatement':
      return node.await ? null : {};
    default:
      return {};
  }
}

export function dump(ast) {
  const lines = [];
  const unsupported = new Set();
  const walk = (node, depth) => {
    if (node === null) {
      lines.push(`${'  '.repeat(depth)}<hole>`);
      return;
    }
    const keys = CHILDREN[node.type];
    const attrs = keys === undefined ? null : attributes(node);
    if (attrs === null) {
      unsupported.add(node.type);
      return;
    }
    const text = Object.entries(attrs).map(([k, v]) => ` ${k}=${v}`).join('');
    lines.push(`${'  '.repeat(depth)}${node.type} ${node.start}-${node.end}${text}`);
    if (node.type === 'TemplateLiteral') {
      node.quasis.forEach((quasi, i) => {
        walk(quasi, depth + 1);
        if (i < node.expressions.length) walk(node.expressions[i], depth + 1);
      });
      return;
    }
    for (const key of keys) {
      const child = node[key];
      if (Array.isArray(child)) child.forEach((c) => walk(c, depth + 1));
      else if (child !== null && child !== undefined) walk(child, depth + 1);
    }
  };
  walk(ast, 0);
  return { text: lines.join('\n'), unsupported: [...unsupported].sort() };
}

const digestOf = (text) => crypto.createHash('sha256').update(text, 'utf8').digest('hex').slice(0, 16);

// ---- cases.txt ----------------------------------------------------------------------------------------------------

// `### <name>` starts a case; its code is everything up to the next header, without the final line break. A header
// `### <name> !json` holds one JSON string literal instead, for code that needs escapes (\r, \u2028, a final line
// break, ...). Lines starting with `## ` are comments between cases.
function readCases(file) {
  const cases = [];
  let current = null;
  const finish = () => {
    if (current === null) return;
    const body = current.lines.join('\n');
    cases.push({ name: current.name, code: current.json ? JSON.parse(body) : body });
  };
  // The file's own final line break is not part of the last case.
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

function corpus() {
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
    const stem = file.slice(0, -'.json'.length);
    for (const e of JSON.parse(fs.readFileSync(path.join(extraDir, file), 'utf8'))) {
      if (e.mode === 'repl') e.codes.forEach((code, i) => entries.push({ id: `extra:${stem}/${e.name}/${i}`, code }));
      else entries.push({ id: `extra:${stem}/${e.name}`, code: e.code });
    }
  }
  return entries;
}

function main() {
  const dir = path.join(root, 'engine/src/test/resources/script/parser');
  const cases = readCases(path.join(dir, 'cases.txt'));
  const oracle = cases.map(({ name, code }) => {
    const result = parse(code);
    if (result.error) return { name, code, error: result.error };
    const { text, unsupported } = dump(result.ast);
    return unsupported.length > 0 ? { name, code, unsupported } : { name, code, ast: text };
  });
  fs.writeFileSync(path.join(dir, 'oracle.json'), `${JSON.stringify(oracle, null, 1)}\n`);

  const digests = {};
  let failures = 0;
  for (const { id, code } of corpus()) {
    const result = parse(code);
    if (result.error) {
      failures++;
      console.log(`corpus: acorn rejects ${id}: ${result.error.message} at ${result.error.pos}`);
      continue;
    }
    const { text, unsupported } = dump(result.ast);
    if (unsupported.length > 0) {
      failures++;
      console.log(`corpus: ${id} uses ${unsupported.join(', ')}`);
      continue;
    }
    digests[id] = digestOf(text);
  }
  fs.writeFileSync(path.join(dir, 'corpus-digest.json'), `${JSON.stringify(digests, null, 1)}\n`);
  const kinds = { ok: 0, error: 0, unsupported: 0 };
  for (const o of oracle) kinds[o.error ? 'error' : o.unsupported ? 'unsupported' : 'ok']++;
  console.log(`cases: ${oracle.length} (${kinds.ok} parse, ${kinds.error} fail in acorn, ${kinds.unsupported} outside the subset)`);
  console.log(`corpus: ${Object.keys(digests).length} digests, ${failures} not digested`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
