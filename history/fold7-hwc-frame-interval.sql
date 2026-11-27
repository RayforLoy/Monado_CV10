SELECT
    hwc.name AS display_call,
    hint.name AS frame_interval,
    COUNT(*) AS calls,
    ROUND(AVG(hwc.dur) / 1e6, 3) AS mean_ms
FROM slice AS hwc
LEFT JOIN slice AS hint
    ON hint.id = hwc.parent_id AND hint.name GLOB 'NextFrameInterval *'
WHERE hwc.name GLOB 'HwcPresentOrValidateDisplay *'
    AND hwc.dur > 0
GROUP BY hwc.name, hint.name
ORDER BY hwc.name, hint.name
