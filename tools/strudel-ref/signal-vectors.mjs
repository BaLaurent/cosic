// Records what the signals of the pinned checkout (packages/core/signal.mjs) answer at many times, bit for bit, so that
// the Kotlin port can be compared with them without going through a snapshot that only has a few rounded cases.
//
//   node tools/strudel-ref/signal-vectors.mjs        writes engine/src/test/resources/parity/signal-vectors.json
//
// Every time is an exact fraction n/d (so both sides build it without converting a double), each value is what
// `signal.queryArc(t, t + 1/1000)[0].value` is, and the random signals are recorded with both generators and with a seed.
// Used by SignalsTest.valuesAreStrudelsBitForBit.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const core = path.join(here, 'strudel/packages/core');
const signals = await import(path.join(core, 'signal.mjs'));
const { default: Fraction } = await import(path.join(core, 'fraction.mjs'));
const { saw, isaw, sine, sine2, cosine, square, tri, itri, rand, perlin, berlin, useRNG, withSeed, irand, brand } = signals;

// the same times every run: a linear congruential generator, so the file does not change when it is made again
let state = 20251120;
const next = () => {
  state = (Math.imul(state, 1103515245) + 12345) >>> 0;
  return state / 4294967296;
};
const denominators = [1, 2, 3, 4, 5, 7, 8, 9, 10, 11, 12, 13, 16, 100, 1000];
const times = [];
for (let i = 0; i < 300; i++) {
  const d = denominators[Math.floor(next() * denominators.length)];
  const n = Math.floor(next() * 105 * d) - 5 * d; // from -5 to 100 cycles
  times.push([n, d]);
}

const at = (pattern, [n, d]) => {
  const begin = Fraction(n).div(d);
  return pattern.queryArc(begin, begin.add(Fraction(1, 1000)))[0].value;
};
const series = (name, pattern, extra = {}) => ({ name, ...extra, values: times.map((t) => at(pattern, t)) });

const legacy = [
  series('saw', saw),
  series('isaw', isaw),
  series('sine', sine),
  series('sine2', sine2),
  series('cosine', cosine),
  series('square', square),
  series('tri', tri),
  series('itri', itri),
  series('rand', rand),
  series('perlin', perlin),
  series('berlin', berlin),
  series('brand', brand),
  series('irand8', irand(8)),
  series('rand seed 3', withSeed(() => 3, rand), { seed: 3 }),
  series('perlin seed 2.5', withSeed(() => 2.5, perlin), { seed: 2.5 }),
];
useRNG('precise');
const precise = [
  series('rand', rand),
  series('perlin', perlin),
  series('berlin', berlin),
  series('rand seed 3', withSeed(() => 3, rand), { seed: 3 }),
  series('berlin seed -7', withSeed(() => -7, berlin), { seed: -7 }),
];
useRNG('legacy');

const out = path.resolve(here, '../../engine/src/test/resources/parity/signal-vectors.json');
const text = JSON.stringify({ times, legacy, precise });
fs.writeFileSync(out, `${text}\n`);
console.log(`signal vectors: ${times.length} times, ${legacy.length} legacy and ${precise.length} precise series -> ${out}`);
