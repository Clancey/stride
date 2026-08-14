#!/usr/bin/env bash
# Build, install and re-assert every grant Android drops on reinstall.
#
# `adb install -r` clears enabled_accessibility_services. Back and Recents then stop working with
# no error and nothing on screen to explain it, on a console with no physical buttons. That has
# bitten this project repeatedly, so deploying and re-granting are one command.
#
# The grant that matters most is WRITE_SECURE_SETTINGS: it cannot be granted by any dialog, only
# from here, and once held Stride puts the other two back by itself (StridePermissions.repair).
#
#   tools/deploy.sh [device]        # default: 192.168.10.51:45557
#
# The wireless-debugging port changes on reboot. `adb devices` will tell you the new one.

set -euo pipefail

DEVICE="${1:-192.168.10.51:45557}"
PKG="io.stride.spikes"
SERVICE="$PKG/$PKG.StrideAccessibilityService"
LISTENER="$PKG/$PKG.StrideNotificationListener"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

adb() { command adb -s "$DEVICE" "$@"; }

echo "==> building release apk"
(cd "$ROOT/apps/spikes" && flutter build apk --release)

echo "==> installing to $DEVICE"
if ! adb install -r "$ROOT/apps/spikes/build/app/outputs/flutter-apk/app-release.apk"; then
  # Almost always a debug build already on the console: debug and release are signed with
  # different keys, and Android refuses to update across them. Worth catching explicitly, because
  # `set -e` would otherwise abort here having installed nothing and restored no grants, leaving a
  # console with stale permissions and no obvious reason why.
  echo
  echo "    install failed. If it mentions signatures, a differently-signed build is installed:"
  echo "      adb -s $DEVICE uninstall $PKG"
  echo "    That wipes app data, which is fine — credentials ship in the APK."
  exit 1
fi

echo "==> restoring grants"
# Development-tier, so it survives reinstalls and lets the app self-heal from here on.
adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS || {
  echo "    WRITE_SECURE_SETTINGS refused - self-repair is off, the setup card will ask instead"
}
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
adb shell settings put secure enabled_accessibility_services "$SERVICE"
adb shell settings put secure accessibility_enabled 1
adb shell cmd notification allow_listener "$LISTENER" || true

echo "==> verifying"
printf '    accessibility : %s\n' "$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
printf '    home          : %s\n' "$(adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | grep -m1 packageName | tr -d '\r')"

echo "==> launching"
adb shell am start -n "$PKG/.MainActivity" >/dev/null
echo "done"
