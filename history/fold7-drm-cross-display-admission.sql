-- Fold7 Hello XR captures. Phone Composer uses binder:*_1 in these fixtures,
-- CV10 uses sdm_hpd_process and reports retire events on crtc_event:301.
WITH atomic_requests AS MATERIALIZED (
    SELECT request.id, request.ts AS request_ts, track.utid, thread.name AS worker,
           atomic.ts AS atomic_ts, atomic.ts + atomic.dur AS atomic_end
    FROM slice AS request
    JOIN slice AS atomic ON atomic.parent_id = request.id
    JOIN thread_track AS track ON track.id = request.track_id
    JOIN thread USING (utid)
    WHERE request.name = 'DRMAtomicReq::Commit::' AND request.dur > 0
        AND atomic.name = 'atomic_commit' AND atomic.dur > 0
), external_waits AS MATERIALIZED (
    SELECT external.*,
           (SELECT SUM(MIN(state.ts + state.dur, external.atomic_ts) -
                       MAX(state.ts, external.request_ts))
            FROM thread_state AS state
            WHERE state.utid = external.utid AND state.dur > 0
                AND state.state IN ('S', 'D')
                AND state.ts < external.atomic_ts
                AND state.ts + state.dur > external.request_ts) AS asleep_ns
    FROM atomic_requests AS external
    WHERE external.worker = 'sdm_hpd_process'
), candidates AS MATERIALIZED (
    SELECT external.id, phone.utid AS phone_utid, phone.atomic_end AS phone_end,
           MIN(phone.atomic_end, external.atomic_ts) -
               MAX(phone.atomic_ts, external.request_ts) AS overlap_ns
    FROM external_waits AS external
    JOIN atomic_requests AS phone
        ON phone.worker GLOB 'binder:*_1'
        AND phone.atomic_ts < external.atomic_ts
        AND phone.atomic_end > external.request_ts
), best_overlap AS MATERIALIZED (
    SELECT candidates.*, ROW_NUMBER() OVER (
        PARTITION BY id ORDER BY overlap_ns DESC, phone_end DESC
    ) AS priority
    FROM candidates
), matched AS MATERIALIZED (
    SELECT external.*, phone.phone_end, phone.overlap_ns,
           (SELECT MAX(state.ts) FROM thread_state AS state
            WHERE state.utid = external.utid AND state.waker_utid = phone.phone_utid
                AND state.ts BETWEEN external.request_ts AND external.atomic_ts
                AND ABS(state.ts - phone.phone_end) <= 500000) AS phone_wake_ts
    FROM external_waits AS external
    LEFT JOIN best_overlap AS phone ON phone.id = external.id AND phone.priority = 1
), retires AS (
    SELECT DISTINCT retire.ts + retire.dur AS at
    FROM slice AS retire
    JOIN thread_track AS track ON track.id = retire.track_id
    JOIN thread USING (utid)
    WHERE thread.name = 'crtc_event:301' AND retire.name = 'signal_retire_fence' AND retire.dur >= 0
)
SELECT COUNT(*) AS cv10_calls,
       COUNT(*) FILTER (WHERE atomic_ts - request_ts >= 1000000) AS cv10_calls_delayed_1ms,
       ROUND(AVG(atomic_ts - request_ts) / 1e6, 3) AS mean_before_cv10_atomic_ms,
       ROUND(AVG(COALESCE(asleep_ns, 0)) / 1e6, 3) AS mean_cv10_asleep_before_atomic_ms,
       ROUND(AVG(atomic_end - atomic_ts) / 1e6, 3) AS mean_inside_cv10_atomic_ms,
       COUNT(*) FILTER (WHERE overlap_ns >= 1000000) AS phone_overlap_1ms,
       COUNT(*) FILTER (WHERE overlap_ns >= 1000000
                        AND atomic_ts - phone_end BETWEEN 0 AND 500000)
           AS cv10_enters_within_500us_after_phone_atomic,
       COUNT(*) FILTER (WHERE overlap_ns >= 1000000 AND phone_wake_ts IS NOT NULL)
           AS phone_wakes_cv10_within_500us_of_phone_atomic_end,
       (SELECT COUNT(*) FROM retires) AS cv10_retire_events,
       (SELECT ROUND((COUNT(*) - 1) * 1e9 / (MAX(at) - MIN(at)), 2) FROM retires)
           AS cv10_retire_events_per_second
FROM matched;
