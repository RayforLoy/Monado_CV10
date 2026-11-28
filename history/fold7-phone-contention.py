#!/usr/bin/env python3
"""Separate main-thread phone HWC and inferred DRM contention at missed CV10 ticks."""

import argparse
from bisect import bisect_right
from collections import Counter
from dataclasses import dataclass
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from statistics import median

from perfetto.trace_processor import TraceProcessor


WINDOW = spec_from_file_location("follower_window", Path(__file__).with_name("fold7-follower-window.py"))
TRACE = module_from_spec(WINDOW)
WINDOW.loader.exec_module(TRACE)

# Device-specific IDs from Fold7 firmware F9660ZCUDBZJ9 and the matched captures.
EVENTS_SQL = """
WITH timed AS MATERIALIZED (
    SELECT s.id, s.parent_id, s.name, s.ts, s.ts + s.dur AS finish,
           thread.name AS thread, thread.tid = process.pid AS main_thread
    FROM slice AS s JOIN thread_track AS track ON track.id = s.track_id
    JOIN thread USING (utid) LEFT JOIN process USING (upid)
    WHERE s.dur >= 0 AND s.name IN (
        'signal_retire_fence', 'complete_commit', 'DRMAtomicReq::Commit::',
        'atomic_commit', 'sde_kms_prepare_fence',
        'HwcPresentOrValidateDisplay 1', 'HwcPresentOrValidateDisplay 3'
    )
), phone_atomic AS MATERIALIZED (
    SELECT atomic.ts, atomic.finish, prepare.ts AS prepare_ts
    FROM timed AS atomic
    JOIN timed AS request ON request.id = atomic.parent_id AND request.name = 'DRMAtomicReq::Commit::'
    JOIN timed AS prepare ON prepare.parent_id = atomic.id AND prepare.name = 'sde_kms_prepare_fence'
    WHERE atomic.name = 'atomic_commit' AND atomic.thread GLOB 'binder:*_1'
      AND atomic.finish > atomic.ts
), phone_workers AS MATERIALIZED (
    SELECT ts, finish FROM timed WHERE name = 'complete_commit' AND thread = 'crtc_commit:288'
)
SELECT 'cv10_retire' AS kind, finish AS ts, finish FROM timed
WHERE name = 'signal_retire_fence' AND thread = 'crtc_event:301'
UNION ALL
SELECT 'cv10_request', ts, finish FROM timed
WHERE name = 'DRMAtomicReq::Commit::' AND thread = 'sdm_hpd_process' AND finish > ts
UNION ALL
SELECT 'cv10_hwc', ts, finish FROM timed
WHERE name = 'HwcPresentOrValidateDisplay 1' AND thread = 'surfaceflinger' AND main_thread AND finish > ts
UNION ALL
SELECT 'phone_hwc', ts, finish FROM timed
WHERE name = 'HwcPresentOrValidateDisplay 3' AND thread = 'surfaceflinger' AND main_thread AND finish > ts
UNION ALL
SELECT 'phone_atomic', phone_atomic.ts, phone_atomic.finish FROM phone_atomic
UNION ALL
SELECT 'phone_drm_wait', phone_atomic.ts, phone_atomic.prepare_ts FROM phone_atomic
WHERE EXISTS (
    SELECT 1 FROM phone_workers AS previous
    WHERE previous.ts <= phone_atomic.ts AND previous.finish > phone_atomic.ts
      AND previous.finish BETWEEN phone_atomic.prepare_ts - 300000 AND phone_atomic.prepare_ts
)
ORDER BY ts
"""


@dataclass(frozen=True)
class Intervals:
    starts: list[int]
    ends: list[int]

    @classmethod
    def from_rows(cls, rows):
        starts = []
        ends = []
        for start, end in rows:
            if ends and start <= ends[-1]:
                ends[-1] = max(ends[-1], end)
            else:
                starts.append(start)
                ends.append(end)
        return cls(starts, ends)

    def contains(self, point):
        index = bisect_right(self.starts, point) - 1
        return index >= 0 and point < self.ends[index]


def read_events(path):
    events = {kind: [] for kind in (
        "cv10_retire", "cv10_request", "cv10_hwc", "phone_hwc", "phone_atomic", "phone_drm_wait",
    )}
    with TraceProcessor(trace=str(path)) as processor:
        for row in processor.query(EVENTS_SQL):
            events[row.kind].append((row.ts, row.finish))
    return events


def starts(events, kind):
    return [start for start, _ in events[kind]]


@dataclass(frozen=True)
class Leads:
    sf_to_retire: int
    request_to_retire: int


def calibrate(path, events):
    retires = starts(events, "cv10_retire")
    if len(retires) < 20:
        raise ValueError(f"{path}: too few CV10 hardware retire timestamps")
    period = median(later - earlier for earlier, later in zip(retires, retires[1:]))
    if abs(period - TRACE.CV10_PERIOD) > 100_000 or events["phone_hwc"] or events["phone_atomic"]:
        raise ValueError(f"{path}: reference must be a CV10 90 Hz trace with phone HWC inactive")
    hwc = list(TRACE.intervals_to_next(starts(events, "cv10_hwc"), retires, TRACE.CV10_PERIOD))
    request = list(TRACE.intervals_to_next(starts(events, "cv10_request"), retires, TRACE.CV10_PERIOD))
    if min(len(hwc), len(request)) < 0.95 * len(retires):
        raise ValueError(f"{path}: too few CV10 HWC/DRM events paired to a retire timestamp")
    budget, _ = TRACE.read_sf_budget(path, 1)
    return Leads(round(median(hwc)) + budget.before_hwc, round(median(request)))


def missing_ticks(retires):
    for grid, actual in TRACE.cv10_dispatch_runs(retires, 0):
        # The existing grid helper excludes the closing retire from each valid run.
        observed = set(actual)
        yield from (tick for tick in grid if tick not in observed)


def replay(events, leads, phase):
    retires = starts(events, "cv10_retire")
    inline = Intervals.from_rows(events["phone_hwc"])
    drm = Intervals.from_rows(events["phone_drm_wait"])
    counts = Counter()
    # Drop the trace-edge periods to avoid counting incomplete blocking slices.
    margin = 2 * TRACE.CV10_PERIOD
    for tick in missing_ticks(retires):
        if tick < retires[0] + margin or tick > retires[-1] - margin:
            continue
        sf_blocked = inline.contains(tick - leads.sf_to_retire + phase)
        drm_blocked = drm.contains(tick - leads.request_to_retire + phase)
        counts[sf_blocked, drm_blocked] += 1
    return counts


def report(path, events, leads):
    retires = starts(events, "cv10_retire")
    if len(retires) < 20:
        raise ValueError(f"{path}: too few CV10 hardware retire timestamps")
    hz = (len(retires) - 1) * 1_000 * TRACE.MS / (retires[-1] - retires[0])
    print(f"{path.name}: {hz:.2f} CV10 retires/s, {len(events['phone_hwc'])} inline phone calls, "
          f"{len(events['phone_drm_wait'])}/{len(events['phone_atomic'])} phone atomics paired to an older worker")
    for phase_ms in (-1, 0, 1):
        counts = replay(events, leads, phase_ms * TRACE.MS)
        print(f"  {phase_ms:+} ms: {counts.total()} missed; both {counts[True, True]}, "
              f"SF only {counts[True, False]}, DRM only {counts[False, True]}, "
              f"neither {counts[False, False]}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cv10-only", required=True, type=Path, help="90 Hz trace with phone panel off")
    parser.add_argument("capture", type=Path, nargs="+")
    args = parser.parse_args()
    reference = read_events(args.cv10_only)
    leads = calibrate(args.cv10_only, reference)
    print(f"Reference lead before retire: SF {leads.sf_to_retire / TRACE.MS:.3f} ms, "
          f"external DRM request {leads.request_to_retire / TRACE.MS:.3f} ms")
    report(args.cv10_only, reference, leads)
    for path in args.capture:
        report(path, read_events(path), leads)


if __name__ == "__main__":
    main()
