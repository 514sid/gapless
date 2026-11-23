#!/usr/bin/env python3
"""Summarizes a soak run written by soak.sh.

    python3 samples/soak/report.py soak-20261122-2110

Reads samples.csv, logcat.txt and events.txt from the run directory and the asset list from the
sample app's samples.json, and prints a Markdown summary: restarts and reboots, playback events
per asset, unexpected errors, the hold at each video cut, and how memory, threads and open files
moved over the run.
"""

import csv
import json
import os
import re
import statistics
import sys
from collections import Counter, defaultdict
from datetime import datetime

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MANIFEST = os.path.join(ROOT, "app", "src", "main", "assets", "samples", "samples.json")
WARMUP_S = 600  # Memory trends skip the first ten minutes, while caches fill.

LINE = re.compile(r"^(\d\d-\d\d) (\d\d:\d\d:\d\d\.\d+) ([VDIWEF])/([^(]+)\(\s*(\d+)\): (.*)$")
STARTED = re.compile(r"Started: (\S+) \[[^\]]+\], began (-?\d+)ms before this event")


def parse_time(day, clock, year):
    return datetime.strptime(f"{year}-{day} {clock}", "%Y-%m-%d %H:%M:%S.%f").timestamp()


def slope_per_hour(points):
    """Least-squares slope of (seconds, value) points, in value per hour."""
    if len(points) < 3:
        return None
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    mx, my = statistics.fmean(xs), statistics.fmean(ys)
    den = sum((x - mx) ** 2 for x in xs)
    if den == 0:
        return None
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / den * 3600


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    run = sys.argv[1]
    manifest = json.load(open(MANIFEST))
    assets = {a["id"]: a for a in manifest["assets"]}
    broken = {a["id"] for a in manifest["assets"] if a.get("broken")}

    rows = list(csv.DictReader(open(os.path.join(run, "samples.csv"))))
    reachable = [r for r in rows if r["uptime_s"]]
    year = datetime.fromtimestamp(int(rows[0]["time"])).year if rows else datetime.now().year

    # Reboots: uptime going down. Player restarts: a new process id.
    reboots, restarts, last_up, last_pid = 0, 0, None, None
    for r in reachable:
        up = int(r["uptime_s"])
        if last_up is not None and up < last_up:
            reboots += 1
        last_up = up
        if r["player_pid"]:
            if last_pid and r["player_pid"] != last_pid:
                restarts += 1
            last_pid = r["player_pid"]
    unreachable = len(rows) - len(reachable)
    no_player = sum(1 for r in reachable if not r["player_pid"])

    started, errors, preload_errors, missed = Counter(), defaultdict(Counter), defaultdict(Counter), Counter()
    hard_cuts, crashes, anrs, lmk_kills = 0, [], 0, 0
    starts = []  # (start time, asset id)
    failures = []  # times of errors, which hold the schedule up on purpose
    log_path = os.path.join(run, "logcat.txt")
    for line in open(log_path, errors="replace") if os.path.exists(log_path) else []:
        m = LINE.match(line.rstrip("\n"))
        if not m:
            continue
        day, clock, level, tag, _, msg = m.groups()
        tag = tag.strip()
        if tag == "GaplessPlayer":
            s = STARTED.search(msg)
            if s:
                started[s.group(1)] += 1
                starts.append((parse_time(day, clock, year) - int(s.group(2)) / 1000, s.group(1)))
            elif msg.startswith("Error on "):
                failures.append(parse_time(day, clock, year))
                aid, _, text = msg[len("Error on "):].partition(": ")
                errors[aid][text[:80]] += 1
            elif msg.startswith("PreloadError on "):
                failures.append(parse_time(day, clock, year))
                aid, _, text = msg[len("PreloadError on "):].partition(": ")
                preload_errors[aid][text[:80]] += 1
            elif msg.startswith("PreloadMissed: "):
                missed[msg.split()[1]] += 1
        elif tag == "GaplessVideo" and "trying hard cuts" in msg:
            hard_cuts += 1
        elif tag == "AndroidRuntime" and "FATAL EXCEPTION" in msg:
            crashes.append(f"{day} {clock}")
        elif tag == "ActivityManager" and "ANR in io.github.gapless" in msg:
            anrs += 1
        elif ("lowmemorykiller" in tag or tag == "lmkd" or tag == "ActivityManager") \
                and "io.github.gapless" in msg and ("Kill" in msg or "kill" in msg):
            lmk_kills += 1

    # The hold at a video cut: how long after a clip's scheduled end the next one began. Cuts with
    # a failure in between are left out: the schedule waits a second before skipping on purpose.
    holds = []
    starts.sort()
    failures.sort()
    for (t0, a0), (t1, _) in zip(starts, starts[1:]):
        asset = assets.get(a0)
        if not asset or a0 in broken or not asset["mimeType"].startswith("video/"):
            continue
        if any(t0 < f < t1 for f in failures):
            continue
        hold = (t1 - t0) * 1000 - asset["durationMs"]
        if -500 < hold < 60_000:  # Outside that, playback was interrupted, not cut.
            holds.append(hold)

    hours = (int(rows[-1]["time"]) - int(rows[0]["time"])) / 3600 if len(rows) > 1 else 0
    out = []
    p = out.append
    p(f"# Soak run {os.path.basename(run.rstrip('/'))}")
    p("")
    p(f"{hours:.1f} hours, {len(rows)} samples, {sum(started.values())} assets started.")
    p("")
    p("## Stability")
    p("")
    p(f"- Device reboots: {reboots}")
    p(f"- Player process restarts: {restarts}")
    p(f"- Samples with the device unreachable: {unreachable}; with no player running: {no_player}")
    p(f"- Crashes (FATAL EXCEPTION): {len(crashes)}" + (f", at {', '.join(crashes[:10])}" if crashes else ""))
    p(f"- ANRs: {anrs}; low-memory kills of the app: {lmk_kills}")
    p(f"- Switches to hard cuts: {hard_cuts}")
    events = os.path.join(run, "events.txt")
    if os.path.exists(events):
        notable = [l.strip() for l in open(events) if "relaunch" in l or "unreachable" in l]
        if notable:
            p(f"- Script events: {len(notable)}, first: {notable[0]}")
    p("")

    p("## Playback")
    p("")
    p("| Asset | Started | Errors | Preload errors | Preload missed |")
    p("| --- | --- | --- | --- | --- |")
    for aid in sorted(set(started) | set(errors) | set(preload_errors) | set(missed)):
        mark = " (broken)" if aid in broken else ""
        p(f"| `{aid}`{mark} | {started[aid]} | {sum(errors[aid].values())} | "
          f"{sum(preload_errors[aid].values())} | {missed[aid]} |")
    p("")
    unexpected = {a: c for a, c in list(errors.items()) + list(preload_errors.items()) if a not in broken and c}
    if unexpected:
        p("Unexpected errors on working assets:")
        p("")
        for aid, msgs in unexpected.items():
            for text, n in msgs.most_common(3):
                p(f"- `{aid}` x{n}: {text}")
    else:
        p("No errors on working assets.")
    p("")

    if holds:
        holds.sort()
        q = lambda f: holds[min(len(holds) - 1, int(f * len(holds)))]
        p("## Hold at video cuts")
        p("")
        p(f"{len(holds)} cuts: median {q(0.5):.0f} ms, 95th percentile {q(0.95):.0f} ms, "
          f"99th {q(0.99):.0f} ms, worst {holds[-1]:.0f} ms. "
          f"Over 1 second: {sum(1 for h in holds if h > 1000)}.")
        p("")

    p("## Resources")
    p("")
    p("Trends skip the first 10 minutes and are least-squares slopes over the rest.")
    p("")
    p("| Measure | First | Last | Max | Trend per hour |")
    p("| --- | --- | --- | --- | --- |")
    t0 = int(rows[0]["time"]) if rows else 0
    for col, label, unit in [
        ("pss_kb", "Player memory (PSS)", "MB"), ("java_kb", "Java heap", "MB"),
        ("native_kb", "Native heap", "MB"), ("graphics_kb", "Graphics", "MB"),
        ("threads", "Threads", ""), ("fds", "Open files", ""),
        ("memavail_kb", "System free memory", "MB"), ("swapfree_kb", "Free swap", "MB"),
        ("hal_rss_kb", "Hardware codec process", "MB"), ("swcodec_rss_kb", "Software codec process", "MB"),
        ("renderer_kb", "WebView renderer (RAM + swap)", "MB"),
        ("load1", "Load average", ""),
    ]:
        pts = [(int(r["time"]) - t0, float(r[col])) for r in rows if r.get(col)]
        if not pts:
            continue
        div = 1024 if unit == "MB" else 1
        fmt = (lambda v: f"{v / div:.1f} {unit}".strip()) if unit else (lambda v: f"{v:g}")
        trend = slope_per_hour([pt for pt in pts if pt[0] >= WARMUP_S])
        trend_s = "n/a" if trend is None else (f"{trend / div:+.2f} {unit}".strip() if unit else f"{trend:+.2f}")
        p(f"| {label} | {fmt(pts[0][1])} | {fmt(pts[-1][1])} | {fmt(max(v for _, v in pts))} | {trend_s} |")
    cpu = [(int(r["time"]), int(r["cpu_jiffies"]), r["player_pid"]) for r in rows if r.get("cpu_jiffies")]
    usage = [(c1 - c0) / (t1 - t0_) for (t0_, c0, p0), (t1, c1, p1) in zip(cpu, cpu[1:]) if p0 == p1 and t1 > t0_]
    if usage:
        p("")
        p(f"Player CPU: average {statistics.fmean(usage):.0f}% of one core, highest interval {max(usage):.0f}%.")
    print("\n".join(out))


if __name__ == "__main__":
    main()
