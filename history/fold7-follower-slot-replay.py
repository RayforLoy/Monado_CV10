#!/usr/bin/env python3
"""Replay the AOSP follower-slot helper with Fold7 timing captures."""

import argparse
from bisect import bisect_left, bisect_right
from collections import Counter
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from statistics import quantiles
import subprocess


WINDOW = spec_from_file_location("follower_window", Path(__file__).with_name("fold7-follower-window.py"))
TRACE = module_from_spec(WINDOW)
WINDOW.loader.exec_module(TRACE)


def invoke(executable, rows):
    result = subprocess.run([str(executable.resolve())], input="".join(f"{row}\n" for row in rows),
                            capture_output=True, text=True, check=True)
    return result.stdout.splitlines()


def replay(executable, path, reference, cv10):
    capture = TRACE.read_capture(path)
    phone_retires = capture.phone_retires
    phone_calls = [start for start, _ in capture.phone_hwc]
    plans = []
    duration = 0
    (budgets,) = invoke(executable, ["C"])
    start_lead, planning_lead = map(int, budgets.split())
    for grid, _ in TRACE.cv10_dispatch_runs(capture.cv10_retires, reference.cv10_lead):
        duration += grid[-1] - grid[0]
        for current, upcoming in zip(grid, grid[1:]):
            now = current + cv10.after_hwc
            next_wake = upcoming - cv10.before_hwc
            last_retire = bisect_right(phone_retires, now) - 1
            if last_retire < 0:
                continue
            anchor = phone_retires[last_retire]
            # This simulates the follower's VSync tracker with the latest causal sample.
            # The reconstructed CV10 grid supplies its planned wake, so this is conditional
            # on that wake being known to SurfaceFlinger at the time of reservation.
            steps = (now + planning_lead - anchor) // reference.phone_period + 1
            target = anchor + steps * reference.phone_period
            plans.append((now, target, reference.phone_period, next_wake))

    if not plans or duration <= 0:
        raise ValueError(f"No usable display windows in {path}")
    output = invoke(executable, ["P {} {} {} {}".format(*plan) for plan in plans])
    reservations = []
    for (_, target, _, next_wake), result in zip(plans, output, strict=True):
        if result == "-":
            continue
        wake, latest = map(int, result.split())
        nearest = bisect_left(phone_retires, target)
        candidates = phone_retires[max(0, nearest - 1):nearest + 1]
        actual_target = min(candidates, key=lambda time: abs(time - target))
        if abs(actual_target - target) <= reference.phone_period // 2:
            reservations.append((target, wake, latest, next_wake, actual_target))

    seconds = duration / (1_000 * TRACE.MS)
    print(f"{path.stem}: {len(plans)} plan calls, {len(reservations)} matched reservations, {seconds:.2f}s")
    for delay in (0, 500_000, 1_000_000):
        for include_stock_contention in (False, True):
            rows = []
            verified = []
            for target, wake, latest, next_wake, actual_target in reservations:
                now = wake + delay
                previous = bisect_right(phone_retires, now) - 1
                retire = phone_retires[previous]
                call = bisect_right(phone_calls, now) - 1
                # Approximation for the diagnostic comparison with stock traffic still present.
                pending = include_stock_contention and call >= 0 and phone_calls[call] > retire
                updated_target = retire + round((target - retire) / reference.phone_period) * reference.phone_period
                rows.append(f"R {target} {wake} {latest} {now} {int(pending)} {retire} {next_wake} {updated_target}")
                verified.append((now, actual_target))

            results = list(map(int, invoke(executable, rows)))
            counts = Counter(results)
            ready = {}
            for (now, target), result in zip(verified, results, strict=True):
                if result == 0:
                    ready[target] = min(now, ready.get(target, now))
            lead = [target - now for target, now in ready.items()]
            late = sum(value < start_lead for value in lead)
            lead_p05 = quantiles(lead, n=20)[0] / TRACE.MS if len(lead) > 1 else 0
            print(f"  wake delay {delay / TRACE.MS:.1f} ms, "
                  f"stock traffic {'on' if include_stock_contention else 'off'}: "
                  f"{len(ready)} distinct ready ({len(ready) / seconds:.2f}/s), "
                  f"{late} below {start_lead / TRACE.MS:.1f} ms lead, p05 lead {lead_p05:.2f} ms, "
                  f"rechecks {dict(sorted(counts.items()))}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("executable", type=Path, help="Host build of fold7-follower-slot-check.cpp")
    parser.add_argument("capture", type=Path, nargs="+")
    parser.add_argument("--auto", required=True, type=Path, help="Phone-led 60 Hz reference trace")
    parser.add_argument("--cv10-only", required=True, type=Path, help="90 Hz CV10 with the phone panel off")
    args = parser.parse_args()
    reference = TRACE.load_reference(args.auto, args.cv10_only)
    cv10, _ = TRACE.read_sf_budget(args.cv10_only, 1)
    for path in args.capture:
        replay(args.executable, path, reference, cv10)
