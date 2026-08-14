import 'package:fake_async/fake_async.dart';
import 'package:stride_control/stride_control.dart';
import 'package:stride_control/testing.dart';

export 'package:stride_control/stride_control.dart';
export 'package:stride_control/testing.dart';

/// Small, round limits so timing is trivial to reason about under fake_async.
/// Acceleration is effectively uncapped by default; the dedicated acceleration
/// tests pass their own tight [ControlLimits].
const ControlLimits fastLimits = ControlLimits(
  maxSpeedAccel: 1000,
  maxInclineSlew: 1000,
  commandTimeout: Duration(milliseconds: 500),
  stopConfirmTimeout: Duration(seconds: 1),
  telemetryWatchdog: Duration(seconds: 1),
  stopPreemptGrace: Duration(milliseconds: 100),
  stopConfirmSpeedKph: 0.5,
);

/// Wires a [Coordinator] to a [FakeMachineLink] inside a `fake_async` zone, with
/// a fake clock shared by both so acceleration timing is deterministic.
class Harness {
  Harness._(this.async, this.link, this.coord, this.events, this.states);

  final FakeAsync async;
  final FakeMachineLink link;
  final Coordinator coord;
  final List<SafetyEvent> events;
  final List<SafetyState> states;

  /// Advance fake time.
  void tick(Duration d) => async.elapse(d);

  /// Capture the current generation, the way a real client would before acting.
  int get gen => coord.generation;

  /// Convenience: command a speed under the current generation.
  Future<CommandResult> setSpeed(double kph) =>
      coord.setSpeed(Speed.kph(kph), generation: coord.generation);

  Future<CommandResult> setIncline(double pct) =>
      coord.setIncline(Percent(pct), generation: coord.generation);

  bool hasEvent<T extends SafetyEvent>() => events.any((e) => e is T);
}

/// Holds a command result once its future resolves. `.ignore()` keeps the
/// analyzer happy about the unawaited future and swallows nothing meaningful
/// because the result is inspected via [result].
class Captured {
  CommandResult? result;
}

Captured capture(Future<CommandResult> future) {
  final c = Captured();
  future.then((r) => c.result = r).ignore();
  return c;
}

/// Runs [body] with a fully wired coordinator. [prime] runs against the link
/// before the coordinator connects (for attach-to-moving scenarios).
void runControl(
  void Function(Harness h) body, {
  ControlLimits limits = fastLimits,
  ProfileLimits? profile,
  ControlRanges? ranges,
  Duration ackDelay = const Duration(milliseconds: 50),
  double beltAccelKph = 20.0,
  void Function(FakeMachineLink link)? prime,
}) {
  fakeAsync((async) {
    final start = DateTime(2026, 1, 1);
    DateTime clock() => start.add(async.elapsed);

    final link = FakeMachineLink(
      ranges: ranges,
      ackDelay: ackDelay,
      beltAccelKph: beltAccelKph,
      clock: clock,
    );
    prime?.call(link);

    final coord = Coordinator(
      link: link,
      limits: limits,
      profile: profile,
      clock: clock,
    );
    final events = <SafetyEvent>[];
    final states = <SafetyState>[];
    final eventSub = coord.events.listen(events.add);
    final stateSub = coord.states.listen(states.add);

    coord.start();
    async.elapse(const Duration(milliseconds: 20));

    final h = Harness._(async, link, coord, events, states);
    try {
      body(h);
    } finally {
      eventSub.cancel();
      stateSub.cancel();
      coord.dispose();
      async.elapse(const Duration(milliseconds: 10));
    }
  });
}

/// Drives the belt up to a steady cruising speed and lets telemetry catch up.
/// Uses generous acceleration headroom (callers that test acceleration limiting
/// do not use this).
void cruise(Harness h, double kph) {
  h.setSpeed(kph);
  h.tick(const Duration(milliseconds: 300));
}
