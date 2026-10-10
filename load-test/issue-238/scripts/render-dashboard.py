#!/usr/bin/env python3
from __future__ import annotations

import csv
import html
import json
import math
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "out"
CHART_DIR = OUT_DIR / "charts"

RECORDING_STAGES = [1, 10, 30, 50]
SSE_STAGES = [1, 50, 100, 300]

NOTIFICATION_SPLIT_RECORDING = [
    {"stage": 1, "requests": 1, "success": 1, "failure": 0, "max_s": 0.048, "timeout_logs": 0},
    {"stage": 10, "requests": 10, "success": 10, "failure": 0, "max_s": 0.28, "timeout_logs": 0},
    {"stage": 30, "requests": 30, "success": 30, "failure": 0, "max_s": 0.64, "timeout_logs": 0},
    {"stage": 50, "requests": 50, "success": 50, "failure": 0, "max_s": 0.92, "timeout_logs": 0},
]

ASYNC_RECORDING = [
    {"stage": 1, "requests": 1, "success": 1, "failure": 0, "max_s": 0.075, "timeout_logs": 0},
    {"stage": 10, "requests": 10, "success": 10, "failure": 0, "max_s": 0.17, "timeout_logs": 0},
    {"stage": 30, "requests": 30, "success": 30, "failure": 0, "max_s": 1.10, "timeout_logs": 0},
    {"stage": 50, "requests": 50, "success": 50, "failure": 0, "max_s": 0.73, "timeout_logs": 0},
]

ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")

BG = "#0f1117"
PANEL = "#181b20"
GRID = "#2a2f3a"
TEXT = "#d8d9da"
MUTED = "#8b949e"
GREEN = "#73bf69"
RED = "#f2495c"
BLUE = "#5794f2"
YELLOW = "#f2cc0c"
CYAN = "#56b6c2"
ORANGE = "#ff9830"
PURPLE = "#b877d9"


def read_json(path: Path) -> dict:
    with path.open() as f:
        return json.load(f)


def read_prometheus_kv(path: Path) -> dict[str, float]:
    values: dict[str, float] = {}
    if not path.exists():
        return values
    for line in path.read_text().splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        try:
            values[key] = float(value)
        except ValueError:
            continue
    return values


def read_tsv_rows(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return []
    with path.open() as f:
        return list(csv.DictReader(f, delimiter="\t"))


def number(value: str | int | float | None, default: float = 0.0) -> float:
    if value is None or value == "NA":
        return default
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def column_values(rows: list[dict[str, str]], column: str) -> list[float]:
    return [number(row.get(column)) for row in rows if row.get(column) not in (None, "NA")]


def max_column(rows: list[dict[str, str]], column: str, default: float = 0.0) -> float:
    values = column_values(rows, column)
    return max(values) if values else default


def min_column(rows: list[dict[str, str]], column: str, default: float = 0.0) -> float:
    values = column_values(rows, column)
    return min(values) if values else default


def delta_column(rows: list[dict[str, str]], column: str, default: float = 0.0) -> float:
    values = column_values(rows, column)
    return values[-1] - values[0] if values else default


def parse_recording_log(path: Path) -> dict[str, float]:
    values = {
        "active_max": 0.0,
        "idle_min": math.inf,
        "pending_max": 0.0,
        "meeting_lock_max": 0.0,
        "credit_lock_max": 0.0,
    }
    if not path.exists():
        values["idle_min"] = 0.0
        return values

    for raw_line in path.read_text(errors="ignore").splitlines():
        line = ANSI_RE.sub("", raw_line)
        if "[HIKARI]" in line:
            active = re.search(r"active=([0-9-]+)", line)
            idle = re.search(r"idle=([0-9-]+)", line)
            pending = re.search(r"pending=([0-9-]+)", line)
            if active:
                values["active_max"] = max(values["active_max"], number(active.group(1)))
            if idle:
                values["idle_min"] = min(values["idle_min"], number(idle.group(1)))
            if pending:
                values["pending_max"] = max(values["pending_max"], number(pending.group(1)))
        if "meeting lock acquired" in line:
            elapsed = re.search(r"elapsedMs=([0-9]+)", line)
            if elapsed:
                values["meeting_lock_max"] = max(values["meeting_lock_max"], number(elapsed.group(1)))
        if "credit lock acquired" in line:
            elapsed = re.search(r"elapsedMs=([0-9]+)", line)
            if elapsed:
                values["credit_lock_max"] = max(values["credit_lock_max"], number(elapsed.group(1)))

    if values["idle_min"] is math.inf:
        values["idle_min"] = 0.0
    return values


def metric_count(metrics: dict, key: str) -> int:
    return int(number(metrics.get(key, {}).get("count"), 0))


def read_recording_stage(stage: int) -> dict[str, float]:
    summary = read_json(OUT_DIR / f"recording-start-{stage}-summary.json")
    metrics = summary["metrics"]
    duration = metrics["http_req_duration"]
    rows = read_tsv_rows(OUT_DIR / f"recording-start-{stage}-actuator.tsv")
    prom = read_prometheus_kv(OUT_DIR / f"recording-start-{stage}-prometheus.txt")
    log_values = parse_recording_log(OUT_DIR / f"recording-start-{stage}-app.log")

    return {
        "stage": stage,
        "requests": metric_count(metrics, "http_reqs"),
        "success": metric_count(metrics, "recording_start_successes"),
        "failure": metric_count(metrics, "recording_start_failures"),
        "avg_ms": number(duration.get("avg")),
        "p95_ms": number(duration.get("p(95)")),
        "p99_ms": number(duration.get("p(99)")),
        "max_ms": number(duration.get("max")),
        "throughput": number(metrics.get("http_reqs", {}).get("rate")),
        "hikari_active": max(max_column(rows, "hikari_active"), log_values["active_max"]),
        "hikari_idle": min(
            min_column(rows, "hikari_idle", default=math.inf),
            log_values["idle_min"],
        ),
        "hikari_pending": max(max_column(rows, "hikari_pending"), log_values["pending_max"]),
        "hikari_max": max_column(rows, "hikari_max"),
        "timeout_delta": delta_column(rows, "hikari_timeout_total"),
        "meeting_lock_ms": log_values["meeting_lock_max"],
        "credit_lock_ms": log_values["credit_lock_max"],
        "cpu": max(max_column(rows, "process_cpu_usage"), prom.get("process_cpu_usage_max", 0.0)),
        "heap_mb": max(
            max_column(rows, "jvm_heap_used_bytes"),
            prom.get("jvm_heap_used_bytes_max", 0.0),
        )
        / 1024
        / 1024,
        "threads": max(max_column(rows, "jvm_live_threads"), prom.get("jvm_live_threads_max", 0.0)),
    }


def read_sse_stage(stage: int) -> dict[str, float]:
    summary = read_json(OUT_DIR / f"sse-{stage}-summary.json")
    metrics = summary["metrics"]
    connect = metrics["sse_connect_time_ms"]
    prom = read_prometheus_kv(OUT_DIR / f"sse-{stage}-prometheus.txt")
    attempts = metric_count(metrics, "sse_connection_attempts")
    success = metric_count(metrics, "sse_connection_successes")
    return {
        "stage": stage,
        "attempts": attempts,
        "success": success,
        "failure": attempts - success,
        "avg_ms": number(connect.get("avg")),
        "p95_ms": number(connect.get("p(95)")),
        "p99_ms": number(connect.get("p(99)")),
        "hikari_active": prom.get("hikari_active_max", 0.0),
        "hikari_idle": prom.get("hikari_idle_min", 0.0),
        "hikari_pending": prom.get("hikari_pending_max", 0.0),
        "timeout_delta": prom.get("hikari_timeout_increase", 0.0),
        "threads": prom.get("jvm_live_threads_max", 0.0),
    }


def esc(value: object) -> str:
    return html.escape(str(value), quote=True)


def fmt(value: float, digits: int = 1) -> str:
    if abs(value - round(value)) < 0.0001:
        return str(int(round(value)))
    return f"{value:.{digits}f}"


def nice_max(value: float) -> float:
    if value <= 0:
        return 1
    magnitude = 10 ** math.floor(math.log10(value))
    normalized = value / magnitude
    if normalized <= 1:
        nice = 1
    elif normalized <= 2:
        nice = 2
    elif normalized <= 5:
        nice = 5
    else:
        nice = 10
    return nice * magnitude


def svg_header(width: int, height: int) -> list[str]:
    return [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        f'<rect width="{width}" height="{height}" rx="8" fill="{PANEL}"/>',
        '<style>',
        "text{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif}",
        ".title{font-size:18px;font-weight:600;fill:#d8d9da}",
        ".subtitle{font-size:12px;fill:#8b949e}",
        ".axis{font-size:11px;fill:#8b949e}",
        ".value{font-size:11px;font-weight:600;fill:#d8d9da}",
        ".legend{font-size:12px;fill:#d8d9da}",
        "</style>",
    ]


def add_title(parts: list[str], title: str, subtitle: str) -> None:
    parts.append(f'<text class="title" x="24" y="30">{esc(title)}</text>')
    parts.append(f'<text class="subtitle" x="24" y="50">{esc(subtitle)}</text>')


def grid(parts: list[str], left: float, top: float, width: float, height: float, y_max: float, ticks: int = 5) -> None:
    for i in range(ticks + 1):
        y = top + height - height * i / ticks
        value = y_max * i / ticks
        parts.append(f'<line x1="{left}" y1="{y:.2f}" x2="{left + width}" y2="{y:.2f}" stroke="{GRID}" stroke-width="1"/>')
        parts.append(f'<text class="axis" x="{left - 10}" y="{y + 4:.2f}" text-anchor="end">{esc(fmt(value))}</text>')


def legend(parts: list[str], items: list[tuple[str, str]], x: float, y: float) -> None:
    cursor = x
    for label, color in items:
        parts.append(f'<rect x="{cursor}" y="{y - 9}" width="10" height="10" rx="2" fill="{color}"/>')
        parts.append(f'<text class="legend" x="{cursor + 16}" y="{y}">{esc(label)}</text>')
        cursor += 16 + len(label) * 7 + 18


def stacked_bar_chart(
    title: str,
    subtitle: str,
    labels: list[str],
    series: list[tuple[str, list[float], str]],
    output: Path,
    width: int = 760,
    height: int = 420,
) -> None:
    parts = svg_header(width, height)
    add_title(parts, title, subtitle)
    left, top, right, bottom = 64, 76, 28, 50
    plot_w = width - left - right
    plot_h = height - top - bottom
    totals = [sum(values[i] for _, values, _ in series) for i in range(len(labels))]
    y_max = nice_max(max(totals) * 1.15)
    grid(parts, left, top, plot_w, plot_h, y_max)
    bar_w = min(62, plot_w / len(labels) * 0.48)
    group_w = plot_w / len(labels)

    for i, label in enumerate(labels):
        x = left + group_w * i + group_w / 2 - bar_w / 2
        base_y = top + plot_h
        cumulative = 0.0
        for name, values, color in series:
            value = values[i]
            bar_h = (value / y_max) * plot_h if y_max else 0
            y = base_y - bar_h
            parts.append(f'<rect x="{x:.2f}" y="{y:.2f}" width="{bar_w:.2f}" height="{bar_h:.2f}" fill="{color}"/>')
            if value > 0:
                parts.append(
                    f'<text class="value" x="{x + bar_w / 2:.2f}" y="{y + bar_h / 2 + 4:.2f}" text-anchor="middle">{esc(fmt(value))}</text>'
                )
            base_y = y
            cumulative += value
        parts.append(f'<text class="axis" x="{x + bar_w / 2:.2f}" y="{top + plot_h + 25}" text-anchor="middle">{esc(label)}</text>')
        parts.append(f'<text class="axis" x="{x + bar_w / 2:.2f}" y="{top + plot_h + 41}" text-anchor="middle">total {esc(fmt(cumulative))}</text>')
    legend(parts, [(name, color) for name, _, color in series], left, height - 16)
    parts.append("</svg>")
    output.write_text("\n".join(parts))


def line_chart(
    title: str,
    subtitle: str,
    labels: list[str],
    series: list[tuple[str, list[float], str]],
    output: Path,
    width: int = 760,
    height: int = 420,
    y_max: float | None = None,
    unit: str = "",
    value_digits: int = 1,
) -> None:
    parts = svg_header(width, height)
    add_title(parts, title, subtitle)
    left, top, right, bottom = 64, 76, 28, 50
    plot_w = width - left - right
    plot_h = height - top - bottom
    if y_max is None:
        y_max = nice_max(max(max(values) for _, values, _ in series) * 1.15)
    grid(parts, left, top, plot_w, plot_h, y_max)

    def x_at(index: int) -> float:
        if len(labels) == 1:
            return left + plot_w / 2
        return left + plot_w * index / (len(labels) - 1)

    def y_at(value: float) -> float:
        return top + plot_h - (value / y_max) * plot_h

    for name, values, color in series:
        path = " ".join(
            ("M" if i == 0 else "L") + f" {x_at(i):.2f} {y_at(value):.2f}"
            for i, value in enumerate(values)
        )
        parts.append(f'<path d="{path}" fill="none" stroke="{color}" stroke-width="2.5"/>')
        for i, value in enumerate(values):
            x = x_at(i)
            y = y_at(value)
            parts.append(f'<circle cx="{x:.2f}" cy="{y:.2f}" r="4" fill="{color}"/>')
            parts.append(f'<text class="value" x="{x:.2f}" y="{y - 9:.2f}" text-anchor="middle">{esc(fmt(value, value_digits))}{esc(unit)}</text>')

    for i, label in enumerate(labels):
        parts.append(f'<text class="axis" x="{x_at(i):.2f}" y="{top + plot_h + 25}" text-anchor="middle">{esc(label)}</text>')
    legend(parts, [(name, color) for name, _, color in series], left, height - 16)
    parts.append("</svg>")
    output.write_text("\n".join(parts))


def table_html(recording: list[dict[str, float]], sse: list[dict[str, float]]) -> str:
    recording_rows = "\n".join(
        f"""
        <tr>
          <td>{int(row['stage'])}</td>
          <td>{int(row['success'])}</td>
          <td>{int(row['failure'])}</td>
          <td>{fmt(row['p99_ms'] / 1000, 2)}s</td>
          <td>{fmt(row['hikari_active'])}</td>
          <td>{fmt(row['hikari_pending'])}</td>
          <td>{fmt(row['timeout_delta'])}</td>
        </tr>
        """
        for row in recording
    )
    sse_rows = "\n".join(
        f"""
        <tr>
          <td>{int(row['stage'])}</td>
          <td>{int(row['success'])}</td>
          <td>{int(row['failure'])}</td>
          <td>{fmt(row['p99_ms'], 2)}ms</td>
          <td>{fmt(row['hikari_pending'])}</td>
          <td>{fmt(row['timeout_delta'])}</td>
        </tr>
        """
        for row in sse
    )
    return f"""
    <section class="panel table-panel">
      <h2>Recording Start Detail</h2>
      <table>
        <thead><tr><th>Concurrency</th><th>Success</th><th>Failure</th><th>P99</th><th>Active</th><th>Pending</th><th>Timeouts</th></tr></thead>
        <tbody>{recording_rows}</tbody>
      </table>
    </section>
    <section class="panel table-panel">
      <h2>SSE Baseline Detail</h2>
      <table>
        <thead><tr><th>Connections</th><th>Success</th><th>Failure</th><th>P99</th><th>Pending</th><th>Timeouts</th></tr></thead>
        <tbody>{sse_rows}</tbody>
      </table>
    </section>
    """


def rows_table(title: str, headers: list[str], rows: list[list[object]]) -> str:
    header_html = "".join(f"<th>{esc(header)}</th>" for header in headers)
    row_html = "\n".join(
        "<tr>" + "".join(f"<td>{esc(cell)}</td>" for cell in row) + "</tr>"
        for row in rows
    )
    return f"""
    <article class="panel table-panel">
      <h3>{esc(title)}</h3>
      <table>
        <thead><tr>{header_html}</tr></thead>
        <tbody>{row_html}</tbody>
      </table>
    </article>
    """


def stat_card(label: str, value: str, detail: str, color: str) -> str:
    return f"""
    <div class="stat" style="border-color:{color}">
      <div class="stat-label">{esc(label)}</div>
      <div class="stat-value">{esc(value)}</div>
      <div class="stat-detail">{esc(detail)}</div>
    </div>
    """


def chart_panel(src: str, alt: str) -> str:
    return f'<article class="panel"><img src="{esc(src)}" alt="{esc(alt)}"></article>'


def category_section(
    section_id: str,
    eyebrow: str,
    title: str,
    summary: str,
    cards: list[str],
    charts: list[str],
    table: str,
) -> str:
    return f"""
    <section class="category" id="{esc(section_id)}">
      <div class="category-head">
        <div>
          <div class="eyebrow">{esc(eyebrow)}</div>
          <h2>{esc(title)}</h2>
          <p>{esc(summary)}</p>
        </div>
      </div>
      <div class="stats">{''.join(cards)}</div>
      <div class="chart-grid">{''.join(charts)}{table}</div>
    </section>
    """


def recording_failure_table(recording: list[dict[str, float]]) -> str:
    return rows_table(
        "녹음 시작 실패 상세",
        ["동시 요청", "성공", "실패", "P99", "Active", "Pending", "Timeout"],
        [
            [
                int(row["stage"]),
                int(row["success"]),
                int(row["failure"]),
                f"{fmt(row['p99_ms'] / 1000, 2)}s",
                fmt(row["hikari_active"]),
                fmt(row["hikari_pending"]),
                fmt(row["timeout_delta"]),
            ]
            for row in recording
        ],
    )


def sse_table(sse: list[dict[str, float]]) -> str:
    return rows_table(
        "SSE 연결 성공 상세",
        ["연결 수", "성공", "실패", "P99", "Pending", "Timeout"],
        [
            [
                int(row["stage"]),
                int(row["success"]),
                int(row["failure"]),
                f"{fmt(row['p99_ms'], 2)}ms",
                fmt(row["hikari_pending"]),
                fmt(row["timeout_delta"]),
            ]
            for row in sse
        ],
    )


def success_case_table(title: str, rows: list[dict[str, float]]) -> str:
    return rows_table(
        title,
        ["동시 요청", "성공", "실패", "최대 응답 시간", "풀 대기 시간 초과 로그"],
        [
            [
                int(row["stage"]),
                int(row["success"]),
                int(row["failure"]),
                f"{fmt(row['max_s'], 3)}s",
                int(row["timeout_logs"]),
            ]
            for row in rows
        ],
    )


def write_dashboard(recording: list[dict[str, float]], sse: list[dict[str, float]]) -> None:
    r50 = next(row for row in recording if row["stage"] == 50)
    s300 = next(row for row in sse if row["stage"] == 300)
    split50 = next(row for row in NOTIFICATION_SPLIT_RECORDING if row["stage"] == 50)
    async50 = next(row for row in ASYNC_RECORDING if row["stage"] == 50)

    sse_section = category_section(
        "sse-success",
        "Category 1",
        "SSE 연결 성공",
        "SSE 단독 Baseline은 300개 연결까지 전부 성공했고 Hikari Pending과 Connection Timeout이 0이었다.",
        [
            stat_card("SSE 300", f"{int(s300['success'])}/{int(s300['attempts'])}", "successful connections", GREEN),
            stat_card("SSE failure", str(int(s300["failure"])), "failed connections", BLUE),
            stat_card("SSE P99", f"{fmt(s300['p99_ms'], 2)}ms", "connect latency at 300", CYAN),
            stat_card("Hikari timeout", fmt(s300["timeout_delta"]), "timeout increase", YELLOW),
        ],
        [
            chart_panel("sse-success-failure.svg", "SSE success and failure"),
            chart_panel("sse-latency.svg", "SSE connection latency"),
        ],
        sse_table(sse),
    )

    recording_failure_section = category_section(
        "recording-failure",
        "Category 2",
        "기존 녹음 시작 실패",
        "AFTER_COMMIT 알림 저장과 REQUIRES_NEW가 같은 요청 흐름에 묶인 상태에서는 성공 건수가 풀 크기 부근에서 멈추고 Pending과 Timeout이 증가했다.",
        [
            stat_card("Recording 50", f"{int(r50['success'])}/{int(r50['requests'])}", "successful starts", RED),
            stat_card("Recording 50 failures", str(int(r50["failure"])), "500 responses after pool timeout", RED),
            stat_card("Max Hikari pending", fmt(r50["hikari_pending"]), "recording-start-50 stage", YELLOW),
            stat_card("P99 latency", f"{fmt(r50['p99_ms'] / 1000, 2)}s", "connection wait dominated", ORANGE),
        ],
        [
            chart_panel("recording-start-success-failure.svg", "Recording start success and failure"),
            chart_panel("recording-start-hikari.svg", "Recording start Hikari connection pool"),
            chart_panel("recording-start-latency.svg", "Recording start latency"),
            chart_panel("recording-start-lock-wait.svg", "Recording start row lock waits"),
        ],
        recording_failure_table(recording),
    )

    split_section = category_section(
        "notification-split-success",
        "Category 3",
        "녹음 시작과 알림 저장 분리 성공",
        "알림 후처리를 제거하거나 격리한 B 구성에서는 풀 10에서도 동시 50 녹음 시작이 모두 성공했고 풀 대기 시간 초과가 발생하지 않았다.",
        [
            stat_card("Split 50", f"{int(split50['success'])}/{int(split50['requests'])}", "successful starts", GREEN),
            stat_card("Split failure", str(int(split50["failure"])), "failed starts", GREEN),
            stat_card("Max response", f"{fmt(split50['max_s'], 2)}s", "at concurrency 50", CYAN),
            stat_card("Timeout logs", str(int(split50["timeout_logs"])), "Connection is not available", GREEN),
        ],
        [
            chart_panel("notification-split-success-failure.svg", "Notification split success and failure"),
            chart_panel("notification-split-latency.svg", "Notification split response latency"),
            chart_panel("notification-split-timeouts.svg", "Notification split timeout logs"),
        ],
        success_case_table("알림 저장 분리 성공 상세", NOTIFICATION_SPLIT_RECORDING),
    )

    async_section = category_section(
        "async-success",
        "Category 4",
        "@Async 적용 성공",
        "@Async로 AFTER_COMMIT 후속 작업을 요청 Thread에서 분리하자 원본 Transaction cleanup이 먼저 진행되어 풀 10에서도 동시 50까지 모두 성공했다.",
        [
            stat_card("Async 50", f"{int(async50['success'])}/{int(async50['requests'])}", "successful starts", GREEN),
            stat_card("Async failure", str(int(async50["failure"])), "failed starts", GREEN),
            stat_card("Max response", f"{fmt(async50['max_s'], 2)}s", "at concurrency 50", CYAN),
            stat_card("Timeout logs", str(int(async50["timeout_logs"])), "Connection is not available", GREEN),
        ],
        [
            chart_panel("async-success-failure.svg", "Async success and failure"),
            chart_panel("async-latency.svg", "Async response latency"),
            chart_panel("async-timeouts.svg", "Async timeout logs"),
        ],
        success_case_table("@Async 적용 성공 상세", ASYNC_RECORDING),
    )

    sections = "\n".join([sse_section, recording_failure_section, split_section, async_section])

    body = f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Issue 238 Load Test Dashboard</title>
  <style>
    :root {{
      color-scheme: dark;
      --bg: {BG};
      --panel: {PANEL};
      --grid: {GRID};
      --text: {TEXT};
      --muted: {MUTED};
    }}
    * {{ box-sizing: border-box; }}
    body {{
      margin: 0;
      background: var(--bg);
      color: var(--text);
      font: 14px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }}
    main {{
      max-width: 1580px;
      margin: 0 auto;
      padding: 28px;
    }}
    header {{
      display: flex;
      align-items: end;
      justify-content: space-between;
      gap: 24px;
      margin-bottom: 20px;
    }}
    h1 {{
      margin: 0 0 6px;
      font-size: 24px;
      letter-spacing: 0;
    }}
    p {{
      margin: 0;
      color: var(--muted);
    }}
    nav {{
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin: 18px 0 24px;
    }}
    nav a {{
      color: var(--text);
      text-decoration: none;
      background: var(--panel);
      border: 1px solid var(--grid);
      border-radius: 8px;
      padding: 9px 12px;
    }}
    .category {{
      margin: 22px 0 30px;
      padding-top: 8px;
    }}
    .category-head {{
      display: flex;
      justify-content: space-between;
      gap: 18px;
      margin-bottom: 14px;
    }}
    .eyebrow {{
      color: var(--muted);
      font-size: 12px;
      margin-bottom: 6px;
      text-transform: uppercase;
    }}
    .stats {{
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 14px;
      margin: 18px 0;
    }}
    .stat {{
      background: var(--panel);
      border: 1px solid;
      border-radius: 8px;
      padding: 16px;
    }}
    .stat-label,
    .stat-detail {{
      color: var(--muted);
      font-size: 12px;
    }}
    .stat-value {{
      margin: 8px 0;
      font-size: 30px;
      font-weight: 700;
    }}
    .chart-grid {{
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 14px;
    }}
    .panel {{
      background: var(--panel);
      border: 1px solid var(--grid);
      border-radius: 8px;
      overflow: hidden;
    }}
    .panel img {{
      display: block;
      width: 100%;
      height: auto;
    }}
    .table-panel {{
      padding: 18px;
    }}
    h2 {{
      margin: 0 0 8px;
      font-size: 22px;
      letter-spacing: 0;
    }}
    h3 {{
      margin: 0 0 14px;
      font-size: 17px;
    }}
    table {{
      width: 100%;
      border-collapse: collapse;
    }}
    th,
    td {{
      border-bottom: 1px solid var(--grid);
      padding: 9px 8px;
      text-align: right;
    }}
    th:first-child,
    td:first-child {{
      text-align: left;
    }}
    th {{
      color: var(--muted);
      font-weight: 500;
    }}
    @media (max-width: 980px) {{
      .stats,
      .chart-grid {{
        grid-template-columns: 1fr;
      }}
      main {{
        padding: 18px;
      }}
    }}
  </style>
</head>
<body>
  <main>
    <header>
      <div>
        <h1>Issue 238 Load Test Dashboard</h1>
        <p>Four separated views: SSE success, original recording-start failure, notification split success, and @Async success.</p>
      </div>
      <p>Source: out + after-commit-requires-new README</p>
    </header>
    <nav>
      <a href="#sse-success">SSE 연결 성공</a>
      <a href="#recording-failure">녹음 시작 실패</a>
      <a href="#notification-split-success">알림 저장 분리 성공</a>
      <a href="#async-success">@Async 성공</a>
    </nav>
    {sections}
  </main>
</body>
</html>
"""
    (CHART_DIR / "dashboard.html").write_text(body)


def main() -> None:
    CHART_DIR.mkdir(parents=True, exist_ok=True)
    recording = [read_recording_stage(stage) for stage in RECORDING_STAGES]
    sse = [read_sse_stage(stage) for stage in SSE_STAGES]

    recording_labels = [str(row["stage"]) for row in recording]
    sse_labels = [str(row["stage"]) for row in sse]

    stacked_bar_chart(
        "Recording Start: Success vs Failure",
        "Success stays near Hikari max pool size while failures grow at 30 and 50 concurrent requests.",
        recording_labels,
        [
            ("success", [row["success"] for row in recording], GREEN),
            ("failure", [row["failure"] for row in recording], RED),
        ],
        CHART_DIR / "recording-start-success-failure.svg",
    )

    line_chart(
        "Recording Start: Hikari Pool Pressure",
        "Active reaches maxPoolSize=10, idle drops to zero, and pending grows with concurrency.",
        recording_labels,
        [
            ("active", [row["hikari_active"] for row in recording], BLUE),
            ("idle", [row["hikari_idle"] for row in recording], GREEN),
            ("pending", [row["hikari_pending"] for row in recording], YELLOW),
            ("timeouts", [row["timeout_delta"] for row in recording], RED),
            ("max pool", [row["hikari_max"] for row in recording], MUTED),
        ],
        CHART_DIR / "recording-start-hikari.svg",
    )

    line_chart(
        "Recording Start: Latency",
        "At concurrency 10 and above, API latency tracks the 30s connection acquisition timeout.",
        recording_labels,
        [
            ("avg", [row["avg_ms"] / 1000 for row in recording], CYAN),
            ("p95", [row["p95_ms"] / 1000 for row in recording], ORANGE),
            ("p99", [row["p99_ms"] / 1000 for row in recording], RED),
        ],
        CHART_DIR / "recording-start-latency.svg",
        unit="s",
    )

    line_chart(
        "Recording Start: Row Lock Wait",
        "Meeting and TeamCredit lock waits stayed low compared with the 30s connection wait.",
        recording_labels,
        [
            ("meeting lock", [row["meeting_lock_ms"] for row in recording], BLUE),
            ("credit lock", [row["credit_lock_ms"] for row in recording], PURPLE),
        ],
        CHART_DIR / "recording-start-lock-wait.svg",
        y_max=6,
        unit="ms",
    )

    line_chart(
        "SSE Baseline: Connect Latency",
        "SSE reached 300 concurrent connections without Hikari pending or connection timeouts.",
        sse_labels,
        [
            ("avg", [row["avg_ms"] for row in sse], CYAN),
            ("p95", [row["p95_ms"] for row in sse], ORANGE),
            ("p99", [row["p99_ms"] for row in sse], RED),
        ],
        CHART_DIR / "sse-latency.svg",
        unit="ms",
    )

    stacked_bar_chart(
        "SSE Baseline: Success vs Failure",
        "All attempted SSE connections succeeded through 300 concurrent connections.",
        sse_labels,
        [
            ("success", [row["success"] for row in sse], GREEN),
            ("failure", [row["failure"] for row in sse], RED),
        ],
        CHART_DIR / "sse-success-failure.svg",
    )

    split_labels = [str(row["stage"]) for row in NOTIFICATION_SPLIT_RECORDING]
    stacked_bar_chart(
        "Notification Split: Success vs Failure",
        "With notification follow-up removed or isolated, recording start succeeded through 50 concurrent requests.",
        split_labels,
        [
            ("success", [row["success"] for row in NOTIFICATION_SPLIT_RECORDING], GREEN),
            ("failure", [row["failure"] for row in NOTIFICATION_SPLIT_RECORDING], RED),
        ],
        CHART_DIR / "notification-split-success-failure.svg",
    )

    line_chart(
        "Notification Split: Max Response Time",
        "The 30s connection wait disappeared; max response time stayed under 1 second at concurrency 50.",
        split_labels,
        [
            ("max", [row["max_s"] for row in NOTIFICATION_SPLIT_RECORDING], CYAN),
        ],
        CHART_DIR / "notification-split-latency.svg",
        y_max=1.2,
        unit="s",
        value_digits=2,
    )

    line_chart(
        "Notification Split: Pool Timeout Logs",
        "Connection acquisition timeout logs stayed at zero in every stage.",
        split_labels,
        [
            ("timeout logs", [row["timeout_logs"] for row in NOTIFICATION_SPLIT_RECORDING], GREEN),
        ],
        CHART_DIR / "notification-split-timeouts.svg",
        y_max=1,
    )

    async_labels = [str(row["stage"]) for row in ASYNC_RECORDING]
    stacked_bar_chart(
        "@Async: Success vs Failure",
        "With AFTER_COMMIT listeners moved off the request thread, recording start succeeded through 50 concurrent requests.",
        async_labels,
        [
            ("success", [row["success"] for row in ASYNC_RECORDING], GREEN),
            ("failure", [row["failure"] for row in ASYNC_RECORDING], RED),
        ],
        CHART_DIR / "async-success-failure.svg",
    )

    line_chart(
        "@Async: Max Response Time",
        "The original transaction can clean up first, so max response time stayed near sub-second levels.",
        async_labels,
        [
            ("max", [row["max_s"] for row in ASYNC_RECORDING], CYAN),
        ],
        CHART_DIR / "async-latency.svg",
        y_max=1.2,
        unit="s",
        value_digits=2,
    )

    line_chart(
        "@Async: Pool Timeout Logs",
        "Connection acquisition timeout logs stayed at zero after async separation.",
        async_labels,
        [
            ("timeout logs", [row["timeout_logs"] for row in ASYNC_RECORDING], GREEN),
        ],
        CHART_DIR / "async-timeouts.svg",
        y_max=1,
    )

    write_dashboard(recording, sse)

    print(f"Generated charts under {CHART_DIR}")


if __name__ == "__main__":
    main()
