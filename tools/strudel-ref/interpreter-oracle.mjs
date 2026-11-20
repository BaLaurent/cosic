// Reference data for the JavaScript interpreter of the script engine (engine/src/test/kotlin/cosic/script/next/).
//
// It runs snippets in Node (V8, the engine Strudel's code runs on), strict mode, and writes what they gave, so the Kotlin
// interpreter can be compared with V8 on values, key order and the text of error messages:
//
//   node tools/strudel-ref/interpreter-oracle.mjs
//
// Input   engine/src/test/resources/script/interpreter/cases.txt    one snippet per `### <name>` header
//         engine/src/test/resources/script/interpreter/prelude.js   show() and run(), shared with the Kotlin test
// Output  engine/src/test/resources/script/interpreter/oracle.json  { name: result text }
//
// A snippet is the body of a function; it returns a value, which prelude.js's show() prints, or throws, which run() prints
// as `THROW <name>: <message>`. Format of cases.txt: `### <name>` then the code, or `### <name> !json` then one JSON string
// literal (for code with characters that are awkward in a text file); `## ` lines are comments.
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.COSIC_ROOT ?? path.resolve(here, '../..');
const dir = path.join(root, 'engine/src/test/resources/script/interpreter');

export function parseCases(text) {
  const cases = [];
  let current = null;
  for (const line of text.split('\n')) {
    if (line.startsWith('## ')) continue;
    const header = /^### (\S+)( !json)?$/.exec(line);
    if (header) {
      current = { name: header[1], json: !!header[2], lines: [] };
      cases.push(current);
    } else if (current) {
      current.lines.push(line);
    }
  }
  for (const c of cases) {
    const body = c.lines.join('\n').replace(/\n+$/, '');
    c.code = c.json ? JSON.parse(body) : body;
  }
  return cases;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const prelude = fs.readFileSync(path.join(dir, 'prelude.js'), 'utf8');
  const cases = parseCases(fs.readFileSync(path.join(dir, 'cases.txt'), 'utf8'));
  const context = vm.createContext({
    isArray: Array.isArray,
    keys: Object.keys,
    hasOwn: (o, k) => Object.prototype.hasOwnProperty.call(o, k),
  });
  const oracle = {};
  let failed = 0;
  for (const c of cases) {
    if (c.name in oracle) throw new Error(`duplicate case ${c.name}`);
    try {
      const script = new vm.Script(`(function () { "use strict";\n${prelude}\nreturn run(function () {\n${c.code}\n}); })()`, { filename: c.name });
      oracle[c.name] = script.runInContext(context, { timeout: 5000 });
    } catch (e) {
      failed++;
      console.error(`${c.name}: Node itself failed: ${e && e.message}`);
    }
  }
  fs.writeFileSync(path.join(dir, 'oracle.json'), JSON.stringify(oracle, null, 1) + '\n');
  console.log(`${cases.length} cases, ${Object.keys(oracle).length} results, ${failed} failures`);
  if (failed) process.exit(1);
}
