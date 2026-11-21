// The audio features Task 5.2 compares (docs/parity/audio-parity.md): the same text runs in the Chromium page (page.mjs, on the AudioBuffer Strudel rendered)
// and in Node (feature-vectors.mjs, which writes the vectors AudioFeaturesTest checks Kotlin's port against). AudioFeatures.kt (engine/src/test/kotlin/cosic/parity)
// computes the same numbers on Cosic's render; keep the two in step, operation by operation.
//
// Frames are FRAME = 2048 samples every HOP = 1024 samples of one channel (whole frames only: floor((length - 2048) / 1024) + 1 of them), Hann-windowed
// (periodic: 0.5 - 0.5 cos(2 pi i / 2048)) for the spectrum:
//   rms       20 log10 of the root mean square of the unwindowed frame, floored at -100 dBFS (a frame of zeros is -100);
//   centroid  sum(f_k P_k) / sum(P_k) over bins 1..1024 of the power spectrum P_k = re^2 + im^2, f_k = k sr / 2048 (0 when the frame has no energy);
//   bands     8 octave bands centred on 62.5 * 2^b Hz (b = 0..7: 62.5 .. 8 kHz), edges at the centre / sqrt 2 and the centre * sqrt 2; the energy of a band is
//             2 sum(P_k) / (2048 sum(w_i^2)) over the bins k in 1..1023 whose frequency k sr / 2048 lies in [low, high), which is a mean square (a full-scale sine
//             is 0.5), in dB, floored at -100;
//   onsets    the frames f whose rms is above -50 dBFS and more than 6 dB above the rms of frame f - 1 (frame -1 counts as -100 dBFS).
export const FRAME = 2048;
export const HOP = 1024;
export const BAND_COUNT = 8;
export const FLOOR_DB = -100;
export const ONSET_RISE_DB = 6;
export const ONSET_MIN_DB = -50;
const FIRST_CENTRE = 62.5;
const SQRT_HALF = 0.7071067811865476;
const SQRT_TWO = 1.4142135623730951;

const HALF = FRAME / 2;
const hann = new Float64Array(FRAME);
let windowEnergy = 0;
for (let i = 0; i < FRAME; i++) {
  hann[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / FRAME);
  windowEnergy += hann[i] * hann[i];
}
const twiddleRe = new Float64Array(HALF);
const twiddleIm = new Float64Array(HALF);
for (let k = 0; k < HALF; k++) {
  const a = (-2 * Math.PI * k) / FRAME;
  twiddleRe[k] = Math.cos(a);
  twiddleIm[k] = Math.sin(a);
}
const bitReversed = new Int32Array(FRAME);
for (let i = 0, bits = Math.log2(FRAME); i < FRAME; i++) {
  let r = 0;
  for (let b = 0; b < bits; b++) if (i & (1 << b)) r |= 1 << (bits - 1 - b);
  bitReversed[i] = r;
}

/** In-place radix-2 FFT of [re], [im] (length FRAME), unscaled. */
function fft(re, im) {
  for (let i = 0; i < FRAME; i++) {
    const j = bitReversed[i];
    if (j > i) {
      let t = re[i];
      re[i] = re[j];
      re[j] = t;
      t = im[i];
      im[i] = im[j];
      im[j] = t;
    }
  }
  for (let size = 2; size <= FRAME; size *= 2) {
    const half = size / 2;
    const step = FRAME / size;
    for (let start = 0; start < FRAME; start += size) {
      for (let k = 0; k < half; k++) {
        const wr = twiddleRe[k * step];
        const wi = twiddleIm[k * step];
        const a = start + k;
        const b = a + half;
        const tr = re[b] * wr - im[b] * wi;
        const ti = re[b] * wi + im[b] * wr;
        re[b] = re[a] - tr;
        im[b] = im[a] - ti;
        re[a] += tr;
        im[a] += ti;
      }
    }
  }
}

/** The bin range [low, high) of each band at [sampleRate]. */
export function bandBins(sampleRate) {
  const out = [];
  for (let b = 0; b < BAND_COUNT; b++) {
    const centre = FIRST_CENTRE * 2 ** b;
    const low = Math.max(1, Math.ceil((centre * SQRT_HALF * FRAME) / sampleRate));
    const high = Math.min(HALF, Math.ceil((centre * SQRT_TWO * FRAME) / sampleRate));
    out.push([low, high]);
  }
  return out;
}

const toDb = (power) => (power > 0 ? Math.max(10 * Math.log10(power), FLOOR_DB) : FLOOR_DB);

/** The features of one channel [x] (a Float32Array or any indexable of numbers) at [sampleRate]. */
export function channelFeatures(x, sampleRate) {
  const frames = x.length < FRAME ? 0 : Math.floor((x.length - FRAME) / HOP) + 1;
  const rms = new Array(frames);
  const centroid = new Array(frames);
  const bands = new Array(frames * BAND_COUNT);
  const onsets = [];
  const re = new Float64Array(FRAME);
  const im = new Float64Array(FRAME);
  const power = new Float64Array(HALF + 1);
  const ranges = bandBins(sampleRate);
  const bandScale = 2 / (FRAME * windowEnergy);
  for (let f = 0; f < frames; f++) {
    const start = f * HOP;
    let sum = 0;
    for (let i = 0; i < FRAME; i++) {
      const v = x[start + i];
      sum += v * v;
      re[i] = v * hann[i];
      im[i] = 0;
    }
    const level = Math.sqrt(sum / FRAME);
    rms[f] = level > 0 ? Math.max(20 * Math.log10(level), FLOOR_DB) : FLOOR_DB;
    fft(re, im);
    let total = 0;
    let weighted = 0;
    for (let k = 1; k <= HALF; k++) {
      const p = re[k] * re[k] + im[k] * im[k];
      power[k] = p;
      total += p;
      weighted += k * p;
    }
    centroid[f] = total > 0 ? ((weighted / total) * sampleRate) / FRAME : 0;
    for (let b = 0; b < BAND_COUNT; b++) {
      let s = 0;
      for (let k = ranges[b][0]; k < ranges[b][1]; k++) s += power[k];
      bands[f * BAND_COUNT + b] = toDb(bandScale * s);
    }
    const previous = f > 0 ? rms[f - 1] : FLOOR_DB;
    if (rms[f] > ONSET_MIN_DB && rms[f] - previous > ONSET_RISE_DB) onsets.push(f);
  }
  return { frames, rms, centroid, bands, onsets };
}

/** The features of every channel plus the level of the whole signal. */
export function features(channels, sampleRate) {
  let peak = 0;
  let sum = 0;
  let n = 0;
  for (const c of channels) {
    for (let i = 0; i < c.length; i++) {
      const v = c[i];
      peak = Math.max(peak, Math.abs(v));
      sum += v * v;
    }
    n += c.length;
  }
  return {
    sampleRate,
    frameSize: FRAME,
    hop: HOP,
    frames: channels[0].length,
    peakDb: peak > 0 ? 20 * Math.log10(peak) : FLOOR_DB,
    rmsDb: sum > 0 ? 10 * Math.log10(sum / n) : FLOOR_DB,
    channels: channels.map((c) => channelFeatures(c, sampleRate)),
  };
}

/** Rounds the numbers of [f] for the file: dB to 2 decimals, Hz to 1. */
export function compact(f) {
  const r2 = (v) => Math.round(v * 100) / 100;
  const r1 = (v) => Math.round(v * 10) / 10;
  return {
    ...f,
    peakDb: r2(f.peakDb),
    rmsDb: r2(f.rmsDb),
    channels: f.channels.map((c) => ({ frames: c.frames, rms: c.rms.map(r2), centroid: c.centroid.map(r1), bands: c.bands.map(r2), onsets: c.onsets })),
  };
}

/** 10 log10 of the mean power of [db] values (dB), floored at -100: the long-term level of a list of per-frame levels. */
export function meanDb(db) {
  if (!db.length) return FLOOR_DB;
  let sum = 0;
  for (const v of db) sum += 10 ** (v / 10);
  return Math.max(10 * Math.log10(sum / db.length), FLOOR_DB);
}

/**
 * The long-term figures of features [f] (rounded or not): the mean power of all the frames of both channels, and of every octave band, in dB. Entries that are random by
 * design are compared on these (AudioCompare.compareLongTerm); their reference is the mean over several seeds ([averageLongTerm]) so that the run-to-run spread of Strudel itself
 * (about a dB for a reverb tail) does not count against the engine.
 */
export function longTerm(f) {
  return {
    levelDb: meanDb(f.channels.flatMap((c) => c.rms)),
    bandsDb: Array.from({ length: BAND_COUNT }, (_, b) => meanDb(f.channels.flatMap((c) => c.bands.filter((_, i) => i % BAND_COUNT === b)))),
  };
}

/** The power mean of several [longTerm] results, one per seed. */
export function averageLongTerm(list) {
  return {
    seeds: list.length,
    levelDb: meanDb(list.map((l) => l.levelDb)),
    bandsDb: Array.from({ length: BAND_COUNT }, (_, b) => meanDb(list.map((l) => l.bandsDb[b]))),
  };
}
