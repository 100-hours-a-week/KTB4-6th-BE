import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
const targets = JSON.parse(open(__ENV.TARGETS));
const ok = new Counter('start_ok');
const dur = new Trend('start_ms', true);
export const options = { scenarios: { s: { executor: 'per-vu-iterations', vus: targets.length, iterations: 1, maxDuration: '3m' } } };
export default function () {
  const t = targets[__VU - 1];
  const r = http.post(`http://localhost:8080/api/v1/meetings/${t.meeting}/recordings`, null,
    { headers: { Cookie: `accessToken=${t.token}` }, timeout: '120s' });
  dur.add(r.timings.duration);
  if (r.status === 201) ok.add(1);
}
