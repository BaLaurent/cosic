// Golden vectors for the tonal functions of the engine (engine/src/main/kotlin/cosic/pattern/{Tonal,Voicings}.kt): what the real
// @tonaljs libraries and Strudel's tonal package answer, for the inputs the unit tests (TonalGoldenTest) feed the Kotlin port.
//
//   node tools/strudel-ref/tonal-oracle.mjs
//
// Output engine/src/test/resources/tonal/golden.json:
//   notes, intervals          Note.get / Interval.get of names, valid and not
//   transpose                 Note.transpose(note, interval) of every pair of a set of notes (with octave) and intervals
//   transposeNoOctave         the same for notes without octave, in a fresh state each (the oracle's tonal.mjs changes the
//                             cached note it transposes, so these are computed one interval per process, first call only)
//   intervalAdd, intervalSubstract, fromSemitones, semitones, enharmonic, chromaMidi, ranges
//   scaleTokenize, scaleGet   Scale.tokenize / Scale.get of scale names
//   scaleHaps                 what `pure(value).scale(name)` gives (tonal.mjs), values as written
//   scaleTranspose            what `pure(note).scale(name).scaleTranspose(offset)` gives
//   transposeHaps             what `pure(value).transpose(amount)` gives, first call per octave-less note per process
//   renderVoicing             tonleiter's renderVoicing of chords, anchors, modes and offsets
//   voicingsInRange           chord-voicings' voicingsInRange and the picks of minTopNoteDiff
//   stepInNamedScale, scaleStep, nearestNumberIndex   tonleiter helpers
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.COSIC_ROOT ?? path.resolve(here, '../..');
const tonalDir = path.join(here, 'strudel/packages/tonal');
const coreDir = path.join(here, 'strudel/packages/core');
const output = path.join(root, 'engine/src/test/resources/tonal/golden.json');
const importFile = (file) => import(pathToFileURL(file).href);

// ---- the child process of the octave-less transposition cases --------------------------------------------------------------

if (process.argv[2] === '--transpose-no-octave') {
  const { pure } = await importFile(path.join(coreDir, 'index.mjs'));
  await importFile(path.join(tonalDir, 'index.mjs'));
  const { notes, amount } = JSON.parse(process.argv[3]);
  const out = {};
  for (const note of notes) {
    try {
      out[note] = pure(note).transpose(amount).firstCycle()[0].value;
    } catch (err) {
      out[note] = { error: String(err.message) };
    }
  }
  process.stdout.write('\nCHILD_JSON:' + JSON.stringify(out) + '\n');
  process.exit(0);
}

const log = console.log;
console.log = () => {}; // the packages announce themselves
const core = await importFile(path.join(coreDir, 'index.mjs'));
const tonalLib = await importFile(path.join(tonalDir, 'node_modules/@tonaljs/tonal/dist/index.mjs'));
const tonal = await importFile(path.join(tonalDir, 'index.mjs'));
const tonleiter = await importFile(path.join(tonalDir, 'tonleiter.mjs'));
const requireFromTonal = createRequire(path.join(tonalDir, 'package.json'));
const chordVoicingsModule = requireFromTonal('chord-voicings');
const chordVoicings = chordVoicingsModule.default ?? chordVoicingsModule; // as voicings.mjs reads it
console.log = log;
const { Note, Interval, Scale, Range } = tonalLib;
const { pure } = core;

// ---- JSON: what JSON cannot say is written as a string -----------------------------------------------------------------------

const plain = (value) => {
  if (value === undefined) return null;
  if (typeof value === 'number') return Number.isFinite(value) ? value : String(value);
  if (Array.isArray(value)) return value.map(plain);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, plain(v)]));
  return value;
};
const num = (x) => plain(x);
const safely = (fn) => {
  try {
    return fn();
  } catch (err) {
    return { error: String(err?.message ?? err) };
  }
};

// ---- names ---------------------------------------------------------------------------------------------------------------------

const noteNames = [
  'c', 'C', 'c4', 'C#4', 'Db', 'eb3', 'F##2', 'Gbb', 'bb', 'b', 'b3', 'bb3', 'bbb', 'B#', 'e#', 'fb', 'cb', 'C-1', 'c-2', 'c10',
  'a0', 'g9', 'g-5', 'x', '', ' ', 'cs3', 'fs', 'Ab', 'Abb4', 'A##', 'ax4', 'cx', 'Cx', 'c#x', 'c 4', 'c4 ', 'c4x', 'c04',
  'c004', 'c-', 'c--1', '1c', 'h', 'H3', 'c3.5', 'C100', 'A19', 'B#-1', 'Cb0', 'e##', 'f#', 'F#3', 'g#4', 'Db2', 'bf3', 'c3 major',
  'C\n', 'a', 'A', 'd', 'E', 'g', 'G7', 'bx', 'bxx', 'B##9',
];
const intervalNames = [
  '1P', '2m', '2M', '3m', '3M', '4P', '4A', '5d', '5P', '5A', '6m', '6M', '7m', '7M', '8P', '9m', '9M', '10m', '10M', '11P', '12P',
  '13m', '13M', '14m', '15P', '16M', '19P', '22P', '-1P', '-2M', '-3m', '-8P', '-15P', '2A', '2AA', '4AA', '4AAA', '5dd', '5ddd',
  '3d', '3dd', '2d', '1d', '1A', '8d', '1m', '4m', '3P', '7P', '0P', '0M', 'M3', 'm3', 'P5', 'd5', 'A4', 'AA4', 'dd7', 'P-5',
  'M+3', '+3M', '3M foo', 'foo 3M', 'xyz', '', ' ', '3', 'M', '3Mx', '1e1M', '100P', '99999999P', '-99P', '17P', '20M', '3AAAA', '3dddd',
];
const transposeNotes = ['c4', 'C#4', 'Db', 'eb3', 'F##2', 'Gbb3', 'bb3', 'b3', 'B#2', 'e#4', 'fb4', 'cb2', 'C-1', 'c-2', 'c10', 'a0', 'cs3', 'x', '', 'Ab4', 'A##3', 'ax4', 'g9', 'e3', 'f#3', 'D5'];
const transposeIntervals = intervalNames.filter((n) => n !== '3M foo');
const octaveLess = ['c', 'd', 'e', 'f', 'g', 'a', 'b', 'c#', 'db', 'eb', 'f#', 'gb', 'ab', 'a#', 'bb', 'B#', 'Cb', 'e##', 'fbb', 'cs', 'cx', 'x'];

// ---- notes and intervals -----------------------------------------------------------------------------------------------------

const noteCases = noteNames.map((src) => {
  const n = Note.get(src);
  return { src, empty: n.empty, name: n.name, pc: n.pc, acc: n.acc, oct: num(n.oct), step: num(n.step), alt: num(n.alt), chroma: num(n.chroma), height: num(n.height), midi: n.midi, freq: n.freq };
});
const intervalCases = intervalNames.map((src) => {
  const i = Interval.get(src);
  return { src, empty: i.empty, name: i.name, num: num(i.num), q: i.q ?? null, step: num(i.step), alt: num(i.alt), dir: num(i.dir), type: i.type ?? null, semitones: num(i.semitones), chroma: num(i.chroma), oct: num(i.oct), coord: i.coord ?? null };
});

const transposeCases = [];
for (const note of transposeNotes) for (const interval of transposeIntervals) transposeCases.push([note, interval, safely(() => Note.transpose(note, interval))]);

// octave-less notes: one process per amount, each note once (see the header)
const noOctaveAmounts = [0, 1, 2, 5, 7, 11, 12, 13, -1, -2, -7, -12, -13, 24, '1P', '2M', '3m', '4A', '5d', '8P', '-2M', '-8P', '15P', 'bogus'];
const transposeNoOctave = [];
for (const amount of noOctaveAmounts) {
  const raw = execFileSync(process.execPath, [fileURLToPath(import.meta.url), '--transpose-no-octave', JSON.stringify({ notes: octaveLess, amount })], { encoding: 'utf8' });
  const out = JSON.parse(raw.split('\n').find((line) => line.startsWith('CHILD_JSON:')).slice('CHILD_JSON:'.length));
  for (const note of octaveLess) transposeNoOctave.push([note, amount, plain(out[note])]);
}

const addPairs = ['1P', '3M', '5P', '8P', '9M', '3m', '7m', '-3M', '-8P', '15P', '2A', '4A', '5d', '1d', '6M', 'bogus', '', '4P', '12P', '-2m'];
const intervalAdd = [];
const intervalSubstract = [];
for (const a of addPairs) {
  for (const b of addPairs) {
    intervalAdd.push([a, b, safely(() => Interval.add(a, b)) ?? null]);
    intervalSubstract.push([a, b, safely(() => Interval.substract(a, b)) ?? null]);
  }
}
const fromSemitones = [];
for (let s = -50; s <= 50; s++) fromSemitones.push([s, Interval.fromSemitones(s)]);
for (const s of [0.5, -1.5, 1e21, 1e15, 2.25, 120, -120, 1000]) fromSemitones.push([s, String(Interval.fromSemitones(s))]);
const semitones = intervalNames.map((name) => [name, num(Interval.semitones(name))]);

const enharmonicNotes = ['C#4', 'Db4', 'D#', 'Eb', 'B#3', 'Cb4', 'E#', 'Fb', 'C', 'c', 'G##2', 'Abb', 'cs', 'x', '', 'B#-1', 'Cb0'];
const enharmonic = [];
for (const a of enharmonicNotes) for (const b of enharmonicNotes) enharmonic.push([a, b, safely(() => Note.enharmonic(a, b))]);
const chromaMidi = noteNames.map((n) => [n, num(Note.chroma(n)), Note.midi(n)]);
const rangeInputs = [
  ['F3', 'A4'], ['C3', 'C6'], ['A4', 'F3'], ['C4', 'C4'], ['C4', 'D4'], ['Db3', 'Gb3'], ['D3', 'A4'], [60, 72], ['C4', 65], ['C4'], ['C4', 'bogus'], [],
  ['0', '12'], ['c-1', 'c0'], ['B#3', 'Cb5'], ['G9', 'G9'], [0, 3],
];
const ranges = rangeInputs.map((r) => [r, safely(() => Range.chromatic(r))]);

// ---- scales ---------------------------------------------------------------------------------------------------------------------

const scaleTypes = tonalLib.ScaleType.all();
const typeNames = scaleTypes.flatMap((s) => [s.name, ...s.aliases]);
const tonics = ['C', 'Eb', 'f#4'];
const scaleNames = [
  ...typeNames.flatMap((t) => tonics.map((n) => `${n} ${t}`)),
  ...typeNames, 'Major', 'C MAJOR', 'C Major Pentatonic', 'c', 'C', 'C4', 'x major', 'major  pentatonic', ' major', 'C bogus', 'bogus', '', 'C ', ' ',
  '101010110101', 'C 101010110101', 'C 2741', 'C:major', 'Cs major', 'cb2 major', 'B# major', 'G### minor',
];
const scaleTokenize = scaleNames.map((n) => [n, Scale.tokenize(n)]);
const scaleGet = scaleNames.map((n) => {
  const s = Scale.get(n);
  return { name: n, empty: s.empty, scaleName: s.name, type: s.type, tonic: s.tonic, intervals: s.intervals, notes: s.notes };
});

// ---- the functions of tonal.mjs, through the real package ------------------------------------------------------------------------

const firstValue = (pat) => safely(() => {
  const haps = pat.firstCycle();
  return haps.map((h) => ({ value: plain(h.value), scale: h.context.scale ?? null }));
});
const withoutLogs = (fn) => {
  const saved = { error: console.error, warn: console.warn, log: console.log };
  console.error = console.warn = console.log = () => {};
  try {
    return fn();
  } finally {
    Object.assign(console, saved);
  }
};
const degrees = [-15, -8, -7, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 13, 14, 15, 0.5, 1.5, -0.5, 2.3, '0b', '1#', '-2', '3##', '4bb', '-4#', '7b', '7s', '2f', 'x', '', '1e1'];
const scaleSet = ['C major', 'C:major', 'major', 'C minor', 'Eb dorian', 'F# pentatonic', 'c4 bebop major', 'Cb2 major', 'A# minor', 'C chromatic', 'D hirajoshi', 'G1 minor pentatonic', 'C whole tone', 'C bogus', 'C', 'C ritusen', 'Bb altered'];
const scaleHaps = [];
for (const scale of scaleSet) {
  for (const degree of degrees) {
    scaleHaps.push({ scale, value: plain(degree), as: 'n', result: withoutLogs(() => firstValue(pure({ n: degree }).scale(scale))) });
    scaleHaps.push({ scale, value: plain(degree), as: 'plain', result: withoutLogs(() => firstValue(pure(degree).scale(scale))) });
  }
  for (const note of ['c', 'C3', 'c#3', 'Db4', 'e', 'f#2', 'b', 'Cb', 'B#', 'a0', 'g9', 'cs3', 'x', 'c-1', 'ab', 'E##5']) {
    scaleHaps.push({ scale, value: note, as: 'note', result: withoutLogs(() => firstValue(pure({ note }).scale(scale))) });
    scaleHaps.push({ scale, value: note, as: 'plain', result: withoutLogs(() => firstValue(pure(note).scale(scale))) });
  }
  scaleHaps.push({ scale, value: plain({ n: 2, s: 'piano', anchor: 'c4' }), as: 'anchor', result: withoutLogs(() => firstValue(pure({ n: 2, s: 'piano', anchor: 'c4' }).scale(scale))) });
  scaleHaps.push({ scale, value: plain({ n: 2, anchor: 60 }), as: 'anchor', result: withoutLogs(() => firstValue(pure({ n: 2, anchor: 60 }).scale(scale))) });
  scaleHaps.push({ scale, value: plain({ n: '3#', anchor: 'g4' }), as: 'anchor', result: withoutLogs(() => firstValue(pure({ n: '3#', anchor: 'g4' }).scale(scale))) });
  scaleHaps.push({ scale, value: plain({ n: 1, s: 'x', gain: 0.5 }), as: 'extra', result: withoutLogs(() => firstValue(pure({ n: 1, s: 'x', gain: 0.5 }).scale(scale))) });
  scaleHaps.push({ scale, value: plain({ value: 4 }), as: 'value', result: withoutLogs(() => firstValue(pure({ value: 4 }).scale(scale))) });
  scaleHaps.push({ scale, value: plain({ s: 'x' }), as: 'nothing', result: withoutLogs(() => firstValue(pure({ s: 'x' }).scale(scale))) });
}
// a list is how mini-notation gives `C:major`
for (const scale of [['C', 'major'], ['C', 'minor', 'pentatonic'], ['major'], ['Eb', 'melodic', 'minor']]) {
  for (const degree of [0, 1, 2, 7, -1]) {
    scaleHaps.push({ scale, value: degree, as: 'plain', result: withoutLogs(() => firstValue(pure(degree).scale(scale))) });
  }
}

const scaleTranspose = [];
for (const scale of ['C major', 'C:minor', 'F# pentatonic', 'c4 bebop major', 'Cb2 major', 'A# minor', 'C chromatic', 'D hirajoshi', 'C whole tone', 'Eb dorian', 'major', 'C bogus']) {
  for (const note of ['c3', 'g#3', 'bb3', 'C4', 'Cb3', 'c', 'x', 'c#3']) {
    for (const offset of [0, 1, -1, 3, 7, -8, 14, 2.5, '2', 'x']) {
      scaleTranspose.push({ scale, note, offset: plain(offset), result: withoutLogs(() => firstValue(pure(note).scale(scale).scaleTranspose(offset))) });
    }
  }
  for (const degree of [0, 3, -2, 8]) {
    for (const offset of [0, 1, -3, 4]) {
      scaleTranspose.push({ scale, note: { n: degree }, offset, result: withoutLogs(() => firstValue(pure({ n: degree, s: 'x' }).scale(scale).scaleTranspose(offset))) });
    }
  }
}
scaleTranspose.push({ scale: null, note: 'c3', offset: 1, result: withoutLogs(() => firstValue(pure('c3').scaleTranspose(1))) });
scaleTranspose.push({ scale: 'C major', note: 5, offset: 1, result: withoutLogs(() => firstValue(pure(5).scale('C major').scaleTranspose(1))) });

const transposeHaps = [];
{
  const numbers = [0, 40, 60, -3, 12.5];
  const amounts = [0, 1, -1, 7, 12, -12, 13, '1P', '2M', '3m', '-2M', '5P', '8P', 'bogus', '2', '', 1.5];
  for (const value of numbers) {
    for (const amount of amounts) {
      transposeHaps.push({ value: plain(value), amount: plain(amount), result: withoutLogs(() => firstValue(pure(value).transpose(amount))) });
      transposeHaps.push({ value: plain({ note: value, s: 'x' }), amount: plain(amount), result: withoutLogs(() => firstValue(pure({ note: value, s: 'x' }).transpose(amount))) });
    }
  }
  // notes with an octave: independent of what was transposed before
  for (const value of ['c2', 'C3', 'c#4', 'Db4', 'e3', 'bb2', 'B#3', 'Cb4', 'f##5', 'a0', 'g9', 'c-1', 'e#-1', 'x', 'cs3', 'hello', '']) {
    for (const amount of amounts) {
      transposeHaps.push({ value, amount: plain(amount), result: withoutLogs(() => firstValue(pure(value).transpose(amount))) });
      transposeHaps.push({ value: plain({ note: value, s: 'x' }), amount: plain(amount), result: withoutLogs(() => firstValue(pure({ note: value, s: 'x' }).transpose(amount))) });
    }
  }
  for (const value of [true, null, [1, 2], { n: 3 }]) {
    transposeHaps.push({ value: plain(value), amount: 1, result: withoutLogs(() => firstValue(pure(value).transpose(1))) });
  }
}

// ---- tonleiter -----------------------------------------------------------------------------------------------------------------

const dictionaries = {
  lefthand: tonal.voicingRegistry.lefthand.dictionary,
  guidetones: tonal.voicingRegistry.guidetones.dictionary,
  triads: tonal.voicingRegistry.triads.dictionary,
  legacy: tonal.voicingRegistry.legacy.dictionary,
  ireal: tonal.voicingRegistry.ireal.dictionary,
  'ireal-ext': tonal.voicingRegistry['ireal-ext'].dictionary,
  numbers: { '': ['7 12 16', '0 7 16', '4 7 12'], m: ['0 3 7'], 7: [[0, 4, 7, 10]] },
};
const chordsByDictionary = {
  lefthand: ['C^7', 'Am7', 'G7', 'Dm7b5', 'F#o7', 'A7b9', 'Bb7#11', 'C', 'H'],
  guidetones: ['C^7', 'Am7', 'G7', 'Cm6', 'D69'],
  triads: ['C', 'Am', 'F#o', 'Bbaug', 'C/E', 'Db', 'H'],
  legacy: ['C', 'Am', 'C^7', 'Em7', 'G7', 'F#m7b5'],
  ireal: ['C', 'Am', 'F', 'G7', 'Dm7', 'C^7', 'Bb^7', 'Ebm7b5', 'F#o7', 'Gm6', 'A7b9', 'C/E', 'Bb7b9/F', 'Dsus', 'E7#9', 'C#m7', 'cminor', '', 'C13', 'C69', 'B#^7', 'Cmaj7'],
  'ireal-ext': ['C', 'Am7', 'G7', 'C^7', 'Bb7alt'],
  numbers: ['C', 'Am', 'G7', 'Dm', 'F#'],
};
const anchors = ['c5', 'c4', 'g4', 'Bb4', 'a3', 60, 66.5, 'x', null];
const renderVoicing = [];
for (const dictName of Object.keys(dictionaries)) {
  for (const chord of chordsByDictionary[dictName]) {
    for (const anchor of anchors) {
      for (const mode of ['below', 'above', 'duck', 'root', 'oldabove', 'oldroot', 'bogus']) {
        const args = { chord, dictionary: dictionaries[dictName], mode, anchor };
        renderVoicing.push({ dictionary: dictName, chord, anchor, mode, result: safely(() => tonleiter.renderVoicing(args)) });
      }
    }
  }
  for (const chord of chordsByDictionary[dictName].slice(0, 3)) {
    for (const offset of [0, 1, -1, 3, 1.5]) {
      for (const n of [undefined, 0, 1, -1, 5, 1.5]) {
        for (const octaves of [undefined, 2]) {
          const args = { chord, dictionary: dictionaries[dictName], offset, n, octaves, anchor: 'g5' };
          renderVoicing.push({ dictionary: dictName, chord, anchor: 'g5', mode: 'below', offset: plain(offset), n: plain(n), octaves: plain(octaves), result: safely(() => tonleiter.renderVoicing(args)) });
        }
      }
    }
  }
}
// the tonleiter.test.mjs cases
renderVoicing.push({ dictionary: 'm7', chord: 'Em7', anchor: 'Bb4', mode: 'below', inline: { m7: ['3 7 10 14', '10 14 15 19'] }, result: tonleiter.renderVoicing({ chord: 'Em7', anchor: 'Bb4', dictionary: { m7: ['3 7 10 14', '10 14 15 19'] }, mode: 'below' }) });

const voicingsInRange = [];
const lastVoicings = [null, ['C3', 'E3', 'G3'], ['A4', 'C5'], []];
for (const dictName of ['lefthand', 'guidetones', 'triads', 'legacy', 'ireal', 'ireal-ext']) {
  for (const chord of ['C', 'Am', 'C^7', 'A7', 'Dm7', 'G7', 'F#m7b5', 'Bb^7', 'C69', 'H']) {
    for (const range of [undefined, ['F3', 'A4'], ['C3', 'C6'], ['A2', 'E3']]) {
      const found = safely(() => chordVoicings.dictionaryVoicingFinder(dictionaries[dictName])(chord, range));
      voicingsInRange.push({ dictionary: dictName, chord, range: range ?? null, result: found });
    }
  }
  for (const chord of ['C^7', 'Am7', 'G7']) {
    for (const last of lastVoicings) {
      const result = safely(() => chordVoicings.dictionaryVoicing({ chord, dictionary: dictionaries[dictName], range: ['F3', 'A4'], picker: chordVoicings.minTopNoteDiff, lastVoicing: last ?? undefined }));
      voicingsInRange.push({ dictionary: dictName, chord, range: ['F3', 'A4'], last, pick: true, result });
    }
  }
}

const stepInNamedScale = [];
for (const scale of ['C major', 'D major', 'C:major', 'major', 'A minor', 'F# hirajoshi', 'Eb dorian', 'C bogus', 'C chromatic']) {
  for (const step of [0, 1, -1, 3, 7, -8, 12]) {
    for (const anchor of [undefined, 'Eb4', 'c3', 60, 'g5', 'C', 71]) {
      stepInNamedScale.push({ scale, step, anchor: plain(anchor), result: safely(() => num(tonleiter.stepInNamedScale(step, scale, anchor))) });
    }
  }
}
const scaleStep = [];
for (const notes of [[60, 63, 67], ['C3', 'E3', 'G3', 'Bb3'], [], [60]]) {
  for (const offset of [0, 1, 4, -1, -4, 7, 1.5]) {
    for (const octaves of [undefined, 1, 2]) {
      scaleStep.push({ notes, offset, octaves: plain(octaves), result: safely(() => num(octaves === undefined ? tonleiter.scaleStep(notes, offset) : tonleiter.scaleStep(notes, offset, octaves))) });
    }
  }
}
const nearest = [];
for (const [target, numbers] of [[3, [0, 2, 4, 5]], [3, [0, 3, 3, 5]], [-2, [0, 12]], [6, [0, 4, 8]], [5, []]]) {
  for (const preferHigher of [true, false, undefined]) nearest.push({ target, numbers, preferHigher: plain(preferHigher), result: tonleiter.nearestNumberIndex(target, numbers, preferHigher) });
}
const midi2note = [];
for (const midi of [0, 12, 60, 61, 127, 128, -1, 60.5, 1000]) for (const sharp of [false, true]) midi2note.push({ midi, sharp, result: tonleiter.midi2note(midi, sharp) });
const tokenizeChord = ['C', 'Bb^7/F', 'Am7', 'C#m7b5/G#', 'H', '', 'cmaj', 'C/', 'C//E', 'C^7/', 'Ab/Bb'].map((chord) => [chord, plain(tonleiter.tokenizeChord(chord))]);

fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(
  output,
  JSON.stringify(
    {
      notes: noteCases, intervals: intervalCases, transpose: transposeCases, transposeNoOctave, intervalAdd, intervalSubstract, fromSemitones, semitones,
      enharmonic, chromaMidi, ranges, scaleTokenize, scaleGet, scaleHaps, scaleTranspose, transposeHaps,
      renderVoicing, voicingsInRange, stepInNamedScale, scaleStep, nearestNumberIndex: nearest, midi2note, tokenizeChord,
    },
    (key, value) => (value === undefined ? null : typeof value === 'number' && !Number.isFinite(value) ? String(value) : value),
  ) + '\n',
);
log(
  `notes ${noteCases.length}, intervals ${intervalCases.length}, transpose ${transposeCases.length}+${transposeNoOctave.length}, scales ${scaleGet.length}, ` +
    `scaleHaps ${scaleHaps.length}, scaleTranspose ${scaleTranspose.length}, transposeHaps ${transposeHaps.length}, renderVoicing ${renderVoicing.length}, ` +
    `voicingsInRange ${voicingsInRange.length}`,
);
