// Generates engine/src/test/resources/kabel/printer-vectors.json: the text escodegen 2.1.0 prints for each snippet with
// `{ format: { semicolons: false } }`, the way plugin-kabelsalat.mjs (genExprSource) calls it. KabelTest's printer test
// parses each `code` as a program of one expression statement with the Kotlin parser and checks that printJs gives the same `expected`.
//
// Run from the repository root after tools/strudel-ref/fetch.sh:   node tools/strudel-ref/kabel-printer-vectors.mjs
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const root = path.resolve(import.meta.dirname, '../..');
const require = createRequire(path.join(root, 'tools/strudel-ref/strudel/packages/transpiler/package.json'));
const acorn = require('acorn');
const escodegen = require('escodegen');

const snippets = [
  // numbers: the value is printed, not the source text
  '.13', '0.13', '1.50', '1e3', '1e21', '1e-7', '0x10', '1_000', '123456789012345680000', '5e-324', '0', '1.',
  '1..toFixed(2)', '(1).toFixed(2)', '1.5.toFixed(2)', '0x10.toString()', '(0).x', '10 .x',
  // strings and escapes
  "'a'", '"a"', "'it\\'s'", '"a\\"b"', "'a\\nb'", "'tab\\t'", "'\\u2028'", "'\\0'", "'\\x01'", "'\\x7f'", "'\\x1b[0m'", "'é'", "'日本'",
  "'\\u{1F600}'", "'back\\\\slash'", "'\\b\\f\\v'", "'\\u00e9'", '"\'"', '`plain`',
  // identifiers, literals
  'x', 'true', 'false', 'null', 'this', 'sFreq',
  // binary operators and precedence
  'a + b * c', '(a + b) * c', 'a - (b - c)', 'a - b - c', 'a / (b / c)', 'a ** b ** c', '(a ** b) ** c', '(-a) ** 2', 'a % b * c', 'a % (b * c)',
  'a + b + c', 'a + (b + c)', 'a << b + c', '(a << b) + c', 'a < b == c > d', 'a & b | c ^ d', 'a | b & c', 'a in b', 'a instanceof B', '!(a in b)',
  'a+ +b', 'a - -b', 'a + -b', 'a- --b', 'a+ ++b', 'x / y / z', 'sFreq / 2', '1 / 3',
  // logical and nullish
  'a && b || c', 'a && (b || c)', 'a || b && c', '(a ?? b) || c', 'a ?? (b || c)', 'a ?? b ?? c', '(a && b) ?? c', 'a || (b ?? c)',
  // unary, update
  '-x', '- -x', '-(-x)', '+(+x)', '!x', '!!x', '~x', 'typeof x', 'typeof (x + 1)', 'void 0', '-(a + b)', '-a.b', '(-a).b', 'x++', '--x', '-x++', '- --x', '+ +x', '(a, b)++ ',
  // conditional, assignment, sequence
  'a ? b : c', 'a ? b : c ? d : e', '(a ? b : c) ? d : e', 'a ? (b, c) : d', 'a = b', 'a = b = c', 'a += 1', 'a ??= b', 'a ||= b', 'a.b = c', '(a = b) + c', 'a ? b = 1 : c = 2', '(a, b)', 'f((a, b))', '(a, b) + c',
  // calls, members, new, chains
  'f()', 'f(a)', 'f(a, b, c)', 'f(a)(b)', 'a.b.c', 'a[0]', 'a[b + 1]', 'a["b"]', 'a[0][1]', 'f(a).b(c).d', 'new F', 'new F()', 'new F(a, b)', 'new (f())()', 'new (a.b())', 'new a.b()', '(new F).x', 'new F().x',
  'a?.b', 'a?.[0]', 'a?.()', 'a?.b.c(d)', '(a?.b).c', 'a?.b?.()', 'f(...a)', 'f(a, ...b)', '[...a, b]', '(function () {})()', '(() => 1)()', 'f(function () { return 1 })',
  // sound graphs, as the documentation writes them
  'saw(saw(sFreq / pat[0]).mul(8).add(sFreq).lag(pat[1])).mul(0.3).mul(sGate.adsr(0, 0.15, 0.5, pat[2])).lpf(sGate.adsr(0, 0.2, 0.3, 0.2).mul(1).add(0)).add(x => x.delay(pat[3]).mul(0.7)).add(x => x.delay(pat[4]).mul(0.77)).mul(0.7).add(x => x.delay(.13).mul(0.7)).out()',
  'sine(440).out()', 'audioin().mul(sGate.adsr(0.001, 0.3, 0, 0.2))', 'sine(f).mul(pat[2]).add(pat[3])',
  // arrows and functions
  'x => x', 'x => x + 1', '(x) => x', '(x, y) => x + y', '() => 1', '() => ({})', '() => ({ a: 1 })', 'x => ({ ...x })', 'x => y => x + y', '(x = 1) => x', '(...xs) => xs', '({ a, b }) => a + b', '([a, b]) => a + b',
  'async x => x', 'async (x, y) => x', 'x => { return x }', '() => {}', '() => { }', '() => { return }', '() => { return 1 + 2 }', 'x => (x, 1)', 'x => a ? b : c', '(x => x) || y', 'f(x => x, y => y)',
  'function () {}', 'function f(a, b) { return a }', 'async function () {}', '(function f() {})', 'a = function () {}', 'async () => await x', 'x => await_(x)',
  '() => { const a = 1; let b = 2; var c = 3; return a + b + c }', '() => { const a = pat[0]; const b = pat[1]; let f = n(sFreq); saw(f.mul(a)).out() }',
  '() => { const a = 1, b = 2; return a }', '() => { let a; return a }', '() => { let [a, b] = c; return a }', '() => { const { a, b } = c; return a }', '() => { const { a: b } = c; return b }', '() => { const { a = 1 } = c; return a }', '() => { const { ...r } = c; return r }', '() => { const [a, , b] = c; return a }',
  '() => { const [a, ...r] = c; return r }', '() => { let x = (1, 2); return x }', '() => { let x = a in b; return x }',
  '() => { if (a) b(); }', '() => { if (a) { b() } }', '() => { if (a) b(); else c(); }', '() => { if (a) { b() } else { c() } }', '() => { if (a) { b() } else if (c) { d() } else { e() } }', '() => { if (a) return 1; else return 2 }', '() => { if (a) { if (b) c(); } else d() }',
  '() => { for (let i = 0; i < 3; i++) { f(i) } }', '() => { for (;;) { break } }', '() => { for (let i = 0, j = 1; i < j; i++) f(i) }', '() => { for (i = 0; i < 3; i++) f(i) }', '() => { for (const x of xs) f(x) }', '() => { for (const x of xs) { f(x) } }', '() => { for (const k in o) f(k) }', '() => { for (x of xs) f(x) }', '() => { for (const [a, b] of xs) f(a) }',
  '() => { for (var i = (a in b); i; ) f() }', '() => { while (a) { f() } }', '() => { while (a) f() }', '() => { do { f() } while (a) }', '() => { do f(); while (a) }', '() => { while (a) { if (b) continue; break } }', '() => { ; }', '() => { throw new Error("x") }', '() => { throw x }',
  '() => { function g(a) { return a } return g(1) }', '() => { const f = () => { const g = () => { return 1 }; return g() }; return f() }', '() => { f(() => { return 1 }) }', '() => { return () => { return 1 } }', '() => { ({}).x; }', '() => { (function () {})(); }', '() => { (async function () {})(); }', '() => { ({ a: 1 }).a; }', '() => { x = { a: 1 }; }',
  '() => { a; b; c }', '() => { a; b; c; }', '() => { lbl: for (;;) { break lbl } }',
  // arrays and objects
  '[]', '[1]', '[1, 2]', '[1, 2, 3]', '[[1, 2], [3]]', '[, 1]', '[1, , 2]', '[1, ,]', '[a => a, b => b]', '[[]]', '[{}]', '{}', '({})', '({ a: 1 })', '({ a: 1, b: 2 })', '({ a })', '({ a, b })', '({ "a b": 1 })', '({ 1: 2 })', '({ [a]: 1 })', '({ a() {} })', '({ get a() { return 1 } })', '({ set a(v) {} })', '({ async a() {} })',
  '({ ...a })', '({ a: 1, ...b })', '({ a: { b: 1 } })', '({ a: { b: 1, c: 2 } })', '({ a: [1, 2] })', '({ a: function () { return 1 } })', '({ a: () => 1 })', '({ a: x => { return x } })', 'f({ a: 1 })', 'f([1, 2])', 'f({ a: 1 }, [2, 3])', 'x = [1, 2]', '[1, 2].map(x => x * 2)', '[1, 2][0]',
  // template literals
  '`a`', '`a${b}c`', '`${a}`', 'tag`a`', 'tag`a${b}`', 'a.tag`x`', 'f(a)`x`',
  // awaiting, spreads in new
  '() => { await x }', 'async () => { await f(x); return await g(y) }', 'new F(...a)', '(async () => 1)()',
  // member on literals and parenthesised heads
  '"a".length', "'a'.length", '[1, 2].length', '({}).a', '(a || b).c', '(a, b).c', '(a + b).c', '(-a).c', '(a ? b : c).d', '(x => x).name', '(function () {}).name', '(a = b).c', '(await_).c', '(new F).c', '(a.b)()', '(a || b)()', '(a ? b : c)()', '(() => 1)().x', '(a, b)()',
];

const out = [];
const failures = [];
for (const code of snippets) {
  let expected;
  try {
    const program = acorn.parse(code, { ecmaVersion: 2022, allowAwaitOutsideFunction: true });
    if (program.body.length !== 1 || program.body[0].type !== 'ExpressionStatement') throw new Error('not one expression statement');
    const node = program.body[0].expression;
    expected = escodegen.generate(node, { format: { semicolons: false } });
  } catch (e) {
    failures.push(`${JSON.stringify(code)}: ${e.message}`);
    continue;
  }
  out.push({ code, expected });
}
const dest = path.join(root, 'engine/src/test/resources/kabel/printer-vectors.json');
fs.mkdirSync(path.dirname(dest), { recursive: true });
fs.writeFileSync(dest, JSON.stringify(out, null, 1) + '\n');
console.log(`${out.length} vectors written to ${path.relative(root, dest)}`);
if (failures.length) console.log(`skipped ${failures.length} snippets acorn rejects:\n  ` + failures.join('\n  '));
