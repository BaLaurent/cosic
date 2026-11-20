// Shared by tools/strudel-ref/interpreter-oracle.mjs (which runs it in Node) and InterpreterOracleTest (which runs it in
// the Kotlin interpreter). It uses nothing but the language and three helpers each side defines: isArray(v), keys(o) and
// hasOwn(o, k), which are Array.isArray, Object.keys and Object.prototype.hasOwnProperty.call.
function quote(s) { return '"' + s + '"'; }
function showNumber(n) { return n === 0 && 1 / n < 0 ? '-0' : '' + n; }
function show(v) {
  const t = typeof v;
  if (t === 'undefined') return 'undefined';
  if (t === 'string') return quote(v);
  if (t === 'number') return showNumber(v);
  if (t === 'boolean') return '' + v;
  if (t === 'function') return 'function ' + v.name + '/' + v.length;
  if (v === null) return 'null';
  if (isArray(v)) {
    let out = '';
    for (let i = 0; i < v.length; i++) out += (i ? ',' : '') + show(v[i]);
    return '[' + out + ']';
  }
  const ks = keys(v);
  let out = '';
  for (let i = 0; i < ks.length; i++) out += (i ? ',' : '') + ks[i] + ':' + show(v[ks[i]]);
  return '{' + out + '}';
}
function run(f) {
  try {
    return show(f());
  } catch (e) {
    return 'THROW ' + (typeof e === 'object' && e !== null ? e.name + ': ' + e.message : 'value ' + show(e));
  }
}
