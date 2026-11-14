#!/bin/bash
# Takes the README screenshots on a running emulator (phone or tv), steered through the debug-only
# "demo" intent extras of MainActivity. Usage: android.sh <phone|tv> <out-dir> <apk>
set -u
DEV=$1; OUT=$2; APK=$3
mkdir -p "$OUT"
PKG=cz.ondra.absplayer
go() { adb shell am start -n $PKG/.ui.MainActivity "$@" >/dev/null; }
shot() { sleep "${2:-4}"; adb exec-out screencap -p > "$OUT/$DEV-$1.png"; echo "shot $DEV-$1"; }

adb install -r -g "$APK"
# clean status bar
adb shell settings put global sysui_demo_allowed 1
for c in "enter" "clock -e hhmm 0941" "battery -e level 100 -e plugged false" \
         "network -e wifi show -e level 4 -e mobile hide" "notifications -e visible false"; do
  adb shell am broadcast -a com.android.systemui.demo -e command $c >/dev/null
done

# a friendly device name for "Play on"
if [ "$DEV" = tv ]; then NAME="Living Room TV"; else NAME="Pixel 8"; fi
adb shell settings put global device_name "'$NAME'"

go --es demo lang --es code en; sleep 6
go --es demo login --es server http://10.0.2.2:13378 --es user demo --es pass demo
sleep 15
go --es demo home --ei tab 0;            shot 1-continue 12
go --es demo book --es title Alice;      shot 2-book 6
go --es demo play --es title Alice;      sleep 6
go --es demo player;                     shot 3-player 5
go --es demo devices;                    shot 4-play-on 4
go --es demo home --ei tab 2;            shot 5-library 14
go --es demo home --ei tab 1;            shot 6-added 10
go --es demo reader --es title Alice --es mode fromAudio; shot 7-reader 15
go --es demo pause
adb logcat -d -t 400 > "$OUT/$DEV-logcat.txt" 2>/dev/null || true
ls -la "$OUT"
