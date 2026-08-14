# Stride

A Flutter Android launcher and persistent workout overlay for NordicTrack / iFit machines.

Stride replaces the console's launcher, puts the workout controls on a toggleable overlay that
floats above whatever else is running, and lets you pin and launch ordinary Android apps — Spotify,
YouTube, Plex — underneath it. Media pauses when the workout pauses. Since the console has no
physical Home or Back button, Stride supplies system navigation too.

**Status: Phase 0.** Nothing here controls a treadmill yet. The repository currently contains the
architecture plan, a spike harness whose only job is to answer — on real hardware — whether the
approach is viable at all, the safety coordinator that will own every motor command, and a mock
console to test it against. **No spike has been run on hardware yet**; every question in
`docs/SPIKES.md` is still open.

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
