// Reference data for the mondo parser and runner of the script engine (engine/src/test/kotlin/cosic/script/mondo/).
//
// It runs the real packages/mondo/mondo.mjs of the pinned Strudel checkout over a corpus and writes what came out, so
// the Kotlin port can be compared with it token by token, node by node and value by value:
//
//   node tools/strudel-ref/mondo-oracle.mjs
//
// Output (engine/src/test/resources/mondo/)
//   parser-oracle.json   per program and offset: the tokens, the tree with its types and positions, the tree printed
//                        compact and pretty, the locations of the atoms, or the error. The corpus is the mondo documentation
//                        of Strudel, hand-written programs (each operator, each error) and generated ones (a seeded
//                        generator of balanced and unbalanced token soups).
//   runner-oracle.json   per program: the value the runner made of it with the arithmetic evaluator of mondo.test.mjs,
//                        or the error. The programs are the ones of mondo.test.mjs (read by running that file with a
//                        stand-in for vitest that records every `runner.run` and every expectation) and hand-written
//                        ones for the special forms and their mistakes. Programs that share a scope are marked with the
//                        scope's id, and run in the order they are listed.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import util from 'node:util';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.COSIC_ROOT ?? path.resolve(here, '../..');
const strudel = path.join(here, 'strudel');
if (!fs.existsSync(path.join(strudel, '.fetched'))) {
  throw new Error('tools/strudel-ref/strudel is missing: run `bash tools/strudel-ref/fetch.sh` first');
}
const outDir = path.join(root, 'engine/src/test/resources/mondo');
const mondoUrl = pathToFileURL(path.join(strudel, 'packages/mondo/mondo.mjs')).href;
const { MondoParser, MondoRunner, printAst } = await import(mondoUrl);

// ---- the parser -----------------------------------------------------------------------------------------------------

const position = (n) => (n.loc ? '@' + n.loc.join('-') : '');
// every node with its type and position: (list children...)@loc, type:"value"@loc
const typed = (n) =>
  n.type === 'list' ? `(${n.children.map(typed).join(' ')})${position(n)}` : `${n.type}:${JSON.stringify(n.value)}${position(n)}`;

const parseCase = (code, offset, full) => {
  const out = { code, offset };
  if (full) {
    try {
      out.tokens = new MondoParser().tokenize(code, offset).map((t) => `${t.type}:${JSON.stringify(t.value)}${position(t)}`);
    } catch (e) {
      out.tokensError = e.message;
    }
  }
  try {
    const ast = new MondoParser().parse(code, offset);
    out.ast = typed(ast);
    if (full) {
      out.compact = printAst(ast, true);
      out.pretty = printAst(ast, false);
    }
  } catch (e) {
    out.error = e.message;
  }
  if (full) {
    try {
      out.locations = new MondoParser().get_locations(code, offset);
    } catch (e) {
      out.locationsError = e.message;
    }
  }
  return out;
};

// the tunes of website/src/pages/learn/mondo-notation.mdx, which write `\*` for `*`
const docs = fs.readFileSync(path.join(strudel, 'website/src/pages/learn/mondo-notation.mdx'), 'utf8');
const docPrograms = [...docs.matchAll(/tune=\{`([\s\S]*?)`\}/g)].map((m) => m[1].replaceAll('\\*', '*'));

const handWritten = [
  '', ' ', '\n', 'a', 'a b', '()', '(a)', '(a b)', '((a b))', '(((a)))', '(a (b c))', '(1 .2 1.2 10 22.3)', '-1 -.5 -', '- 1', '1 - 1',
  'a // hello', '// hello', '// hello\nworld', 'a // x\nb', 'a // x\r\nb', 'a //', '//', '/ /', '"a b"', "'a b'", '"a', "'a", '"a\nb"',
  '"a" \'b\' "c d"', '""', "''", 'a"b"', '"a""b"',
  '[a b c]', '[a [b c] d]', '<a b c>', '<a <b c> d>', '{a b c}', '{a b c}%4', '[]', '<>', '{}', '[ ]', '[a', 'a]', ')', '(a))', '((a)', '[a)', '<a]',
  '[a, b]', '[a | b]', '[a, b | c]', '[a , b $ c]', '$ s bd $ s hh', '$', ',', '|', '| a', 'a |', 'a , , b', '[, a]', '[a,]', '[a , b c , d]',
  '<a, b c>', '{a, b c}', '(a, b)', '(a | b c | d)', '(a b | c, d | e f)',
  'a*2', 'a/2', 'a:b', 'a:b:c', 'a!2', 'a@3', 'a%4', 'a?', 'a?.5', 'a..b', '0..3', 'a+1', 'a-1', 'a&3', 'a*2:3', 'a:3*2', 'a*', '*a', '* a', 'a *', 'a**b',
  'a * * b', 'a +- b', '[a - -b]', 's [- bd]', 'a - b', 'a -b', 'a-b', 'a - 1', 'a -1', 'a -1 b', 'a .. b', '.. a', 'a ..', 'a..', '..', '1..2..3', 'a & b & c',
  'a*2&3', 'a&3*2', 'a:b&c:d', 'a!', 'a!!', '[a !]', '[a b !]', '[a ! b]', '[a@3 b]', '[a b@3]', 'a.5', 'a.b', 'a ^ b', 'a^b', 'a~b', '~', '~ ~', '_', 'a_b',
  'a # b', '# a', '#', 'a #', 'a # b # c', '(# fast 2)', '(#)', '(# fast 2 # room 1)', '((# mul 2) 2)', 'a # * 2', 'a # b c # d e', 'a # # b', '# # a',
  '[a # b]', '[# a]', '[a b # c, d # e]', '[a # b | c # d]', '<a b # c>', '{a b # c}', 'a#b', 'a #b', 'a# b', 'x # ! 2', 'x # + 1', 'x # - 1',
  'a b\tc\nd', 'a\u00a0b', 'a\u2003b', '\u00a0a\u2003', 'a\u2028b', '"a\u2028b"', 'a\u000bb', 'a\ufeffb', '\ufeffa', 'a\u0085b', 'é', 'a é', 'a,b,c', 'a$b$c',
  's bd', 's bd hh', 's [bd hh]', 's [bd hh]*2', 's <bd hh>', 's bd:3', 's bd*2 # fast 2', 'note [c e g]', 'n 0..7', 'n 0..7 # scale C:minor', 'n 0..7 # scale \'C minor\'',
  '(s hh*8)', 's hh*8', 'mondo', 'def a 1', '(def a 1)', '(def (f x) x)', '(fn (x) x)', '(let ((x 1)) x)', '(if a b c)', '(match (a b) (else c))',
  ...docPrograms,
];

// a seeded generator, so that the corpus does not change from one run to the next
const random = (() => {
  let a = 0x2f6e2b1;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
})();
const pick = (list) => list[Math.floor(random() * list.length)];
const atoms = ['s', 'n', 'bd', 'hh', 'cp', 'x', 'a', 'b', 'c2', 'fast', 'slow', 'rev', 'jux', 'stack', 'ply', 'speed', 'def', 'fn', 'if', 'match', 'else', '_', '~', '^', 'a-b', 'a#b'];
const numbers = ['1', '2', '3', '12', '.5', '1.5', '-1', '-3', '-.25', '0.25', '007'];
const strings = ['"x y"', "'a b'", '""', "''", '"x"', "'C minor'"];
const ops = ['*', '/', ':', '!', '@', '%', '?', '+', '-', '&', '..'];
const seps = ['', ' ', ' ', ' ', '  ', '\n', '\t', ' \n '];
const leaf = () => {
  const r = random();
  if (r < 0.45) return pick(atoms);
  if (r < 0.6) return pick(numbers);
  if (r < 0.68) return pick(strings);
  if (r < 0.74) return '// c\n';
  return pick(atoms);
};
const join = (parts) => parts.reduce((text, part, i) => (i === 0 ? part : text + pick(seps) + part), '');
// a balanced expression with operators, pipes, stacks and chooses in the right places and a few in the wrong ones
const structured = (depth) => {
  const parts = [];
  const count = 1 + Math.floor(random() * 5);
  for (let i = 0; i < count; i++) {
    const r = random();
    if (r < 0.12 && depth > 0) {
      const [open, close] = pick([['(', ')'], ['[', ']'], ['<', '>'], ['{', '}']]);
      parts.push(open + structured(depth - 1) + close);
    } else if (r < 0.3) {
      parts.push(pick(ops));
    } else if (r < 0.37) {
      parts.push(pick(['#', ',', '$', '|']));
    } else {
      parts.push(leaf());
    }
  }
  return join(parts);
};
const soup = () => {
  const vocabulary = ['(', ')', '[', ']', '<', '>', '{', '}', '#', ',', '$', '|', ...ops];
  const parts = [];
  const count = 1 + Math.floor(random() * 12);
  for (let i = 0; i < count; i++) parts.push(random() < 0.55 ? pick(vocabulary) : leaf());
  return join(parts);
};
const generated = [];
for (let i = 0; i < 700; i++) generated.push(structured(3));
for (let i = 0; i < 500; i++) generated.push(soup());

const parser = [];
for (const code of handWritten) for (const offset of [-1, 0, 25]) parser.push(parseCase(code, offset, true));
for (const code of generated) parser.push(parseCase(code, 5, false));

// ---- the runner -----------------------------------------------------------------------------------------------------

// A JSON text of a value: arrays and primitives as they are; what JSON cannot say is an object with one key.
const enc = (v) => {
  if (v === undefined) return { u: 1 };
  if (typeof v === 'number' && !Number.isFinite(v)) return { n: String(v) };
  if (typeof v === 'function') return { fn: 1 };
  if (Array.isArray(v)) return v.map(enc);
  return v;
};

// The evaluator of the arithmetic tests of mondo.test.mjs, kept as the file has it.
const multi =
  (op) =>
  (init, ...rest) =>
    rest.reduce((acc, arg) => op(acc, arg), init);
const lib = {
  '+': multi((a, b) => a + b),
  add: multi((a, b) => a + b),
  '-': multi((a, b) => a - b),
  sub: multi((a, b) => a - b),
  '*': multi((a, b) => a * b),
  '/': multi((a, b) => a / b),
  mod: multi((a, b) => a % b),
  eq: (a, b) => a === b,
  lt: (a, b) => a < b,
  gt: (a, b) => a > b,
  and: (a, b) => a && b,
  or: (a, b) => a || b,
  not: (a) => !a,
  run: (...args) => args[args.length - 1],
  def: () => 0,
  sin: Math.sin,
  cos: Math.cos,
  PI: Math.PI,
  cons: (a, b) => [a, ...(Array.isArray(b) ? b : [b])],
  car: (pair) => pair[0],
  cdr: (pair) => pair.slice(1),
  list: (...items) => items,
  nil: [],
  isnull: (items) => items.length === 0,
  concat: (...msgs) => msgs.join(''),
  error: (...msgs) => {
    throw new Error(msgs.join(' '));
  },
};
function evaluator(node, scope) {
  if (node.type !== 'list') {
    return scope[node.value] ?? lib[node.value] ?? node.value;
  }
  const [fn, ...args] = node.children;
  if (typeof fn !== 'function') {
    throw new Error(`"${fn}": expected function, got ${typeof fn} "${fn}"`);
  }
  return fn(...args);
}
const runner = new MondoRunner({ evaluator });
const run = MondoRunner.prototype.run;

// 1. the programs of mondo.test.mjs: the file is run with a stand-in for vitest that records what `run` is given and what
//    each test expects (the scope of a program is told by an id, and what it held when it was first seen)
const scopeIds = new Map();
const scopeFor = (scope) => {
  if (scope === undefined) return { scope: null };
  if (!scopeIds.has(scope)) scopeIds.set(scope, { id: scopeIds.size + 1, init: JSON.stringify(scope) });
  return { scope: scopeIds.get(scope).id, init: JSON.parse(scopeIds.get(scope).init) };
};
const recorded = [];
{
  let suite = '';
  let name = '';
  let runs = [];
  MondoRunner.prototype.run = function (code, scope, offset) {
    runs.push({ code, ...scopeFor(scope) });
    return run.call(this, code, scope, offset);
  };
  globalThis.__shim = {
    describe(title, fn) {
      suite = title;
      fn();
    },
    it(title, fn) {
      name = title;
      runs = [];
      fn();
    },
    expect(actual) {
      return {
        toEqual(expected) {
          if (runs.length === 0) return; // the tests of the parser are in the Kotlin tests by hand
          const pass = util.isDeepStrictEqual(actual, expected);
          if (!pass) throw new Error(`${suite} > ${name}: mondo.test.mjs expects ${util.inspect(expected)} but got ${util.inspect(actual)}`);
          recorded.push({ name: `${suite} > ${name}`, program: runs[runs.length - 1], expected: enc(expected) });
        },
      };
    },
  };
  const source = fs
    .readFileSync(path.join(strudel, 'packages/mondo/test/mondo.test.mjs'), 'utf8')
    .replace("import { describe, expect, it } from 'vitest';", 'const { describe, expect, it } = globalThis.__shim;')
    .replace("from '../mondo.mjs'", `from '${mondoUrl}'`);
  const file = path.join(os.tmpdir(), `mondo-test-${process.pid}.mjs`);
  fs.writeFileSync(file, source);
  try {
    await import(pathToFileURL(file).href);
  } finally {
    fs.rmSync(file, { force: true });
    MondoRunner.prototype.run = run;
  }
}

// 2. programs of our own: the special forms and what goes wrong with them
const mistakes = [
  '(+ 1 2)', '(def a 2) (* a a)', '(let ((x 1) (y 2)) (+ x y))', "(if (gt 1 0) 'yes' 'no')", "(if (lt 1 0) 'yes' 'no')", "(match ((lt 1 0) 'a') (else 'b'))",
  '(match)', '(match 5)', '(match ())', '(match ((gt 1 0)))', '(match (else))', '(match ((eq 1 1) 1) ((eq 1 1) 2))', '(match ((eq 1 2) 1))', '(if 1 2)', '(if 1 2 3 4)',
  '(if)', '(let)', '(let x)', '(let (x) x)', '(let ((x)) x)', '(let (x 1) x)', '(let ((x 1)))', '(let ((x 1) (y x)) y)', '(let ((x 1)) (let ((x 2)) x) x)',
  '(fn)', '((fn) 1)', '((fn x x) 1)', '((fn (x)) 1)', '((fn (x y) (+ x y)) 1)', '((fn (x) x) 1 2)', '((fn (x) x))', '((fn (x x) x) 1 2)', '((fn (x) 1 2 3) 1)',
  '((fn () 7))', '((fn ((a b)) 7) 1)', '(def)', '(def a)', '(def a 1 2)', '(def (f) 1)', '(def (f x y z) (+ x y z)) (f 1 2 3)', '(def () 1)', '(def (a b) c d)',
  '(def (a b) c d) (a 1)', '(def x 1) (def x 2) x', '(def (f) 1 2) (f)', '(def a (def b 1))', '(def a 1) (def b (+ a 1)) b', '(def f (fn (x) (+ x 1))) (f 1)',
  '(def (g n) (def m 10) (+ n m)) (g 1)', '(def (g n) (def m 10) (+ n m)) (g 1) m', '(def (f n) (if (eq n 0) 0 (+ n (f (- n 1))))) (f 10)',
  '(+ 1 2) // comment', '(+ 1 // inline\n 2)', '// only a comment', "'string'", '"string"', "(concat 'a' 'b')", '(list 1 2)', 'nil', '(car nil)', '(cdr nil)', "(error 'x' 'y')",
  '(foo)', '(1)', '(1 2)', "('a' 1)", '()', '(())', '((+ 1 2))', '(nil 1)', '((list 1) 2)', '[1 2]', '<1 2>', '{1 2}', '(+ 1 [2 3])', '(a b)',
  '1', '-1', '.5', "(+ 'a' 1)", '(+ 1 \'a\')', '(+ 1 nil)', '(- 1)', '(+)', '(* 2 3 4)', '(/ 1 0)', '(mod 7 3)', '(mod -7 3)', '(not 0)', '(not 1)', '(and 0 1)', '(or 0 1)', '(and 1 2)',
  '(eq 1 1)', '(eq 1 \'1\')', '(lt 1 2)', '(gt 1 2)', '(lt \'a\' \'b\')', '(cons 1 (list 2))', '(cons 1 2)', '(isnull nil)', '(isnull (list 1))', '(sin 0)', '(cos 0)', 'PI',
  '(def a 0) a', '(def a 0) (if a 1 2)', "(def a '') (if a 1 2)", '(def a nil) (if a 1 2)', '(if nil 1 2)',
  '(def a 1) ((fn (a) a) 2)', '(def a 1) ((fn (b) a) 2)', '((fn (list) list) 3)', '((fn (list) (list 1 2)))', '((fn (list) list))',
  '(let ((f (fn (x) (* x 2)))) (f 4))', '((let ((x 3)) (fn (y) (+ x y))) 4)', '(let ((x 1)) (def y 2) (+ x y))', '(let ((x 1)) (def y 2) (+ x y)) y',
  'a', 'a b', 'run 1 2 3', '1 2', '(def a 1) 2',
];
const programs = [];
for (const code of mistakes) {
  const entry = { name: code, program: { code: 'run ' + code }, expected: null };
  try {
    entry.expected = enc(runner.run('run ' + code, {}));
  } catch (e) {
    entry.error = e.message;
  }
  programs.push(entry);
}

fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'parser-oracle.json'), JSON.stringify(parser, null, 0).replaceAll('},{', '},\n{') + '\n');
fs.writeFileSync(
  path.join(outDir, 'runner-oracle.json'),
  JSON.stringify({ recorded, programs }, null, 0).replaceAll('},{', '},\n{') + '\n',
);
console.log(`parser: ${parser.length} programs; runner: ${recorded.length} recorded tests, ${programs.length} programs of our own`);
