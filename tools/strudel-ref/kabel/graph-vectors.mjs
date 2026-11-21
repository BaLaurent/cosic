// Compile-structure vectors: runs every program of graph-cases.json through the REAL evaluate + compile and records what comes out.
// Normally run through render-golden.mjs (its documented one-liner writes engine/src/test/resources/kabel/graph-vectors.json).
// Standalone: node --import ./register-loader.mjs graph-vectors.mjs [graph-cases.json] [out.json]
//
// Entry: {id, code, error, errorName, errorStage, registers, ugens, src, ...extras}
//   code        the program as passed to evaluate (after the superdough substitution; gates are cc('strudel-gate-0'))
//   error       null, or e.message of whatever evaluate/compile threw (then errorName = e.name, errorStage = 'evaluate'|'compile', and
//               registers/ugens/src are null)
//   ugens       [{type, inputs}] exactly as compile() returns; inputs: numbers as numbers, strings as strings, undefined as null,
//               NaN/Infinity as the strings 'NaN'/'Infinity'/'-Infinity'
//   console     (only when non-empty) console.log/warn output produced while evaluating/compiling
//   functionError   null, or `Name: message` of `new Function('time','nodes','input','r','o','s','o.fill(0);\n' + src)`
//   workletError    null, or `Name: message` of the real GenericProcessor.onmessage for the compiled program (cc(5) throws here)
//   runError        null, or `Name: message` of two real process() blocks (start 0, end 1, sampleRate 48000)
//   note        free text
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { evaluateKabel, mulberry32, newProcessorClass, setCurrentTime, setSampleRate, ugenInputs, captureConsole } from './kabel-realm.mjs';

const errText = (e) => `${e?.name ?? 'Error'}: ${e?.message ?? e}`;

export async function generateGraphVectors(cases) {
  const vectors = [];
  const realMathRandom = Math.random;
  const seen = new Set();
  for (const c of cases) {
    if (seen.has(c.id)) throw new Error(`duplicate graph case id ${c.id}`);
    seen.add(c.id);
    setSampleRate(48000);
    setCurrentTime(0);
    Math.random = mulberry32(1);
    const entry = { id: c.id, code: c.code, error: null, errorName: null, errorStage: null, registers: null, ugens: null, src: null };
    let stage = 'evaluate';
    const r = captureConsole(() => {
      const exit = evaluateKabel(c.code);
      stage = 'compile';
      return exit.compile({ log: false });
    });
    let compiled = null;
    if (r.error) {
      entry.error = String(r.error?.message ?? r.error);
      entry.errorName = r.error?.name ?? typeof r.error;
      entry.errorStage = stage;
    } else {
      compiled = r.result;
      entry.registers = compiled.registers;
      entry.ugens = compiled.ugens.map((u) => ({ type: u.type, inputs: ugenInputs([u])[0] }));
      entry.src = compiled.src;
    }
    if (r.logs.length) entry.console = r.logs;
    entry.functionError = null; entry.workletError = null; entry.runError = null;
    if (compiled) {
      try { new Function('time', 'nodes', 'input', 'r', 'o', 's', 'o.fill(0);\n' + compiled.src); } catch (e) { entry.functionError = errText(e); }
      let proc = null;
      try {
        const GP = await newProcessorClass(`graph-${c.id}`);
        proc = new GP();
        proc.port.onmessage({ data: { src: compiled.src, schema: { ugens: compiled.ugens, registers: compiled.registers }, start: 0, gateEnd: 1, end: 1 } });
      } catch (e) { entry.workletError = errText(e); proc = null; }
      if (proc && !entry.functionError) {
        try {
          const L = new Float32Array(128), R = new Float32Array(128);
          for (let blk = 0; blk < 2; blk++) { setCurrentTime((blk * 128) / 48000); proc.process([[]], [[L, R]]); }
        } catch (e) { entry.runError = errText(e); }
      }
    }
    if (c.note) entry.note = c.note;
    vectors.push(entry);
  }
  Math.random = realMathRandom;
  return vectors;
}

// standalone
if (process.argv[1] && fileURLToPath(import.meta.url) === fs.realpathSync(process.argv[1])) {
  const inFile = process.argv[2] ?? new URL('./graph-cases.json', import.meta.url).pathname;
  const vectors = await generateGraphVectors(JSON.parse(fs.readFileSync(inFile, 'utf8')));
  if (process.argv[3]) fs.writeFileSync(process.argv[3], JSON.stringify(vectors, null, 1) + '\n');
  for (const v of vectors) console.log(`${v.id}: ${v.error ? `${v.errorName}(${v.errorStage}): ${v.error}` : `ok regs=${v.registers} ugens=${v.ugens.length}`}${v.functionError ? ` | fn ${v.functionError}` : ''}${v.workletError ? ` | worklet ${v.workletError}` : ''}${v.runError ? ` | run ${v.runError}` : ''}`);
}
