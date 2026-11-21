// Builds the reference page (page.mjs) inside the pinned Strudel workspace: render.mjs copies this file next to it, in <checkout>/cosic-audio/, so that
// @strudel/* and superdough resolve to the packages of the checkout. The audioworklet plugin is the workspace's own (the one the website uses).
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import bundleAudioWorkletPlugin from '../packages/vite-plugin-bundle-audioworklet/vite-plugin-bundle-audioworklet.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const packages = path.resolve(here, '../packages');

export default {
  root: here,
  base: './',
  cacheDir: path.join(here, '.vite'),
  logLevel: 'warn',
  plugins: [bundleAudioWorkletPlugin()],
  resolve: {
    alias: [
      { find: /^@strudel\/soundfonts$/, replacement: path.join(packages, 'soundfonts/index.mjs') },
      { find: /^superdough$/, replacement: path.join(packages, 'superdough/index.mjs') },
      { find: /^superdough\/(.*)$/, replacement: path.join(packages, 'superdough') + '/$1' },
    ],
  },
  build: { target: 'esnext', outDir: 'dist', emptyOutDir: true, minify: false, sourcemap: false, chunkSizeWarningLimit: 1 << 30 },
};
