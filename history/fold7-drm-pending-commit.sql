-- Thread names in hello-composer-{lit-auto,lit-forced,off-forced}.perfetto-trace.
WITH phone_workers AS MATERIALIZED (
    SELECT work.ts, work.dur
    FROM slice AS work
    JOIN thread_track AS track ON track.id = work.track_id
    JOIN thread USING (utid)
    WHERE thread.name = 'crtc_commit:288'
        AND work.name = 'complete_commit'
        AND work.dur > 0
), phone_retires AS MATERIALIZED (
    SELECT retire.ts + retire.dur AS ts
    FROM slice AS retire
    JOIN thread_track AS track ON track.id = retire.track_id
    JOIN thread USING (utid)
    WHERE thread.name = 'crtc_event:288'
        AND retire.name = 'signal_retire_fence'
        AND retire.dur >= 0
), atomic_fences AS MATERIALIZED (
    SELECT commit_slice.ts AS enter_ts,
           commit_slice.ts + commit_slice.dur AS exit_ts,
           fence.ts AS fence_ts,
           hal_commit.ts AS hal_entry_ts
    FROM slice AS commit_slice
    JOIN slice AS request ON request.id = commit_slice.parent_id
    LEFT JOIN slice AS hal_commit ON hal_commit.id = request.parent_id
        AND hal_commit.name = 'HWDeviceDRM::AtomicCommit::'
    JOIN slice AS fence ON fence.parent_id = commit_slice.id
    JOIN thread_track AS track ON track.id = commit_slice.track_id
    JOIN thread USING (utid)
    WHERE thread.name = 'binder:1825_1'
        AND request.name = 'DRMAtomicReq::Commit::'
        AND commit_slice.name = 'atomic_commit'
        AND commit_slice.dur > 0
        AND fence.name = 'sde_kms_prepare_fence'
), matched AS MATERIALIZED (
    SELECT atomic_fences.*,
           (SELECT MAX(ts + dur) FROM phone_workers
            WHERE ts <= enter_ts AND ts + dur > enter_ts) AS previous_end_ts,
           (SELECT MIN(ts) FROM phone_workers
            WHERE ts >= fence_ts) AS next_start_ts
    FROM atomic_fences
), matched_retires AS MATERIALIZED (
    SELECT matched.*,
           (SELECT MAX(phone_retires.ts) FROM phone_retires
            WHERE phone_retires.ts BETWEEN previous_end_ts - 1000000
                                      AND previous_end_ts + 1000000) AS previous_retire_ts
    FROM matched
)
SELECT COUNT(*) AS calls,
       ROUND(AVG(fence_ts - enter_ts) / 1e6, 3) AS mean_enter_to_fence_ms,
       ROUND(AVG(exit_ts - fence_ts) / 1e6, 3) AS mean_fence_to_exit_ms,
       COUNT(previous_end_ts) AS previous_in_flight,
       COUNT(*) FILTER (WHERE previous_end_ts BETWEEN fence_ts - 300000 AND fence_ts)
           AS previous_ends_within_300us_before_fence,
       ROUND(AVG(fence_ts - previous_end_ts) / 1e6, 3) AS mean_previous_end_to_fence_ms,
       COUNT(*) FILTER (WHERE next_start_ts BETWEEN exit_ts AND exit_ts + 300000)
           AS next_starts_within_300us_after_return,
       COUNT(previous_retire_ts) AS previous_retire_matches,
       COUNT(*) FILTER (WHERE previous_retire_ts > enter_ts) AS previous_retire_after_entry,
       COUNT(*) FILTER (WHERE previous_retire_ts < previous_end_ts) AS previous_retire_before_end,
       ROUND(AVG(previous_retire_ts - enter_ts) / 1e6, 3) AS mean_entry_to_previous_retire_ms,
       ROUND(AVG(previous_end_ts - previous_retire_ts) / 1e6, 3) AS mean_previous_retire_to_end_ms,
       ROUND(MAX(previous_end_ts - previous_retire_ts) / 1e6, 3) AS max_previous_retire_to_end_ms,
       ROUND(PERCENTILE(previous_end_ts - previous_retire_ts, 95) / 1e6, 3)
           AS p95_previous_retire_to_end_ms,
       COUNT(*) FILTER (WHERE previous_end_ts - previous_retire_ts > 500000)
           AS exceeded_500us,
       COUNT(*) FILTER (WHERE hal_entry_ts IS NOT NULL AND previous_retire_ts IS NOT NULL)
           AS retire_guard_replay_matches,
       ROUND(PERCENTILE(MAX(0, previous_retire_ts - hal_entry_ts), 99) / 1e6, 3)
           AS retire_guard_replay_p99_ms,
       ROUND(MAX(previous_retire_ts - hal_entry_ts) / 1e6, 3)
           AS retire_guard_replay_max_ms,
       COUNT(*) FILTER (WHERE previous_retire_ts - hal_entry_ts > 17000000)
           AS retire_guard_replay_over_17ms
FROM matched_retires
