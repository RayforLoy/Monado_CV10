-- SurfaceFlinger HWC call order and the start of the matching phone atomic commit.
-- Fold7 capture IDs: HWC 1 / sdm_hpd_process (CV10), HWC 3 / binder:*_1 (cover).
WITH batches AS MATERIALIZED (
    SELECT s.id, s.track_id, s.ts, s.ts + s.dur AS finish,
           LAG(s.ts + s.dur) OVER (PARTITION BY s.track_id ORDER BY s.ts) AS previous_finish
    FROM slice AS s
    JOIN thread_track AS tr ON tr.id = s.track_id
    JOIN thread AS t USING (utid)
    JOIN process AS p USING (upid)
    WHERE s.name GLOB 'composite [0-9]*' AND s.depth = 0 AND s.dur > 0
      AND t.name = 'surfaceflinger' AND t.tid = p.pid
), calls AS MATERIALIZED (
    SELECT track_id, ts, ts + dur AS finish, name
    FROM slice
    WHERE name IN ('HwcPresentOrValidateDisplay 1', 'HwcPresentOrValidateDisplay 3') AND dur > 0
), paired AS MATERIALIZED (
    SELECT batches.*,
           COUNT(*) FILTER (WHERE calls.name = 'HwcPresentOrValidateDisplay 1') AS cv10_calls,
           COUNT(*) FILTER (WHERE calls.name = 'HwcPresentOrValidateDisplay 3') AS phone_calls,
           MIN(calls.ts) FILTER (WHERE calls.name = 'HwcPresentOrValidateDisplay 1') AS cv10_hwc,
           MIN(calls.ts) FILTER (WHERE calls.name = 'HwcPresentOrValidateDisplay 3') AS phone_hwc,
           MAX(calls.finish) FILTER (WHERE calls.name = 'HwcPresentOrValidateDisplay 3')
               AS phone_hwc_finish
    FROM batches
    LEFT JOIN calls ON calls.track_id = batches.track_id
        AND calls.ts >= batches.ts AND calls.ts < batches.finish
    GROUP BY batches.id
), with_previous_hwc AS MATERIALIZED (
    SELECT paired.*,
           LAG(CASE WHEN phone_calls = 1 THEN phone_hwc END)
               OVER (PARTITION BY track_id ORDER BY ts) AS previous_phone_hwc,
           LAG(CASE WHEN phone_calls = 1 THEN phone_hwc_finish END)
               OVER (PARTITION BY track_id ORDER BY ts) AS previous_phone_hwc_finish
    FROM paired
), atomic_requests AS MATERIALIZED (
    SELECT request.id, request.ts AS request_ts, thread.name AS worker,
           atomic.ts AS entry, atomic.ts + atomic.dur AS finish
    FROM slice AS request
    JOIN slice AS atomic ON atomic.parent_id = request.id
    JOIN thread_track AS track ON track.id = request.track_id
    JOIN thread USING (utid)
    WHERE request.name = 'DRMAtomicReq::Commit::' AND request.dur > 0
        AND atomic.name = 'atomic_commit' AND atomic.dur > 0
        AND (thread.name = 'sdm_hpd_process' OR thread.name GLOB 'binder:*_1')
), external_in_batch AS MATERIALIZED (
    SELECT paired.id AS batch_id,
           MIN(external.request_ts) AS request_ts, MIN(external.entry) AS entry,
           COUNT(external.id) AS cv10_requests
    FROM paired
    LEFT JOIN atomic_requests AS external ON external.worker = 'sdm_hpd_process'
        AND external.request_ts >= paired.ts AND external.request_ts < paired.finish
    GROUP BY paired.id
), matching_blocker AS (
    SELECT external.batch_id, MIN(phone.request_ts) AS phone_request_ts,
           MIN(phone.entry) AS phone_entry, MIN(phone.finish) AS phone_finish,
           COUNT(phone.id) AS matches
    FROM external_in_batch AS external
    JOIN atomic_requests AS phone ON phone.worker GLOB 'binder:*_1'
        AND MIN(phone.finish, external.entry) - MAX(phone.entry, external.request_ts) >= 1000000
        AND external.entry - phone.finish BETWEEN 0 AND 500000
    WHERE external.cv10_requests = 1
    GROUP BY external.batch_id
), timing AS (
    SELECT paired.*, external.cv10_requests, blocker.matches, blocker.phone_request_ts,
           CASE WHEN blocker.matches = 1 AND blocker.phone_entry < paired.ts
                THEN blocker.phone_finish - paired.ts END AS phone_atomic_remaining_ns,
           CASE WHEN blocker.matches = 1
                     AND blocker.phone_request_ts >= paired.previous_phone_hwc
                     AND blocker.phone_request_ts <= paired.previous_phone_hwc_finish
                THEN blocker.phone_finish - paired.previous_phone_hwc_finish END
               AS phone_atomic_after_previous_hwc_ns
    FROM with_previous_hwc AS paired
    JOIN external_in_batch AS external ON external.batch_id = paired.id
    LEFT JOIN matching_blocker AS blocker ON blocker.batch_id = paired.id
)
SELECT
    COUNT(*) AS batches,
    COUNT(*) FILTER (WHERE cv10_calls = 1 AND phone_calls = 1) AS paired_hwc,
    COUNT(*) FILTER (WHERE cv10_calls = 1 AND phone_calls = 1 AND cv10_hwc < phone_hwc)
        AS cv10_hwc_first,
    COUNT(*) FILTER (WHERE cv10_calls = 1 AND phone_calls = 1 AND phone_hwc < cv10_hwc)
        AS phone_hwc_first,
    COUNT(*) FILTER (WHERE cv10_requests = 1) AS single_cv10_atomic_in_batch,
    COUNT(*) FILTER (WHERE matches = 1) AS single_phone_blocker,
    COUNT(*) FILTER (WHERE matches = 1 AND phone_request_ts < ts)
        AS phone_already_in_flight_at_batch_start,
    COUNT(*) FILTER (WHERE matches = 1 AND phone_request_ts < cv10_hwc)
        AS phone_already_in_flight_at_cv10_hwc,
    COUNT(*) FILTER (WHERE matches = 1 AND phone_request_ts < previous_finish)
        AS phone_started_before_previous_batch_finished,
    COUNT(*) FILTER (WHERE matches = 1 AND phone_request_ts >= previous_finish
                         AND phone_request_ts < ts)
        AS phone_started_between_batches,
    COUNT(*) FILTER (WHERE matches = 1 AND phone_request_ts >= ts)
        AS phone_started_during_batch,
    COUNT(*) FILTER (WHERE phone_atomic_remaining_ns > 0) AS atomic_crosses_batch_start,
    ROUND(PERCENTILE(phone_atomic_remaining_ns, 50) / 1e6, 3)
        AS phone_atomic_remaining_p50_ms,
    ROUND(PERCENTILE(phone_atomic_remaining_ns, 95) / 1e6, 3)
        AS phone_atomic_remaining_p95_ms,
    COUNT(phone_atomic_after_previous_hwc_ns) AS phone_request_during_previous_hwc,
    COUNT(*) FILTER (WHERE phone_atomic_after_previous_hwc_ns > 0)
        AS phone_atomic_outlived_previous_hwc,
    ROUND(PERCENTILE(phone_atomic_after_previous_hwc_ns, 95) / 1e6, 3)
        AS phone_atomic_after_previous_hwc_p95_ms
FROM timing;
