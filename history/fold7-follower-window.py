#!/usr/bin/env python3
"""Replay the phone's observed retire windows against a reconstructed CV10 dispatch grid."""

import argparse
from bisect import bisect_left, bisect_right
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
class DispatchBudget:
    before_hwc: int
    after_hwc: int


def read_sf_budget(path, hwc):
    with TraceProcessor(trace=str(path)) as processor:
        rows = list(processor.query(f"""
            WITH main_slices AS (
                SELECT s.ts, s.dur, s.name, s.depth, s.track_id
                FROM slice s JOIN thread_track t ON t.id = s.track_id
                JOIN thread USING (utid) JOIN process USING (upid)
                WHERE thread.tid = process.pid AND thread.name = 'surfaceflinger' AND s.dur > 0
            ),
            begins AS (
                SELECT track_id, SUBSTR(name, 12, INSTR(name, ' vsyncIn') - 12) AS token,
                       MIN(ts) AS ts
                FROM main_slices WHERE depth = 0 AND name GLOB 'beginFrame * vsyncIn*'
                GROUP BY track_id, token
            ),
            composites AS (
                SELECT ts, dur, track_id, SUBSTR(name, 11) AS token
                FROM main_slices WHERE depth = 0 AND name GLOB 'composite *'
            ),
            calls AS (
                SELECT ts, track_id FROM main_slices WHERE name = 'HwcPresentOrValidateDisplay {hwc}'
            )
            SELECT calls.ts - begins.ts AS before_hwc,
                   composites.ts + composites.dur - calls.ts AS after_hwc
            FROM calls JOIN composites ON calls.track_id = composites.track_id
                 AND calls.ts BETWEEN composites.ts AND composites.ts + composites.dur
            JOIN begins ON begins.track_id = composites.track_id AND begins.token = composites.token
                AND begins.ts <= composites.ts
        """))
    if len(rows) < 20:
        raise ValueError(f"{path} has too few paired SurfaceFlinger frames for display {hwc}: {len(rows)}")
    return DispatchBudget(
        before_hwc=round(quantiles([row.before_hwc for row in rows], n=20)[18]),
        after_hwc=round(quantiles([row.after_hwc for row in rows], n=20)[18]),
    ), len(rows)


@dataclass(frozen=True)
class Reference:
    cv10_lead: int
    phone_lead: int
    phone_period: int
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
        phone_period=round(median(later - earlier for earlier, later in zip(auto.phone_retires, auto.phone_retires[1:]))),
        check_to_hwc=round(median(intervals_to_next(cv10_only.sf_checks, cv10_calls, CV10_PERIOD))),
        cv10_call_duration=round(quantiles([duration for _, duration in cv10_only.cv10_hwc], n=20)[18]),
        phone_call_duration=round(quantiles([duration for _, duration in auto.phone_hwc], n=20)[18]),
    )


def cv10_dispatch_runs(retires, lead):
    first_retire = 0
    grid = []
    for index, (earlier, later) in enumerate(zip(retires, retires[1:])):
        gap = later - earlier
        steps = round(gap / CV10_PERIOD)
        if steps < 1 or abs(gap - steps * CV10_PERIOD) > 750_000:
            if len(grid) >= 2:
                yield grid, retires[first_retire:index + 1]
            first_retire = index + 1
            grid = []
            continue
        grid.extend(earlier + round(step * gap / steps) - lead for step in range(steps))
    if len(grid) >= 2:
        yield grid, retires[first_retire:]


def phone_windows(phone_retires, first_dispatch, last_dispatch):
    first = bisect_left(phone_retires, first_dispatch)
    last = bisect_left(phone_retires, last_dispatch)
    timestamps = phone_retires[first:last]
    return list(zip(timestamps, timestamps[1:]))


def first_joint_dispatch(ready, latest, grid, check_lead):
    index = bisect_left(grid, ready + check_lead)
    return index < len(grid) and grid[index] <= latest


def independent_dispatch(ready, latest, ideal, grid, cv10, phone, guard):
    index = max(0, bisect_left(grid, ready) - 1)
    candidates = []
    while index + 1 < len(grid) and grid[index] <= latest:
        earliest = max(ready, grid[index] + cv10.after_hwc + guard + phone.before_hwc)
        deadline = min(latest, grid[index + 1] - cv10.before_hwc - guard - phone.after_hwc)
        if earliest <= deadline:
            candidates.append(min(max(ideal, earliest), deadline))
        index += 1
    return min(candidates, key=lambda time: abs(time - ideal), default=None)


def causal_dispatch(ready, latest, ideal, previous_retires, reference, cv10, phone, guard, margin):
    # Project recent samples to the cycle of the latest observed retire and take the median phase.
    latest_retire = previous_retires[-1]
    phases = [retire + round((latest_retire - retire) / CV10_PERIOD) * CV10_PERIOD
              for retire in previous_retires]
    anchor = round(median(phases)) - reference.cv10_lead
    first = (ready - anchor) // CV10_PERIOD
    last = (latest - anchor) // CV10_PERIOD + 1
    predicted_grid = [anchor + step * CV10_PERIOD for step in range(first, last + 1)]
    return independent_dispatch(ready + phone.before_hwc, latest - margin, ideal,
                                predicted_grid, cv10, phone, guard + margin)


def rechecked_dispatch(ready, latest, ideal, retires, reference, cv10, phone, guard, margin):
    index = bisect_right(retires, ready)
    while index:
        chosen = causal_dispatch(ready, latest, ideal, retires[max(0, index - 5):index],
                                 reference, cv10, phone, guard, margin)
        # Re-plan if another real CV10 retire arrives before the phone work starts.
        # Once phone preparation starts, its main-thread budget is already reserved.
        wake = chosen - phone.before_hwc if chosen is not None else latest - phone.before_hwc - margin
        if index == len(retires) or retires[index] > wake:
            return chosen
        ready = retires[index]
        index = bisect_right(retires, ready, lo=index)
    return None


def overlaps_cv10(chosen, grid, cv10, phone, guard):
    index = bisect_left(grid, chosen)
    return (index == 0 or index == len(grid) or
            chosen - phone.before_hwc < grid[index - 1] + cv10.after_hwc + guard or
            grid[index] - cv10.before_hwc < chosen + phone.after_hwc + guard)


def report(path, reference, guard, sf_budgets, prediction_margin):
    capture = read_capture(path)
    runs = list(cv10_dispatch_runs(capture.cv10_retires, reference.cv10_lead))
    if not runs:
        raise ValueError("The trace contains no reconstructable CV10 dispatch intervals")
    full_windows = phone_windows(capture.phone_retires, runs[0][0][0], runs[-1][0][-1])
    windows = [(earlier, later, grid, retires) for grid, retires in runs
               for earlier, later in phone_windows(capture.phone_retires, grid[0], grid[-1])]
    if not full_windows or not windows:
        raise ValueError("The trace contains no complete phone retire windows")

    span = (full_windows[-1][1] - full_windows[0][0]) / (1_000 * MS)
    excluded = len(full_windows) - len(windows)
    print(f"{path.stem}: {len(windows)} of {len(full_windows)} windows over {span:.3f} s "
          f"({excluded} excluded across {len(runs)} CV10 timing runs)")
    print("Phone fence lead | Frame-start check | HWC-start check | Independent HWC | Independent main thread | "
          "Causal main thread (late/overlap) | Recheck at CV10 retire (late/overlap)")
    hwc_budgets = (DispatchBudget(0, reference.cv10_call_duration), DispatchBudget(0, reference.phone_call_duration))
    budget_options = (hwc_budgets, sf_budgets)
    for lead_ms in (1, 3, 5, 7, 9):
        early, late = 0, 0
        shifts = [[] for _ in budget_options]
        causal_phases = [[], []]
        deadline_misses = [0, 0]
        overlaps = [0, 0]
        for previous, target, grid, retires in windows:
            ready = previous + PHONE_RETIRE_GUARD
            latest = target - lead_ms * MS - reference.phone_call_duration
            ideal = target - reference.phone_lead
            early += first_joint_dispatch(ready, latest, grid, reference.check_to_hwc)
            late += first_joint_dispatch(ready, latest, grid, 0)
            for phases, (cv10, phone) in zip(shifts, budget_options):
                chosen = independent_dispatch(ready, latest, ideal, grid, cv10, phone, guard)
                if chosen is not None:
                    phases.append(abs(chosen - ideal))
            index = bisect_right(retires, ready)
            if index == 0:
                continue
            predicted_target = previous + reference.phone_period
            predicted_latest = predicted_target - lead_ms * MS - reference.phone_call_duration
            predicted_ideal = predicted_target - reference.phone_lead
            choices = (
                causal_dispatch(ready, predicted_latest, predicted_ideal, retires[max(0, index - 5):index],
                                reference, *sf_budgets, guard, prediction_margin),
                rechecked_dispatch(ready, predicted_latest, predicted_ideal, retires,
                                   reference, *sf_budgets, guard, prediction_margin),
            )
            for choice, chosen in enumerate(choices):
                if chosen is None:
                    continue
                late_dispatch = chosen > latest
                overlap = overlaps_cv10(chosen, grid, *sf_budgets, guard)
                deadline_misses[choice] += late_dispatch
                overlaps[choice] += overlap
                if not late_dispatch and not overlap:
                    causal_phases[choice].append(abs(chosen - ideal))
        dispatch = " | ".join(format_dispatch(phases, span) for phases in shifts)
        causal_dispatches = " | ".join(
            f"{format_dispatch(phases, span)} ({late}/{overlap})"
            for phases, late, overlap in zip(causal_phases, deadline_misses, overlaps)
        )
        print(f"{lead_ms} ms | {early} ({early / span:.2f}/s) | {late} ({late / span:.2f}/s) | {dispatch} | "
              f"{causal_dispatches}")


def format_dispatch(phases, span):
    shift95 = quantiles(phases, n=20)[18] if len(phases) > 1 else (phases[0] if phases else 0)
    return f"{len(phases)} ({len(phases) / span:.2f}/s, p95 shift {shift95 / MS:.2f} ms)"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path, nargs="+")
    parser.add_argument("--auto", type=Path, required=True, help="Primary 60 Hz phone reference")
    parser.add_argument("--cv10-only", type=Path, required=True, help="90 Hz CV10 reference with phone panel off")
    parser.add_argument("--guard-ms", type=float, default=0.5, help="Reserve on either side of CV10 HWC work")
    parser.add_argument("--prediction-margin-ms", type=float, default=0.25,
                        help="Extra reserve on predicted deadlines in the causal model")
    args = parser.parse_args()
    for option in ("guard_ms", "prediction_margin_ms"):
        value = getattr(args, option)
        if not isfinite(value) or value < 0:
            parser.error(f"--{option.replace('_', '-')} must be a finite non-negative number")
    reference = load_reference(args.auto, args.cv10_only)
    print(f"CV10 reference lead {reference.cv10_lead / MS:.3f} ms, "
          f"phone lead {reference.phone_lead / MS:.3f} ms, frame check is {reference.check_to_hwc / MS:.3f} ms before HWC.")
    print(f"Reference p95 HWC duration: CV10 {reference.cv10_call_duration / MS:.3f} ms, "
          f"phone {reference.phone_call_duration / MS:.3f} ms. CV10 reserve is {args.guard_ms:.2f} ms.")
    cv10_sf, cv10_count = read_sf_budget(args.cv10_only, 1)
    phone_sf, phone_count = read_sf_budget(args.auto, 3)
    print("Main-thread p95 from beginFrame to HWC start and from HWC start to composite end: "
          f"CV10 {cv10_sf.before_hwc / MS:.3f} + {cv10_sf.after_hwc / MS:.3f} ms ({cv10_count} pairs), "
          f"phone baseline {phone_sf.before_hwc / MS:.3f} + {phone_sf.after_hwc / MS:.3f} ms ({phone_count} pairs).")
    print(f"Causal prediction uses a phone period of {reference.phone_period / MS:.3f} ms "
          f"and {args.prediction_margin_ms:.2f} ms extra deadline reserve.")
    for capture in args.capture:
        report(capture, reference, round(args.guard_ms * MS), (cv10_sf, phone_sf),
               round(args.prediction_margin_ms * MS))
