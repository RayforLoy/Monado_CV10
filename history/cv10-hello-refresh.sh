#!/system/bin/sh
# Start with hello_xr in the foreground on the Fold7. No root required.
set -eu
base=/data/local/tmp/cv10-hello-refresh
key=min_refresh_rate
rate=120.0
exec 9>"$base.lock"
flock -n 0 <&9 || { echo "A CV10 refresh session is already active."; exit 0; }

restore() {
    [ -f "$base.original" ] || return 0
    old=$(cat "$base.original")
    current=$(settings get system "$key")
    if [ "$current" = "$rate" ]; then
        if [ "$old" = null ]; then
            settings delete system "$key" >/dev/null
        else
            settings put system "$key" "$old"
        fi
    fi
    rm -f "$base.original"
}

active() {
    pidof org.cv10.monado.out_of_process >/dev/null &&
    dumpsys activity activities | grep 'topResumedActivity=.*org.khronos.openxr.hello_xr.opengles/' >/dev/null &&
    dumpsys SurfaceFlinger --display-id | grep HUAWEIAV03 >/dev/null
}

# Recover the previous setting if an earlier session lost power before cleanup.
restore
[ "${1:-}" != restore ] || exit 0
if ! active; then echo "Open hello_xr with the CV10 attached." >&2; exit 1; fi
original=$(settings get system "$key")
echo "$original" > "$base.tmp"
mv "$base.tmp" "$base.original"
trap restore EXIT
trap 'exit 0' HUP INT TERM
settings put system "$key" "$rate"
echo "CV10 session active."
while active; do sleep 3; done
