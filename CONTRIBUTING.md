# Contributing to Stride

Thanks for looking. A few things about this project make contributing unusual, so
please read the first two sections before opening a pull request.

## Before anything else: the hardware reality

Stride runs on a treadmill. It has been tested on **exactly one machine**, a
NordicTrack Commercial 1750. A bug here does not corrupt a document — it can leave
someone with a console they cannot operate, or a belt that does not stop when the
screen says it did.

Two consequences:

- **Read [`docs/RUNBOOK.md`](docs/RUNBOOK.md) and verify the revert path** before you
  set Stride as your default launcher. The console has no physical Home or Back
  button; a launcher that crashes on start is a brick until you revert it over adb.
- **Never test control commands with anyone on the belt.** Read-only calls
  (`CanRead`, `Get*`, the `*Subscription` streams) cannot move it. Anything under
  `control/` can.

## License: do not paste GPL code

This repository is **Apache-2.0 throughout**, and that is only defensible because
nothing in it is derived from the GPL-3 prior art it cites
([`NOTICE`](NOTICE), `docs/PLAN.md` §2.3).

The nearby projects — `cagnulein/qdomyos-zwift`, `a-vikulin/thud`,
`mikepugh/NordicFTMS` — are all GPL-3. Copying source from any of them into this repo
would force a relicense of the entire tree.

So:

- **Protocol *facts* are fine.** GATT UUIDs, byte layouts, flag semantics and field
  numbers are not copyrightable. Take them from the Bluetooth SIG specification, from
  `protocol/glassos/` (extracted from the console's own firmware), or from your own
  captures.
- **Their *expression* is not.** Do not copy or transliterate their code, and do not
  paste their `.proto` files. Cross-check against them by all means — say so in a
  comment, as the existing code does.
- By opening a pull request you confirm your contribution is your own work and may be
  released under Apache-2.0.

## Building and testing

```bash
(cd apps/spikes             && flutter test)  # harness: DER scanner, protobuf inspector, mTLS
(cd packages/stride_control && dart test)     # safety coordinator, incl. failure modes
(cd tools/glassos_mock      && dart test)     # mock console physics and fault injection
```

Each line is a subshell because all three paths are relative to the repo root. Run
without them and the second `cd` looks for `apps/spikes/packages/stride_control`,
fails, `&&` short-circuits, and two of the three suites quietly never run — with the
error scrolling past under `flutter test`'s output.

**Android lint is not optional**, and neither `flutter build` nor the Dart tests will
catch what it catches:

```bash
# First time only, from the repo root. The subshell matters: the lint command below is
# relative to the root too, and a bare `cd` here would send it looking for
# apps/spikes/apps/spikes/android.
(cd apps/spikes && flutter build apk --debug)

cd apps/spikes/android
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:lintDebug     # Gradle needs JDK 17+
```

The build comes first because on a fresh clone **there is no `./gradlew` yet**. Flutter
generates the Gradle wrapper into `apps/spikes/android/` during an Android build, and
Flutter's own `.gitignore` template excludes `gradlew`, `gradlew.bat` and
`gradle-wrapper.jar` for exactly that reason — they are generated, not authored. The same
build writes `local.properties`. Run the lint command on its own in a clean checkout and
it fails with "no such file or directory", which reads like a broken repo rather than a
missing step.

Do not "fix" that by committing the wrapper. It would fight Flutter's template, and the
committed jar would then drift from whatever the installed Flutter injects. Reach for a
system Gradle only if you know why you are doing it: the wrapper exists to pin the
version, and `gradle-wrapper.properties` is the file that says which one.

`NewApi`/`InlinedApi` are build-aborting errors on purpose. Stride compiles against a
modern SDK but targets a console on API 26–28, so a newer method overload compiles
cleanly, passes every test, and then throws `NoSuchMethodError` on the device. That
has already happened: `MotionEvent.getRawX(int)` is API 29+, and it crashed the
overlay on the first edge swipe — taking out the only Back and Home button the console
has.

You can get a long way without hardware. `tools/glassos_mock/` is a local mock console
with physics and fault injection, and `docs/SPIKES.md` is honest about what an
emulator run does and does not prove — the short version is that it catches crashes
and API mistakes and tells you nothing about GlassOS, the motor, or the safety key.

## Changes to the control path

Anything touching machine control has to hold the invariants in `docs/PLAN.md` §5.
The important ones:

- A **stop is never** ramp-limited, delayed, or queued behind another command. It
  preempts.
- Ramp limiting constrains **acceleration only**, never deceleration.
- A stop is "done" only on ack **plus** observed deceleration in telemetry. With
  neither, the UI escalates to **"USE THE SAFETY KEY"** — never to "stopped".
- Absolute clamps live at installation/device level. Profiles may only *tighten*
  them, never widen them.
- No auto-start from launch, boot, profile switch, or the companion app.

These are enforced in one place, above every transport, so that choosing a different
connection can never choose different safety rules. Keep it that way — do not push
safety logic down into a driver.

## Documentation

The docs in this repo carry the reasoning, not just the result, and several decisions
are recorded together with the argument that overturned an earlier one. If you change
a behaviour that a doc explains, update the doc in the same pull request. A stale
explanation is worse than none — the README claimed Stride shipped no certificates
long after it had started bundling them.

## Pull requests

- Keep them focused; unrelated cleanups in their own PR.
- Explain *why*, not just what. Match the surrounding comment style — this codebase
  documents the trap a piece of code exists to avoid.
- Say what you actually ran, and on what. "Tested on a Commercial 1750" and "mock
  only, no hardware" are both useful and honestly different.
- Never commit captured traces without scrubbing them. They carry machine serials and
  account identifiers; `.gitignore` blocks `*.trace`, `*.pcap` and `.telemetry/` for
  that reason.

## Reporting problems

Security and safety defects go through the private path in
[`SECURITY.md`](SECURITY.md), **not** a public issue. Everything else — including
"it worked on my non-1750 console" — is welcome as a regular issue.
