// Loaded first by page.mjs (ES modules run their imports in order), before any Strudel module exists. Three things the reference render needs:
//   * Math.random is a seeded generator (the seed is in the page's URL), so that the noise buffers and the chain ids of superdough make the same render twice;
//   * fetch of another origin goes through the local caching proxy of render.mjs (/proxy?u=<url>), which stores every file it serves under ~/.cache/cosic-samples
//     (<host>/<path>), where the Kotlin side finds the samples, maps and soundfonts the reference used;
//   * decodeAudioData records what it decoded when the bytes were not a WAV file (mp3, ogg, flac): the engine has no decoder for those on the JVM, so its sample store
//     reads the PCM Chromium made, keyed by the SHA-1 of the encoded bytes (/decoded/<sha1>, a 32-bit float WAV at the context's rate).
const params = new URLSearchParams(location.search);

let state = Number(params.get('seed') ?? 1) >>> 0;
Math.random = () => {
  state = (state + 0x6d2b79f5) >>> 0;
  let t = state;
  t = Math.imul(t ^ (t >>> 15), t | 1);
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};

const nativeFetch = window.fetch.bind(window);
export const requests = [];
window.fetch = (input, init) => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
  if (/^https?:\/\//i.test(url) && !url.startsWith(location.origin)) {
    requests.push(url);
    return nativeFetch('/proxy?u=' + encodeURIComponent(url), init);
  }
  return nativeFetch(input, init);
};

const hex = (buffer) => [...new Uint8Array(buffer)].map((b) => b.toString(16).padStart(2, '0')).join('');

function floatWav(buffer) {
  const channels = buffer.numberOfChannels;
  const frames = buffer.length;
  const out = new ArrayBuffer(44 + frames * channels * 4);
  const view = new DataView(out);
  const tag = (at, text) => [...text].forEach((c, i) => view.setUint8(at + i, c.charCodeAt(0)));
  tag(0, 'RIFF');
  view.setUint32(4, 36 + frames * channels * 4, true);
  tag(8, 'WAVE');
  tag(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 3, true);
  view.setUint16(22, channels, true);
  view.setUint32(24, buffer.sampleRate, true);
  view.setUint32(28, buffer.sampleRate * channels * 4, true);
  view.setUint16(32, channels * 4, true);
  view.setUint16(34, 32, true);
  tag(36, 'data');
  view.setUint32(40, frames * channels * 4, true);
  const data = [];
  for (let c = 0; c < channels; c++) data.push(buffer.getChannelData(c));
  let at = 44;
  for (let i = 0; i < frames; i++) for (let c = 0; c < channels; c++, at += 4) view.setFloat32(at, data[c][i], true);
  return out;
}

/** Promises of the sidecar uploads in flight: page.mjs waits for them before it reports. */
export const pending = [];
const nativeDecode = BaseAudioContext.prototype.decodeAudioData;
BaseAudioContext.prototype.decodeAudioData = function (encoded, success, failure) {
  const copy = encoded.slice(0);
  const promise = nativeDecode.call(this, encoded, success, failure);
  promise
    .then((decoded) => {
      const head = new Uint8Array(copy, 0, Math.min(4, copy.byteLength));
      const riff = head.length === 4 && String.fromCharCode(...head) === 'RIFF';
      if (riff) return;
      pending.push(
        crypto.subtle
          .digest('SHA-1', copy)
          .then((digest) => fetch('/decoded/' + hex(digest), { method: 'POST', body: floatWav(decoded) })),
      );
    })
    .catch(() => {});
  return promise;
};

// The impulse response of a reverb is rendered by superdough in a small OfflineAudioContext (reverbGen.mjs applyGradualLowpass) and reaches the convolver some
// milliseconds of wall time later, whenever the page gets round to it: a race against the render of the program, which in the chunked offline renderer has the first
// hits (and, when a reverb is made later, its first hits) with a convolver that has no impulse response yet, or only part of the tail. The reference should not depend on it:
// the main render (the context page.mjs announces in window.__cosicMainFrames) is started, and resumed between chunks, only when no impulse response is in flight, as if
// it took no time to make (the engine builds its impulse responses before the first block, too).
const irPending = new Set();
const settled = () => Promise.allSettled([...irPending]).then(() => new Promise((resolve) => setTimeout(resolve, 0)));
const nativeStart = OfflineAudioContext.prototype.startRendering;
OfflineAudioContext.prototype.startRendering = function () {
  if (this.length === window.__cosicMainFrames) return settled().then(() => nativeStart.call(this));
  const promise = nativeStart.call(this);
  irPending.add(promise);
  promise.finally(() => setTimeout(() => irPending.delete(promise), 0)).catch(() => {});
  return promise;
};
const resumeOwner = Object.getOwnPropertyDescriptor(OfflineAudioContext.prototype, 'resume') ? OfflineAudioContext.prototype : BaseAudioContext.prototype;
const nativeResume = resumeOwner.resume;
resumeOwner.resume = function () {
  if (this.length !== window.__cosicMainFrames) return nativeResume.call(this);
  // renderPatternAudioInChunks calls resume() once more after the last chunk; when the render is over by then Chromium rejects it ("cannot resume an offline context that has
  // not started"), which fast programs hit and slow ones do not. Nothing is left to resume then.
  return settled().then(() => (this.state !== 'suspended' || this.currentTime >= this.length / this.sampleRate ? undefined : nativeResume.call(this)));
};

// A trace of what the main render context was asked, for diagnosing the failures of Strudel's chunked render (page.mjs reports it, render.mjs prints it on an error).
export const trace = [];
const stamp = () => Math.round(performance.now());
for (const name of ['suspend', 'resume', 'startRendering']) {
  const owner = Object.getOwnPropertyDescriptor(OfflineAudioContext.prototype, name) ? OfflineAudioContext.prototype : BaseAudioContext.prototype;
  const wrapped = owner[name];
  owner[name] = function (...args) {
    if (this.length === window.__cosicMainFrames) trace.push(`${stamp()} ${name}(${args.join(',')}) state=${this.state} t=${this.currentTime}`);
    return wrapped.apply(this, args);
  };
}
