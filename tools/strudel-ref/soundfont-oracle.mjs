// Runs the soundfont code of the pinned Strudel (packages/soundfonts/fontloader.mjs: the text from `let defaultSoundfontUrl` to the end, sliced out of the file
// and evaluated as it is) in Node and writes what it does as the JSON that SoundfontOracleTest replays through Cosic's resolver:
//
//   node tools/strudel-ref/soundfont-oracle.mjs
//
// STRUDEL_DIR (default tools/strudel-ref/strudel next to this file) is the checkout made by fetch.sh. The imports of fontloader.mjs are replaced by what the Node
// run needs: the real noteToMidi, freqToMidi and getSoundIndex of @strudel/core, the real getParamADSR and getADSRValues of superdough/helpers.mjs and the real gm.mjs;
// `fetch` serves the recorded fonts of engine/src/test/resources/soundfont (any font name gets the font of the case), the AudioContext is a recorder whose
// decodeAudioData answers with the bytes it was given, and getPitchEnvelope and getVibratoOscillator record their arguments.
//
// It also (re)writes engine/src/test/resources/soundfont/edges_sf2_file.js, a font in the shape of the real ones whose zones cover the corner cases of the loop rule,
// the tuning and the zones that cannot play.
//
// Written: engine/src/test/resources/soundfont/oracle.json
//   fontChoice   what `s("gm_x").n(n)` fetches (the font file the registered sound picks) for every name of gm.mjs
//   zones        per recorded font and per `note`/`freq`: the zone of getFontPitch (its index in the font) and what getFontBufferSource sets on the buffer source
//   envelopes    the arguments of getParamADSR and the start and stop times of the registered sound
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.STRUDEL_DIR ?? path.join(here, 'strudel');
const resources = path.join(here, '../../engine/src/test/resources/soundfont');
const log = console.log;
console.log = () => {}; // @strudel/core announces itself on stdout
const core = await import(pathToFileURL(path.join(root, 'packages/core/index.mjs')));
const helpers = await import(pathToFileURL(path.join(root, 'packages/superdough/helpers.mjs')));
const gm = (await import(pathToFileURL(path.join(root, 'packages/soundfonts/gm.mjs')))).default;
console.log = log;

// ---- the edge font ---------------------------------------------------------------------------------------------------------------------------------------------

const b64 = (text) => Buffer.from(text, 'latin1').toString('base64');
const edgeZones = [
  // [keyRangeLow, keyRangeHigh, originalPitch, coarseTune, fineTune, loopStart, loopEnd, sampleRate, payload]
  [0, 9, 6000, 0, 0, 0, 100, 44100, 'file'], // loopStart 0: no loop
  [10, 19, 6000, 0, 0, 1, 100, 44100, 'file'], // loopStart 1: no loop (the rule is loopStart > 1)
  [20, 29, 6000, 0, 0, 2, 100, 44100, 'file'], // loopStart 2: loops
  [30, 39, 6000, 0, 0, 100, 100, 44100, 'file'], // loopStart == loopEnd: no loop
  [40, 49, 6000, 0, 0, 200, 100, 44100, 'file'], // loopStart > loopEnd: no loop
  [50, 59, 6000, 0, 0, -1, -2, 44100, 'file'], // the -1, -2 of fonts without a loop
  [60, 69, 7200, -12, 50, 1000, 2000, 22050, 'file'], // tuned, at another rate
  [70, 79, 6000, 3, -25, 3000, 4000, 48000, 'file'],
  [80, 89, 6000, 0, 0, 10, 20, 44100, 'sample'], // `sample` instead of `file`: the raw path of getBuffer returns nothing
  [90, 99, 6000, 0, 0, 10, 20, 44100, 'none'], // neither: no buffer
  [109, 100, 6000, 0, 0, 10, 20, 44100, 'file'], // keyRangeLow > keyRangeHigh: never matches
  [110, 127, 0, 0, 0, 10, 20, 44100, 'file'], // originalPitch 0: base detune 0
  [0, 127, 6000, 0, 0, 5, 50, 44100, 'file'], // catch-all behind the others
];
function edgeFontText() {
  const zones = edgeZones.map(([lo, hi, original, coarse, fine, loopStart, loopEnd, rate, payload], i) => {
    const lines = [`\t\t{`, `\t\t\tmidi:${i}`, `\t\t\t,originalPitch:${original}`, `\t\t\t,keyRangeLow:${lo}`, `\t\t\t,keyRangeHigh:${hi}`];
    lines.push(`\t\t\t,loopStart:${loopStart}`, `\t\t\t,loopEnd:${loopEnd}`, `\t\t\t,coarseTune:${coarse}`, `\t\t\t,fineTune:${fine}`, `\t\t\t,sampleRate:${rate}`, `\t\t\t,ahdsr:true`);
    if (payload === 'file') lines.push(`\t\t\t,file:'${b64(`zone ${i} of the edge font`)}'`);
    if (payload === 'sample') lines.push(`\t\t\t,sample:'${b64('raw pcm')}'`);
    lines.push(`\t\t\t,anchor:0.0125`, `\t\t\t//_tone.Edge_${i}`, `\t\t}`);
    return lines.join('\n');
  });
  return `console.log('load _tone_9999_Edges_sf2_file');\nvar _tone_9999_Edges_sf2_file={\n\tzones:[\n${zones.join('\n\t\t,')}\n\t]\n};\n`;
}
fs.writeFileSync(path.join(resources, 'edges_sf2_file.js'), edgeFontText());

// ---- the code under test ---------------------------------------------------------------------------------------------------------------------------------------

const source = fs.readFileSync(path.join(root, 'packages/soundfonts/fontloader.mjs'), 'utf8');
const start = source.indexOf('let defaultSoundfontUrl');
if (start < 0 || !source.includes('export function registerSoundfonts')) throw new Error('fontloader.mjs does not look like the pinned one');
const code = source.slice(start).replace(/^export /gm, '');

const idOf = (bytes) => `${bytes.length}:${Buffer.from(bytes.subarray(0, 8)).toString('hex')}`;

/** A fresh copy of the module (its caches start empty) with the mocks of this run. */
function instance(fontText) {
  const run = { fetched: [], gainEvents: [], started: [], stopped: [], pitchEnvelope: [], vibrato: [], sources: [] };
  const registered = new Map();
  const param = (events) =>
    new Proxy({ value: 1 }, {
      get: (target, key) => (key in target ? target[key] : (...args) => events.push([key, ...args])),
    });
  const context = {
    createBufferSource() {
      const src = {
        playbackRate: { value: 1 },
        detune: {},
        loop: false,
        loopStart: undefined,
        loopEnd: undefined,
        buffer: undefined,
        start: (t) => run.started.push(t),
        stop: (t) => run.stopped.push(t),
        connect: (node) => node,
      };
      run.sources.push(src);
      return src;
    },
    createGain() {
      const events = [];
      run.gainEvents.push(events);
      return { gain: param(events) };
    },
    createBuffer: (channels, length, sampleRate) => ({ channels, length: Math.trunc(length), sampleRate, getChannelData: () => new Float32Array(Math.trunc(length)) }),
    decodeAudioData: (arraybuffer, resolve) => resolve({ decoded: true, id: idOf(new Uint8Array(arraybuffer).subarray(0, arraybuffer.byteLength)) }),
  };
  const fetchMock = async (url) => {
    run.fetched.push(url);
    return { text: async () => fontText };
  };
  const factory = new Function(
    'noteToMidi', 'freqToMidi', 'getSoundIndex', 'getAudioContext', 'registerSound', 'getParamADSR', 'getADSRValues', 'getPitchEnvelope', 'getVibratoOscillator',
    'onceEnded', 'releaseAudioNode', 'gm', 'fetch',
    `${code}\nreturn { setSoundfontUrl, getFontBufferSource, getFontPitch, registerSoundfonts };`,
  );
  const api = factory(
    core.noteToMidi, core.freqToMidi, core.getSoundIndex, () => context, (name, fn, data) => registered.set(name, { fn, data }), helpers.getParamADSR, helpers.getADSRValues,
    (...args) => run.pitchEnvelope.push(args.slice(1)), (...args) => { run.vibrato.push(args.slice(1)); return undefined; }, () => {}, () => {}, gm, fetchMock,
  );
  api.registerSoundfonts();
  return { api, run, registered, context };
}

// ---- encoding of what JSON cannot say ----------------------------------------------------------------------------------------------------------------------------

const enc = (x) => {
  if (x === undefined) return { $: 'undefined' };
  if (typeof x === 'number') {
    if (Number.isNaN(x)) return { $: 'NaN' };
    if (x === Infinity) return { $: 'Infinity' };
    if (x === -Infinity) return { $: '-Infinity' };
    if (Object.is(x, -0)) return { $: '-0' };
  }
  return x;
};
const encValue = (value) => Object.fromEntries(Object.entries(value).map(([k, v]) => [k, enc(v)]));

// ---- fontChoice -------------------------------------------------------------------------------------------------------------------------------------------------

const chaosText = fs.readFileSync(path.join(resources, '0160_Chaos_sf2_file.js'), 'utf8');
const fontText = (name) => fs.readFileSync(path.join(resources, `${name}.js`), 'utf8');

const fontChoice = [];
const choiceNs = [undefined, null, 0, 1, 2, 7, 31, 32, 33, -1, -2, -33, 1.5, 2.5, 2.4999, 0.5, -0.5, -1.5, 1e9, NaN, Infinity, '3', 'x', true];
const compactNs = [undefined, 0, 1, 7, -1, 2.5, 33];
for (const name of Object.keys(gm)) {
  for (const n of ['gm_piano', 'gm_flute', 'gm_epiano1'].includes(name) ? choiceNs : compactNs) {
    const { registered, run } = instance(chaosText);
    const value = { duration: 1, note: 'c3' };
    if (n !== undefined) value.n = n;
    const entry = registered.get(name);
    let result;
    try {
      await entry.fn(0, { ...value }, () => {});
      result = { ok: true, font: path.basename(run.fetched[0], '.js') };
    } catch (error) {
      result = { ok: false, error: String(error.message) };
    }
    fontChoice.push({ name, n: enc(n), ...result });
  }
}

// ---- the soundfont URL --------------------------------------------------------------------------------------------------------------------------------------------

const urlCases = [];
for (const base of [undefined, 'https://example.test/fonts', 'http://192.168.1.5:5432/a b/sound']) {
  const { api, registered, run } = instance(chaosText);
  if (base !== undefined) api.setSoundfontUrl(base);
  await registered.get('gm_flute').fn(0, { duration: 1, n: 3 }, () => {});
  urlCases.push({ base: base ?? null, url: run.fetched[0] });
}

// ---- zones ------------------------------------------------------------------------------------------------------------------------------------------------------

const zoneFonts = ['0160_Chaos_sf2_file', '1152_FluidR3_GM_sf2_file', 'edges_sf2_file'];
const notes = [
  ...Array.from({ length: 131 }, (_, i) => i - 1), // -1 to 129
  0.5, 26.5, 27.5, 28, 28.5, 53.99, 54.0001, 54.5, 54.9999, 55.0001, 55.5, 72.5, 72.9999, 73.0001, 73.5, 83.5, 84.5, 96.5, 97.5, 107.5, 108.5, 127.5, 128.5, 1e9, -1e9, NaN, Infinity, -Infinity,
  'c3', 'C3', 'eb4', 'Eb4', 'fs2', 'bf5', 'c', 'e', 'c#4', 'cs4', 'C-1', 'g9', 'a0', 'b#2', 'cf4', 'Cb-1', 'x', '', 'h3', 'c3 e3', '60', ' c3',
  undefined, null, true, false, {}, [60],
];
const freqs = [440, 261.6255653005986, 27.5, 8.175798915643707, 4186.009044809578, 12543.853951415975, 1, 0.001, 22050, 1e9, 0, -440, NaN, Infinity, '440', 'abc', '', 5e-324];

function describeSource(src) {
  return { rate: enc(src.playbackRate.value), loop: src.loop, loopStart: enc(src.loopStart), loopEnd: enc(src.loopEnd) };
}

const zones = [];
for (const font of zoneFonts) {
  const text = fontText(font);
  const preset = new Function('return {' + text.split('={')[1])().zones;
  const zoneIndex = (zone) => preset.findIndex((z) => z.keyRangeLow === zone.keyRangeLow && z.keyRangeHigh === zone.keyRangeHigh && z.midi === zone.midi);
  const inputs = [
    ...notes.map((note) => ({ note })),
    ...freqs.map((freq) => ({ freq })),
    { freq: 440, note: 'c3' },
    { freq: 0, note: 'c5' },
    { freq: 0, note: null },
    {},
  ];
  for (const input of inputs) {
    const { api, context, run } = instance(text);
    const value = { ...input };
    let result;
    try {
      const src = await api.getFontBufferSource(font, value, context);
      let zone = undefined;
      const midi = value.freq ? core.freqToMidi(value.freq) : typeof value.note === 'string' ? core.noteToMidi(value.note) : value.note ?? core.noteToMidi('c3');
      try { zone = (await api.getFontPitch(font, midi, context)).zone; } catch {}
      result = { ok: true, zone: zone === undefined ? null : zoneIndex(zone), ...describeSource(src) };
    } catch (error) {
      result = { ok: false, error: String(error.message) };
    }
    zones.push({ font, value: encValue(input), ...result });
  }
}

// ---- envelopes and the times of the registered sound ---------------------------------------------------------------------------------------------

const envelopeInputs = [
  { duration: 1 },
  { duration: 0.25, attack: 0.1 },
  { duration: 0.25, decay: 0.1 },
  { duration: 0.25, attack: 0.05, decay: 0.1 },
  { duration: 0.25, attack: 0.05, decay: 0.1, sustain: 0.5 },
  { duration: 0.25, sustain: 0.2 },
  { duration: 0.25, release: 0.5 },
  { duration: 0.25, release: 0 },
  { duration: 0.25, attack: 0, decay: 0, sustain: 0, release: 0 },
  { duration: 0.5, attack: 0.6, decay: 0.2, sustain: 1, release: 0.1 },
  { duration: 0.5, attack: 0.3, decay: 0.3, sustain: 0.4, release: 0.2 },
  { duration: 0, attack: 0.3 },
  { duration: 2, attack: 5, decay: 5 },
  { duration: 0.25, attack: 2, sustain: 2 },
  { duration: 0.25, attack: -1, decay: -1, sustain: -1, release: -1 },
  { duration: 0.125, attack: null, decay: 0.2 },
];
const envelopes = [];
for (const input of envelopeInputs) {
  for (const time of [0, 1.5, 123.456]) {
    const { registered, run } = instance(chaosText);
    const value = { note: 'c3', ...input };
    const handle = await registered.get('gm_piano').fn(time, value, () => {});
    envelopes.push({
      time,
      value: encValue(input),
      adsr: run.gainEvents[0],
      adsrNumbers: null,
      started: run.started.map(enc),
      stopped: run.stopped.map(enc),
      hasNode: !!handle.node,
    });
  }
}
// the arguments getParamADSR got: the recorded events say them (setValueAtTime(min, begin), then the ramps), and the peak is 0.3
for (const e of envelopes) e.adsr = e.adsr.map(([method, ...args]) => [method, ...args.map(enc)]);

fs.writeFileSync(
  path.join(resources, 'oracle.json'),
  JSON.stringify({ commit: '84ef32eec8074f90c6690ab8d6a6c204f508b182', fontChoice, urls: urlCases, zones, envelopes }, null, 0) + '\n',
);
log(`fontChoice ${fontChoice.length}, urls ${urlCases.length}, zones ${zones.length}, envelopes ${envelopes.length}`);
