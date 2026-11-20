// Copied to test/cosic-extra.test.mjs of the Strudel checkout and run with vitest by tools/strudel-ref/export.mjs.
//
// Runs the Cosic-authored snippets of engine/src/test/resources/parity/extra-snippets/<id>.json through Strudel and
// writes the expected output to engine/src/test/resources/parity/extra/<id>.json.
//
// Environment: COSIC_EXTRA = a task id, a comma separated list of ids, or `all`; COSIC_ROOT = the Cosic repository
// (default: four levels above this file, i.e. tools/strudel-ref/strudel/test/ -> repository root).
//
// A snippet is one of
//   {"name", "code", "cycles", "modules"?, "exactMessage"?}
//       evaluated as test/runtime.mjs's queryCode does (the oracle of the snapshot tests);
//   {"name", "mode": "repl", "codes": [code, ...], "cycles", "modules"?, "exactMessage"?}
//       evaluated one after the other by a REPL of @strudel/core (labels, all/each/hush, tempo persistence, solo), with
//       the setcps/setcpm/p mocks of runtime.mjs removed.
// `modules` are workspace packages (e.g. "@strudel/mondo") that are loaded into the scope first.
// Hap strings go through the roundFloatStrings of test/tunes.test.mjs, like the tune snapshots.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { afterAll, describe, it } from 'vitest';
import { evalScope, Pattern, repl, useRNG } from '@strudel/core';
import { transpiler } from '@strudel/transpiler';
import { queryCode } from './runtime.mjs';

const testDir = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.COSIC_ROOT ?? path.resolve(testDir, '../../../..');
const parityDir = path.join(root, 'engine/src/test/resources/parity');
const snippetsDir = path.join(parityDir, 'extra-snippets');
const outputDir = path.join(parityDir, 'extra');
const selection = process.env.COSIC_EXTRA ?? '';

const ids =
  selection === 'all'
    ? fs
        .readdirSync(snippetsDir)
        .filter((file) => file.endsWith('.json'))
        .map((file) => file.slice(0, -'.json'.length))
        .sort()
    : selection
        .split(',')
        .map((id) => id.trim())
        .filter(Boolean);

// test/tunes.test.mjs:8-18
const roundFloatStrings = (input, precision = 12) =>
  input.replace(/-?\d+\.\d+/g, (match) =>
    Number(match).toFixed(precision).replace(/\.?0+$/, '').replace(/\.$/, ''),
  );

// test/runtime.mjs:81-108 and :177-179. `samples` is mocked as well so that no snippet needs the network.
const id = (x) => x;
const identityMethods = [
  'osc', 'csound', 'tone', 'webdirt', 'pianoroll', 'speak', 'wave', 'filter', 'adsr', 'webaudio', 'soundfont', 'tune',
  'midi', '_scope', '_spiral', '_pitchwheel', '_pianoroll', '_spectrum', '_print', 'markcss', 'p', 'dough',
];
const applyOracleMocks = () => {
  // module state of Strudel that a snippet can change (`useRNG('precise')`) must not reach the next snippet
  useRNG('legacy');
  identityMethods.forEach((name) => {
    Pattern.prototype[name] = function () {
      return this;
    };
  });
  return evalScope({ setcps: id, setcpm: id, samples: id });
};

// '@strudel/mondo' -> packages/mondough/mondough.mjs: the root of the checkout only links its own dependencies.
const workspaceEntry = (spec) => {
  const packagesDir = path.resolve(testDir, '../packages');
  for (const entry of fs.readdirSync(packagesDir)) {
    const manifest = path.join(packagesDir, entry, 'package.json');
    if (!fs.existsSync(manifest)) continue;
    const json = JSON.parse(fs.readFileSync(manifest, 'utf8'));
    if (json.name === spec) return path.join(packagesDir, entry, json.main ?? 'index.js');
  }
  return undefined;
};
const loadModules = (specs = []) =>
  Promise.all(
    specs.map((spec) => {
      const entry = workspaceEntry(spec);
      return import(/* @vite-ignore */ entry ? pathToFileURL(entry).href : spec);
    }),
  );

const messageOf = (err) => String(err?.message ?? err);

const runTestMode = async (snippet) => {
  try {
    await applyOracleMocks();
    await evalScope(...(await loadModules(snippet.modules)));
    const haps = await queryCode(snippet.code, snippet.cycles);
    return { haps: haps.map((hap) => roundFloatStrings(hap)) };
  } catch (err) {
    return { haps: [], error: messageOf(err) };
  }
};

const runReplMode = async (snippet) => {
  await applyOracleMocks();
  await evalScope(...(await loadModules(snippet.modules)));
  const session = repl({ getTime: () => 0, transpiler, onSchedule: () => {}, setInterval, clearInterval });
  for (const code of snippet.codes) {
    await session.evaluate(code, false);
  }
  const pattern = session.state.pattern;
  const haps = pattern
    ? pattern.sortHapsByPart().queryArc(0, snippet.cycles).map((hap) => roundFloatStrings(hap.show(true)))
    : [];
  const evalError = session.state.evalError;
  return {
    haps,
    cps: session.scheduler.cps,
    miniLocations: (session.state.miniLocations ?? []).map(([from, to]) => [from, to]),
    error: evalError ? messageOf(evalError) : null,
  };
};

const withDefaults = (snippet) => ({ ...snippet, cycles: snippet.cycles ?? 4 });

const expectedEntry = (snippet, result) => ({
  name: snippet.name,
  ...(snippet.mode === 'repl' ? { mode: 'repl', codes: snippet.codes } : { code: snippet.code }),
  cycles: snippet.cycles,
  ...(snippet.modules ? { modules: snippet.modules } : {}),
  ...(snippet.exactMessage ? { exactMessage: true } : {}),
  ...result,
});

const results = new Map(); // id -> entries by snippet position
const loaded = ids.map((snippetId) => {
  const file = path.join(snippetsDir, `${snippetId}.json`);
  const snippets = JSON.parse(fs.readFileSync(file, 'utf8')).map(withDefaults);
  const names = new Set();
  for (const snippet of snippets) {
    if (names.has(snippet.name)) throw new Error(`${file}: duplicate snippet name ${snippet.name}`);
    names.add(snippet.name);
  }
  results.set(snippetId, new Array(snippets.length));
  return { snippetId, snippets };
});

// Plain snippets first: a REPL leaves all/each/hush and the real p/setcps behind in the global scope.
describe('cosic extra snippets', () => {
  if (loaded.length === 0) {
    it('has nothing to do', () => {});
  }
  for (const mode of ['test', 'repl']) {
    for (const { snippetId, snippets } of loaded) {
      snippets.forEach((snippet, position) => {
        if ((snippet.mode === 'repl') !== (mode === 'repl')) return;
        it(`${snippetId}: ${snippet.name}`, { timeout: 120_000 }, async () => {
          let result;
          try {
            result = snippet.mode === 'repl' ? await runReplMode(snippet) : await runTestMode(snippet);
          } catch (err) {
            result = { haps: [], cps: null, miniLocations: [], error: messageOf(err) };
          }
          results.get(snippetId)[position] = expectedEntry(snippet, result);
        });
      });
    }
  }

  afterAll(() => {
    fs.mkdirSync(outputDir, { recursive: true });
    for (const { snippetId, snippets } of loaded) {
      const entries = results.get(snippetId);
      if (entries.some((entry) => entry === undefined)) {
        throw new Error(`${snippetId}: not every snippet ran`);
      }
      const text = `[\n${entries.map((entry) => JSON.stringify(entry)).join(',\n')}\n]\n`;
      fs.writeFileSync(path.join(outputDir, `${snippetId}.json`), snippets.length ? text : '[]\n');
    }
  });
});
