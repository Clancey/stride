# Stride

A Flutter Android launcher and persistent workout overlay for NordicTrack / iFit machines.

Stride replaces the console's launcher, puts the workout controls on a toggleable overlay that
floats above whatever else is running, and lets you pin and launch ordinary Android apps — Spotify,
YouTube, Plex — underneath it. Media pauses when the workout pauses. Since the console has no
physical Home or Back button, Stride supplies system navigation too.

**Status: Phase 0.** Nothing here controls a treadmill yet. The repository currently contains the
architecture plan and a spike harness whose only job is to answer, on real hardware, whether the
approach is viable at all.

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
├─ apps/spikes/        # Phase 0 spike harness (throwaway) - answers S1-S10 on real hardware
└─ docs/               # Plan, runbook, spike results
```

Packages get extracted when real reuse appears, not before. The device abstraction is deliberately
deferred to Phase 6, *after* two concrete implementations exist (`docs/PLAN.md` §4).

---

## Running the spike harness

```bash
cd apps/spikes
flutter build apk --debug
adb install -r build/app/outputs/flutter-apk/app-debug.apk
```

Then grant permissions per [`docs/RUNBOOK.md`](docs/RUNBOOK.md) §6 and work through
[`docs/SPIKES.md`](docs/SPIKES.md).

The harness deliberately **cannot command the motor**. It observes, extracts credentials, probes
the transport, and measures — nothing more. Motor control does not arrive until Phase 1, and only
behind the Control & Safety Coordinator (`docs/PLAN.md` §3.1).

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
