// Reference data for the standard library of the script engine (engine/src/main/kotlin/cosic/script/next/js/stdlib/).
//
// Like interpreter-oracle.mjs (and with the same case format and prelude), but the snippets use the library: Math,
// Array, String, Number, Object, JSON, Map, Set, Date, console. They run in Node (V8), strict mode, and what they print is
// what StdlibOracleTest requires of the Kotlin library.
//
//   node tools/strudel-ref/stdlib-oracle.mjs
//
// Input   engine/src/test/resources/script/stdlib/cases.txt     one snippet per `### <name>` header (see interpreter-oracle.mjs)
//         engine/src/test/resources/script/interpreter/prelude.js
// Output  engine/src/test/resources/script/stdlib/oracle.json   { name: result text }
//
// Beside the helpers of the prelude (isArray, keys, hasOwn) a snippet may call `logs()`: the lines `console.*` wrote since
// the last call, as an array of strings (Node's own Console formats them: util.format and util.inspect). Local time
// depends on the machine, so a snippet only uses UTC methods, `Date.UTC` and ISO strings.
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { Console } from 'node:console';
import { Writable } from 'node:stream';
import { fileURLToPath } from 'node:url';
import { parseCases } from './interpreter-oracle.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.COSIC_ROOT ?? path.resolve(here, '../..');
const dir = path.join(root, 'engine/src/test/resources/script');

const prelude = fs.readFileSync(path.join(dir, 'interpreter/prelude.js'), 'utf8');
const cases = parseCases(fs.readFileSync(path.join(dir, 'stdlib/cases.txt'), 'utf8'));
const written = [];
const sink = new Writable({
  write(chunk, _encoding, done) {
    written.push(chunk.toString().replace(/\n$/, ''));
    done();
  },
});
const console_ = new Console({ stdout: sink, stderr: sink });
const context = vm.createContext({
  isArray: Array.isArray,
  keys: Object.keys,
  hasOwn: (o, k) => Object.prototype.hasOwnProperty.call(o, k),
  console: console_,
  logs: () => written.splice(0, written.length),
});
const oracle = {};
let failed = 0;
for (const c of cases) {
  if (c.name in oracle) throw new Error(`duplicate case ${c.name}`);
  try {
    written.length = 0;
    const script = new vm.Script(`(function () { "use strict";\n${prelude}\nreturn run(function () {\n${c.code}\n}); })()`, { filename: c.name });
    oracle[c.name] = script.runInContext(context, { timeout: 5000 });
  } catch (e) {
    failed++;
    console.error(`${c.name}: Node itself failed: ${e && e.message}`);
  }
}
fs.writeFileSync(path.join(dir, 'stdlib/oracle.json'), JSON.stringify(oracle, null, 1) + '\n');
console.log(`${cases.length} cases, ${Object.keys(oracle).length} results, ${failed} failures`);
if (failed) process.exit(1);
