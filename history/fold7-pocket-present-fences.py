#!/usr/bin/env python3
"""Measure direct Pocket XR presentation using Android graphics frame events."""

import argparse
from bisect import bisect_right
from collections import defaultdict
from pathlib import Path
import re
from statistics import median, quantiles

from perfetto.trace_processor import TraceProcessor


SECOND = 1_000_000_000
MILLISECOND = 1_000_000
PRESENT = "PresentFenceSignaled"
RECORD = re.compile(r"\bCV10_(PRESENT|GPU|FEEDBACK|SCANOUT) ([^\r\n]+)")
FIELDS = re.compile(r"(\w+)=(-?\d+)(?: |$)")


def rate(timestamps):
    timestamps = sorted(set(timestamps))
    if len(timestamps) < 2:
        return 0.0
    return (len(timestamps) - 1) * SECOND / (timestamps[-1] - timestamps[0])


def report_interval(label, times):
    tail = quantiles(times, n=20)[18] if len(times) > 1 else times[0]
    print(f"{label}: {median(times) / MILLISECOND:.2f} / {tail / MILLISECOND:.2f} ms ({len(times)} frames)")


def report_hwc(actual_times, outputs):
    # The fixture uses the 90 Hz CV10 mode. Reconstruct candidate scanouts
    # inside each gap, leaving out gaps that do not fit this refresh grid.
    period = round(SECOND / 90)
    slots = []
    actual_times = sorted(set(actual_times))
    for earlier, later in zip(actual_times, actual_times[1:]):
        steps = round((later - earlier) / period)
        if abs(later - earlier - steps * period) <= 500_000:
            slots.extend(earlier + step * period for step in range(1, steps))
    print(f"Missing 90 Hz scanout slots estimated from fence gaps: {len(slots)}.")
    for output, calls in outputs.items():
        starts = [begin for begin, _ in calls]
        overlap = 0
        for slot in slots:
            candidate = bisect_right(starts, slot) - 1
            overlap += candidate >= 0 and slot < calls[candidate][1]
        if calls:
            print(f"HWC {output}: {overlap}/{len(slots)} missing slots overlap PresentOrValidate.")


def analyze(trace_path, log_path, layer, hwc_ids):
    log = log_path.read_text(encoding="utf-8-sig")
    benchmark = re.search(r"CV10_BENCH start=(\d+)", log)
    if not benchmark:
        raise ValueError("The Monado log has no CV10 replay start")
    start = int(benchmark[1]) + 12 * SECOND
    end = start + 15 * SECOND

    records = defaultdict(dict)
    for category, record in RECORD.findall(log):
        values = {key: int(value) for key, value in FIELDS.findall(record)}
        records[category][values["id"]] = values

    # Read only the raw per-buffer event tracks. Perfetto also builds aggregate
    # App, GPU and SF tracks using the same frame numbers.
    frames = defaultdict(dict)
    with TraceProcessor(trace=str(trace_path)) as processor:
        offsets = [row.ts - row.clock_value for row in processor.query(
            "SELECT ts, clock_value FROM clock_snapshot WHERE clock_name = 'MONOTONIC'"
        )]
        if not offsets or max(offsets) - min(offsets) > MILLISECOND:
            raise ValueError("Clock snapshots do not establish one stable monotonic offset")
        offset = round(median(offsets))
        for row in processor.query("""
            SELECT slice.ts, slice.name, EXTRACT_ARG(slice.arg_set_id, 'frame_number') AS frame,
                   EXTRACT_ARG(slice.arg_set_id, 'layer_name') AS layer
            FROM slice JOIN track ON slice.track_id = track.id
            WHERE track.name LIKE 'Buffer: %' AND slice.name IN (
                'Queue', 'AcquireFenceSignaled', 'Latch', 'FallbackComposition', 'PresentFenceSignaled'
            )
        """):
            if row.layer == layer:
                frames[row.frame][row.name] = row.ts - offset
        outputs = {output: [] for output in hwc_ids}
        for row in processor.query("""
            SELECT ts, dur, name FROM slice
            WHERE name GLOB 'HwcPresentOrValidateDisplay [0-9]*' AND dur > 0 ORDER BY ts
        """):
            output = int(row.name.removeprefix("HwcPresentOrValidateDisplay "))
            if output in outputs:
                outputs[output].append((row.ts - offset, row.ts - offset + row.dur))

    presented = {frame: events for frame, events in frames.items() if start <= events.get(PRESENT, 0) < end}
    if not presented:
        raise ValueError(f"No buffer present fences in the replay interval for {layer!r}")

    intervals = defaultdict(list)
    feedback_matches = 0
    for frame, events in presented.items():
        actual = events[PRESENT]
        latch = events.get("Latch")
        queue = events.get("Queue")
        acquire = events.get("AcquireFenceSignaled")
        composition = events.get("FallbackComposition")
        if latch is not None and queue is not None and acquire is not None:
            intervals["Queued and acquired to latch"].append(latch - max(queue, acquire))
            intervals["Latch to present fence"].append(actual - latch)
        if latch is not None and composition is not None:
            intervals["Latch to RenderEngine composite"].append(composition - latch)
            intervals["RenderEngine composite to present fence"].append(actual - composition)

        # This preview starts each Android Surface and Vulkan present ID series
        # together. Only join a Monado frame when its actual time independently
        # matches the Android fence for that frame number.
        feedback = records["FEEDBACK"].get(frame)
        if feedback is None or abs(feedback["actual"] - actual) > 1_000:
            continue
        feedback_matches += 1
        gpu = records["GPU"].get(frame)
        if gpu is not None and latch is not None:
            intervals["Monado GPU completion to latch"].append(latch - gpu["end"])
            intervals["Monado GPU completion to present fence"].append(actual - gpu["end"])
        pose = records["SCANOUT"].get(frame)
        if pose is not None:
            intervals["Absolute scanout pose target error"].append(abs(actual - pose["target_ns"]))

    submits = [record["end"] for record in records["PRESENT"].values()
               if record["result"] == 0 and start <= record["end"] < end]
    actual_times = [events[PRESENT] for events in presented.values()]
    print(f"Monado {rate(submits):.2f} submits/s. CV10 {rate(actual_times):.2f} presents/s.")
    print(f"Android present fence and Vulkan actual time agree by frame number for {feedback_matches}/{len(presented)} frames.")
    print(f"Intervals report p50 / p95. Trace clock minus monotonic clock: {offset} ns.")
    for label, times in intervals.items():
        report_interval(label, times)
    if hwc_ids:
        report_hwc(actual_times, outputs)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path)
    parser.add_argument("monado_log", type=Path)
    parser.add_argument("--layer", required=True, help="Exact layer_name in the graphics frame events")
    parser.add_argument("--hwc-id", type=int, action="append", default=[], help="Compare intervals for this HWC ID")
    args = parser.parse_args()
    analyze(args.trace, args.monado_log, args.layer, args.hwc_id)
