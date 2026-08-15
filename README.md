# Stride

A Flutter Android launcher and persistent workout overlay for NordicTrack / iFit machines.

<p align="center">
  <a href="https://www.buymeacoffee.com/clancey"><img src="https://img.shields.io/badge/Buy%20me%20a%20coffee-clancey-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=black" alt="Buy me a coffee"></a>
</p>

Stride replaces the console's launcher, puts the workout controls on a toggleable overlay that
floats above whatever else is running, and lets you pin and launch ordinary Android apps — Spotify,
YouTube, Plex — underneath it. Media pauses when the workout pauses. Since the console has no
physical Home or Back button, Stride supplies system navigation too.

**Status: running on hardware.** Stride is installed as the default launcher on a treadmill console
and updates itself over the air. The overlay, app pinning, media coupling, system navigation, and
the background updater (`docs/APPSTORE.md`) all work on a real machine. Motor control is not wired
up yet — the workout metrics you see are read, not commanded — and the Phase 0 questions in
`docs/SPIKES.md` around GlassOS control are still open.

> **Tested on exactly one machine: a NordicTrack Commercial 1750.**
> Other iFit consoles run the same Android software and will *probably* work, but nobody has tried.
> Read [`docs/RUNBOOK.md`](docs/RUNBOOK.md) before you find out, and open an issue either way.

## Install

Everything a console needs is in the catalog repo:

```bash
curl -fsSL https://raw.githubusercontent.com/Clancey/stride-catalog/main/install.sh | bash
```

The walkthrough — including how to make Stride the launcher, and how to undo that — is
**[Clancey/stride-catalog](https://github.com/Clancey/stride-catalog)**.

---

## Safety

**The physical safety key is the only true emergency stop.**

Stride's software stop is a best-effort convenience. Nothing in this repository is a fail-safe, and
the UI must never imply the belt has stopped without confirmation. A treadmill is the one component
in this project that can physically hurt someone; the design reflects that (`docs/PLAN.md` §5).

Before setting Stride as your default launcher, read **[`docs/RUNBOOK.md`](docs/RUNBOOK.md)** and
verify the revert path. A launcher that crashes on start leaves you with a treadmill you cannot
operate.

---

## Documentation

| Document | What it is |
|---|---|
| [`docs/PLAN.md`](docs/PLAN.md) | The architecture and phasing plan. The source of truth. |
| [`docs/RUNBOOK.md`](docs/RUNBOOK.md) | How to recover a console you have broken. Read first. |
| [`docs/SPIKES.md`](docs/SPIKES.md) | The Phase 0 checklist, filled in on the hardware. |
| [`docs/APPSTORE.md`](docs/APPSTORE.md) | How Stride and third-party apps get updated on a console with no Play Store. |
| [`CHANGELOG.md`](CHANGELOG.md) | Every released version. Generated from tags by `tools/changelog.sh` (see APPSTORE §12). |

Updates are served from a separate public repo — **[`Clancey/stride-catalog`](https://github.com/Clancey/stride-catalog)** —
which holds the APKs and the catalog JSON and is read directly as raw files. Its README is the
install/setup walkthrough for a console that does not have Stride on it yet.

---

## Layout

```
stride/
├─ apps/spikes/          # Phase 0 spike harness (throwaway) - answers S1-S10 on real hardware
├─ packages/
│  └─ stride_control/    # Control & Safety Coordinator - sole owner of motor commands (PLAN 3.1)
├─ tools/
│  └─ glassos_mock/      # Mock GlassOS console, so control logic is testable without a treadmill
└─ docs/                 # Plan, runbook, spike results
```

`stride_control` and `glassos_mock` exist early for one reason: the safety logic is the part that
must not be written under time pressure on a live machine. The coordinator is fully unit-tested
against a fake machine link with fault injection (dropped acks, stalled telemetry, safety-key
pulls, external actors), and the mock console lets those paths run without anyone standing near a
belt. The mock's protobuf field numbers are **guesses** until spike S2-A confirms the real schema;
they are isolated in one file so that swap is cheap.

Other packages get extracted when real reuse appears, not before. The device abstraction is
deliberately deferred to Phase 6, *after* two concrete implementations exist (`docs/PLAN.md` §4).

---

## Running the spike harness

```bash
cd apps/spikes
flutter build apk --debug
adb install -r build/app/outputs/flutter-apk/app-debug.apk
```

Then grant permissions per [`docs/RUNBOOK.md`](docs/RUNBOOK.md) §6 and work through
[`docs/SPIKES.md`](docs/SPIKES.md). If an install is rejected with "built for an older version of
Android", see `docs/RUNBOOK.md` §7 — that is the low-target-SDK block, and it is usually a
third-party APK, not this one.

The harness targets `minSdk 26 / targetSdk 28` to match the console. **That is deliberate**: at
targetSdk 30+ package-visibility filtering breaks app enumeration, which is the launcher's whole
job. A modern-target build for phone testing is available via `-PstrideTargetSdk=35`, but its
results do not transfer to the console.

The harness deliberately **cannot command the motor**. It observes, extracts credentials, probes
the transport, and measures — nothing more. Motor control does not arrive until Phase 1, and only
behind the Control & Safety Coordinator (`docs/PLAN.md` §3.1).

## Running the tests

```bash
cd apps/spikes         && flutter test    # harness: DER scanner, protobuf inspector, mTLS
cd packages/stride_control && dart test   # safety coordinator, incl. failure modes
cd tools/glassos_mock      && dart test   # mock console physics and fault injection
```

**Also run Android lint — `flutter build` does not, and the Dart tests cannot see Kotlin:**

```bash
cd apps/spikes/android
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:lintDebug     # Gradle needs JDK 17+
```

`NewApi`/`InlinedApi` are errors that abort the build. This is not pedantry. We compile against a
modern SDK but target a console on API 26-28, so a newer method overload compiles fine, passes every
test, and then throws `NoSuchMethodError` on the device. That already happened once:
`MotionEvent.getRawX(int)` is API 29+, and it crashed the overlay on the first edge swipe — taking
out the only Back and Home button the console has.

### Testing on an emulator first

Use an **API 28 touch** device, not an Android TV image and not API 33+:

```bash
sdkmanager "system-images;android-28;google_apis;arm64-v8a"
avdmanager create avd -n Stride_Console_API28 \
  -k "system-images;android-28;google_apis;arm64-v8a" -d "Nexus 10"
# edit ~/.android/avd/Stride_Console_API28.avd/config.ini:
#   hw.lcd.width=1280  hw.lcd.height=800  hw.lcd.density=160  hw.screen=multi-touch
```

A TV image has no touchscreen, so the edge-swipe navigation is untestable on it; an API 33+ image
hides API-level bugs because the newer methods exist. See `docs/SPIKES.md` for what an emulator run
does and does not tell you — the short version is that it catches crashes and API mistakes, and
answers **nothing** about GlassOS, certificates, the motor, or the safety key.

---

## Credentials

**Stride ships no certificates and never will.**

The GlassOS service on the console requires mutual TLS. Those credentials live inside the console's
own iFit package, and Stride extracts them on-device, at your explicit request, into app-private
storage. They are never bundled into a build, never written to `/sdcard`, and never logged. See
`docs/PLAN.md` §2.2 for why this design is correct regardless of whether the credentials turn out
to be per-device or shared, and §9 for the risks that this reduces but does not eliminate.

---

## Prior art

This project stands on published research by others, all GPL-3:

- **[`cagnulein/qdomyos-zwift`](https://github.com/cagnulein/qdomyos-zwift)** — the reference for
  device protocols across ~120 machines. Notably it does *not* use GlassOS; it scrapes an iFit log
  file and simulates touches, which is why it needs the iFit UI on screen.
- **[`a-vikulin/thud`](https://github.com/a-vikulin/thud)** — major prior art. Already replaces the
  iFit workout player over GlassOS gRPC, with structured workouts and FIT export. Its existence is
  the strongest evidence that Stride's primary transport works.
- **[`mikepugh/NordicFTMS`](https://github.com/mikepugh/NordicFTMS)** — publishes the GlassOS
  `.proto` definitions and bridges the console to FTMS.

Stride's differentiators are the *launcher* role, app pinning with media coupling, system
navigation, profiles, and the companion HealthKit story — none of which those projects do.

---

## Licensing

Split deliberately, because one license does not fit the whole repository
(`docs/PLAN.md` §2.3):

| Component | License |
|---|---|
| Launcher, overlay, device drivers, GlassOS client | **GPL-3.0** ([`LICENSE`](LICENSE)) |
| Sync protocol and companion apps | **Apache-2.0** ([`LICENSE-APACHE`](LICENSE-APACHE)) |

The two halves communicate over a documented network protocol rather than a linked library. That
process boundary is what keeps the iOS companion distributable through the App Store, which GPL-3
would otherwise make impractical.
