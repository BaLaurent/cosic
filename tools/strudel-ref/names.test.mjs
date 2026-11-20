// Copied to test/names.test.mjs of the Strudel checkout and run with vitest by tools/strudel-ref/export.mjs.
//
// Writes engine/src/test/resources/parity/strudel-names.json, the names a program can use in the oracle runtime
// (test/runtime.mjs, the scope that Strudel's own snapshot tests evaluate in):
//   globals   Object.keys(globalThis) that the runtime added to a bare Node,
//   methods   Object.getOwnPropertyNames(Pattern.prototype),
//   controls  the keys of core's controls.mjs registry (names for which isControlName() holds).
// CoverageTest requires every one of them to be implemented or listed in coverage-exclusions.txt.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it } from 'vitest';

// Taken before runtime.mjs is imported: whatever the oracle scope adds to globalThis comes after this line.
const bareGlobals = new Set(Object.keys(globalThis));

const testDir = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.COSIC_ROOT ?? path.resolve(testDir, '../../../..');
const output = path.join(root, 'engine/src/test/resources/parity/strudel-names.json');

// evalScope() assigns the scope on a microtask after runtime.mjs has been evaluated; it does so in one
// synchronous loop, so seeing `note` means the whole scope is in place.
const evalScopeSettled = async () => {
  for (let waited = 0; typeof globalThis.note !== 'function'; waited += 20) {
    if (waited > 20_000) throw new Error('evalScope() of test/runtime.mjs did not finish');
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
};

const block = (key, names) => `  ${JSON.stringify(key)}: [\n${names.map((name) => `    ${JSON.stringify(name)}`).join(',\n')}\n  ]`;

describe('strudel names', () => {
  it('lists the globals, Pattern methods and controls of the oracle runtime', async () => {
    await import('./runtime.mjs');
    await evalScopeSettled();
    const strudel = await import('@strudel/core');
    const globals = Object.keys(globalThis).filter((name) => !bareGlobals.has(name)).sort();
    const methods = Object.getOwnPropertyNames(strudel.Pattern.prototype).sort();
    const controls = Object.keys(strudel.controls).filter((name) => strudel.isControlName(name)).sort();
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(
      output,
      `{\n${[block('globals', globals), block('methods', methods), block('controls', controls)].join(',\n')}\n}\n`,
    );
  });
});
