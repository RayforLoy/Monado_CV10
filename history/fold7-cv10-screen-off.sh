#!/system/bin/sh
# SPDX-License-Identifier: BSL-1.0
# Start from an authorised ADB shell after the XR application is presenting.
set -eu

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <seconds, 5 to 600>" >&2
    exit 2
fi
case "$1" in
    ''|*[!0-9]*|0*|????*) exit 2 ;;
esac
if [ "$1" -lt 5 ] || [ "$1" -gt 600 ]; then
    exit 2
fi
duration=$1

if [ "$(id -u)" != 2000 ] || [ "$(getprop ro.product.model)" != SM-F9660 ]; then
    echo 'This helper requires an authorised ADB shell on the Fold7 SM-F9660.' >&2
    exit 1
fi

glasses_id() {
    dumpsys -t 1 SurfaceFlinger --display-id |
        sed -n 's/^Display \([0-9][0-9]*\).*displayName="HUAWEIAV03"$/\1/p'
}

scheduler() {
    dumpsys -t 1 SurfaceFlinger --scheduler
}

clock_field() {
    sed -n "s/^[[:space:]]*$1=//p"
}

remaining_charge() {
    dumpsys -t 1 battery | sed -n 's/^[[:space:]]*level: \([0-9][0-9]*\)$/\1/p'
}

uptime_seconds() {
    read -r seconds idle < /proc/uptime
    echo "${seconds%%.*}"
}

glasses=$(glasses_id)
case "$glasses" in
    ''|*[!0-9]*) echo 'Could not identify exactly one connected CV10.' >&2; exit 1 ;;
esac
before=$(scheduler)
previous=$(echo "$before" | clock_field pacesetterDisplayId)
charge=$(remaining_charge)
if [ -z "$previous" ] || [ "$(echo "$before" | clock_field forcedPacesetterDisplayId)" != nullopt ] ||
    [ "${charge:-0}" -lt 20 ]; then
    echo 'The Fold7 display clock is already managed or the battery is below 20%.' >&2
    exit 1
fi

panel_off=0
clock_owned=0
restore() {
    if [ "$panel_off" -eq 1 ]; then
        cmd display power-reset 0 || echo 'Panel reset failed.' >&2
    fi
    if [ "$clock_owned" -eq 1 ] && [ "$(scheduler | clock_field forcedPacesetterDisplayId)" = "$glasses" ]; then
        if sfdo reset-forced-pacesetter; then
            sleep 0.3
            after=$(scheduler)
            if [ "$(echo "$after" | clock_field forcedPacesetterDisplayId)" = nullopt ] &&
                [ "$(echo "$after" | clock_field pacesetterDisplayId)" = "$glasses" ] &&
                [ "$previous" != "$glasses" ] && sfdo force-pacesetter "$previous"; then
                sfdo reset-forced-pacesetter
            fi
        else
            echo 'Clock reset failed.' >&2
        fi
    fi
    echo "restored forced clock: $(scheduler | clock_field forcedPacesetterDisplayId)"
}
trap restore EXIT
trap 'exit 0' INT TERM

clock_owned=1
sfdo force-pacesetter "$glasses"
panel_off=1
cmd display power-off 0
echo "active pid=$$ CV10=$glasses duration=${duration}s"

deadline=$(($(uptime_seconds) + duration))
while [ "$(uptime_seconds)" -lt "$deadline" ]; do
    sleep 1
    charge=$(remaining_charge)
    if [ "$(glasses_id)" != "$glasses" ] || [ "${charge:-0}" -lt 20 ]; then
        break
    fi
done
