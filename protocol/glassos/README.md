# GlassOS protocol definitions

The complete gRPC API surface of the iFit console's machine daemon, `com.ifit.glassos_service`.

## Provenance — these are not guesses

Every `.proto` in this directory was extracted from the real console, not reconstructed from
observed traffic and not copied from someone else's reverse-engineering notes:

- Device: `EwayMediatekXenon1` (`Eway` / `Xenon1`, board `mt8188`, `ro.build.characteristics=aiot`)
- Machine: NordicTrack Commercial 1750
- OS: Android 13 / API 33, 1920x1080 @ density 160
- Source: `/system/app/glassos/glassos.apk`, GlassOS **6.14.6** (a system app), pulled read-only
  over adb. The APK ships its own `.proto` files uncompiled at the archive root.

That provenance matters: earlier phases of this project were working from *inferred* field
numbers, and `docs/PLAN.md` explicitly flagged "every protobuf field number in the mock is a
guess" as an open risk. That risk is now closed for the services defined here.

Keep these files verbatim. If the console firmware updates, re-extract rather than hand-patching,
and diff the result — a changed field number is exactly the kind of silent breakage that would
otherwise show up as a plausible-looking wrong number on a screen.

## Endpoint

```
127.0.0.1:54321        gRPC over HTTP/2
```

Confirmed by reading `/proc/net/tcp6` on the console: the listening socket on port `0xD431`
(54321) is owned by uid `10068`, which `pm list packages -U` resolves to
`com.ifit.glassos_service`. The bind address decodes to **127.0.0.1**, so the endpoint is
loopback-only — unreachable from the network, but directly reachable by any app running on the
console. Which is exactly what Stride is.

Authentication requires mutual TLS. **Verified live against the user's console**, not assumed.

The server presents a certificate with `CN=localhost`, issued by `CN=testca`
(`O=Internet Widgits Pty Ltd` — OpenSSL's placeholder defaults). Connecting without a client
certificate fails the handshake with TLS alert 40; connecting with a *wrong* client certificate
fails with alert 46 (`certificate_unknown`). So the server genuinely verifies clients.

The credentials are **not** in the GlassOS APK, and looking there was a mistake: GlassOS is the
*server*. It ships only Let's Encrypt roots (`res/3l.pem`, `res/8R.pem`) for its own outbound
HTTPS. Client credentials live in the *clients*.

`org.cagnulen.qdomyoszwift` bundles them uncompiled at `assets/ca_cert.pem`,
`assets/client_cert.pem`, `assets/client_key.pem`. The client certificate's subject is
`CN=com.ifit.eriador` — it authenticates as one of iFit's own applications.

These are iFit's factory credentials, not per-device ones: the CA in that APK verifies the server
certificate on this console (`openssl verify` → `OK`), and the two were minted three minutes apart
on 2023-10-25. They expire **2033-10-22**.

Two client-side gotchas:

- The server certificate uses a legacy Common Name with **no SAN**, which modern TLS stacks reject
  outright (Go refuses it; Android will too). The Android client needs a trust manager pinned to
  this CA that verifies the chain without hostname matching, rather than blanket-trusting everything.
- ALPN must offer `h2`. Negotiated cipher is `ECDHE-RSA-AES128-GCM-SHA256` over TLS 1.2.

## Why this changes the design

Phase 0 rendered every machine reading as "Not measured" because Stride had no data source. These
definitions provide one, and reading is categorically safer than commanding:

- **Reading cannot move the belt.** The safety machinery in `MachineLink`/the Coordinator exists to
  gate *commands*. Subscribing to telemetry carries none of that risk, so metrics can become real
  well before any control path is unlocked.
- **`CanRead` is a first-class RPC.** Every metric service exposes
  `rpc CanRead(Empty) returns (AvailabilityResponse)`. Capability is something we *ask the machine*,
  rather than a boolean we assert about it. This is strictly better than a hardcoded constant and
  should replace the guessed availability flags in `MachineLink`.
- **`ConsoleState` includes `SAFETY_KEY_REMOVED`** (`console/ConsoleState.proto`), alongside
  `WORKOUT`, `PAUSED`, `IDLE`, `LOCKED`, `SLEEP`, `ERROR`. This is a real safety signal, and it
  resolves the "a session is user intent, not machine state" divergence flagged in review: we can
  now show both, and reconcile them, instead of guessing.

## The proto3 trap that would make Stride lie

This is the most important thing in this document.

**proto3 does not serialize default values.** A field that is `0.0` and a field that was never set
are byte-identical on the wire. Read against the real console while it sat `IDLE`, every metric
service answered like this:

```
SpeedService/GetSpeed          -> {}
InclineService/GetIncline      -> {}
CaloriesBurnedService/...      -> {}
DistanceService/GetDistance    -> { "remainingDistanceKm": "NaN" }
ElapsedTimeService/...         -> { "timeRemaining": "NaN" }
```

An empty message deserialized into a Kotlin data class with numeric fields yields **`0.0`
everywhere**. Rendered without care, that puts a confident `0.0 mph` beside a belt whose real
state we do not know. That is precisely the fabricated-zero bug this project already shipped once
and deliberately removed, and the API hands it to us by default.

Rules, non-negotiable:

1. **Absent numeric field means no reading, not zero.** Parse into nullable types. Never let a
   protobuf default reach the UI as a number.
2. **`NaN` means no reading.** It appears in normal operation, as above. Check explicitly;
   `NaN` also fails every comparison, so it will silently survive range checks.
3. **`CanRead` must default to false.** `AvailabilityResponse{ bool isAvailable }` is *also*
   proto3, so an unavailable service answers `{}` — the `false` is omitted, not transmitted. A
   client that defaults this field to `true` concludes the opposite of the truth. Deny by default.

Rule 3 is not hypothetical. Measured on the 1750:

| Service | `CanRead` | Meaning |
|---|---|---|
| Distance, Speed, Incline, ElapsedTime, CaloriesBurned, HeartRate | `{"isAvailable": true}` | readable |
| **StepCount, Cadence** | `{}` | **not available** |

Cadence being unavailable settles an open question — the 1750 has no cadence sensor, and the
console reports that honestly. Ask `CanRead` before showing a metric, and show "Not measured"
rather than a zero when it says no.

## The services that matter to Stride

| Service | Get | Stream | Key field |
|---|---|---|---|
| `DistanceService` | `GetDistance` | `DistanceSubscription` | `lastDistanceKm`, `remainingDistanceKm` |
| `SpeedService` | `GetSpeed` | — | speed in kph |
| `InclineService` | `GetIncline` | — | incline percent |
| `ElapsedTimeService` | `GetElapsedTime` | — | `timeSeconds` |
| `CaloriesBurnedService` | `GetCaloriesBurned` | — | calories |
| `HeartRateService` | — | — | heart rate |
| `StepCountService` | — | — | steps (unavailable on 1750) |
| `ConsoleService` | `GetConsoleState` | `ConsoleStateChanged` | `ConsoleState` enum |

Units are metric at the wire (`Km`, `Kph`). Convert at the edge, and only at the edge.

Pace is not a service — iFit derives it from speed. Derive it the same way, from a *measured*
speed, and never from elapsed time against an assumed speed.

## Reproducing the read

Verified working against the console on 2026-08-13. Read-only calls only.

```sh
adb -s <console> forward tcp:54321 tcp:54321
grpcurl -insecure \
  -cert client_cert.pem -key client_key.pem \
  -import-path protocol/glassos -proto console/ConsoleService.proto \
  -H client_id:com.ifit.eriador \
  127.0.0.1:54321 com.ifit.glassos.ConsoleService/GetConsoleState
# -> { "consoleState": "IDLE" }
```

`-insecure` here skips Go's hostname verification only; the chain itself was verified separately
with `openssl verify -CAfile ca_cert.pem`. Stride must not take the blanket-trust shortcut — see
the SAN note above.

**`CanRead`, `Get*` and the `*Subscription` streams are safe: reading cannot move the belt.**
`SetSpeed`, `SetIncline`, `StartNewWorkout` and anything else in `control/` can, and are governed
by the safety rules in `docs/PLAN.md`. Do not call them casually, and never while someone is on
the machine.

## On reusing qz's credentials

`org.cagnulen.qdomyoszwift` is GPL-3 and distributes these files publicly in its source tree, so
obtaining them involves no circumvention — they are published, and they authenticate as
`com.ifit.eriador` rather than as a per-user identity.

That is a legal and ethical question worth deciding deliberately rather than by default, and it is
the user's call, not this repo's. The engineering point is narrower: the protocol is reachable and
the credential shape is known, so Stride's telemetry design no longer depends on solving access.
If we later ship our own credential path, nothing above changes except where the bytes come from.

## Still open

- Whether GlassOS is exclusive-client, i.e. whether a second client disturbs `com.ifit.rivendell`'s
  session. Read-only calls succeeded while iFit was installed and idle; this has **not** been
  tested during a live workout and must not be tested while anyone is on the belt.
- Whether the belt keeps moving if a controlling client dies.

## Measured during a live workout, 2026-08-13

Run on an **empty** treadmill, with the user present and the machine's own controls to hand.

**`StartNewWorkout(Empty)` moves the belt.** It is not a passive session start. It took the console
`IDLE → WARM_UP → WORKOUT` and started the belt at `minKph` (1.609 kph = 1.0 mph) with no speed
command sent at all. Anything in Stride that calls it must treat it as a motor command, with the
same gating as `SetSpeed`.

**`WorkoutService/Stop(Empty)` halts it cleanly**, returning `{"success": true}`, and the console
settles `WORKOUT → WORKOUT_RESULTS → IDLE` with the speed field absent again.

Telemetry populated exactly as hoped, streaming at roughly 2 Hz:

```json
{"workoutID":"d3a3a6d8-…","timeSeconds":36,"lastDistanceKm":0.016540491104125976,
 "remainingDistanceKm":19.304549550411686}
{"workoutID":"d3a3a6d8-…","timeSeconds":36,"lastKph":1.609344482421875,
 "maxKph":1.6093444978925633,"avgKph":1.6093442159540512}
```

Distance accrues at 0.000447 km/s, which is exactly 1.609 kph. **Distance is real, measured, and
does not have to be derived** from elapsed time against an assumed speed.

### The discriminator that makes honest rendering possible

Over the same 36 seconds, `InclineSubscription` delivered 39 messages, and **not one contained an
incline field**:

```json
{"workoutID":"d3a3a6d8-b50b-406c-8e46-98b1485cf276"}
```

Incline was a real, measured `0%`. proto3 dropped it precisely *because* it was zero, and the
message is field-for-field indistinguishable from the `IDLE` case where we know nothing.

`workoutID` resolves it. GlassOS stamps it on every metric message belonging to a live workout and
omits it otherwise, so:

- `CanRead` false → **unknown**, whatever else arrives.
- `workoutID` absent + value absent → **unknown**; nothing is being measured.
- `workoutID` present + value absent → **genuine zero**.
- `NaN` → **unknown**; GlassOS uses it for "no figure".

This rule is implemented once, with the evidence attached, in
`apps/spikes/android/app/src/main/kotlin/io/stride/spikes/GlassOsTelemetry.kt`. Parse through it
rather than reimplementing the reasoning at each call site.

### The machine describes its own limits

`ConsoleService/GetConsole` returns, among 50+ fields:

```
machineType = TREADMILL      modelNumber = 17125
maxKph = 19.313 (12.0 mph)   minKph = 1.608 (1.0 mph)
maxInclinePercent = 12       minInclinePercent = -3
canSetSpeed = true           canSetIncline = true
```

This is Stride's `ControlRanges`, straight from the machine. Note `minKph`: inside a workout there
is no zero speed, so the speed rail correctly starts at 1 mph rather than 0.
