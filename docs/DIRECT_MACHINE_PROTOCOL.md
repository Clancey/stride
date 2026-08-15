# Direct machine protocol — the FitPro register path

Stride can drive the treadmill two ways, chosen by the `transport` setting:

- **GLASSOS** — talk to iFit's GlassOS gRPC server on `localhost:54321` and let it command the
  hardware. This is the default.
- **DIRECT** — speak the FitPro register protocol to the machine ourselves, over USB serial or BLE,
  with GlassOS entirely out of the loop.

This document describes the DIRECT path: the wire format, the connect handshake, the register map,
and the places where the two transports have to be made to mean the same thing.

Everything marked VERIFIED below was read out of decompiled GlassOS 6.14.6 (JADX for Java, apktool
for smali where JADX dropped detail). Anything not so marked is inference and is called out.

> **An earlier revision of this document was wrong in seven specific ways**, all of which had been
> labelled "VERIFIED". They are listed at the end under "Corrections", because the wrong values are
> the kind that produce *a different valid command* rather than a parse failure — if you have code
> or notes derived from the old version, check them against that list.

## Design rule: the transports must be answerable to the same questions

`MachineCommands` is the interface both transports implement. If the app can ask it, it is on that
interface, and both sides answer — that is what makes DIRECT a drop-in rather than a subset.

Two consequences worth stating, because they are easy to undo:

- **Nothing branches on the transport.** There is no `if (transport == DIRECT)` in the control path.
  `MachineLink` → `MachineCoordinator.connectConsole()` → `commands?.connect()` is automatically
  correct because `connect()` is on the interface.
- **In DIRECT, no GlassOS client exists at all.** `GlassOsClient` is constructed in exactly one
  place — the GLASSOS branch of `MachineLink.openTransport`. Not "constructed but unused": absent.
  So there is no socket, no credential load, and no startup `Connect` on the direct path, which is
  the requirement that the direct mode not talk to GlassOS *at all*.
- **Switching transports closes the old one.** `GlassOsClient.close()` is one-way and checked at the
  top of `postRaw`, so a reference captured by another thread before the switch cannot send after
  it. A closed client is replaced, never reopened.

`DirectTransportParityTest` contains a `Recorder : MachineCommands` that fails to compile if the
interface grows a method — which is the only reliable way to keep this true over time.

## Wire format — VERIFIED

### Register block (`vh/f.j(list, withValues)`)

The payload is a **bitmask of register ids**, not a sequence of per-register descriptors.

```
empty list -> [0x00]
maskBytes  = (maxFieldId / 8) + 1
out[0]     = maskBytes
out[1 + i] = OR of (1 << (fieldId % 8)) for every register with fieldId / 8 == i
if withValues: append each register's value, in ASCENDING fieldId order
```

### Body (`vh/f.g()`)

```
body = j(writes, withValues = true) ++ j(reads, withValues = false)
```

Writes first **with** values, then reads **without**. The asymmetry is real and the order matters.

### Frame (`vh/d.e()`)

```
frame[0]              = device address
frame[1]              = body.size + 4        (total frame length, including the checksum)
frame[2]              = command
frame[3..]            = body
frame[frame[1] - 1]   = SUM8 over frame[0 .. frame[1]-2]
```

The checksum is a plain 8-bit sum that wraps. Not a CRC, not an XOR.

### Response

```
[0] address   [1] length   [2] command   [3] status   [4..] values   [last] checksum
```

Values are packed contiguously from offset 4 in **ascending fieldId order**, and must fill the frame
exactly — leftover bytes mean the reply answers a different question than we asked, at which point
every value already decoded is a guess about which register it came from. `parseResponse` rejects
that case rather than returning a plausible wrong number.

Note the response header is **five** bytes of overhead (address, length, command, status, checksum)
against a request's four; the request has no status byte.

GlassOS does **not** verify the response checksum. Stride computes it, reports it, tolerates a bad
one for telemetry, and requires a good one to accept a write acknowledgement.

### Status — 0 is not success

```
DEV_NOT_SUPPORTED(0)  CMD_NOT_SUPPORTED(1)  DONE(2)  IN_PROGRESS(3)  FAILED(4)
TIME_LEFT(5)  UNKNOWN_FAILURE(7)  SECURITY_BLOCK(8)  COMM_FAILED(9)
```

**Success is `DONE` = 2.** A zero status is "this device does not exist".

## Link layer — the envelope is BLE-only

From `th/q`'s constructor: `if (format == 2)` the frame is wrapped in `[0x02, 0x04, 0x02, len]` and
given a 400 ms timeout; **otherwise the frame is written bare**. So:

| Transport | Envelope | Chunking |
|-----------|----------|----------|
| USB serial | none — bare frame | none |
| BLE | `[0x02, 0x04, 0x02, len]` | yes |

### BLE chunking (`th/o`)

Lead packet `[0xFE, 0x02, len, chunkCount]` — four bytes, **not** padded — where
`chunkCount = (len <= 18 ? 1 : ceil(len / 18)) + 1`. Then 20-byte data packets
`[idx, dataLen, ...up to 18 bytes]`, the last of which has index `0xFF`.

> JADX renders that ceiling as `Math.ceil(bArr.length / 18)`, which reads like an integer-division
> bug. **It is not.** The smali shows `int-to-float` / `div-float` around it; JADX dropped the
> conversions. This is the general hazard with this codebase — when a decompiled expression looks
> wrong, check the smali before "fixing" it.

### BLE reply framing (`th/q.g()`)

Replies are chunked too. The parser does `drop(26, concat(packets))`, and 26 decomposes as
20 (lead packet) + 2 (data header) + 4 (FitPro2 envelope). That tells us three things that are not
otherwise obvious: replies are chunked, reply packets are **padded to 20 bytes** with the real
length in byte `[1]`, and replies **carry the envelope** just as requests do.

### BLE characteristics — the names are reversed

The names are from the **device's** point of view, which is the opposite of what the host wants.
Confirmed in smali (`hc/o.smali`, around line 400):

| UUID | Name | What the **host** does |
|------|------|------------------------|
| `00001534-1412-efde-1523-785feabcd123` | `DeviceRx` | **writes here** |
| `00001535-1412-efde-1523-785feabcd123` | `DeviceTx` | **subscribes here** |

Service `00001533-1412-efde-1523-785feabcd123`; notifications enabled via CCC descriptor `2902`.

USB serial: `bulkTransfer` on endpoints 0/1, vendor **ICON = 8508**.

## The connect handshake — VERIFIED (`xh/n0.F()`)

There is a `CONNECT(4)` and a `DISCONNECT(5)` in the command enum. **Neither is ever sent** — JADX
labels them "Fake field, exist only in values array". Connecting is a handshake, not a command.

1. Start the send loop.
2. `DEVICE_INFO` to **MAIN (2)**.
3. Reuse the device record that came back — including its address (see below).
4. Batch `SYSTEM_INFO`, `VERSION_INFO`, `SERIAL_NUMBER`.
5. `SUPPORTED_DEVICES`, `SUPPORTED_COMMANDS`.
6. `VERIFY_SECURITY`, if the device asked for it.
7. Poll `READ_WRITE_DATA` every 100 ms.

### DEVICE_INFO reply layout

| Bytes | Meaning |
|-------|---------|
| `[4]` | hardware version |
| `[5]` | firmware version |
| `[6..9]` | model, int32 LE |
| `[10..11]` | brand, int16 LE |
| `[12]` | supported-register mask byte count |
| `[13..]` | supported-register mask |

**That mask is how the machine answers "does incline work? does the fan work?"** — which is exactly
the question the settings screen used to answer with a hardcoded guess. It is now capability-driven:
the console says what it implements and the UI reports that.

### Addressing

Frames go to **MAIN (2)**, not to a treadmill-specific address. `DEVICE_INFO` is asked on MAIN, and
its parser builds the device record from the address it *asked* (`this.f16654a`); the register
reader then takes its address from that same record (`vh/f`'s `super(bVar.f18899a)`). A hardcoded
treadmill address would be talking to a device iFit never talks to.

## Register map — VERIFIED (`sh/a.java:145`)

**The ordinal is not the field id** past `ACTUAL_INCLINE`, and the trailing `4`/`8`/`12` in the
decompiled constructor calls is the **Kotlin default-argument mask**, not a byte length. Both of
those were misread in the previous revision of this document.

| Register | Field | Width | Access |
|----------|-------|-------|--------|
| `KPH` | 0 | 2 | write |
| `GRADE` | 1 | 2 | write |
| `WATTS` | 3 | 2 | read |
| `CURRENT_DISTANCE` | 4 | 4 | read |
| `FAN_SPEED` | 8 | 1 | write |
| `PULSE` | 10 | 4 | write |
| `RUNNING_TIME` | 11 | 4 | read |
| `WORKOUT_MODE` | 12 | 1 | write |
| `ACTUAL_KPH` | 16 | 2 | read |
| `ACTUAL_INCLINE` | 17 | 2 | read |
| `ACTUAL_DISTANCE` | 19 | 4 | read |
| `CURRENT_CALORIES` | 21 | 4 | read |
| `MAX_GRADE` / `MIN_GRADE` | 27 / 28 | 2 | read |
| `MAX_KPH` / `MIN_KPH` | 30 / 31 | 2 | read |
| `START_REQUESTED` | 96 | 1 | read |
| `FAN_STATE` | 98 | 1 | write |

`MIN_KPH`/`MAX_KPH` and `MIN_GRADE`/`MAX_GRADE` are what answer "what speeds and inclines are
available" on the direct path — the machine's own limits, read from the machine, rather than a table
we maintain. `MachineLimits` carries them; `MachineCoordinator.connectConsole()` refreshes them on
*every* connect, not only the first, so a transport switch cannot leave stale limits clamping the
rider.

The read block is a plain bitmask with no read-only restriction, so a writable register such as
`WORKOUT_MODE` is perfectly readable. Only writes are access-checked (`th/a`), and writing a
read-only register throws rather than being sent.

### Value serializers

| Value | Encoding | Endianness | Source |
|-------|----------|------------|--------|
| Speed | `(short)(kph * 100)`, 2 bytes | **little-endian** | `g7/z` |
| Incline | `(short)(grade * 100)`, 2 bytes **signed** | little-endian | `g7/s` |
| WorkoutMode | 1 byte | n/a | `g7/v` |
| Ints (distance, time, calories) | 4 bytes | little-endian | `uh/d` |

Incline is signed, so a decline is two's complement; that is pinned by a `-3.0%` test in
`FitProCodecTest`.

> **GlassOS's own speed decoder truncates.** `g7/z.h` does `Double.valueOf(int / 100)` — integer
> division — while incline uses `/ 100.0d`. That is a real defect in iFit's code, not in ours. It is
> why `FitProProbe`'s cross-check tolerance against GlassOS-reported speed is 0.75 mph rather than
> something tight.

## Two enums that look alike and are not

FitPro `WorkoutMode` and GlassOS `WorkoutState` use **different numbers for the same states**:

| State | FitPro | GlassOS |
|-------|--------|---------|
| IDLE | 1 | 1 |
| RUNNING | **2** | **3** |
| PAUSED | **3** | **4** |
| RESULTS | **4** | **5** |

FitPro's `RUNNING` (2) is GlassOS's `IDLE` (2's neighbour in the other enum). Casting an int across
that boundary would silently report a running treadmill as idle. `FitProValues` converts explicitly
in both directions and `DirectTransportParityTest` pins the mapping.

## `workoutId` — VERIFIED, and synthesised on the direct path

Every GlassOS metric response carries a `workoutID` in field 1 (`nb/r3` — `GetSpeedResponse`:
`WORKOUTID = 1`, `TIMESECONDS = 2`, `LASTKPH = 3`). It is built from `xl/b`, whose constructor
parameter is named `workoutInstanceID`.

`am/j` is what settles its meaning. It builds those responses two ways:

- `p(workoutInstanceID, timeSeconds, value)` — during a workout;
- `q()` — which passes `CoreConstants.EMPTY_STRING` and `0` — when there is not one.

So the field is **not a value to display. It is a discriminator: non-empty exactly when a workout
instance exists.** It has to exist because proto3 omits zero values — a missing speed *inside* a
workout is a measured 0.0, and the same missing speed *outside* one means nothing is measuring. On
the wire those two are otherwise identical.

FitPro has no equivalent register (`sh/a` has `WORKOUT_MODE` and `START_REQUESTED` and nothing
resembling an instance id), so this is a GlassOS-layer construct. The direct path therefore
synthesises the *meaning* rather than pretending to have iFit's identifier:

- a non-empty token while `WORKOUT_MODE` says a workout instance is live;
- `null` when it says there is not one;
- a **new** token each time a workout begins, so a caller comparing two readings can tell a second
  run from a continuation of the first — the other thing an instance id is for.

`RESULTS` counts as still belonging to the instance: the totals on screen are that workout's, and
GlassOS keeps reporting them there too. `IDLE`, `SLEEP`, `LOCKED`, `DEMO` and the service modes do
not.

**`UNKNOWN` holds rather than clears.** A `WORKOUT_MODE` register that did not answer decodes to
`UNKNOWN`, so `UNKNOWN` is a failed read, not a console state. Clearing on it would end the instance
every time one register dropped off a noisy link and mint a fresh token on the next good poll,
turning one run into two as far as any caller comparing tokens is concerned. Holding can only
preserve an id we already had evidence for, never invent one — so it cannot fail open. This is the
same rule as `!= false` elsewhere in this code: unknown is not refusal.

Tokens are `direct-N` from a process-wide counter — counted rather than random so they are
reproducible in a log and in a test, and shared so two consoles in one process cannot both be
`direct-1`. `WorkoutInstanceIdTest` covers all of the above.

## Telemetry parity

Every field of the shared `GlassOsClient.Snapshot` is populated on the direct path. One is actually
better: `fanLevel` is a real value from `FAN_STATE`, where GlassOS returns null.

Two remain **inferred, not verified**: the unit of `CURRENT_DISTANCE` (assumed metres) and of
`RUNNING_TIME` (assumed seconds). They are decoded as such and cross-checked by `FitProProbe`, but
no capture confirms them.

## Corrections to the previous revision

Each of these was previously stated as verified and was wrong. They are listed because a wrong value
here does not fail loudly — it produces a different, valid command.

| Previously claimed | Actually |
|--------------------|----------|
| Speed is big-endian | **Little-endian** |
| Incline is a 4-byte int | **2 bytes, signed** |
| Byte lengths are 4 / 8 / 12 | Those are **Kotlin default-arg masks**, not lengths |
| `ACTUAL_DISTANCE` is field 18 | **19** |
| Payload is `[fieldId] + value` per register | **`[maskCount][mask bytes][values]`** |
| Checksum unknown, possibly a CRC | **SUM8**, last byte |
| Fan is register 8 | `FAN_SPEED` is 8, but **`FAN_STATE` = 98** is the one that works |

That last one is the likely reason the fan appeared not to work, and it is the origin of this whole
piece of work.

## What is still open

- **None of this has been run against real hardware.** The remaining step is a sanity readback, not
  a search — the format is recovered, not guessed.
- Distance and elapsed-time units are inferred (above).
- `SUPPORTED_DEVICES` is requested during the handshake but the reply is not checked, so there is no
  model or device-type allowlist before commands are bound.
