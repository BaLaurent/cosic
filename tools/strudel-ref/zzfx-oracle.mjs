// Runs the ZZFX sounds of the pinned superdough and writes their buffers as golden vectors for cosic.audio.sources.Zzfx* (Task 3.13).
//
//   node tools/strudel-ref/zzfx-oracle.mjs          # writes engine/src/test/resources/sources/zzfx.json
//
// What runs is the real thing: packages/superdough/{zzfx,zzfx_fork,util,logger}.mjs are copied unmodified into a scratch directory next to three stubs for the modules that
// need the application (audioContext, superdough: the sound registry, helpers). registerZZFXSounds() therefore registers the real `onTrigger` of every ZZFX sound, and a case
// calls it exactly as superdough() does, `onTrigger(t, value, onended)` with `value.duration` the hap duration in seconds; the buffer it plays is the one it creates with the
// stub context. A "build" case calls buildSamples of zzfx_fork.mjs directly. Math.random is replaced by SplitMix64 (cosic.audio.sources.SourceRng) seeded per case, so the
// random start frequency is the one the Kotlin tests draw.
//
// Needs Node 22+. STRUDEL_DIR (default tools/strudel-ref/strudel next to this file) is the checkout made by fetch.sh; ZZFX_OUT overrides the output directory.
import fs from 'fs';
import os from 'os';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.STRUDEL_DIR ?? path.join(here, 'strudel');
const outDir = process.env.ZZFX_OUT ?? path.join(here, '../../engine/src/test/resources/sources');
const COMMIT = '84ef32eec8074f90c6690ab8d6a6c204f508b182';

// ------------------------------------------------------------------------------------------------------------------ SplitMix64 = SourceRng
const M64 = (1n << 64n) - 1n;
function splitMix(seed) {
  let state = BigInt.asUintN(64, BigInt(seed));
  return () => {
    state = (state + 0x9e3779b97f4a7c15n) & M64;
    let z = state;
    z = ((z ^ (z >> 30n)) * 0xbf58476d1ce4e5b9n) & M64;
    z = ((z ^ (z >> 27n)) * 0x94d049bb133111ebn) & M64;
    z = z ^ (z >> 31n);
    return Number(z >> 11n) * 2 ** -53;
  };
}

// ------------------------------------------------------------------------------------------------------------------ the scratch module tree
const superdough = path.join(root, 'packages/superdough');
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'zzfx-oracle-'));
for (const f of ['zzfx.mjs', 'zzfx_fork.mjs', 'util.mjs', 'logger.mjs']) fs.copyFileSync(path.join(superdough, f), path.join(scratch, f));
fs.writeFileSync(path.join(scratch, 'audioContext.mjs'), 'export const getAudioContext = () => globalThis.__ctx;\n');
fs.writeFileSync(path.join(scratch, 'superdough.mjs'), 'export const registerSound = (name, onTrigger, data) => { (globalThis.__sounds ??= {})[name] = { onTrigger, data }; };\n');
fs.writeFileSync(path.join(scratch, 'helpers.mjs'), 'export const onceEnded = () => {}; export const releaseAudioNode = () => {};\n');

const makeContext = (sampleRate) => ({
  sampleRate,
  createBuffer(channels, length, rate) {
    if (length < 1) throw new Error('createBuffer: length ' + length);
    const data = new Float32Array(length);
    return { length, sampleRate: rate, getChannelData: () => data };
  },
  createBufferSource: () => ({ start(t) { this.t = t; } }),
});

const zzfx = await import(pathToFileURL(path.join(scratch, 'zzfx.mjs')).href);
const fork = await import(pathToFileURL(path.join(scratch, 'zzfx_fork.mjs')).href);
zzfx.registerZZFXSounds();
const sounds = globalThis.__sounds;
const names = Object.keys(sounds);

// ------------------------------------------------------------------------------------------------------------------ the cases
const sound = (name, wave, value, durationSec, extra = {}) => ({ kind: 'sound', name, sound: wave, controls: { s: wave, ...value }, durationSec, sampleRate: 48000, seed: 7, ...extra });
const build = (name, args, extra = {}) => ({ kind: 'build', name, args, sampleRate: 48000, seed: 7, ...extra });

const cases = [
  // ---- getZZFX: the mapping of the controls, every waveform
  sound('z_sine note 69', 'z_sine', { note: 69 }, 0.1),
  sound('z_sine default note', 'z_sine', {}, 0.08),
  sound('z_sine note 0', 'z_sine', { note: 0 }, 0.05),
  sound('z_sine note name', 'z_sine', { note: 'c4' }, 0.05),
  sound('z_sine note name without octave', 'z_sine', { note: 'eb' }, 0.05),
  sound('z_sine freq wins', 'z_sine', { note: 40, freq: 330 }, 0.05),
  sound('z_sine ignores octave', 'z_sine', { note: 57, octave: 2 }, 0.05),
  sound('z_sine note that is a boolean gives 220 Hz', 'z_sine', { note: true }, 0.05),
  sound('z_sawtooth envelope', 'z_sawtooth', { note: 48, attack: 0.01, decay: 0.02, sustain: 0.5, release: 0.05 }, 0.15),
  sound('z_sawtooth curve 2', 'z_sawtooth', { note: 55, curve: 2 }, 0.06),
  sound('z_triangle', 'z_triangle', { freq: 330 }, 0.06),
  sound('z_triangle curve 0.5', 'z_triangle', { freq: 330, curve: 0.5 }, 0.06),
  sound('z_square', 'z_square', { note: 60 }, 0.06),
  sound('z_square ignores curve', 'z_square', { note: 60, curve: 3 }, 0.05),
  sound('z_tan', 'z_tan', { note: 55, curve: 2 }, 0.06),
  sound('z_noise', 'z_noise', { note: 40 }, 0.06),
  sound('zzfx is a triangle', 'zzfx', { note: 52 }, 0.06),
  sound('zzfx default', 'zzfx', {}, 0.05),
  sound('s decides the waveform, not the registered name', 'z_sine', { note: 57, s: 'z_sawtooth' }, 0.05),
  sound('s in capitals is no waveform', 'z_sine', { note: 57, s: 'Z_SINE' }, 0.05),
  sound('duration shorter than attack and decay', 'z_sine', { note: 57, attack: 0.03, decay: 0.03 }, 0.04),
  sound('sustain is the sustain volume', 'z_sine', { note: 57, sustain: 0.3, decay: 0.02, release: 0.03 }, 0.08),
  // ---- the controls of the generator
  sound('zrand', 'z_sine', { note: 57, zrand: 0.3 }, 0.05),
  sound('slide', 'z_sine', { note: 57, slide: 4 }, 0.06),
  sound('deltaSlide', 'z_sawtooth', { note: 57, slide: -2, deltaSlide: 30 }, 0.06),
  sound('pitchJump', 'z_sine', { note: 57, pitchJump: 200, pitchJumpTime: 0.02 }, 0.08),
  sound('znoise', 'z_sine', { note: 57, znoise: 0.5 }, 0.05),
  sound('znoise on the noise waveform', 'z_noise', { note: 45, znoise: 1 }, 0.05),
  sound('zmod', 'z_sine', { note: 57, zmod: 60 }, 0.06),
  sound('zcrush 0.05', 'z_sine', { note: 57, zcrush: 0.05 }, 0.04),
  sound('zcrush 0.5', 'z_sawtooth', { note: 57, zcrush: 0.5 }, 0.04),
  sound('zdelay', 'z_sine', { note: 57, zdelay: 0.01 }, 0.06),
  sound('zdelay long release', 'z_triangle', { note: 50, zdelay: 0.03, release: 0.05 }, 0.04),
  sound('lfo repeat with tremolo', 'z_sine', { note: 57, lfo: 0.015, tremolo: 0.6 }, 0.08),
  sound('lfo repeat resets the slide and the pitch jump', 'z_sine', { note: 57, lfo: 0.02, slide: 5, pitchJump: 100, pitchJumpTime: 0.01 }, 0.1),
  sound('tremolo without a repeat is ignored', 'z_sine', { note: 57, tremolo: 0.6 }, 0.04),
  // ---- the zzfx list
  sound('zzfx list', 'zzfx', { zzfx: [0.5, 0, 200, 0.01, 0.03, 0.04, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.7, 0.01, 0] }, 0.1),
  sound('zzfx list with the defaults of buildSamples (randomness 0.05)', 'zzfx', { zzfx: [1, undefined, 440, 0, 0.05, 0.05] }, 0.1, { seed: 11 }),
  sound('zzfx list of one', 'zzfx', { zzfx: [0.8] }, 0.1, { seed: 12 }),
  sound('zzfx list with everything', 'z_sine', { zzfx: [0.7, 0.1, 300, 0.005, 0.03, 0.05, 2, 1.5, 2, 1, 150, 0.01, 0.02, 0.2, 20, 0.1, 0.01, 0.6, 0.01, 0.4] }, 0.1, { seed: 13 }),
  // ---- buildSamples itself, with another sample rate
  build('44100 Hz', [0.5, 0, 440, 0, 0.05, 0.05, 0, 1], { sampleRate: 44100 }),
  build('volume 1 freq 440 sustain 0.1', [1, 0, 440, 0, 0.1, 0.1]),
  build('bit crush', [1, 0, 440, 0, 0.02, 0.01, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0.05]),
  build('negative bit crush', [1, 0, 440, 0, 0.02, 0.01, 0, 1, 0, 0, 0, 0, 0, 0, 0, -0.03]),
  build('negative curve', [1, 0, 440, 0, 0.02, 0.01, 1, -1]),
  build('shape beyond noise', [1, 0, 220, 0, 0.02, 0.01, 7, 1]),
];

// ------------------------------------------------------------------------------------------------------------------ run them
const b64 = (floats) => Buffer.from(floats.buffer, floats.byteOffset, floats.byteLength).toString('base64');
const results = [];
for (const c of cases) {
  globalThis.__ctx = makeContext(c.sampleRate);
  Math.random = splitMix(c.seed);
  const out = { kind: c.kind, name: c.name, sampleRate: c.sampleRate, seed: c.seed };
  if (c.kind === 'sound') {
    const value = { ...c.controls, duration: c.durationSec };
    let created;
    try {
      created = sounds[c.sound].onTrigger(0, value, () => {});
    } catch (e) {
      out.error = String(e.message);
    }
    // JSON has no undefined: an undefined entry of a list is written as the string "undefined" and the Kotlin tests read it back as undefined
    const written = Object.fromEntries(Object.entries(c.controls).map(([k, v]) => [k, Array.isArray(v) ? v.map((x) => (x === undefined ? 'undefined' : x)) : v]));
    Object.assign(out, { sound: c.sound, controls: written, durationSec: c.durationSec });
    if (created) {
      const buffer = created.node.buffer;
      out.length = buffer.length;
      out.samples = b64(buffer.getChannelData(0));
    }
  } else {
    const samples = Float32Array.from(fork.buildSamples(...c.args));
    out.args = c.args;
    out.length = samples.length;
    out.samples = b64(samples);
  }
  results.push(out);
}

fs.mkdirSync(outDir, { recursive: true });
const file = path.join(outDir, 'zzfx.json');
fs.writeFileSync(file, JSON.stringify({ commit: COMMIT, node: process.version, sounds: names, cases: results }));
fs.rmSync(scratch, { recursive: true, force: true });
console.log(`wrote ${results.length} cases to ${file}`);
