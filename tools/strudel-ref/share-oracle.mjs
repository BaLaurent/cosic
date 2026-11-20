// Reference data for the share links and the metadata comments of the app
// (engine/src/test/kotlin/cosic/io/ShareLinkTest.kt and MetadataTest.kt).
//
// It runs the code of strudel.cc itself at the pinned commit:
//
//   code2hash, hash2code           packages/core/util.mjs       the part of the URL after the `#` of a link the REPL's Share button makes
//   getMetadata                    website/src/metadata_parser.js   the `// @title ...` comments of a program
//   atob, TextEncoder, TextDecoder, decodeURIComponent   those two functions are built on them, so what the runtime does with a bad
//                                  link (an invalid character, a missing `=`, a byte that is not UTF-8) is what the app has to do
//
//   node tools/strudel-ref/share-oracle.mjs        writes engine/src/test/resources/share/oracle.json
//
// What is stored (every string is exactly what the JavaScript runtime gave; a string with a lone surrogate is written \uXXXX):
//   prebake   the default prebake script of the REPL (what Prebake.DEFAULT must be)
//   encode    {code | source, hash}            hash = code2hash(code); `source` names a corpus program (`tunes:<name>`) whose code is in parity/tunes.json
//             {.., decoded}                    what hash2code(hash) gives back, where that is not the code itself (a leading U+FEFF is
//                                              dropped by the decoder; a lone surrogate was encoded as U+FFFD)
//   decode    {hash, code} | {hash, error}     hash2code(hash), or the name of the error it threw
//   metadata  {code, tags}                     getMetadata(code), `tags` as [key, value] pairs in the order JavaScript enumerates the object
//             {source, tags}                   the same for a corpus program: `examples:<name>#<index>`, `tunes:<name>`; only those that have tags
//   corpus    the sources the metadata cases of kind `source` stand for, so that a test knows which programs have none
//
// The random cases come from a generator with a fixed seed, so running this again leaves `git status` clean.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.COSIC_ROOT ?? path.resolve(here, '../..');
const strudel = path.join(here, 'strudel');
if (!fs.existsSync(path.join(strudel, '.fetched'))) {
  throw new Error('tools/strudel-ref/strudel is missing: run `bash tools/strudel-ref/fetch.sh` first');
}
const output = path.join(root, 'engine/src/test/resources/share/oracle.json');
const commit = JSON.parse(fs.readFileSync(path.join(root, 'engine/src/test/resources/parity/meta.json'), 'utf8')).strudelCommit;

// @strudel/core announces itself on the console while it loads.
const log = console.log;
console.log = () => {};
const { code2hash, hash2code } = await import(pathToFileURL(path.join(strudel, 'packages/core/util.mjs')).href);
console.log = log;
// metadata_parser.js is an ES module in a package that does not say so: load it from its text
const metadataSource = fs.readFileSync(path.join(strudel, 'website/src/metadata_parser.js'), 'utf8');
const { getMetadata } = await import(`data:text/javascript;base64,${Buffer.from(metadataSource).toString('base64')}`);

// ---- the generator --------------------------------------------------------------------------------------------------

function mulberry32(seed) {
  return () => {
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const random = mulberry32(0x5eed);
const pick = (list) => list[Math.floor(random() * list.length)];

// ---- encode ---------------------------------------------------------------------------------------------------------

// the script the REPL puts in front of the code of a link (website/src/settings.mjs: `initialPrebakeScript`)
const settingsSource = fs.readFileSync(path.join(strudel, 'website/src/settings.mjs'), 'utf8');
const prebake = /const initialPrebakeScript = `([\s\S]*?)`;/.exec(settingsSource)[1];

const ENCODE_CODES = [
  '',
  'a',
  'ab',
  'abc',
  'abcd',
  '"c3 e3"', // the example of util.mjs
  '$: s("bd*4")\n\nall(osc)', // the example of the README
  '$: s("[bd <hh oh>]*2").bank("tr909").dec(.4)', // the code the REPL starts with
  '??>', // base64 with a `+`
  '???', // base64 with a `/`
  '>>>>>>>>>>>>>>>>>>>>', // a longer run of the same
  'é 🎵',
  'Ünïcödé — “quotes” … ✓ 日本語 한국어 العربية',
  '\u0000\u0001\t\r\n',
  's("bd")\r\n.fast(2)\r\n',
  '👨‍👩‍👧‍👦 🏳️‍🌈 🇫🇷',
  '\ufeff"c3"', // a byte order mark: the decoder of the browser drops it
  '\ud800', // a lone surrogate: TextEncoder writes U+FFFD
  'a\udc00b',
  '\ud83d',
  'x'.repeat(40000), // longer than the 0x8000 bytes the encoder hands to String.fromCharCode at a time
  '€'.repeat(12000), // 36000 bytes, with the chunk boundary inside a character
  '🎵'.repeat(9000), // 36000 bytes in 4-byte characters
  `${prebake}\n$: s("bd*4")`, // what the Share button makes: the prebake script, a line break, the code
];
const encode = ENCODE_CODES.map((code) => {
  const hash = code2hash(code);
  const entry = { code, hash };
  const decoded = hash2code(hash);
  if (decoded !== code) entry.decoded = decoded;
  return entry;
});

// the programs of the REPL: real code of every size
const tunes = JSON.parse(fs.readFileSync(path.join(root, 'engine/src/test/resources/parity/tunes.json'), 'utf8'));
for (const tune of tunes) {
  const hash = code2hash(tune.code);
  const entry = { source: `tunes:${tune.name}`, hash };
  const decoded = hash2code(hash);
  if (decoded !== tune.code) entry.decoded = decoded;
  encode.push(entry);
}

// ---- decode ---------------------------------------------------------------------------------------------------------

function decodeCase(hash) {
  try {
    return { hash, code: hash2code(hash) };
  } catch (e) {
    return { hash, error: e?.name ?? 'Error' };
  }
}

const b64 = (bytes) => Buffer.from(bytes).toString('base64');
const hashOfBytes = (bytes) => encodeURIComponent(b64(bytes));

const DECODE_HASHES = [
  'JDogcygiYmQqNCIpCgphbGwob3NjKQ%3D%3D', // the README example
  'ImMzIGUzIg%3D%3D', // the example of util.mjs
  'ImMzIGUzIg==', // not percent-encoded
  'ImMzIGUzIg', // no padding
  'ImMzIGUzIg%3D', // a single padding character where two belong
  'ImMzIGUzIg%3D%3D%3D', // too much padding
  'ImMzIGUzIg%3D%3D%3D%3D',
  'YQ%3D%3D',
  'YQ=', // one `=` after two characters: not valid base64
  'YQ',
  'Y', // length 1 of 4: never valid
  'YQ%3D%3DYQ%3D%3D', // padding in the middle
  'YQ%0A%3D%3D', // a line break inside
  'Y%20Q%3D%3D', // a space inside
  '%20YQ%3D%3D%20', // spaces around
  'Y\tQ==',
  'YQ\r\n==',
  '%09%0A%0C%0D%20', // nothing but whitespace
  '%0B', // a vertical tab is not whitespace of base64
  '', // an empty hash is the empty program
  '-_-_', // the alphabet of URLs is not the one of atob
  'Pz8+',
  'Pz8/',
  'Pz8%2B',
  'Pz8%2F',
  'Pz8%2b', // lower-case hex digits
  'AAAA',
  'AAA',
  'AA',
  'YR', // leftover bits that are not zero
  'YWJj%20', // an encoded space after complete groups
  '%', // a broken percent sequence
  '%4',
  '%G1',
  '%E0%A4%A', // a UTF-8 sequence cut short
  '%FF', // a byte that is no UTF-8 lead
  '%C0%80', // an overlong sequence
  '%ED%A0%80', // a surrogate
  '%F4%90%80%80', // beyond U+10FFFF
  '%E2%82%AC', // a euro sign: valid, but not base64 ('€' is outside its alphabet)
  'é',
  'YQ==é',
  'YQ%3D%3D#',
  'ImMzIGUzIg%3D%3D%23',
  '+++/',
  'Zm9v+',
  'a b',
];
const decode = DECODE_HASHES.map(decodeCase);

// bytes that are not UTF-8: what the browser's decoder puts in their place
const LEADS = [0xc0, 0xc1, 0xc2, 0xdf, 0xe0, 0xe1, 0xe2, 0xec, 0xed, 0xee, 0xef, 0xf0, 0xf1, 0xf3, 0xf4, 0xf5, 0xf8, 0xff];
const TAILS = [0x80, 0x8f, 0x90, 0x9f, 0xa0, 0xbf];
const ASCII = [0x41, 0x30, 0x20, 0x0a, 0x7f, 0x00];
for (let n = 0; n < 400; n++) {
  const length = Math.floor(random() * 14);
  const bytes = [];
  for (let i = 0; i < length; i++) {
    const r = random();
    bytes.push(r < 0.35 ? pick(LEADS) : r < 0.7 ? pick(TAILS) : pick(ASCII));
  }
  decode.push(decodeCase(hashOfBytes(bytes)));
}
// a byte order mark in front, in all the ways it can be cut
for (const bytes of [[0xef, 0xbb, 0xbf], [0xef, 0xbb, 0xbf, 0x61], [0xef, 0xbb], [0xef], [0xef, 0xbb, 0xbf, 0xef, 0xbb, 0xbf, 0x61], [0x61, 0xef, 0xbb, 0xbf]]) {
  decode.push(decodeCase(hashOfBytes(bytes)));
}

// ---- metadata -------------------------------------------------------------------------------------------------------

/** The cases of test/metadata.test.mjs: every `const tune = ...` of it. */
function tunesOfTheTestFile() {
  const text = fs.readFileSync(path.join(strudel, 'test/metadata.test.mjs'), 'utf8');
  const found = [];
  for (const m of text.matchAll(/const tune = (`[^`]*`|'[^']*');/g)) found.push(eval(m[1]));
  return found;
}

const CASES = [
  ...tunesOfTheTestFile(),
  '',
  '   ',
  '// @title Awesome song\r\n// @by Sam\r\n', // lines end with CR LF
  '// @title Awesome song\r// @by Sam', // and with CR alone
  '// @title Awesome song\u2028// @by Sam', // and with the line separator of JavaScript
  '// @title Awesome song\u0085// @by Sam', // NEL is a line break of Java, not of JavaScript
  '// @title Awesome\u00a0song\u00a0 // @by\u3000Sam', // no-break and ideographic spaces
  '// @title\tAwesome song', // a tab after the tag is not the separator
  '// @title  \t Awesome   \t  song  ',
  '// @ title x', // a space right after the @
  '//@title x',
  '// @',
  '// @@',
  '// @title @by',
  '// @by ,, Sam,,  ,Sandy,',
  '// @by\n// @by Sam',
  '/* @by Sam\n,\n\n Sandy, ,Sandra */',
  '/* @url https://example.com/a?b=c#d */', // `//` inside a comment
  's("bd") // @title x\n// @title y',
  '// @title x\n// @title y\n// @title z',
  '// @details a\n// @details b\n// @details c',
  '// @details  a\t\tb\u00a0c',
  '/* unterminated @title x',
  '/* @title x /* @by y */ */',
  '/**/ // @title x',
  '/* */ // @title x',
  '/*/ @title x */',
  '/* // @title x */ // @by y',
  'const s = "http://example.com"; // @title after',
  'const s = "// @title in a string"',
  's("bd") /* @title a */ /* @by b */ /* @by c */',
  '// "Awesome song"',
  '// "Awesome song',
  '// "',
  '// ""',
  '// "" @by Sam',
  '//   "Awesome song"   @by Sam',
  '// x "Awesome song"',
  '// "a" "b" "c"',
  '/* "Block title" */',
  '/* "Block title"\n@by Sam\n@by Sandy */',
  '// "Title with @by inside"',
  '// "Awesome song" @title Other',
  '// @title Other\n// "Awesome song"',
  '// @version 1.0',
  '// @version 1.0\n// @version 2.0',
  '// @license CC BY-NC-SA 4.0\n// @tag chill, ambient\n// @tag lofi',
  '// @genre a,b\n// @genre c\nnote("c")',
  '// @1 one\n// @0 zero\n// @a letter\n// @10 ten\n// @title t',
  '// @01 padded\n// @4294967295 big\n// @4294967294 largest index',
  '// @title Awesome \u{1f3b5} song',
  '// @title \ud800 lone',
  '\ufeff// @title with BOM',
  '// @title\n// @by\n// @url\n// @genre\n// @license\n// @tag',
  '// @title Tab\tin\tmiddle\n',
  '/*\n@details line one\n         line two\n\n         line three\n*/',
  '/* @by\n\tSam\n\tSandy\n*/',
  'a // b // @title nested',
  '// song @by Sam @license CC0\n// @title x',
  '///@title triple',
  '// // @title double',
];

const metadata = CASES.map((code) => ({ code, tags: Object.entries(getMetadata(code)) }));

// every program of the corpus: only those that have tags are stored, the others must have none
const corpus = [];
const examples = JSON.parse(fs.readFileSync(path.join(root, 'engine/src/test/resources/parity/examples.json'), 'utf8'));
for (const e of examples) corpus.push({ source: `examples:${e.name}#${e.index}`, code: e.code });
for (const t of tunes) corpus.push({ source: `tunes:${t.name}`, code: t.code });
for (const { source, code } of corpus) {
  const tags = Object.entries(getMetadata(code));
  if (tags.length) metadata.push({ source, tags });
}

// ---- write ----------------------------------------------------------------------------------------------------------

const result = {
  strudelCommit: commit,
  node: process.version,
  prebake,
  encode,
  decode,
  metadata,
  corpus: corpus.map((c) => c.source),
};
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, `${JSON.stringify(result, null, 1)}\n`);
console.log(`${path.relative(root, output)}: ${encode.length} encode, ${decode.length} decode, ${metadata.length} metadata cases, ${corpus.length} corpus programs`);
