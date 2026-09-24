#!/bin/bash
# Скриншоты и видео превью в симуляторе. $1 — версия iOS-рантайма (префикс, напр. 26 или 18), $2 — папка.
set -u
VER="$1"; OUT="$2"; mkdir -p "$OUT"
APP=$(find build/Build/Products -maxdepth 2 -name "Preview.app" | head -1)
RT=$(xcrun simctl list runtimes -j | python3 -c "
import json,sys
rs=[r for r in json.load(sys.stdin)['runtimes'] if r.get('isAvailable') and r['name'].startswith('iOS $VER')]
rs.sort(key=lambda r:[int(x) for x in r['version'].split('.')])
print(rs[-1]['identifier'] if rs else '')")
[ -z "$RT" ] && { echo "no runtime iOS $VER"; exit 0; }
DT=$(xcrun simctl list devicetypes -j | python3 -c "
import json,sys
ts=[t for t in json.load(sys.stdin)['devicetypes'] if t['name'].startswith('iPhone')]
pref=['iPhone 17 Pro','iPhone 16 Pro','iPhone 15 Pro']
for p in pref:
  for t in ts:
    if t['name']==p: print(t['identifier']); sys.exit()
print([t for t in ts if 'Pro' in t['name'] and 'Max' not in t['name']][-1]['identifier'])")
echo "runtime=$RT devicetype=$DT" | tee "$OUT/device.txt"
DEV=$(xcrun simctl create "reed-$VER" "$DT" "$RT")
xcrun simctl boot "$DEV"; xcrun simctl bootstatus "$DEV" -b >/dev/null
xcrun simctl status_bar "$DEV" override --time "9:41" --dataNetwork wifi --wifiMode active --wifiBars 3 \
  --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100 || true
xcrun simctl ui "$DEV" appearance dark || true
xcrun simctl install "$DEV" "$APP"
ID=ru.reedapp.preview
launch() { SIMCTL_CHILD_REED_SCENE="$1" xcrun simctl launch --terminate-running-process "$DEV" $ID -AppleLanguages "(ru)" -AppleLocale ru_RU >/dev/null; }
sleep 2
for s in ${SCENES:-login login_consent codes codes_family home_off home_connecting home_on home_cell home_nocode family family_member newdevice profile profile_netcheck services notifications blocked support_empty support_chat}; do
  launch "$s"; sleep 3.5
  xcrun simctl io "$DEV" screenshot "$OUT/$s.png" >/dev/null 2>&1
done
for a in ${ANIMS:-anim_connect anim_tabs anim_login anim_server anim_notif anim_support}; do
  xcrun simctl terminate "$DEV" $ID 2>/dev/null
  xcrun simctl io "$DEV" recordVideo --codec=h264 --force "$OUT/$a.mp4" >/dev/null 2>&1 & REC=$!
  sleep 1.5
  launch "$a"
  case $a in anim_server) sleep 16;; anim_support) sleep 13;; anim_login) sleep 9;; *) sleep 11;; esac
  kill -INT $REC; wait $REC 2>/dev/null
done
xcrun simctl shutdown "$DEV" || true
ls -la "$OUT"
