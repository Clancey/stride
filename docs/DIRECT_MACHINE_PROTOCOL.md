# Direct machine protocol — the Sindarin / FitPro register path

> **This path is not connected to anything, on purpose.** The codec that implements it
> (`apps/spikes/android/app/src/main/kotlin/io/stride/spikes/FitProCodec.kt`) can turn numbers into
> bytes and back, and it can do nothing else. It opens no USB device, no BLE connection, no socket.
> Read the "What is not verified" section before you even think about changing that.

Today Stride drives the treadmill only indirectly: it talks to iFit's GlassOS gRPC server
(`localhost:54321`, see `GlassOsClient.kt`) and lets GlassOS command the hardware. This document
describes the layer *underneath* that server — the register protocol GlassOS itself uses to drive
the machine directly over USB serial or BLE. Reimplementing it would let Stride talk to the machine
without GlassOS in the loop at all.

That is worth writing down because it is a real option, and because one of its details settles a
question the project had left open. But it is not worth *building* yet, and the rest of this
document explains why.

## Why this matters

- **Independence from GlassOS.** If GlassOS is unavailable, misbehaving, or removed, the register
  path is the only way to read telemetry from and (in principle) command the machine.
- **Lower latency.** It removes a gRPC hop and a whole service process from the loop.
- **It answers a standing question about distance** (see "One finding, stated plainly" below).

None of that changes the standing rule: **nothing may move the treadmill.** The register path is
documented here as knowledge, not as a feature.

## Register map

Frames are register-based, not opcode-based: each frame carries a register (bit-field) descriptor
plus a serialized value. Field ids and the read-only flag are transcribed from uncorrupted
decompiled source (`sh/a.java`). Byte lengths are given where the source stated them explicitly;
where it did not, the codec defaults to the protocol's common 4-byte width and the length should be
confirmed against captured traffic before it is relied on.

### Writable setpoints

| Register       | Field | Bytes | Notes                                  |
|----------------|-------|-------|----------------------------------------|
| `KPH`          | 0     | 4     | speed setpoint                         |
| `GRADE`        | 1     | 4     | incline setpoint                       |
| `RESISTANCE`   | 2     | 4     | bike / rower                           |
| `FAN_SPEED`    | 8     | 4     | 1-byte FanState value                  |
| `VOLUME`       | 9     | 4     |                                        |
| `PULSE`        | 10    | 4     |                                        |
| `WORKOUT_MODE` | 12    | 4     | 1-byte WorkoutMode; start / stop / pause |
| `AUDIO_SOURCE` | 14    | 4     |                                        |

### Read-only telemetry (machine-reported)

| Register                   | Field | Bytes | Notes                          |
|----------------------------|-------|-------|--------------------------------|
| `WATTS`                    | 3     | 4     |                                |
| `CURRENT_DISTANCE`         | 4     | 4     | little-endian int              |
| `RPM`                      | 5     | 4     |                                |
| `DISTANCE`                 | 6     | 4     | little-endian int              |
| `KEY_OBJECT`               | 7     | 4     |                                |
| `RUNNING_TIME`             | 11    | 8     |                                |
| `CALORIES`                 | 13    | 4     |                                |
| `LAP_TIME`                 | 15    | 4     |                                |
| `ACTUAL_KPH`               | 16    | 4     |                                |
| `ACTUAL_INCLINE`           | 17    | 4     |                                |
| `ACTUAL_DISTANCE`          | 18    | 8     |                                |
| `RECOVERABLE_CONSOLE_TIME` | 19    | 4     |                                |
| `CURRENT_CALORIES`         | 20    | 4     |                                |

A write to any read-only register is a programming error, not a value the machine will accept: the
real implementation throws "trying to write to a read only field" (`th/a.java`). `FitProCodec`
mirrors that — `encodeRegisterWrite` throws `IllegalArgumentException` for a read-only register.

## WorkoutMode enum

Values are verified (`yh/n.java`). The numeric value is **not** the ordinal — `PAUSE_OVERRIDE` is
20 — so the wire value must always be taken from the value, never the position.

| Mode              | Value | Mode             | Value |
|-------------------|-------|------------------|-------|
| `UNKNOWN`         | 0     | `DEMO`           | 9     |
| `IDLE`            | 1     | `WARM_UP`        | 10    |
| `RUNNING`         | 2     | `COOL_DOWN`      | 11    |
| `PAUSE`           | 3     | `SLEEP`          | 12    |
| `RESULTS`         | 4     | `RESUME`         | 13    |
| `DEBUG`           | 5     | `LOCKED`         | 14    |
| `LOG`             | 6     | `PAUSE_OVERRIDE` | 20    |
| `MAINTENANCE`     | 7     |                  |       |
| `DMK`             | 8     |                  |       |

An unrecognised value decodes to `UNKNOWN` rather than throwing, so a firmware revision that
introduces a new mode cannot crash telemetry decoding.

## Value serializers and their endianness

These are verified from uncorrupted serializers. **The endianness is deliberately mixed. Do not
"tidy" it into consistency — that would corrupt every value.**

| Value        | Serializer                        | Endianness      | Source   |
|--------------|-----------------------------------|-----------------|----------|
| Speed        | `(short)(kph * 100)`, 2 bytes     | **big-endian**  | `g7/z`   |
| Incline      | `(int)(grade * 100)`, 4 bytes     | little-endian   | `g7/s`   |
| WorkoutMode  | 1 byte = value                    | n/a             | `g7/v`   |
| Distance     | 4-byte int                        | little-endian   | `uh/d`   |

Speed is the only big-endian value. Incline is a signed 32-bit int, so a decline (negative grade)
is carried in two's complement — sign handling is the easiest thing to get wrong and is pinned by a
`-3.0%` test in `FitProCodecTest`.

## Frame envelopes

Verified (`th/q.java`, `th/o.java`, `yh/a.java`, `vh/b.java`):

- **FitPro2 envelope:** `[0x02, 0x04, 0x02, len] + payload`, with a 400 ms response timeout.
- **BLE chunking:** a lead packet `[0xFE, 0x02, len, chunkCount]`, then up to 18 payload bytes per
  20-byte data packet `[idx, dataLen, <=18 payload bytes]`; the final data packet's index is `0xFF`.
- **Frame byte 0** is the device address (`TREADMILL = 4`).
- **Frame byte 3** is a status (`DONE = 2`, `CMD_NOT_SUPPORTED = 1`).

The envelopes themselves — the prefix bytes, the length byte, the chunk layout — are verified. What
they *wrap* is not; see the next section.

## Transports

Both transports share the same frame / register / serializer path.

- **USB serial** (wired console): `glassos_sindarin_usb`; `bulkTransfer` on endpoints 0/1; vendor
  lock **ICON = 8508**.
- **BLE** (wireless console): writes the same frames to
  - **DeviceTx** `00001535-1412-efde-1523-785feabcd123` — **write-only**,
  - **DeviceRx** `00001534-…` — **notify**, for console responses,
  - notifications enabled via the **CCC descriptor** `2902`.

`FitProCodec` implements none of these. It has no transport code and no Android I/O imports, and it
is not referenced by `OverlayService`, `SpikeBridge`, `MachineLink`, `GlassOsClient`, or any
Service. It is a leaf.

## What is NOT verified

> The register map and the value serializers were transcribed from clean decompiled code. The
> **core send routine was not** — `th/n`, `hc/g0`, and `wh/c` came out of JADX corrupted.

Two things are therefore unverified and must not be trusted on the wire:

1. **The in-frame byte order** — how the register descriptor and value are laid out *inside* the
   payload the envelope wraps.
2. **Any CRC or checksum** — whether one exists, where it sits, and how it is computed.

Because of this, `FitProCodec.encodeRegisterWrite` and the framing helpers are marked **UNVERIFIED**
in their KDoc, and the codec is deliberately not connected to a transport. Encoding a frame as a
byte array in a unit test is fine. Transmitting one built on an unverified layout — with the belt
able to move as a result — is not.

## One finding, stated plainly

**Distance is read from a machine register; it is not integrated from speed × time by the app.**

The machine reports distance directly through `DISTANCE` (field 6, 4-byte little-endian int),
`CURRENT_DISTANCE` (field 4), and `ACTUAL_DISTANCE` (field 18, 8 bytes). All three are read-only
telemetry (`sh/a.java`), decoded from raw machine bytes (`uh/d.java`). The app does not accumulate
distance locally from speed and elapsed time.

This settles a question the project had left open: to show live distance, read the machine register.
Only a *virtual* console (one pretending to be a machine) would need to maintain its own distance
accumulator, and that is explicitly not what the real machine path does.

## What must happen before this is ever used

This is a hard gate, in order:

1. **Capture real traffic.** Record actual USB serial and BLE frames from a live console driving the
   machine. Nothing below can be done without captures.
2. **Confirm the framing.** Verify the in-frame byte order of the register descriptor and value
   against the captures — the part JADX corrupted.
3. **Confirm the checksum.** Determine whether a CRC/checksum exists, where it sits, and how it is
   computed; validate it against the captures.
4. **Only then, and only with the user's explicit authorisation, consider a write path.** A read
   path (telemetry only) is the natural first step and carries none of the movement risk. A write
   path may not be built or wired in without that explicit sign-off, because it is the one thing
   that can move the belt.

Until every one of these is done, `FitProCodec` stays what it is: a tested, documented, transport-less
codec that cannot send a byte to anything.
