# Phase 0 spike results

Fill this in **on the hardware**. Every row is a yes/no question from `PLAN.md` §6. A "no" on
**S1**, **S2-A**, or **S6** changes or kills the project, so record those honestly and early.

Build and install the harness:

```bash
cd apps/spikes
flutter build apk --debug
adb install -r build/app/outputs/flutter-apk/app-debug.apk
```

Then grant permissions per `RUNBOOK.md` §6, in that order. **Read `RUNBOOK.md` before setting the
default HOME app.**

### What the harness actually automates

Be honest with yourself here, because a checkbox ticked from a screen that did not really measure
anything is worse than an empty checkbox.

| Spike | Harness support | You must do manually |
|---|---|---|
| ENV | Reads build fields; also capture resolution and density yourself | resolution/density if not shown |
| S1 | Weak — queries HOME and opens the HOME settings screen | setting HOME, the reboot, the revert |
| S2-A | Most — extract, handshake, read-only call, telemetry stream, reconnect | iFit state matrix, judging the results |
| S2-B | **None, deliberately** — the harness has no command path at all | everything |
| S2b | **None** — see the S2b section, the original test design was wrong | fingerprint and SAN comparison |
| S3 | Partial — overlay windows plus interference counters | reboot, low-memory kill, hours-long run, per-app judgement |
| S4 | **None** — install and try them yourself | everything |
| S5 | Partial — sends observe/pause/resume and tracks session tokens | judging whether resume was *correct* |
| S6 | **None, deliberately** — the harness never commands the motor | everything, with a second person present |
| S7 | **None** — no Bluetooth code exists in the harness | everything |
| S8 | **Effectively none** — no frame instrumentation | build in profile mode and measure properly |
| S9 | Weak — a spike service exists to watch; it is a surrogate, not the real coordinator | the 60 minutes, the memory pressure |
| S10 | Partial — Back/Home/Recents buttons and foreground reporting | the reboot, and verifying Back over *third-party* apps |

**S8 in particular**: `flutter build apk --debug` runs Dart in JIT with assertions on and no
tree-shaking. Measuring it tells you nothing useful about shipping performance. Build
`--profile` before recording any number.

**S5 in particular**: the harness can tell you it sent a pause and that a session changed state. It
cannot tell you whether resuming was the *right* call — that depends on whether the user intervened,
which only you observed.

---

## Status board

| Spike | Question | Result | Date | Notes |
|---|---|---|---|---|
| ENV | What is this console? | ⬜ | | SDK level, model, fingerprint |
| **S1** | Launcher replaceable **and revertible**? | ⬜ | | Blocking |
| **S2-A** | Certs extract + mTLS + read-only RPC works? | ⬜ | | Blocking — gates Phase 1 |
| **S2-B** | Command acked; dead-client behaviour known? | ⬜ | | Blocking — gates any control shipping |
| S2b | Certs per-device or shared? | ⬜ | | Not blocking; affects onboarding |
| S3 | Overlay survives real apps? | ⬜ | | Incl. edge-strip interference |
| S4 | Media apps install and play? | ⬜ | | Netflix expected to fail |
| S5 | MediaSession observe + control? | ⬜ | | |
| **S6** | Safety key still cuts power? | ⬜ | | Blocking |
| S7 | Same-device BLE loopback? | ⬜ | | Only matters if S2-A fails |
| S8 | Flutter frame times acceptable? | ⬜ | | **Profile build only** |
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

Split into two gates, because they block different things and carry very different risk. **S2-A
issues no motion command. S2-B moves a treadmill.**

### S2-A — Transport and read-only (gate: may Phase 1 begin?)

No RPC in this section requests motion. That is not the same as "safe to do casually": several
steps force-stop, relaunch, or restart iFit and GlassOS, and the effect of that on a machine
mid-workout is exactly what is unknown. **Belt stationary, nobody on the deck, safety key within
reach**, for the whole of S2-A.

- [ ] APK located and readable by a non-system app
- [ ] Credentials extracted on-device — how many certs / keys found: ______
- [ ] Extractor reported a **confident** selection, not an ambiguous one: ______
- [ ] TCP connect to `localhost:54321` succeeds
- [ ] mTLS handshake completes; ALPN negotiated: ______
- [ ] Server actually **required** the client certificate (a handshake that succeeds without one
      means we have not proven mTLS at all — test this deliberately): ______
- [ ] `ConsoleService/GetConsole` returns a response
- [ ] `ConsoleInfo` ranges match the real machine (max kph ____, max incline ____)
- [ ] Telemetry subscription delivers speed/incline; units confirmed: ______
- [ ] Telemetry keeps flowing for 10+ minutes without a silent stall
- [ ] Behaviour with iFit **backgrounded**: ______
- [ ] Behaviour with iFit **force-stopped**: ______
- [ ] Behaviour with iFit **running simultaneously** — is GlassOS exclusive-client? ______
- [ ] Reconnects cleanly after GlassOS restart / app restart / process death
- [ ] No SELinux denials in `logcat` for a non-system app

**S2-A result:** ______

### S2-B — Command and failure behaviour (gate: may any control code ship?)

**Stand off the belt. Have a second person present. Have the safety key in hand.**
Start with incline. Incline is *lower risk* than speed because it need not start the belt — it is
not harmless. It drives heavy hardware and can pinch, destabilise a person standing on the deck, or
damage the frame if something is under it. Check underneath before you move it.

**B1 — incline acknowledgement (belt stopped):**

- [ ] One low-risk incline command acknowledged
- [ ] The ack is a real ack — confirmed by an observed telemetry change, not just a returned message
- [ ] Command latency, request to observed change: ______ ms
- [ ] A command issued and then immediately superseded behaves sanely: ______
- [ ] What happens to a command sent during a GlassOS restart: ______
- [ ] `WorkoutService` state stream reports changes made by the **hardware** controls too: ______
- [ ] Client killed mid-incline-command — what does the actuator do? ______

**B2 — moving-belt disconnect (the dangerous one, do it last and deliberately):**

B1 does not answer this. A stopped belt with a stalled incline command tells you nothing about what
a *moving* belt does when its controller vanishes. Run it as its own experiment: belt at walking
pace, **nobody on the deck**, second person at the safety key, then kill the controlling process.

- [ ] **What does the belt do when the controlling client disappears?** ______
      *(this answer feeds hazard table row 1 in PLAN.md §5 and may be the difference between
      "Medium" and "High" residual risk on the whole project. If the belt keeps moving, say so
      plainly in the README — no software fail-safe is achievable and the safety key is the only
      stop.)*
- [ ] Time from client death to belt stopping, if it stops at all: ______ s
- [ ] Safety key still cuts power in this state

**S2-B result:** ______

The spike harness deliberately does **not** implement S2-B's command step. It is schema-free and
read-only by design. S2-B belongs to Phase 1, behind the Control and Safety Coordinator, with clamps
and stop-preemption already enforced — not to a throwaway harness.

---

## S2b — Certs per-device or shared?

**The obvious test does not work.** The original design here was: install `mikepugh/NordicFTMS`
(which bakes its author's certs in at build time) and see whether it connects. Connecting is decent
evidence that certs are shared. But **failing to connect proves nothing** — it is equally consistent
with a different firmware version, a different GlassOS API surface, a rejected `client_id`, an ABI
or minSdk mismatch, an app-level bug, or the service simply not listening. Reading a negative result
as "certs are per-device" is a straightforward affirming-the-consequent error, and it would push
Stride's onboarding design in the wrong direction.

Use direct comparison instead. Certificates are public data (the private key is not), so a
fingerprint is safe to record and share.

- [ ] Fingerprint of the extracted **client** certificate: ______
- [ ] Fingerprint of the extracted **CA** certificate: ______
- [ ] Subject / CN of the client certificate: ______ (does it embed a serial number or device id?)
- [ ] Certificate validity window: ______ (a per-device cert is usually minted at manufacture)

```bash
# -text is needed to see the SAN; -subject alone will not show it.
openssl x509 -in client.crt -noout -fingerprint -sha256 -subject -issuer -dates
openssl x509 -in client.crt -noout -text | grep -A1 "Subject Alternative Name"
```

**Read the result carefully — this is easy to overclaim.** What each observation actually licenses:

| Observation | Valid conclusion | NOT a valid conclusion |
|---|---|---|
| Same client-cert fingerprint on two consoles | Identical certificate material; certs are shared | — |
| Different fingerprints on two consoles | Distinct certificate material per console | That GlassOS *binds* a cert to its device |
| Console A's certs authenticate to console B | Cross-acceptance holds for that pair | That it holds for every firmware |
| Console A's certs rejected by console B | Something rejected them | Necessarily that certs are device-bound |
| Serial number in subject or SAN | Strong evidence of per-device minting | Proof — a personalised cert can still be cross-accepted under a shared CA |

So a serial-bearing subject is strong evidence, not a settled answer. The only conclusive test is a
controlled cross-machine one, which needs a second console. Until then, record the evidence and say
"probably per-device" rather than pretending it is known.

Note the certificates themselves are not secret, but a cert carrying a machine's serial number is
not privacy-neutral — redact the serial before posting a fingerprint publicly.

Optional supporting evidence, correctly interpreted:

- [ ] NordicFTMS release APK connected: ______
      *(connected ⇒ shared, at least on this model line. **Did not connect ⇒ inconclusive.**
      Capture logcat and say why it failed before drawing any conclusion at all.)*

Note that NordicFTMS and tHUD are older apps. Installing them on the console (API 26-28) is fine,
but installing them on a **modern phone** may fail with "built for an older version of Android and
doesn't include the latest privacy protections" — that is the low-target-SDK block, not a problem
with the APK's contents. See `RUNBOOK.md` §7 for the `--bypass-low-target-sdk-block` flag. Do not
mistake a failed *install* for a failed *connection*; that is the same inference error this section
exists to prevent.

Either answer leaves Stride's on-device self-extraction correct — this only determines how much
onboarding friction real users face.

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

**Edge-strip interference** — the cost quantified. This is the part nobody else has measured, and it
is the main reason to be nervous about §3.3. For each app from S4, reset the counters on the overlay
screen, use the app normally for 10 minutes, then record:

| App | Edge strip usable? | Intentional swipes | Touches stolen | What broke |
|---|---|---|---|---|
| | | | | |

Count a touch as "stolen" only when the strip consumed it and the user was trying to hit something in
the app underneath. That is a judgement call the harness cannot make for you, which is why the
counters are split rather than totalled.

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

**Not automated, deliberately.** The spike harness never commands the motor — it has no control
code path at all. Drive the belt from iFit's own UI (or the console's hardware controls) for the
baseline, and for the Stride-as-launcher case drive it however the machine allows with iFit not
running. The overlay HUD the harness draws is an inert diagnostic surface: it shows no telemetry and
its controls stop nothing.

- [ ] Safety key cuts the belt with iFit running (baseline)
- [ ] Safety key cuts the belt with **Stride as launcher and iFit not running**
- [ ] Reinserting the key does **not** auto-restart the belt
- [ ] Safety key still cuts the belt while the Stride overlay is displayed on top

**Result:** ______

A "no" here stops the project until it is understood. This is the only true emergency stop.

---

## S7 — Same-device BLE loopback

**Not automated.** The spike harness contains no Bluetooth code at all, because this only matters as
a fallback if S2-A fails, and building BLE plumbing speculatively is exactly the kind of work Phase 0
exists to avoid. If S2-A fails, write a throwaway BLE probe then.

- [ ] Adapter supports peripheral role (`BluetoothAdapter.isMultipleAdvertisementSupported`)
- [ ] A central on this device can see a peripheral hosted by this device

Only matters as a fallback if S2-A fails. Expected answer: no.

---

## S8 — Flutter performance

**Build in profile mode.** A debug APK runs Dart in JIT with assertions enabled and no tree-shaking;
its frame times are not evidence about anything shippable.

```bash
flutter build apk --profile
```

- Overlay + app grid at native resolution, average frame time: ______ ms
- 99th percentile: ______ ms
- Worst frame: ______ ms
- Jank visible during media playback underneath? ______
- Memory used by the Flutter engine(s): ______ MB

Budget: this console targets 60 fps, so 16.7 ms. A 99th percentile past roughly 32 ms means visible
stutter and pushes the always-visible strip toward native Android views (PLAN.md §3.2).

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
