// Reference data for the controls table of the engine (engine/src/main/kotlin/cosic/pattern/Controls.kt).
//
// It loads packages/core/controls.mjs of the Strudel checkout with a recording wrapper around registerControl and
// registerMultiControl, replays what the file did to `Pattern.prototype` and to its exports, and writes the result:
//
//   node tools/strudel-ref/controls-registry.mjs
//
// Output  engine/src/test/resources/parity/controls-registry.json
//   registrations  every registerControl call in file order (the calls registerMultiControl makes included):
//                  { index, line, names (the first argument as given), multi (array form), keys, aliases (the rest
//                  arguments as given), viaMulti (the line of the registerMultiControl call, or null), exports }
//   methods        { name: { owner: <registration index>, keys, multi } }  what `Pattern.prototype[name]` does afterwards
//   globals        { name: { owner, keys, multi } }                        the exported control functions
// and, with the checks that make the replay trustworthy, a failure for any name where the replay and Node disagree.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.COSIC_ROOT ?? path.resolve(here, '../..');
const coreDir = path.join(here, 'strudel/packages/core');
const output = path.join(root, 'engine/src/test/resources/parity/controls-registry.json');

const source = fs.readFileSync(path.join(coreDir, 'controls.mjs'), 'utf8');

// Same-line edits only, so that line numbers stay those of the original file.
const instrumented = source
  .replace(
    'export function registerControl(names, ...aliases) {',
    'export function registerControl(names, ...aliases) { globalThis.__cosicCalls.push({ kind: "control", names: structuredClone(names), aliases: [...aliases], frames: new Error().stack.split("\\n").slice(2, 4) });',
  )
  .replace(
    '  bag[name] = createParam(names);',
    '  bag[name] = createParam(names); globalThis.__cosicCalls[globalThis.__cosicCalls.length - 1].func = bag[name];',
  )
  .replace(
    'export function registerMultiControl(names, maxControls, ...aliases) {',
    'export function registerMultiControl(names, maxControls, ...aliases) { globalThis.__cosicMulti.push({ line: Number(/:(\\d+):\\d+\\)?$/.exec(new Error().stack.split("\\n")[2])[1]), at: globalThis.__cosicCalls.length });',
  );
for (const marker of ['__cosicCalls.push', '__cosicCalls[globalThis', '__cosicMulti.push']) {
  if (!instrumented.includes(marker)) throw new Error(`controls.mjs no longer has the shape this script patches (${marker})`);
}

const copy = path.join(coreDir, 'controls.cosic-registry.mjs');
fs.writeFileSync(copy, instrumented);
globalThis.__cosicCalls = [];
globalThis.__cosicMulti = [];

let ns;
let Pattern;
try {
  ns = await import(pathToFileURL(copy).href);
  ({ Pattern } = await import(pathToFileURL(path.join(coreDir, 'pattern.mjs')).href));
} finally {
  fs.rmSync(copy, { force: true });
}

const lineOf = (frame) => Number(/:(\d+):\d+\)?$/.exec(frame)[1]);
const sourceLines = source.split('\n');
// V8 reports a call at the line of its callee, which for `export const {...} = registerMultiControl(...)` is the last
// line of the statement: report the first one instead.
const statementStart = (line) => {
  let l = line;
  while (l > 1 && !/^(export |for |registerControl|registerMultiControl|const |let )/.test(sourceLines[l - 1])) l--;
  return l;
};
const innerCallLine = sourceLines.findIndex((l) => l.includes('registerControl(theseNames, ...theseAliases)')) + 1;
const multiLines = globalThis.__cosicMulti; // { line, at: number of registerControl calls before it }

const registrations = globalThis.__cosicCalls.map((call, index) => {
  const viaMulti = multiLines.filter((m) => m.at <= index).at(-1);
  // A call from inside registerMultiControl is reported at the line of that function's own call to registerControl.
  const inside = lineOf(call.frames[0]) === innerCallLine;
  const line = statementStart(inside ? lineOf(call.frames[1]) : lineOf(call.frames[0]));
  const multi = Array.isArray(call.names);
  const keys = multi ? [...call.names] : [call.names];
  return { index, line, names: call.names, multi, keys, aliases: call.aliases, viaMulti: inside ? statementStart(viaMulti.line) : null, func: call.func };
});

// ---- exports: the call whose function a name exports ---------------------------------------------------------------

const ownerOfFunc = new Map(registrations.map((r) => [r.func, r.index]));
const globals = {};
for (const [name, value] of Object.entries(ns)) {
  if (!ns.isControlName(name) || typeof value !== 'function') continue;
  const owner = ownerOfFunc.get(value);
  if (owner === undefined) continue; // a function of the file that is not made by registerControl (ds, adsr, as, ...)
  globals[name] = { owner, keys: registrations[owner].keys, multi: registrations[owner].multi };
  (registrations[owner].exports ??= []).push(name);
}

// ---- methods: replay the assignments registerControl makes to Pattern.prototype ------------------------------------

const methods = {};
for (const r of registrations) {
  const name = r.keys[0];
  methods[name] = r.index;
  for (const alias of r.aliases) methods[alias] = methods[name];
}
// Functions of the file made with `register` replace the method of the same name when they come later (`ds` is also an
// alias of delaysync): the method is then not a control.
const registered = new Set([...source.matchAll(/^export const (\w+) = register\(\s*'(\w+)'/gm)].map((m) => m[2]));
const methodTable = {};
for (const [name, owner] of Object.entries(methods)) {
  methodTable[name] = registered.has(name)
    ? { overriddenBy: 'register' }
    : { owner, keys: registrations[owner].keys, multi: registrations[owner].multi };
}

// Replay against Node: Pattern.prototype[name] with a list shows which keys the method sets.
const probe = Pattern.prototype.fmap;
const problems = [];
const { pure } = await import(pathToFileURL(path.join(coreDir, 'pattern.mjs')).href);
const seen = Array.from({ length: 12 }, (_, i) => i + 1);
const behaviour = (fn) => {
  const result = fn.call(pure({})).queryArc(0, 1)[0].value;
  return Object.keys(result);
};
for (const [name, entry] of Object.entries(methodTable)) {
  if (entry.overriddenBy) continue;
  const real = Pattern.prototype[name];
  if (typeof real !== 'function') {
    problems.push(`${name}: not a method in Node`);
    continue;
  }
  const wrapped = function () {
    return real.call(this, seen);
  };
  const got = behaviour(wrapped);
  // multi: the first keys.length entries are set; otherwise the single key holds the whole list
  const expected = entry.multi ? entry.keys.slice(0, seen.length) : [entry.keys[0]];
  if (JSON.stringify(got) !== JSON.stringify(expected)) problems.push(`${name}: replay says ${expected}, Node says ${got}`);
}
for (const [name, entry] of Object.entries(globals)) {
  const got = Object.keys(ns[name](seen).queryArc(0, 1)[0].value);
  const expected = entry.multi ? entry.keys.slice(0, seen.length) : [entry.keys[0]];
  if (JSON.stringify(got) !== JSON.stringify(expected)) problems.push(`global ${name}: replay says ${expected}, Node says ${got}`);
}
if (problems.length > 0) {
  console.error(problems.join('\n'));
  process.exit(1);
}
void probe;

const sorted = (object) => Object.fromEntries(Object.entries(object).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)));
const registrationsOut = registrations.map(({ func, ...rest }) => ({ ...rest, exports: rest.exports ?? [] }));
// One entry per line: the file is read by people and by ControlsTest, and it stays small.
const lines = (entries, format) => entries.map((entry) => `  ${format(entry)}`).join(',\n');
const table = (object) => lines(Object.entries(object), ([name, value]) => `${JSON.stringify(name)}: ${JSON.stringify(value)}`);
fs.writeFileSync(
  output,
  `{\n "registrations": [\n${lines(registrationsOut, (r) => JSON.stringify(r))}\n ],\n "methods": {\n${table(sorted(methodTable))}\n },\n "globals": {\n${table(sorted(globals))}\n }\n}\n`,
);
console.log(
  `controls registry: ${registrations.length} registrations, ${Object.keys(methodTable).length} method names, ${Object.keys(globals).length} exported control functions`,
);
