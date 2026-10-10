import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
const targets = JSON.parse(open(__ENV.TARGETS));
const ok = new Counter('end_ok');
const dur = new Trend('end_ms', true);
export const options = { scenarios: { s: { executor: 'per-vu-iterations', vus: targets.length, iterations: 1, maxDuration: '3m' } } };
export default function () {
  const t = targets[__VU - 1];
  const r = http.patch(`http://localhost:8080/api/v1/recordings/${t.rec}`, JSON.stringify({ status: 'COMPLETED' }),
    { headers: { Cookie: `accessToken=${t.token}`, 'Content-Type': 'application/json' }, timeout: '120s' });
  dur.add(r.timings.duration);
  if (r.status === 200) ok.add(1);
}
