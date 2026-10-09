import sse from 'k6/x/sse';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const FIXTURE_FILE = __ENV.FIXTURE_FILE || 'out/fixture.json';
const CONNECTIONS = parseInt(__ENV.CONNECTIONS || '1', 10);
const HOLD_SECONDS = parseInt(__ENV.HOLD_SECONDS || '60', 10);

const attempts = new Counter('sse_connection_attempts');
const successes = new Counter('sse_connection_successes');
const failures = new Counter('sse_connection_failures');
const connectTime = new Trend('sse_connect_time_ms', true);

const connections = new SharedArray('issue238-sse-connections', function () {
  const fixture = JSON.parse(open(FIXTURE_FILE));
  return fixture.connections;
});

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    meeting_sse_baseline: {
      executor: 'shared-iterations',
      vus: CONNECTIONS,
      iterations: CONNECTIONS,
      maxDuration: `${HOLD_SECONDS + 30}s`,
      gracefulStop: '5s',
    },
  },
};

export default function () {
  if (connections.length < CONNECTIONS) {
    throw new Error(`fixture has ${connections.length} connections, but CONNECTIONS=${CONNECTIONS}`);
  }

  const index = exec.scenario.iterationInTest % CONNECTIONS;
  const target = connections[index];
  const url = `${BASE_URL}/api/v1/meetings/${target.meetingId}/events`;
  const startedAt = Date.now();
  let opened = false;
  let connectedEventReceived = false;
  let failureRecorded = false;

  attempts.add(1);

  const response = sse.open(url, {
    method: 'GET',
    timeout: `${HOLD_SECONDS + 30}s`,
    headers: {
      Accept: 'text/event-stream',
      Cookie: `accessToken=${target.accessToken}`,
    },
    tags: {
      endpoint: 'meeting_sse_events',
      meetingId: String(target.meetingId),
    },
  }, function (client) {
    client.on('open', function () {
      opened = true;
      successes.add(1);
      connectTime.add(Date.now() - startedAt);
    });

    client.on('event', function (event) {
      if (event.name === 'CONNECTED') {
        connectedEventReceived = true;
        sleep(HOLD_SECONDS);
        client.close();
      }
    });

    client.on('error', function (error) {
      if (!failureRecorded) {
        failures.add(1);
        failureRecorded = true;
      }
      console.error(`SSE error userId=${target.userId} meetingId=${target.meetingId}: ${error.error()}`);
    });
  });

  const ok = check(response, {
    'sse status is 200': (r) => r && r.status === 200,
    'sse open event observed': () => opened,
    'CONNECTED event observed': () => connectedEventReceived,
  });

  if (!ok && !failureRecorded) {
    failures.add(1);
  }
}
