// Reference bytes for the MQTT output (engine/src/main/kotlin/cosic/io/mqtt/): what Paho (paho-mqtt 1.1.0, the client packages/mqtt/mqtt.mjs
// uses) writes to its WebSocket for CONNECT and PUBLISH, with the options mqtt.mjs gives it (keep alive 60, clean session, MQTT 3.1.1).
//
//   node tools/strudel-ref/mqtt-oracle.mjs
//
// Output  engine/src/test/resources/mqtt/paho.json   { connect: [...], publish: [...], publishLengths: [...] }
// Needs the checkout of tools/strudel-ref/strudel with its packages installed (bash tools/strudel-ref/fetch.sh).
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.COSIC_ROOT ?? path.resolve(here, '../..');
const require = createRequire(path.join(here, 'strudel/packages/mqtt/'));

const hex = (buffer) => Buffer.from(buffer).toString('hex');
let sockets = [];

class FakeWebSocket {
  constructor(url, protocols) {
    this.url = url;
    this.protocols = protocols;
    this.sent = [];
    sockets.push(this);
    setTimeout(() => this.onopen && this.onopen({}), 0);
  }
  send(data) {
    this.sent.push(Buffer.from(data));
    if (this.sent.length === 1 && this.sent[0][0] === 0x10) {
      const connack = new Uint8Array([0x20, 0x02, 0x00, 0x00]).buffer;
      setTimeout(() => this.onmessage && this.onmessage({ data: connack }), 0);
    }
  }
  close() {}
}
globalThis.WebSocket = FakeWebSocket;
const Paho = require('paho-mqtt');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// mqtt.mjs: new Paho.Client(host, client); cx.connect({ onSuccess, onFailure, userName?, password? })
async function session(host, clientId, auth, publishes) {
  sockets = [];
  const cx = new Paho.Client(host, clientId);
  let ok = false;
  const props = { onSuccess: () => (ok = true), onFailure: (e) => { throw new Error(JSON.stringify(e)); } };
  if (auth.userName !== undefined) props.userName = auth.userName;
  if (auth.password !== undefined) props.password = auth.password;
  cx.connect(props);
  await sleep(20);
  if (!ok) throw new Error('not connected');
  const out = [];
  for (const [topic, payload] of publishes) {
    const m = new Paho.Message(payload);
    m.destinationName = topic;
    cx.send(m);
  }
  await sleep(5);
  const ws = sockets[0];
  return { url: ws.url, protocols: ws.protocols, packets: ws.sent.map(hex) };
}

const result = { connect: [], publish: [], publishLengths: [] };
const connects = [
  ['c', {}],
  ['c', { userName: 'u', password: 'p' }],
  ['c', { userName: 'u' }],
  ['strudel-123456', { userName: 'üser', password: 'pässwörd' }],
  ['', {}],
];
for (const [clientId, auth] of connects) {
  const s = await session('ws://localhost:9001/mqtt', clientId, auth, []);
  result.connect.push({ clientId, userName: auth.userName ?? null, password: auth.password ?? null, url: s.url, protocols: s.protocols, hex: s.packets[0] });
}
const publishes = [
  ['/t', 'hi'],
  ['/strudel-pattern', '{"s":"sax","speed":2,"duration":0.5,"cps":0.5}'],
  ['/a/b', '{"topic":["a","b"],"n":1,"duration":0.5,"cps":0.5}'],
  ['/ü/€', 'héllo €'],
  ['/t', ''],
  ['/e', '😀 ok'],
];
const s = await session('ws://localhost:9001/mqtt', 'c', {}, publishes);
publishes.forEach(([topic, payload], i) => result.publish.push({ topic, payload, hex: s.packets[i + 1] }));
for (const n of [0, 1, 123, 124, 16379, 16380, 2097147, 2097148]) {
  const t = await session('ws://localhost:9001/mqtt', 'c', {}, [['/t', 'x'.repeat(n)]]);
  const p = Buffer.from(t.packets[1], 'hex');
  result.publishLengths.push({ payloadBytes: n, totalBytes: p.length, head: p.subarray(0, 6).toString('hex') });
}
const file = path.join(root, 'engine/src/test/resources/mqtt/paho.json');
fs.writeFileSync(file, JSON.stringify(result, null, 1) + '\n');
console.log('wrote', file, result.connect.length, 'connects', result.publish.length, 'publishes');
process.exit(0);
