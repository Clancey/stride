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

**The endpoints are not always declared bulk — VERIFIED against a real X22i.** GlassOS 6.14.6, the
source for the rest of this section, runs on a product-3 (FitPro2) console and its endpoints were
never independently checked against real hardware. A product-2 (FitPro1) console does not match:
`dumpsys usb` on a NordicTrack X22i shows it enumerating as `class=3` ("ICON Generic HID") with two
**interrupt** endpoints (`type=3`, i.e. `USB_ENDPOINT_XFER_INT`), not bulk. `UsbSerialTransport.open`
originally matched bulk endpoints only, so it found no usable interface on this console and returned
null before a handshake was ever attempted — the direct path read as "no console" on exactly the
hardware this whole feature was built for. It now accepts either transfer type: Android's
`bulkTransfer` reaches usbfs's `USBDEVFS_BULK` ioctl, which the kernel honours for interrupt
endpoints as well as bulk ones, so no change was needed to the transfer calls themselves once the
endpoint filter stopped excluding them.

## The connect handshake — VERIFIED (`xh/n0.F()`)

There is a `CONNECT(4)` and a `DISCONNECT(5)` in the command enum. **Neither is ever sent** — JADX
labels them "Fake field, exist only in values array". Connecting is a handshake, not a command.

1. Start the send loop.
2. `DEVICE_INFO` to **MAIN (2)**.
3. Reuse the device record that came back — including its address (see below).
4. `SUPPORTED_DEVICES`, `SUPPORTED_COMMANDS`.
5. `SYSTEM_INFO`, `VERSION_INFO`, `SERIAL_NUMBER` — **each only if step 4 advertised it**.
6. `VERIFY_SECURITY`, if the device asked for it.
7. Poll `READ_WRITE_DATA` every 100 ms.

Steps 4 and 5 were previously documented the other way round. FitPro1's `Connect` fetches the device
tree immediately after `DEVICE_INFO` and then loops the interrogation commands under
`if (PrimaryDevice.SupportedCommands.Contains(item.Key))`, so the order is load-bearing: the console
is never asked for something it has just said it does not implement.

### Command request bodies — not all of them are empty

Every command declares a `ContentLength`, and Stride assumed for a long time that the handshake
commands were all zero. Two are not:

| Command | Content length | Body |
|---------|---------------|------|
| `DEVICE_INFO`, `SERIAL_NUMBER`, `SUPPORTED_COMMANDS`, `SUPPORTED_DEVICES` | 0 | — |
| `SYSTEM_INFO` | 2 | `[fetchMcuName, 0]` |
| `VERSION_INFO` | 2 | `[fetchMcuName, fetchConsoleName]` |
| `VERIFY_SECURITY` | 36 | 32-byte hash ++ 4-byte LE key |

iFit constructs both info commands with the flags defaulted to false, so the correct body is two
zero bytes — "do not also send me the name".

This was not cosmetic. A NordicTrack X22i in the field completed `DEVICE_INFO` (which we framed
correctly) and then went silent for the rest of the session, starting with `SYSTEM_INFO` — the first
command we under-filled. Note that the silence of the *later*, correctly-framed commands is not
explained by the bad frame on its own: this is a USB HID interrupt endpoint, where every report is
its own framed transfer, so there is no shared byte stream to desync. The likely explanation is that
the malformed frame wedges the console's command processor until the link is re-established, but
that mechanism is inferred rather than confirmed.

### DEVICE_INFO reply layout

Names taken from `yh/b.toString()`, which spells out its own constructor order, and matched against
the fill order in `vh/e.a` case 0.

| Bytes | Meaning |
|-------|---------|
| `[4]` | **software** version |
| `[5]` | **hardware** version |
| `[6..9]` | **serial number**, int32 LE |
| `[10..11]` | manufacturer, int16 LE |
| `[12]` | supported-register mask byte count |
| `[13..]` | supported-register mask |

An earlier revision of this document had the first two the other way round and called `[6..9]` a
model number. That was harmless while the versions were only logged, and stopped being harmless the
moment the software version acquired a meaning — see the security note below.

**That mask is how the machine answers "does incline work? does the fan work?"** — which is exactly
the question the settings screen used to answer with a hardcoded guess. It is now capability-driven:
the console says what it implements and the UI reports that.

The mask is indexed by **field id**, not by ordinal: `vh/e` sets bit `(i * 8) + bit` and matches it
against `sh.a.D`, which is the field id. This is what makes it safe to use the mask to filter read
lists, which the direct path must do — values are packed contiguously with no per-value tags, so a
single unsupported register in a read list makes the whole response unparseable.

### VERIFY_SECURITY

A console whose software version is **above 75** will not accept writes until it has been shown a
32-byte hash. The guard is explicit in `xh/n0.smali`:

```
iget v5, v0, Lyh/b;->b:I    # softwareVersion
const/16 v13, 0x4b          # 75
if-le v5, v13, :cond_c      # <= 75 skips the security branch entirely
```

and stated again in FitPro1's own source as `if (PrimaryDevice.SoftwareVersion > 75) await Unlock()`.

This document used to call it "the one thing the direct path cannot do", on the reasoning that the
blob was a secret Stride had no way to hold. That was wrong, and only looked right for as long as
the algorithm was unknown. It is not a secret at all: it is a pure function of three numbers the
console itself reports during the handshake.

```
hash[b] = b + 1                                     for b in 0..31
if bit b of serialNumber is set:
    hash[b] ^= (byte)( (b < 16 ? rotate(partNumber) : partNumber) >> b )
else:
    hash[b] ^= (byte)( (b + 1) * (b + modelNumber) )

where rotate(p) = (p << 16) | (p >> 16)
```

Every shift is **arithmetic** (C# `>>` on `int`), the multiply is allowed to overflow, and each
result is truncated to a byte. The inputs are the serial number from `DEVICE_INFO` and the part and
model numbers from `SYSTEM_INFO`.

The body is that hash followed by a 4-byte little-endian key, which is `8 × masterLibraryVersion`
from `VERSION_INFO` — a number the console also told us. The reply is a status plus one key byte;
only status `DONE` means unlocked.

Two details are easy to miss and both break the handshake silently:

- `SYSTEM_INFO`'s parser rewrites one console's part number
  (`if (partNumber == 370357 && model == 39915) partNumber = 374677`) **before** the hash reads it.
- The master library version is a **single byte**; the two bytes after it are the build number.

A console can also drop its unlock mid-session. iFit handles a `SECURITY_BLOCK` status on any
command by unlocking again and reissuing it, and so does Stride — once, since the commands that flow
through that path are absolute setpoints and are safe to repeat.

### Addressing

Frames go to **MAIN (2)**, not to a treadmill-specific address. `DEVICE_INFO` is asked on MAIN, and
its parser builds the device record from the address it *asked* (`this.f16654a`); the register
reader then takes its address from that same record (`vh/f`'s `super(bVar.f18899a)`). A hardcoded
treadmill address would be talking to a device iFit never talks to.

**That covers outgoing frames only, and confusing it with reply validation was a real bug on real
hardware.** `parseResponse`'s `expectAddress` check requires an *incoming* reply's own address byte
to equal an address the caller supplies. On a NordicTrack X22i (FitPro1, software 83), every reply
observed — `DEVICE_INFO`, `SYSTEM_INFO`, `VERSION_INFO`, and every register read/write — came back
stamped with the console's own bus address (**5**) instead of the address asked (MAIN/2). GlassOS-era
hardware apparently echoes the address it was asked, closely enough that this was never seen to
fail; this console does not. Because `expectAddress` used the *outgoing* address for both directions,
every read and write after the handshake was silently rejected as a malformed/unanswering reply,
producing an unbreakable reconnect loop that looked identical to "no console attached."

Fixed by keeping the reply's own address from `DEVICE_INFO`'s un-overridden parse in a separate
`DirectMachineSession.replyAddress`, and validating subsequent replies against that instead of the
outgoing address. Outgoing frames are unaffected — they still go to MAIN, matching the paragraph
above. Confirmed live on that same X22i: the handshake now completes and `MachineLink` holds a
stable `Attached` connection with live telemetry, where it previously looped `NoAnswer` forever.

**What that check is, and what it is not.** It is *peer validation*: it rejects a frame from a device
that is not the one we handshook with. It is **not** request/reply correlation, which this document
and the code both claimed for a while. FitPro carries no request id, and two answers from the *same*
console to two successive `READ_WRITE_DATA` frames carry an identical address byte and an identical
command byte — so a late reply to an earlier frame is precisely what these bytes cannot separate, and
on a console that stamps every reply the same way that is every frame it sends. Real correlation
would need the transport to drain or quarantine what it has not matched after a timeout, which it
does not do today. Peer validation is still worth having, and `replyAddress` is what makes it work on
this hardware.

The **probe** validates its reply the same way. `FitProProbe.confirm` takes the address the answer
must come from and `DirectMachineSession.connect` passes `replyAddress ?: address`, so the one
exchange that moves `Stage` to `LINK_CONFIRMED` — and `LINK_CONFIRMED` is what lets
`DirectMachineCommands` encode a write at all, and where `MachineLimits` is read from — is held to
the same standard as the reads and writes that follow it. Before that, on a shared bus, `DEVICE_INFO`
could establish the peer as one device while a different responder supplied the reply that unlocked
writes and the limits that feed the clamp. A wrong-source reply is retried exactly once, because
nothing re-runs the probe while the handshake stands, so a single crossed frame would otherwise leave
a working console unable to write until the transport dropped. Not observed on hardware — this is
defence in depth, bounded before and after by the probe's own plausibility checks.

`parseDeviceInfo` is held to `replyMatches` (command byte, declared length, checksum) before the
session learns an address from it, and its supported-register mask is bounded by the frame's declared
length rather than by the buffer, so a console that under-declares cannot have its checksum byte and
the transport's zero padding decoded as registers it never claimed.

The other post-`DEVICE_INFO` handshake replies — `SUPPORTED_COMMANDS`, `SYSTEM_INFO`, `VERSION_INFO`,
`SERIAL_NUMBER`, `VERIFY_SECURITY` — still ignore byte 0. That is a deliberate omission, not an
oversight: none of them can authorise a write, since the write gate is the probe alone, and a foreign
reply on any of them can only *deny* — a poisoned `SYSTEM_INFO` yields a wrong security hash and a
`SECURITY_BLOCK`. Validating them would add a second way to fail the unlock on a console above
software 75, which is a way to lose direct control entirely and is not testable without one of those
consoles in hand.

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
| `IDLE_MODE_LOCKOUT` | 95 | 1 | write |
| `START_REQUESTED` | 96 | 1 | read |
| `FAN_STATE` | 98 | 1 | write |
| `REQUIRE_START_REQUESTED` | 108 | 1 | write |

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

### A third pair — and this one is a decoy

`ControlType` in `GlassOsClient` was flagged during review as off-by-one against `vf/a.java`
(`IFitControlType`: `unknown(0), gear(1), incline(2), mps(3)`). It is not. The **wire** enum is
`pb/e.java` — `CONTROL_TYPE_UNKNOWN(0), CONTROL_TYPE_INCLINE(1), CONTROL_TYPE_MPS(2)` — reached from
the `Control` message in `pb/b.java` (`type = 1, at = 2, value = 3`) through `pb.e.b(int)`. Stride
matches the protobuf exactly. `vf/a` is an internal Kotlin SDK type that never reaches the socket.

This is recorded because "correcting" it would have been invisible: incline and speed presets are
separated by filtering on this value, so shifting it by one swaps the two rails and throws nothing.
`DirectTransportParityTest` now pins the values with a comment pointing here.

### `RESUME` is sent, even though GlassOS declines to publish it

`xh/n0.p0()` is a 1:1 translation from GlassOS `ConsoleState` to FitPro `WorkoutMode`, and
`ConsoleState.RESUME` maps to `WorkoutMode.RESUME` (13) — not to `RUNNING`. Stride was sending
`RUNNING` on resume.

The confusing part is `vh/f.m()`, which returns early for `(WORKOUT_MODE, RESUME)` and reads like a
"never send RESUME" guard. It is not: `m()` returns `void` and only touches a metric key, so it is
the **publisher**. The frame builders are `g()` and `j()`, and neither excludes RESUME. GlassOS puts
RESUME on the wire and merely declines to surface it as a console state — consistent with the read
side, which maps RESUME to `WORKOUT_RUNNING`.

`resume()` now writes RESUME and falls back to RUNNING only if the console refuses, so a machine that
does not implement the mode still resumes.

### Ending a workout writes zero twice — deliberately

`stop()` is one frame carrying `KPH = 0` and `WORKOUT_MODE = IDLE` together, and that has not
changed. What has is what follows it, and only at a definitive **End workout** — never at a pause.
An end now sends, in this order and each as its own queued job behind the stop:

| Order | Write | Sent when |
|---|---|---|
| 1 | `KPH = 0`, `WORKOUT_MODE = IDLE` | always — the stop itself, preempting the queue |
| 2 | `KPH = 0` | always |
| 3 | `GRADE = 0` | only when telemetry shows the belt at rest **and** this console has proved its speed register reports motion |
| 4 | `FAN_STATE = OFF` | always |

The second zero looks redundant and is the point. If the stop frame landed, the console is idle, it
will most likely refuse 2, and the cost is a log line. If the stop frame was **lost** — a dropped
BLE chunk, a USB board that left the bus (see the hazard below) — then the console is still in a
workout, it accepts 2, and that is the write that stops the treadmill. The trade is one round trip
on the ending path against a belt left running under an app showing "Start workout".

`GRADE` is gated on **observed** speed, from `ACTUAL_KPH` (16), and not on 2 being acknowledged.
That distinction was a bug before it was a rule: an ack means a console took a register write, and
by the argument above it is precisely the *lost-stop* branch where 2 is accepted — so an ack-gated
deck movement would fire only while the belt was still running. A null reading (stale snapshot, or
a machine that could not be asked) is not permission either. The deck therefore stays put unless
Stride can see a stopped belt, which after a pause it normally can, and mid-run it cannot.

**A zero from `ACTUAL_KPH` is not automatically permission either — see issue #34.** On the X22i
that register reads exactly `0x0000` on every poll while a rider walks at 4 mph, with
`CURRENT_DISTANCE` accumulating the real pace beside it. It is a confident, well-formed, entirely
plausible zero — so a null check does not catch it, and a gate built only on "speed reads zero"
flattens the deck under a moving belt on that machine.

Two things make it unrepairable by asking differently. **iFit never reads that register on a
treadmill**: `SpeedMetric` selects `Kph` (field 0, the commanded setpoint) for belt-based consoles
and `ActualKph` (16) only for non-belt ones, so iFit showing a correct speed there says nothing
about whether field 16 works. And **there is no per-field validity marker** — `SetResponseBytes`
checks only command status and total length, then consumes raw bytes in field order, so "the value
is zero" and "I do not have this value" are the same bytes.

The gate therefore also requires that this console has, at some point on this link, reported a
speed above the moving threshold at all. Until it has, its zero is indistinguishable from a
register stuck at zero and is treated as worth nothing. On an X22i exhibiting #34 the deck is never
flattened, which is exactly where it sat before any of this existed.

`Rpm` (field 5) is the obvious next avenue for a real motion signal on such a console: it is
**read-only**, so it is a machine measurement rather than a setpoint, and it may carry roller or
motor movement where field 16 is dead. Nobody has checked whether the X22i populates it, so nothing
depends on it yet. Corroborating against `CURRENT_DISTANCE` failing to advance would serve too.
Either would let that condition be strengthened — it must not be dropped.

`GRADE = 0` is clamped like any other incline, so a machine whose reported grade range excludes
zero gets as flat as it goes rather than a value it would refuse — the same coercion `startWorkout`
applies on the way in.

None of this is stop *confirmation*. A stop is done on ack plus observed deceleration in telemetry
(`docs/PLAN.md` §5.4). Commanded `KPH` (0) and observed `ACTUAL_KPH` (16) are different fields, and
no amount of writing the first is evidence about the second.

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
| `DEVICE_INFO[4]` is hardware, `[5]` firmware, `[6..9]` model | **`[4]` software, `[5]` hardware, `[6..9]` serial** |
| `resume()` writes `RUNNING` | **`RESUME` (13)**, with `RUNNING` only as a fallback |
| Auto fan support is implied by the fan register being present | **Nothing on the wire carries it** — it is a per-console config field, so the only honest answer is to try it once |

That fan one is the likely reason the fan appeared not to work, and it is the origin of this whole
piece of work.

One claim was checked and found **already correct**: `ControlType`. It is listed under "two enums
that look alike" above rather than here, because the trap is that it looks wrong.

## What is still open

- **Now run against real hardware — a NordicTrack X22i (FitPro1, software 83, hardware 1).**
  `DEVICE_INFO`, `SYSTEM_INFO`, `VERSION_INFO`, `SUPPORTED_COMMANDS`, `VERIFY_SECURITY`, and every
  register read confirmed working end to end after the addressing fix above. The remaining open item
  is starting a workout — see below.
- **A Commercial 1750 (FitPro2, USB product 3) is a different story**, and the two should not be read
  as one result. It answers frames but has never completed `DEVICE_INFO`; see "Run against real
  hardware" below for what it does instead and for the sync sequence it expects first.
- Distance and elapsed-time units are inferred (above).
- `SUPPORTED_DEVICES` is never requested — `connect` sends `SUPPORTED_COMMANDS` and then only
  `SYSTEM_INFO`, `VERSION_INFO` and `SERIAL_NUMBER`, and `parseSupportedDevices` has no callers — so
  there is no model or device-type allowlist before commands are bound.
- **Whether GlassOS's `StartNewWorkout` moves the belt by itself.** It does — measured on the real
  machine, it drove the console `IDLE → WARM_UP → WORKOUT` and started the belt at **1.0 mph** with
  no speed command sent by Stride, on a flat deck. `DirectMachineCommands.startWorkout` now
  reproduces that: mode first, then the opening speed and a zero grade in a second frame.

  The mode goes in its own frame deliberately. Values inside one register block are ordered by field
  id, so `KPH` (0) would reach the console *ahead* of `WORKOUT_MODE` (12) in a combined frame — a
  speed arriving while the machine is still idle, which is the case most likely to be discarded.

  What is *not* settled is whether the 1.0 mph is a constant or the machine's own floor. The machine
  it was measured on (17125) reports a 1.0 mph minimum, so one observation fits both readings. The
  implementation coerces 1 mph into the reported range, which agrees with both wherever they agree
  and sends an acceptable speed on a machine whose floor is higher.

  **On the X22i, `WORKOUT_MODE = RUNNING` is refused outright — `FAILED` (status 4), not
  `SECURITY_BLOCK`.** The console is genuinely answering, not silent: `DEVICE_INFO` and `VERIFY_SECURITY`
  succeed on the same connection, security key was attached, and `MachineLink` shows a stable `Attached`
  connection at the time of the attempt. Four candidate causes were tested live and each was ruled out
  by an *identical* `FAILED` reply — worth recording so nobody re-tests them:

  1. **Combined write.** `KPH` + `GRADE` + `WORKOUT_MODE` sent together in one frame, against the
     reasoning two paragraphs up — same refusal as mode alone.
  2. **An explicit session command.** `Command.CONNECT` (4) sent once after the handshake, on the
     theory that FitPro1 needs a step FitPro2/GlassOS's own client never sends (see "Command types"
     above — `CONNECT`/`DISCONNECT` are dead code in GlassOS). The console does not answer it at all —
     a timeout, not a refusal — so this console doesn't implement it either.
  3. **`WARM_UP` instead of `RUNNING`.** On the theory that FitPro1 wants the `IDLE → WARM_UP →
     WORKOUT` progression GlassOS observes automatically driven explicitly by the caller. Refused
     identically — never even reached the follow-up `RUNNING` write.
  4. **A stale security grant.** `authorisedWrite` only re-unlocks on an explicit `SECURITY_BLOCK`
     reply, so a security-related refusal reported as generic `FAILED` on this console generation
     would never trigger it. Forced a fully fresh `DEVICE_INFO` + `VERIFY_SECURITY` handshake
     immediately before the mode write — same refusal.

  That every attempt produces the same clean, immediate `FAILED` — never a crash, never a different
  status, never silence — rules out a framing bug, points at a real firmware-side precondition this
  protocol surface doesn't expose, and is not something visible from GlassOS's decompiled behaviour
  (GlassOS is FitPro2-only). The belt never moved in any of the four attempts.

  **This has since been answered, and the entry above is kept only so nobody re-tests those four.**
  `Sindarin.FitPro1` is no longer out of reach: `ifit-standalone.apk` bundles it as Xamarin/.NET
  assemblies (`assemblies/assemblies.blob`, an LZ4-block-compressed `XABA` AssemblyStore), and
  `Sindarin.FitPro1.Core` unpacks with `ilspycmd`. `FitPro1Console` writes two fields after unlock,
  before it treats a console as initialized for control, and Stride had never sent either:

  ```csharp
  bool flag = IsBitFieldSupported(BitField.RequireStartRequested);  // field 108
  SetRequireStartRequested(flag);
  bool idleModeLockout = !flag || !PrimaryDevice.Device.IsBeltBasedMachine();
  SetIdleModeLockout(idleModeLockout);                              // field 95
  ```

  Neither field has a GlassOS/FitPro2 binding, which is why every earlier pass over this protocol was
  blind to them. Field 96 (`START_REQUESTED`, read-only) already sat in this repo's table at the
  number that decompiled enum gives it, which is the cross-check that the numbering is the real wire
  numbering. `DirectMachineSession.initializeStartGate` now sends both, and the fifth avenue is the
  one that worked: the author's X22i accepted the init writes and the next `startWorkout()` drove the
  belt for about two minutes.

  Three things about that result are worth stating plainly rather than filing as settled:

  1. **It is a single live observation**, and it was taken against an earlier revision that sent both
     fields in *one* frame. It therefore no longer describes what Stride sends.
  2. **The writes are ordered, and the order is load-bearing.** Field 108 arms a gate; field 95
     removes one. Values inside one register block are ordered by field id (see the `startWorkout`
     note above), so batching them puts 95 *ahead* of 108 — backwards. They now go out as two frames,
     108 first, and 95 is only sent once 108 has been acknowledged, so every way the sequence can
     fail leaves the console more gated rather than less.

     This is what iFit does too, and the binary is unusually clear about it. Each setter goes through
     `FitnessConsoleBase.SetValue`, which starts its own `SetValueAsync`, and
     `FitPro1Console.SetValidatedValuesAsync` wraps that single value in its own `ReadWriteDataCmd`:
     two commands, two frames. `ReadWriteDataCmd` *does* sort ascending when several fields share one
     command (`OrderBy((int)x.BitField)`) — the same behaviour as `registerBlock` — so batching these
     two would reverse them in either implementation, and iFit declines to batch them.
     `FitProCommunicationGroup.CreateMessages` only chunks already-built bytes into 20-byte
     messages; it does no reordering.

     Two things Stride does here that iFit does **not**, both deliberate rather than copied:
     `InitializeConsole` awaits neither setter and aborts for neither, whereas Stride waits for the
     arming write before sending the lockout write; and iFit discards both results, whereas Stride
     propagates the outcome into `ConnectResult` and refuses control while the gate's state is
     unknown. A third: before clearing the lockout Stride reads `START_REQUESTED` (96) back and
     leaves the lockout alone unless the console says no start is pending — `connect()` runs
     unattended from launch and from every reconnect, and "Stride commands no motion" is a weaker
     claim than "no motion can result" on a console that is already holding a start request. All
     three are choices appropriate to a reimplementation that can move a belt, not reproductions of
     iFit's behaviour.

     Stated precisely, because the loose version is wrong: the guarantee is *not* that every failure
     leaves the console more gated than it started — if the lockout write's reply is lost after it
     landed, the result is the ordinary `(108=1, 95=0)`. The guarantee is that **the lockout is never
     cleared unless the gate was armed first**, which is what excludes the one combination worse than
     doing nothing.
  3. **`IsBeltBasedMachine()` is assumed, not read.** `DeviceExtensions.IsBeltBasedMachine` answers
     it from the primary device in `DeviceInfoCmd`, and accepts `Treadmill` **and**
     `InclineTrainer` — the X22i is an incline trainer, so hardcoding true is right for this console
     and wrong in general. Stride has no primary device to consult: `DeviceInfo.address` is the bus
     address a reply was stamped with (5 on the X22i), not a device type, and `SUPPORTED_DEVICES` is
     never requested (see the separate entry above). `DirectMachineSession.BELT_BASED_MACHINE` names
     the assumption; FitPro1 equipment that is not belt-based needs the real conditional and would
     send `IDLE_MODE_LOCKOUT = 1`.

  The init is gated on `Variant.FITPRO1` *and* on the console reporting field 108. Over USB that is
  two independent gates — `variantOf` reads the product id, and a 1750 resolves to `FITPRO2` — so it
  is a structural no-op on GlassOS-era consoles. Over BLE it is one gate, not two:
  `BleTransport.variant` is hardcoded `FITPRO1` and derives nothing from the device, so a
  BLE-attached console is held by the capability check alone. Re-confirmation on an X22i against the
  two-frame sequence is still wanted.
- The preset **ladder step** (1.0) is invented. No preset register exists in FitPro, so the direct
  path must synthesise the quick picks; the endpoints come from the machine's own MIN/MAX registers,
  but nothing corroborates the spacing between them.

## Run against real hardware — a Commercial 1750 (FITPRO2, USB product 3)

The first readback against a live console. Three things were settled, and none of them is the wire
format: the format was never reached.

**The board.** `vendor 8508 / product 3`, `iFIT-LargeX`, one interface of class 255 (vendor
specific) carrying an **interrupt** pipe each way, both 64-byte, `bInterval 1`. No bulk endpoints at
all — so [`isDataPipeType`] accepting interrupt endpoints is what makes this device reachable, not a
FitPro1-only allowance.

**`bulkTransfer` drives those interrupt endpoints.** Measured, not assumed: once the interface was
genuinely Stride's, ordinary `bulkTransfer` moved frames both ways and the console answered. This is
`USBDEVFS_BULK` being turned into an interrupt URB by `usb_bulk_msg`, which is what the code claimed
and is now confirmed on this kernel. A `UsbRequest` path exists behind it as a fallback for a kernel
where that does not hold; on this console it is never reached.

**The interface is the whole fight, and iFit wins it by default.** `com.ifit.glassos_service` holds
the console's USB interface from boot. While it does:

- `openDevice` succeeds, `claimInterface(force = true)` returns true, and then **every transfer
  fails instantly** — not a timeout, an immediate refusal, on both `bulkTransfer` and `UsbRequest`.
  From the rider's side this is indistinguishable from an absent treadmill, which is why
  `describeBus` now reports "opened it but could not move a single byte" as its own case.
- Forcing the claim **breaks iFit until the console is rebooted.** Android's `force` issues
  `USBDEVFS_DISCONNECT` and nothing ever issues `USBDEVFS_CONNECT`, so GlassOS lost its link to the
  lower board and reported `DISCONNECTED` from then on. Restarting `glassos_service` did not recover
  it; a reboot did. `FitProTransport.close` carried this as a suspicion marked "very likely
  harmless". It is neither.

  Hence: claim plainly first and force only as a fallback, and say so in the opt-in.

**And then the console refuses the protocol.** With the link genuinely working, `DEVICE_INFO`
returns `CMD_NOT_SUPPORTED` from `ADDRESS_MAIN` and `ADDRESS_TREADMILL` alike. The console is
answering well-formed frames and declining the command.

That is the outcome [`Variant.FITPRO2`] already warned about in writing: this codec is the console
talking **down to its motor board**, and a FitPro2 console presenting itself on USB is the
app-facing link — `Sindarin.FitPro2.Core`'s `[communicationType][device|command][payloadLength]
[payload]`, a different wire with no checksum. Selecting the register codec for product id 3 was
recorded as unconfirmed; it is now confirmed **wrong** for this direction of the link.

So direct access is not usable on a 1750 as things stand, and the handshake failure now says which
of the two possible reasons it hit rather than the single sentence "No FitPro device answered" that
covered both. Driving a product-3 board directly means implementing the app-to-console protocol,
which this file does not describe.

### Confirmed: forcing the claim takes the belt away, and only a replug gives it back

The consequence above was measured end to end on the 1750, in both directions.

Switching to direct access with the USB permission granted severed the console from its treadmill.
GlassOS reported `DISCONNECTED` from then on, and it did **not** come back from restarting
`glassos_service`, from restarting Stride, or from `adb reboot`. Checked at the kernel afterwards,
the board was not merely unclaimed — it was **gone from the bus**: `num_connects=0`,
`/sys/bus/usb/devices/` holding nothing but the four root hubs, and no `/dev/bus/usb/003/002`. A
device that is not enumerated cannot be recovered by anything in userspace, which is why every
restart failed and why the rider-facing copy says *power-cycled at the wall*. Unplugging the
console's USB lead and plugging it back in re-enumerated it (`3-1 213c iFIT-LargeX`) and GlassOS
attached again immediately.

With the refusal in place the same sequence is now harmless. Selecting direct access, granting the
USB dialog, and switching back to iFit leaves the link untouched:

```
I MachineLink:     usb permission broadcast: claimed=true, actually granted=true
W FitProTransport: usb: /dev/bus/usb/003/002 is held by another process and GlassOS is
                   running; refusing to force the claim
I MachineLink:     console Connect -> Attached(state=2)
```

`Attached(state=2)` is `IDLE` — the console still has its treadmill, during and after the attempt.

One more thing this run settled, which is easy to mistake for a dead link: with GlassOS attached and
the machine idle, `SetIncline` comes back

```
WorkoutState IDLE is not valid for this operation, expected one of [[RUNNING]]
```

That is a healthy console decoding the command and declining it. Speed and incline only move once a
workout is running, so a refusal in that shape is evidence the link works, not that it does not.

## Read against GlassOS 6.14.6, and the FitPro2 handshake corrected

Everything below is from the decompiled `com.ifit.glassos_service` rather than from the C#, and each
claim names the class it came from. Three of them contradict what this file previously said.

### Confirmed unchanged

The wire format is right. `vh/d.e()` builds `[address][length][command][body…][checksum]` with an
overhead of 4 and the checksum over bytes `0 … length-2` — byte for byte what [`frame`] does. The
command ids (`vh/c`), device addresses (`yh/a`: `MAIN` 2, `TREADMILL` 4), and status values
(`vh/b`: `DEV_NOT_SUPPORTED` 0, `CMD_NOT_SUPPORTED` 1, `DONE` 2, …, `SECURITY_BLOCK` 8) all match.
Replies put the status at byte 3 and the payload at byte 4 (`vh/e.a`).

The `[0x02, 0x04, 0x02, len]` envelope really is BLE-only, and now for a better reason than
inference: `th/q` applies it when its `connectionType` is 2, the BLE driver passes 2 (`hc/g0`), and
the USB driver passes 1 (`th/s`, constructed over `wh/c`). USB gets the bare frame.

### Corrected: `SYSTEM_INFO` declares three body bytes, not two

`vh/e`'s no-arg constructor — the `SYSTEM_INFO` one — sets ContentLength to 3 and returns
`new byte[]{0, 0, 0}`. `VERSION_INFO` (`vh/j`) really is 2, sending `{false, false}`. The previous
revision gave both 2, which is a frame whose declared length disagrees with its own contents.

### Corrected: the console was never refusing anything

This file recorded that a product-3 board answers `DEVICE_INFO` with `CMD_NOT_SUPPORTED`, and
concluded the register codec was the wrong protocol for the app-facing link. **That conclusion was
wrong, and it was an artefact of this codebase's own framing.** With the bytes logged both ways:

```
DEVICE_INFO @4 -> 04 04 81 89 <- 00 04 04 81
```

The reply is a padding `00` followed by the request. Read literally, byte 1 is `04` — the *address*,
not a length — so the reader declared a 4-byte frame, truncated, and handed up `00 04 04 81`. Byte 3
of that is `81`; of the resynchronised `04 04 81 89` it is `89`, the request's own checksum. Neither
is a status. "The console says CMD_NOT_SUPPORTED" was this code reading a checksum.

Two fixes follow, both mirroring GlassOS. Leading zeroes are skipped, because byte 0 is a device
address and address 0 is `NONE` — `ai/b.a` rejects `bytes[0] == 0` outright. And a frame equal to
the request is an acknowledgement, not an answer, so the reader reads on; GlassOS survives this by
validating every read and simply reading again (`rj/p`, `wh/c.r`).

Once resynchronised the console echoes and then says nothing more, which is a different and more
honest failure than the one previously recorded.

### Found: USB links are opened with a 64-byte `0xFF` sync

`wh/c.X` is the USB adapter's open routine, and it logs its own steps: *"Discarding buffer from
console"*, *"Sending buffer full of 0xFF"*, *"0xFF send successfully. Now reading"*, then either
*"Read the response but it was not what was expected"* or *"Read the response and it was equal to the
expected response. Incrementing consecutiveBuffers"*.

So: discard whatever is pending, write 64 bytes of `0xFF`, read, and require the same buffer back —
**twice consecutively**, giving up after ten failures of either kind, 500 ms between attempts, 300 ms
per transfer (`wh/c.f17339x`). The expected answer is `ai.b.f824b`: 64 bytes of `0xFF` with index 3
left as a wildcard, the same constant the running link uses to *reject* a frame.

Nothing in Stride did this, which is the most likely reason the stream was a byte out of step.
Implemented in `UsbSerialTransport.synchronise`; **not yet confirmed to make the console answer**,
because the board dropped off the USB bus before the sequence could be observed end to end.

### Hazard, reproduced twice: the board can leave the bus entirely

Driving this console directly — claiming its interface and writing to it — twice ended with the board
**not enumerated at all**: `num_connects=0`, nothing under `/sys/bus/usb/devices` but the four root
hubs, no `/dev/bus/usb/003/00x`. Neither restarting `glassos_service`, nor restarting Stride, nor
`adb reboot` brought it back; unplugging the console's USB lead and plugging it in again did, both
times.

That is worse than losing the interface, because nothing in software can re-enumerate a device that
is not there. It is the strongest argument for the refusal in `UsbSerialTransport.open`: on a console
where iFit's service is live, direct access is not a thing to try casually.
