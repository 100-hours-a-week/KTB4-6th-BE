# Issue 238 Recording Start Baseline

## Scope

Recording start API standalone load test.

Excluded during measured stages:

- Meeting SSE
- Audio WebSocket
- AI WebSocket
- Audio chunk upload
- Recording status update / end
- Other API load

Fixture creation and smoke verification were excluded from measured windows.

## Verified API

- Method: `POST`
- URL: `/api/v1/meetings/{meetingId}/recordings`
- Auth: `Cookie: accessToken={token}`
- Expected success status: `201 Created`
- Response wrapper: `ApiResponse<RecordingSessionResponse>`

Code path checked:

`RecordingController.start()` -> `RecordingService.start()` -> meeting pessimistic lock -> active team member / joined participant validation -> team credit pessimistic lock -> active recording / in-progress meeting checks -> `RecordingSession` save -> credit use + `CreditLedger` save -> meeting status change -> `RecordingStartedEvent`.

## Fixture

Each measured request used an independent team and an independent `WAITING` meeting.

- `1`: 1 team / 1 meeting
- `10`: 10 teams / 10 meetings
- `30`: 30 teams / 30 meetings
- `50`: 50 teams / 50 meetings

For each target:

- The caller was the team leader and an `ACTIVE` `TeamMember`.
- The caller joined the meeting as a `JOINED` participant.
- Initial team credit balance was 200.
- No team/meeting was reused across measured stages.

## Smoke

Smoke fixture: 1 fresh team / 1 fresh meeting.

- HTTP status: `201`
- API response: `success=true`
- `RecordingSession` created: yes
- Meeting status: `IN_PROGRESS`
- Recording status: `RECORDING`
- Credit: 200 -> 180
- Recording credit ledger: 1 row, amount `-20`
- Hikari connection timeout increase: 0

## Results

The table uses k6 summary, direct Actuator sampling, existing `[RECORDING_START]` application logs, and DB verification. Connection timeout uses direct Actuator counter delta because Prometheus can miss short-window increments.

| Metric | 1 | 10 | 30 | 50 |
|---|---:|---:|---:|---:|
| Requests | 1 | 10 | 30 | 50 |
| Success | 1 | 10 | 10 | 10 |
| Failure | 0 | 0 | 20 | 40 |
| Avg Latency (ms) | 42.461 | 30024.697 | 30030.031 | 30058.480 |
| P95 (ms) | 42.461 | 30028.741 | 30050.751 | 30094.280 |
| P99 (ms) | 42.461 | 30029.078 | 30051.539 | 30098.677 |
| Max Latency (ms) | 42.461 | 30029.162 | 30051.661 | 30099.408 |
| Throughput (req/s) | 19.491 | 0.333 | 0.998 | 1.661 |
| Max Hikari Active | 1 | 10 | 10 | 10 |
| Min Hikari Idle | 9 | 0 | 0 | 0 |
| Max Hikari Pending | 0 | 12 | 32 | 52 |
| Max Pool Size | 10 | 10 | 10 | 10 |
| Connection Timeout Increase | 0 | 10 | 30 | 50 |
| Max Meeting Lock Wait (ms) | 3 | 1 | 5 | 1 |
| Max TeamCredit Lock Wait (ms) | 1 | 0 | 1 | 0 |
| Max Process CPU | 0.006645 | 0.047377 | 0.059742 | 0.099354 |
| Max Heap Used (bytes) | 161935968 | 184226112 | 180123032 | 179793592 |
| Max Live Threads | 53 | 55 | 74 | 94 |

## DB Verification

- `1`: 1 meeting became `IN_PROGRESS`, 1 `RECORDING` session, 1 recording ledger, credit 180.
- `10`: 10 meetings became `IN_PROGRESS`, 10 `RECORDING` sessions, 10 recording ledgers, all credits 180.
- `30`: 10 meetings became `IN_PROGRESS`; 20 remained `WAITING`. 10 `RECORDING` sessions and ledgers were created.
- `50`: 10 meetings became `IN_PROGRESS`; 40 remained `WAITING`. 10 `RECORDING` sessions and ledgers were created.

## Observations

- At concurrency 10 and above, Hikari active connections reached max pool size 10 and idle dropped to 0.
- Hikari pending increased with concurrency: 12 at 10, 32 at 30, 52 at 50.
- Hikari connection timeout counter increased during recording start alone.
- 10 concurrent requests eventually returned `201`, but latency was about 30 seconds.
- 30 and 50 concurrent requests returned only 10 successes; remaining requests returned `500 INTERNAL_SERVER_ERROR`.
- Existing logs show meeting lock and team credit lock acquisition times stayed low for successful requests. The dominant wait is connection acquisition / pool exhaustion, not row-lock wait.
- App logs show `HikariPool-1 - Connection is not available, request timed out after 30005~30006ms`.
- The timeout stack appears while handling post-commit notification work triggered by `RecordingStartedEvent`. This is an inference from the log stack, not a code change.

## Judgment

Recording start standalone reproduced the connection pool bottleneck.

The bottleneck appears in the recording start path including the `RecordingStartedEvent` follow-up work. It is not caused by SSE, Audio WebSocket, AI WebSocket, audio chunks, recording end, or other API load in this experiment.

## Next Experiment

1. Run the same recording start test with `RecordingStartedEvent` notification handling isolated or disabled in a test-only setup to confirm whether post-commit notification DB work is the pool consumer.
2. Run a diagnostic-only comparison with a larger Hikari pool to verify whether failure count tracks pool size.
3. Add temporary test-only instrumentation around transaction synchronization / event listener execution if event isolation confirms the path.

## Files

Added:

- `load-test/issue-238/recording-start-baseline.js`
- `load-test/issue-238/scripts/prepare-recording-start-fixture.sh`
- `load-test/issue-238/scripts/run-recording-start-stage.sh`
- `load-test/issue-238/scripts/verify-recording-start-stage.sh`
- `load-test/issue-238/scripts/summarize-recording-start-results.sh`
- `load-test/issue-238/results-recording-start-baseline.md`

Modified:

- `load-test/issue-238/README.md`

Generated under ignored `load-test/issue-238/out/`:

- Stage fixture JSON files
- k6 logs and summaries
- Actuator samples
- Prometheus range outputs
- Stage app-log slices
- DB verification TSV files
