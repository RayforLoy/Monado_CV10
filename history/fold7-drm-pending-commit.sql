-- Thread names in hello-composer-lit-{auto,forced}.perfetto-trace.
WITH phone_workers AS (
    SELECT work.ts, work.dur
    FROM slice AS work
    JOIN thread_track AS track ON track.id = work.track_id
    JOIN thread USING (utid)
    WHERE thread.name = 'crtc_commit:288'
        AND work.name = 'complete_commit'
        AND work.dur > 0
), atomic_fences AS (
    SELECT commit_slice.ts AS enter_ts,
           commit_slice.ts + commit_slice.dur AS exit_ts,
           fence.ts AS fence_ts
    FROM slice AS commit_slice
    JOIN slice AS request ON request.id = commit_slice.parent_id
    JOIN slice AS fence ON fence.parent_id = commit_slice.id
    JOIN thread_track AS track ON track.id = commit_slice.track_id
    JOIN thread USING (utid)
    WHERE thread.name = 'binder:1825_1'
        AND request.name = 'DRMAtomicReq::Commit::'
        AND commit_slice.name = 'atomic_commit'
        AND commit_slice.dur > 0
        AND fence.name = 'sde_kms_prepare_fence'
), matched AS (
    SELECT atomic_fences.*,
           (SELECT MAX(ts + dur) FROM phone_workers
            WHERE ts <= enter_ts AND ts + dur > enter_ts) AS previous_end_ts,
           (SELECT MIN(ts) FROM phone_workers
            WHERE ts >= fence_ts) AS next_start_ts
    FROM atomic_fences
)
SELECT COUNT(*) AS calls,
       ROUND(AVG(fence_ts - enter_ts) / 1e6, 3) AS mean_enter_to_fence_ms,
       ROUND(AVG(exit_ts - fence_ts) / 1e6, 3) AS mean_fence_to_exit_ms,
       COUNT(previous_end_ts) AS previous_in_flight,
       COUNT(*) FILTER (WHERE previous_end_ts BETWEEN fence_ts - 300000 AND fence_ts)
           AS previous_ends_within_300us_before_fence,
       ROUND(AVG(fence_ts - previous_end_ts) / 1e6, 3) AS mean_previous_end_to_fence_ms,
       COUNT(*) FILTER (WHERE next_start_ts BETWEEN exit_ts AND exit_ts + 300000)
           AS next_starts_within_300us_after_return
FROM matched
