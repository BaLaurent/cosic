// Module-resolution hooks for tools/strudel-ref/midi-oracle.mjs (registered with `module.register`).
//
// packages/midi/midi.mjs imports two things that only exist in a browser page: the audio context and clock bridge of
// @strudel/webaudio, and two helpers of superdough. The oracle runs the real midi.mjs (and the real webmidi and @strudel/core
// it imports) in Node, so these two specifiers are answered with small stand-ins whose behaviour the oracle sets through
// `globalThis.__clockBridge` and `globalThis.__audioContext`. Nothing else is replaced.
const STUBS = {
  '@strudel/webaudio': `
    export const getAudioContext = () => globalThis.__audioContext;
    export const getClockBridge = () => globalThis.__clockBridge;
  `,
  '../superdough/helpers.mjs': `
    export const scheduleAtTime = (fn) => fn();
    export const ensureMinimalOutput = () => {};
  `,
};

export async function resolve(specifier, context, nextResolve) {
  if (specifier in STUBS) {
    return { url: 'data:text/javascript,' + encodeURIComponent(STUBS[specifier]), shortCircuit: true, format: 'module' };
  }
  return nextResolve(specifier, context);
}
