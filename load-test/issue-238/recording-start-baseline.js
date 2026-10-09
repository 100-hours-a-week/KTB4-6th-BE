import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const FIXTURE_FILE = __ENV.FIXTURE_FILE || 'load-test/issue-238/out/recording-start-fixture.json';
const REQUESTS = parseInt(__ENV.REQUESTS || '1', 10);
const EXPECTED_STATUS = parseInt(__ENV.EXPECTED_STATUS || '201', 10);
const REQUEST_TIMEOUT = __ENV.REQUEST_TIMEOUT || '240s';

const attempts = new Counter('recording_start_attempts');
const successes = new Counter('recording_start_successes');
const failures = new Counter('recording_start_failures');
const latency = new Trend('recording_start_latency_ms', true);

const targets = new SharedArray('issue238-recording-start-targets', function () {
  const fixture = JSON.parse(open(FIXTURE_FILE));
  return fixture.targets;
});

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    recording_start_baseline: {
      executor: 'per-vu-iterations',
      vus: REQUESTS,
      iterations: 1,
      maxDuration: '300s',
      gracefulStop: '5s',
    },
  },
};

export default function () {
  if (targets.length < REQUESTS) {
    throw new Error(`fixture has ${targets.length} targets, but REQUESTS=${REQUESTS}`);
  }

  const index = exec.vu.idInTest - 1;
  const target = targets[index];
  const url = `${BASE_URL}/api/v1/meetings/${target.meetingId}/recordings`;

  attempts.add(1);
  const response = http.post(url, null, {
    headers: {
      Accept: 'application/json',
      Cookie: `accessToken=${target.accessToken}`,
    },
    tags: {
      endpoint: 'recording_start',
      meetingId: String(target.meetingId),
      teamId: String(target.teamId),
    },
    timeout: REQUEST_TIMEOUT,
  });
  latency.add(response.timings.duration);

  let body = {};
  try {
    body = response.json();
  } catch (error) {
    body = {};
  }

  const ok = check(response, {
    [`recording start status is ${EXPECTED_STATUS}`]: (r) => r.status === EXPECTED_STATUS,
    'recording start response success is true': () => body.success === true,
    'recording session id returned': () => body.data && Number.isInteger(body.data.recordingSessionId),
  });

  if (ok) {
    successes.add(1);
    return;
  }

  failures.add(1);
  console.error(
    `recording start failed index=${index} userId=${target.userId} teamId=${target.teamId} `
    + `meetingId=${target.meetingId} status=${response.status} body=${response.body}`,
  );
}
