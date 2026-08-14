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

Authentication is believed to require mutual TLS plus a `client_id` header. **Unverified on this
firmware.** The client credentials are *not* in the GlassOS APK — it ships only Let's Encrypt
roots (`res/3l.pem`, `res/8R.pem`) for outbound HTTPS. Their real location is still open.

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

## The services that matter to Stride

| Service | Get | Stream | Key field |
|---|---|---|---|
| `DistanceService` | `GetDistance` | `DistanceSubscription` | `lastDistanceKm`, `remainingDistanceKm` |
| `SpeedService` | `GetSpeed` | — | speed in kph |
| `InclineService` | `GetIncline` | — | incline percent |
| `ElapsedTimeService` | `GetElapsedTime` | — | `timeSeconds` |
| `CaloriesBurnedService` | `GetCaloriesBurned` | — | calories |
| `HeartRateService` | — | — | heart rate |
| `StepCountService` | — | — | steps |
| `ConsoleService` | `GetConsoleState` | `ConsoleStateChanged` | `ConsoleState` enum |

Units are metric at the wire (`Km`, `Kph`). Convert at the edge, and only at the edge.

Pace is not a service — iFit derives it from speed. Derive it the same way, from a *measured*
speed, and never from elapsed time against an assumed speed.

## Still open

- Where the mTLS client certificate and key actually live on this firmware.
- Whether GlassOS is exclusive-client, i.e. whether a second client disturbs `com.ifit.rivendell`'s
  session. Read-only calls are expected to be safe in parallel; **this has not been tested on the
  user's machine and must not be tested while anyone is on the belt.**
- Whether `CadenceService` returns anything real on a 1750, which has no cadence sensor.
