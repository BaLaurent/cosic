// Writes engine/src/test/resources/parity/audio-feature-vectors.json: the features.mjs output on a few signals that need no transcendental function to make (a sawtooth, LCG
// noise, silence), so that AudioFeaturesTest rebuilds the very same Float32 samples in Kotlin and checks its port against these numbers (unrounded).
//   node tools/strudel-ref/audio/feature-vectors.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { features } from './features.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const out = path.join(here, '../../../engine/src/test/resources/parity/audio-feature-vectors.json');

function build(segments) {
  const parts = segments.map((s) => {
    if (s.zeros) return new Float32Array(s.zeros);
    if (s.saw) {
      const { length, amp, step } = s.saw;
      return Float32Array.from({ length }, (_, i) => (((i * step) % 1000) / 500 - 1) * amp);
    }
    const { length, amp, seed } = s.noise;
    const o = new Float32Array(length);
    let x = seed >>> 0;
    for (let i = 0; i < length; i++) {
      x = (x * 1664525 + 1013904223) % 4294967296;
      o[i] = (x / 2147483648 - 1) * amp;
    }
    return o;
  });
  const all = new Float32Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    all.set(p, at);
    at += p.length;
  }
  return all;
}

const cases = [
  { name: 'saw at 48k', sampleRate: 48000, segments: [{ saw: { length: 6144, amp: 0.5, step: 37 } }] },
  { name: 'saw at 44.1k', sampleRate: 44100, segments: [{ saw: { length: 6144, amp: 0.25, step: 11 } }] },
  { name: 'noise at 48k', sampleRate: 48000, segments: [{ noise: { length: 5000, amp: 0.25, seed: 7 } }] },
  {
    name: 'bursts with silence',
    sampleRate: 48000,
    segments: [{ zeros: 3000 }, { saw: { length: 5000, amp: 0.9, step: 53 } }, { saw: { length: 4000, amp: 0.1, step: 53 } }, { zeros: 2000 }, { noise: { length: 4000, amp: 0.8, seed: 3 } }],
  },
  { name: 'shorter than a frame', sampleRate: 48000, segments: [{ saw: { length: 1500, amp: 0.5, step: 37 } }] },
];

const doc = {
  generator: 'tools/strudel-ref/audio/feature-vectors.mjs',
  cases: cases.map((c) => ({ ...c, features: features([build(c.segments)], c.sampleRate) })),
};
fs.writeFileSync(out, JSON.stringify(doc) + '\n');
console.log('wrote', out, fs.statSync(out).size, 'bytes,', cases.length, 'cases');
