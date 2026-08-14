# glassos_mock - a mock GlassOS console for Stride

A standalone Dart gRPC server that pretends to be the private **GlassOS** service
on a NordicTrack Commercial 1750's iFit console, so the Stride treadmill control
app can be built and its safety behavior tested without a treadmill. It speaks
mutual TLS on `localhost:54321`, simulates belt physics, and can inject the
failure modes required by `docs/PLAN.md` section 5.

This is the "emulator-first" tool from `docs/PLAN.md` section 7.

## The schema is a labelled guess, not the real protocol

We do **not** know the real GlassOS protobuf schema. The published `.proto` files
live in a GPL-3 repo we are deliberately not copying, and the true field numbers
can only be confirmed by running `apps/spikes/lib/glassos_probe.dart` against real
hardware. So this mock defines its own *plausible* schema in
`lib/src/messages.dart` and hand-encodes it with a tiny wire-format codec
(`lib/src/proto_codec.dart`) that is the inverse of the probe's schema-free
decoder. When real field numbers arrive, `lib/src/messages.dart` is the only file
that should need to change.

Method paths served (documented GlassOS surface):

- `/com.ifit.glassos.ConsoleService/GetConsole`
- `/com.ifit.glassos.SpeedService/{GetSpeed,SetSpeed,StreamSpeed}`
- `/com.ifit.glassos.InclineService/{GetIncline,SetIncline,StreamIncline}`
- `/com.ifit.glassos.WorkoutService/{GetWorkoutState,StartNewWorkout,Pause,Resume,Stop,WorkoutStateChanged}`

`GetConsole` returns representative (not measured) ranges for a 1750: 0-12 mph
(0-19.31 kph), -3% to +15% incline, speed and incline settable, resistance not.

## 1. Generate throwaway certificates

```
bash tool/gen_certs.sh
```

This writes a throwaway CA, server cert, and client cert into `certs/`:

```
certs/ca.pem  ca.key  server.pem  server.key  client.pem  client.key
```

These are **test fixtures only** and grant no access to any real console. `certs/`
plus `*.pem`/`*.key` are gitignored (repo root and this package), so none of it can
be committed. Verify with `git status` - no cert material should appear.

Note: certificate validity is 800 days on purpose. macOS's TLS stack rejects
server certs valid for more than 825 days, and the handshake then fails with a
cryptic verification error.

## 2. Run the server

Uses the repo's Flutter-bundled Dart:

```
/Users/clancey/Projects/flutter/bin/dart run bin/glassos_mock.dart
```

Flags:

- `--port N` - listen port (default 54321).
- `--no-repl` - run headless (no interactive fault console); Ctrl-C to stop.
- `--certs DIR` - certificate directory (default `certs`).
- `--no-enforce-client-id` - stop rejecting calls without the `client_id` header.
- `--require-client-cert` - strict mTLS: abort the handshake if the client does
  not present a certificate. Off by default so the schema-free probe (which does
  not present a client cert on its gRPC channel) can still connect. When a client
  does present a cert, it is always validated against the CA - that is genuine
  mutual TLS, exercised by the smoke test.
- `--client-lost-policy stop|keep` - what the belt does if the controlling client
  disappears (see faults below).

The required metadata header is `client_id: com.ifit.dev_app`, matching the
documented GlassOS requirement.

## 3. Belt physics

The belt does not jump to the commanded speed. It ramps (bounded acceleration,
faster but still finite deceleration) and telemetry reports the actual
instantaneous speed. This is deliberate: the safety coordinator confirms a stop by
**observing deceleration** in telemetry, not just by receiving an ack
(`docs/PLAN.md` 5.4). A safety-key pull cuts the belt with a faster deceleration.

## 4. Fault injection

Faults are the point of this tool (`docs/PLAN.md` section 5). Three ways to drive
them:

### Interactive REPL (default)

When run with a terminal, an interactive prompt starts. Type `help` for the list.

| Command | Fault (PLAN.md 5 / hazard table) |
|---|---|
| `ack-delay <ms>` | delayed acknowledgements |
| `drop-acks [on\|off]` | dropped acknowledgements (belt still hears the command) |
| `stall-telemetry [on\|off]` | telemetry stalls while the belt keeps moving |
| `die` then `restart` | server dies mid-command and restarts |
| `link-drop [secs]` | link failure then reconnection |
| `client-lost-policy stop\|keep` | configure what the belt does when the client disappears |
| `client-lost` | simulate the controlling client vanishing |
| `key-pull` / `key-insert` | safety-key pull and reinsertion |
| `button start\|pause\|resume\|stop` | a hardware button changes workout state under the client |
| `speed <kph>` / `incline <pct>` | manual actuation, to watch the ramp |
| `status` | print belt speed, incline, workout state, key, active faults |
| `quit` | shut down |

Example session:

```
glassos> speed 8
glassos> status          # belt is ramping, not yet at 8
glassos> stall-telemetry on
glassos> die             # applies the client-lost policy, transport goes down
glassos> restart
glassos> key-pull        # emergency: belt cut, latched to paused
glassos> key-insert
```

### Environment variables (headless / CI presets)

- `GLASSOS_ACK_DELAY_MS=250`
- `GLASSOS_DROP_ACKS=1`
- `GLASSOS_STALL_TELEMETRY=1`
- `GLASSOS_CLIENT_LOST_POLICY=stop`

```
GLASSOS_DROP_ACKS=1 dart run bin/glassos_mock.dart --no-repl
```

### Programmatic (tests)

`test/fault_test.dart` drives every fault directly through `MockMachine` and
`GlassOsMockHost` (the REPL is a thin wrapper over the same calls).

## 5. Tests

```
/Users/clancey/Projects/flutter/bin/dart analyze
/Users/clancey/Projects/flutter/bin/dart test
```

`test/smoke_test.dart` starts the server in-process over real mTLS and makes
genuine gRPC calls: `GetConsole`, `SetSpeed` (asserting the belt ramps rather than
jumping), telemetry streaming, the workout lifecycle, a probe-compatible CA-only
client, strict `requireClientCert` mTLS, and rejection of calls missing the
`client_id` header. `test/fault_test.dart` covers the physics and every fault.

## Assumptions most likely to be wrong on real hardware

- **Every protobuf field number.** They are a guess (fields numbered 1..N in
  declaration order). The probe on real hardware is the source of truth.
- **Message shapes.** `SetSpeed` is assumed to carry a `double` kph in field 1 and
  an optional generation id; `ConsoleInfo` field ordering is invented; workout
  enum ordinals are invented.
- **Units.** We assume `SetSpeed` is kph (the documented method is `SetSpeed(kph)`)
  and incline is percent, but telemetry units and semantics are unconfirmed.
- **Client-lost behavior.** What GlassOS actually does when its controlling client
  dies is an open question (`docs/PLAN.md` hazard row 1); here it is a configurable
  policy precisely so both answers can be tested.
- **mTLS strictness and the `client_id` header semantics.** Real GlassOS requires
  the client certificate; the mock defaults to request-and-validate (not require)
  so the unmodified probe still connects. The exact accepted `client_id` value and
  whether the transport is exclusive-client are unconfirmed.
- **Ramp rates.** Acceleration and deceleration rates are representative guesses,
  not measured from a 1750.
