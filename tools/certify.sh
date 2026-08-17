#!/usr/bin/env bash
# Walk a console through Google's uncertified-device registration, from your computer.
#
#   tools/certify.sh [device]
#
# Play refuses to sign in on this console with "This device isn't Play Protect certified". That is
# expected: Google certifies a *build* when its manufacturer submits it, and nobody submitted a
# treadmill. The supported way out is to register the console's GSF device id against the Google
# account that will use it. See docs/APPSTORE.md section 11.6.
#
# Three of the four steps are mechanical and easy to get wrong by hand, so this does them:
#
#   1. reads the device id, which the shell cannot do on its own
#   2. opens the registration page (you sign in and paste — only you can do that part)
#   3. clears data on Play Services and the Play Store, which is the step people skip
#   4. reboots
#
# Requires Stride to be installed: the id lives behind READ_GSERVICES, which the shell user does
# not hold and the database behind it needs root, so the app answers on the shell's behalf.

set -euo pipefail

# shellcheck source=tools/console.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/console.sh"

PKG="io.stride.spikes"
URL="https://www.google.com/android/uncertified"
GMS="com.google.android.gms"
VENDING="com.android.vending"

DEVICE="$(require_console "${1:-}")"
adb() { command adb -s "$DEVICE" "$@"; }

if ! adb shell pm path "$PKG" >/dev/null 2>&1; then
  echo "Stride is not installed on $DEVICE, and it is what reads the id." >&2
  echo "Run tools/deploy.sh first." >&2
  exit 1
fi

echo "==> reading the device id"
# The receiver answers with result data, so this is one parseable line rather than a log scrape.
raw="$(adb shell am broadcast -a "$PKG.DUMP_CERTIFICATION" -n "$PKG/.CertificationDumpReceiver" 2>&1 || true)"
id="$(printf '%s' "$raw" | sed -n 's/.*data="\([^"]*\)".*/\1/p')"

if [ -z "$id" ] || [ "$id" = "none" ]; then
  echo
  if adb shell pm list packages | grep -q "^package:$GMS$"; then
    echo "Play Services is installed but has no device id yet."
    echo "It has not reached Google, which it needs before there is anything to register."
    echo "Put the console on a network, reboot it, and run this again in a few minutes."
  else
    echo "This console has no Google apps yet, so there is nothing to certify."
    echo "Install the Google Play bundle first, from Stride's Store tab."
  fi
  exit 1
fi

# Grouped the same way the console shows it, so the two can be checked against each other. The
# groups are for reading only - what gets registered is the bare number.
grouped="$(printf '%s' "$id" | sed -E 's/(.{4})/\1 /g; s/ $//')"

cat <<BANNER

    device id   $grouped
    register    $id

BANNER

echo "Registering it is the one step nobody can do for you: the page needs you signed in"
echo "as the account this console will use. A different account will not work."
echo
echo "Paste the number on the second line. It is decimal - if you have seen a hex id from"
echo "another tool, that is the same id in another base and the page will reject it."
echo

if command -v pbcopy >/dev/null 2>&1; then
  printf '%s' "$id" | pbcopy
  echo "It is on your clipboard."
fi

opened=""
if command -v open >/dev/null 2>&1; then
  open "$URL" && opened=1
elif command -v xdg-open >/dev/null 2>&1; then
  xdg-open "$URL" >/dev/null 2>&1 && opened=1
fi
[ -n "$opened" ] || echo "Open $URL"

echo

# Everything past this point wipes both apps' data and reboots the console, so it needs a person.
# Piped or redirected stdin gives `read` an instant EOF, which would sail straight through the
# confirmation and clear the data of someone who had not registered anything yet - found the hard
# way, testing this script.
if [ ! -t 0 ]; then
  echo "Nothing else can be done without you: register the id above, then run this again" >&2
  echo "from a terminal to clear the cached failure and reboot." >&2
  exit 1
fi

echo "Once the page says the device is registered, type 'done' to clear the cached"
echo "failure and reboot. Anything else stops here and changes nothing."
read -r answer
if [ "$answer" != "done" ]; then
  echo "Stopped. Nothing was changed."
  exit 0
fi

echo "==> clearing Play Services and Play Store data"
# The step people skip, and the reason registering "does not work": both apps cache the failed
# certification check, and nothing re-reads it until their data is gone.
adb shell pm clear "$GMS" || echo "    (could not clear $GMS)"
adb shell pm clear "$VENDING" || echo "    (could not clear $VENDING)"

echo "==> rebooting"
adb reboot

cat <<'DONE'

Rebooting. Google can take a few minutes to honour the registration, so if Play still
refuses on the first try, leave it a while and open it again rather than starting over.

Wireless debugging does not survive a reboot on this console, so re-enable it in
Developer options before the next tools/ run.
DONE
