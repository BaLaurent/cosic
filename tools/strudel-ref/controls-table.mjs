// Prints the table of controls.mjs registrations that engine/src/main/kotlin/cosic/pattern/Controls.kt embeds
// (the constant `CONTROL_TABLE`), one line per statement of the file, from the oracle's own record of what it did:
//
//   node tools/strudel-ref/controls-registry.mjs    # first: writes engine/src/test/resources/parity/controls-registry.json
//   node tools/strudel-ref/controls-table.mjs       # prints the table
//
// Line format:   L<line> <keys> [*<count>] [=<aliases>] [!<exports>] <domain>      or      L<line> matrix <domain>
//   keys     `a` for a control with one key, `a|b|c` for the array form (a trailing `|` marks the array form with one key)
//   *count   a registerMultiControl call (keys, aliases as the call's arguments; `fmh2`... are made from them)
//   =aliases the rest arguments of the call, comma separated
//   !exports the global names the statement exports, when they are not the key and the aliases
//   domain   A audio, SD SuperDirt only, M MIDI, O OSC, V visual, T tonal, Z ZZFX, ? undocumented
// The domains are the hand classification of the controls table of docs/parity/spec/controls-tonal.md (section 1.2).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.COSIC_ROOT ?? path.resolve(here, '../..');
const registry = JSON.parse(fs.readFileSync(path.join(root, 'engine/src/test/resources/parity/controls-registry.json'), 'utf8'));
const spec = fs.readFileSync(path.join(root, 'docs/parity/spec/controls-tonal.md'), 'utf8').split('\n').slice(110, 202);

const domains = new Map();
for (const row of spec) {
  if (row.startsWith('#')) continue;
  for (const entry of row.split(/(?=\bL\d+\s)/)) {
    const m = /^L(\d+)\s+(.*)$/.exec(entry.trim());
    if (!m) continue;
    const tag = m[2].split(/\s+/).filter((t) => ['A', 'SD', 'M', 'O', 'V', 'T', 'Z', '?'].includes(t)).at(-1);
    if (tag === undefined) throw new Error(`no domain on line ${m[1]}`);
    domains.set(Number(m[1]), tag);
  }
}

const keysText = (r) => (r.multi ? (r.keys.length === 1 ? `${r.keys[0]}|` : r.keys.join('|')) : r.keys[0]);
const sameSet = (a, b) => a.length === b.length && a.every((x) => b.includes(x));

const lines = [];
const seenMulti = new Set();
for (const r of registry.registrations) {
  if (r.viaMulti !== null) {
    if (seenMulti.has(r.viaMulti)) continue;
    seenMulti.add(r.viaMulti);
    const group = registry.registrations.filter((x) => x.viaMulti === r.viaMulti);
    const aliasesArg = group[1].aliases.map((a) => a.slice(0, -1)); // the rest arguments, as made for i = 2
    const exported = [...new Set(group.flatMap((x) => x.exports))].sort();
    lines.push(
      `L${r.viaMulti} ${keysText(r)} *${group.length}${aliasesArg.length ? ` =${aliasesArg.join(',')}` : ''} !${exported.join(',')} A`,
    );
    continue;
  }
  if (r.line === 742) {
    if (!lines.some((l) => l.startsWith('L742 '))) lines.push('L742 matrix A');
    continue;
  }
  const domain = domains.get(r.line) ?? (() => { throw new Error(`no domain for line ${r.line}`); })();
  const defaultExports = [r.keys[0], ...r.aliases];
  const exportsText = sameSet([...new Set(defaultExports)], r.exports) ? '' : ` !${r.exports.join(',')}`;
  lines.push(`L${r.line} ${keysText(r)}${r.aliases.length ? ` =${r.aliases.join(',')}` : ''}${exportsText} ${domain}`);
}
console.log(lines.join('\n'));
