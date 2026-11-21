// Runs the real dough.wasm of dough-synth@0.4.1 (the engine @strudel/dough plays through) and writes what it renders as golden vectors for cosic.dough (Task 4.12).
//
//   node tools/strudel-ref/dough-oracle.mjs          # writes engine/src/test/resources/dough/golden.json
//
// What runs is the real thing: dough.js from the npm package builds the engine exactly as the audio worklet does (`new Dough({server: true, sampleRate})`, `initWasm`, the
// shared 200 MB memory, `dough_init(sr, 32 voices, 1 orbit, 1 s delay, ...)`), events go in through `Dough.encodeEvent` (the same `key/value/key/value` text Strudel sends) and
// the parser of the C code, and every block is one call of `dsp()` (128 frames, `D.output`). The only thing replaced is the transport (no AudioWorklet, no messages).
//
// A case is a list of events, each either IMMEDIATE (no `time`: the engine runs it between two blocks, at block `atBlock`, in the order listed) or TIMED (`time` in seconds: the
// engine keeps it in its schedule and runs it before the first sample whose time, `doughtime + i / sr` as the C code computes it in doubles, is not before it). The frame a timed
// event starts at is worked out here with the same double arithmetic and written next to it, so the Kotlin test starts it at that frame. A fresh engine renders every case, because
// the engine has state that outlives events (the random seed of the noises, the delay line and the reverb).
//
// Needs Node 22+. STRUDEL_DIR (default tools/strudel-ref/strudel next to this file) is the checkout made by fetch.sh; DOUGH_OUT overrides the output directory.
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { fileURLToPath, pathToFileURL } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.STRUDEL_DIR ?? path.join(here, 'strudel');
const outDir = process.env.DOUGH_OUT ?? path.join(here, '../../engine/src/test/resources/dough');
const COMMIT = '84ef32eec8074f90c6690ab8d6a6c204f508b182';

const pnpm = path.join(root, 'node_modules/.pnpm');
const dir = fs.readdirSync(pnpm).find((d) => d.startsWith('dough-synth@0.4.1'));
if (!dir) throw new Error('dough-synth@0.4.1 is not installed in ' + pnpm + ' (run tools/strudel-ref/fetch.sh)');
const pkg = path.join(pnpm, dir, 'node_modules/dough-synth');
const { Dough } = await import(pathToFileURL(path.join(pkg, 'dough.js')).href);
const wasmBytes = fs.readFileSync(path.join(pkg, 'dough.wasm'));
const sha = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');

const SR = 48000;
const BLOCK = 128;
const secondsToBlocks = (seconds, sr = SR) => Math.ceil((seconds * sr) / BLOCK);

// ------------------------------------------------------------------------------------------------------------------ the files the cases play
// A file is registered the way the worklet does it on a `load_file` message: `dough_fits_pcm` says where the data goes, the data is copied into the engine's pcm memory and
// `dough_register_pcm(index, pointer, frames, channels, freq)` makes it a file. Strudel always registers them with freq 65.46 (Dough.registerFile).
const FILE_FREQ = 65.46;
function makeFile(index, channels, frames, fn) {
  const pcm = new Float32Array(frames * channels);
  for (let i = 0; i < frames; i++) for (let c = 0; c < channels; c++) pcm[i * channels + c] = Math.fround(fn(i, c, frames));
  return { index, channels, frames, freq: FILE_FREQ, pcm };
}
// 0: stereo, 2400 frames: a decaying 300 Hz sine on the left and a falling ramp on the right
const fileStereo = makeFile(0, 2, 2400, (i, c, n) => (c === 0 ? Math.sin((2 * Math.PI * 300 * i) / SR) * Math.exp((-3 * i) / n) : 0.8 * (1 - (2 * i) / n)));
// 1: mono, 1800 frames: a click-like burst of a 1 kHz sine with a triangle envelope
const fileMono = makeFile(1, 1, 1800, (i, _c, n) => 0.9 * Math.sin((2 * Math.PI * 1000 * i) / SR) * (1 - Math.abs((2 * i) / n - 1)));
const FILES = [fileStereo, fileMono];

function registerFile(D, file) {
  const size = file.frames * file.channels;
  const head = D.dough_fits_pcm(file.index, size);
  if (head < 0) throw new Error('cannot load file ' + file.index + ': ' + head);
  D.pcm_data.set(file.pcm, head);
  D.dough_register_pcm(file.index, D.mirror.pcm + head * 4, file.frames, file.channels, file.freq);
}

// ------------------------------------------------------------------------------------------------------------------ the cases
const cases = [];
const add = (name, seconds, events, extra = {}) => cases.push({ name, sr: extra.sr ?? SR, blocks: secondsToBlocks(seconds, extra.sr ?? SR), events, ...extra });
const play = (controls, extra = {}) => ({ controls: { dough: 'play', ...controls }, ...extra });
const cmd = (name, controls = {}, extra = {}) => ({ controls: { dough: name, ...controls }, ...extra });
const at = (frame, sr = SR) => ({ time: frame / sr });
const atBlock = (atBlock) => ({ atBlock });

// ---- sources
add('sine 220 duration .1', 0.15, [play({ s: 'sine', freq: 220, duration: 0.1 })]);
add('sine note 69 is 440 Hz', 0.08, [play({ s: 'sine', note: 69, duration: 0.05 })]);
add('tri default frequency 330', 0.1, [play({ s: 'tri', duration: 0.06 })]);
add('triangle note 60', 0.1, [play({ s: 'triangle', note: 60, duration: 0.06 })]);
add('saw 110 (polyBLEP)', 0.1, [play({ s: 'saw', freq: 110, duration: 0.06 })]);
add('sawtooth note 33 and zaw 110 together', 0.1, [play({ s: 'sawtooth', note: 33, duration: 0.06 }), play({ s: 'zaw', freq: 110, duration: 0.06 })]);
add('pulse pw .1', 0.1, [play({ s: 'pulse', freq: 110, pw: 0.1, duration: 0.06 })]);
add('square default pw and pulze pw .3', 0.1, [play({ s: 'square', freq: 110, duration: 0.06 }), play({ s: 'pulze', freq: 165, pw: 0.3, duration: 0.06 })]);
add('zquare', 0.08, [play({ s: 'zquare', freq: 220, duration: 0.05 })]);
add('white noise (LCG seed 123456789)', 0.08, [play({ s: 'white', duration: 0.05 })]);
add('pink noise', 0.08, [play({ s: 'pink', duration: 0.05 })]);
add('brown noise', 0.08, [play({ s: 'brown', duration: 0.05 })]);
add('three noises share the generator', 0.08, [play({ s: 'white', duration: 0.04 }), play({ s: 'pink', duration: 0.04 }), play({ s: 'brown', duration: 0.04 })]);
add('const', 0.06, [play({ s: 'const', duration: 0.03 })]);
add('unknown sound plays a triangle', 0.08, [play({ s: 'bd', freq: 220, duration: 0.05 })]);
add('sound is an alias of s', 0.08, [play({ sound: 'saw', freq: 220, duration: 0.05 })]);
add('no sound at all: triangle at 330 Hz', 0.08, [play({ duration: 0.05 })]);

// ---- envelopes and levels
add('full adsr', 0.2, [play({ s: 'saw', freq: 110, attack: 0.02, decay: 0.05, sustain: 0.3, release: 0.05, duration: 0.1 })]);
add('attack only: sustain 1', 0.15, [play({ s: 'sine', freq: 220, attack: 0.03, duration: 0.06 })]);
add('decay only: sustain 0', 0.15, [play({ s: 'sine', freq: 220, decay: 0.05, duration: 0.1 })]);
add('attack and decay: sustain 0', 0.15, [play({ s: 'saw', freq: 220, attack: 0.01, decay: 0.04, duration: 0.1 })]);
add('release only', 0.15, [play({ s: 'sine', freq: 220, release: 0.06, duration: 0.05 })]);
add('sustain above 1 is clamped', 0.12, [play({ s: 'sine', freq: 220, attack: 0.01, decay: 0.01, sustain: 2, duration: 0.05 })]);
add('gain velocity postgain', 0.08, [play({ s: 'sine', freq: 220, gain: 0.5, velocity: 0.5, postgain: 2, duration: 0.05 })]);
add('gate 0 frees the voice at once', 0.05, [play({ s: 'sine', freq: 220, gate: 0 }), play({ s: 'saw', freq: 110, duration: 0.02 })]);
add('endless voice until hush', 0.2, [play({ s: 'saw', freq: 110 }), cmd('hush', {}, atBlock(30))]);
add('hush_endless keeps voices with a duration', 0.2, [play({ s: 'saw', freq: 110 }), play({ s: 'sine', freq: 220, duration: 0.15 }), cmd('hush_endless', {}, atBlock(25))]);
add('hush_group', 0.2, [play({ s: 'saw', freq: 110, group: 1 }), play({ s: 'sine', freq: 220, group: 2 }), cmd('hush_group', { group: 1 }, atBlock(25))]);
add('panic stops everything', 0.1, [play({ s: 'saw', freq: 110 }), play({ s: 'sine', freq: 220 }), cmd('panic', {}, atBlock(20))]);
add('reset stops everything', 0.1, [play({ s: 'saw', freq: 110 }), cmd('reset', {}, atBlock(20)), play({ s: 'sine', freq: 330, duration: 0.03 }, atBlock(30))]);
add('events with times start on their sample', 0.2, [
  play({ s: 'sine', freq: 220, duration: 0.03, ...at(0) }),
  play({ s: 'saw', freq: 220, duration: 0.03, ...at(1000) }),
  play({ s: 'tri', freq: 330, duration: 0.03, ...at(2501) }),
  play({ s: 'pulse', freq: 110, duration: 0.03, ...at(6007) }),
]);

// ---- pitch, vibrato, FM, AM, RM, glide
add('penv up with attack', 0.12, [play({ s: 'saw', freq: 220, penv: 12, patt: 0.01, pdec: 0.05, duration: 0.08 })]);
add('penv alone: psus 1 targets the frequency', 0.1, [play({ s: 'saw', freq: 220, penv: -12, patt: 0.02, duration: 0.08 })]);
add('penv with decay and sustain', 0.15, [play({ s: 'sine', freq: 220, penv: 7, patt: 0.005, pdec: 0.04, psus: 0.4, prel: 0.03, duration: 0.08 })]);
add('canonical envelope names are ignored', 0.1, [play({ s: 'saw', freq: 220, penv: 12, pattack: 0.01, pdecay: 0.05, duration: 0.06 })]);
add('vib 8 vibmod 24', 0.15, [play({ s: 'sine', freq: 220, vib: 8, vibmod: 24, duration: 0.1 })]);
add('vibmod alone', 0.1, [play({ s: 'sine', freq: 220, vibmod: 2, duration: 0.06 })]);
add('fm 4 fmh 5', 0.1, [play({ s: 'sine', freq: 110, fm: 4, fmh: 5, duration: 0.06 })]);
add('fmi is fm, fmh alone', 0.1, [play({ s: 'sine', freq: 110, fmi: 2, duration: 0.04 }), play({ s: 'sine', freq: 220, fmh: 1.5, duration: 0.04 })]);
add('fm envelope', 0.15, [play({ s: 'sine', freq: 110, fm: 2, fmh: 3, fme: 2, fma: 0.01, fmd: 0.05, fms: 0.2, fmr: 0.04, duration: 0.08 })]);
add('am 4', 0.15, [play({ s: 'saw', freq: 110, am: 40, duration: 0.1 })]);
add('amdepth alone', 0.1, [play({ s: 'saw', freq: 110, amdepth: 1, duration: 0.06 })]);
add('rm 440 rmdepth 1', 0.1, [play({ s: 'saw', freq: 220, rm: 440, rmdepth: 1, duration: 0.06 })]);
add('rmdepth alone is ring modulation by silence', 0.06, [play({ s: 'saw', freq: 220, rmdepth: 0.5, duration: 0.03 })]);
add('glide on a voice group', 0.2, [
  play({ s: 'sine', note: 60, group: 1, voices: 1, glide: 0.5, duration: 0.15, ...at(0) }),
  play({ s: 'sine', note: 72, group: 1, voices: 1, glide: 0.5, duration: 0.1, ...at(3000) }),
]);

// ---- filters
add('lpf 800 lpq .5', 0.1, [play({ s: 'saw', freq: 110, lpf: 800, lpq: 0.5, duration: 0.06 })]);
add('cutoff and resonance are aliases', 0.08, [play({ s: 'saw', freq: 110, cutoff: 600, resonance: 8, duration: 0.05 })]);
add('lpf 24db', 0.08, [play({ s: 'saw', freq: 110, lpf: 800, lpq: 4, ftype: '24db', duration: 0.05 })]);
add('lpf 48db', 0.08, [play({ s: 'saw', freq: 110, lpf: 800, lpq: 4, ftype: '48db', duration: 0.05 })]);
add('ftype 3 is read as a number (four stages)', 0.08, [play({ s: 'saw', freq: 110, lpf: 800, ftype: 3, duration: 0.05 })]);
add('lpf envelope', 0.15, [play({ s: 'saw', freq: 110, lpf: 800, lpq: 0.5, lpe: 4, lpd: 0.2, duration: 0.1 })]);
add('lpf envelope with all stages', 0.15, [play({ s: 'saw', freq: 110, lpf: 400, lpe: 5, lpa: 0.02, lpd: 0.04, lps: 0.3, lpr: 0.04, duration: 0.08 })]);
add('lpe alone filters at 20000 Hz', 0.08, [play({ s: 'saw', freq: 110, lpe: 1, duration: 0.05 })]);
add('hpf 1000 hpq 1', 0.1, [play({ s: 'saw', freq: 220, hpf: 1000, hpq: 1, duration: 0.06 })]);
add('hcutoff and hresonance, 24db', 0.08, [play({ s: 'saw', freq: 220, hcutoff: 700, hresonance: 3, ftype: '24db', duration: 0.05 })]);
add('hpf envelope', 0.12, [play({ s: 'saw', freq: 110, hpf: 500, hpe: 3, hpa: 0.01, hpd: 0.05, hps: 0.2, hpr: 0.03, duration: 0.08 })]);
add('bpf 1000 bpq 2', 0.1, [play({ s: 'saw', freq: 110, bpf: 1000, bpq: 2, duration: 0.06 })]);
add('bandf and bandq, 48db', 0.08, [play({ s: 'white', bandf: 1500, bandq: 3, ftype: '48db', gain: 0.1, duration: 0.05 })]);
add('bpf envelope', 0.12, [play({ s: 'white', bpf: 800, bpe: 2, bpa: 0.01, bpd: 0.05, bps: 0.1, bpr: 0.03, duration: 0.08 })]);
add('all three filters in a row', 0.1, [play({ s: 'white', lpf: 3000, hpf: 300, bpf: 1000, bpq: 1, duration: 0.06 })]);

// ---- effects of a voice
add('phaser rate only', 0.15, [play({ s: 'saw', freq: 110, phaser: 2, duration: 0.1 })]);
add('phaser with all parameters', 0.15, [play({ s: 'saw', freq: 110, phaser: 3, phaserdepth: 0.5, phasersweep: 1000, phasercenter: 800, duration: 0.1 })]);
add('phaserrate alias and depth', 0.1, [play({ s: 'white', phaserrate: 1, phaserdepth: 1, duration: 0.06 })]);
add('flanger', 0.15, [play({ s: 'saw', freq: 110, flanger: 1, flangerdepth: 0.7, flangerfeedback: 0.8, duration: 0.1 })]);
add('flangerrate alias with defaults', 0.12, [play({ s: 'white', flangerrate: 4, duration: 0.08 })]);
add('chorus', 0.15, [play({ s: 'saw', freq: 110, chorus: 2, chorusdepth: 0.8, chorusdelay: 15, duration: 0.1 })]);
add('chorusrate alias with defaults', 0.12, [play({ s: 'saw', freq: 220, chorusrate: 1, duration: 0.08 })]);
add('distort 4', 0.08, [play({ s: 'saw', freq: 110, distort: 4, duration: 0.05 })]);
add('distort with distortvol', 0.08, [play({ s: 'sine', freq: 110, distort: 1.5, distortvol: 0.5, duration: 0.05 })]);
add('crush 4', 0.08, [play({ s: 'sine', freq: 220, crush: 4, duration: 0.05 })]);
add('crush below 1 is 1', 0.06, [play({ s: 'sine', freq: 220, crush: 0.2, gain: 8, duration: 0.03 })]);
add('coarse 8', 0.08, [play({ s: 'saw', freq: 220, coarse: 8, duration: 0.05 })]);
add('coarse 3.9 is read as 3', 0.06, [play({ s: 'saw', freq: 220, coarse: 3.9, duration: 0.03 })]);
add('pan hard left, hard right, a quarter', 0.08, [
  play({ s: 'sine', freq: 220, pan: 0, duration: 0.04 }),
  play({ s: 'sine', freq: 330, pan: 1, duration: 0.04, ...at(2400) }),
  play({ s: 'sine', freq: 440, pan: 0.25, duration: 0.04, ...at(4800) }),
]);
add('pan with a filter and an amplitude modulation', 0.08, [play({ s: 'saw', freq: 110, pan: 0.8, lpf: 1200, am: 30, duration: 0.05 })]);

// ---- orbit: delay and reverb
add('delay .5 delaytime .08 delayfeedback .8', 0.4, [play({ s: 'sine', freq: 440, delay: 0.5, delaytime: 0.08, delayfeedback: 0.8, duration: 0.02 })]);
add('delay with the default time and feedback', 0.45, [play({ s: 'saw', freq: 220, lpf: 2000, delay: 0.7, delayfeedback: 0.5, duration: 0.03 })]);
add('default delay time is .333 s', 1.1, [play({ s: 'saw', freq: 220, lpf: 2000, delay: 0.7, duration: 0.03 })]);
add('delaytime past the delay line (1 s) is clamped', 1.1, [play({ s: 'sine', freq: 440, delay: 1, delaytime: 5, delayfeedback: 0.5, duration: 0.02 })]);
add('two voices share the delay: the last one sets its time', 0.4, [
  play({ s: 'sine', freq: 440, delay: 0.5, delaytime: 0.1, delayfeedback: 0.5, duration: 0.02 }),
  play({ s: 'saw', freq: 220, delay: 0.5, delaytime: 0.15, delayfeedback: 0.6, duration: 0.02, ...at(1000) }),
]);
add('verb .5', 0.35, [play({ s: 'saw', freq: 220, verb: 0.5, duration: 0.02 })]);
add('verb with every parameter', 0.35, [play({ s: 'sine', freq: 330, verb: 0.8, verbdecay: 0.9, verbdamp: 0.5, verbpredelay: 0.05, verbdiff: 0.5, duration: 0.03 })]);
add('room aliases', 0.25, [play({ s: 'white', room: 0.4, roomdecay: 0.6, roomdamp: 0.7, roompredelay: 0.02, roomdiff: 0.4, duration: 0.01 })]);
add('reverb alias, short predelay', 0.2, [play({ s: 'pulse', freq: 330, reverb: 1, verbpredelay: 0, duration: 0.02 })]);
add('delay and reverb together on stereo output', 0.4, [play({ s: 'saw', freq: 165, pan: 0.2, delay: 0.4, delaytime: 0.12, delayfeedback: 0.5, verb: 0.4, duration: 0.03 })]);
add('reverb tail past 2^15 samples (the modulation turns around)', 0.8, [play({ s: 'sine', freq: 330, verb: 1, verbdecay: 0.99, duration: 0.01 })]);

// ---- files
add('stereo file', 0.1, [play({ file: 0 })]);
add('file at speed 2', 0.08, [play({ file: 0, speed: 2 })]);
add('file at speed 0.5, begin and end', 0.1, [play({ file: 0, speed: 0.5, begin: 0.25, end: 0.75 })]);
add('file backwards', 0.1, [play({ file: 0, speed: -1 })]);
add('mono file with a note (rate = freq / 65.46)', 0.08, [play({ file: 1, note: 67 })]);
add('mono file with freq and a filter', 0.08, [play({ file: 1, freq: 130.92, lpf: 1500, duration: 0.04 })]);
add('file that ends before the envelope', 0.1, [play({ file: 0, speed: 8, duration: 0.2 })]);
add('begin after end is silence', 0.05, [play({ file: 0, begin: 0.8, end: 0.2, duration: 0.02 })]);
add('file index that is not registered plays a triangle', 0.05, [play({ file: 5, freq: 220, duration: 0.03 })]);
add('begin and end are clamped to 0 and 1', 0.06, [play({ file: 1, begin: -1, end: 5 })]);

// ---- voices and groups
add('33rd voice is dropped', 0.3, [...Array.from({ length: 33 }, (_, i) => play({ s: 'sine', freq: 110 + 10 * i, gain: 0.3, duration: 0.01 + 0.004 * i }))]);
add('voices end one after another', 0.4, [...Array.from({ length: 8 }, (_, i) => play({ s: i % 2 ? 'saw' : 'sine', freq: 110 * (1 + i / 4), lpf: 2000, duration: 0.01 + 0.015 * i }))]);
add('group steals its oldest voice', 0.3, [
  play({ s: 'sine', freq: 220, group: 0, voices: 2, duration: 0.2, ...at(0) }),
  play({ s: 'sine', freq: 330, group: 0, voices: 2, duration: 0.2, ...at(500) }),
  play({ s: 'sine', freq: 440, group: 0, voices: 2, duration: 0.2, ...at(1000) }),
  play({ s: 'sine', freq: 550, group: 0, voices: 2, duration: 0.2, ...at(1500) }),
]);
add('group steal keeps the envelope running; reset 1 starts it again', 0.3, [
  play({ s: 'saw', freq: 220, group: 3, voices: 1, attack: 0.01, decay: 0.05, sustain: 0.5, duration: 0.2, ...at(0) }),
  play({ s: 'saw', freq: 330, group: 3, voices: 1, attack: 0.01, decay: 0.05, sustain: 0.5, duration: 0.2, ...at(3000) }),
  play({ s: 'saw', freq: 440, group: 3, voices: 1, attack: 0.01, decay: 0.05, sustain: 0.5, duration: 0.2, reset: 1, ...at(6000) }),
]);
add('group without voices does not steal', 0.15, [play({ s: 'sine', freq: 220, group: 5, duration: 0.1 }), play({ s: 'sine', freq: 330, group: 5, duration: 0.1 }, atBlock(10))]);
add('update_group changes the voices of the group', 0.25, [
  play({ s: 'saw', freq: 220, group: 1, lpf: 3000 }),
  play({ s: 'saw', freq: 110, group: 2 }),
  cmd('update_group', { group: 1, freq: 330, lpf: 500, pan: 0.2 }, atBlock(30)),
  cmd('hush', {}, atBlock(60)),
]);
add('update_all changes every voice', 0.25, [
  play({ s: 'saw', freq: 220 }),
  play({ s: 'sine', freq: 110, lpf: 5000 }),
  cmd('update_all', { gain: 0.5, lpf: 400, lpq: 2, verb: 0.3 }, atBlock(30)),
  cmd('hush', {}, atBlock(60)),
]);
add('update with a file, begin and speed', 0.12, [play({ file: 0, speed: 0.5 }), cmd('update_all', { speed: 2, begin: 0.1 }, atBlock(5))]);

// ---- the text of an event
add('unknown keys are ignored', 0.08, [play({ s: 'sine', freq: 220, n: 3, unit: 'c', bank: 'RolandTR909', cutoffa: 4, duration: 0.05 })]);
add('numeric strings go through atof', 0.08, [play({ s: 'sine', freq: '220.5', attack: '0.01', lpf: '1e3', duration: '0.05' })]);
add('exponent forms and bad numbers', 0.08, [play({ s: 'sine', freq: '2.2e2', gain: 'x', pan: '', lpf: '5e', duration: 0.05 })]);
add('an alias later in the event wins', 0.08, [play({ s: 'saw', freq: 110, lpf: 300, cutoff: 5000, duration: 0.05 })]);
add('a slash in a value shifts the keys', 0.08, [play({ s: 'saw', freq: 110, bank: 'a/gain', gain: 0.2, duration: 0.05 })]);

// ---- everything together, and another sample rate (the delay line, the lag unit and the frequency of every oscillator depend on it)
add('a voice with every stage', 0.4, [
  play({
    s: 'saw', note: 45, duration: 0.2, attack: 0.01, decay: 0.05, sustain: 0.6, release: 0.08, penv: 5, patt: 0.01, pdec: 0.04, vib: 6, vibmod: 0.5, fm: 0.5, fmh: 2, fme: 1, fma: 0.01, fmd: 0.05, fms: 0.3,
    lpf: 1500, lpq: 3, lpe: 2, lpd: 0.1, hpf: 80, bpf: 4000, bpq: 0.3, ftype: '24db', coarse: 2, crush: 8, distort: 0.5, distortvol: 0.7, am: 7, amdepth: 0.3, rm: 90, rmdepth: 0.2,
    phaser: 0.7, flanger: 0.3, chorus: 1.1, pan: 0.35, gain: 0.8, velocity: 0.9, postgain: 1.2, delay: 0.3, delaytime: 0.09, delayfeedback: 0.4, verb: 0.3,
  }),
]);
add('44.1 kHz: glide towards an updated frequency, with a filter', 0.15, [play({ s: 'sine', freq: 330, glide: 0.05, group: 1, voices: 1, lpf: 1000, duration: 0.1 }), cmd('update_group', { group: 1, freq: 660 }, atBlock(5))], { sr: 44100 });
add('44.1 kHz: timed events', 0.2, [play({ s: 'saw', freq: 220, duration: 0.04, ...at(100, 44100) }), play({ s: 'pulse', freq: 330, duration: 0.04, ...at(3333, 44100) })], { sr: 44100 });
add('44.1 kHz: delay and reverb', 0.5, [play({ s: 'sine', freq: 440, delay: 0.6, delaytime: 0.09, delayfeedback: 0.6, verb: 0.5, pan: 0.7, duration: 0.02 })], { sr: 44100 });
add('44.1 kHz: file', 0.1, [play({ file: 1, note: 60 })], { sr: 44100 });

// ------------------------------------------------------------------------------------------------------------------ running them
const b64 = (floats) => Buffer.from(floats.buffer, floats.byteOffset, floats.byteLength).toString('base64');
const text = new TextDecoder();

/** The `atof` of dough.h (CLANGWASM): the time of an event is the double this makes of its text, which is not always the double the text stands for. */
function cAtoi(s) {
  let i = 0;
  let negative = false;
  if (s[0] === '-') {
    negative = true;
    i++;
  }
  let acc = 0;
  for (; i < s.length; i++) {
    acc = (Math.imul(acc, 10) + 0) | 0;
    const digit = s.charCodeAt(i) - 48;
    if (digit < 0 || digit > 9) return 0;
    acc = (acc + digit) | 0;
  }
  return negative ? -acc : acc;
}
function cAtof(str) {
  let p = 0;
  const at = (i) => (i < str.length ? str[i] : '\0');
  const isDigit = (c) => c >= '0' && c <= '9';
  let sign = 1;
  if (at(p) === '-') {
    sign = -1;
    p++;
  }
  let integerEnd = p;
  while (at(integerEnd) !== '\0' && isDigit(at(integerEnd))) integerEnd++;
  let integerPart = 0;
  for (; p < integerEnd; p++) {
    integerPart *= 10;
    integerPart += at(p).charCodeAt(0) - 48;
  }
  let stop = at(p);
  if (stop === '\0') return sign * integerPart;
  let significand = integerPart;
  if (stop === '.') {
    p++;
    let multiplier = 1;
    while (isDigit(at(p))) {
      multiplier *= 0.1;
      significand += multiplier * (at(p).charCodeAt(0) - 48);
      p++;
    }
  }
  stop = at(p);
  if (stop === '\0') return sign * significand;
  let exponent = 0;
  if (stop === 'e' || stop === 'E') exponent = cAtoi(str.slice(p + 1));
  let value = significand;
  while (exponent > 0) {
    value *= 10;
    --exponent;
  }
  while (exponent < 0) {
    value *= 0.1;
    ++exponent;
  }
  return sign * value;
}

/** The frame a timed event starts at: the first sample whose time is not before the event's. The C code adds `i / sr` to `tick / sr`, both in doubles. */
function startFrameOf(time, sr) {
  for (let F = Math.max(0, Math.floor(time * sr) - 3); ; F++) {
    const block = Math.floor(F / BLOCK);
    const i = F - block * BLOCK;
    const doughtime = (block * BLOCK) / sr;
    if (time <= doughtime + i / sr) return F;
  }
}

async function render(c) {
  const D = new Dough({ server: true, sampleRate: c.sr });
  const memory = D.initMemory();
  await D.initWasm(wasmBytes, memory);
  const files = (c.files ?? FILES).filter((f) => c.events.some((e) => 'file' in e.controls) || c.files);
  for (const f of files) registerFile(D, f);
  const activeVoices = new Int32Array(memory.buffer, D.mirror.active_voices, 1);

  const events = c.events.map((e) => {
    const encoded = text.decode(D.encodeEvent(e.controls)).replace(/\0$/, '');
    const timed = 'time' in e.controls;
    return { controls: e.controls, encoded, atBlock: e.atBlock ?? 0, timed, startFrame: timed ? startFrameOf(cAtof(String(e.controls.time)), c.sr) : null };
  });
  // timed events are pushed ahead, as Strudel does when it sees them in its look-ahead window
  const timedCount = events.filter((e) => e.timed).length;
  if (timedCount > 64) throw new Error(c.name + ': the schedule holds 64 events');
  for (const e of events) if (e.timed) D.schedule(e.controls);

  const out = new Float32Array(c.blocks * BLOCK * 2);
  const voices = [];
  for (let b = 0; b < c.blocks; b++) {
    for (const e of events) if (!e.timed && e.atBlock === b) D.schedule(e.controls);
    D.dsp();
    out.set(D.output, b * BLOCK * 2);
    voices.push(activeVoices[0]);
  }
  // when both channels are identical at every frame only one is kept
  let same = true;
  for (let i = 0; i < out.length; i += 2) {
    if (Math.fround(out[i]) !== Math.fround(out[i + 1]) && !(Number.isNaN(out[i]) && Number.isNaN(out[i + 1]))) {
      same = false;
      break;
    }
  }
  let data = out;
  if (same) {
    data = new Float32Array(out.length / 2);
    for (let i = 0; i < data.length; i++) data[i] = out[2 * i];
  }
  let peak = 0;
  let nan = 0;
  for (const v of out) {
    if (Number.isNaN(v)) nan++;
    else peak = Math.max(peak, Math.abs(v));
  }
  return {
    name: c.name,
    sampleRate: c.sr,
    frames: c.blocks * BLOCK,
    events: events.map((e) => ({ encoded: e.encoded, controls: e.controls, ...(e.timed ? { startFrame: e.startFrame } : { atBlock: e.atBlock }) })),
    files: files.map((f) => ({ index: f.index, channels: f.channels, frames: f.frames, freq: f.freq, pcm: b64(f.pcm) })),
    activeVoices: voices,
    stereo: !same,
    output: b64(data),
    peak,
    nan,
  };
}

const t0 = Date.now();
const results = [];
for (const c of cases) {
  const r = await render(c);
  results.push(r);
  console.log(`${r.name.padEnd(70)} frames ${String(r.frames).padStart(6)}  peak ${r.peak.toFixed(4)}  ${r.stereo ? 'stereo' : 'dual-mono'}${r.nan ? '  NaN ' + r.nan : ''}`);
}

fs.mkdirSync(outDir, { recursive: true });
const golden = {
  commit: COMMIT,
  package: 'dough-synth@0.4.1',
  wasmSha256: sha(wasmBytes),
  node: process.version,
  sampleRate: SR,
  blockFrames: BLOCK,
  cases: results.map(({ peak, nan, ...rest }) => rest),
};
const file = path.join(outDir, 'golden.json');
fs.writeFileSync(file, JSON.stringify(golden));
console.log(`${results.length} cases, ${(fs.statSync(file).size / 1e6).toFixed(2)} MB, ${((Date.now() - t0) / 1000).toFixed(1)} s -> ${path.relative(process.cwd(), file)}`);
