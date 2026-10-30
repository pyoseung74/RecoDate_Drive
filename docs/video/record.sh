#!/usr/bin/env bash
# Records demo scenes from the Pleos emulator (1920x1080). Coordinates are device pixels (2560x1440).
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1
OUT=/c/Android/video/raw
mkdir -p "$OUT"
tap() { "$ADB" shell input tap "$1" "$2"; }
back() { "$ADB" shell input keyevent 4; }

# rec NAME SECONDS -- then run the actions given as a function name
rec() {
  local name=$1 secs=$2 fn=$3
  "$ADB" shell screenrecord --size 1920x1080 --bit-rate 10000000 --time-limit "$secs" "/sdcard/$name.mp4" &
  local pid=$!
  sleep 1
  $fn
  wait $pid
  sleep 1
  "$ADB" pull "/sdcard/$name.mp4" "C:/Android/video/raw/$name.mp4" >/dev/null 2>&1
  "$ADB" exec-out screencap -p > "$OUT/$name.end.png"
  echo "recorded $name ($(stat -c %s "$OUT/$name.mp4") bytes)"
}

s_home() { sleep 2; tap 1352 368; sleep 3; }
s_recommend() { sleep 1; tap 2161 1211; sleep 13; }
s_drive() { sleep 1; tap 986 260; sleep 2; tap 2323 348; sleep 2; tap 2161 1211; sleep 12; }
s_rain() { sleep 1; tap 986 260; sleep 2; tap 2442 157; sleep 2; tap 1987 612; sleep 2; back; sleep 2; tap 2161 1211; sleep 10; }
s_start() { sleep 2; tap 1160 1179; sleep 15; }
s_navi() { "$ADB" shell monkey -p ai.umos.maps.android.navigation.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 9; }
s_arrive() { "$ADB" shell am start -n com.recodate.drive/.MainActivity >/dev/null 2>&1; sleep 3; tap 2442 157; sleep 2; tap 1692 777; sleep 9; }

case "$1" in
  home) rec s1_home 7 s_home ;;
  recommend) rec s2_recommend 15 s_recommend ;;
  drive) rec s3_drive 18 s_drive ;;
  rain) rec s4_rain 20 s_rain ;;
  start) rec s5_start 18 s_start ;;
  navi) rec s6_navi 10 s_navi ;;
  arrive) rec s7_arrive 15 s_arrive ;;
esac
