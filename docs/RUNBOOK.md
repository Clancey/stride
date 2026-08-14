# Runbook — recovering a console you have broken

> **Read this before you set Stride as the default HOME app.** The whole point of spike S1 is to
> prove the revert path *first*. If you cannot get back to iFit, do not proceed.

The console has no physical Home or Back button. A launcher that crashes on start, or an overlay
that swallows every touch, leaves you with a treadmill you cannot operate by hand. ADB is the only
reliable way back, so ADB access must be established and tested **before** anything else.

---

## 0. Prerequisites — set these up first, verify them, then continue

```bash
# Confirm the device is reachable and authorised.
adb devices          # must show "device", not "unauthorized" or "offline"

# If it is on Wi-Fi rather than USB:
adb connect <console-ip>:5555
```

**Keep a second terminal with an open `adb shell` while you experiment.** If the UI locks up, an
already-open shell is faster and more reliable than establishing a new connection.

### Prove ADB survives a reboot — before you touch HOME

This is the step people skip and then regret. A broken HOME app is most dangerous exactly when the
console has just rebooted, which is also when a Wi-Fi ADB connection is least likely to come back on
its own. If ADB is over Wi-Fi, the console may not re-enable port 5555 after a reboot at all.

```bash
adb reboot
# wait for the console to come back, then WITHOUT touching the screen:
adb devices        # must show "device" again
```

One warm `adb reboot` is necessary but not sufficient. Also prove:

```bash
# A cold reconnect, not just a live session that happened to survive.
adb kill-server && adb devices          # (over Wi-Fi: adb connect <ip>:5555)

# The revert command itself, run against the CURRENT iFit HOME, so you know the
# syntax is right on this firmware before you need it in anger.
adb shell cmd package set-home-activity com.ifit.rivendell/<activity>
adb shell cmd package resolve-activity -a android.intent.action.MAIN \
  -c android.intent.category.HOME
```

- [ ] A **full power cycle** (pull mains, not `adb reboot`) and ADB still returns unattended
- [ ] `adb kill-server` then a fresh connect, with no screen interaction
- [ ] The console's IP is stable — a DHCP lease change over Wi-Fi loses you the device; set a static
      lease on the router if you cannot use USB

If any of that does not work unattended, **stop**. Fix ADB persistence, or use USB, before
continuing. Do not change the default launcher on a console you can only reach through a connection
you have not proven survives a restart.

A broken HOME app does not itself control adbd or Wi-Fi, so there is no launcher-specific way to lose
ADB — the realistic risk is a crash-looping HOME app starving the device, plus the ordinary
possibility that ADB was never going to come back anyway. Cold reconnect plus a power cycle is the
proof that matters.

### Record the state you are restoring to

```bash
# Note the -a: without an action, resolve-activity returns "No activity found".
adb shell cmd package resolve-activity -a android.intent.action.MAIN \
  -c android.intent.category.HOME

adb shell settings get secure enabled_accessibility_services
adb shell settings get secure accessibility_enabled
```

**Save that output.** The accessibility value in particular may list OEM or iFit services; §3 below
appends to it rather than overwriting it, but if something does overwrite it, this recording is the
only way back. A value of `null` means the setting is unset, which is different from an empty
string — restore it with `settings delete secure <key>`, not by writing `"null"`.

---

## 1. Revert the default launcher

The most common failure. Symptom: the console boots to a black screen, a crashing app, or Stride
with no way out.

```bash
# Point HOME back at the iFit console app.
adb shell cmd package set-home-activity com.ifit.rivendell/<activity>

# If you do not know the activity name, list every HOME candidate:
adb shell cmd package query-activities -a android.intent.action.MAIN \
  -c android.intent.category.HOME
```

If `set-home-activity` is unavailable on this firmware, disable Stride so the system is forced to
fall back to the only remaining HOME app:

```bash
adb shell pm disable-user --user 0 io.stride.spikes
# and to bring it back later
adb shell pm enable io.stride.spikes
```

Note: `pm clear-package-preferred-activities` does **not** exist on modern `pm` (verified absent on
API 33; do not rely on it on 26-28 either). `pm clear android` is sometimes suggested online for
resetting HOME preference — **never run it**. It wipes the settings of the `android` system package
and can take Wi-Fi, ADB, and your way back in with it.

Last resort:

```bash
adb uninstall io.stride.spikes
```

---

## 2. Kill a runaway overlay

Symptom: the screen is covered, or edge strips eat every touch so no app is usable.

```bash
# Revoke the overlay permission - the windows disappear immediately.
adb shell appops set io.stride.spikes SYSTEM_ALERT_WINDOW deny

# Or stop the process outright.
adb shell am force-stop io.stride.spikes
```

Re-grant later with:

```bash
adb shell appops set io.stride.spikes SYSTEM_ALERT_WINDOW allow
```

---

## 3. Disable the accessibility service

Symptom: unexpected Back presses, or the service is interfering with input.

`enabled_accessibility_services` is a single colon-separated list shared by **every** accessibility
service on the device, including OEM and iFit ones. Blanket-writing it is how you silently disable
something the console needed. Append and remove surgically instead.

Both snippets below are self-contained (safe to paste into a fresh terminal), fail closed if ADB is
not reachable, and match the component **exactly** so a lookalike package is never touched. Android
records a component in either full (`pkg/pkg.Class`) or short (`pkg/.Class`) form, so both are
matched.

Remove only Stride, preserving everything else:

```bash
STRIDE_PKG=io.stride.spikes
STRIDE_CLS=io.stride.spikes.StrideAccessibilityService

if ! adb get-state >/dev/null 2>&1; then echo "ADB not connected - STOP"; exit 1; fi
CUR=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r') || {
  echo "read failed - refusing to write"; exit 1; }
case "$CUR" in null|"") CUR="" ;; esac

NEW=$(printf '%s' "$CUR" | tr ':' '\n' \
  | grep -vx -e "$STRIDE_PKG/$STRIDE_CLS" -e "$STRIDE_PKG/.${STRIDE_CLS##*.}" \
  | grep -v '^$' | paste -sd: -)

if [ -z "$NEW" ]; then
  adb shell settings delete secure enabled_accessibility_services
  adb shell settings put secure accessibility_enabled 0
else
  adb shell settings put secure enabled_accessibility_services "$NEW"
fi
```

Only set `accessibility_enabled 0` when the resulting list is empty — the snippet does this for you.
Turning the master switch off while other services are still listed disables them too.

Re-enable by appending, not replacing:

```bash
STRIDE_PKG=io.stride.spikes
STRIDE_CLS=io.stride.spikes.StrideAccessibilityService
STRIDE_SVC="$STRIDE_PKG/$STRIDE_CLS"

if ! adb get-state >/dev/null 2>&1; then echo "ADB not connected - STOP"; exit 1; fi
CUR=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r') || {
  echo "read failed - refusing to write"; exit 1; }
case "$CUR" in null|"") CUR="" ;; esac

if printf '%s' "$CUR" | tr ':' '\n' \
     | grep -qx -e "$STRIDE_SVC" -e "$STRIDE_PKG/.${STRIDE_CLS##*.}"; then
  echo "already present"
else
  [ -z "$CUR" ] && NEW="$STRIDE_SVC" || NEW="$CUR:$STRIDE_SVC"
  adb shell settings put secure enabled_accessibility_services "$NEW"
fi
adb shell settings put secure accessibility_enabled 1
```

Both snippets were exercised against a live device across seven cases: an OEM service alongside
Stride, Stride recorded in short form, Stride as the only entry, an unset (`null`) baseline, a
repeated add, and a lookalike package (`io.stride.spikesOTHER`) that must survive removal. On macOS
they rely on BSD `paste -sd: -` and `grep -x`, both of which behave as required.

If you lost the baseline entirely, the recording from the top of this document is the only way back.

---

## 4. Navigating without buttons, from the host

While the console has no Home or Back button, ADB does:

```bash
adb shell input keyevent KEYCODE_HOME      # 3
adb shell input keyevent KEYCODE_BACK      # 4
adb shell input keyevent KEYCODE_APP_SWITCH # 187
```

This works from the host because `adb shell` runs with shell UID, which holds `INJECT_EVENTS`. A
sideloaded app does not — which is exactly why Stride needs the accessibility service (plan §3.3).

---

## 5. If the belt is moving and you cannot stop it

**Pull the safety key. That is the only true emergency stop.**

No software path in this repository is a fail-safe. Do not attempt to debug a moving treadmill from
a terminal. Stop it physically, then investigate.

Afterwards, capture what happened:

```bash
adb logcat -d > ~/stride-incident-$(date +%s).log
```

---

## 6. Permission grants used by the spike harness

Collected here so a fresh flash can be brought back to a testable state quickly.

```bash
# S3 - overlay windows
adb shell appops set io.stride.spikes SYSTEM_ALERT_WINDOW allow

# S5 - MediaSessionManager.getActiveSessions() requires an enabled notification listener.
# Verified present on API 33 and on API 28, where it also *appends* rather than overwriting
# (an unrelated setupwizard listener already in the list survived). NOT present on API 26
# (NotificationManagerService gained this shell command in API 27), so try it and check the
# result rather than assuming it worked:
adb shell cmd notification allow_listener \
  io.stride.spikes/io.stride.spikes.StrideNotificationListener
adb shell settings get secure enabled_notification_listeners
```

If that command is unavailable on this firmware, append to the setting directly. Same surgical
pattern as §3 — `enabled_notification_listeners` is also a shared colon-separated list, so do not
overwrite it:

```bash
LSNR=io.stride.spikes/io.stride.spikes.StrideNotificationListener
CUR=$(adb shell settings get secure enabled_notification_listeners 2>/dev/null | tr -d '\r')
case "$CUR" in null|"") CUR="" ;; esac
if printf '%s' "$CUR" | tr ':' '\n' | grep -qx "$LSNR"; then echo "already present"; else
  [ -z "$CUR" ] && NEW="$LSNR" || NEW="$CUR:$LSNR"
  adb shell settings put secure enabled_notification_listeners "$NEW"
fi
```

A failure here does not endanger the console — it produces a false S5 failure, which is its own kind
of expensive mistake.

```bash
# S10 - Back / Recents. Use the APPEND snippet in §3, not a bare
# "settings put secure enabled_accessibility_services". Overwriting the list here would undo
# exactly the protection §3 exists to provide.

# S1 - only after the revert path above has been tested AND ADB is proven to survive a reboot
adb shell cmd package set-home-activity io.stride.spikes/io.stride.spikes.MainActivity
```

---

## 7. An APK will not install

### "This app was built for an older version of Android and doesn't include the latest privacy protections"

That is `INSTALL_FAILED_DEPRECATED_SDK_VERSION`. Android refuses to sideload apps whose
**`targetSdkVersion`** is below a floor. It is about `targetSdk`, not `minSdk`, and not the version
of Android you are installing *onto* being too old.

| Installing onto | Minimum `targetSdkVersion` accepted |
|---|---|
| Android 13 (API 33) and earlier | no restriction |
| Android 14 (API 34) | 23 |
| Android 15 / 16 (API 35 / 36) | 24 |

**The Stride spike harness is not affected by this.** It declares `minSdk 26` / `targetSdk 28`,
which clears every floor above, and it installs on an API 33 emulator. So if you see this message,
first find out *which* APK is actually being rejected.

Check any APK, ours or a third party's:

```bash
AAPT=$(ls "$ANDROID_SDK_ROOT"/build-tools/*/aapt2 | tail -1)
"$AAPT" dump badging some.apk | grep -E "package:|sdkVersion"
```

The likely culprits are the **third-party APKs that `SPIKES.md` asks you to sideload** — NordicFTMS
and tHUD are older apps and may well target below the floor. On the treadmill console itself (API
26-28) no floor exists, so this error only appears when you install one of them on a modern phone or
tablet.

To install a low-target APK anyway, for testing only:

```bash
adb install --bypass-low-target-sdk-block some.apk
```

That flag exists on Android 14+ and is the supported developer escape hatch. There is no way for a
normal user to bypass the block from the UI, which is why it is a poor idea to ever depend on a
low-target APK in the product itself.

### The harness specifically

If our own APK is rejected, it is an OEM-specific policy rather than stock Android. Build a variant
that targets a modern SDK:

```bash
flutter build apk --debug -PstrideTargetSdk=35
```

**Do not make that the default.** `targetSdk 28` is a deliberate choice, and raising it changes
runtime behaviour in ways that would invalidate the spike results: scoped storage at 29+ breaks
locating the iFit APK (S2), **package-visibility filtering at 30+ breaks app enumeration, which is
the launcher's core feature** (S4), and background-activity-start limits at 29+ affect the overlay.
A modern-target build is for inspecting the UI on a phone, not for drawing conclusions about the
console.

### Other install failures

```bash
# Signature mismatch with an already-installed copy - uninstall first.
adb uninstall io.stride.spikes

# Not enough space for a 160+ MB debug APK.
adb shell df -h /data

# Wrong ABI (rare; the debug APK is fat and includes all of them).
adb shell getprop ro.product.cpu.abilist
```

---

## 8. Escalation order

1. Revoke the specific permission causing the problem (§2, §3).
2. Force-stop the app (§2).
3. Revert HOME (§1).
4. Disable the package (§1).
5. Uninstall (§1).
6. Factory reset the console — **only** with a known-good path back to a working iFit install, since
   this console is not a device you can trivially reimage.
