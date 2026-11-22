#!/bin/bash
# Runs the sample app's soak playlist on a device for hours and records how it holds up.
#
#   samples/soak/soak.sh [hours] [interval-seconds]
#
# Defaults: 24 hours, a sample every 300 seconds. Set ANDROID_SERIAL to pick the device (an
# ip:port is reconnected if it drops) and SOAK_EXTRAS for extra launch extras, for example
# SOAK_EXTRAS="--ef volume 1". Writes into soak-<date>/ in the current directory, then runs
# report.py on it. Keep the computer awake and on the same network as the device.

set -u
HOURS=${1:-24}
INTERVAL=${2:-300}
SERIAL=${ANDROID_SERIAL:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}
EXTRAS=${SOAK_EXTRAS:-"--ef volume 0"}
PKG=io.github.gapless.sample
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${SOAK_OUT:-soak-$(date +%Y%m%d-%H%M)}

[ -n "$SERIAL" ] || { echo "no device"; exit 1; }
mkdir -p "$OUT"
A="adb -s $SERIAL"
END=$(( $(date +%s) + $(awk "BEGIN{print int($HOURS * 3600)}") ))
log() { echo "$(date '+%F %T') $*" | tee -a "$OUT/events.txt"; }

reconnect() {
    case "$SERIAL" in *:*) adb connect "$SERIAL" >/dev/null 2>&1 ;; esac
}

launch() {
    # shellcheck disable=SC2086
    $A shell am start -S -n $PKG/.MainActivity --es playlist soak $EXTRAS >/dev/null 2>&1
}

# Keep the computer awake for as long as this script runs (macOS).
command -v caffeinate >/dev/null && caffeinate -i -w $$ &

log "soak start: $HOURS h, sample every ${INTERVAL}s, device $SERIAL, extras: $EXTRAS"
$A logcat -c
launch

# The player's logs, restarted whenever adb drops.
(
    while [ "$(date +%s)" -lt "$END" ]; do
        $A logcat -v time GaplessPlayer:V GaplessVideo:V AndroidRuntime:E ActivityManager:I \
            lowmemorykiller:V lmkd:V '*:S' >> "$OUT/logcat.txt" 2>/dev/null
        echo "--- logcat ended $(date '+%F %T')" >> "$OUT/logcat.txt"
        sleep 10
        reconnect
    done
) &
LOGCAT_LOOP=$!
trap 'kill $LOGCAT_LOOP 2>/dev/null; pkill -P $LOGCAT_LOOP 2>/dev/null' EXIT

echo "time,uptime_s,player_pid,pss_kb,java_kb,native_kb,graphics_kb,threads,fds,cpu_jiffies,memavail_kb,swapfree_kb,load1,hal_rss_kb,swcodec_rss_kb" \
    > "$OUT/samples.csv"

missing=0
while [ "$(date +%s)" -lt "$END" ]; do
    if ! $A shell true >/dev/null 2>&1; then
        reconnect
        echo "$(date +%s),,,,,,,,,,,,,," >> "$OUT/samples.csv"
        log "device unreachable"
        sleep "$INTERVAL"
        continue
    fi
    pid=$($A shell pidof $PKG:player 2>/dev/null | tr -d '\r')
    uptime=$($A shell cut -d. -f1 /proc/uptime | tr -d '\r')
    mem=$($A shell cat /proc/meminfo | tr -d '\r')
    memavail=$(echo "$mem" | awk '/MemAvailable/{print $2}')
    swapfree=$(echo "$mem" | awk '/SwapFree/{print $2}')
    load=$($A shell cat /proc/loadavg | awk '{print $1}')
    hal=$($A shell 'grep VmRSS /proc/$(pidof android.hardware.media.c2@1.1-mediatek)/status' 2>/dev/null | awk '{print $2}')
    sw=$($A shell 'grep VmRSS /proc/$(pidof media.swcodec)/status' 2>/dev/null | awk '{print $2}')
    if [ -n "$pid" ]; then
        missing=0
        mi=$($A shell dumpsys meminfo "$pid" | tr -d '\r')
        pss=$(echo "$mi" | awk '/TOTAL PSS:/{print $3; exit}')
        java=$(echo "$mi" | awk '/Java Heap:/{print $3; exit}')
        native=$(echo "$mi" | awk '/Native Heap:/{print $3; exit}')
        gfx=$(echo "$mi" | awk '/Graphics:/{print $2; exit}')
        threads=$($A shell "grep Threads /proc/$pid/status" | awk '{print $2}')
        fds=$($A shell "run-as $PKG ls /proc/$pid/fd" 2>/dev/null | wc -l | tr -d ' ')
        cpu=$($A shell "cat /proc/$pid/stat" | awk '{print $14+$15}')
    else
        pss= java= native= gfx= threads= fds= cpu=
        missing=$((missing + 1))
        if [ "$missing" -ge 2 ]; then
            log "player not running for two samples; relaunching"
            launch
            missing=0
        fi
    fi
    echo "$(date +%s),$uptime,$pid,$pss,$java,$native,$gfx,$threads,$fds,$cpu,$memavail,$swapfree,$load,$hal,$sw" \
        >> "$OUT/samples.csv"
    sleep "$INTERVAL"
done

log "soak end"
python3 "$HERE/report.py" "$OUT" | tee "$OUT/summary.md"
