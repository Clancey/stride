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

Record these before changing anything:

```bash
adb shell cmd package resolve-activity -c android.intent.category.HOME
adb shell settings get secure enabled_accessibility_services
adb shell settings get secure accessibility_enabled
```

Save the output. That is the state you are restoring to.

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

If `set-home-activity` is unavailable on this firmware, clear the preferred-activity mapping and
let the system re-prompt:

```bash
adb shell pm clear-package-preferred-activities io.stride.spikes
```

Nuclear option — disable Stride entirely, which forces the system to fall back to the only
remaining HOME app:

```bash
adb shell pm disable-user --user 0 io.stride.spikes
# and to bring it back later
adb shell pm enable io.stride.spikes
```

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

```bash
adb shell settings put secure enabled_accessibility_services ""
adb shell settings put secure accessibility_enabled 0
```

Re-enable with:

```bash
adb shell settings put secure enabled_accessibility_services \
  io.stride.spikes/io.stride.spikes.StrideAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

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

# S5 - MediaSessionManager.getActiveSessions() requires an enabled notification listener
adb shell cmd notification allow_listener \
  io.stride.spikes/io.stride.spikes.StrideNotificationListener

# S10 - Back / Recents
adb shell settings put secure enabled_accessibility_services \
  io.stride.spikes/io.stride.spikes.StrideAccessibilityService
adb shell settings put secure accessibility_enabled 1

# S1 - only after the revert path above has been tested
adb shell cmd package set-home-activity io.stride.spikes/io.stride.spikes.MainActivity
```

---

## 7. Escalation order

1. Revoke the specific permission causing the problem (§2, §3).
2. Force-stop the app (§2).
3. Revert HOME (§1).
4. Disable the package (§1).
5. Uninstall (§1).
6. Factory reset the console — **only** with a known-good path back to a working iFit install, since
   this console is not a device you can trivially reimage.
