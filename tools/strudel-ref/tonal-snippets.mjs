// Writes the Cosic-authored snippets of task 2.8 (the tonal functions), engine/src/test/resources/parity/extra-snippets/2.8.json:
//
//   node tools/strudel-ref/tonal-data.mjs            # the data of the Kotlin port (and what to compare it with)
//   node tools/strudel-ref/tonal-snippets.mjs        # this file
//   node tools/strudel-ref/export.mjs --extra 2.8    # what Strudel makes of them: extra/2.8.json
//
// The functions that have no `@example` in Strudel (the synonyms trans, scaleTrans and strans, and setDefaultVoicings, setVoicingRange,
// registerVoicings, voicingAlias, resetVoicings, voicingRegistry) are called plain and with a pattern as argument; every scale name of
// tonal is used once; chords, dictionaries, anchors, modes, offsets and octaves are combined.
//
// The oracle runs all snippets of a file in one process, and tonal's state is global: `voicings` remembers its last voicing, the default
// dictionary and the ranges can be changed, and `transpose` changes the cached note it transposes (the Kotlin port does not copy that),
// so the snippets reset what they depend on, name the dictionaries they add differently, and use every octave-less note once.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.COSIC_ROOT ?? path.resolve(here, '../..');
const output = path.join(root, 'engine/src/test/resources/parity/extra-snippets/2.8.json');
const { ScaleType } = await import(pathToFileURL(path.join(here, 'strudel/packages/tonal/node_modules/@tonaljs/tonal/dist/index.mjs')).href);

const snippets = [];
const add = (name, code, cycles = 1) => {
  if (snippets.some((s) => s.name === name)) throw new Error(`duplicate snippet ${name}`);
  snippets.push({ name, code, cycles });
};
const single = (text) => `'${text.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`;

// ---- scale ----------------------------------------------------------------------------------------------------------------------

const types = ScaleType.all();
const typeNames = types.flatMap((t) => [t.name, ...t.aliases]);
typeNames.forEach((name, i) => add(`scale.name.${i}`, `n("0 1 2 3 4 5 6 7 -1 -8").scale(${single(`C ${name}`)})`));
// the notation's own spelling of a scale name: spaces are colons (a name with an apostrophe cannot be written)
const miniNames = typeNames.filter((name) => /^[\p{L}0-9\- #.^_]+$/u.test(name));
miniNames.forEach((name, i) => add(`scale.mini.${i}`, `n("0 2 4 7 9").scale("D:${name.replace(/ /g, ':')}")`));
for (const [i, tonic] of ['C', 'D', 'Eb', 'F#', 'G#', 'Bb', 'Cb', 'E#', 'c4', 'a2', 'B#5', 'Dbb', 'f', 'ab3'].entries()) {
  add(`scale.tonic.${i}`, `n("0 1 2 3 4 5 6 7").scale(${single(`${tonic} major`)})`);
  add(`scale.tonic.minor.${i}`, `n("0 1 2 3 4 5 6").scale(${single(`${tonic} minor pentatonic`)})`);
}
add('scale.noTonic.1', `n("0 1 2 3 4 5 6 7").scale('major')`);
add('scale.noTonic.2', `n("<0 2 4>*2").scale('minor pentatonic')`);
add('scale.degrees.accidentals', `n("0b 1# 2## 3bb -2# 7b 4s 5f 6#").scale('C major')`);
add('scale.degrees.negative', `n("-1 -2 -3 -7 -8 -9 -15").scale('C major')`);
add('scale.degrees.large', `n("7 8 14 15 21 22 100 -100").scale('F# minor')`);
add('scale.degrees.fractional', `n("0.5 1.5 2.3 -0.5 -1.5").scale('C major')`);
add('scale.degrees.accidentals.pentatonic', `n("0# 1b 2## 3bb 4# -1b").scale('A4 minor pentatonic')`);
add('scale.degrees.plain', `"0 1 2 3".scale('C:major')`);
add('scale.degrees.plain.2', `"0 1 2 3".scale('Ab4 dorian')`);
add('scale.notes.1', `note("c e g a# bb f#").scale('C major')`);
add('scale.notes.2', `note("c4 e4 g#5 cb3 b#2").scale('Eb minor')`);
add('scale.notes.3', `note("c d e f g a b").scale('C pentatonic')`);
add('scale.notes.4', `note("c d e f g a b").scale('Eb:minor:pentatonic')`);
add('scale.notes.5', `note("<c4 g4 e5>").scale('A# minor')`);
add('scale.notes.6', `note("g1 a9 c-1 cb0 e##3").scale('C chromatic')`);
add('scale.notes.7', `"c e g".scale('D whole tone')`);
add('scale.notes.8', `note("c3 e3 g3").scale('bogus tonic')`);
add('scale.pattern.1', `n("0 1 2 3").scale("<C:major D:minor>")`);
add('scale.pattern.2', `n("0 1 2 3").scale("C:<major minor>/2")`, 4);
add('scale.pattern.3', `n("0 1 2 3").scale(cat('C minor','F dorian'))`, 2);
add('scale.pattern.4', `n("<0 2 4>*2").scale(slowcat('C major', 'D minor'))`, 2);
add('scale.pattern.5', `n("0 1 2 3").scale("C:major D:dorian")`);
add('scale.pattern.6', `n("0 1 2 3").scale(['C', 'major'].join(' '))`);
add('scale.objects.1', `n("0 1 2").s("piano").gain(0.5).scale('C:major')`);
add('scale.objects.2', `s("piano").n("0 1 2").scale('C:major')`);
add('scale.objects.3', `s("piano").note("c e g").scale('C:major')`);
add('scale.objects.4', `n("0 1 2").color("red").scale('C:major').note()`);
add('scale.anchor.1', `n("0 1 2 3").anchor("c4").scale('C major')`);
add('scale.anchor.2', `n("0 .. 3").anchor("<c4 g4>").scale("C:major")`, 2);
add('scale.anchor.3', `n("0 1 2 3").anchor(66).scale("<C:major F:minor>")`, 2);
add('scale.anchor.4', `n("0 1#").anchor("e3").scale("D:major")`);
add('scale.anchor.5', `n("0 1 2").anchor("c4").scale('C major').s("piano")`);
add('scale.anchor.6', `n("0 1 2").anchor("c4").scale('major')`);
add('scale.value', `n({value: 3}).scale('C major')`);
add('scale.value.2', `pure({value: 3, s: 'x'}).scale('C major')`);
add('scale.noValue', `s("bd hh").scale('C major')`);
add('scale.steps', `n("0 1 2 3").scale('C major').pace(8)`);
add('scale.errors.step', `n("0 1 2").scale('C bogus')`);
add('scale.errors.incomplete', `n("0 1 2").scale('C')`);
add('scale.errors.badStep', `n("0 x 2").scale('C major')`);
add('scale.errors.quantize', `note("c e").scale('C bogus')`);
add('scale.errors.quantizeIncomplete', `note("c e").scale('C')`);
add('scale.errors.number', `n("0 1").scale(3)`);

// ---- transpose and its synonyms --------------------------------------------------------------------------------------------------

const { Note, Interval } = await import(pathToFileURL(path.join(here, 'strudel/packages/tonal/node_modules/@tonaljs/tonal/dist/index.mjs')).href);

// A note without octave that tonal transposes out of octave 3 is remembered by tonal (its cached note gets the octave 3), and the same
// note staying in octave 3 later is then written with the octave. The port does not copy that, so no snippet may do it: `octaveLess`
// checks every use of a spelling against the ones before it, in the order the oracle sees them (snippet by snippet, cycle by cycle).
const crossed = new Set();
const octaveLess = (name, uses, code, cycles = 1) => {
  for (const [spelling, amount] of uses) {
    const interval = !Number.isNaN(Number(amount)) ? Interval.fromSemitones(Number(amount)) : String(amount);
    const crossing = Note.get(Note.transpose(`${spelling}3`, interval)).oct !== 3;
    if (!crossing && crossed.has(spelling)) throw new Error(`${name}: ${spelling} stays in octave 3 after it left it`);
    if (crossing) crossed.add(spelling);
  }
  add(name, code, cycles);
};

add('transpose.numbers.1', `note("48 52 55").transpose(7)`);
add('transpose.numbers.2', `note("48 52 55").transpose("<0 -12 5>")`, 3);
add('transpose.numbers.3', `note("48 52 55").transpose("1P 2M 3m")`);
add('transpose.numbers.4', `"48 52".transpose(2).note()`);
add('transpose.numbers.5', `n("0 1 2").note().transpose(2)`);
add('transpose.numbers.6', `note("48 52 55").transpose("bogus")`);
add('transpose.numbers.7', `note("48 52 55").transpose(1.5)`);
add('transpose.numbers.8', `note("48.5 52").transpose(-3)`);
add('transpose.strings.1', `note("c2 e3 g4").transpose(7)`);
add('transpose.strings.2', `note("c2 e3 g4").transpose("<0 2 5>")`, 3);
add('transpose.strings.3', `note("c2 e3 g4").transpose("<1P 2M 3m 4A 5d 8P -2M>")`, 7);
add('transpose.strings.4', `note("c#4 db4 f##2 gbb3").transpose("2M")`);
add('transpose.strings.5', `note("b3 b#3 cb4").transpose(1)`);
add('transpose.strings.6', `"c2 c3".fast(2).transpose("<0 -2 5 3>".slow(2)).note()`, 2);
add('transpose.strings.7', `note("c4 e4 g4").transpose("bogus")`);
add('transpose.strings.8', `note("c4 e4 g4").transpose(12.5)`);
add('transpose.strings.9', `note("c4 e4 g4").transpose("-8P")`);
add('transpose.strings.10', `note("c-1 g9").transpose(5)`);
add('transpose.strings.11', `note("c4 e4").transpose("20M")`);
add('transpose.strings.12', `note("c4 e4").transpose('')`);
octaveLess('transpose.octaveLess.1', [['c', 1], ['d', 1]], `note("c d").transpose(1)`);
octaveLess('transpose.octaveLess.2', [['e', 12], ['f', 12]], `note("e f").transpose(12)`);
octaveLess('transpose.octaveLess.3', [['g', '2M'], ['a', '2M']], `note("g a").transpose("2M")`);
octaveLess('transpose.octaveLess.4', [['d', -1], ['gb', -1], ['d', 2], ['gb', 2], ['d', -2], ['gb', -2]], `note("d gb").transpose("<-1 2 -2>")`, 3);
octaveLess('transpose.octaveLess.5', [['ab', '1P'], ['bb', '2M'], ['c#', '3m']], `note("ab bb c#").transpose("1P 2M 3m")`);
octaveLess('transpose.octaveLess.6', ['eb', 'f#', 'g#'].flatMap((n) => [0, 5, 7, 12].map((a) => [n, a])), `note("eb f# g#").transpose("<0 5 7 12>")`, 4);
octaveLess('transpose.octaveLess.7', [['a#', -9], ['cb', -9]], `note("a# cb").transpose("-9")`);
octaveLess('transpose.octaveLess.8', ['b', 'bb'].flatMap((n) => [-12, 7, 13].map((a) => [n, a])), `note("b bb").transpose("<-12 7 13>")`, 3);
add('transpose.objects.1', `s("bd hh").transpose(2)`);
add('transpose.objects.2', `n("0 1").transpose(2)`);
add('transpose.objects.3', `note("c4 e4").s("piano").transpose(2)`);
add('transpose.objects.4', `note("48 52").s("piano").transpose("<2 3>")`, 2);
add('transpose.objects.5', `note("c4 e4").gain(0.4).s("piano").transpose("3m")`);
add('transpose.notNotes.1', `"hello world".transpose(2)`);
add('transpose.notNotes.2', `pure(true).transpose(2)`);
add('transpose.notNotes.3', `note("c4 x").transpose(2)`);
add('transpose.notNotes.4', `note("cs4 e4").transpose(2)`);
add('transpose.global.1', `note("c3 e3").superimpose(transpose(7))`);
add('transpose.global.2', `note("c3 e3").layer(transpose("<7 12>"), x => x)`, 2);
add('trans.plain', `note("c3 e3 g3").trans(7)`);
add('trans.pattern', `note("c3 e3 g3").trans("<0 2 5>")`, 3);
add('trans.interval', `note("c3 e3 g3").trans("2M")`);
add('trans.global', `note("c3 e3").superimpose(trans("<7 5>"))`, 2);

// ---- scaleTranspose ---------------------------------------------------------------------------------------------------------------

add('scaleTranspose.plain.1', `n("0 2 4").scale('C:major').scaleTranspose(1)`);
add('scaleTranspose.plain.2', `n("0 2 4").scale('C:major').scaleTranspose(-1)`);
add('scaleTranspose.plain.3', `n("0 2 4").scale('C:major').scaleTranspose(7)`);
add('scaleTranspose.plain.4', `n("0 2 4").scale('C:major').scaleTranspose(-8)`);
add('scaleTranspose.plain.5', `n("0 2 4").scale('F# minor').scaleTranspose(3)`);
add('scaleTranspose.plain.6', `n("0 2 4").scale('Eb dorian').scaleTranspose("2")`);
add('scaleTranspose.plain.7', `note("c3 e3 g3").scale('C major').scaleTranspose(1)`);
add('scaleTranspose.plain.8', `note("c3 e3 g3").scale('C pentatonic').scaleTranspose(2)`);
add('scaleTranspose.plain.9', `n("0 2 4").scale('C chromatic').scaleTranspose(13)`);
add('scaleTranspose.plain.10', `n("0 2 4").scale('Cb2 major').scaleTranspose(3)`);
add('scaleTranspose.plain.11', `n("0 2 4").scale('C major').scaleTranspose(1.5)`);
add('scaleTranspose.plain.12', `n("0 2 4").scale('C major').scaleTranspose(0)`);
add('scaleTranspose.pattern.1', `n("0 2 4").scale('C:major').scaleTranspose("<0 1 2 -1>")`, 4);
add('scaleTranspose.pattern.2', `n("0 2 4").scale('C:major').scaleTranspose("0 2 4")`);
add('scaleTranspose.pattern.3', `n("0 2 4").scale("<C:major D:minor>").scaleTranspose("<1 2>")`, 4);
add('scaleTranspose.pattern.4', `n("0 2 4").scale('C:major').scaleTranspose("0 [1,2]")`);
add('scaleTranspose.objects', `n("0 2 4").s("piano").scale('C:major').scaleTranspose(2)`);
add('scaleTranspose.global', `n("0 2 4").scale('C:major').superimpose(scaleTranspose(2))`);
add('scaleTranspose.global.2', `n("0 2 4").scale('C:major').layer(scaleTranspose("<0 2>"), scaleTranspose(4))`, 2);
add('scaleTranspose.chain', `n("0 2 4").scale('C:major').scaleTranspose(1).transpose(12)`);
add('scaleTranspose.errors.noScale', `note("c3 e3").scaleTranspose(1)`);
add('scaleTranspose.errors.notInScale', `note("c#3").scale('C major').transpose(1).scaleTranspose(1)`);
add('scaleTranspose.errors.notNote', `n("0 2").scale('C major').fmap(x => 5).scaleTranspose(1)`);
add('scaleTranspose.errors.badOffset', `n("0 2").scale('C major').scaleTranspose("x")`);
add('scaleTranspose.anchored', `n("0 2").anchor("c4").scale('C major').scaleTranspose(1)`);
add('scaleTrans.plain', `n("0 2 4").scale('C:major').scaleTrans(1)`);
add('scaleTrans.pattern', `n("0 2 4").scale('C:major').scaleTrans("<0 1 2>")`, 3);
add('scaleTrans.global', `n("0 2 4").scale('C:major').superimpose(scaleTrans(2))`);
add('strans.plain', `n("0 2 4").scale('C:major').strans(1)`);
add('strans.pattern', `n("0 2 4").scale('C:major').strans("<0 -1 2>")`, 3);
add('strans.global', `n("0 2 4").scale('C:major').superimpose(strans("<2 4>"))`, 2);

// ---- voicing ---------------------------------------------------------------------------------------------------------------------------

const roots = 'C C# Db D D# Eb E F F# Gb G G# Ab A A# Bb B'.split(' ');
const dictionaries = ['ireal', 'ireal-ext', 'lefthand', 'guidetones', 'triads', 'legacy'];
for (const dict of dictionaries) {
  add(`voicing.roots.${dict}`, `resetVoicings(); chord("${roots.join(' ')}").dict('${dict}').voicing()`);
  add(`voicing.sevenths.${dict}`, `resetVoicings(); chord("C^7 Dm7 E7 Fm7b5 Go7 A-7 Bb^7 Cm6").dict('${dict}').voicing()`);
  // symbols the notation cannot hold (`C+`) are written as strings
  add(`voicing.symbols.${dict}`, `resetVoicings(); chord(seq('C2', 'C5', 'C6', 'C7', 'C9', 'C11', 'C13', 'C69', 'Cadd9', 'C+', 'Co', 'Ch', 'Csus', 'C^', 'C-', 'C^9', 'C-9', 'C-11', 'C7sus', 'Cm', 'Caug', 'CM', 'C')).dict('${dict}').voicing()`);
}
add('voicing.aliases.1', `resetVoicings(); chord(seq('Cm7', 'C-7', 'CM7', 'C^7', 'Caug', 'C+', 'C', 'Cmaj', 'Cmin7', 'Cm^7', 'C-^7', 'C-M7', 'CmM7', 'C7b9', 'C7#9', 'C7alt')).voicing()`);
add('voicing.default', `resetVoicings(); chord("<C^7 Am7 Dm7 G7>").voicing()`, 4);
add('voicing.plainStrings', `resetVoicings(); "<C^7 Am7 Dm7 G7>".voicing().note()`, 4);
add('voicing.plainStrings.2', `resetVoicings(); "<C^7 Am7>".voicing()`, 2);
add('voicing.pattern.chords', `resetVoicings(); chord("<C Am F G>").voicing().s("piano")`, 4);
add('voicing.slash.1', `resetVoicings(); chord('C/E').voicing()`);
add('voicing.slash.2', `resetVoicings(); chord('Bb7b9/F').voicing()`);
add('voicing.anchor.numbers', `resetVoicings(); chord("C^7").anchor("<48 60 66 72>").voicing()`, 4);
add('voicing.anchor.names', `resetVoicings(); chord("Am7").anchor("<c3 c4 g4 e5 Bb4>").voicing()`, 5);
add('voicing.anchor.default', `resetVoicings(); chord("Am7 G7 C^7").voicing()`);
add('voicing.mode.all', `resetVoicings(); chord("Am7").anchor("c5").mode("<below above duck root oldabove oldroot>").voicing()`, 6);
add('voicing.mode.all.2', `resetVoicings(); chord("C").anchor("g4").mode("<below above duck root oldabove oldroot>").voicing()`, 6);
add('voicing.mode.lefthand', `resetVoicings(); chord("C^7 A7").dict('lefthand').anchor("e4").mode("<below above duck root>").voicing()`, 4);
add('voicing.mode.unknown', `resetVoicings(); chord("C^7").mode("bogus").voicing()`);
add('voicing.offset', `resetVoicings(); chord("C^7").offset("<0 1 2 3 4 -1 -2>").voicing()`, 7);
add('voicing.offset.2', `resetVoicings(); chord("<C Am>").dict('triads').offset("<0 1 2 3>").voicing()`, 4);
add('voicing.offset.fractional', `resetVoicings(); chord("C^7").offset(1.5).voicing()`);
add('voicing.n', `resetVoicings(); n("0 1 2 3").chord("<C Am>").voicing()`, 2);
add('voicing.n.2', `resetVoicings(); n("0 1 2 3 4 5 -1 -2").chord("C^7").anchor("g4").voicing()`);
add('voicing.n.3', `resetVoicings(); n("<0 1 2 3>*4").chord("Am7").dict('lefthand').anchor("g4").voicing().s("piano")`);
add('voicing.n.fractional', `resetVoicings(); n("0.5 1").chord("C").voicing()`);
add('voicing.octaves', `resetVoicings(); n("0 1 2 3 4 5").chord("C").octaves("<1 2 4>").voicing()`, 3);
add('voicing.octaves.2', `resetVoicings(); chord("<Am C>").octaves("<2 4>").voicing()`, 4);
add('voicing.dict.names', `resetVoicings(); chord("<C^7 A7>").dict("<lefthand ireal triads>").voicing()`, 3);
add('voicing.dict.unknown', `resetVoicings(); chord("C^7").dict("bogus").voicing()`);
add('voicing.dict.default.legacy', `setDefaultVoicings('legacy'); chord("<C Am F G>").voicing()`, 4);
add('voicing.dict.default.lefthand', `setDefaultVoicings('lefthand'); chord("<C^7 A7>").voicing()`, 2);
add('voicing.dict.default.back', `resetVoicings(); chord("<C^7 A7>").voicing()`, 2);
add('voicing.unknownChord', `resetVoicings(); chord("<C Q C^7 H>").voicing()`, 4);
add('voicing.lowercase', `resetVoicings(); chord("c am").voicing()`);
add('voicing.rest', `resetVoicings(); chord("C ~ Am -").voicing()`);
add('voicing.extra.controls', `resetVoicings(); chord("<C Am>").voicing().s("piano").gain(0.8).room(0.5)`, 2);
add('voicing.extra.controls.2', `resetVoicings(); s("piano").chord("<C Am>").gain(0.8).voicing()`, 2);
add('voicing.noChord', `resetVoicings(); s("bd").voicing()`);
add('voicing.number', `resetVoicings(); pure(5).voicing()`);
add('voicing.structure', `resetVoicings(); chord("<C Am>").struct("x*4").voicing()`, 2);
add('voicing.slow', `resetVoicings(); chord("<C^7 Am7>/2").voicing()`, 4);
add('voicing.tune.1', `resetVoicings(); chord("<C^7 Am7 Dm7 G7>*2").dict('lefthand').anchor("G4").voicing()`, 2);
add('voicing.tune.2', `resetVoicings(); chord("<C^7 A7 Dm7 Fm7>/2").dict('lefthand').voicing()`, 4);
add('voicing.house', `resetVoicings(); addVoicings('snippetHouse', {'': ['7 12 16', '0 7 16', '4 7 12'], 'm': ['0 3 7']}); chord("<Am C D F>").dict('snippetHouse').anchor(66).voicing()`, 4);
add('voicing.inlineDictionary', `resetVoicings(); chord("<Am C>").dict({'': ['0 4 7'], 'm': ['0 3 7']}).anchor(60).voicing()`, 2);
add('voicing.arrayDictionary', `resetVoicings(); chord("<Am C>").dict({'': [[0, 4, 7]], 'm': [[0, 3, 7], [3, 7, 12]]}).anchor(60).voicing()`, 2);

// ---- voicings (deprecated) and the registry --------------------------------------------------------------------------------------------

const first = 'resetVoicings(); setVoicingRange(\'lefthand\', [\'F3\', \'A4\']);';
for (const dict of dictionaries) {
  add(`voicings.${dict}`, `${first} "<C^7 A7 Dm7 G7>".voicings('${dict}').note()`, 4);
  add(`voicings.${dict}.2`, `${first} "<C Am F G7 Em7 Bb^7>".voicings('${dict}').note()`, 6);
}
add('voicings.stack', `${first} stack("<C^7 A7 Dm7 G7>".voicings('lefthand'), "<C3 A2 D3 G2>").note()`, 4);
add('voicings.twoCalls', `${first} stack("<C^7 A7>".voicings('lefthand'), "<Dm7 G7>".voicings('guidetones')).note()`, 2);
add('voicings.pattern.dictionary', `${first} "<C^7 A7>".voicings("<lefthand guidetones>").note()`, 2);
add('voicings.unknownDictionary', `${first} "C^7".voicings('bogus').note()`);
add('voicings.unknownChord', `${first} "<C^7 Q A7>".voicings('lefthand').note()`, 3);
add('voicings.rest', `${first} "C^7 ~ A7".voicings('lefthand').note()`);
add('voicings.chordControl', `${first} chord("<C^7 A7>").voicings('lefthand').note()`, 2);
add('addVoicings.range', `${first} addVoicings('snippetCookie', {7: ['3M 7m 9M 12P 15P', '7m 10M 13M 16M 19P'], '^7': ['3M 6M 9M 12P 14M', '7M 10M 13M 16M 19P']}, ['C3', 'C6']); "<C^7 A7 C^7 A7>".voicings('snippetCookie').note()`, 4);
add('addVoicings.defaultRange', `${first} addVoicings('snippetPlain', {'': ['1P 3M 5P', '3M 5P 8P', '5P 8P 10M']}); "<C F G>".voicings('snippetPlain').note()`, 3);
add('addVoicings.voicing', `resetVoicings(); addVoicings('snippetVoicing', {'': ['1P 3M 5P', '3M 5P 8P', '5P 8P 10M']}); chord("<C F G>").dict('snippetVoicing').anchor("g4").voicing()`, 3);
add('registerVoicings.plain', `${first} registerVoicings('snippetRegistered', {'': ['1P 3M 5P', '3M 5P 8P']}); "<C F>".voicings('snippetRegistered').note()`, 2);
add('registerVoicings.options', `${first} registerVoicings('snippetRegistered2', {'': ['1P 3M 5P', '3M 5P 8P', '5P 8P 10M']}, {range: ['C4', 'G5'], mode: 'above', anchor: 'c4'}); "<C F G>".voicings('snippetRegistered2').note()`, 3);
add('registerVoicings.pattern', `resetVoicings(); registerVoicings('snippetRegistered3', {'m': ['0 3 7'], '': ['0 4 7']}); chord("<Am C>").dict("<snippetRegistered3 triads>").anchor(60).voicing()`, 2);
add('registerVoicings.voicingIgnoresMode', `resetVoicings(); registerVoicings('snippetRegistered4', {'': ['1P 3M 5P', '3M 5P 8P', '5P 8P 10M']}, {mode: 'above', anchor: 'e5'}); chord("<C F>").dict('snippetRegistered4').voicing()`, 2);
add('setVoicingRange.plain', `resetVoicings(); setVoicingRange('lefthand', ['C3', 'C5']); "<C^7 A7 Dm7 G7>".voicings('lefthand').note()`, 4);
add('setVoicingRange.pattern', `resetVoicings(); setVoicingRange('guidetones', ['C2', 'G3']); "<C^7 A7 Dm7 G7>".voicings("<guidetones lefthand>").note()`, 4);
add('setVoicingRange.restore', `${first} "<C^7 A7 Dm7 G7>".voicings('lefthand').note()`, 4);
add('setVoicingRange.unknown', `resetVoicings(); setVoicingRange('snippetUnknown', ['C3', 'C5']); s("bd")`);
add('setVoicingRange.keepsDictionary', `resetVoicings(); setVoicingRange('triads', ['C2', 'C3']); chord("<C Am>").dict('triads').voicing()`, 2);
add('setDefaultVoicings.plain', `setDefaultVoicings('triads'); chord("<C Am F G>").voicing()`, 4);
add('setDefaultVoicings.pattern', `setDefaultVoicings('guidetones'); chord("<C^7 Am7>").voicing().s("piano")`, 2);
add('setDefaultVoicings.ireal', `setDefaultVoicings('ireal-ext'); chord("<C^7 Am7>").voicing()`, 2);
add('setDefaultVoicings.unknown', `setDefaultVoicings('snippetNowhere'); chord("<C^7 Am7>").voicing()`, 2);
add('setDefaultVoicings.result', `n(setDefaultVoicings('legacy').length)`);
add('setDefaultVoicings.reset', `setDefaultVoicings('lefthand'); resetVoicings(); chord("<C^7 Am7>").voicing()`, 2);
add('resetVoicings.plain', `resetVoicings(); "<C^7 A7>".voicings('lefthand').note()`, 2);
add('resetVoicings.pattern', `resetVoicings(); stack("<C^7 A7>".voicings('lefthand'), "<Dm7 G7>".voicings('guidetones')).note()`, 2);
add('resetVoicings.defaultAgain', `setDefaultVoicings('triads'); resetVoicings(); chord("<C Am>").voicing()`, 2);
add('resetVoicings.result', `n(typeof resetVoicings())`);
add('voicingAlias.plain', `resetVoicings(); const d = {m7: ['3m 5P 7m 9M', '7m 9M 10m 12P']}; voicingAlias('m7', 'min7', d); registerVoicings('snippetAlias', d); chord("<Dmin7 Dm7>").dict('snippetAlias').voicing()`, 2);
add('voicingAlias.sets', `resetVoicings(); const a = {'^7': ['3M 5P 7M 9M']}; const b = {'^7': ['3M 7M 10M']}; voicingAlias('^7', 'maj7', [a, b]); registerVoicings('snippetAliasA', a); registerVoicings('snippetAliasB', b); stack(chord("Cmaj7").dict('snippetAliasA').voicing(), chord("Cmaj7").dict('snippetAliasB').voicing())`);
add('voicingAlias.pattern', `resetVoicings(); const d = {m: ['0 3 7'], '': ['0 4 7']}; voicingAlias('m', 'min', d); registerVoicings('snippetAlias2', d); chord("<Amin Am A>").dict('snippetAlias2').anchor(66).voicing()`, 3);
add('voicingAlias.missingSymbol', `resetVoicings(); const d = {m: ['0 3 7']}; voicingAlias('nothing', 'x', d); registerVoicings('snippetAlias3', d); chord("<Am Ax>").dict('snippetAlias3').anchor(66).voicing()`, 2);
add('voicingAlias.registry', `resetVoicings(); voicingAlias('^7', 'snippetMaj7', voicingRegistry.ireal.dictionary); chord("<CsnippetMaj7 C^7>").voicing()`, 2);
add('voicingRegistry.names', `s("bd").n(voicingRegistry.lefthand.range.length)`);
add('voicingRegistry.mode', `note(voicingRegistry.guidetones.mode)`);
add('voicingRegistry.lefthandAnchor', `note(voicingRegistry.lefthand.anchor)`);
add('voicingRegistry.rangeText', `s(voicingRegistry.lefthand.range.join('-'))`);
add('voicingRegistry.symbol', `s("bd").n(Object.keys(voicingRegistry.triads.dictionary).length)`);
add('voicingRegistry.assign', `resetVoicings(); voicingRegistry.snippetAssigned = {dictionary: {'': ['0 4 7'], 'm': ['0 3 7']}}; chord("<Am C>").dict('snippetAssigned').anchor(60).voicing()`, 2);
add('voicingRegistry.assignRange', `resetVoicings(); voicingRegistry.snippetAssigned2 = {dictionary: {'': ['1P 3M 5P', '3M 5P 8P']}, range: ['C4', 'G4']}; "<C F>".voicings('snippetAssigned2').note()`, 2);
add('voicingRegistry.lookup', `s("bd").n(Object.keys(voicingRegistry).includes('ireal') ? 1 : 0)`);

// ---- rootNotes ----------------------------------------------------------------------------------------------------------------------

add('rootNotes.plain', `"<C^7 A7 Dm7 G7>".rootNotes(2).note()`, 4);
add('rootNotes.pattern', `"<C^7 A7 Dm7 G7>".rootNotes("<2 3>").note()`, 4);
add('rootNotes.pattern.2', `"C^7 A7 Dm7 G7".rootNotes("2 3").note()`);
add('rootNotes.accidentals', `"C#m7 Bb^7 Ebm7b5 F#o7 Gb7 Abm".rootNotes(2).note()`);
add('rootNotes.lowercase', `"c am".rootNotes(3).note()`);
add('rootNotes.chordControl', `chord("<C^7 A7>").rootNotes(2)`, 2);
add('rootNotes.chordControl.2', `chord("<C^7 A7>").s("piano").rootNotes(2).note()`, 2);
add('rootNotes.chordControl.3', `chord("<C^7 A7>").rootNotes("<2 4>")`, 2);
add('rootNotes.global', `chord("<C^7 A7>").superimpose(rootNotes(2)).note()`, 2);
add('rootNotes.fractional', `"C^7".rootNotes(2.5).note()`);
add('rootNotes.negative', `"C^7".rootNotes(-1).note()`);
add('rootNotes.stack', `stack("<C^7 A7>".rootNotes(2), "<C^7 A7>".rootNotes(4)).note()`, 2);
add('rootNotes.notChord', `"hello".rootNotes(2).note()`);
add('rootNotes.number', `pure(5).rootNotes(2)`);

// ---- arp and arpWith ------------------------------------------------------------------------------------------------------------------------

const chords = `note("<[c,eb,g]!2 [c,f,ab] [d,f,ab]>")`;
add('arp.plain', `${chords}.arp("0 [0,2] 1 [0,2]")`, 4);
add('arp.pattern', `${chords}.arp("<0 1 2>")`, 4);
add('arp.pattern.2', `${chords}.arp("<[0 1] [2 1]>*2")`, 4);
add('arp.numbers', `${chords}.arp(1)`, 4);
add('arp.negative', `${chords}.arp("-1 -2 -3")`, 4);
add('arp.large', `${chords}.arp("5 7 9 10")`, 4);
add('arp.seq', `${chords}.arp("0 1 2").fast(2)`, 4);
add('arp.stack', `stack(${chords}.arp("0 1 2"), note("c2 g2"))`, 2);
add('arp.single', `note("c e g").arp("0 1 2")`);
add('arp.three', `note("[c,e,g] [d,f,a]").arp("0 1 2")`);
add('arp.fractional', `${chords}.arp("0.5 1.5")`, 2);
add('arp.controls', `${chords}.s("piano").gain(0.5).arp("0 1 2")`, 2);
add('arp.controls.2', `note("<[c,eb,g]!2 [c,f,ab]>").s("<piano sax>").arp("0 1 2")`, 3);
add('arp.silence', `${chords}.arp("~ 0")`, 2);
add('arp.notNumbers', `${chords}.arp("a b")`, 2);
add('arp.chordControl', `chord("<Am C>").arp("0 1 2")`, 2);
add('arp.voicing', `resetVoicings(); chord("<C^7 A7>").voicing().arp("0 1 2 3")`, 2);
add('arp.polyrhythm', `note("[c,e,g]*2 [d,f,a]").arp("0 1")`, 2);
add('arp.arpOfArp', `${chords}.arp("0 1 2").arp("0 1")`, 2);
add('arpWith.plain', `${chords}.arpWith(haps => haps[2])`, 4);
add('arpWith.first', `${chords}.arpWith(haps => haps[0])`, 4);
add('arpWith.last', `${chords}.arpWith(haps => haps[haps.length - 1])`, 4);
add('arpWith.sequence', `${chords}.arpWith(haps => sequence(...haps))`, 4);
add('arpWith.reverse', `${chords}.arpWith(haps => sequence(...haps.slice().reverse()))`, 4);
add('arpWith.stack', `${chords}.arpWith(haps => stack(haps[0], haps[2]))`, 4);
add('arpWith.fastcat', `${chords}.arpWith(haps => fastcat(...haps.slice(0, 2)))`, 2);
add('arpWith.pure', `${chords}.arpWith(haps => pure(haps[1]))`, 2);
add('arpWith.pattern', `${chords}.arpWith(haps => reify("0 1").fmap(i => haps[i]))`, 2);
add('arpWith.controls', `note("<[c,eb,g]!2 [c,f,ab]>").s("piano").arpWith(haps => haps[1])`, 3);
add('arpWith.single', `note("c e g").arpWith(haps => haps[0])`);
add('arpWith.undefined', `${chords}.arpWith(haps => haps[7])`, 2);
add('arpWith.number', `${chords}.arpWith(haps => 3)`, 2);
add('arpWith.methodChain', `${chords}.arpWith(haps => haps[2]).fast(2)`, 2);
add('arpWith.struct', `${chords}.arpWith(haps => haps[1]).struct("x*2")`, 2);
add('arpWith.chordControl', `chord("<Am C>").voicing().arpWith(haps => haps[0])`, 2);

fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, JSON.stringify(snippets, null, 1) + '\n');
console.log(`${snippets.length} snippets (${typeNames.length} scale names, ${miniNames.length} of them as mini-notation)`);
