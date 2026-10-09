# Issue 238 SSE Baseline Result

Date: 2026-10-09
Branch: `perf/#238-be-load-test`

## Scope

Meeting SSE standalone baseline only. Recording start, Audio WebSocket, AI requests, and other API load were intentionally excluded from the measured window.

No performance improvement code was changed in this pass.

## Environment

- BE local profile: `UP` at `http://localhost:8081/actuator/health`
- Actuator Prometheus endpoint: available at `http://localhost:8081/actuator/prometheus`
- MySQL: `meety-mysql` Docker health `healthy`
- Prometheus target: `host.docker.internal:8081`, health `up`, scrape interval `15s`
- Grafana datasource: `Prometheus` proxy query `up` returned `1`
- Redis: existing `redis-ktb` container returned `PONG`; Redis port work was not expanded
- Post-test Hikari state: Active `0`, Idle `10`, Pending `0`

## Tooling

- System `k6`: `k6 v2.3.0 (commit/devel, go1.27.1, darwin/arm64)`
- `K6_AUTO_EXTENSION_RESOLUTION`: unset
- `sse-baseline.js` import: `import sse from 'k6/x/sse';`
- System `k6/x/sse` import check: unavailable, failed with `invalid build parameters: unknown dependency : k6/x/sse`
- Custom binary: Docker `grafana/xk6` image, repository-local `load-test/issue-238/out/k6-sse`
- Custom binary version: `k6-sse v2.0.0 (go1.27.1, linux/arm64)`
- Extension: `github.com/phymbert/xk6-sse v0.2.0, k6/x/sse [js]`

Build command is documented in `load-test/issue-238/README.md`. The binary is under `out/`, which is git-ignored.

## Execution Windows

All stages used `HOLD_SECONDS=60`.

| Connections | Start | End |
| ---: | --- | --- |
| 1 | 2026-10-09T21:07:55+09:00 | 2026-10-09T21:08:55+09:00 |
| 50 | 2026-10-09T21:09:09+09:00 | 2026-10-09T21:10:09+09:00 |
| 100 | 2026-10-09T21:10:31+09:00 | 2026-10-09T21:11:32+09:00 |
| 300 | 2026-10-09T21:11:42+09:00 | 2026-10-09T21:12:43+09:00 |

## Result Table

| Metric | 1 | 50 | 100 | 300 |
| --- | ---: | ---: | ---: | ---: |
| Attempted Connections | 1 | 50 | 100 | 300 |
| Successful Connections | 1 | 50 | 100 | 300 |
| Failed Connections | 0 | 0 | 0 | 0 |
| Avg Connect Latency | 25 ms | 40.42 ms | 149.9 ms | 220.36 ms |
| P95 | 25 ms | 57 ms | 192 ms | 320 ms |
| P99 | 25 ms | 58 ms | 194.01 ms | 331.02 ms |
| Max Hikari Active | 2 | 0 | 0 | 0 |
| Min Hikari Idle | 8 | 10 | 10 | 10 |
| Max Hikari Pending | 0 | 0 | 0 | 0 |
| Connection Timeouts | 0 | 0 | 0 | 0 |
| Max Process CPU | 0.00018508627402743675 | 0.00123938295239854 | 0.0030801345246544184 | 0.006498625566018634 |
| Max JVM Heap Used | 100,904,640 B | 105,098,944 B | 123,585,912 B | 135,237,272 B |
| Max Live Threads | 52 | 92 | 127 | 242 |

## Additional Prometheus Metrics

| Metric | 1 | 50 | 100 | 300 |
| --- | ---: | ---: | ---: | ---: |
| Hikari Max | 10 | 10 | 10 | 10 |
| Hikari Acquire Seconds Max | 0.003 | 0.037 | 0.128 | 0.266 |
| GC Pause Count Increase | 0 | 1 | 2 | 6 |
| GC Pause Seconds Max | 0 | 0.009 | 0.017 | 0.017 |

## Application Log Notes

The application code emits the requested phases from `MeetingSseService.connect()`:

- `SERVICE_ENTER`
- `DB_VALIDATION_COMPLETED`
- `EMITTER_REGISTERED`
- `CONNECTED_EVENT_SENT`
- `SERVICE_RETURN_BEFORE`

It also logs Hikari `active`, `idle`, `pending`, transaction state `txActive`, and connect elapsed timings. The local `bootRun` process writes stdout/stderr to pipes, not to a repository log file, and `/actuator/logfile` is not available without authentication. Durable artifacts saved in this directory are therefore the k6 logs, k6 summary JSON files, stage timestamps, and Prometheus range-query outputs.

## Confirmed Facts

- All stages completed with `checks_succeeded=100%`.
- `sse status is 200`, `sse open event observed`, and `CONNECTED event observed` passed for 1, 50, 100, and 300 connections.
- Authentication through the `accessToken` Cookie worked for every attempted SSE connection.
- Each stage held the SSE connection for about 60 seconds and then completed normally.
- Hikari Pending was `0` for every Prometheus range query.
- Hikari Connection Timeout increase was `0` for every stage.
- After the baseline, Hikari returned to Active `0`, Idle `10`, Pending `0`.
- At 300 connections, p95 was `320 ms` and p99 was `331.02 ms`.
- Prometheus scrape interval is `15s`, so very short DB-active spikes inside `connect()` can be missed by range samples. This affects interpretation of Active/Idle spikes, but Pending and timeout remained `0`.

## Not Confirmed Yet

- This does not prove the recording-start path is safe under load.
- This does not cover Audio WebSocket traffic.
- This does not cover AI request or summary/report load.
- This does not prove that DB-active spikes never happen inside the sub-second SSE connect path; the current Prometheus scrape interval is too coarse for that.

## Judgment

SSE-only load up to 300 concurrent connections did not reproduce connection-pool exhaustion:

- 300 successful SSE connections
- Hikari Pending `0`
- Hikari Connection Timeout increase `0`
- No sustained Active saturation was observed in Prometheus range data
- p95/p99 stayed under 1 second

Conclusion for this scope: SSE standalone load is not currently reproduced as the connection-pool bottleneck. The next test target should be recording start alone, still without Audio WebSocket and AI traffic.
