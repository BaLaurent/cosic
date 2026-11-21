// The page render.mjs opens once per corpus entry: it renders the entry's program with the real renderPatternAudio of @strudel/webaudio (webaudio-render.mjs is that
// file, made by render.mjs with the one change that it hands back the AudioBuffer instead of downloading a WAV) after the same prebake the REPL runs, computes the
// features of features.mjs on the two channels and posts everything back to the local server (/result/<id>). Nothing here is part of the product.
import { pending, requests, trace } from './patch.mjs';
import * as core from '@strudel/core';
import * as mini from '@strudel/mini';
import * as tonal from '@strudel/tonal';
import * as edo from '@strudel/edo';
import * as xen from '@strudel/xen';
import * as webaudio from '@strudel/webaudio';
import * as soundfonts from '@strudel/soundfonts';
import { transpiler } from '@strudel/transpiler';
import { renderPatternAudio } from './webaudio-render.mjs';
import { compact, features } from './features.mjs';

const { Pattern, noteToMidi, valueToMidi, evalScope, repl } = core;
const params = new URLSearchParams(location.search);
const id = params.get('id');
const post = (path, body) => fetch(path, { method: 'POST', body: typeof body === 'string' ? body : JSON.stringify(body) });
const logs = [];
document.addEventListener(core.logger.key, (e) => {
  const { message, type } = e.detail;
  if (logs.length < 200) logs.push(type ? `${type}: ${message}` : message);
});
window.addEventListener('error', (e) => logs.push(`window error: ${e.message}`));
window.addEventListener('unhandledrejection', (e) => logs.push(`unhandled rejection: ${e.reason && (e.reason.stack || e.reason)}`));

// The visuals and the outputs that are not audio do nothing in the offline render (test/runtime.mjs mocks them the same way).
for (const name of [
  'pianoroll', '_pianoroll', 'punchcard', '_punchcard', 'spiral', '_spiral', 'scope', 'tscope', 'fscope', '_scope', '_tscope', '_fscope', 'pitchwheel',
  '_pitchwheel', 'spectrum', '_spectrum', '_print', 'markcss', 'wordfall', '_wordfall',
]) {
  Pattern.prototype[name] = function () {
    return this;
  };
}

// The same modules, in the same order, the REPL puts in scope (website/src/repl/util.mjs loadModules), less what has no sound.
await evalScope(core, edo, tonal, mini, xen, webaudio, soundfonts);

// website/src/repl/prebake.mjs, calls and data as they are there; the one difference: the alias map is awaited, so that the program never runs before it.
const baseCDN = 'https://strudel.b-cdn.net';
const dirt = await (await fetch('./dirt-samples.json')).json();
async function prebake() {
  await Promise.all([
    webaudio.registerSynthSounds(),
    webaudio.registerZZFXSounds(),
    soundfonts.registerSoundfonts(),
    webaudio.samples(`${baseCDN}/piano.json`, `${baseCDN}/piano/`, { prebake: true }),
    webaudio.samples(`${baseCDN}/vcsl.json`, `${baseCDN}/VCSL/`, { prebake: true }),
    webaudio.samples(`${baseCDN}/tidal-drum-machines.json`, `${baseCDN}/tidal-drum-machines/machines/`, { prebake: true, tag: 'drum-machines' }),
    webaudio.samples(`${baseCDN}/uzu-drumkit.json`, `${baseCDN}/uzu-drumkit/`, { prebake: true, tag: 'drum-machines' }),
    webaudio.samples(`${baseCDN}/uzu-wavetables.json`, `${baseCDN}/uzu-wavetables/`, { prebake: true }),
    webaudio.samples(`${baseCDN}/mridangam.json`, `${baseCDN}/mrid/`, { prebake: true, tag: 'drum-machines' }),
    webaudio.samples(dirt, `${baseCDN}/Dirt-Samples/`, { prebake: true }),
  ]);
  await webaudio.aliasBank(`${baseCDN}/tidal-drum-machines-alias.json`);
}

// the `piano` of prebake.mjs
const maxPan = noteToMidi('C8');
const panwidth = (pan, width) => pan * width + (1 - width) / 2;
Pattern.prototype.piano = function () {
  return this.fmap((v) => ({ ...v, clip: v.clip ?? 1 }))
    .s('piano')
    .release(0.1)
    .fmap((value) => {
      const midi = valueToMidi(value);
      const pan = panwidth(Math.min(Math.round(midi) / maxPan, 1), 0.5);
      return { ...value, pan: (value.pan || 1) * pan };
    });
};

const SAMPLE_RATE = 48000;
const MAX_POLYPHONY = 128; // the engine's default, and superdough's
const f32 = (a) => new Uint8Array(a.buffer, a.byteOffset, a.byteLength);

async function run() {
  const entry = await (await fetch(`/entry/${id}`)).json();
  const result = { id, code: entry.code, cycles: entry.cycles };
  try {
    await prebake();
    const r = repl({ getTime: () => 0, transpiler, onSchedule: () => {}, setInterval, clearInterval });
    r.scheduler.setCps(entry.cps ?? 0.5);
    await r.evaluate(entry.code, false);
    if (r.state.evalError) throw r.state.evalError;
    const cps = r.scheduler.cps;
    result.cps = cps;
    window.__cosicMainFrames = Math.trunc(((entry.cycles - 0) / cps) * SAMPLE_RATE); // the length renderPatternAudio gives its OfflineAudioContext (patch.mjs)
    const started = performance.now();
    const buffer = await renderPatternAudio(r.state.pattern, cps, 0, entry.cycles, SAMPLE_RATE, MAX_POLYPHONY, false);
    result.renderMillis = Math.round(performance.now() - started);
    const whole = [buffer.getChannelData(0), buffer.getChannelData(1)];
    // an entry may compare the first cycles only (compareCycles): the last cycle of a K() voice is silent in Strudel's chunked render, in every seed
    const kept = entry.compareCycles ? Math.min(whole[0].length, Math.round((entry.compareCycles / cps) * SAMPLE_RATE)) : whole[0].length;
    const channels = whole.map((c) => c.subarray(0, kept));
    // Strudel's WAV export (floatTo16BitPCM) and the sound card clip to [-1, 1]; so does the output stage of the engine: compare what is heard
    for (const c of channels) for (let i = 0; i < c.length; i++) c[i] = c[i] > 1 ? 1 : c[i] < -1 ? -1 : c[i];
    result.features = compact(features(channels, buffer.sampleRate));
    if (params.get('pcm') !== '0') {
      const pcm = new Uint8Array(channels[0].byteLength * 2);
      pcm.set(f32(channels[0]), 0);
      pcm.set(f32(channels[1]), channels[0].byteLength);
      await fetch(`/pcm/${id}`, { method: 'POST', body: pcm });
    }
  } catch (e) {
    result.error = String((e && e.stack) || e);
  }
  await Promise.allSettled(pending);
  result.logs = logs;
  result.requests = requests;
  result.trace = trace;
  result.userAgent = navigator.userAgent;
  await post(`/result/${id}`, result);
}
run().catch((e) => post(`/result/${id}`, { id, error: String((e && e.stack) || e), logs }));
