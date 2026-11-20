// Reference data for the visuals of the script engine (engine/src/test/kotlin/cosic/visual/VisualGoldenTest.kt).
//
// It runs the REAL drawing code of the pinned Strudel (packages/draw: __pianoroll, the punchcard and wordfall painters, the spiral and pitchwheel; packages/webaudio:
// drawTimeScope, drawFrequencyScope, the spectrum; `Pattern.prototype.draw` with its memory; the `Drawer` of draw.mjs; `print`) in Node against a canvas that only
// records what it is told, and stores the recorded calls, so the Kotlin renderers can be compared with them call by call:
//
//   node tools/strudel-ref/visual-oracle.mjs        writes engine/src/test/resources/visual/golden.json
//
// What is stored per case (the cases are described in this file, so the JSON holds their inputs as well):
//   renderers   one drawing: `kind`, the canvas size, `options`, `time`, `drawTime`, the haps (or the analyser data) and `calls`, the recorded canvas calls.
//   loops       a `Pattern.prototype.draw` loop (pianoroll, scope, fscope, spectrum, print) run frame by frame: the pattern (`code`), the call time, the frame times,
//               and for each frame the memory of the loop (`hap.show(true)`) and the calls it made.
//   drawers     a `Drawer` with painters: the pattern, the times of `invalidate` and of the frames, and per frame the visible haps, the time and the painters.
//
// A call is `["set", property, value]` or `["call", method, [arguments]]`; a number that is not finite is stored as a string. Two normalisations make the two sides
// comparable: a `lineTo` that starts a path is stored as `moveTo` (a canvas treats it so), and a `clearRect` of the whole surface followed by `putImageData(image, -n, 0)`
// is stored as `["call", "scrollLeft", [n]]` (how the spectrum scrolls). `measureText(text).width` is `10 * text.length`.
//
// The module `superdough` that scope.mjs and spectrum.mjs import is replaced by a stand-in that serves the data of the case (no browser, no audio).
import fs from 'node:fs';
import path from 'node:path';
import { register } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.COSIC_ROOT ?? path.resolve(here, '../..');
const strudel = path.join(here, 'strudel');
if (!fs.existsSync(path.join(strudel, '.fetched'))) {
  throw new Error('tools/strudel-ref/strudel is missing: run `bash tools/strudel-ref/fetch.sh` first');
}
const COMMIT = '84ef32eec8074f90c6690ab8d6a6c204f508b182';
const pkg = (p) => pathToFileURL(path.join(strudel, 'packages', p)).href;

// the packages talk on the console while they load and run
const log = console.log;
const warn = console.warn;
console.log = () => {};
console.warn = () => {};

register(
  'data:text/javascript,' +
    encodeURIComponent(`
export async function resolve(specifier, context, nextResolve) {
  if (specifier === 'superdough') {
    return { url: 'data:text/javascript,' + encodeURIComponent('export const analysers = globalThis.__analysers; export const getAnalyzerData = (type, id) => globalThis.__analyzerData(type, id);'), shortCircuit: true };
  }
  return nextResolve(specifier, context);
}`),
  import.meta.url,
);
globalThis.__analysers = {};
globalThis.__analyzerData = () => {
  throw new Error('no analyser data set');
};

const core = await import(pkg('core/index.mjs'));
const mini = await import(pkg('mini/index.mjs'));
mini.miniAllStrings(); // what the transpiler does to the strings of a program: they are mini-notation
const draw = await import(pkg('draw/index.mjs'));
// the theme the REPL installs at startup (packages/codemirror/themes/strudel-theme.mjs), which the defaults of the app are
draw.setTheme({ ...draw.getTheme(), background: '#222', foreground: '#fff', gutterForeground: '#8a919966' });
const scope = await import(pkg('webaudio/scope.mjs'));
await import(pkg('webaudio/spectrum.mjs'));
const { Hap, TimeSpan, Fraction } = core;

// ---- a browser, as far as draw.mjs and print.mjs look at it --------------------------------------------------------------------------------------------------
let frameQueue = new Map();
let frameId = 0;
globalThis.window = globalThis;
globalThis.innerWidth = 100;
globalThis.innerHeight = 100;
globalThis.devicePixelRatio = 1;
globalThis.addEventListener = () => {};
// enough of a document for `getDrawContext()` (a call without a `ctx` option) and for the list of `print`
globalThis.document = {
  querySelector: () => null,
  createElement: () => ({ textContent: '', style: {}, getContext: () => ({ canvas: { width: 100, height: 100 } }) }),
  body: { prepend() {} },
};
globalThis.requestAnimationFrame = (fn) => {
  frameQueue.set(++frameId, fn);
  return frameId;
};
globalThis.cancelAnimationFrame = (id) => {
  frameQueue.delete(id);
};

/** Runs the frame callbacks that are waiting, once each, with the time of the clock set to [t]. */
const runFrame = (t) => {
  core.setTime(() => t);
  const due = [...frameQueue.entries()];
  frameQueue = new Map();
  for (const [, fn] of due) fn(t);
};

// ---- the recording canvas -------------------------------------------------------------------------------------------------------------------------------------
const norm = (v) => (typeof v === 'number' && !Number.isFinite(v) ? String(v) : v);

function recorder(width, height, calls) {
  const state = {};
  let hasPoint = false;
  const ctx = { canvas: { width, height } };
  for (const p of ['fillStyle', 'strokeStyle', 'globalAlpha', 'lineWidth', 'lineCap', 'font', 'textBaseline']) {
    Object.defineProperty(ctx, p, {
      get: () => state[p],
      set: (v) => {
        state[p] = v;
        calls.push(['set', p, norm(v)]);
      },
      enumerable: true,
    });
  }
  const call = (name, args) => calls.push(['call', name, args.map(norm)]);
  ctx.clearRect = (...a) => call('clearRect', a);
  ctx.fillRect = (...a) => call('fillRect', a);
  ctx.strokeRect = (...a) => call('strokeRect', a);
  ctx.fillText = (text, x, y, maxWidth) => call('fillText', maxWidth === undefined ? [String(text), x, y] : [String(text), x, y, maxWidth]);
  ctx.measureText = (text) => ({ width: 10 * String(text).length });
  ctx.beginPath = () => {
    hasPoint = false;
    call('beginPath', []);
  };
  ctx.moveTo = (x, y) => {
    hasPoint = true;
    call('moveTo', [x, y]);
  };
  ctx.lineTo = (x, y) => {
    if (!hasPoint) {
      hasPoint = true;
      call('moveTo', [x, y]);
    } else {
      call('lineTo', [x, y]);
    }
  };
  ctx.arc = (...a) => {
    hasPoint = true;
    call('arc', a);
  };
  ctx.closePath = () => call('closePath', []);
  ctx.stroke = () => call('stroke', []);
  ctx.fill = () => call('fill', []);
  ctx.getImageData = () => ({ image: true });
  ctx.putImageData = (_image, dx, dy) => {
    const last = calls[calls.length - 1];
    if (dy === 0 && last && last[0] === 'call' && last[1] === 'clearRect' && last[2][0] === 0 && last[2][1] === 0 && last[2][2] === width && last[2][3] === height) {
      calls.pop();
      calls.push(['call', 'scrollLeft', [-dx]]);
    } else {
      throw new Error('putImageData was not preceded by a clear of the whole surface');
    }
  };
  return ctx;
}

// ---- haps -------------------------------------------------------------------------------------------------------------------------------------------------------
const frac = (x) => Fraction(x);
const span = (b, e) => new TimeSpan(frac(b), frac(e));
/** A hap from a compact description: whole [b, e] (or null), part (default the whole), value, tags. */
const hap = (whole, value, { part, tags } = {}) => {
  const w = whole && span(whole[0], whole[1]);
  const p = part ? span(part[0], part[1]) : w;
  return new Hap(w, p, value, tags ? { tags } : {});
};
const fracText = (f) => `${f.s * f.n}/${f.d}`;
const spanOf = (t) => t && [fracText(t.begin), fracText(t.end)];
const hapSpec = (h) => ({ whole: spanOf(h.whole), part: spanOf(h.part), value: h.value, tags: h.context.tags ?? null });

const note = (n, begin, end, extra = {}) => hap([begin, end], { note: n, ...extra });

// ---- cases ------------------------------------------------------------------------------------------------------------------------------------------------------
const renderers = [];
const add = (name, kind, canvas, time, options, haps, extra = {}) => renderers.push({ name, kind, canvas, time, options, haps, ...extra });

const A = [note(60, 0, 1), note(64, 1, 2)];
add('pianoroll vector A', 'pianoroll', { width: 400, height: 100 }, 0, {}, A);
add('pianoroll vector B (vertical)', 'pianoroll', { width: 400, height: 100 }, 0, { vertical: 1 }, A);
add('pianoroll later time', 'pianoroll', { width: 400, height: 100 }, 0.5, {}, A);
add('pianoroll colour leaks into the next hap', 'pianoroll', { width: 300, height: 80 }, 0.25, {}, [
  hap([0, 1], { note: 60, color: 'red' }),
  hap([0, 1], { note: 62 }),
  hap([1, 2], { note: 64 }),
]);
add('pianoroll colorizeInactive 0', 'pianoroll', { width: 300, height: 80 }, 0.25, { colorizeInactive: 0, active: 'cyan', inactive: 'gray' }, [
  hap([0, 1], { note: 60, color: 'red' }),
  hap([1, 2], { note: 62, color: 'blue' }),
  hap([-1, 0], { note: 64 }),
]);
add('pianoroll flipTime flipValues', 'pianoroll', { width: 400, height: 100 }, 0.5, { flipTime: 1, flipValues: 1 }, A);
add('pianoroll vertical flips', 'pianoroll', { width: 120, height: 400 }, 0.5, { vertical: 1, flipTime: 1 }, A);
add('pianoroll vertical flipValues', 'pianoroll', { width: 120, height: 400 }, 0.5, { vertical: 1, flipValues: 1 }, A);
add('pianoroll not folded', 'pianoroll', { width: 400, height: 100 }, 0, { fold: 0 }, A);
add('pianoroll not folded, min and max', 'pianoroll', { width: 400, height: 100 }, 0, { fold: 0, minMidi: 55, maxMidi: 70 }, A);
add('pianoroll autorange', 'pianoroll', { width: 400, height: 100 }, 0, { fold: 0, autorange: 1 }, A);
add('pianoroll labels', 'pianoroll', { width: 400, height: 100 }, 0.5, { labels: 1 }, [
  hap([0, 1], { note: 'c3', s: 'sine' }),
  hap([1, 2], { s: 'bd', n: 3 }),
  hap([0, 1], { s: 'hh' }),
  hap([1, 2], { s: 'cp', label: 'clap', activeLabel: 'CLAP' }),
  hap([0, 2], { s: 'bd', label: 'plain', activeLabel: 'ACTIVE' }),
]);
add('pianoroll labels vertical with a font', 'pianoroll', { width: 200, height: 400 }, 0.5, { labels: 1, vertical: 1, fontFamily: 'serif' }, [
  hap([0, 1], { note: 'c3' }),
  hap([1, 2], { note: 'e3' }),
]);
add('pianoroll string values are rows of their own', 'pianoroll', { width: 400, height: 120 }, 0, {}, [
  hap([0, 1], { s: 'bd' }),
  hap([0, 1], { s: 'hh' }),
  hap([1, 2], { s: 'cp' }),
  hap([1, 2], { s: 'bd' }),
]);
add('pianoroll numbers and strings together', 'pianoroll', { width: 400, height: 120 }, 0, {}, [
  hap([0, 1], { s: 'bd' }),
  hap([0, 1], { note: 60 }),
  hap([1, 2], { s: 'hh' }),
  hap([1, 2], { note: 48 }),
  hap([1, 2], { s: 'a' }),
]);
add('pianoroll frequencies and note names', 'pianoroll', { width: 400, height: 100 }, 0, {}, [
  hap([0, 1], { freq: 440 }),
  hap([1, 2], { note: 'e4' }),
  hap([1, 2], { note: 'nonsense' }),
  hap([0, 1], { n: 5 }),
]);
add('pianoroll plain values', 'pianoroll', { width: 400, height: 100 }, 0, {}, [hap([0, 1], 60), hap([1, 2], 'bd')]);
add('pianoroll velocity and gain', 'pianoroll', { width: 400, height: 100 }, 0, {}, [
  hap([0, 1], { note: 60, velocity: 0.5, gain: 0.5 }),
  hap([1, 2], { note: 62, gain: 0.25 }),
]);
add('pianoroll hide inactive, no fill, smear', 'pianoroll', { width: 400, height: 100 }, 0.5, { hideInactive: 1, smear: 1, fill: 0 }, A);
add('pianoroll fillActive stroke', 'pianoroll', { width: 400, height: 100 }, 0.5, { fillActive: 1, stroke: 1, strokeActive: 0 }, A);
add('pianoroll background and playhead', 'pianoroll', { width: 400, height: 100 }, 0.5, { background: '#112233', playheadColor: 'yellow', playhead: 0.25, cycles: 8 }, A);
add('pianoroll clip shortens the bar', 'pianoroll', { width: 400, height: 100 }, 0.5, {}, [hap([0, 1], { note: 60, clip: 0.5 }), hap([1, 2], { note: 62, duration: 0.25 })]);
add('pianoroll tag filter', 'pianoroll', { width: 400, height: 100 }, 0.5, { id: 'a' }, [
  hap([0, 1], { note: 60 }, { tags: ['a'] }),
  hap([1, 2], { note: 62 }, { tags: ['b'] }),
  hap([1, 2], { note: 64 }, { tags: ['b', 'a'] }),
]);
add('pianoroll fragments', 'pianoroll', { width: 400, height: 100 }, 0.5, {}, [hap([0, 1], { note: 60 }, { part: [0.5, 1] }), hap([1, 2], { note: 62 })]);
add('pianoroll no haps', 'pianoroll', { width: 400, height: 100 }, 0, {}, []);

// punchcard and wordfall are painters: `getDrawOptions` gives them the time of the drawer
const punchOpts = [
  ['punchcard defaults', 'punchcard', {}],
  ['punchcard not folded', 'punchcard', { fold: 0 }],
  ['punchcard with cycles and playhead of the user (ignored)', 'punchcard', { cycles: 1, playhead: 0 }],
  ['wordfall defaults', 'wordfall', {}],
  ['wordfall with options', 'wordfall', { fillActive: 0, active: 'red' }],
];
for (const [name, kind, options] of punchOpts) {
  add(name, kind, { width: 200, height: 300 }, 0.5, options, [hap([0, 1], { s: 'bd' }), hap([1, 2], { s: 'hh' }), hap([-1, 0], { s: 'cp', n: 2 })], { drawTime: [-2, 2] });
}
add('punchcard with another drawer time', 'punchcard', { width: 300, height: 100 }, 1, {}, A, { drawTime: [-1, 3] });

// spiral
const S = { width: 200, height: 200 };
add('spiral vector', 'spiral', S, 0, {}, [hap([0, 1], { note: 60 })], { drawTime: [-2, 2] });
add('spiral several haps', 'spiral', S, 0.5, {}, [
  hap([0, 1], { note: 60 }),
  hap([1, 2], { note: 62, color: 'red' }),
  hap([-1, 0], { note: 64 }),
  hap([0.5, 1.5], { note: 65, color: '#00ff00' }),
], { drawTime: [-2, 2] });
add('spiral options', 'spiral', S, 0.25, { steady: 0.96, stretch: 2, size: 55, thickness: 6, cap: 'round', inset: 2, padding: 0.1, fade: 0, colorizeInactive: 1, playheadColor: 'red', playheadLength: 0.1, playheadThickness: 3, activeColor: 'white', inactiveColor: 'gray' }, [
  hap([0, 1], { note: 60 }),
  hap([1, 2], { note: 62 }),
], { drawTime: [-2, 2] });
add('spiral tag filter', 'spiral', S, 0.25, { id: 't' }, [hap([0, 1], { note: 60 }, { tags: ['t'] }), hap([0, 1], { note: 62 })], { drawTime: [-2, 2] });

// pitchwheel
add('pitchwheel vector', 'pitchwheel', S, 0, { id: 1 }, [hap([0, 1], { note: 36 }, { tags: [1] })]);
add('pitchwheel flake with several haps', 'pitchwheel', S, 0, { id: 1 }, [
  hap([0, 1], { note: 36 }, { tags: [1] }),
  hap([0, 1], { note: 43, color: 'red' }, { tags: [1] }),
  hap([0, 1], { freq: 330, gain: 0.5 }, { tags: [1] }),
  hap([0, 1], { s: 'bd' }, { tags: [1] }),
]);
add('pitchwheel polygon with a circle', 'pitchwheel', S, 0, { id: 1, mode: 'polygon', circle: 1, hapcircles: 0 }, [
  hap([0, 1], { note: 36 }, { tags: [1] }),
  hap([0, 1], { note: 40, color: 'cyan', velocity: 0.5 }, { tags: [1] }),
  hap([0, 1], { note: 43 }, { tags: [1] }),
]);
add('pitchwheel edo from the haps', 'pitchwheel', { width: 240, height: 240 }, 0, { id: 1 }, [
  hap([0, 1], { note: 36, edo: 19, root: 100 }, { tags: [1] }),
]);
add('pitchwheel degrees and labels', 'pitchwheel', { width: 240, height: 240 }, 0, { id: 1, mode: 'flake' }, [
  hap([0, 1], { note: 36, degreeIndexes: [0, 2, 4, 5, 7, 9, 11], intLabels: ['1', '2', '3', '4', '5', '6', '7'] }, { tags: [1] }),
]);
add('pitchwheel no edo', 'pitchwheel', S, 0, { id: 1, edo: 0, thickness: 5, hapRadius: 4, margin: 20 }, [hap([0, 1], { note: 40 }, { tags: [1] })]);
add('pitchwheel tag filter', 'pitchwheel', S, 0, { id: 'w' }, [hap([0, 1], { note: 36 }, { tags: ['w'] }), hap([0, 1], { note: 40 })]);

// scope, fscope
const wave = Array.from({ length: 64 }, (_, i) => Math.sin((i / 64) * 2 * Math.PI * 3) * 0.8);
add('time scope', 'timescope', { width: 500, height: 60 }, 0, { pos: 0.5, scale: 1 }, [], { data: wave });
add('time scope with a trigger', 'timescope', { width: 500, height: 60 }, 0, { pos: 0.5, scale: 1, trigger: 0.25, thickness: 2, color: 'red' }, [], { data: wave });
add('time scope not aligned', 'timescope', { width: 400, height: 100 }, 0, { align: false }, [], { data: [0.5, 0.2, -0.1, -0.3, 0.4, 0.1, -0.2, 0.3] });
add('time scope aligned', 'timescope', { width: 400, height: 100 }, 0, {}, [], { data: [0.5, 0.2, -0.1, -0.3, 0.4, 0.1, -0.2, 0.3] });
add('time scope without a falling edge', 'timescope', { width: 400, height: 100 }, 0, {}, [], { data: [0.5, 0.6, 0.7, 0.8] });
add('time scope without an analyser', 'timescope', { width: 400, height: 100 }, 0, { pos: 0.5 }, [], { data: null });
add('frequency scope', 'freqscope', { width: 400, height: 100 }, 0, {}, [], { data: [-75, -150, 0, -10, -200, -30, 5, -90] });
add('frequency scope with options', 'freqscope', { width: 300, height: 100 }, 0, { color: 'cyan', scale: 0.5, pos: 1, lean: 1, min: -100, max: -20 }, [], { data: [-75, -150, 0, -10, -200, -30, 5, -90] });
add('frequency scope with many bins (bars narrower than a pixel)', 'freqscope', { width: 10, height: 100 }, 0, {}, [], { data: Array.from({ length: 32 }, (_, i) => -i * 5) });

// ---- loops: Pattern.prototype.draw, frame by frame ---------------------------------------------------------------------------------------------------
const loops = [];
const addLoop = (name, kind, code, options, begin, frames, extra = {}) => loops.push({ name, kind, code, options, begin, frames, ...extra });

addLoop('pianoroll loop', 'pianoroll', 'note("c e g").s("sawtooth")', {}, 0, [0, 0.05, 0.1, 0.3, 0.9, 1.5, 3.2]);
addLoop('pianoroll loop started later (past haps are not shown, future ones are)', 'pianoroll', 'note("c e <g a>")', { cycles: 2, playhead: 0.25 }, 3.4, [3.4, 3.45, 3.6, 4.5]);
addLoop('pianoroll loop with overscan and hideNegative', 'pianoroll', 'note("c e").slow(2)', { overscan: 1, hideNegative: 1 }, 0, [0, 0.1, 0.5, 2.5]);
addLoop('pianoroll loop catches up at most 0.1 cycles', 'pianoroll', 's("bd*4")', {}, 0, [0, 0.01, 1.0, 1.01]);
addLoop('pianoroll loop on a stack', 'pianoroll', 'stack(s("bd*2"), note("c e g").s("sine"))', { labels: 1 }, 0, [0, 0.1, 0.6]);
addLoop('scope loop', 'scope', 's("bd*4").color("red")', { id: 1 }, 0, [0, 0.1, 0.3], { data: wave });
addLoop('scope loop without colour', 'scope', 's("bd*4")', { id: 'w', pos: 0.5, scale: 1 }, 0, [0, 0.1], { data: wave });
addLoop('scope loop without an analyser', 'scope', 's("bd*4")', {}, 0, [0, 0.1], { data: null });
addLoop('fscope loop', 'fscope', 's("bd*4").color("red")', { color: 'cyan' }, 0, [0, 0.1], { data: [-75, -150, 0, -10] });
addLoop('fscope loop with smear', 'fscope', 's("bd*4")', { smear: 0.5 }, 0, [0, 0.1], { data: [-75, -150, 0, -10] });
addLoop('spectrum loop', 'spectrum', 's("bd*4").color("blue")', { speed: 2 }, 0, [0, 0.1, 0.2], { data: [-75, -150, 0, -10, -20, -30, -80, -40] });
addLoop('spectrum loop keeps its last colour', 'spectrum', 'stack(s("bd").color("green"), s("hh").slow(4).late(0.5))', {}, 0, [0, 0.1, 0.6, 0.8], { data: [-75, -150, 0, -10] });
addLoop('spectrum loop without an analyser', 'spectrum', 's("bd*4")', {}, 0, [0, 0.1], { data: null });
addLoop('print loop', 'print', 's("bd sd hh*2 cp")', { id: 'p' }, 0, [0, 0.1, 0.3, 0.6, 1.2]);
addLoop('print loop with round 0 and a padding', 'print', 's("bd sd")', { id: 'p', round: 0, padding: 4 }, 0, [0, 0.6, 1.2, 2.2]);
addLoop('print loop with the options of the call', 'print', 's("bd*3").n("1 2")', { id: 'p', round: 2, padding: 1, maxLines: 3 }, 0, [0, 0.4, 0.8, 1.2, 1.6]);

// ---- drawers: the Drawer of draw.mjs with painters --------------------------------------------------------------------------------------------------
const drawers = [];
const addDrawer = (name, code, invalidateAt, frames, extra = {}) => drawers.push({ name, code, invalidateAt, frames, ...extra });
addDrawer('drawer with a spiral', 's("bd*2").spiral()', 0, [0, 0.01, 0.2, 0.25, 1, 5]);
addDrawer('drawer with a punchcard in a stack', 'stack(s("bd*2").punchcard(), note("c e").s("sine"))', 0, [0, 0.1, 0.2, 0.5]);
addDrawer('drawer without painters', 's("bd*2")', 0, [0, 0.1]);
addDrawer('drawer invalidated while it plays', 's("bd*2").spiral()', 1.5, [1.5, 1.6, 1.7, 2.5], { earlier: { code: 's("hh*4").spiral()', invalidateAt: 0, frames: [0, 0.5, 1, 1.4] } });
addDrawer('drawer with two painters of one pattern', 's("bd*2").spiral().pitchwheel()', 0, [0, 0.1]);
addDrawer('drawer with a painter in a cat', 'cat(s("bd").spiral(), s("hh"))', 0, [0, 0.5, 1.2]);

// ---- running the cases --------------------------------------------------------------------------------------------------------------------------------------
const hapsOf = (specs) => specs.map((h) => hap(h.whole, h.value, { part: h.part, tags: h.tags }));

function render(c) {
  const calls = [];
  const ctx = recorder(c.canvas.width, c.canvas.height, calls);
  const haps = c.haps.map((h) => (h instanceof Hap ? h : hap(h.whole, h.value, h)));
  c.haps = haps.map(hapSpec);
  switch (c.kind) {
    case 'pianoroll':
      draw.__pianoroll({ ...c.options, time: c.time, ctx, haps });
      break;
    case 'punchcard':
      draw.getPunchcardPainter(c.options)(ctx, c.time, haps, c.drawTime);
      break;
    case 'wordfall':
      core.silence.wordfall(c.options).getPainters()[0](ctx, c.time, haps, c.drawTime);
      break;
    case 'spiral':
      core.silence.spiral(c.options).getPainters()[0](ctx, c.time, haps, c.drawTime);
      break;
    case 'pitchwheel':
      draw.pitchwheel({ ...c.options, time: c.time, ctx, haps });
      break;
    case 'timescope':
    case 'freqscope': {
      const id = c.options.id ?? 1;
      const data = c.data && Float32Array.from(c.data);
      globalThis.__analyzerData = (type, i) => {
        if (i !== id) throw new Error(`unexpected analyser id ${i}`);
        return data;
      };
      const analyser = data ? { frequencyBinCount: data.length } : undefined;
      (c.kind === 'timescope' ? scope.drawTimeScope : scope.drawFrequencyScope)(analyser, { ...c.options, ctx });
      break;
    }
    default:
      throw new Error(`unknown kind ${c.kind}`);
  }
  c.calls = calls;
}
for (const c of renderers) render(c);

/** Evaluates a pattern in the scope of the packages: strings are mini-notation through the string parser the mini package installed. */
function evaluate(code) {
  const names = Object.keys(core).filter((k) => /^[A-Za-z_$][\w$]*$/.test(k));
  return new Function(...names, `return (${code});`)(...names.map((k) => core[k]));
}

// what `draw` hands to the function of a loop is the memory of the loop: take a copy of it at every frame
let memories = [];
const draw0 = core.Pattern.prototype.draw;
core.Pattern.prototype.draw = function (fn, options) {
  return draw0.call(
    this,
    (haps, ...rest) => {
      memories.push(haps.map((h) => h.show(true)));
      return fn(haps, ...rest);
    },
    options,
  );
};

let loopNumber = 0;
function runLoop(c) {
  const calls = [];
  const ctx = recorder(c.canvas?.width ?? 500, c.canvas?.height ?? 100, calls);
  c.canvas = { width: ctx.canvas.width, height: ctx.canvas.height };
  // every case has a loop of its own (`memory[id]` of draw.mjs outlives a case)
  const id = `loop${++loopNumber}`;
  c.id = id;
  const options = { ...c.options, id };
  frameQueue = new Map();
  for (const k of Object.keys(globalThis.__analysers)) delete globalThis.__analysers[k]; // the object the stand-in module exports
  const data = c.data === undefined ? null : c.data && Float32Array.from(c.data);
  if (data) globalThis.__analysers[id] = { frequencyBinCount: data.length };
  globalThis.__analyzerData = () => data;
  core.setTime(() => c.begin);
  const pat = evaluate(c.code).tag(id);
  const el = { children: [], follow: false };
  let result;
  switch (c.kind) {
    case 'pianoroll':
      result = pat.pianoroll({ ...options, ctx });
      break;
    case 'scope':
      result = pat.scope({ ...options, ctx });
      break;
    case 'fscope':
      result = pat.fscope({ ...options, ctx });
      break;
    case 'spectrum':
      result = pat.spectrum({ ...options, ctx });
      break;
    case 'print': {
      el.appendChild = (line) => {
        el.children.push(line);
      };
      Object.defineProperty(el, 'childElementCount', { get: () => el.children.length });
      el.removeChild = () => {
        el.children.shift();
      };
      Object.defineProperty(el, 'firstChild', { get: () => el.children[0] });
      result = pat.print({ ...options, el });
      break;
    }
    default:
      throw new Error(`unknown loop kind ${c.kind}`);
  }
  c.options = options;
  c.analyzed = result.queryArc(0, 1).map((h) => h.show(true));
  c.out = [];
  for (const t of c.frames) {
    calls.length = 0;
    memories = [];
    runFrame(t);
    c.out.push({ time: t, memory: memories[0] ?? null, calls: calls.slice(), lines: c.kind === 'print' ? el.children.map((l) => l.textContent) : undefined });
  }
  frameQueue = new Map();
}
for (const c of loops) runLoop(c);

function runDrawer(c) {
  const shown = (haps) => haps.map((h) => h.show(true));
  const seen = [];
  const drawer = new draw.Drawer((haps, time, _drawer, painters) => seen.push({ haps: shown(haps), time, painters: new Set(painters).size }), [-2, 2]);
  let now = 0;
  const scheduler = { now: () => now, pattern: core.silence };
  const run = (code, invalidateAt, frames, out) => {
    scheduler.pattern = evaluate(code);
    now = invalidateAt;
    drawer.invalidate(scheduler, invalidateAt);
    out.invalidated = { visible: shown(drawer.visibleHaps), painters: new Set(drawer.painters).size, total: drawer.painters.length };
    out.frames = [];
    for (const t of frames) {
      now = t;
      seen.length = 0;
      drawer.framer.onFrame();
      out.frames.push({ time: t, frame: seen[0] ?? null, visible: shown(drawer.visibleHaps) });
    }
  };
  drawer.scheduler = scheduler;
  if (c.earlier) run(c.earlier.code, c.earlier.invalidateAt, c.earlier.frames, (c.earlier.out = {}));
  c.out = {};
  run(c.code, c.invalidateAt, c.frames, c.out);
}
for (const c of drawers) runDrawer(c);

console.log = log;
console.warn = warn;
const doc = {
  generator: 'tools/strudel-ref/visual-oracle.mjs',
  commit: COMMIT,
  theme: { foreground: draw.getTheme().foreground, gutterForeground: draw.getTheme().gutterForeground, background: draw.getTheme().background },
  source: `the drawing code of packages/draw and packages/webaudio at the pinned commit, run in Node ${process.version} against a recording canvas`,
  renderers,
  loops,
  drawers,
};
// one case per line: a diff of the file shows which case changed
const replacer = (k, v) => (typeof v === 'number' && !Number.isFinite(v) ? String(v) : v);
const list = (cases) => `[\n${cases.map((c) => '  ' + JSON.stringify(c, replacer)).join(',\n')}\n ]`;
const { renderers: r, loops: l, drawers: d, ...head } = doc;
const text = `{\n${Object.entries(head).map(([k, v]) => ` ${JSON.stringify(k)}: ${JSON.stringify(v)}`).join(',\n')},\n "renderers": ${list(r)},\n "loops": ${list(l)},\n "drawers": ${list(d)}\n}\n`;
const out = path.join(root, 'engine/src/test/resources/visual/golden.json');
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, text);
console.log(`${path.relative(root, out)}: ${renderers.length} renderers, ${loops.length} loops, ${drawers.length} drawers, ${renderers.reduce((n, c) => n + c.calls.length, 0)} calls`);
