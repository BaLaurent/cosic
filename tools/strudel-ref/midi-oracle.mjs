// Golden vectors for the MIDI feature of the engine (engine/src/main/kotlin/cosic/io/midi/*): what Strudel's own @strudel/midi sends.
//
//   node tools/strudel-ref/midi-oracle.mjs                      writes engine/src/test/resources/midi/vectors.json
//   node tools/strudel-ref/midi-oracle.mjs --cc-names-kotlin    writes engine/src/main/kotlin/cosic/io/midi/MidiCcNames.kt
//
// The real packages/midi/{midi,util,input}.mjs of the pinned checkout run here, with the real webmidi 3.1.12 and the real
// @strudel/core they import. Only what a browser page provides is faked: `navigator.requestMIDIAccess` answers with ports that
// record what is sent to them (data bytes and the time stamp WebMidi gave), @strudel/webaudio's clock bridge and two superdough
// helpers are stand-ins (midi-oracle-hooks.mjs), `document`/`window`/`localStorage` are minimal, and `performance.now` runs
// forward so that the logger's one-second de-duplication never hides a line.
//
// Output (all values that JSON cannot hold are `{"$": "undefined" | "NaN" | "Infinity" | "-Infinity" | "-0" | "pattern"}`):
//   outputs        the output ports of the encode cases
//   ccNames        WebMidi's two control change name tables (`getCcNumberByName` looks in `modern` and then in `legacy`)
//   devices        `getDevice(spec, names)` of midi/util.mjs
//   maps           what the encode cases of `maps: "defined"` ran after (`defaultmidimap`, `midimaps`)
//   encode         one `.midi(port, options)` hap each: the messages sent (bytes, device, time relative to the hap's time), the log
//                  lines, and the error the handler threw (messages sent before it threw stay in `packets`)
//   configErrors   `.midi(...)` calls that throw when they are made
//   midin, midikeys   the input side: messages in, what `midin`/`midikeys` answer, what is stored and sent back
// Used by MidiVectorsTest, MidiInTest and MidiKeyboardTest.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import { register } from 'node:module';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.COSIC_ROOT ?? path.resolve(here, '../..');
const checkout = path.join(here, 'strudel');
const packages = path.join(checkout, 'packages');
const webmidiFile = path.join(checkout, 'node_modules/.pnpm/webmidi@3.1.12/node_modules/webmidi/dist/esm/webmidi.esm.js');
const output = path.join(root, 'engine/src/test/resources/midi/vectors.json');
const ccNamesKotlin = path.join(root, 'engine/src/main/kotlin/cosic/io/midi/MidiCcNames.kt');
const importFile = (file) => import(pathToFileURL(file).href);

const realLog = console.log.bind(console);
const progress = (message) => process.stderr.write(message + '\n');

/** The time (seconds of the engine clock) every hap is scheduled at; the stand-in clock bridge maps it to TARGET * 1000 ms. */
const TARGET = 10;

// ---- JSON encoding of JavaScript values -----------------------------------------------------------------------------------

let corePromise;
const core = () => (corePromise ??= importFile(path.join(packages, 'core/index.mjs')));

const encode = (v) => {
  if (v === undefined) return { $: 'undefined' };
  if (typeof v === 'number') {
    if (Number.isNaN(v)) return { $: 'NaN' };
    if (v === Infinity) return { $: 'Infinity' };
    if (v === -Infinity) return { $: '-Infinity' };
    if (Object.is(v, -0)) return { $: '-0' };
    return v;
  }
  if (Array.isArray(v)) return v.map(encode);
  if (v && typeof v === 'object') {
    if (v.__pattern) return { $: 'pattern' };
    return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, encode(x)]));
  }
  return v;
};

const decode = async (j) => {
  if (j && typeof j === 'object' && !Array.isArray(j)) {
    if ('$' in j) {
      switch (j.$) {
        case 'undefined': return undefined;
        case 'NaN': return NaN;
        case 'Infinity': return Infinity;
        case '-Infinity': return -Infinity;
        case '-0': return -0;
        case 'pattern': return (await core()).pure(1);
      }
    }
    const out = {};
    for (const [k, x] of Object.entries(j)) out[k] = await decode(x);
    return out;
  }
  if (Array.isArray(j)) return Promise.all(j.map(decode));
  return j;
};

// ---- the browser the real code runs in --------------------------------------------------------------------------------------

function installFakes({ inputs, outputs, store = new Map() }) {
  register('./midi-oracle-hooks.mjs', import.meta.url);
  const sent = [];
  const logs = [];
  const timers = [];
  let now = 0;
  const port = (type, name) => {
    const p = {
      id: `${type}-${name}`, name, manufacturer: '', type, state: 'connected', connection: 'closed', onstatechange: null, onmidimessage: null,
      open: async () => p, close: async () => p, clear() {}, addEventListener() {}, removeEventListener() {},
    };
    if (type === 'output') p.send = (data, ts) => sent.push({ name, data: Array.from(data), ts });
    return p;
  };
  const nativeInputs = inputs.map((name) => port('input', name));
  const nativeOutputs = outputs.map((name) => port('output', name));
  const access = {
    inputs: new Map(nativeInputs.map((p) => [p.id, p])), outputs: new Map(nativeOutputs.map((p) => [p.id, p])),
    sysexEnabled: true, onstatechange: null,
  };
  globalThis.document = {
    addEventListener() {}, body: {},
    dispatchEvent(e) {
      logs.push(e.detail.message);
      return true;
    },
  };
  globalThis.window = { addEventListener() {}, navigator: globalThis.navigator };
  Object.defineProperty(globalThis.navigator, 'requestMIDIAccess', { value: async () => access, configurable: true });
  Object.defineProperty(globalThis, 'localStorage', {
    value: { getItem: (k) => (store.has(k) ? store.get(k) : null), setItem: (k, v) => void store.set(k, String(v)) },
    configurable: true, writable: true,
  });
  globalThis.__clockBridge = { getPerformanceTime: (t) => t * 1000, getAudioContextTime: (ms) => ms / 1000 };
  globalThis.__audioContext = { currentTime: 0 };
  // the logger de-duplicates identical lines within a second of performance.now(): make every call a long time later
  Object.defineProperty(performance, 'now', { value: () => (now += 5000), configurable: true, writable: true });
  console.log = () => {};
  const realSetTimeout = globalThis.setTimeout;
  globalThis.setTimeout = (fn, ms, ...args) => {
    timers.push({ fn: () => fn(...args), ms });
    return timers.length;
  };
  const tick = () => new Promise((resolve) => setImmediate(resolve));
  const flushTimers = async () => {
    while (timers.length) timers.shift().fn();
    for (let i = 0; i < 5; i++) await tick();
  };
  return { sent, logs, store, nativeInputs, nativeOutputs, flushTimers, tick, realSetTimeout };
}

const packet = (s, outputs) => ({ device: outputs.indexOf(s.name), bytes: s.data, offsetMs: s.ts === false ? 'immediate' : s.ts - TARGET * 1000 });

// ---- the encode cases ---------------------------------------------------------------------------------------------------------

const OUTPUTS = ['Arturia KeyStep 32', 'IAC Driver Bus 1', 'Midi Through Port-0'];

const MAPS = {
  default: { lpf: 74, hpf: { ccn: 75, min: 0, max: 20000, exp: 0.5 }, room: { ccn: 91 } },
  named: {
    mymap: { lpf: 74, gain: 7, pan: { ccn: 10, min: -1, max: 1 }, speed: 0 },
    weird: {
      lpf: { min: 0 }, // no ccn
      hpf: { ccn: 75, min: 5, max: 5 }, // min === max
      room: { ccn: 'volumecoarse', exp: 2 },
      delay: 'abc', // a string mapping
      size: { ccn: 12, min: 1, max: 0 }, // inverted range
      shape: null, // a falsy mapping is ignored
    },
  },
};

const NOTES = ['c4', 'c', 'eb3', 'fs2', 'C#5', 'a0', 'g9', 'a9', 60, 0, 127, 36.0, 128, -1, 60.5, '', null, 'xyz', true, NaN, 1e21, Infinity, 'c-1'];
const VELOCITIES = [0.9, 0, 1, 0.5, 1.8, -0.1, NaN, null, undefined, '0.5', Infinity, 0.3333, 0.004, 0.999];
const CHANNELS = [1, 2, 16, 0, 17, [1, 2], [2, 2, 3], [17, 1], 'all', 'none', null, undefined, 3.7, '4', [], NaN, [1, 'all'], [[3]], true, [1, 99, 16]];
const CCNS = [74, 0, 127, 128, -1, 74.5, '74', 'volumecoarse', 'modulationwheelcoarse', 'brightness', 'soundattacktime', 'damperpedal', 'nope', 'constructor', '__proto__', NaN, null, true, [74], 'cc'];
const CCVS = [0.5, 0, 1, 1.5, -0.1, NaN, '0.5', null, Infinity, 0.999, 0.004, 0.4960629921259842];
const PROGS = [10, 0, 127, 128, -1, 5.5, NaN, '5', null, 1e21, 63];
const BENDS = [0.4, 0, -1, 1, 1.5, -1.0001, NaN, null, '0.5', 0.123456, -0.5, 0.9999, -0.99999];
const TOUCHES = [0.5, 0, 1, 2, -1, NaN, '1', null, 0.123];
const SYSEX_IDS = [119, 0, 255, 256, -1, 1.5, [0, 32, 50], [], [1, 256], [1, 2.5], 'a', null, NaN, [127], 125];
const SYSEX_DATA = [[1, 2, 3, 4], [], [255], [256], [-1], [1, 2.5], 'abc', null, [1, '2'], [0x7f, 0x80], [0, 127]];
const NRPNNS = [[1, 8], [0, 0], [127, 127], [128, 1], [1], [1, 2, 3], 5, 'x', null, [1.5, 2], [256, 0], [-1, 5], [16383 >> 7, 3]];
const NRPVS = [123, 0, 127, 128, [1, 2], [127, 127], [1, 2, 3], '5', null, -1, 5.5, NaN, 255, 256];
const COMMANDS = ['clock', 'midiClock', 'start', 'stop', 'continue', 'other', ['progNum', 5], ['progNum', 200], ['cc', 64], ['cc'], ['sysex', 119, [1, 2]], ['sysex', 1], ['sysex', [0, 32, 50], [9]], [], null, 5];
const MIDIMAP_NAMES = ['default', 'mymap', 'nomap', 'weird', 5, null, ''];
const HAP_PORTS = [undefined, 0, 1, 2, 3, -1, 1.5, 'Arturia', 'IAC', 'Through', 'nope', '', null, true, NaN, [1]];
const WHOLES = [[[1, 1], [2, 1]], [[0, 1], [1, 1]], [[1, 2], [3, 4]], [[5, 3], [11, 6]], [[3, 1], [7, 2]], [[0, 1], [1, 3]]];
const CPSS = [0.5, 1, 1.25, 2, 0.4];
const CONFIGS = [
  [undefined, undefined], ['IAC', undefined], [0, undefined], [2, undefined], ['nope', undefined], [5, undefined],
  [undefined, { isController: true }], [undefined, { noteOffsetMs: 10 }], [undefined, { noteOffsetMs: 5000 }], [undefined, { midichannel: 3 }],
  [undefined, { midichannel: [1, 2] }], [undefined, { velocity: 0.5 }], [undefined, { velocity: 2 }], [undefined, { gain: 0.5 }],
  [undefined, { midimap: 'mymap' }], [undefined, { midiport: 'IAC' }], [undefined, { isController: 'yes' }], [undefined, { noteOffsetMs: '2' }],
  [undefined, { noteOffsetMs: undefined }], [undefined, { velocity: undefined }], [undefined, { gain: null }], [undefined, { midichannel: 0 }],
  [{ port: 'IAC', isController: true }, undefined], [{ port: 'Arturia', velocity: 0.7 }, { gain: 0.5 }], [{}, undefined], ['Through', { midichannel: 9 }],
  [undefined, { velocity: '0.25' }], [undefined, { noteOffsetMs: null }], [undefined, { midimap: 'weird' }], [undefined, { midimap: 'default' }],
];
const HAND_EXTRA_KEYS = [{ cutoff: 0.5 }, { cutoff: 0.5, hcutoff: 10000 }, { lpf: 0.5 }, { room: 0.25, gain: 0.5 }, { gain: 0.5, pan: 0.25 }, { speed: 0.5 }, { delay: 0.3 }, { size: 0.5 }, { shape: 0.5 }, { cutoff: 'x' }, { cutoff: undefined }, { cutoff: null }, { s: 'bd' }];

let seed = 0x4d1d1;
const next = () => {
  seed = (Math.imul(seed, 1103515245) + 12345) >>> 0;
  return seed / 4294967296;
};
const pick = (list) => list[Math.floor(next() * list.length)];

function randomCase(index) {
  const value = {};
  const features = [
    () => (value.note = pick(NOTES)),
    () => ((value.ccn = pick(CCNS)), (value.ccv = pick(CCVS))),
    () => (value.progNum = pick(PROGS)),
    () => ((value.sysexid = pick(SYSEX_IDS)), (value.sysexdata = pick(SYSEX_DATA))),
    () => ((value.nrpnn = pick(NRPNNS)), (value.nrpv = pick(NRPVS))),
    () => (value.midibend = pick(BENDS)),
    () => (value.miditouch = pick(TOUCHES)),
    () => (value.midicmd = pick(COMMANDS)),
    () => Object.assign(value, pick(HAND_EXTRA_KEYS), next() < 0.5 ? { midimap: pick(MIDIMAP_NAMES) } : {}),
    () => (value.ccn = pick(CCNS)),
    () => (value.ccv = pick(CCVS)),
    () => (value.sysexid = pick(SYSEX_IDS)),
    () => (value.nrpv = pick(NRPVS)),
  ];
  const count = 1 + Math.floor(next() * 3);
  for (let i = 0; i < count; i++) pick(features)();
  if (next() < 0.35) value.midichan = pick(CHANNELS);
  if (next() < 0.25) value.velocity = pick(VELOCITIES);
  if (next() < 0.2) value.gain = pick(VELOCITIES);
  if (next() < 0.2) value.midiport = pick(HAP_PORTS);
  if (next() < 0.1) value.midimap = pick(MIDIMAP_NAMES);
  const [port, options] = next() < 0.6 ? [undefined, undefined] : pick(CONFIGS);
  return { name: `random ${index}`, maps: next() < 0.5 ? 'none' : 'defined', port, options, whole: pick(WHOLES), cps: pick(CPSS), value };
}

const one = (name, value, extra = {}) => ({ name, maps: 'none', port: undefined, options: undefined, whole: [[1, 1], [2, 1]], cps: 0.5, value, ...extra });
const atStart = [[[0, 1], [1, 1]]][0];

function handCases() {
  return [
    one('note c4 at cycle 0 (note, then Start)', { note: 'c4', midichan: 1 }, { whole: atStart }),
    one('note c4', { note: 'c4', midichan: 1 }),
    one('note c4 default channel', { note: 'c4' }),
    one('note number 60', { note: 60 }),
    one('note c (default octave 3)', { note: 'c' }),
    one('note eb3 and fs2', { note: 'eb3' }),
    one('note fs2', { note: 'fs2' }),
    one('note velocity from gain and velocity', { note: 'c4', gain: 0.5, velocity: 0.8 }),
    one('note config velocity', { note: 'c4' }, { options: { velocity: 0.5 } }),
    one('note velocity 1', { note: 'c4', velocity: 1 }),
    one('note velocity 1.8', { note: 'c4', velocity: 1.8 }),
    one('note 128', { note: 128 }),
    one('note empty', { note: '' }),
    one('note null', { note: null }),
    one('note 60.5', { note: 60.5 }),
    one('note not a note', { note: 'xyz' }),
    one('note NaN velocity', { note: 'c4', velocity: NaN }),
    one('note on channels 1 and 2', { note: 'c4', midichan: [1, 2] }),
    one('note on channel 0', { note: 'c4', midichan: 0 }),
    one('note on channel 17', { note: 'c4', midichan: 17 }),
    one('note on channel null (all sixteen)', { note: 'c4', midichan: null }),
    one('note on channel all', { note: 'c4', midichan: 'all' }),
    one('note on channel none', { note: 'c4', midichan: 'none' }),
    one('note controller', { note: 'c4' }, { options: { isController: true } }),
    one('note short hap', { note: 'c4', clip: 0.001 }),
    one('note with duration control', { note: 'c4', duration: 0.25 }),
    one('note with clip', { note: 'c4', clip: 0.5 }),
    one('note offset larger than half the hap', { note: 'c4' }, { options: { noteOffsetMs: 5000 } }),
    one('note at 2 cps', { note: 'c4' }, { cps: 2 }),
    one('ccn 74 ccv .5', { ccn: 74, ccv: 0.5 }),
    one('ccn name', { ccn: 'volumecoarse', ccv: 1 }),
    one('ccn legacy name', { ccn: 'soundattacktime', ccv: 1 }),
    one('ccn unknown name', { ccn: 'nope', ccv: 1 }),
    one('ccn out of range', { ccn: 128, ccv: 1 }),
    one('ccv 1.5', { ccn: 74, ccv: 1.5 }),
    one('ccv NaN', { ccn: 74, ccv: NaN }),
    one('ccn only', { ccn: 74 }),
    one('ccv only', { ccv: 0.5 }),
    one('progNum 10', { progNum: 10 }),
    one('progNum 128', { progNum: 128 }),
    one('progNum 5.5', { progNum: 5.5 }),
    one('sysex one byte id', { sysexid: 119, sysexdata: [1, 2, 3, 4] }),
    one('sysex three byte id', { sysexid: [0, 32, 50], sysexdata: [1] }),
    one('sysex bad data', { sysexid: 119, sysexdata: [1, 256] }),
    one('sysex data not an array', { sysexid: 119, sysexdata: 'abc' }),
    one('sysex bad id', { sysexid: 256, sysexdata: [] }),
    one('nrpn', { nrpnn: [1, 8], nrpv: 123 }),
    one('nrpn two value bytes', { nrpnn: [1, 8], nrpv: [1, 2] }),
    one('nrpn out of range', { nrpnn: [128, 1], nrpv: 5 }),
    one('nrpn number as parameter', { nrpnn: 5, nrpv: 7 }),
    one('midibend .4', { midibend: 0.4 }),
    one('midibend 0', { midibend: 0 }),
    one('midibend -1', { midibend: -1 }),
    one('midibend 1', { midibend: 1 }),
    one('midibend 1.5', { midibend: 1.5 }),
    one('miditouch .5', { miditouch: 0.5 }),
    one('miditouch 2', { miditouch: 2 }),
    one('polyTouch is never used', { polyTouch: 0.5 }),
    one('midicmd clock', { midicmd: 'clock' }),
    one('midicmd clock at cycle 0 (Start, then clock)', { midicmd: 'clock' }, { whole: atStart }),
    one('midicmd midiClock', { midicmd: 'midiClock' }),
    one('midicmd start', { midicmd: 'start' }),
    one('midicmd stop', { midicmd: 'stop' }),
    one('midicmd continue', { midicmd: 'continue' }),
    one('midicmd progNum', { midicmd: ['progNum', 5] }),
    one('midicmd cc never works', { midicmd: ['cc', 64] }),
    one('midicmd sysex', { midicmd: ['sysex', 119, [1, 2]] }),
    one('midicmd unknown', { midicmd: 'other' }),
    one('any value without a midi control', { s: 'bd' }),
    one('value is a string', 'c4'),
    one('value is a number', 60),
    one('value is null', null),
    one('value is an array', ['c4']),
    one('value is true', true),
    one('everything in one hap', { note: 'c4', progNum: 3, sysexid: 1, sysexdata: [2], ccn: 7, ccv: 1, nrpnn: [1, 2], nrpv: 3, midibend: 0.5, miditouch: 0.5, midicmd: 'clock' }, { whole: atStart }),
    one('an error in the middle keeps what was sent', { note: 'c4', progNum: 200, midibend: 0.5 }),
    one('port by index', { note: 'c4' }, { port: 0 }),
    one('port by name', { note: 'c4' }, { port: 'Through' }),
    one('port in the hap', { note: 'c4', midiport: 'Arturia' }),
    one('port not found', { note: 'c4' }, { port: 'nope' }),
    one('port index out of range', { note: 'c4' }, { port: 7 }),
    one('port as an option object', { note: 'c4' }, { port: { port: 'Through', isController: false } }),
    // midimaps
    one('midimap default, lpf mapped', { note: 'c4', cutoff: 0.5 }, { maps: 'defined' }),
    one('midimap default, ranges and exponent', { cutoff: 0.5, hcutoff: 10000, room: 0.25 }, { maps: 'defined' }),
    one('midimap named', { note: 'c4', cutoff: 0.5, gain: 0.5, pan: 0.5 }, { maps: 'defined', options: { midimap: 'mymap' } }),
    one('midimap named in the hap', { note: 'c4', cutoff: 0.5, midimap: 'mymap' }, { maps: 'defined' }),
    one('midimap not found', { note: 'c4', midimap: 'nomap' }, { maps: 'defined' }),
    one('midimap not found without maps', { note: 'c4', midimap: 'nomap' }),
    one('midimap alias key quirk', { lpf: 0.5 }, { maps: 'defined' }),
    one('midimap weird: no ccn', { cutoff: 0.5 }, { maps: 'defined', options: { midimap: 'weird' } }),
    one('midimap weird: min equals max', { hcutoff: 0.5 }, { maps: 'defined', options: { midimap: 'weird' } }),
    one('midimap weird: name and exponent', { room: 0.5 }, { maps: 'defined', options: { midimap: 'weird' } }),
    one('midimap weird: string mapping', { delay: 0.5 }, { maps: 'defined', options: { midimap: 'weird' } }),
    one('midimap weird: inverted range', { roomsize: 0.25 }, { maps: 'defined', options: { midimap: 'weird' } }),
    one('midimap weird: falsy mapping', { shape: 0.5 }, { maps: 'defined', options: { midimap: 'weird' } }),
    one('midimap value not a number', { cutoff: 'x' }, { maps: 'defined' }),
    one('midimap value undefined', { cutoff: undefined }, { maps: 'defined' }),
    one('midimap value out of range', { cutoff: 5 }, { maps: 'defined' }),
    one('midimap then note and cc', { cutoff: 0.5, note: 'c4', ccn: 7, ccv: 0.5 }, { maps: 'defined' }),
  ];
}

const CONFIG_ERRORS = [
  { name: 'a pattern as the port', port: { __pattern: true }, options: undefined },
  { name: 'null as the port', port: null, options: undefined },
];

async function encodeSection() {
  const fakes = installFakes({ inputs: [], outputs: OUTPUTS });
  const { Pattern, Hap, TimeSpan, Fraction } = await core();
  const midi = await importFile(path.join(packages, 'midi/midi.mjs'));
  const util = await importFile(path.join(packages, 'midi/util.mjs'));
  await midi.enableWebMidi();

  const runCase = async (c) => {
    fakes.sent.length = 0;
    fakes.logs.length = 0;
    const [[bn, bd], [en, ed]] = c.whole;
    const whole = new TimeSpan(Fraction(bn).div(bd), Fraction(en).div(ed));
    const hap = new Hap(whole, whole, await decode(c.value), {});
    let error = null;
    try {
      const pattern = new Pattern(() => [hap]).midi(await decode(c.port), await decode(c.options));
      const scheduled = pattern.queryArc(0, 1)[0];
      scheduled.context.onSchedule(scheduled, TARGET, c.cps);
    } catch (e) {
      error = e.message;
    }
    return { packets: fakes.sent.map((s) => packet(s, OUTPUTS)), logs: [...fakes.logs], error };
  };

  const record = async (c) => ({
    name: c.name, maps: c.maps, port: encode(c.port), options: encode(c.options), whole: c.whole, cps: c.cps, value: encode(c.value),
    expected: await runCase(c),
  });

  const cases = [...handCases(), ...Array.from({ length: 700 }, (_, i) => randomCase(i))];
  const none = [];
  for (const c of cases.filter((x) => x.maps === 'none')) none.push(await record(c));

  midi.defaultmidimap(MAPS.default);
  await midi.midimaps(MAPS.named);
  const defined = [];
  for (const c of cases.filter((x) => x.maps === 'defined')) defined.push(await record(c));

  const configErrors = [];
  for (const c of CONFIG_ERRORS) {
    let error = null;
    try {
      new Pattern(() => []).midi(await decode(encode(c.port)), c.options);
    } catch (e) {
      error = e.message;
    }
    configErrors.push({ name: c.name, port: encode(c.port), options: encode(c.options), error });
  }

  // getDevice
  const deviceLists = [OUTPUTS, ['IAC Driver Bus 1'], ['Arturia KeyStep 32', 'IAC Driver Bus 1', 'IAC Driver Bus 2'], [], ['A', 'B']];
  const specs = [undefined, null, 0, 1, 2, 3, -1, 1.5, NaN, 'IAC', 'Arturia', 'Through', 'nope', '', true, false, [0], { a: 1 }];
  const devices = [];
  for (const names of deviceLists) {
    for (const spec of specs) {
      let expected;
      try {
        const found = util.getDevice(spec, names.map((name) => ({ name })));
        expected = { index: found === undefined ? null : names.findIndex((n) => n === found.name) };
      } catch (e) {
        expected = { error: e.message };
      }
      devices.push({ spec: encode(spec), names, expected });
    }
  }

  // the control change name tables of WebMidi
  const webmidi = await importFile(webmidiFile);
  const modern = webmidi.Enumerations.CONTROL_CHANGE_MESSAGES.map((m) => [m.number, m.name]);
  const legacy = webmidi.Enumerations.MIDI_CONTROL_CHANGE_MESSAGES;

  return { outputs: OUTPUTS, maps: MAPS, encode: [...none, ...defined], configErrors, devices, ccNames: { modern, legacy } };
}

// ---- the input side (a child process: WebMidi is a singleton and the inputs are fixed once it is enabled) --------------------------------

async function inputSection() {
  const inputs = ['In A', 'In B', 'In C', 'In D', 'In E', 'In F'];
  const outputs = ['In A', 'In C', 'Other Out'];
  const store = new Map();
  // what an earlier session left in storage
  store.set('strudel-midin-In B-chanall', JSON.stringify({ 7: 0.5, 10: 1 }));
  store.set('strudel-midin-In B-chan1', JSON.stringify({ 7: 0.25 }));
  store.set('strudel-midin-In C-chan1', JSON.stringify({ 7: 1, 10: 0.5, 74: 0.2 }));
  store.set('strudel-midin-In C-chan3', JSON.stringify({ 1: 1 }));
  store.set('strudel-midin-In C-chanall', JSON.stringify({ 7: 0.5 }));
  const initialStore = Object.fromEntries(store);
  const fakes = installFakes({ inputs, outputs, store });
  const core_ = await core();
  (await importFile(path.join(packages, 'mini/index.mjs'))).miniAllStrings(); // makes strings patterns, which `kb("0.5 1")` needs
  const midi = await importFile(path.join(packages, 'midi/midi.mjs'));
  const state = await importFile(path.join(packages, 'core/schedulerState.mjs'));
  const { TimeSpan, Fraction } = core_;
  const native = (name) => fakes.nativeInputs[inputs.indexOf(name)];
  const feed = (name, bytes) => native(name).onmidimessage({ data: Uint8Array.from(bytes), timeStamp: 0 });
  const value = (pattern) => pattern.queryArc(0, 1)[0].value;

  const scenarios = [];
  const midinScenario = async (name, input, { messages = [], queries = [], beforeListening = [] }) => {
    fakes.logs.length = 0;
    fakes.sent.length = 0;
    let error = null;
    let cc;
    try {
      cc = await midi.midin(input);
    } catch (e) {
      error = e.message;
    }
    const logs = [...fakes.logs];
    const out = { name, input: encode(input), logs, error, before: [], values: [], sent: [], storage: {} };
    if (error) return out;
    const query = async (q) => {
      try {
        const p = q.chan === undefined ? cc(q.cc) : cc(q.cc, q.chan);
        return encode(value(p));
      } catch (e) {
        return { error: e.message };
      }
    };
    for (const q of queries.filter((x) => x.when === 'before')) out.before.push({ ...q, value: await query(q) });
    for (const m of beforeListening) if (typeof input === 'string' || typeof input === 'number') feed(inputs[typeof input === 'number' ? input : inputs.indexOf(input)], m);
    out.beforeListening = beforeListening;
    out.afterDeaf = [];
    for (const q of queries.filter((x) => x.when === 'deaf')) out.afterDeaf.push({ ...q, value: await query(q) });
    fakes.sent.length = 0;
    await fakes.flushTimers();
    out.sent = fakes.sent.map((s) => packet(s, outputs));
    const target = typeof input === 'number' ? inputs[input] : inputs.includes(input) ? input : null;
    if (target) for (const m of messages) feed(target, m);
    out.messages = messages;
    for (const q of queries.filter((x) => x.when === undefined)) out.values.push({ ...q, value: await query(q) });
    out.storage = Object.fromEntries([...store].filter(([k]) => k.includes(`-${typeof input === 'string' ? input : '\u0000'}-`)));
    return out;
  };

  const q = (cc, chan, when) => ({ cc, ...(chan === undefined ? {} : { chan }), ...(when ? { when } : {}) });
  scenarios.push(await midinScenario('channel and system messages', 'In A', {
    beforeListening: [[0xb0, 7, 127]],
    messages: [[0xb0, 7, 127], [0xb1, 7, 64], [0xb0, 10, 0], [0xc0, 5], [0x90, 60, 127], [0xe0, 0, 64], [0xb2, 7, 1]],
    queries: [q(7, undefined, 'deaf'), q(7), q(7, 1), q(7, 2), q(7, 3), q(10), q(5), q(60), q(99), q(99, 1), q('7'), q(7.5), q(7, 17), q(7, 0), q(7, null)],
  }));
  scenarios.push(await midinScenario('state of an earlier session', 'In B', {
    messages: [[0xb0, 7, 127]],
    queries: [q(7, undefined, 'before'), q(7, 1, 'before'), q(7, 3, 'before'), q(10, undefined, 'before'), q(7), q(7, 1), q(7, 3)],
  }));
  scenarios.push(await midinScenario('state sent back to the device', 'In C', {
    messages: [[0xb0, 74, 10], [0xb2, 1, 5]],
    queries: [q(7), q(7, 1), q(74, 1), q(74), q(1, 3), q(1, 2)],
  }));
  scenarios.push(await midinScenario('an input by number', 3, { messages: [[0xb0, 7, 127]], queries: [q(7), q(7, 1)] }));
  scenarios.push(await midinScenario('a device that is not there', 'Nope', { messages: [[0xb0, 7, 127]], queries: [q(7), q(7, 1)] }));
  scenarios.push(await midinScenario('the default input', undefined, { messages: [], queries: [q(7)] }));
  try {
    await midi.midin(core_.pure('In A'));
    scenarios.push({ name: 'a pattern as the input', error: null });
  } catch (e) {
    scenarios.push({ name: 'a pattern as the input', error: e.message, logs: [] });
  }

  // midikeys
  const keyboards = [];
  const keyboardScenario = async (name, input, { started = true, noteLengths = [undefined], messages }) => {
    fakes.logs.length = 0;
    state.setIsStarted(started);
    state.setTime(() => 3);
    state.setCpsFunc(() => 0.5);
    let kb;
    let error = null;
    try {
      kb = await midi.midikeys(input);
    } catch (e) {
      error = e.message;
    }
    const out = { name, input: encode(input), error, logs: [...fakes.logs], messages, started, queries: [] };
    if (error) return out;
    const target = typeof input === 'number' ? inputs[input] : input;
    for (const m of messages) feed(target, m);
    for (const nl of noteLengths) {
      const haps = (nl === undefined ? kb() : kb(await decode(nl))).queryArc(0, 1);
      out.queries.push({
        noteLength: encode(nl),
        haps: haps.map((h) => ({ value: encode(h.value), length: h.whole.end.sub(h.whole.begin).valueOf() })),
      });
    }
    return out;
  };
  keyboards.push(await keyboardScenario('note on, note off and velocity zero', 'In D', {
    noteLengths: [undefined, 0.25, '0.5 1'],
    messages: [[0x90, 60, 100], [0x80, 60, 0], [0x91, 64, 0], [0x92, 67, 127], [0xb0, 7, 5], [0xe0, 0, 64]],
  }));
  keyboards.push(await keyboardScenario('the scheduler is not started', 'In E', { started: false, messages: [[0x90, 60, 100]] }));
  keyboards.push(await keyboardScenario('a device that is not there', 'Nowhere', { messages: [] }));
  keyboards.push(await keyboardScenario('an input by number', 5, { messages: [[0x9f, 0, 1]] }));

  return { midin: scenarios, midikeys: keyboards, initialStore, inputs, outputs };
}

// ---- Kotlin source of the control change names ------------------------------------------------------------------------------------------------

async function writeCcNamesKotlin() {
  const webmidi = await importFile(webmidiFile);
  const modern = webmidi.Enumerations.CONTROL_CHANGE_MESSAGES.map((m) => [m.number, m.name]);
  const legacy = Object.entries(webmidi.Enumerations.MIDI_CONTROL_CHANGE_MESSAGES);
  const rows = (list) => list.map(([a, b]) => `        "${typeof a === 'string' ? a : b}" to ${typeof a === 'string' ? b : a},`).join('\n');
  const source = `package cosic.io.midi

/*
 * The control change names of WebMidi 3.1.12 (\`Enumerations.CONTROL_CHANGE_MESSAGES\` and the deprecated \`MIDI_CONTROL_CHANGE_MESSAGES\`),
 * which \`Utilities.getCcNumberByName\` looks a \`ccn\` given as a string up in: the first table, then the second.
 *
 * Generated by \`node tools/strudel-ref/midi-oracle.mjs --cc-names-kotlin\` from the module the pinned @strudel/midi imports. Do not edit.
 */
internal object MidiCcNames {
    /** \`Enumerations.CONTROL_CHANGE_MESSAGES\`: name to controller number. */
    val modern: Map<String, Int> = linkedMapOf(
${rows(modern.map(([n, name]) => [name, n]))}
    )

    /** \`Enumerations.MIDI_CONTROL_CHANGE_MESSAGES\`, which \`getCcNumberByName\` falls back on. */
    val legacy: Map<String, Int> = linkedMapOf(
${rows(legacy)}
    )
}
`;
  fs.mkdirSync(path.dirname(ccNamesKotlin), { recursive: true });
  fs.writeFileSync(ccNamesKotlin, source);
  progress(`wrote ${path.relative(root, ccNamesKotlin)} (${modern.length} + ${legacy.length} names)`);
}

// ---- main ---------------------------------------------------------------------------------------------------------------------------------------

const args = process.argv.slice(2);
if (args[0] === '--cc-names-kotlin') {
  await writeCcNamesKotlin();
  process.exit(0);
}

if (args[0] === '--section' && args[1] === 'input') {
  const result = await inputSection();
  realLog('\nCHILD_JSON:' + JSON.stringify(result));
  process.exit(0);
}

const main = await encodeSection();
const childOutput = execFileSync(process.execPath, [fileURLToPath(import.meta.url), '--section', 'input'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
const childJson = childOutput.split('\nCHILD_JSON:')[1];
if (!childJson) throw new Error('the input section printed nothing:\n' + childOutput.slice(-2000));
const inputs = JSON.parse(childJson);

const meta = { pin: '84ef32eec8074f90c6690ab8d6a6c204f508b182', webmidi: '3.1.12', node: process.version, target: TARGET };
fs.mkdirSync(path.dirname(output), { recursive: true });
const line = (v) => JSON.stringify(v);
const lines = (list) => '[\n' + list.map((x) => '    ' + line(x)).join(',\n') + '\n  ]';
const text =
  '{\n' +
  `  "meta": ${line(meta)},\n` +
  `  "outputs": ${line(main.outputs)},\n` +
  `  "ccNames": ${line(main.ccNames)},\n` +
  `  "maps": ${line(main.maps)},\n` +
  `  "devices": ${lines(main.devices)},\n` +
  `  "configErrors": ${lines(main.configErrors)},\n` +
  `  "encode": ${lines(main.encode)},\n` +
  `  "input": ${line({ inputs: inputs.inputs, outputs: inputs.outputs, initialStore: inputs.initialStore })},\n` +
  `  "midin": ${lines(inputs.midin)},\n` +
  `  "midikeys": ${lines(inputs.midikeys)}\n` +
  '}\n';
fs.writeFileSync(output, text);
const failures = main.encode.filter((c) => c.expected.error).length;
progress(
  `wrote ${path.relative(root, output)}: ${main.encode.length} encode cases (${failures} with an error), ${main.devices.length} device cases, ` +
    `${inputs.midin.length} midin and ${inputs.midikeys.length} midikeys scenarios`,
);
process.exit(0);
