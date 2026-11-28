-- Approximate whether the next SF wake was already requested at the end of each batch.
-- Stock trace slices do not expose the return value of getScheduledFrameResult().
WITH sf AS (
    SELECT s.ts, s.dur, s.name, s.depth, t.name AS thread, t.tid, p.pid
    FROM slice s
    JOIN thread_track tr ON s.track_id = tr.id
    JOIN thread t USING (utid)
    JOIN process p USING (upid)
    WHERE p.pid IN (SELECT tid FROM thread WHERE name = 'surfaceflinger') AND s.dur > 0
), begins AS (
    SELECT MIN(ts) AS ts, SUBSTR(name, 12, INSTR(name, ' vsyncIn') - 12) AS token
    FROM sf WHERE tid = pid AND depth = 0 AND name GLOB 'beginFrame * vsyncIn*'
    GROUP BY token
), batches AS (
    SELECT begins.ts AS start, sf.ts + sf.dur AS finish
    FROM sf JOIN begins ON begins.token = SUBSTR(sf.name, 11)
    WHERE tid = pid AND depth = 0 AND name GLOB 'composite *' AND begins.ts <= sf.ts
), callbacks AS (
    SELECT ts FROM sf WHERE name = 'vsyncCallback' AND thread = 'TimerDispatch'
), requests AS (
    SELECT ts + dur AS finish FROM sf WHERE name = 'scheduleFrame'
), by_wake AS (
    SELECT start, finish, (SELECT MAX(ts) FROM callbacks WHERE ts <= batches.start) AS current_wake
    FROM batches
), by_next_wake AS (
    SELECT *, (SELECT MIN(ts) FROM callbacks WHERE ts > by_wake.current_wake) AS next_wake
    FROM by_wake
), groups AS (
    SELECT CASE
        WHEN current_wake IS NULL OR next_wake IS NULL THEN 'capture edge'
        WHEN next_wake <= finish THEN 'timer already fired'
        WHEN NOT EXISTS(SELECT 1 FROM requests WHERE requests.finish > by_next_wake.current_wake
                        AND requests.finish <= by_next_wake.finish) THEN 'no scheduleFrame in interval'
        WHEN next_wake - finish < 3250000 THEN 'requested, future wake under 3.25 ms away'
        ELSE 'requested, future wake at least 3.25 ms away'
    END AS stage
    FROM by_next_wake
)
SELECT stage, COUNT(*) AS frames FROM groups GROUP BY stage ORDER BY stage;
