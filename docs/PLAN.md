# Stride — Flutter Android launcher + workout overlay for NordicTrack / iFit machines

> Working name: **Stride**. Alternatives: Cadence, Treadhead, Overdrive, Baseline.
>
> **Revision 3** — adds system navigation (§3.3), because the console has no physical Home or Back
> button. Revision 2 restructured the plan after a critical review: safety enforcement now precedes
> any motor control; the phase order is a narrow 1750 vertical slice before any generalization;
> several Android/BLE assumptions were downgraded from "design" to "unproven, must spike"; scope
> trimmed substantially for a solo build.

## 1. Problem statement

The iFit console on a NordicTrack Commercial 1750 is a capable Android tablet welded to a
treadmill, locked into a subscription experience. The goal is a Flutter app that:

- Boots as the console's **default HOME launcher**.
- Presents a **persistent, toggleable workout overlay** with the controls the native iFit workout
  has (speed, incline, start/pause/stop, quick jumps, live metrics), on top of whatever is running.
- **Pins and launches third-party apps** (Spotify, YouTube, Plex, maybe Netflix) that run
  *underneath* the overlay.
- **Provides system navigation** — the console has **no physical Home or Back button**, so Stride
  must supply Back / Home / Recents to any foreground app, reachable by an **edge swipe** that works
  regardless of what is on screen.
- **Pauses/resumes media** in lockstep with workout state.
- Supports **profiles** — per-user pinned app sets, unit prefs, workout defaults.
- Eventually talks to the **breadth of equipment qdomyos-zwift supports**, so the same app is
  useful on a phone next to an Echelon bike or a generic FTMS trainer.
- Syncs with **companion iOS/Android apps**: phone/watch streams live HR in, finished workouts flow
  out to **HealthKit / Health Connect**.

## 2. Research findings that shape the design

### 2.1 How the machine is actually controlled

The motor controller on a 1750 is a **separate board** (MC-2100 class) on an internal UART. Android
userspace has **no direct access**. Three known paths:

| Tier | Path | Applies to | Notes |
|---|---|---|---|
| A | **GlassOS gRPC over localhost:54321, mTLS** | Modern iFit (≈2019+), incl. 1750 | The design target. Streaming metrics + set speed/incline/resistance + workout start/pause/stop. Client certs hide inside the `com.ifit.rivendell` APK. Proven by `mikepugh/NordicFTMS` **and by `a-vikulin/thud`, which already replaces the iFit player entirely**. |
| B | **Legacy WebSocket** `ws://<ip>/control`, JSON `{"type":"set","values":{"KPH":"8.5"}}` | Pre-≈2019 iFit firmware | What qz's `proformwifitreadmill` uses. Likely absent on this console. |
| C | **ADB logcat + `input swipe` touch simulation** | Any iFit console | What qz's `nordictrackifitadbtreadmill` uses. **Rejected**: it requires the iFit UI to be foregrounded, which contradicts the entire premise of this product. |

### 2.2 The mTLS certificate problem — how everyone else handles it

**CORRECTED 2026-08-13, by direct measurement on the user's console. The original claim below was
wrong, and it was wrong in the direction that mattered.**

This section previously read: *"You asked how qdomyos-zwift handles the certs. The answer: it
doesn't — it avoids gRPC entirely."* That conclusion came from searching the qz **repository** and
finding no `.pem` files and no `QSslCertificate` usage. The search was accurate; the inference was
not.

The installed `org.cagnulen.qdomyoszwift` APK on the console ships, uncompiled, at its asset root:

```
assets/ca_cert.pem
assets/client_cert.pem
assets/client_key.pem
```

and its dex references `glassos` throughout. qz speaks GlassOS gRPC directly. It does not defeat
the mutual TLS — **it ships the credentials.**

The `input swipe` / log-scraping / OCR path described below is real, but it is qz's *phone-side
remote control* path for driving a console it cannot run on. It is not how the on-console build
reads telemetry. Conflating the two produced a confidently wrong conclusion, and the lesson is
worth keeping: absence of evidence in a source repository is not evidence of absence in a shipped
binary. Check the artifact that actually runs.

#### What was verified live, not inferred

- The client certificate's subject is **`CN=com.ifit.eriador`** — it authenticates as one of iFit's
  own applications. The working metadata header is `client_id: com.ifit.eriador`, not
  `com.ifit.dev_app` as recorded below.
- **`openssl verify -CAfile ca_cert.pem <console server cert>` → OK.** qz's bundled CA validates
  *this* console's server certificate, and the two were minted three minutes apart on 2023-10-25,
  expiring 2033-10-22.
- Full mTLS handshake succeeds: TLS 1.2, `ECDHE-RSA-AES128-GCM-SHA256`, ALPN `h2`,
  `Verify return code: 0 (ok)`. Live calls return real data —
  `ConsoleService/GetConsoleState → IDLE`, `DistanceService/CanRead → isAvailable: true`.
- Without a client certificate the handshake fails with TLS alert 40; with a *wrong* one
  (gRPC's public test client cert) it fails with alert 46, `certificate_unknown`. The server really
  does verify clients.

#### This answers open question S2b

**The certificates are shared factory credentials, not per-device.** A CA extracted from a
general-purpose app distributed to thousands of users validates this specific console. tHUD's
documentation claiming per-treadmill certs is not true for this firmware line; NordicFTMS's
prebuilt-APK distribution, which only works if certs are shared, was the correct read.

**Stride's decision does not change.** Self-extraction on device is still the design, for reasons
that survive S2b being answered: we still refuse to bake anyone's key material into a distributed
binary, and an extractor stays correct if iFit ever does move to per-device credentials. What
changes is that the extraction target is now known to be reachable and the credential shape is
confirmed, so telemetry work is no longer blocked on solving access.

One implementation detail that will otherwise cost an afternoon: the server certificate uses a
legacy Common Name with **no SAN**, which modern TLS stacks reject outright. Stride's Android
client needs a trust manager pinned to this CA that verifies the chain *without* hostname matching
— not blanket trust.

Full detail, including the proto3 parsing trap that would otherwise make Stride render fabricated
zeros, is in `protocol/glassos/README.md`.

---

*Original text, retained because the mechanism it describes is still how qz's phone-side remote
works:*

A search of `cagnulein/qdomyos-zwift` and its companion app `QZCompanionNordictrackTreadmill` for
gRPC, protobuf, `QSslCertificate`/`QSslKey`/`QSslConfiguration`, `.pem`/`.p12`/`.jks`, GlassOS, or
any client-cert handling returns **zero results**. There are no certificate files in either repo.

qz gets telemetry by **scraping a log file** iFit writes to `/sdcard/` for lines like
`Changed KPH`, `Changed Grade`, `Changed Watts`, `Changed RPM`, `HeartRateDataUpdate` — with an
**OCR screenshot fallback** when log access fails — then UDP-broadcasts it on port 8002. It sends
control back as `input swipe` touch simulation.

**The projects that do use GlassOS gRPC split two ways:**

| Project | Cert strategy | Notes |
|---|---|---|
| `mikepugh/NordicFTMS` | **Bundled in the APK** at `assets/certs/glassos_{ca,client_cert,client_key}.pem`, loaded via `AssetManager`. Certs are *not* committed to the repo — the maintainer bakes his own in at build time | Implies he believes certs are shared across machines, since the README never mentions extraction |
| `a-vikulin/thud` (tHUD) | **Read from the filesystem at runtime**, `/sdcard/Android/data/<pkg>/files/certs/{ca.crt,client.crt,client.key}` | Docs explicitly say certs are per-treadmill and must come from *your own* console |

**tHUD is major prior art and the single biggest de-risking discovery in this research.** It is a
GPL-3 app that **already fully replaces the iFit Workout Player** on the console, driving GlassOS
over gRPC, with structured workouts, HR zones, FIT export, Garmin upload, and a BLE DIRCON server.
Tested on a NordicTrack X24. Its existence largely answers S2 in the affirmative: a sideloaded,
non-system app *can* own the machine while iFit's UI is gone.

#### The concrete facts we now have

- **Endpoint**: `localhost:54321`, gRPC over mTLS (tHUD also uses a Unix domain socket).
- **Required metadata header**: `client_id: com.ifit.dev_app`.
- **Cert material**: three PEM files — CA cert, client cert, and an **unencrypted PKCS#8 RSA private
  key**.
- **Where they hide**: iFit stores them inside `com.ifit.rivendell` (the *console* package, not the
  phone app) **disguised as fake JPEG files** in resources. `codeberg.org/avikulin/tHUD-certs` is a
  Python script that finds them by scanning the APK ZIP for standard ASN.1/DER structure patterns
  and converts them to PEM. Per its README, it embeds no proprietary secrets — it's pure structural
  detection.
- **The `.proto` files are public** in `mikepugh/NordicFTMS:app/src/main/proto/com/ifit/glassos/`:
  `SpeedService.SetSpeed(kph)`, `InclineService.SetIncline(percent)`, `ResistanceService`,
  `CadenceService`, `WattsService`, `DistanceService`, `WorkoutService`
  (`StartNewWorkout`/`Pause`/`Resume`/`Stop`/`WorkoutStateChanged` stream), and `ConsoleService`.
  Dart stubs can be generated straight from them with `protoc` + the Dart gRPC plugin.
- **`ConsoleInfo` is a gift.** It reports `machineType`, `maxKph`/`minKph`,
  `maxInclinePercent`/`minInclinePercent`, `maxResistance`/`minResistance`,
  `canSetSpeed`/`canSetIncline`/`canSetResistance`, plus serial and firmware version — 50+ fields.
  **That is our `ControlRanges` and capability model, straight from the machine**, instead of
  hand-maintained per-model tables. It also means Stride's device-level safety ceiling (§3.6) can be
  seeded from what the machine itself declares.

#### Stride's decision

**Ship zero certificates; adopt the tHUD runtime-load pattern, plus on-device self-extraction.**

1. A `CredentialProvider` reads certs at runtime and imports them into **app-private** storage
   (`getFilesDir()`, Keystore-wrapped where the key format allows) — *not* `/sdcard`, which tHUD uses
   and which is world-readable to other apps. Never logged, never exported.
2. **On-device extraction as a first-run wizard.** The extraction algorithm is public and purely
   structural, so Stride can do it itself: `pm path com.ifit.rivendell` → read the APK → scan for
   DER-encoded ASN.1 in fake-JPEG resources → import. This removes the entire PC + Python + `adb push`
   dance from the user's path and is a genuine UX win over both existing projects.
3. **Never bundle a cert in the APK.** NordicFTMS's approach only works if certs are shared, and it
   puts the maintainer's own key material into every distributed binary. Not doing that.

**Open question (S2b): are the certs per-device or shared across a model/firmware line?** tHUD says
per-device; NordicFTMS's prebuilt-APK distribution implies shared. Nobody has published a comparison
across physical machines. Self-extraction (step 2) makes Stride correct under *either* answer, which
is exactly why it's the chosen design.

**This still does not make the legal question go away** (see §9). It reduces exposure and keeps our
distribution clean; it does not transfer risk cleanly to the user.

#### The sidecar fallback, downgraded

Revision 1 claimed Stride could connect over "loopback BLE" to a NordicFTMS FTMS peripheral on the
same device. **This is probably not viable** — a single Android Bluetooth adapter generally cannot
act as central to a peripheral it is itself hosting, and peripheral-role support on this MediaTek
part is unverified. Downgraded to a Phase 0 question (S7). It also matters much less now, since tHUD
proves the direct gRPC path works. If needed, the real alternatives are: fork the sidecar to expose
localhost IPC, run it on a separate device, or — the primary plan — own the GlassOS client directly.

### 2.3 Licensing — and why one license doesn't fit the whole repo

qdomyos-zwift, NordicFTMS, and tHUD are **all GPL-3**.

- Protocol *knowledge* (GATT UUIDs, JSON keys, byte layouts) is not copyrightable.
- Reading their C++/Java and writing Dart is a derivative-work grey zone.
- **The `.proto` files in the NordicFTMS repo are a sharper case.** They describe an interface, but
  they are also concrete files under GPL-3. Safest path: generate Dart stubs from protos we obtain
  ourselves from the iFit APK, and treat NordicFTMS's copies as documentation for cross-checking.
- **GPL-3 conflicts with normal Apple App Store distribution.** If GPL device/core code links into
  the iOS companion, shipping that companion through the App Store becomes impractical.

**Proposed split:**

| Component | License | Rationale |
|---|---|---|
| Launcher app, device drivers, GlassOS client | **GPL-3** | Where derivative-work risk from reading qz/NordicFTMS actually lives |
| Sync protocol spec + companion apps | **Permissive (Apache-2.0)**, independently authored | Keeps the iOS companion App-Store-distributable |

The two halves talk over a **documented network protocol**, not a linked library — a clean process
boundary that makes the split defensible. Upstream notices and provenance preserved in the GPL half.

**FFI reuse of qz is rejected** — its device classes are welded to Qt (`QObject`, `QLowEnergy*`,
`QTimer`). Clean Dart drivers instead.

### 2.4 Device model to port

qz's abstraction is a good reference: base `bluetoothdevice` → `treadmill` / `bike` / `elliptical` /
`rower` / `stairclimber` / `jumprope`; telemetry for speed, incline, resistance, cadence, HR, power,
calories, distance, odometer, elapsed/moving time; commands `changeSpeed`, `changeInclination`,
`changePower`, `changeResistance`, `start`/`stop`/`pause`. Transports: BLE GATT (FTMS `0x1826`, CPS
`0x1818`, CSC `0x1816`, HR `0x180D`, RSC `0x1814`, plus ~80 proprietary), WebSocket, telnet, UDP,
ADB, USB serial, RFCOMM, ANT+, CSAFE, DIRCON.

Stride will **not** port 120 drivers. It builds GlassOS first, then generic FTMS, and only extracts
a shared abstraction once those two reveal the real commonality (§4).

## 3. Architecture

### 3.1 Control & Safety Coordinator — the load-bearing component

**Nothing commands the motor except through this.** It exists before the first physical control
command ships, not in a later "session engine" phase. It is a single non-UI foreground service and
the sole owner of device and workout state; every Flutter engine and every UI is a *client* of it.

Responsibilities:

- **Serialized command queue.** One in-flight command; no concurrent writes to the machine.
- **Device-level absolute clamps**, above and independent of user profiles (§3.6).
- **Acceleration / slew limiting** on speed and incline.
- **Stop preemption.** A stop cancels every queued command immediately and is *never* rate-limited
  or ramp-delayed. Ramp limiting constrains acceleration only.
- **Generation IDs.** Every command carries the generation of the session that issued it. A reply or
  queued command from a stale generation (after stop, disconnect, or reconnect) is discarded — this
  is what prevents a delayed "set 8 mph" landing after the user hit stop.
- **Command acknowledgement + timeout.** Commands are not fire-and-forget; each expects an ack or a
  confirming telemetry change within a deadline.
- **Telemetry watchdog.** Stall > N seconds while the belt is commanded to move → attempt stop and
  escalate loudly.
- **Attach-to-moving-machine recovery.** If Stride starts or reconnects while the belt is already
  moving, reconcile to observed state rather than assume zero.
- **Safety-key latch.** A safety-key removal latches a stopped state requiring explicit local reset;
  it cannot be cleared by a companion app or a queued command.

### 3.2 Launcher app (Android specifics)

Flutter for UI; a Kotlin layer for everything Flutter can't reach.

- **HOME intent**: `MAIN` + `HOME` + `DEFAULT`, `stateNotNeeded`, `singleTask`; handle `onNewIntent`
  for the HOME key.
- **App inventory**: `PackageManager.queryIntentActivities` for `LAUNCHER` and `LEANBACK_LAUNCHER`;
  icons cached as bytes across the platform channel.
- **Overlay** — corrected from Revision 1:
  - A foreground `Service` owns the overlay windows. Interactive controls and passive readouts live
    in **separate, tightly-bounded windows** — `FLAG_NOT_TOUCHABLE` is a whole-window flag, so
    "inert regions inside one Flutter window" (Rev 1's claim) does not work.
  - Permission: `SYSTEM_ALERT_WINDOW`, granted once via
    `adb shell appops set <pkg> SYSTEM_ALERT_WINDOW allow`.
  - A second Flutter engine (`FlutterEngineGroup`) is *an option*, not a given. It shares some
    runtime cost but still means **separate isolates and separate plugin instances** — it solves
    nothing about state sync, which is exactly why §3.1's service is the single source of truth. If
    memory or frame time on this SoC is tight, the always-visible strip becomes **native Android
    views** and Flutter is reserved for the expanded panel. Decided by S8, not assumed now.
  - **The `AccessibilityService` is back in the design — but for navigation, not for drawing.** See
    §3.3. Revision 1 wrongly proposed it as an *overlay* fallback; that claim stays removed.
    `startLockTask` kiosk behavior also stays removed — lock-task over arbitrary third-party apps
    needs device-owner provisioning.
- **Media control**: `MediaSessionManager.getActiveSessions()` via a `NotificationListenerService`
  (grantable by `cmd notification allow_listener`). The `AudioManager.dispatchMediaKeyEvent`
  fallback is nondeterministic and is a last resort only.
- **Keep the screen awake whenever the belt is moving.**
- **Boot**: `RECEIVE_BOOT_COMPLETED` → start the coordinator service.

### 3.3 System navigation — the console has no physical buttons

The 1750 console has **no Home button and no Back button**. Once Stride launches Spotify, the user
is stranded unless Stride supplies navigation itself. This is not a nice-to-have; it is what makes
"pin and launch apps" usable at all.

**Home is easy. Back is the hard part.**

| Action | Mechanism | Notes |
|---|---|---|
| **Home** | `startActivity` with `ACTION_MAIN` + `CATEGORY_HOME` | Trivial — Stride *is* the home app. No special permission. |
| **Back** | `AccessibilityService.performGlobalAction(GLOBAL_ACTION_BACK)` | **The only way** a non-system app can send Back to another app. `input keyevent 4` needs `INJECT_EVENTS`, which is signature-level and unobtainable without `/system` access. |
| **Recents** | `performGlobalAction(GLOBAL_ACTION_RECENTS)` | Same service, free once it exists. |

So an `AccessibilityService` is **required**, not optional. Enablement without a Settings UI:

```
adb shell settings put secure enabled_accessibility_services <pkg>/<svc>
adb shell settings put secure accessibility_enabled 1
```

Whether this sticks across reboot on this firmware is **S10**.

**Bonus, not incidental:** the same service reports window-state changes, which gives Stride the
current foreground package for free. That directly improves media ownership tracking (§3.2, Phase 3) and lets
the HUD label what's playing.

#### Edge-swipe gesture design

Android 8/9 has no system gesture navigation, so there is no OS gesture layer to conflict with — but
also no `setSystemGestureExclusionRects` to negotiate with apps. We own the problem entirely.

- **Thin always-present edge strips** as `TYPE_APPLICATION_OVERLAY` windows (left, right, and
  optionally bottom), roughly 16–24 dp wide, touchable, `FLAG_NOT_FOCUSABLE`.
- On `ACTION_DOWN` in a strip, the window is **immediately resized to full-screen** so the rest of
  the drag is tracked. This is the standard technique for third-party gesture-nav apps.
- The gesture **must exceed a minimum travel distance** before it is treated as navigation. Short
  taps and tiny drags in the strip should be ignored, because we cannot re-inject a swallowed touch
  into the app underneath (that would need `INJECT_EVENTS` again).
- A completed edge swipe reveals the **navigation + HUD panel**: Back, Home, Recents, plus the
  workout controls — one gesture, one surface. This is also the recovery path when the always-visible
  HUD strip has been toggled off.
- Strip edges, width, and enabled/disabled state are **per-profile configurable**, because the right
  answer depends on which media app is pinned.

#### The unavoidable cost

An always-touchable edge strip **steals input from the app underneath in that region**. That is a
real regression for apps with edge-adjacent controls — a bottom strip will fight Netflix's scrubber
and Spotify's now-playing bar.

Mitigations, in order of preference:

1. Keep strips as narrow as tolerable and prefer **left/right edges over the bottom**.
2. Restrict the active zone to a **partial span** of the edge (e.g. the middle third) rather than
   its full length.
3. Make the whole gesture layer **per-profile toggleable**, with Back/Home living permanently on the
   always-visible HUD strip as the fallback for users who'd rather not lose the edge.

**Safety constraint:** the navigation panel must never obscure or displace the stop control, and no
gesture may hide the stop control while the belt is moving (§5).

### 3.4 Device layer (extracted late, in Phase 6 — not designed up front)

Target shape once GlassOS + FTMS reveal the real commonality. Split by role, not one god-interface:

```dart
// Actuator
abstract class MachineController {
  ControlRanges get ranges;                   // min/max/step/unit per controllable, + writability
  ControlMode get mode;                       // speed+incline vs. resistance vs. ERG power
  Future<CommandResult> setSpeed(Speed v);    // -> requested, applied, ack status, failure reason
  Future<CommandResult> setIncline(Percent v);
  Future<CommandResult> stop();
}

// Independent sensor — a machine, a chest strap, and a phone are all just sources
abstract class MetricSource {
  Stream<MetricSample> get samples;           // value + timestamp + source + quality/freshness
}

// Lifecycle, separate from both
abstract class DeviceSession { Stream<ConnectionState> get state; }
```

Key corrections over Rev 1: commands return **typed results** (requested vs. applied vs. rejected)
instead of `Future<void>`; capabilities carry **ranges, units and step resolution** rather than
booleans; metric source selection **falls back on staleness** rather than using a static priority
list.

**On GlassOS machines, `ControlRanges` is populated by the machine itself**, from
`ConsoleService.GetConsole() → ConsoleInfo` (`maxKph`/`minKph`, `maxInclinePercent`/
`minInclinePercent`, `maxResistance`/`minResistance`, `canSetSpeed`/`canSetIncline`/
`canSetResistance`, `machineType`). No hand-maintained per-model table, and the device-level safety
ceiling (§3.6) can be seeded from what the machine declares about itself rather than from a guess.
Generic FTMS exposes a similar (weaker) capability descriptor, which is good evidence this shape
generalizes.

Discovery maps evidence (BLE name prefix, advertised service UUID, manufacturer data, or explicit
config for network devices) → driver factory, as declarative descriptors rather than qz's 280 KB
if-chain.

### 3.5 Workout session

`idle → running ⇄ paused → summary`, layered **on top of** the coordinator, which owns safety. Owns
elapsed vs. moving time, distance integration, calorie estimation, laps, media side effects, and
emits a `WorkoutSummary` → FIT → sync queue.

**GlassOS exposes this natively.** `WorkoutService` provides `StartNewWorkout`, `Pause`, `Resume`,
`Stop`, and a `WorkoutStateChanged` stream. Prefer these over faking a stop by commanding speed 0 —
the machine's own notion of workout state is authoritative, and the stream tells us when *something
else* (a hardware button, the safety key) changed it. Stride's session state machine must treat that
stream as an input, not assume it is the only actor.

**No auto-start, ever.** The belt never begins moving from app launch, boot, profile switch, or a
companion command without explicit on-console confirmation.

### 3.6 Profiles

SQLite (drift). A `Profile` owns pinned apps + order, units, speed/incline presets, HR zones,
preferred companion, media policy.

**Corrected:** safety limits are **not** a profile field. An **installation/device-level hard
ceiling** exists that a profile may only *lower*, never raise. **Profile and device switching are
disallowed while the belt is moving.** Device binding and calibration are stored separately from
user preferences — they belong to the machine, not the person.

**Media app detection is ranking, not classification.** Evidence (`MediaBrowserService` filter,
media foreground-service permission, observed `MediaSession` history, curated package list) produces
a *suggestion ordering*. All launchable apps remain available to pin; nothing is auto-pinned
silently. Many real media apps expose nothing detectable until first launch, so classification would
be wrong in both directions.

### 3.7 Companion apps + health sync

**Corrected from Rev 1's dual-channel design.** Start with **one authenticated LAN channel** for both
live HR and workout sync — LAN latency is fine for HR, and it avoids betting the feature on unproven
BLE peripheral behavior.

- **Transport**: TLS (or Noise) over TCP with durable device identities, replay protection,
  rate-limited pairing, and secrets in platform secure storage. mDNS for discovery **plus a manual
  IP / QR fallback**, because multicast filtering and AP client isolation are common.
- **BLE HRS (`0x180D`) is a later optional fallback**, added only after testing iOS background
  peripheral advertising and Android peripheral-role support. It also carries no app-level identity,
  so it can't bind an HR stream to a specific profile and can never be the only channel.
- **iOS**: watchOS `HKWorkoutSession` streams HR → iPhone writes the finished `HKWorkout` plus
  distance/energy samples to HealthKit.
- **Android**: Health Connect `ExerciseSessionRecord`, `HeartRateRecord`, `DistanceRecord`,
  `TotalCaloriesBurnedRecord`.
- **Remote motor control from the phone is deferred**, not "nearly free" as Rev 1 claimed. It is a
  physical-safety attack surface. If it ever ships, it requires explicit, time-limited arming
  performed *on the console*.
- No Google Play Services on the console → **LAN only, no cloud dependency.**

### 3.8 Repo layout (deliberately small at first)

```
stride/
├─ apps/launcher/           # Android HOME launcher, overlay, coordinator service (Kotlin + Flutter)
├─ packages/stride_control/ # coordinator, safety, GlassOS client, workout session
├─ apps/companion/          # Flutter iOS/Android phone app         (permissive license)
├─ packages/stride_sync/    # sync protocol, shared with companion  (permissive license)
└─ tools/glassos_mock/      # local gRPC server replaying recorded traces
```

Rev 1's seven-package melos monorepo (`stride_core`, `stride_devices`, `stride_bridge`, `stride_ui`,
`stride_health`, …) plus DIRCON, a virtual FTMS bridge, TCX alongside FIT, and three companion
platforms was premature for a solo project with zero code. **Packages get extracted when real reuse
appears**, not before.

### 3.9 The workout surface — what the overlay actually draws

Built against the stock iFit console screenshot, which is the visual authority for this surface.
The console is 1920x1080 at density 160, so 1 dp is 1 px and every number below is both.

| Surface | Where | Lives while |
|---|---|---|
| Metric strip | top, full width, 186 px | chrome visible; toggleable ("Metrics") |
| Speed / incline rails | left and right, 132 dp, scrollable presets | chrome visible; toggleable per side |
| Bottom bar | bottom, 132 px — Back / Home / Recents, timer transport, volume, fan, toggles | always, while chrome visible |
| Track floor | bottom centre, 1020 x 300 dp, perspective oval | a workout exists, no video playing, not over Stride's own launcher; toggleable ("Track") |
| Goal ring | bottom right, 260 dp | a goal exists **and** a session exists to measure it against |
| Now-playing card | bottom left | music (not video) has an active `MediaSession` |

Rules this surface has already had to learn the hard way:

- **A window is structural.** Anything that adds or removes an overlay window must go through a
  full chrome rebuild; only text may be updated in place. The track floor silently never appeared
  on workout start because a state change was treated as textual.
- **Decorative surfaces set `FLAG_NOT_TOUCHABLE`; interactive ones must not.** The now-playing card
  is therefore *added and removed*, never merely hidden, so a stale touchable window cannot eat
  taps meant for the app underneath.
- **Anything drawn over a third-party app needs its own scrim.** White text over an album grid or a
  poster wall is unreadable. Both the goal ring and the track floor's infield label carry a soft
  radial scrim rather than trusting the content beneath them.
- **Stride's own launcher is a client of the overlay, not an exception to it.** The launcher insets
  for every edge the HUD occupies, and also for the now-playing card, which floats inside the
  content area. Third-party apps get no inset and are not expected to cooperate.
- **The goal belongs to the session.** Ending a workout clears it. A ring reading "0%" over an idle
  console is not a stale number, it is a number about a workout that no longer exists.
- **Every bottom sheet in the launcher goes through `showStrideSheet()`.** A stock
  `showModalBottomSheet` sizes against the full window, ignores the ~318 px the HUD occupies, and
  clipped a safety warning mid-sentence.

#### Goals

A goal is chosen on a dedicated **Start workout** screen (distance or time, preset chips plus a
custom value) reachable from the launcher header — deliberately in the header, because the
launcher hides its workout panel whenever the overlay is up, which is the normal configuration.
Opening it mid-workout changes the goal without restarting the session or discarding elapsed time.

Progress is computed against the machine's own distance register, never integrated from speed x
time. When the reading is stale or absent the ring shows `Not measured` and no arc. A confident
wrong number about how far someone has run is worse than no number.

### 3.10 Direct-machine protocol — a documented codec, deliberately unwired

`docs/DIRECT_MACHINE_PROTOCOL.md` and `FitProCodec.kt` describe the FitPro2 register protocol the
console itself speaks to the motor controller, below GlassOS. It settles a question that had been
open since Revision 1: **distance is a register the machine reports (`CURRENT_DISTANCE`,
`ACTUAL_DISTANCE`), not something the console integrates.**

It ships as a pure codec with **no transport and no callers**, and stays that way until the
standing safety work in §3.1 exists to sit in front of it. The in-frame byte order and CRC are
still unverified, and the endianness is genuinely mixed — speed big-endian, incline and distance
little-endian — so this is documentation with tests, not a driver.

## 4. Phasing — vertical slice first

Rev 1 built profiles and generic device abstractions before proving the primary hardware path, so a
late firmware discovery could have invalidated a lot of work. Reordered:

| Phase | Outcome | Gate |
|---|---|---|
| **0. Spikes** | Retire the existential unknowns (§6) | S1, **S2-A**, S6 all pass |
| **1. Coordinator + GlassOS** | §3.1 service, on-device cert extraction wizard, GlassOS gRPC client (Dart stubs from the iFit protos), `ConsoleInfo`-seeded ranges, + **one safely-bounded command** (incline only, or speed capped low). Headless — no launcher, no UI polish | **S2-B** passes: real control on the 1750, with clamps / watchdog / stop-preemption enforced from the very first command, and the dead-client behaviour documented |
| **2. Overlay + navigation** | Always-visible strip + expanded panel; **AccessibilityService providing Back / Home / Recents**; edge-swipe gesture layer (§3.3). Simplest possible rendering | Overlay survives launching Spotify, you can get *back out* of it, and stop is always reachable |
| **3. Media coupling** | MediaSession control with ownership tracking; pause/resume tied to workout state | Pause workout → Spotify pauses; resume restores only what Stride paused |
| **4. Launcher shell** | Default HOME, app grid, pin/unpin, iFit still launchable as escape hatch | Boots and survives reboot |
| **5. Sessions + export** | Full workout engine, summary screen, FIT encoding, local history | Valid FIT importable by Strava/Garmin |
| **6. Generalize + FTMS** | Extract §3.4 abstraction from GlassOS + a *new* generic FTMS driver; profiles CRUD; media-app ranking | Works against an FTMS trainer *and* the 1750 through one interface |
| **7. Companions** | LAN sync channel, iOS + watchOS + Android apps, HealthKit / Health Connect writes | Watch HR on the console HUD; workout lands in Apple Health |
| **8. Device breadth** | Proprietary families on demand (Echelon, Domyos, Horizon, Sole, Schwinn, Bowflex, Kingsmith) | Each with hardware-in-loop validation, not just trace replay |
| **9. Optional** | Virtual FTMS peripheral / DIRCON for Zwift; structured workouts (`.zwo` / `.fit` / ERG) | Deferred by design |

Note the deliberate inversion: **the abstraction is extracted in Phase 6, after two concrete
implementations exist**, rather than designed in the abstract early.

### Deviation: the launcher shell moved earlier (and why that is safe)

Phases 2 and 4 put the overlay and the launcher shell *after* Phase 1's real motor control. In
practice the launcher UI is being built during Phase 0, ahead of its gate. This is a deliberate
change, not drift, so it is recorded here rather than quietly done.

Why it is safe: the gates on phases 1-3 exist because those phases **command the machine**. The
launcher shell does not. It has no Coordinator, no GlassOS client, and no motor path, and none may
be added to it — the same constraint the spike harness already lives under (§5). Building a
launcher cannot hurt anybody, so gating it behind a treadmill test buys nothing.

Why it is useful now:

- **The hardware spikes are blocked and will stay blocked** until someone is physically at the
  console. Serialising all UI work behind them wastes the entire interval.
- **UI questions do not get answered by hardware.** Whether the app grid is usable at arm's length
  while running is a design question, and the only way to answer it is to look at it and use it.
  Waiting for S2-B tells us nothing about it.
- **It surfaces integration bugs early.** Running the app as a real launcher on a matching API 28
  device immediately exposed two defects that no unit test could see: pressing HOME did nothing at
  all (`singleTask` delivers `onNewIntent`, and Flutter kept its old route), and the overlay drew
  over the app's own title bar and back button on every screen. Both are Phase 2/4 problems found
  during Phase 0, for free.

What has **not** moved: nothing about control, safety, or the Coordinator. Phase 1 still gates on
S2-B, and no command path exists in the launcher. The Phase 0 exit gate (S1, S2-A, S6) is unchanged
— a pretty launcher does not retire a single hardware unknown, and must not be mistaken for progress
against them.

## 5. Safety

A treadmill is the one component here that can physically hurt someone.

**The physical safety key is the only true emergency stop.** Stride's software stop is a best-effort
convenience. The plan must never present it as a fail-safe, and the UI must never imply the belt is
stopped without confirmation.

Enforced by §3.1's coordinator, from the very first control command in Phase 1:

1. **Never interfere with the safety key.** Verify the hardware interlock still cuts the belt with
   Stride as launcher and iFit not running (S6). Safety-key removal latches until explicit local
   reset.
2. **Ramp limiting** on acceleration and incline slew — but **never on deceleration or stop**.
3. **Absolute clamps** at installation/device level; profiles may only tighten them.
4. **Positive stop confirmation.** A stop is "done" only on ack *plus* observed deceleration in
   telemetry. If neither arrives, the UI escalates to **"USE THE SAFETY KEY"** — not "stopped".
5. **A stop sent over a dead link is not a fail-safe.** Characterize what GlassOS does when its
   controlling client dies: if losing the client does not stop the belt, then no software fail-safe
   is achievable and the docs must say so plainly.
6. **No auto-start** from launch, boot, profile switch, or companion.
7. **Screen stays awake while moving.**
8. **Stale-command rejection** via generation IDs (§3.1).

### Hazard table (maintained, not decorative)

| Hazard | Detection | Mitigation | Residual risk |
|---|---|---|---|
| Belt keeps moving after app process death | none from inside a dead process | Characterize GlassOS dead-client behavior; document safety key as primary | **High if GlassOS does not auto-stop** |
| Delayed command executes after stop | generation ID mismatch | discard stale generation | Low |
| Telemetry stalls while belt commanded to move | watchdog timeout | attempt stop, escalate UI | Medium — depends on row 1 |
| Stop command lost on a failed link | no ack, no observed deceleration | escalate to "USE SAFETY KEY" | Medium |
| Overlay engine crashes, stop button disappears | service-side heartbeat on the overlay window | native fallback window; a whole-process kill still defeats this | Medium |
| Navigation panel or edge gesture hides the stop control while moving | UI invariant test | stop control is pinned and cannot be occluded or dismissed while the belt moves | Low |
| Runaway acceleration from a bad UI value | coordinator clamp + ramp limit | reject before transmit | Low |
| Attach to already-moving belt, assume zero | reconcile from telemetry on attach | explicit recovery path | Low |
| Companion app commands motion unexpectedly | — | remote control deferred; on-console arming if ever added | Low (deferred) |

**Required failure-mode tests** before any hardware release tag: app process kill, Dart isolate hang,
service kill under memory pressure, screen sleep, network loss mid-command, stale/duplicated replies,
GlassOS restart, controller reboot, safety-key pull and reinsert, and app restart while the belt is
moving.

## 6. Phase 0 spikes

Each answers a yes/no question. A "no" on S1, S2-A, or S6 changes or kills the project. S2-B does not
gate Phase 0 — it gates shipping any control code, and it belongs behind the Coordinator (see below).

- **S1 — Can the launcher be replaced *and reverted*?** Sideload a hello-world HOME app, set it
  default, reboot, and get back to iFit. Establishes the escape hatch and the documented recovery
  procedure for a broken launcher. If this fails, nothing else matters.
- **S2 — End-to-end GlassOS proof.** Rev 1's version ("does the service survive without iFit
  foregrounded?") proved far too little. **tHUD's existence already strongly suggests most of this
  passes** — it replaces the iFit player outright — so this spike is now about confirming it on
  *your* firmware and answering the parts nobody has published. On the actual 1750:
  1. extract certs from `com.ifit.rivendell` on-device and import to app-private storage;
  2. complete mTLS to `localhost:54321` **from the intended Kotlin/Flutter stack**, with the
     `client_id: com.ifit.dev_app` header (verify ALPN / HTTP-2 on Android 8/9; check whether the
     Unix-domain-socket path tHUD uses is needed);
  3. call `ConsoleService.GetConsole()` and confirm `ConsoleInfo` ranges match the real machine;
  4. subscribe to speed/incline telemetry and confirm units and semantics;
  5. issue one low-risk command and verify acknowledgement;
  6. repeat with iFit backgrounded, force-stopped, relaunched, and connected simultaneously — is the
     service exclusive-client?
  7. reconnect cleanly after GlassOS restart, app restart, and process death;
  8. determine **what the belt does when the controlling client disappears** (feeds §5 hazard row 1);
  9. check for SELinux / endpoint restrictions on a non-system app.

  **Split into two gates.** Steps 1-4, 6, 7 and 9 are read-only and safe to run alone; call that
  **S2-A**, and it is what gates starting Phase 1. Steps 5 and 8 move a treadmill; call that
  **S2-B**, and it gates shipping *any* control code. S2-B belongs behind the Control and Safety
  Coordinator (§3.1) with clamps and stop-preemption already enforced — not in a throwaway spike
  harness. Note also that step 2 must verify the server genuinely *demands* the client certificate;
  a handshake that would also succeed without one has not proven mTLS.
- **S2b — Are certs per-device or shared?** tHUD's docs say per-device; NordicFTMS's prebuilt-APK
  distribution implies shared. **Do not test this by seeing whether NordicFTMS's release APK
  connects.** Success there is real evidence of sharing, but *failure is inconclusive* — it is
  equally explained by firmware version, GlassOS API drift, a rejected `client_id`, an ABI mismatch,
  or an ordinary bug. Test it directly instead: inspect the extracted client certificate's subject,
  SAN, validity window and SHA-256 fingerprint. A subject carrying the console's serial number
  settles it from a single machine. Doesn't block anything — on-device self-extraction is correct
  either way — but it determines how much onboarding friction real users will face.
- **S10 — AccessibilityService for Back/Home/Recents.** Can it be enabled via
  `settings put secure enabled_accessibility_services`, does it **survive reboot** on this firmware,
  and does `GLOBAL_ACTION_BACK` actually work over third-party apps here? **Back has no alternative
  implementation** for a non-system app, so a "no" means navigation degrades to Home-only.
- **S3 — Overlay over a real app, for real.** Not just "does it composite." Test reboot, low-memory
  kill, fullscreen/immersive apps, rotation, HOME presses, screen off/on, hours-long operation,
  Flutter engine restart, and genuine touch pass-through to the app underneath. **Also test edge-strip
  interference**: how badly does a touchable edge strip disrupt the real media apps from S4?
- **S4 — Do the media apps install and play?** Netflix on a non-GMS, uncertified, likely Widevine-L3
  console may refuse outright. Determine the real media set early — it defines what "pinned apps"
  means.
- **S5 — MediaSession control.** Can `NotificationListenerService` be granted via adb here, and can
  we observe and control sessions for **the specific apps that survived S4**?
- **S6 — Safety key still cuts power** with Stride as launcher and iFit not running.
- **S7 — Same-device BLE loopback.** Can this adapter be central to its own peripheral? Determines
  whether the NordicFTMS sidecar fallback exists at all (§2.2).
- **S8 — Flutter performance on this SoC.** Overlay + grid at native resolution, measured frame
  times. Decides Flutter-vs-native for the always-visible strip.
- **S9 — Service survival under memory pressure.** Does the coordinator foreground service stay
  alive for a 60-minute run with Spotify or Netflix in the foreground?

## 7. Testing strategy

- **Emulator-first**: `tools/glassos_mock` replays recorded GlassOS traces, so most development
  happens off the machine.
- **Trace replay tests are parser / regression tests only.** They validate decoding — *not*
  connection setup, authentication, keepalives, command timing, control-point ownership, firmware
  variation, or recovery. Device compatibility claims require **hardware-in-loop** validation. Scrub
  identifiers and secrets from any checked-in trace.
- **Golden tests** for the overlay at the console's exact resolution.
- **Instrumented Android tests** for launcher, overlay, and media-session plumbing.
- **The §5 failure-mode checklist** is a hard gate on every hardware release tag.

## 8. Open questions

1. **Name.** Stride, Cadence, Treadhead, Overdrive, or something else?
2. **License split** — GPL-3 launcher/drivers + permissive companion/protocol (§2.3), or a single
   license accepting the App Store consequences?
3. **Public or private repo?** Public taps the existing NordicUnchained community; it also raises the
   profile of the certificate question.
4. **Console firmware / iFit build** on your 1750 — determines Tier A vs. B and whether privileged
   mode is currently intact.
5. **Keep iFit launchable** from Stride as an escape hatch (recommended), or full replacement?
6. **Non-treadmill hardware** for testing bike/rower paths, or simulator-only for now?
7. **Relationship to tHUD** (`a-vikulin/thud`) — it already replaces the iFit player over GlassOS
   gRPC under GPL-3. Build Stride independently, borrow from it, or contribute the launcher/overlay
   layer upstream to it?
8. **Edge-swipe layout** — which edges (left/right only, or bottom too), full-length or partial span,
   and should Back/Home *also* live permanently on the HUD strip as a gesture-free fallback?

## 9. Risks register

- **The whole product depends on S2** — but far less than in Rev 2. `a-vikulin/thud` already replaces
  the iFit player using GlassOS gRPC on a NordicTrack X24, which is strong evidence the path works
  for a sideloaded, non-system app. The residual risk is firmware/model variance on *your* 1750.
- **tHUD does a large fraction of what Stride does.** It is GPL-3, actively developed, and has
  structured workouts, FIT export, and a DIRCON server already. Stride's differentiators are the
  *launcher* role, the app-pinning + media coupling, system navigation, profiles, and the companion
  HealthKit story — none of which tHUD does. Worth deciding deliberately whether to build alongside
  it, borrow from it under GPL-3, or contribute the launcher layer to it (§8 Q7).
- **Certificates.** User-supplied / self-extracted certs reduce but do not eliminate
  anti-circumvention, contract, and contributory-risk exposure. Note that Stride's on-device
  extraction is *more* automated than tHUD's PC-side script, which cuts both ways: better UX, more
  visible tooling. Plaintext key material on the console is a security liability regardless — hence
  app-private storage rather than tHUD's world-readable `/sdcard` path. Do not publish extraction
  instructions without legal advice.
- **Cert rotation** on any iFit update can break the driver overnight; on-device re-extraction makes
  recovery a one-tap operation rather than a rebuild.
- **`GLOBAL_ACTION_BACK` has no fallback.** If S10 fails — accessibility can't be enabled, or doesn't
  survive reboot — there is no other way for a non-system app to send Back. Navigation would degrade
  to Home-only, which makes many pinned apps frustrating to use.
- **Edge strips steal touches.** An always-touchable edge region will interfere with edge-adjacent
  controls in media apps. This is an inherent cost, mitigated but not eliminated (§3.3).
- **Firmware freezing is a trade-off, not a best practice.** Blanket DNS-blocking iFit endpoints
  leaves an Android 8/9 tablet handling streaming credentials deliberately unpatched, and may
  suppress firmware carrying genuine safety fixes. Prefer network isolation / VLAN, minimal account
  exposure, and a tested rollback image over broad endpoint blocking.
- **Netflix is the likely problem child.** Plan for Spotify + YouTube + Plex/Jellyfin; treat Netflix
  as best-effort.
- **Package visibility** rules will bite the app-inventory code if the launcher is ever distributed
  beyond this console.
- **Scope discipline.** Device breadth is infinite. Ship the 1750 end-to-end first; every driver
  after that is independently valuable and independently optional.
