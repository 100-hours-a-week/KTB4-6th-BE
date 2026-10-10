import http from 'k6/http';
import { WebSocket } from 'k6/websockets';
import { Trend, Counter } from 'k6/metrics';
import { check } from 'k6';

const targets = JSON.parse(open('./targets.json'));
const CHUNKS = Number(__ENV.CHUNKS || 20);
const CHUNK_BYTES = Number(__ENV.CHUNK_BYTES || 4000);
const CHUNK_INTERVAL_MS = Number(__ENV.CHUNK_INTERVAL_MS || 1000);
const BASE = 'localhost:8080';

const startMs = new Trend('rec_start_ms', true);
const streamReadyMs = new Trend('ws_open_to_stream_ready_ms', true);
const endMs = new Trend('rec_end_ms', true);
const acks = new Counter('acks');
const flowOk = new Counter('flow_ok');

export const options = { scenarios: { flow: { executor: 'per-vu-iterations', vus: targets.length, iterations: 1, maxDuration: '3m' } } };

function frame(seq) {
  const buf = new ArrayBuffer(8 + CHUNK_BYTES);
  new DataView(buf).setBigUint64(0, BigInt(seq));
  return buf;
}

export default function () {
  const t = targets[__VU - 1];
  const headers = { Cookie: `accessToken=${t.token}`, 'Content-Type': 'application/json' };
  const start = http.post(`http://${BASE}/api/v1/meetings/${t.meeting}/recordings`, null, { headers });
  startMs.add(start.timings.duration);
  if (!check(start, { 'recording start 201': (r) => r.status === 201 })) {
    console.log(`team=${t.team} start failed ${start.status} ${start.body}`);
    return;
  }
  const rec = start.json('data.recordingSessionId');
  const opened = Date.now();
  let seq = 0, lastAck = 0, timer = null, ready = false;
  const ws = new WebSocket(`ws://${BASE}/ws/v1/recordings/${rec}/audio?audioFormat=webm_opus`, null, { headers: { Cookie: headers.Cookie } });
  ws.binaryType = 'arraybuffer';
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.type === 'recovery.start') ws.send(JSON.stringify({ type: 'recovery.finished', lastSequence: seq }));
    else if (m.type === 'stream.ready' && !ready) {
      ready = true;
      streamReadyMs.add(Date.now() - opened);
      timer = setInterval(() => {
        if (seq >= CHUNKS) { clearInterval(timer); setTimeout(() => ws.close(), 1500); return; }
        ws.send(frame(++seq));
      }, CHUNK_INTERVAL_MS);
    } else if (m.type === 'ack') { acks.add(1); lastAck = m.seq; }
  };
  ws.onclose = (e) => {
    if (timer) clearInterval(timer);
    const end = http.patch(`http://${BASE}/api/v1/recordings/${rec}`, JSON.stringify({ status: 'COMPLETED' }), { headers, timeout: '90s' });
    endMs.add(end.timings.duration);
    const ok = ready && lastAck === CHUNKS && end.status === 200;
    if (ok) flowOk.add(1);
    console.log(`team=${t.team} rec=${rec} start=${start.timings.duration.toFixed(0)}ms ready=${ready} sent=${seq} lastAck=${lastAck} close=${e.code} end=${end.status}/${end.timings.duration.toFixed(0)}ms`);
  };
  ws.onerror = (e) => console.log(`team=${t.team} rec=${rec} ws error ${e.error}`);
}
