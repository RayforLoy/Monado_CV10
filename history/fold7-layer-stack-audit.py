#!/usr/bin/env python3
"""Check physical display routing and visible phone buffer changes in Fold7 traces."""

import argparse
from collections import Counter, defaultdict
from pathlib import Path
from statistics import median, quantiles

from perfetto.trace_processor import TraceProcessor


DISPLAYS = """
SELECT s.id AS snapshot, EXTRACT_ARG(s.arg_set_id, 'vsync_id') AS vsync_id,
       display.display_id, display.is_on, display.is_virtual,
       EXTRACT_ARG(s.arg_set_id, SUBSTR(ids.key, 1, LENGTH(ids.key) - 2) || 'layer_stack') AS stack
FROM surfaceflinger_layers_snapshot AS s
JOIN args AS ids ON ids.arg_set_id = s.arg_set_id AND ids.flat_key = 'displays.id'
JOIN __intrinsic_surfaceflinger_display AS display
  ON display.snapshot_id = s.id AND display.display_id = ids.int_value
WHERE NOT s.has_invalid_elapsed_ts
ORDER BY s.ts, s.id
"""
VISIBLE_LAYERS = """
SELECT snapshot_id, layer_id, EXTRACT_ARG(arg_set_id, 'layer_stack') AS stack,
       EXTRACT_ARG(arg_set_id, 'curr_frame') AS frame_number,
       EXTRACT_ARG(arg_set_id, 'active_buffer.width') AS buffer_width
FROM surfaceflinger_layer
WHERE is_visible
"""
PHONE_BATCHES = """
WITH main_thread AS MATERIALIZED (
    SELECT s.track_id, s.ts, s.dur, s.name, s.depth
    FROM slice s JOIN thread_track tr ON tr.id = s.track_id JOIN thread USING (utid)
    JOIN process USING (upid)
    WHERE thread.tid = process.pid AND s.dur > 0
      AND (thread.name = 'surfaceflinger' OR process.name = 'surfaceflinger'
           OR process.name LIKE '%/surfaceflinger' OR (thread.name IS NULL AND process.name IS NULL))
), composites AS MATERIALIZED (
    SELECT track_id, ts, ts + dur AS finish, CAST(SUBSTR(name, 11) AS INTEGER) AS vsync_id
    FROM main_thread WHERE depth = 0 AND name GLOB 'composite [0-9]*'
), calls AS MATERIALIZED (
    SELECT track_id, ts, dur FROM main_thread WHERE name = 'HwcPresentOrValidateDisplay {phone_hwc}'
)
SELECT composites.vsync_id, composites.ts, calls.dur,
       COUNT(*) OVER (PARTITION BY composites.vsync_id) AS calls_with_token
FROM composites JOIN calls ON calls.track_id = composites.track_id
                         AND calls.ts >= composites.ts AND calls.ts + calls.dur <= composites.finish
ORDER BY composites.ts
"""
UNASSIGNED = 0xFFFFFFFF


def track_routes(processor, phone_display, cv10_display):
    snapshots = defaultdict(list)
    for row in processor.query(DISPLAYS):
        snapshots[row.snapshot].append(row)

    counts = Counter()
    stack_pairs = Counter()
    phone_routes = {}
    excluded_tokens = set()
    for snapshot_id, displays in snapshots.items():
        physical = {row.display_id: row for row in displays if not row.is_virtual}
        phone, cv10 = physical.get(phone_display), physical.get(cv10_display)
        vsync_id = displays[0].vsync_id
        if phone is None or cv10 is None or not phone.is_on or not cv10.is_on:
            counts["missing_or_off"] += 1
        elif any(stack in (None, UNASSIGNED) for stack in (phone.stack, cv10.stack)):
            counts["unassigned"] += 1
        elif any(row.display_id != phone_display and row.is_on and row.stack == phone.stack
                 for row in displays):
            counts["shared"] += 1
            stack_pairs[phone.stack, cv10.stack] += 1
        else:
            counts["isolated"] += 1
            stack_pairs[phone.stack, cv10.stack] += 1
            if vsync_id is not None:
                phone_routes[snapshot_id] = (vsync_id, phone.stack)
            continue
        excluded_tokens.add(vsync_id)

    return counts, stack_pairs, {snapshot: route for snapshot, route in phone_routes.items()
                                if route[0] not in excluded_tokens}


def visible_signatures(processor, phone_routes):
    layers = defaultdict(set)
    for row in processor.query(VISIBLE_LAYERS):
        route = phone_routes.get(row.snapshot_id)
        if route is not None and row.stack == route[1]:
            # Include visible solid/effect layers as well as layers with an active buffer.
            layers[row.snapshot_id].add((row.layer_id, row.frame_number, (row.buffer_width or 0) > 0))

    signatures = defaultdict(set)
    for snapshot_id, (vsync_id, phone_stack) in phone_routes.items():
        current = layers.get(snapshot_id)
        if current:
            signatures[vsync_id].add((phone_stack, frozenset(current)))
    # A token with different captured states is ambiguous, including after hotplug.
    return {vsync_id: next(iter(states)) for vsync_id, states in signatures.items() if len(states) == 1}


def compare_phone_batches(rows, signatures):
    counts = Counter()
    durations = []
    previous = None
    for row in rows:
        signature = signatures.get(row.vsync_id) if row.calls_with_token == 1 else None
        if signature is not None:
            counts["matched"] += 1
        if signature is not None and previous is not None:
            counts["compared"] += 1
            if signature == previous:
                counts["same"] += 1
                durations.append(row.dur / 1_000_000)
        # Do not infer changes across a phone batch with a missing snapshot.
        previous = signature
        counts["total"] += 1
    return counts, durations


def report(trace, phone_display, cv10_display, phone_hwc):
    with TraceProcessor(trace=str(trace)) as processor:
        routes, stack_pairs, phone_routes = track_routes(processor, phone_display, cv10_display)
        if not routes:
            raise ValueError(f"{trace}: no display snapshots, enable android.surfaceflinger.layers")
        signatures = visible_signatures(processor, phone_routes)
        calls, durations = compare_phone_batches(
            processor.query(PHONE_BATCHES.format(phone_hwc=phone_hwc)), signatures)
    print(trace.name)
    print(f"  {routes.total()} snapshots, {routes['isolated']} with both displays on and phone stack isolated, "
          f"{routes['shared']} shared, {routes['missing_or_off']} missing/off, {routes['unassigned']} unassigned")
    print(f"  (phone, CV10) layer stacks: {dict(stack_pairs)}")
    if not calls['total']:
        print("  No inline phone HWC calls found with the supplied HAL display ID")
        return
    print(f"  Phone HWC batches: {calls['matched']}/{calls['total']} matched to a usable snapshot")
    print(f"  Consecutive comparisons: {calls['same']}/{calls['compared']} with the same visible layer signature")
    if durations:
        p95 = quantiles(durations, n=20, method='inclusive')[-1] if len(durations) > 1 else durations[0]
        print(f"  Inline phone HWC in those batches: p50 {median(durations):.3f} ms, p95 {p95:.3f} ms")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phone-display", required=True, type=int, help="Physical display ID of the active phone panel")
    parser.add_argument("--cv10-display", required=True, type=int, help="Physical display ID of HUAWEIAV03")
    parser.add_argument("--phone-hwc", required=True, type=int, help="HAL display ID of the active phone panel")
    parser.add_argument("trace", type=Path, nargs="+")
    args = parser.parse_args()
    if args.phone_display == args.cv10_display:
        parser.error("The two physical display IDs must be distinct")
    for trace in args.trace:
        try:
            report(trace, args.phone_display, args.cv10_display, args.phone_hwc)
        except ValueError as error:
            parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
