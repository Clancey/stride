# Phase 0 spike results

Fill this in **on the hardware**. Every row is a yes/no question from `PLAN.md` §6. A "no" on
**S1**, **S2**, or **S6** changes or kills the project, so record those honestly and early.

Build and install the harness:

```bash
cd apps/spikes
flutter build apk --debug
adb install -r build/app/outputs/flutter-apk/app-debug.apk
```

Then grant permissions per `RUNBOOK.md` §6, in that order. **Read `RUNBOOK.md` before setting the
default HOME app.**

---

## Status board

| Spike | Question | Result | Date | Notes |
|---|---|---|---|---|
| ENV | What is this console? | ⬜ | | SDK level, model, fingerprint |
| **S1** | Launcher replaceable **and revertible**? | ⬜ | | Blocking |
| **S2** | GlassOS certs extract + mTLS gRPC works? | ⬜ | | Blocking |
| S2b | Certs per-device or shared? | ⬜ | | Not blocking; affects onboarding |
| S3 | Overlay survives real apps? | ⬜ | | Incl. edge-strip interference |
| S4 | Media apps install and play? | ⬜ | | Netflix expected to fail |
| S5 | MediaSession observe + control? | ⬜ | | |
| **S6** | Safety key still cuts power? | ⬜ | | Blocking |
| S7 | Same-device BLE loopback? | ⬜ | | Only matters if S2 fails |
| S8 | Flutter frame times acceptable? | ⬜ | | Decides Flutter vs native strip |
| S9 | Service survives 60 min under pressure? | ⬜ | | |
| S10 | Back/Home/Recents, surviving reboot? | ⬜ | | No fallback for Back |

---

## ENV — Environment

- `sdkInt`: ______  `release`: ______
- `manufacturer` / `model` / `device`: ______
- `fingerprint`: ______
- Screen resolution and density: ______

If `sdkInt >= 30`, package-visibility filtering and foreground-service types apply and several
assumptions in the plan need revisiting.

---

## S1 — Launcher replace and revert

- [ ] Other HOME candidates exist (an escape hatch) — list them: ______
- [ ] Stride set as default HOME
- [ ] **Rebooted** and Stride still came up as HOME
- [ ] Reverted to iFit **without a factory reset**
- [ ] Revert procedure in `RUNBOOK.md` §1 confirmed accurate on this firmware

**Result:** ______

---

## S2 — GlassOS end-to-end

The console package: ______ (expected `com.ifit.rivendell`; note if it differs)

- [ ] APK located and readable by a non-system app
- [ ] Credentials extracted on-device — how many certs / keys found: ______
- [ ] TCP connect to `localhost:54321` succeeds
- [ ] mTLS handshake completes; ALPN negotiated: ______
- [ ] `ConsoleService/GetConsole` returns a response
- [ ] `ConsoleInfo` ranges match the real machine (max kph ____, max incline ____)
- [ ] Telemetry subscription delivers speed/incline; units confirmed: ______
- [ ] One low-risk command acknowledged (incline only, or speed capped low)
- [ ] Behaviour with iFit **backgrounded**: ______
- [ ] Behaviour with iFit **force-stopped**: ______
- [ ] Behaviour with iFit **running simultaneously** — is GlassOS exclusive-client? ______
- [ ] Reconnects cleanly after GlassOS restart / app restart / process death
- [ ] **What does the belt do when the controlling client disappears?** ______
      *(this answer feeds hazard table row 1 in PLAN.md §5 and may be the difference between
      "Medium" and "High" residual risk on the whole project)*
- [ ] No SELinux denials in `logcat` for a non-system app

**Result:** ______

---

## S2b — Certs per-device or shared?

- [ ] Installed `mikepugh/NordicFTMS` release APK (certs baked in at build time)
- [ ] It connected / did not connect: ______

Connected ⇒ certs are shared at least across this model line. Did not connect ⇒ per-device, as
tHUD's docs claim. Either way, Stride's on-device self-extraction is correct — this only determines
how much onboarding friction real users face.

---

## S3 — Overlay

- [ ] Composites over a third-party app at all
- [ ] Survives a reboot (with a boot receiver)
- [ ] Survives low-memory kill
- [ ] Survives a fullscreen/immersive app
- [ ] Survives rotation
- [ ] Survives screen off/on
- [ ] Survives an hours-long run
- [ ] Touches genuinely pass through to the app underneath outside the strips

**Edge-strip interference** — the cost quantified. For each app from S4:

| App | Edge strip usable? | What broke |
|---|---|---|
| | | |

`edgeTouchCount` after 10 minutes of normal use of a media app: ______

**Result:** ______

---

## S4 — Media apps

| App | Installs | Launches | Plays | Notes |
|---|---|---|---|---|
| Spotify | ⬜ | ⬜ | ⬜ | |
| YouTube | ⬜ | ⬜ | ⬜ | |
| Plex / Jellyfin | ⬜ | ⬜ | ⬜ | |
| Netflix | ⬜ | ⬜ | ⬜ | Widevine level: ______ |

**The set that actually works here defines what "pinned apps" means for this product.**

---

## S5 — MediaSession

- [ ] `NotificationListenerService` grantable via `cmd notification allow_listener`
- [ ] Sessions visible for the apps that survived S4 — which: ______
- [ ] Pause works per-app
- [ ] Resume restores **only** what Stride paused
- [ ] User pressing play themselves correctly suppresses our resume

**Result:** ______

---

## S6 — Safety key

**Do this with someone else present, and stand off the belt.**

- [ ] Safety key cuts the belt with iFit running (baseline)
- [ ] Safety key cuts the belt with **Stride as launcher and iFit not running**
- [ ] Reinserting the key does **not** auto-restart the belt

**Result:** ______

A "no" here stops the project until it is understood. This is the only true emergency stop.

---

## S7 — Same-device BLE loopback

- [ ] Adapter supports peripheral role
- [ ] A central on this device can see a peripheral hosted by this device

Only matters as a fallback if S2 fails. Expected answer: no.

---

## S8 — Flutter performance

- Overlay + app grid at native resolution, average frame time: ______ ms
- 99th percentile: ______ ms
- Jank visible during media playback underneath? ______

Decides whether the always-visible strip is Flutter or native Android views.

---

## S9 — Service survival

- [ ] Coordinator foreground service alive after 60 min with Spotify foreground
- [ ] Alive after 60 min with a video app foreground
- [ ] Restarted correctly if killed (`START_STICKY` observed working)

**Result:** ______

---

## S10 — Navigation

- [ ] Accessibility service enablable via `settings put secure`
- [ ] `GLOBAL_ACTION_BACK` reaches a **third-party app** (not just this app)
- [ ] `GLOBAL_ACTION_RECENTS` works
- [ ] **Survives a reboot** ← the real question
- [ ] Foreground package reporting works (bonus: improves media ownership tracking)

**Result:** ______

If this fails, Back has no alternative implementation for a non-system app and navigation degrades
to Home-only. Record exactly how it failed.
