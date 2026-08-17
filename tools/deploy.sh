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
#   tools/deploy.sh [device]        # default: whatever is already connected, else mDNS discovery
#
# The wireless-debugging port changes on every reboot, so there is no address worth hardcoding.
# With no argument this uses the console already connected to adb, and failing that asks the
# network for one -- wireless debugging advertises itself over mDNS as _adb-tls-connect._tcp.

set -euo pipefail

find_device() {
  # Something already connected wins: it is the one the operator most likely meant.
  local connected
  connected="$(command adb devices | awk '$2 == "device" { print $1; exit }')"
  if [ -n "$connected" ]; then echo "$connected"; return 0; fi

  # Otherwise ask the network. adb's own resolver knows about paired consoles.
  local found
  found="$(command adb mdns services 2>/dev/null |
    awk '$2 ~ /_adb-tls-connect/ { print $3; exit }')"
  if [ -n "$found" ]; then echo "$found"; return 0; fi

  if command -v dns-sd >/dev/null 2>&1; then
    local name host port
    # Only the "Add" rows are results; the banner mentions the service type too.
    name="$(timeout 5 dns-sd -B _adb-tls-connect._tcp 2>/dev/null |
      awk '$2 == "Add" { print $NF; exit }')"
    if [ -n "$name" ]; then
      local line
      # "... can be reached at Android.local.:41517 (interface 14)" - the last
      # field is the interface, so take the host:port field by its shape.
      line="$(timeout 5 dns-sd -L "$name" _adb-tls-connect._tcp 2>/dev/null |
        awk '/can be reached at/ {
               for (i = 1; i <= NF; i++) if ($i ~ /:[0-9]+$/) { print $i; exit }
             }')"
      host="${line%%:*}"; port="${line##*:}"
      if [ -n "$port" ]; then
        # dns-sd reports the .local name; resolve it, because adb connect wants an address.
        local ip
        ip="$(dscacheutil -q host -a name "${host%.}" 2>/dev/null |
          awk '/^ip_address/ { print $2; exit }')"
        [ -n "$ip" ] && echo "$ip:$port" && return 0
        echo "${host%.}:$port" && return 0
      fi
    fi
  fi
  return 1
}

DEVICE="${1:-}"
if [ -z "$DEVICE" ]; then
  echo "==> looking for a console"
  if ! DEVICE="$(find_device)"; then
    echo "no console found. Enable wireless debugging on it, then either pass the" >&2
    echo "address explicitly or run: adb connect <ip>:<port>" >&2
    exit 1
  fi
  echo "    found $DEVICE"
fi
command adb connect "$DEVICE" >/dev/null 2>&1 || true
PKG="io.stride.spikes"
SERVICE="$PKG/$PKG.StrideAccessibilityService"
LISTENER="$PKG/$PKG.StrideNotificationListener"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

adb() { command adb -s "$DEVICE" "$@"; }

echo "==> building release apk"
if [ ! -f "$ROOT/apps/spikes/android/key.properties" ]; then
  # The build falls back to the debug key here, which is fine for your own console and fatal for
  # anyone else's: Android refuses an update signed by a different key, so a debug-signed build can
  # never be updated in place. The tester has to uninstall and loses profiles, pins, and goals.
  echo "WARNING: no key.properties, so this builds with the DEBUG key."
  echo "         Fine for your own console. Do not hand this build to anyone else -- it can never"
  echo "         self-update, and they lose their data when they have to uninstall."
  echo "         Run tools/keystore.sh unlock first. See docs/SIGNING.md."
fi
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
