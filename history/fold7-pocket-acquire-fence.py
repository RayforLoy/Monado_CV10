#!/usr/bin/env python3
"""Join launcher acquire-fence diagnostics with Pocket XR and BufferQueue timing."""

import argparse
from bisect import bisect_left, bisect_right
from collections import Counter, defaultdict
from pathlib import Path
import re
from statistics import median, quantiles

from perfetto.trace_processor import TraceProcessor


SECOND = 1_000_000_000
MS = 1_000_000
RECORD = re.compile(r"\bCV10_(POCKET_STAMP|ACQUIRE|SOURCE|DRAW|FEEDBACK) (.*)")
VALUE = re.compile(r"(\w+)=(-?\d+)(?: |$)")
SF_ROW = re.compile(r"\s*(\d+)\s+(\d+)\s+(\d+)\s*")


def read_log(path):
    log = path.read_text(encoding="utf-8-sig")
    start = int(re.search(r"CV10_BENCH start=(\d+)", log)[1])
    thread_ids = Counter(re.findall(r"\s+\d+\s+(\d+) I FlipSpaces/Space: CV10_POCKET_STAMP", log))
    tid = int(thread_ids.most_common(1)[0][0])
    keys = {"POCKET_STAMP": "stamp", "ACQUIRE": "stamp", "SOURCE": "layer"}
    rows = defaultdict(dict)
    for kind, payload in RECORD.findall(log):
        values = {name: int(value) for name, value in VALUE.findall(payload)}
        rows[kind][values[keys.get(kind, "id")]] = values
    return start, tid, rows


def read_queue_times(path, tid):
    with TraceProcessor(trace=str(path)) as trace:
        offsets = {row.offset for row in trace.query(
            "SELECT clock_value - ts AS offset FROM clock_snapshot WHERE clock_name = 'MONOTONIC'"
        )}
        if not offsets or max(offsets) - min(offsets) > 100_000:
            raise ValueError("Could not align Perfetto timestamps to CLOCK_MONOTONIC")
        offset = round(median(offsets))
        rows = trace.query(f"""
            SELECT s.ts, s.dur FROM slice s
            JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
            WHERE t.tid = {tid} AND s.name = 'queueBuffer' AND s.dur >= 0 ORDER BY s.ts
        """)
        return [(row.ts + offset, row.ts + row.dur + offset) for row in rows]


def statistics(name, values):
    if len(values) > 1:
        print(f"  {name} p50/p95: {median(values) / MS:.3f}/{quantiles(values, n=20)[18] / MS:.3f} ms")


def read_actuals(path, start):
    active = False
    actuals = set()
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if line in ("CV10", "POCKET") or line.startswith("SAMPLE "):
            active = line == "CV10"
        elif active and (match := SF_ROW.fullmatch(line)):
            actual = int(match[2])
            if start + 12 * SECOND <= actual < start + 27 * SECOND:
                actuals.add(actual)
    return actuals


def report_repeats(actuals, rows, swaps, swap_time, ready):
    if not actuals:
        raise ValueError("No SurfaceFlinger actual presents in the yaw interval")
    presented = {
        value["actual"]: rows["DRAW"].get(frame_id)
        for frame_id, value in rows["FEEDBACK"].items()
        if value["actual"] in actuals
    }
    joined = 0
    counts = Counter()
    previous = None
    gaps = []
    for actual in sorted(actuals):
        draw = presented.get(actual)
        source = rows["SOURCE"].get(draw["layer"]) if draw else None
        producer = rows["POCKET_STAMP"].get(source["stamp"]) if source else None
        if producer is None:
            previous = None
            continue
        joined += 1
        key = source["stamp"], source["layer"]
        if previous is not None and key[0] == previous[0]:
            counts["repeated"] += 1
            reused_layer = key[1] == previous[1]
            pending = not reused_layer and bool(source.get("pending_fence"))
            counts["same XR layer"] += reused_layer
            counts["new XR layer with pending fence at read"] += pending
            first = bisect_right(swap_time, producer["swap"])
            last = bisect_right(swap_time, draw["at"])
            if last <= first:
                counts["no newer pre-swap by draw"] += 1
            else:
                newer = [stamp for _, stamp in swaps[first:last]]
                bounded = [ready[stamp] for stamp in newer if stamp in ready]
                counts["newer pre-swap by draw"] += 1
                counts["all newer candidates bounded"] += len(bounded) == len(newer)
                for name, cutoff in (("read end", source["stream_end"]),
                                     ("0.5 ms before draw", draw["at"] - MS // 2),
                                     ("draw", draw["at"])):
                    counts[f"newer definitely ready by {name}"] += any(high <= cutoff for _, high in bounded)
                if pending and bounded:
                    # The log does not identify which newer buffer was pending at the end of the read.
                    # The earliest bounded ready time therefore measures opportunity, not guaranteed gain.
                    delay = min(high for _, high in bounded) - source["stream_end"]
                    gaps.append(delay)
                    counts["pending and newer ready within 0.5 ms"] += 0 < delay <= MS // 2
                    counts["pending and newer ready within 0.75 ms"] += 0 < delay <= 3 * MS // 4
        previous = key
    print(f"  Presented yaw frames matched to Vulkan and Pocket: {joined}/{len(actuals)}")
    for key in ("repeated", "same XR layer", "new XR layer with pending fence at read", "no newer pre-swap by draw",
                "newer pre-swap by draw", "all newer candidates bounded", "newer definitely ready by read end",
                "newer definitely ready by 0.5 ms before draw", "newer definitely ready by draw",
                "pending and newer ready within 0.5 ms", "pending and newer ready within 0.75 ms"):
        print(f"    {key}: {counts[key]}")
    statistics("new atlas readiness upper bound in repeated frames with a pending fence", gaps)


def report(args):
    start, tid, rows = read_log(args.log)
    queue = read_queue_times(args.trace, tid)
    queue_time = [begin for begin, _ in queue]
    swaps = sorted((entry["swap"], stamp) for stamp, entry in rows["POCKET_STAMP"].items())
    swap_time = [time for time, _ in swaps]
    ready = {}
    for (swap, stamp), (next_swap, _) in zip(swaps, swaps[1:]):
        first, last = bisect_left(queue_time, swap), bisect_left(queue_time, next_swap)
        acquire = rows["ACQUIRE"].get(stamp)
        if last - first != 1 or not acquire or acquire["signal"] <= 0:
            continue
        begin, end = queue[first]
        # The image must already have been queued by the time the consumer acquires it.
        earliest = max(begin, acquire["signal"])
        latest = max(min(end, acquire["acquired"]), acquire["signal"])
        if begin <= acquire["acquired"] and earliest <= latest <= acquire["observed"]:
            ready[stamp] = earliest, latest

    captures = []
    for frame_id, draw in rows["DRAW"].items():
        if not start + 12 * SECOND <= draw["at"] < start + 27 * SECOND:
            continue
        source = rows["SOURCE"].get(draw["layer"])
        if not source or source["stamp"] not in rows["POCKET_STAMP"]:
            continue
        stamp = source["stamp"]
        first_new = bisect_right(swap_time, rows["POCKET_STAMP"][stamp]["swap"])
        draw_index = bisect_right(swap_time, draw["at"])
        newer = [new_stamp for _, new_stamp in swaps[first_new:draw_index]]
        captures.append((draw, source, frame_id in rows["FEEDBACK"], newer))

    print(f"{args.log.stem}: {len(queue)} queueBuffer calls, {len(ready)} matched queue and signaled fences")
    acquires = rows["ACQUIRE"].values()
    print(f"  {sum(a['signal'] > 0 for a in acquires)}/{len(acquires)} acquire records have a kernel signal")
    statistics("queue call width", [end - begin for begin, end in queue])
    statistics("acquire after GPU ready", [a["acquired"] - a["signal"] for a in acquires if a["signal"] > 0])
    for actual in (False, True):
        samples = [(draw, source, newer) for draw, source, feedback, newer in captures if feedback or not actual]
        future = [(draw, source, newer) for draw, source, newer in samples if newer]
        bounded = [(draw, source, newer) for draw, source, newer in future if all(s in ready for s in newer)]
        kind = "actual-feedback draws" if actual else "all draws"
        print(f"  {kind}: {len(samples)} paired, {len(future)} newer pre-swap, {len(bounded)} fully bounded")
        for name, offset in (("stream_end", 0), ("stream_end", MS), ("stream_end", 3 * MS // 2),
                             ("draw", -MS), ("draw", -MS // 2), ("draw", 0)):
            definite, possible = 0, 0
            for draw, source, newer in bounded:
                time = (draw["at"] if name == "draw" else source[name]) + offset
                definite += any(ready[s][1] <= time for s in newer)
                possible += any(ready[s][0] <= time for s in newer)
            print(f"    newer ready at {name}{offset / MS:+g}ms: {definite}–{possible}/{len(bounded)}")
        statistics("read to draw", [draw["at"] - source["stream_end"] for draw, source, _ in samples])

    if args.surfaceflinger is not None:
        report_repeats(read_actuals(args.surfaceflinger, start), rows, swaps, swap_time, ready)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path, help="combined Android logcat")
    parser.add_argument("trace", type=Path, help="Perfetto gfx trace")
    parser.add_argument("--surfaceflinger", type=Path, help="raw CV10 layer presentation samples")
    report(parser.parse_args())
