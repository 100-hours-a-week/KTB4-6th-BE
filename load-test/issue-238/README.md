# Issue 238 BE Load Test

Scope: Issue #238 standalone baselines for Meeting SSE and Recording Start.

This directory contains reproducible assets for isolating Meeting SSE connections from recording, Audio WebSocket, and AI requests. It does not change production code.

## Verified Endpoints

- Local auth signup: `POST /api/v1/auth/local/signup`
- Local auth login: `POST /api/v1/auth/local/login`
- Team create: `POST /api/v1/teams`
- Team join: `POST /api/v1/team-memberships`
- Meeting create: `POST /api/v1/teams/{teamId}/meetings`
- Meeting participant join: `POST /api/v1/meetings/{meetingId}/participants`
- Meeting SSE: `GET /api/v1/meetings/{meetingId}/events`
- Recording start: `POST /api/v1/meetings/{meetingId}/recordings`
- Recording status update: `PATCH /api/v1/recordings/{recordingSessionId}`
- Audio WebSocket: `/ws/v1/recordings/{recordingSessionId}/audio?audioFormat={format}`

Authentication uses the `accessToken` cookie. Do not use an `Authorization: Bearer` header for these scripts.

## Local Environment

Start the repository-provided services:

```bash
docker compose up -d mysql redis prometheus grafana
```

If port `6379` is already allocated, do not silently change the Compose file. Either stop the conflicting local Redis or run the BE with `REDIS_PORT` pointing at a known disposable Redis.

Start BE:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

Expected local ports:

- BE API: `8080`
- Actuator management: `8081`
- Prometheus: `9090`
- Grafana: `3001`

Health and metrics checks:

```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8081/actuator/prometheus
curl http://localhost:9090/api/v1/targets
```

Grafana is provisioned with a `Prometheus` datasource. If default credentials are still active:

```bash
curl -u admin:admin 'http://localhost:3001/api/datasources/proxy/uid/PBFA97CFB590B2093/api/v1/query?query=up'
```

## Required Tools

```bash
./load-test/issue-238/scripts/check-tools.sh
```

The SSE test script requires `k6/x/sse`. The installed `k6` must be able to resolve that extension. Do not replace it with a different SSE client for baseline measurements without recording the tool change.

## SSE Extension Binary

The system `k6` is not modified for this test. If `k6/x/sse` is unavailable through the installed `k6`, build a project-local binary through Docker xk6:

```bash
docker run --rm \
  -v "$PWD":/work \
  -w /work \
  grafana/xk6 build v2.0.0 \
  --with github.com/phymbert/xk6-sse@v0.2.0 \
  --output /work/load-test/issue-238/out/k6-sse
```

Verified binary:

```bash
docker run --rm \
  -v "$PWD":/work \
  -w /work \
  --entrypoint /work/load-test/issue-238/out/k6-sse \
  grafana/xk6 version
```

Expected version:

```text
k6-sse v2.0.0 (go1.27.1, linux/arm64)
Extensions:
  github.com/phymbert/xk6-sse v0.2.0, k6/x/sse [js]
```

`load-test/issue-238/out/` is git-ignored, so the custom binary and generated test outputs are not committed.

## Fixture Preparation

Default fixture size is 50 teams with 6 members each, matching 300 distinct SSE connections while respecting the current team capacity limit of 10 members.

Fixture creation is intentionally separate from the measured SSE window.

```bash
LOAD_TEST_PASSWORD='set-a-disposable-local-password' \
  ./load-test/issue-238/scripts/prepare-sse-fixture.sh
```

Output:

- `load-test/issue-238/out/fixture.json`

The fixture file contains access tokens and must not be committed.

Configurable variables:

- `BASE_URL` default `http://localhost:8080`
- `TEAMS` default `50`
- `MEMBERS_PER_TEAM` default `6`
- `LOGIN_PREFIX` default timestamped prefix
- `FIXTURE_FILE` default `load-test/issue-238/out/fixture.json`

## SSE Baseline

Run one stage at a time. Leave enough idle time between stages to confirm previous SSE connections have closed and registry/log counts return to zero.

```bash
CONNECTIONS=1 HOLD_SECONDS=60 \
  FIXTURE_FILE=load-test/issue-238/out/fixture.json \
  ./load-test/issue-238/scripts/run-sse-stage.sh

CONNECTIONS=50 HOLD_SECONDS=60 \
  FIXTURE_FILE=load-test/issue-238/out/fixture.json \
  ./load-test/issue-238/scripts/run-sse-stage.sh

CONNECTIONS=100 HOLD_SECONDS=60 \
  FIXTURE_FILE=load-test/issue-238/out/fixture.json \
  ./load-test/issue-238/scripts/run-sse-stage.sh

CONNECTIONS=300 HOLD_SECONDS=60 \
  FIXTURE_FILE=load-test/issue-238/out/fixture.json \
  ./load-test/issue-238/scripts/run-sse-stage.sh
```

Do not run recording start, Audio WebSocket, or AI requests during this baseline.

## Prometheus Range Metrics

`run-sse-stage.sh` records stage start/end time and writes a Prometheus range-query result for the exact stage window:

```bash
START_EPOCH=<stage-start> END_EPOCH=<stage-end> \
  ./load-test/issue-238/scripts/query-prometheus-range.sh
```

Stage outputs are written under `load-test/issue-238/out/`:

- `sse-{connections}.log`
- `sse-{connections}-summary.json`
- `sse-{connections}-prometheus.txt`
- `stage-times.tsv`

## Recording Start Baseline

Run recording start without SSE, Audio WebSocket, AI WebSocket, audio chunks, recording end, or other API load.

Each measured request must use a distinct team and a distinct `WAITING` meeting. Because recording start changes meeting status and consumes credit, do not reuse a fixture after a measured run.

Prepare stage fixtures:

```bash
LOAD_TEST_PASSWORD='set-a-disposable-local-password' \
  COUNT=10 \
  FIXTURE_FILE=load-test/issue-238/out/recording-start-fixture-10.json \
  ./load-test/issue-238/scripts/prepare-recording-start-fixture.sh
```

Run a stage:

```bash
REQUESTS=10 \
  FIXTURE_FILE=load-test/issue-238/out/recording-start-fixture-10.json \
  ./load-test/issue-238/scripts/run-recording-start-stage.sh
```

Stage outputs:

- `recording-start-{requests}.log`
- `recording-start-{requests}-summary.json`
- `recording-start-{requests}-actuator.tsv`
- `recording-start-{requests}-prometheus.txt`
- `recording-start-{requests}-app.log`
- `recording-start-{requests}-db.tsv`
- `stage-times-recording-start.tsv`

Summarize:

```bash
./load-test/issue-238/scripts/summarize-recording-start-results.sh
```
