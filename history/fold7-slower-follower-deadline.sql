-- At the stock scheduler's begin-frame check, is the outstanding phone retire before the
-- displayed CV10 deadline? Both entries come from the same VSYNC ID. The targeter prints the
-- adjusted deadline for late frames, so it is an upper bound on the scheduler's original deadline.
WITH checks AS MATERIALIZED (
    SELECT frame.id, sample.ts, track.name, sample.value AS pending,
           CAST(SUBSTR(frame.name, LENGTH('beginFrame ') + 1,
               INSTR(frame.name, ' vsyncIn ') - LENGTH('beginFrame ') - 1) AS INT) AS vsync_id,
           frame.ts + CAST(ROUND(1000000.0 * CAST(
               SUBSTR(frame.name, INSTR(frame.name, ' vsyncIn ') + LENGTH(' vsyncIn ')) AS DOUBLE))
               AS INT) AS deadline,
           frame.name LIKE '%(adjusted)' AS adjusted
    FROM counter AS sample
    JOIN counter_track AS track ON track.id = sample.track_id
    JOIN slice AS frame ON frame.name LIKE 'beginFrame % vsyncIn%'
        AND sample.ts BETWEEN frame.ts AND frame.ts + frame.dur
    WHERE track.name IN ('PrevFramePending 4630946872173396372', 'PrevFramePending 4621527156978103573')
), retires AS MATERIALIZED (
    SELECT event.ts + event.dur AS ts
    FROM slice AS event
    JOIN thread_track AS track ON track.id = event.track_id
    JOIN thread USING (utid)
    WHERE thread.name = 'crtc_event:288' AND event.name = 'signal_retire_fence' AND event.dur >= 0
), pairs AS MATERIALIZED (
    SELECT phone.vsync_id, cv10.adjusted, cv10.deadline,
           (SELECT MIN(retire.ts) FROM retires AS retire WHERE retire.ts >= phone.ts
                AND retire.ts < phone.ts + 20000000) AS next_retire
    FROM checks AS phone
    JOIN checks AS cv10 USING (vsync_id)
    WHERE phone.name = 'PrevFramePending 4630946872173396372' AND phone.pending = 1
      AND cv10.name = 'PrevFramePending 4621527156978103573'
)
SELECT COUNT(*) AS pending_checks, COUNT(next_retire) AS paired, SUM(adjusted) AS adjusted,
       COUNT(*) FILTER (WHERE next_retire > deadline + 500000) AS retire_later_than_cv10,
       COUNT(*) FILTER (WHERE next_retire < deadline - 500000) AS retire_earlier_than_cv10,
       ROUND(AVG(next_retire - deadline) / 1000000.0, 3) AS mean_retire_after_cv10_ms
FROM pairs;
