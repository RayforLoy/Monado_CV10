-- The call and all of its ancestors must come from the SurfaceFlinger main thread.
-- ID 3 is the inner panel and ID 1 is the CV10 in these captures.
WITH RECURSIVE ancestors(hwc, name, parent_id, depth, call_duration) AS (
    SELECT s.name, s.name, s.parent_id, 0, s.dur
    FROM slice AS s
    JOIN thread_track AS track ON track.id = s.track_id
    JOIN thread USING (utid)
    JOIN process USING (upid)
    WHERE s.name GLOB 'HwcPresentOrValidateDisplay *' AND s.dur > 0
      AND thread.name = 'surfaceflinger' AND thread.tid = process.pid

    UNION ALL

    SELECT a.hwc, s.name, s.parent_id, a.depth + 1, a.call_duration
    FROM ancestors AS a
    JOIN slice AS s ON s.id = a.parent_id
)
SELECT hwc, depth,
       CASE WHEN name GLOB 'composite *' THEN 'SurfaceFlinger::composite'
            WHEN name GLOB 'present for *' THEN 'Output::present'
            WHEN name GLOB 'chooseCompositionStrategy for *' THEN 'Output::chooseCompositionStrategy'
            ELSE name END AS stage,
       COUNT(*) AS calls, ROUND(AVG(call_duration) / 1e6, 3) AS avg_hwc_ms
FROM ancestors
GROUP BY hwc, depth, stage
ORDER BY hwc, depth, calls DESC;
