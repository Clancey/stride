/// Value types shared by the coordinator and the machine link.
///
/// Deliberately **not** the device abstraction from PLAN.md section 3.4. That is extracted in
/// Phase 6, after two concrete implementations exist. These types are shaped by GlassOS because
/// GlassOS is the only implementation right now, and pretending otherwise would be the exact
/// premature generalization the plan warns against.
library;

import 'package:meta/meta.dart';

/// Belt speed. Stored in km/h because that is what GlassOS speaks.
@immutable
class Speed implements Comparable<Speed> {
  const Speed.kph(this.kph);

  const Speed.zero() : kph = 0;

  Speed.mph(double mph) : kph = mph * 1.609344;

  final double kph;

  double get mph => kph / 1.609344;

  bool get isStopped => kph <= 0;

  Speed operator +(Speed other) => Speed.kph(kph + other.kph);

  Speed operator -(Speed other) => Speed.kph(kph - other.kph);

  @override
  int compareTo(Speed other) => kph.compareTo(other.kph);

  bool operator <(Speed other) => kph < other.kph;

  bool operator >(Speed other) => kph > other.kph;

  bool operator <=(Speed other) => kph <= other.kph;

  bool operator >=(Speed other) => kph >= other.kph;

  @override
  bool operator ==(Object other) => other is Speed && other.kph == kph;

  @override
  int get hashCode => kph.hashCode;

  @override
  String toString() => '${kph.toStringAsFixed(1)} kph';
}

/// Incline as a percentage grade. Can be negative on machines that support decline.
@immutable
class Percent implements Comparable<Percent> {
  const Percent(this.value);

  const Percent.zero() : value = 0;

  final double value;

  @override
  int compareTo(Percent other) => value.compareTo(other.value);

  bool operator <(Percent other) => value < other.value;

  bool operator >(Percent other) => value > other.value;

  bool operator <=(Percent other) => value <= other.value;

  bool operator >=(Percent other) => value >= other.value;

  @override
  bool operator ==(Object other) => other is Percent && other.value == value;

  @override
  int get hashCode => value.hashCode;

  @override
  String toString() => '${value.toStringAsFixed(1)}%';
}

/// What a machine will accept for one controllable quantity.
///
/// Ranges carry units and step resolution rather than booleans, because "can set speed" does not
/// tell you whether 8.35 kph is a legal request. On GlassOS these are populated from
/// `ConsoleService.GetConsole()` - the machine describing itself - rather than a hand-maintained
/// per-model table.
@immutable
class Range {
  const Range({
    required this.min,
    required this.max,
    required this.step,
    required this.writable,
  });

  const Range.readOnly()
      : min = 0,
        max = 0,
        step = 0,
        writable = false;

  final double min;
  final double max;

  /// Smallest increment the machine resolves. Zero means continuous / unknown.
  final double step;

  final bool writable;

  bool contains(double v) => v >= min && v <= max;

  /// Clamp into range and snap onto the step grid.
  double clamp(double v) {
    var out = v < min ? min : (v > max ? max : v);
    if (step > 0) {
      out = (out / step).round() * step;
      // Snapping can push back outside the range at the boundaries.
      if (out < min) out = min;
      if (out > max) out = max;
    }
    return out;
  }

  @override
  String toString() =>
      '[$min..$max step $step${writable ? '' : ' read-only'}]';
}

/// The machine's own declaration of what it is and what it accepts.
@immutable
class ControlRanges {
  const ControlRanges({
    required this.speed,
    required this.incline,
    required this.resistance,
    this.machineType = 'unknown',
  });

  /// Conservative fallback used only until the machine reports its real ranges.
  const ControlRanges.unknown()
      : speed = const Range.readOnly(),
        incline = const Range.readOnly(),
        resistance = const Range.readOnly(),
        machineType = 'unknown';

  final Range speed;
  final Range incline;
  final Range resistance;
  final String machineType;

  @override
  String toString() =>
      'ControlRanges($machineType speed=$speed incline=$incline resistance=$resistance)';
}

/// Why a command did not do what was asked.
enum CommandStatus {
  /// Applied and acknowledged by the machine.
  applied,

  /// Accepted, but clamped or ramp-limited to a different value than requested.
  adjusted,

  /// Refused before transmission - out of range, or the quantity is not writable.
  rejected,

  /// Sent, but no acknowledgement arrived within the deadline.
  timedOut,

  /// Discarded because a newer generation superseded it (typically a stop).
  superseded,

  /// The link reported a failure.
  linkFailure,

  /// Refused because the safety-key latch is engaged.
  latched,
}

/// The outcome of a single command.
///
/// Commands return typed results rather than `Future<void>`, because "requested" and "applied" are
/// different facts and safety logic depends on telling them apart.
@immutable
class CommandResult {
  const CommandResult({
    required this.status,
    required this.requested,
    this.applied,
    this.reason,
  });

  final CommandStatus status;

  /// What the caller asked for.
  final double requested;

  /// What was actually sent, after clamping and ramp limiting. Null if nothing was sent.
  final double? applied;

  final String? reason;

  bool get didTransmit => applied != null;

  bool get ok => status == CommandStatus.applied || status == CommandStatus.adjusted;

  @override
  String toString() => 'CommandResult(${status.name} requested=$requested '
      'applied=$applied${reason == null ? '' : ' reason=$reason'})';
}

/// A quantity the machine reports.
enum MetricKind { speed, incline, resistance, cadence, watts, distance, heartRate }

/// One observation, with the freshness information the coordinator needs to decide whether to
/// trust it. Source selection falls back on staleness, not a static priority list.
@immutable
class MetricSample {
  const MetricSample({
    required this.kind,
    required this.value,
    required this.timestamp,
    required this.source,
  });

  final MetricKind kind;
  final double value;
  final DateTime timestamp;

  /// Free-form origin, e.g. `glassos`, `ble:hrs`, `companion`.
  final String source;

  Duration ageAt(DateTime now) => now.difference(timestamp);

  @override
  String toString() => '${kind.name}=$value @$source';
}

/// Connection lifecycle, kept separate from both actuation and sensing.
enum LinkState { disconnected, connecting, connected, faulted }
