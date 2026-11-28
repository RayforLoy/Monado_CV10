#!/usr/bin/env python3
"""Replay the phone's observed retire windows against a reconstructed CV10 dispatch grid."""

import argparse
from bisect import bisect_left
from dataclasses import dataclass
from math import isfinite
from pathlib import Path
from statistics import median, quantiles

from perfetto.trace_processor import TraceProcessor


MS = 1_000_000
CV10_PERIOD = round(1_000 * MS / 90)
# The stock trace measured the phone CRTC worker ending up to 0.448 ms after retire.
PHONE_RETIRE_GUARD = 500_000


@dataclass
class Capture:
    phone_retires: list[int]
    cv10_retires: list[int]
    phone_hwc: list[tuple[int, int]]
    cv10_hwc: list[tuple[int, int]]
    sf_checks: list[int]


def read_capture(path):
    with TraceProcessor(trace=str(path)) as processor:
        def read_retires(crtc):
            return [row.ts for row in processor.query(f"""
                SELECT slice.ts + slice.dur AS ts
                FROM slice JOIN thread_track ON slice.track_id = thread_track.id JOIN thread USING (utid)
                WHERE thread.name = 'crtc_event:{crtc}' AND slice.name = 'signal_retire_fence'
                  AND slice.dur >= 0 ORDER BY ts
            """)]

        def read_calls(hwc):
            return [(row.ts, row.dur) for row in processor.query(f"""
                SELECT ts, dur FROM slice
                WHERE name = 'HwcPresentOrValidateDisplay {hwc}' AND dur > 0 ORDER BY ts
            """)]

        checks = [row.ts for row in processor.query("""
            SELECT DISTINCT frame.ts
            FROM slice AS frame
            JOIN counter ON counter.ts BETWEEN frame.ts AND frame.ts + frame.dur
            JOIN counter_track ON counter_track.id = counter.track_id
            WHERE frame.name LIKE 'beginFrame % vsyncIn%'
              AND counter_track.name = 'PrevFramePending 4621527156978103573'
            ORDER BY frame.ts
        """)]
        return Capture(read_retires(288), read_retires(301), read_calls(3), read_calls(1), checks)


def intervals_to_next(starts, ends, max_interval):
    for start in starts:
        index = bisect_left(ends, start)
        if index < len(ends) and ends[index] - start <= max_interval:
            yield ends[index] - start


@dataclass(frozen=True)
class Reference:
    cv10_lead: int
    phone_lead: int
    check_to_hwc: int
    cv10_call_duration: int
    phone_call_duration: int


def load_reference(auto_path, cv10_only_path):
    auto = read_capture(auto_path)
    cv10_only = read_capture(cv10_only_path)
    phone_calls = [start for start, _ in auto.phone_hwc]
    cv10_calls = [start for start, _ in cv10_only.cv10_hwc]
    return Reference(
        cv10_lead=round(median(intervals_to_next(cv10_calls, cv10_only.cv10_retires, CV10_PERIOD))),
        phone_lead=round(median(intervals_to_next(phone_calls, auto.phone_retires, 20 * MS))),
        check_to_hwc=round(median(intervals_to_next(cv10_only.sf_checks, cv10_calls, CV10_PERIOD))),
        cv10_call_duration=round(quantiles([duration for _, duration in cv10_only.cv10_hwc], n=20)[18]),
        phone_call_duration=round(quantiles([duration for _, duration in auto.phone_hwc], n=20)[18]),
    )


def cv10_dispatch_grid(retires, lead):
    for earlier, later in zip(retires, retires[1:]):
        gap = later - earlier
        steps = round(gap / CV10_PERIOD)
        if steps < 1 or abs(gap - steps * CV10_PERIOD) > 750_000:
            raise ValueError(f"CV10 retire gap {gap / MS:.3f} ms does not fit the 90 Hz grid")
        for step in range(steps):
            yield earlier + round(step * gap / steps) - lead


def first_joint_dispatch(ready, latest, grid, check_lead):
    index = bisect_left(grid, ready + check_lead)
    return index < len(grid) and grid[index] <= latest


def independent_dispatch(ready, latest, ideal, grid, reference, guard):
    index = max(0, bisect_left(grid, ready) - 1)
    candidates = []
    while index + 1 < len(grid) and grid[index] <= latest:
        earliest = max(ready, grid[index] + reference.cv10_call_duration + guard)
        deadline = min(latest, grid[index + 1] - guard - reference.phone_call_duration)
        if earliest <= deadline:
            candidates.append(min(max(ideal, earliest), deadline))
        index += 1
    return min(candidates, key=lambda time: abs(time - ideal), default=None)


def report(path, reference, guard):
    capture = read_capture(path)
    grid = list(cv10_dispatch_grid(capture.cv10_retires, reference.cv10_lead))
    if len(grid) < 2:
        raise ValueError("The trace contains too few CV10 retire fences")
    windows = [(earlier, later) for earlier, later in zip(capture.phone_retires, capture.phone_retires[1:])
               if grid[0] <= earlier and later < grid[-1]]
    if not windows:
        raise ValueError("The trace contains no complete phone retire windows")

    span = (windows[-1][1] - windows[0][0]) / (1_000 * MS)
    print(f"{path.stem}: {len(windows)} windows over {span:.3f} s")
    print("Phone fence lead | Frame-start check | HWC-start check | Independent phone dispatch | p95 phase movement")
    for lead_ms in (1, 3, 5, 7, 9):
        early, late = 0, 0
        shifts = []
        for previous, target in windows:
            ready = previous + PHONE_RETIRE_GUARD
            latest = target - lead_ms * MS - reference.phone_call_duration
            ideal = target - reference.phone_lead
            early += first_joint_dispatch(ready, latest, grid, reference.check_to_hwc)
            late += first_joint_dispatch(ready, latest, grid, 0)
            chosen = independent_dispatch(ready, latest, ideal, grid, reference, guard)
            if chosen is not None:
                shifts.append(abs(chosen - ideal))
        shift95 = quantiles(shifts, n=20)[18] if len(shifts) > 1 else (shifts[0] if shifts else 0)
        print(f"{lead_ms} ms | {early} ({early / span:.2f}/s) | {late} ({late / span:.2f}/s) | "
              f"{len(shifts)} ({len(shifts) / span:.2f}/s) | {shift95 / MS:.2f} ms")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path, nargs="+")
    parser.add_argument("--auto", type=Path, required=True, help="Primary 60 Hz phone reference")
    parser.add_argument("--cv10-only", type=Path, required=True, help="90 Hz CV10 reference with phone panel off")
    parser.add_argument("--guard-ms", type=float, default=0.5, help="Reserve on either side of CV10 HWC work")
    args = parser.parse_args()
    if not isfinite(args.guard_ms) or args.guard_ms < 0:
        parser.error("--guard-ms must be a finite non-negative number")
    reference = load_reference(args.auto, args.cv10_only)
    print(f"CV10 reference lead {reference.cv10_lead / MS:.3f} ms, "
          f"phone lead {reference.phone_lead / MS:.3f} ms, frame check is {reference.check_to_hwc / MS:.3f} ms before HWC.")
    print(f"Reference p95 HWC duration: CV10 {reference.cv10_call_duration / MS:.3f} ms, "
          f"phone {reference.phone_call_duration / MS:.3f} ms. CV10 reserve is {args.guard_ms:.2f} ms.")
    for capture in args.capture:
        report(capture, reference, round(args.guard_ms * MS))
