// Makes a small SoundFont 2 file (engine/src/test/resources/soundfont/sf2/test.sf2), reads it with the real `soundfont2` package (0.4.0) and plays notes of its presets with
// the real `sfumato` (0.1.2) against a recording AudioContext: what Strudel's `loadSoundfont(url)` and `.soundfont(sf, n)` do. Writes the JSON that Sf2FileTest and
// PresetNoteTest replay (engine/src/test/resources/soundfont/sf2/oracle.json):
//
//   node tools/strudel-ref/sf2-oracle.mjs
//
// STRUDEL_DIR (default tools/strudel-ref/strudel next to this file) is the checkout made by fetch.sh.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.STRUDEL_DIR ?? path.join(here, 'strudel');
const out = path.join(here, '../../engine/src/test/resources/soundfont/sf2');
fs.mkdirSync(out, { recursive: true });
const pnpm = path.join(root, 'node_modules/.pnpm');
const sfumatoDir = path.join(pnpm, 'sfumato@0.1.2/node_modules/sfumato');

// ---- the file --------------------------------------------------------------------------------------------------------------------------------------------------

const u16 = (v) => Buffer.from([v & 0xff, (v >> 8) & 0xff]);
const u32 = (v) => Buffer.from([v & 0xff, (v >>> 8) & 0xff, (v >>> 16) & 0xff, (v >>> 24) & 0xff]);
const str = (s, n) => { const b = Buffer.alloc(n); b.write(s, 'latin1'); return b; };
const chunk = (id, payload) => Buffer.concat([Buffer.from(id, 'latin1'), u32(payload.length), payload, payload.length % 2 ? Buffer.alloc(1) : Buffer.alloc(0)]);
const list = (type, chunks) => chunk('LIST', Buffer.concat([Buffer.from(type, 'latin1'), ...chunks]));

const wave = (n, k) => Int16Array.from({ length: n }, (_, i) => Math.round(10000 * Math.sin(i * 0.3 + k)));
const sampleDefs = [
  // name, data, rate, rootKey, pitchCorrection, loop start, loop end (frames from the start of the sample), type
  ['tone A', wave(200, 0), 22050, 60, 5, 50, 150, 1],
  ['tone B left', wave(100, 1), 44100, 72, -10, 10, 90, 4],
  ['tone B right', wave(100, 2), 44100, 72, 0, 10, 90, 2],
];
let position = 0;
const smplParts = [];
const shdr = [];
for (const [name, data, rate, key, correction, loopStart, loopEnd, type] of sampleDefs) {
  const start = position;
  smplParts.push(Buffer.from(data.buffer), Buffer.alloc(46 * 2));
  position += data.length + 46;
  shdr.push(Buffer.concat([str(name, 20), u32(start), u32(start + data.length), u32(start + loopStart), u32(start + loopEnd), u32(rate), Buffer.from([key]), Buffer.from([correction & 0xff]), u16(0), u16(type)]));
}
shdr.push(Buffer.concat([str('EOS', 20), u32(0), u32(0), u32(0), u32(0), u32(0), Buffer.from([0]), Buffer.from([0]), u16(0), u16(0)]));

// generators: [id, value] or [id, [lo, hi]]
const GEN = { delayVolEnv: 33, attackVolEnv: 34, holdVolEnv: 35, decayVolEnv: 36, sustainVolEnv: 37, releaseVolEnv: 38, instrument: 41, keyRange: 43, velRange: 44, sampleId: 53, sampleModes: 54, pan: 17,
  fineTune: 52, coarseTune: 51, overridingRootKey: 58, startloopAddrsOffset: 2, endloopAddrsOffset: 3, startloopAddrsCoarseOffset: 45, velocity: 47, reverbEffectsSend: 16, initialAttenuation: 48 };
const gen = ([id, value]) => (Array.isArray(value) ? Buffer.concat([u16(id), Buffer.from(value)]) : Buffer.concat([u16(id), Buffer.from(new Int16Array([value]).buffer)]));
function zonesOf(items) {
  // items: arrays of generators; returns the bag, generator and (empty) modulator chunks of one list of headers: each header has the zones of items[i]
  const bags = [];
  const gens = [];
  const headerBags = [];
  for (const zones of items) {
    headerBags.push(bags.length);
    for (const zone of zones) {
      bags.push(Buffer.concat([u16(gens.length), u16(0)]));
      for (const g of zone) gens.push(gen(g));
    }
  }
  bags.push(Buffer.concat([u16(gens.length), u16(0)]));
  gens.push(gen([0, 0]));
  return { bags, gens, headerBags };
}
const G = GEN;
const instruments = zonesOf([
  [
    [[G.attackVolEnv, -2000], [G.sustainVolEnv, 200], [G.holdVolEnv, -7000]], // a global zone
    [[G.keyRange, [0, 59]], [G.sampleModes, 1], [G.sampleId, 0]],
    [[G.keyRange, [60, 127]], [G.pan, -250], [G.sampleModes, 1], [G.overridingRootKey, 65], [G.fineTune, 12], [G.startloopAddrsOffset, 3], [G.endloopAddrsOffset, -5], [G.coarseTune, 3], [G.sampleId, 0]],
  ],
  [
    [[G.keyRange, [0, 127]], [G.velRange, [0, 127]], [G.pan, -500], [G.sampleModes, 1], [G.sampleId, 1]],
    [[G.keyRange, [0, 127]], [G.pan, 500], [G.sampleModes, 1], [G.releaseVolEnv, -1200], [G.decayVolEnv, -3000], [G.delayVolEnv, -9000], [G.sampleId, 2]],
    [[G.keyRange, [40, 50]], [G.sampleId, 1], [14, 7]], // no sampleModes: not looped; 14 is an unused generator, which `soundfont2` skips after its id only
  ],
]);
const presets = zonesOf([
  [
    [[G.releaseVolEnv, 500], [G.reverbEffectsSend, 100]], // a global zone
    [[G.instrument, 0]],
  ],
  [
    [[G.keyRange, [20, 100]], [G.fineTune, -5], [G.pan, 100], [G.coarseTune, 2], [G.instrument, 1]],
  ],
]);
const phdr = [
  Buffer.concat([str('Preset One', 20), u16(0), u16(0), u16(presets.headerBags[0]), u32(0), u32(0), u32(0)]),
  Buffer.concat([str('Preset Two', 20), u16(1), u16(0), u16(presets.headerBags[1]), u32(0), u32(0), u32(0)]),
  Buffer.concat([str('EOP', 20), u16(255), u16(255), u16(presets.bags.length - 1), u32(0), u32(0), u32(0)]),
];
const inst = [
  Buffer.concat([str('Inst One', 20), u16(instruments.headerBags[0])]),
  Buffer.concat([str('Inst Two', 20), u16(instruments.headerBags[1])]),
  Buffer.concat([str('EOI', 20), u16(instruments.bags.length - 1)]),
];
const file = chunk('RIFF', Buffer.concat([
  Buffer.from('sfbk', 'latin1'),
  list('INFO', [chunk('ifil', Buffer.concat([u16(2), u16(1)])), chunk('isng', str('EMU8000', 8)), chunk('INAM', str('Cosic test font', 16))]),
  list('sdta', [chunk('smpl', Buffer.concat(smplParts))]),
  list('pdta', [
    chunk('phdr', Buffer.concat(phdr)), chunk('pbag', Buffer.concat(presets.bags)), chunk('pmod', Buffer.alloc(10)), chunk('pgen', Buffer.concat(presets.gens)),
    chunk('inst', Buffer.concat(inst)), chunk('ibag', Buffer.concat(instruments.bags)), chunk('imod', Buffer.alloc(10)), chunk('igen', Buffer.concat(instruments.gens)),
    chunk('shdr', Buffer.concat(shdr)),
  ]),
]));
fs.writeFileSync(path.join(out, 'test.sf2'), file);

// ---- the real parser -------------------------------------------------------------------------------------------------------------------------------------------

const require = createRequire(import.meta.url);
const { SoundFont2 } = require(path.join(pnpm, 'soundfont2@0.4.0/node_modules/soundfont2/lib/SoundFont2.node.js'));
const sf = new SoundFont2(new Uint8Array(file));
const gens = (g) => Object.fromEntries(Object.entries(g ?? {}).map(([id, v]) => [id, 'range' in v ? { lo: v.range.lo, hi: v.range.hi } : v.value]));
const parsed = {
  metaData: { version: sf.metaData.version, name: sf.metaData.name },
  samples: sf.samples.map((s) => ({ ...s.header, length: s.data.length, first: Array.from(s.data.slice(0, 5)), last: Array.from(s.data.slice(-3)), sum: s.data.reduce((a, b) => a + b, 0) })),
  instruments: sf.instruments.map((i) => ({ name: i.header.name, global: i.globalZone ? gens(i.globalZone.generators) : null, zones: i.zones.map((z) => ({ gens: gens(z.generators), sample: sf.samples.indexOf(z.sample) })) })),
  presets: sf.presets.map((p) => ({ name: p.header.name, preset: p.header.preset, bank: p.header.bank, global: p.globalZone ? gens(p.globalZone.generators) : null,
    zones: p.zones.map((z) => ({ gens: gens(z.generators), instrument: sf.instruments.indexOf(z.instrument) })) })),
};

// ---- sfumato against a recording AudioContext ---------------------------------------------------------------------------------------------------------------

class AudioParam {
  constructor() { this.events = []; this.value = 1; }
  setValueAtTime(v, t) { this.events.push(['setValueAtTime', v, t]); }
  exponentialRampToValueAtTime(v, t) { this.events.push(['exponentialRampToValueAtTime', v, t]); }
  cancelAndHoldAtTime(t) { this.events.push(['cancelAndHoldAtTime', t]); }
}
globalThis.AudioParam = AudioParam;
// sfumato imports named exports of the `soundfont2` bundle, which Node cannot take from its CommonJS file: the same code with that one import made by require
const sfumatoSource = fs.readFileSync(path.join(sfumatoDir, 'dist/sfumato.js'), 'utf8');
if (!sfumatoSource.startsWith('import { DEFAULT_GENERATOR_VALUES as w, SoundFont2 as q } from "soundfont2";')) throw new Error('sfumato does not look like the pinned 0.1.2');
const patched = path.join(os.tmpdir(), `sfumato-patched-${process.pid}.mjs`);
fs.writeFileSync(patched, `import { createRequire } from 'node:module';\nconst { DEFAULT_GENERATOR_VALUES: w, SoundFont2: q } = createRequire(import.meta.url)(${JSON.stringify(path.join(pnpm, 'soundfont2@0.4.0/node_modules/soundfont2/lib/SoundFont2.node.js'))});\n` + sfumatoSource.split('\n').slice(1).join('\n'));
const sfumato = await import(pathToFileURL(patched));
fs.unlinkSync(patched);

function recordingContext() {
  const sources = [];
  const ctx = {
    currentTime: 0,
    destination: { destination: true },
    createBuffer: (channels, length, sampleRate) => { const data = new Float32Array(length); return { channels, length, sampleRate, getChannelData: () => data }; },
    createBufferSource() {
      const src = { playbackRate: { value: 1 }, loop: false, loopStart: undefined, loopEnd: undefined, starts: [], stops: [], buffer: null, connect(node) { this.to = node; return node; }, start(t) { this.starts.push(t); }, stop(t) { this.stops.push(t); } };
      sources.push(src);
      return src;
    },
    createGain() { return { gain: new AudioParam(), connect(node) { this.to = node; return node; } }; },
    createStereoPanner() { return { pan: { value: 0 }, connect(node) { this.to = node; return node; } }; },
  };
  return { ctx, sources };
}

const play = [];
for (const [presetIndex, notes] of [[0, [0, 21, 36, 59, 59.5, 60, 61, 64, 72, 127, 128]], [1, [0, 19, 20, 40, 45, 50, 51, 60, 99.5, 100, 100.5, 101]]]) {
  for (const midi of notes) {
    for (const [time, stopAt] of [[1.5, 2.25], [0, 0.01]]) {
      const { ctx, sources } = recordingContext();
      const preset = sf.presets[presetIndex];
      const stop = sfumato.startPresetNote(ctx, preset, midi, time);
      stop(stopAt);
      play.push({
        preset: presetIndex, midi, time, stopAt,
        zones: sources.map((s) => {
          // the nodes of a zone: buffer source -> gain -> panner -> destination
          const gainNode = s.to;
          const panner = gainNode.to;
          return {
            rate: s.playbackRate.value, loop: s.loop, loopStart: s.loopStart ?? null, loopEnd: s.loopEnd ?? null, starts: s.starts, stops: s.stops,
            sampleRate: s.buffer.sampleRate, frames: s.buffer.length, first: Array.from(s.buffer.getChannelData(0).slice(0, 3)),
            events: gainNode.gain.events, pan: panner.pan.value, toDestination: panner.to?.destination === true,
          };
        }),
      });
    }
  }
}
fs.writeFileSync(path.join(out, 'oracle.json'), JSON.stringify({ parsed, play }) + '\n');
console.log(`sf2 ${file.length} bytes, ${play.length} plays`);
