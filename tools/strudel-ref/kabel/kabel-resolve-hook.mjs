// Node module-resolution hook (registered by register-loader.mjs): maps the bare specifier '@kabelsalat/core' that
// lib.js imports to core's src/index.js (core's package.json `module`/`main` point at the bundled dist, which must not be
// used: it is an IIFE for `main`). The harness imports the same URL, so there is exactly one core instance.
import { coreEntryUrl } from './paths.mjs';

export async function resolve(specifier, context, next) {
  if (specifier === '@kabelsalat/core') return { url: coreEntryUrl, shortCircuit: true };
  return next(specifier, context);
}
