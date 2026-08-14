/// The narrow seam between the Control & Safety Coordinator and a real machine.
///
/// This is **not** the Phase 6 device abstraction (PLAN.md section 3.4). It is the minimum surface
/// needed to make the coordinator testable without a treadmill, shaped by GlassOS because GlassOS
/// is the only implementation that exists. It will be replaced, not extended, when a second
/// concrete driver arrives.
///
/// Everything here is intentionally dumb: a link transmits and reports, and makes **no safety
/// decisions of its own**. Clamping, ramp limiting, stop preemption and staleness handling all live
/// in the coordinator, so there is exactly one place to audit.
library;

import 'types.dart';

/// A raw transport to one machine.
abstract class MachineLink {
  Stream<LinkState> get state;

  LinkState get currentState;

  /// What the machine says it accepts. Available once connected.
  ControlRanges get ranges;

  /// Everything the machine reports, interleaved.
  Stream<MetricSample> get samples;

  Future<void> connect();

  /// Transmit a target speed. Returns when the machine acknowledges.
  ///
  /// Implementations must not clamp, ramp, retry, or reorder. Throw [LinkException] on failure and
  /// let the coordinator decide what that means.
  Future<void> sendSpeed(double kph);

  Future<void> sendIncline(double percent);

  /// Transmit a stop. Implementations should use the machine's native stop where one exists rather
  /// than faking it by commanding zero speed, because the machine's own workout state is
  /// authoritative.
  Future<void> sendStop();

  Future<void> dispose();
}

/// A transport-level failure. Distinct from a refusal by the machine.
class LinkException implements Exception {
  LinkException(this.message, {this.cause});

  final String message;
  final Object? cause;

  @override
  String toString() => 'LinkException: $message${cause == null ? '' : ' ($cause)'}';
}
