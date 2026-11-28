SELECT s.name AS stage, COUNT(*) AS samples,
       ROUND(AVG(s.dur) / 1e6, 3) AS avg_ms,
       ROUND(MAX(s.dur) / 1e6, 3) AS max_ms
FROM slice AS s
JOIN thread_track AS track ON track.id = s.track_id
JOIN thread USING (utid)
JOIN process USING (upid)
WHERE s.dur > 0 AND thread.name = 'surfaceflinger' AND thread.tid = process.pid
  AND (s.name = 'Waiting on HWC'
       OR s.name GLOB 'HwcPresentOrValidateDisplay *'
       OR s.name GLOB 'HwcValidateDisplay *'
       OR s.name GLOB 'HwcPresentDisplay *')
GROUP BY s.name
ORDER BY s.name;
