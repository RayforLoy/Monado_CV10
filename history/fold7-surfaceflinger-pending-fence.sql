-- SM-F9660 folded, physical CV10 4621527156978103573 and phone 4630946872173396372.
-- These traces contain FrameTargeter's per-display counter plus the driver's CRTC events.
WITH phone_checks AS MATERIALIZED (
    SELECT sample.ts, sample.value AS pending,
           (SELECT trace.name FROM slice AS trace
            WHERE trace.parent_id = frame.id
              AND trace.name LIKE 'LastSignaledFrameTime %') AS signal_mark,
           (SELECT trace.ts FROM slice AS trace
            WHERE trace.parent_id = frame.id
              AND trace.name LIKE 'LastSignaledFrameTime %') AS signal_mark_ts
    FROM counter AS sample
    JOIN counter_track AS track ON track.id = sample.track_id
    JOIN slice AS frame ON frame.name LIKE 'beginFrame % vsyncIn%'
        AND sample.ts BETWEEN frame.ts AND frame.ts + frame.dur
    WHERE track.name = 'PrevFramePending 4630946872173396372'
), phone_retires AS MATERIALIZED (
    SELECT event.ts + event.dur AS ts
    FROM slice AS event
    JOIN thread_track AS track ON track.id = event.track_id
    JOIN thread USING (utid)
    WHERE thread.name = 'crtc_event:288'
      AND event.name = 'signal_retire_fence'
      AND event.dur >= 0
), phone_work AS MATERIALIZED (
    SELECT work.ts, work.ts + work.dur AS end_ts
    FROM slice AS work
    JOIN thread_track AS track ON track.id = work.track_id
    JOIN thread USING (utid)
    WHERE thread.name = 'crtc_commit:288'
      AND work.name = 'complete_commit'
      AND work.dur > 0
), signal_estimates AS MATERIALIZED (
    SELECT signal_mark_ts + CAST(ROUND(1000000.0 * CAST(
            SUBSTR(signal_mark, INSTR(signal_mark, 'signalTime ') + 11) AS DOUBLE)) AS INT)
           AS estimated_ts
    FROM phone_checks WHERE signal_mark IS NOT NULL
), signal_distances AS MATERIALIZED (
    SELECT (SELECT MIN(ABS(ts - estimated_ts)) FROM phone_retires
            WHERE ABS(ts - estimated_ts) < 5000000) AS nearest_retire_delta_ns
    FROM signal_estimates
)
SELECT COUNT(*) AS phone_checks,
       COUNT(*) FILTER (WHERE pending = 1) AS phone_pending,
       COUNT(*) FILTER (WHERE pending = 1 AND EXISTS(
           SELECT 1 FROM phone_work WHERE phone_work.ts <= phone_checks.ts
              AND phone_checks.ts < end_ts)) AS pending_during_crtc_work,
       (SELECT COUNT(*) FROM signal_estimates) AS sf_reported_signals,
       (SELECT COUNT(*) FROM signal_distances WHERE nearest_retire_delta_ns <= 500000)
           AS signals_within_500us_of_retire,
       (SELECT ROUND(AVG(nearest_retire_delta_ns) / 1000.0, 1) FROM signal_distances)
           AS mean_signal_distance_us
FROM phone_checks;
