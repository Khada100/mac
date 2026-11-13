#!/usr/bin/env bash
# 에뮬레이터에서 앱을 실제로 실행해 보는 테스트
#  1) 설치 후 실행 → 죽지 않는지, OpenCV 로드되는지
#  2) 접근성 서비스 연결
#  3) 화면 캡처 시작 (권한 창 자동 수락)
#  4) 홈 화면 아이콘 A 를 '회피 옵션', 아이콘 B 를 '새로고침 버튼'으로 등록 후 매크로 시작
#     → 매크로가 A 를 발견하고 B 를 실제로 터치해서 B 앱이 열리는지 확인
set -u
PKG=com.imagemacro
APK=apk/app-x86_64-debug.apk
OUT=e2e-out
UI=".github/scripts/ui.py"
mkdir -p "$OUT"
FAIL=0

step() { echo; echo "================ $* ================"; }
pass() { echo "✅ PASS: $*"; }
fail() { echo "❌ FAIL: $*"; FAIL=1; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; python3 "$UI" shot "$OUT/$1.png" "$1"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null 2>&1; }
applog() { adb logcat -d -s ImageMacro:I; }
wait_log() { # 패턴, 최대 초
  for _ in $(seq 1 "$2"); do
    if applog | grep -q -- "$1"; then return 0; fi
    sleep 1
  done
  return 1
}
tap_text() {
  dump
  local xy
  xy=$(python3 "$UI" find "$OUT/ui.xml" "$1" "$2") || return 1
  echo "tap '$2' at $xy"
  adb shell input tap $xy
}
push_file() { # 로컬파일, 앱 내부 경로
  adb push "$1" /data/local/tmp/e2e_tmp.png > /dev/null
  adb shell "run-as $PKG sh -c 'cat > $2' < /data/local/tmp/e2e_tmp.png"
}

adb logcat -c

step "1. 설치 / 실행"
adb install -r -g "$APK" || { fail "설치 실패"; exit 1; }
adb shell settings put secure enabled_accessibility_services "$PKG/$PKG.MacroAccessibilityService"
adb shell settings put secure accessibility_enabled 1
adb shell am start -n "$PKG/.MainActivity"
sleep 6
if adb shell pidof "$PKG" > /dev/null; then pass "앱 실행 중"; else fail "앱이 실행되지 않음"; fi
if wait_log "OpenCV 로드 완료" 5; then pass "OpenCV 로드"; else fail "OpenCV 로드 실패"; fi
if wait_log "서비스 연결됨" 10; then pass "접근성 서비스 연결"; else fail "접근성 서비스 연결 안 됨"; fi
shot 1_main

step "2. 화면 캡처 시작"
# 접근성 상태가 화면에 반영되도록 다시 그리기
adb shell am start -n "$PKG/.MainActivity"
sleep 2
tap_text text "화면 캡처 시작" || fail "'화면 캡처 시작' 버튼 없음"
sleep 3
shot 2_consent
dump
if python3 "$UI" find "$OUT/ui.xml" resource-id "android:id/button1" > /dev/null; then
  tap_text resource-id "android:id/button1"
else
  fail "화면 캡처 권한 창 버튼을 못 찾음"
fi
if wait_log "화면 캡처] 시작" 10; then pass "화면 캡처 시작"; else fail "화면 캡처 시작 안 됨"; fi
sleep 2
shot 3_after_capture

step "3. 홈 화면 아이콘으로 템플릿 만들기"
adb shell input keyevent KEYCODE_HOME
sleep 4
adb exec-out screencap -p > "$OUT/home.png"
dump
cp "$OUT/ui.xml" "$OUT/home_ui.xml"
if python3 "$UI" icons "$OUT/home_ui.xml" > "$OUT/icons.txt"; then
  cat "$OUT/icons.txt"
  read -r ax1 ay1 ax2 ay2 aname < <(sed -n 1p "$OUT/icons.txt")
  read -r bx1 by1 bx2 by2 bname < <(sed -n 2p "$OUT/icons.txt")
  python3 "$UI" crop "$OUT/home.png" "$ax1" "$ay1" "$ax2" "$ay2" "$OUT/opt.png"
  python3 "$UI" crop "$OUT/home.png" "$bx1" "$by1" "$bx2" "$by2" "$OUT/reload.png"
  python3 "$UI" noise "$OUT/start.png" 1
  python3 "$UI" noise "$OUT/collect1.png" 2
  python3 "$UI" noise "$OUT/collect2.png" 3
  adb shell run-as "$PKG" mkdir -p files/templates files/buttons
  push_file "$OUT/opt.png" "files/templates/test_icon.png"
  for b in reload start collect1 collect2; do push_file "$OUT/$b.png" "files/buttons/$b.png"; done
  adb shell run-as "$PKG" ls -l files/templates files/buttons
  pass "템플릿 등록: 옵션=$aname, 새로고침=$bname"
else
  fail "홈 화면 아이콘을 2개 찾지 못함"
  cat "$OUT/home_ui.xml" | head -c 3000
fi
shot 4_home

step "4. 매크로 실행 → 아이콘 A 발견 시 아이콘 B(새로고침) 터치"
adb shell am start -n "$PKG/.MainActivity"
sleep 3
shot 5_main_registered
tap_text text "매크로 시작" || fail "'매크로 시작' 버튼 없음"
sleep 1
adb shell input keyevent KEYCODE_HOME
if wait_log "템플릿 발견] test_icon" 20; then pass "회피 옵션(아이콘 A) 인식"; else fail "회피 옵션 인식 못 함"; fi
if wait_log "RELOAD 클릭" 10; then pass "새로고침(아이콘 B) 터치"; else fail "새로고침 터치 안 함"; fi
sleep 4
TOP=$(adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity")
echo "현재 화면: $TOP"
if echo "$TOP" | grep -q launcher; then fail "터치 후에도 홈 화면 그대로 (터치가 실제로 안 먹음)"; else pass "아이콘 B 앱이 열림 (실제 터치 확인)"; fi
shot 6_after_tap

echo "---- 앱 로그 (세로 테스트) ----"
applog | tail -40
adb logcat -c

step "5. 회전 테스트: 세로에서 캡처 시작 → 가로 화면에서 매크로 (실제 게임과 같은 상황)"
adb shell am broadcast -n "$PKG/.DebugCommandReceiver" -a com.imagemacro.action.DEBUG_STOP > /dev/null
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
adb shell am start -W -a android.settings.SETTINGS > /dev/null
sleep 6
adb exec-out screencap -p > "$OUT/land.png"
read -r LW LH < <(python3 "$UI" size "$OUT/land.png")
echo "화면 크기: ${LW}x${LH}"
if [ "$LW" -gt "$LH" ]; then pass "가로 화면으로 회전"; else fail "가로로 회전되지 않음"; fi
if wait_log "화면 크기 변경 → ${LW}×${LH}" 10; then pass "캡처 크기도 가로로 조정"; else fail "캡처 크기 조정 로그 없음"; fi
dump
cp "$OUT/ui.xml" "$OUT/land_ui.xml"
if python3 "$UI" titles "$OUT/land_ui.xml" > "$OUT/titles.txt"; then
  cat "$OUT/titles.txt"
  read -r ax1 ay1 ax2 ay2 aname < <(sed -n 1p "$OUT/titles.txt")
  read -r bx1 by1 bx2 by2 bname < <(sed -n 2p "$OUT/titles.txt")
  python3 "$UI" crop "$OUT/land.png" "$ax1" "$ay1" "$ax2" "$ay2" "$OUT/opt2.png"
  python3 "$UI" crop "$OUT/land.png" "$bx1" "$by1" "$bx2" "$by2" "$OUT/reload2.png"
  adb shell run-as "$PKG" rm -f files/templates/test_icon.png
  push_file "$OUT/opt2.png" "files/templates/test_setting.png"
  push_file "$OUT/reload2.png" "files/buttons/reload.png"
  pass "가로 화면 템플릿 등록: 옵션=$aname, 새로고침=$bname"
else
  fail "설정 화면 항목을 2개 찾지 못함"
  head -c 3000 "$OUT/land_ui.xml"
fi
shot 7_landscape
sleep 3  # uiautomator 덤프 때문에 잠깐 꺼졌던 접근성 서비스가 다시 붙을 시간
adb shell am broadcast -n "$PKG/.DebugCommandReceiver" -a com.imagemacro.action.DEBUG_START > /dev/null
if wait_log "\[진단\]" 10; then pass "진단 로그 출력"; else fail "진단 로그 없음"; fi
if wait_log "템플릿 발견] test_setting" 20; then pass "회전 후 회피 옵션 인식"; else fail "회전 후 회피 옵션 인식 못 함"; fi
if wait_log "RELOAD 클릭" 10; then pass "회전 후 새로고침 터치"; else fail "회전 후 새로고침 터치 안 함"; fi
sleep 4
adb exec-out screencap -p > "$OUT/land_after.png"
D=$(python3 "$UI" diff "$OUT/land.png" "$OUT/land_after.png")
echo "터치 전후 화면 차이: $D"
if python3 -c "import sys; sys.exit(0 if float('$D') > 5 else 1)"; then pass "터치로 화면이 바뀜 (가로 좌표도 정확)"; else fail "터치 후 화면이 그대로"; fi
shot 8_landscape_after_tap
adb shell am broadcast -n "$PKG/.DebugCommandReceiver" -a com.imagemacro.action.DEBUG_STOP > /dev/null
adb shell settings put system user_rotation 0

step "6. 정리"
echo "---- 앱 로그 (회전 테스트) ----"
applog | tail -60
echo "---- 크래시 ----"
adb logcat -d -s AndroidRuntime:E | tail -40
if adb logcat -d -s AndroidRuntime:E | grep -q "Process: $PKG"; then fail "앱 크래시 발생"; fi

echo
if [ "$FAIL" = 0 ]; then echo "🎉 E2E 전체 통과"; else echo "⚠️ E2E 일부 실패"; fi
exit $FAIL
