// Writes the Cosic-authored snippets of task 1.3 (the controls), engine/src/test/resources/parity/extra-snippets/1.3.json:
//
//   node tools/strudel-ref/controls-registry.mjs     # the registry of controls.mjs (engine/src/test/resources/parity/controls-registry.json)
//   node tools/strudel-ref/controls-snippets.mjs     # this file
//   node tools/strudel-ref/export.mjs --extra 1.3    # what Strudel makes of them: extra/1.3.json
//
// Every method name and every exported function of controls.mjs gets one snippet that calls it three ways (a plain number, a
// pattern, a colon list), so that a missing name, a wrong key list or a wrong namespace shows up as a difference in the haps;
// the controls that stand for a kind of registration get a lot more (zero-argument calls, objects with `value`, arrays, single
// quotes, a receiver for the global function), and so does every function of the file that is not a registration.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.COSIC_ROOT ?? path.resolve(here, '../..');
const parity = path.join(root, 'engine/src/test/resources/parity');
const registry = JSON.parse(fs.readFileSync(path.join(parity, 'controls-registry.json'), 'utf8'));

const snippets = [];
const add = (name, code, extra = {}) => snippets.push({ name, code, cycles: extra.cycles ?? 1, ...(extra.exactMessage ? { exactMessage: true } : {}) });

// ---- every name, three ways -----------------------------------------------------------------------------------------------

// The method sweep: pat.name(0.5), pat.name("0.25 0.75"), pat.name("1:2:3:4:5:6"). `ds` is not a control method.
const methodNames = Object.keys(registry.methods).filter((name) => !registry.methods[name].overriddenBy).sort();
for (const name of methodNames) {
  add(
    `${name}.method`,
    `stack(s("bd hh").${name}(0.5), s("bd hh").${name}("0.25 0.75"), s("bd hh").${name}("1:2:3:4:5:6"))`,
  );
}

// The global sweep. `noise` is skipped: the oracle runtime replaces the global with a mock of a Tone.js node.
const globalNames = Object.keys(registry.globals).filter((name) => name !== 'noise').sort();
for (const name of globalNames) {
  add(`${name}.global`, `stack(${name}(0.5), ${name}("0.25 0.75"), ${name}("1:2:3:4:5:6"))`);
}

// ---- a kind of registration each: all the ways a control can be called ---------------------------------------------------------------

const kinds = [
  's', // array form, three keys, an alias (sound)
  'n', // string form; note|n sets it too
  'note', // array form: note, n
  'lpf', // alias of cutoff|resonance|lpenv
  'delay', // array form, canonical name called as such
  'fmh', // keys swapped against fmi
  'fmi1', // method and global are different functions
  'fmenv', // array form with one key
  'fmwave1', // alias of an array form with one key
  'gain',
  'pan',
  'bank',
  'orbit',
  'o', // method only
  'color', // color|colour
  'transient',
  'vib',
  'vibmod',
  'room',
  'distort',
  'chord',
  'mode',
];
for (const name of kinds) {
  const isMethod = name in registry.methods;
  const isGlobal = name in registry.globals;
  const variants = [];
  if (isMethod) {
    variants.push(
      [`s("bd hh").${name}(0.5)`, 'a number'],
      [`s("bd hh").${name}("0.25 0.75")`, 'a pattern'],
      [`s("bd hh").${name}("<1:2 3:4>")`, 'a pattern of lists'],
      [`s("bd hh").${name}([1, 2, 3])`, 'an array'],
      [`s("bd hh").${name}('x')`, 'a string'],
      [`s("bd hh").${name}('x:y')`, 'a string with a colon'],
      [`s("bd hh").${name}({ value: 3 })`, 'an object with value'],
      [`s("bd hh").${name}({ value: 3, color: 'red' })`, 'value and other keys'],
      [`s("bd hh").${name}({ value: [1, 2] })`, 'an array in value'],
      [`"c e".${name}()`, 'no argument'],
      [`s("bd hh").${name}(1).${name}(2)`, 'twice'],
      [`s("bd hh").${name}()`, 'no argument, controls on the values'],
      [`s("bd hh").${name}(0.5, 0.7)`, 'extra arguments are ignored'],
      [`s("bd").slow(2).${name}(0.5)`, 'pure arguments fragment wholes at cycle boundaries'],
      [`s("bd*2").${name}("<1 2 3>")`, 'a slow pattern'],
    );
  }
  if (isGlobal) {
    variants.push(
      [`${name}(0.5)`, 'a number'],
      [`${name}("0.25 0.75")`, 'a pattern'],
      [`${name}("1:2:3:4:5:6")`, 'a list'],
      [`${name}(0.5, s("bd hh"))`, 'a pattern to set it on'],
      [`${name}("0.25 0.75", s("bd hh"))`, 'a pattern of values and a pattern to set it on'],
      [`${name}()`, 'no argument'],
      [`${name}(undefined, s("bd hh"))`, 'undefined and a pattern'],
      [`${name}({ value: 3, color: 'red' })`, 'value and other keys'],
      [`${name}(0.5, 0)`, 'a falsy second argument'],
    );
  }
  variants.forEach(([code], index) => add(`${name}.${index + 1}`, code, { cycles: 4 }));
}

// ---- namespaces ----------------------------------------------------------------------------------------------------------------------

add('namespace.fmh1', `stack(fmh1("1:2"), s("bd").fmh1("1:2"), s("bd").fmi1("1:2"), fmi1("1:2"))`);
add('namespace.colourMethod', `s("bd").colour("red")`, { exactMessage: true });
add('namespace.colourGlobal', `colour("red")`, { exactMessage: true });
add('namespace.colourIsUndefined', `n(typeof colour === 'undefined' ? 1 : 0)`);
add('namespace.colorWorks', `stack(color("red"), s("bd").color("red"), s("bd").color("red:blue"))`);
add('namespace.oIsAMethod', `s("bd").o(2)`);
add('namespace.oIsNotAGlobal', `o(2)`, { exactMessage: true });
add('namespace.dsMethod', `s("bd hh").ds("0.4:0.2")`);
add('namespace.dsMethodOneValue', `s("bd hh").ds(0.4)`);
add('namespace.dsGlobal', `ds("0.4:0.2", s("bd hh"))`);
add('namespace.delaysyncStillControl', `s("bd hh").delaysync(0.25).delays("0.5 1")`);
add('namespace.tremsyncAliasIsAMethod', `s("bd hh").tremsync("1:2:3:4")`);
add('namespace.tremsyncIsNotAGlobal', `tremsync("1:2")`, { exactMessage: true });
add('namespace.isControlName', `stack(n(isControlName('lpf') ? 1 : 0), n(isControlName('ds') ? 1 : 0), n(isControlName('colour') ? 1 : 0), n(isControlName('nope') ? 1 : 0), n(isControlName('fmh1') ? 1 : 0))`);
add('namespace.getControlName', `stack(s(getControlName('lpf')), s(getControlName('bpf')), s(getControlName('fmh1')), s(getControlName('ds')), s(getControlName('nope')), s(getControlName('size')))`);

// ---- key order, overwriting, merging -----------------------------------------------------------------------------------------------------

add('order.overwriteKeepsPosition', `s("bd").n(1).s("hh").n(2)`);
add('order.mergeSecondWins', `s("bd").gain(0.5).pan(0.2).gain(0.9)`);
add('order.listOnExistingKeys', `s("bd:1:0.5").s("hh").gain(2)`);
add('order.valueMergeKeepsItsKeysFirst', `s("bd").lpf({ value: 100, color: 'red', s: 'hh' })`);
add('order.constructorThenSet', `n("1").s("bd").n(3).s("x")`);
add('order.numbersAsValues', `"1 2".n().s("bd")`);
add('order.plainStringsGetValue', `"c e".s("x")`);
add('order.plainNumbersGetValue', `"1 2".lpf(4)`);
add('order.fragmentsAtCycleBoundaries', `s("bd").slow(2).lpf(500)`, { cycles: 4 });
add('order.slowPatternedArgument', `s("bd hh").slow(2).lpf("<500 800>")`, { cycles: 4 });
add('order.valueWithNullKeepsUndefined', `s("bd").gain(undefined)`);
add('order.undefinedInList', `s("bd").lpf([100, undefined, 3])`);

// ---- as, ad, ds, ar, control, sysex, scrub (adsr is the identity in the oracle runtime, both as a method and as a global: it has unit tests instead) ---------------------------------------------------------------------------------------

add('as.1', `"c:.5 a:1".as("note:clip")`);
add('as.2', `"1 2".as("n")`);
add('as.3', `"1:2:3".as("lpf:hpf")`);
add('as.4', `"1:2".as("lpf:foo")`);
add('as.5', `"x".as(["s", "n"])`);
add('as.6', `"x:3:4".as("s:n")`);
add('as.7', `"0.5 0.25".as("<lpf hpf>")`, { cycles: 4 });
add('as.8', `"x:3".as("s:fmh1")`);
add('ad.1', `s("bd hh").ad("0.1:0.2")`);
add('ad.2', `s("bd hh").ad(0.3)`);
add('ad.3', `s("bd hh").ad("0.1 0.2")`);
add('ad.4', `ad("0.1:0.2", s("bd hh"))`);
add('ds.1', `s("bd hh").ds("0.1:0.2")`);
add('ds.2', `s("bd hh").ds(0.3)`);
add('ds.3', `s("bd hh").ds("0.1 0.2")`);
add('ar.1', `s("bd hh").ar("0.1:0.2")`);
add('ar.2', `s("bd hh").ar(0.3)`);
add('ar.3', `s("bd hh").ar("0.1 0.2")`);
add('ar.4', `ar("0.1:0.2", s("bd hh"))`);
add('control.1', `s("bd hh").control("1:2")`);
add('control.2', `s("bd hh").control([1, 2])`);
add('control.3', `s("bd hh").control("<1:2 3:4>")`, { cycles: 2 });
add('control.4', `s("bd hh").control(5)`, { exactMessage: true });
add('control.5', `control("1:2", s("bd hh"))`);
add('sysex.1', `s("bd hh").sysex([1, 2])`);
add('sysex.2', `s("bd hh").sysex("1:2")`);
add('sysex.3', `s("bd hh").sysex(5)`, { exactMessage: true });
add('scrub.1', `s("bev").scrub("0.25")`);
add('scrub.2', `s("bev").scrub("0.1 0.25")`);
add('scrub.3', `s("bev").scrub("<0.1:2 0.5>")`, { cycles: 2 });
add('scrub.4', `s("bev").scrub("0.1:0.5")`);

// ---- the distortion shortcuts -----------------------------------------------------------------------------------------------------------------------

for (const name of ['soft', 'hard', 'cubic', 'diode', 'asym', 'fold', 'sinefold', 'chebyshev']) {
  add(`${name}.method`, `stack(s("bd hh").${name}(2), s("bd hh").${name}("1:0.5"), s("bd hh").${name}("<1 3>"), s("bd hh").${name}("1:0.5:xyz"))`, { cycles: 2 });
  add(`${name}.global`, `stack(${name}(2), ${name}("1:0.5"), ${name}(2, s("bd hh")), ${name}("<1 3>"))`, { cycles: 2 });
}

// ---- the modulators ----------------------------------------------------------------------------------------------------------------------------------------------

add('lfo.1', `s("saw").note("F1").lpf(500).lfo()`);
add('lfo.2', `s("saw").lpf(500).lfo({ rate: 4, depth: 0.5 })`);
add('lfo.3', `s("saw").lpf(500).lfo({ r: 2, dep: 3, SH: "sine", sk: 0.1 }, 'x')`);
add('lfo.4', `s("saw").lpf(500).lfo({ c: "lpf", rate: "<1 2>" })`, { cycles: 2 });
add('lfo.5', `s("saw").lfo().lpf(500).lfo({ s: 0.3 })`);
add('lfo.6', `s("saw").lpf(500).lfo({ rate: "1 2" })`);
add('lfo.7', `s("saw").lpf(500).lfo({ control: "hpf", sc: "foo" })`);
add('lfo.8', `s("saw").gain(0.5).lfo({ rate: 1, nonsense: 5 })`);
add('lfo.9', `lfo({ rate: 2 })`, { cycles: 3 });
add('lfo.10', `s("saw").lfo({ rate: 2 })`);
add('lfo.11', `s("saw").lpf(500).lfo({ rate: 4 }, "<0 1>")`, { cycles: 2 });
add('lfo.12', `s("saw").lpf(500).lfo({ rate: 4 }, 3).lfo({ depth: 1 })`);
add('lfo.13', `s("saw").lpf(500).lfo({ rate: 4 }).lfo({ rate: 8 }).lpf(300).lfo({ rate: 1 })`);
add('lfo.14', `s("saw").lpf(500).lfo()`.replace('.lfo()', '.lfo(5)'));
add('env.1', `s("saw").note("F1").lpf(500).env({ a: 1 })`);
add('env.2', `s("saw").lpf(500).env({ attack: 0.1, d: 0.2, sus: 0.3, rel: 0.4, dep: 2, da: 3, ac: 0.1, dc: 0.2, rc: 0.3 })`);
add('env.3', `s("saw").lpf(500).env({ a: "0.1 0.2" })`);
add('env.4', `s("saw").lpf(500).env({ a: 1 }, 'x').env({ d: 2 }, 'x')`);
add('env.5', `env({ a: 1 })`, { cycles: 3 });
add('env.6', `s("saw").env({ d: 1 }).lpq(4).lpf(50).env({ a: 0.1, d: 1, ac: 0.8, dc: 0.3, depth: 50 })`);
add('bmod.1', `s("saw").bmod({ b: 1 })`);
add('bmod.2', `s("saw").gain(0.5).bmod({ bus: 2, dep: 0.5, da: 1, dc: 3 })`);
add('bmod.3', `bmod({ b: 1 })`, { cycles: 3 });
add('modulate.1', `s("saw").lpf(500).modulate('lfo', { r: 1 })`);
add('modulate.2', `s("saw").lpf(500).modulate('env', { a: 1 })`);
add('modulate.3', `s("saw").lpf(500).modulate('zzz', { r: 1 })`);
add('modulate.4', `s("saw").lpf(500).modulate('bmod', { b: 1 }, 2)`);

// ---- controls made by the program ----------------------------------------------------------------------------------------------------------------------------------

add('registerControl.1', `const { zzCtlA } = registerControl('zzCtlA'); stack(zzCtlA(1), s("bd").zzCtlA(2))`);
add('registerControl.2', `const bag = registerControl('zzCtlB', 'zzAliasB'); stack(bag.zzCtlB(1), bag.zzAliasB("2:3"), s("bd").zzAliasB(4), n(isControlName('zzAliasB') ? 1 : 0), s(getControlName('zzAliasB')))`);
add('registerControl.3', `const bag = registerControl(['zzCtlC', 'zzCtlD'], 'zzAliasC'); stack(bag.zzCtlC("1:2:3"), s("bd").zzAliasC("4:5"))`);
add('registerMultiControl.1', `const bag = registerMultiControl(['zzMa', 'zzMb'], 2, 'zzMx'); stack(bag.zzMa("1:2"), bag.zzMx2("3:4"), s("bd").zzMx("5:6"), s("bd").zzMx1("7:8"), s("bd").zzMa2("9:10"), s("bd").zzMb1(11))`);
add('registerMultiControl.2', `const bag = registerMultiControl('zzMc', 3); stack(bag.zzMc(1), bag.zzMc2(2), bag.zzMc3("3:4"), s("bd").zzMc1(5))`);
add('createParam.1', `const f = createParam('zzParamA'); stack(f(1), s("bd").zzParamA(2), n(isControlName('zzParamA') ? 1 : 0))`);
add('createParam.2', `const f = createParam(['zzParamB', 'zzParamC']); stack(f("1:2:3"), s("bd").zzParamB("4:5"))`);
add('createParams.1', `const { zzParamD, zzParamE } = createParams('zzParamD', 'zzParamE'); stack(zzParamD(1), zzParamE("2:3"), s("bd").zzParamD(4).zzParamE(5))`);

fs.writeFileSync(path.join(parity, 'extra-snippets/1.3.json'), `[\n${snippets.map((s) => `  ${JSON.stringify(s)}`).join(',\n')}\n]\n`);
console.log(`${snippets.length} snippets (${methodNames.length} method names, ${globalNames.length} global names)`);
