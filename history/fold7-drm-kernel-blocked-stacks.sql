-- Run on the Hello XR auto / forced CV10 / restored auto Perfetto captures.
-- linux.perf samples sched_switch with kernel_frames and user_frames=UNWIND_SKIP.
-- Join the sampled stack to the sleep starting on the same CPU clock, then
-- use sched_waking and the DRM trace independently to identify the wake chain.
WITH RECURSIVE stacks(id, path) AS (
    SELECT c.id, f.name
    FROM stack_profile_callsite AS c
    JOIN stack_profile_frame AS f ON f.id = c.frame_id
    WHERE c.parent_id IS NULL
    UNION ALL
    SELECT c.id, p.path || ' > ' || f.name
    FROM stack_profile_callsite AS c
    JOIN stacks AS p ON p.id = c.parent_id
    JOIN stack_profile_frame AS f ON f.id = c.frame_id
), sampled AS MATERIALIZED (
    SELECT p.ts, p.utid,
           CASE
               WHEN thread.name GLOB 'binder:*_1'
                    AND path LIKE '%drm_mode_atomic_ioctl > drm_atomic_nonblocking_commit > msm_atomic_commit > do_wait_intr%'
                   THEN 'phone_nonblocking_commit_wait'
               WHEN thread.name = 'sdm_hpd_process'
                    AND path LIKE '%drm_atomic_set_property > drm_atomic_get_connector_state > drm_modeset_lock > ww_mutex_lock_interruptible%'
                   THEN 'cv10_connector_mutex_wait'
           END AS wait_kind
    FROM perf_sample AS p
    JOIN stacks ON stacks.id = p.callsite_id
    JOIN thread USING (utid)
    WHERE EXISTS (
        SELECT 1 FROM thread_track AS track
        JOIN slice ON slice.track_id = track.id
        WHERE track.utid = p.utid AND slice.name = 'DRMAtomicReq::Commit::'
    )
), sleeps AS MATERIALIZED (
    SELECT state.id, state.utid, state.ts, state.dur, samples.wait_kind
    FROM sampled AS samples
    JOIN thread_state AS state
      ON samples.utid = state.utid AND ABS(samples.ts - state.ts) <= 50000
    WHERE samples.wait_kind IS NOT NULL
        AND (state.state = 'S' OR state.state GLOB 'D*')
        AND state.dur >= 1000000
    GROUP BY state.id, samples.wait_kind
), drm_waits AS MATERIALIZED (
    SELECT sleeps.*,
           (SELECT state.waker_utid FROM thread_state AS state
            WHERE state.utid = sleeps.utid AND state.ts = sleeps.ts + sleeps.dur
            LIMIT 1) AS waker_utid
    FROM sleeps
), atomic_calls AS MATERIALIZED (
    SELECT s.ts, s.ts + s.dur AS end_ts, tt.utid
    FROM slice AS s
    JOIN thread_track AS tt ON tt.id = s.track_id
    WHERE s.name = 'atomic_commit' AND s.dur > 0
), annotated AS (
    SELECT *,
           CASE WHEN wait_kind = 'phone_nonblocking_commit_wait' THEN EXISTS (
                    SELECT 1 FROM thread
                    WHERE utid = drm_waits.waker_utid AND name = 'crtc_commit:288'
                )
                ELSE EXISTS (
                    SELECT 1 FROM atomic_calls AS phone
                    JOIN thread USING (utid)
                    WHERE phone.utid = drm_waits.waker_utid AND thread.name GLOB 'binder:*_1'
                        AND phone.ts < drm_waits.ts + drm_waits.dur
                        AND phone.end_ts > drm_waits.ts
                        AND ABS(phone.end_ts - (drm_waits.ts + drm_waits.dur)) <= 500000
                )
           END AS expected_waker
    FROM drm_waits
)
SELECT wait_kind, COUNT(*) AS waits_at_least_1ms,
       ROUND(AVG(dur) / 1e6, 3) AS mean_ms,
       ROUND(MAX(dur) / 1e6, 3) AS max_ms,
       SUM(expected_waker) AS expected_waker_count
FROM annotated
GROUP BY wait_kind
ORDER BY wait_kind;
