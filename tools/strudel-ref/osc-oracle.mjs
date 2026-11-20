// Runs the OSC path of the pinned @strudel/osc in Node and writes golden vectors for cosic.io.osc (Task 4.6):
//
//   node tools/strudel-ref/osc-oracle.mjs        # writes engine/src/test/resources/osc/golden.json
//
// What runs (packages/osc at 84ef32eec8074f90c6690ab8d6a6c204f508b182, osc@2.4.5 from the checkout's node_modules):
//   parseControlsFromHap   cut out of osc.mjs (its imports need a browser) and evaluated against the real @strudel/core
//   oscOutput's message    { address: '/dirt/play', args: Object.entries(controls).flat(), timestamp, host?, port? }, as osc.mjs:67-75 builds it
//   the WebSocket hop      JSON.stringify / JSON.parse, which turns undefined and NaN into null inside the args
//   server.js              { timeTag: osc.timeTag(0, timestamp), packets: [{ address, args }] } written by osc.writePacket
// A value of a case is JSON, except that {"$undefined":1}, {"$nan":1}, {"$inf":1} stand for undefined, NaN and Infinity.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const checkout = process.env.STRUDEL_DIR ?? path.join(here, 'strudel');
const out = process.env.OSC_OUT ?? path.join(here, '../../engine/src/test/resources/osc/golden.json');
const core = await import(pathToFileURL(path.join(checkout, 'packages/core/index.mjs')).href);
const osc = createRequire(path.join(checkout, 'packages/osc/package.json'))('osc');

// parseControlsFromHap, as written in osc.mjs
const source = fs.readFileSync(path.join(checkout, 'packages/osc/osc.mjs'), 'utf8');
const start = source.indexOf('export function parseControlsFromHap');
const end = source.indexOf('let timeOrigin');
if (start < 0 || end < start) throw new Error('osc.mjs does not look as expected');
const parseControlsFromHap = new Function(
  'parseNumeral',
  'isNote',
  'noteToMidi',
  source.slice(start, end).replace('export function', 'function') + '\nreturn parseControlsFromHap;',
)(core.parseNumeral, core.isNote, core.noteToMidi);

const revive = (v) => {
  if (v && typeof v === 'object' && !Array.isArray(v)) {
    const keys = Object.keys(v);
    if (keys.length === 1 && keys[0] === '$undefined') return undefined;
    if (keys.length === 1 && keys[0] === '$nan') return NaN;
    if (keys.length === 1 && keys[0] === '$inf') return Infinity;
    return Object.fromEntries(keys.map((k) => [k, revive(v[k])]));
  }
  if (Array.isArray(v)) return v.map(revive);
  return v;
};
const hex = (u) => Buffer.from(u).toString('hex');
const fraction = (s) => core.Fraction(s);
const span = (a) => (a ? new core.TimeSpan(fraction(a[0]), fraction(a[1])) : undefined);
const firstLine = (e) => String(e && e.message ? e.message : e).split('\n')[0];

/** What the browser sends over the WebSocket and the bridge writes to UDP, or the error that ends the message. */
function wire(msg) {
  const data = JSON.parse(JSON.stringify(msg));
  let packet = { address: data.address, args: data.args };
  if ('timestamp' in data) packet = { timeTag: osc.timeTag(0, data.timestamp), packets: [packet] };
  return { bytes: hex(osc.writePacket(packet)), host: data.host ?? null, port: data.port ?? null };
}

const hapCases = [
  { name: 'doc vector', value: { s: 'bd' }, cps: 0.5, whole: ['0', '1'], unixMs: 1_700_000_000_500 },
  {
    name: 'key order',
    value: { note: 'c3', n: '2', roomsize: 0.5, unit: 'c', speed: 1, channels: [1, 2] },
    cps: 0.5,
    whole: ['0', '1'],
    unixMs: 1_700_000_000_500,
  },
  { name: 'cps and fractions', value: { s: 'hh', gain: 0.8 }, cps: 0.6, whole: ['1/3', '2/3'], unixMs: 1_700_000_001_234.5678 },
  { name: 'second cycle', value: { s: 'sd', n: 3 }, cps: 1, whole: ['5/2', '3'], unixMs: 1_700_000_002_000 },
  { name: 'fragment', value: { s: 'bd' }, cps: 1, whole: ['0', '1'], part: ['1/2', '1'], unixMs: 1_700_000_000_000 },
  { name: 'continuous hap', value: { s: 'bd' }, cps: 1, whole: null, part: ['1/4', '3/4'], unixMs: 1_700_000_000_000 },
  { name: 'clip', value: { s: 'bd', clip: 0.5 }, cps: 2, whole: ['0', '1/2'], unixMs: 1_700_000_000_000 },
  { name: 'n string', value: { s: 'bd', n: '3' }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'n zero stays', value: { s: 'bd', n: 0 }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'n note name', value: { s: 'bd', n: 'e3' }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'n not a numeral', value: { s: 'bd', n: 'x' }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'note number', value: { note: 60 }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'note name with octave', value: { note: 'c#4' }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'note name octave key', value: { note: 'e', octave: 5 }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'note name octave fraction', value: { note: 'e', octave: 2.5 }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'note numeric string', value: { note: '48' }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'note not a note', value: { note: 'zz' }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'existing midinote stays in place', value: { midinote: 1, note: 'c3', s: 'x' }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'roomsize zero', value: { roomsize: 0 }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'roomsize string', value: { roomsize: '2' }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'unit c without speed', value: { unit: 'c' }, cps: 0.5, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'unit c speed', value: { unit: 'c', speed: 1.5 }, cps: 0.75, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'unit r speed', value: { unit: 'r', speed: 1.5 }, cps: 0.75, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'channels number', value: { channels: 2 }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'channels null', value: { channels: null }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'host and port', value: { s: 'bd', oschost: '192.168.1.5', oscport: 9000 }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'port only', value: { s: 'bd', oscport: 57121 }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'host undefined', value: { s: 'bd', oschost: { $undefined: 1 } }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'undefined value drops the message', value: { s: 'bd', gain: { $undefined: 1 } }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'nan value drops the message', value: { s: 'bd', gain: { $nan: 1 } }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'infinity value drops the message', value: { s: 'bd', gain: { $inf: 1 } }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'null value drops the message', value: { s: 'bd', gain: null }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'boolean values', value: { s: 'bd', flag: true, other: false }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'array value', value: { s: 'bd', list: [1, 'a', [true, 2]] }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'empty array value', value: { list: [] }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'object value drops the message', value: { s: 'bd', nested: { a: 1 } }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'unicode and padding', value: { s: 'héllo😀', vowel: 'abc', x: 'abcd', empty: '' }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'integer like keys come first', value: { s: 'bd', 3: 'c', 1: 'a' }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'hap keys override cps', value: { cps: 9, s: 'bd' }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'not an object', value: 'bd', cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'big and tiny numbers', value: { a: 1e300, b: -0, c: 0.1, d: 16777217, e: 1e-50, f: -3.5 }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000 },
  { name: 'timetag fraction rounds up to a whole second', value: { s: 'bd' }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_999.9999999 },
  { name: 'timetag small fraction', value: { s: 'bd' }, cps: 1, whole: ['0', '1'], unixMs: 1_700_000_000_000.4 },
];

const cases = hapCases.map((c) => {
  const value = revive(c.value);
  const whole = span(c.whole);
  const part = c.part ? span(c.part) : whole;
  const hap = new core.Hap(whole, part, value, {});
  const result = { name: c.name, value: c.value, cps: c.cps, whole: c.whole, part: c.part ?? null, unixMs: c.unixMs };
  try {
    const controls = parseControlsFromHap(hap, c.cps);
    const args = Object.entries(controls).flat();
    const msg = { address: '/dirt/play', args, timestamp: c.unixMs };
    if (typeof value === 'object' && value !== null && 'oschost' in value) msg.host = value.oschost;
    if (typeof value === 'object' && value !== null && 'oscport' in value) msg.port = value.oscport;
    result.args = JSON.stringify(args);
    try {
      Object.assign(result, wire(msg));
    } catch (e) {
      result.sendError = firstLine(e);
    }
  } catch (e) {
    result.controlsError = firstLine(e);
  }
  return result;
});

const codecCases = [
  { name: 'no arguments', args: [] },
  { name: 'strings', args: ['a', 'ab', 'abc', 'abcd', ''] },
  { name: 'numbers', args: [0, 1, -1, 0.5, 0.1, 1e300, -1e300, 16777217, 1e-50, 123456789.123] },
  { name: 'booleans', args: [true, false] },
  { name: 'nested', args: [[1, 'a', [true]], [], 'x'] },
  { name: 'lone surrogate', args: ['a\ud800b'] },
  { name: 'astral', args: ['😀'] },
  { name: 'null', args: [null] },
  { name: 'object', args: [{ a: 1 }] },
];
const codec = codecCases.map((c) => {
  const result = { name: c.name, args: JSON.stringify(c.args) };
  const args = JSON.parse(result.args);
  try {
    result.message = hex(osc.writePacket({ address: '/dirt/play', args }));
  } catch (e) {
    result.error = firstLine(e);
  }
  return result;
});

fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(
  out,
  JSON.stringify({ commit: '84ef32eec8074f90c6690ab8d6a6c204f508b182', osc: '2.4.5', cases, codec }, null, 1) + '\n',
);
console.log(`wrote ${cases.length} hap cases and ${codec.length} codec cases to ${out}`);
