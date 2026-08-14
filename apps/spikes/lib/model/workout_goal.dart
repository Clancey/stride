import 'package:flutter/foundation.dart';

/// The three goal shapes Stride can track alongside the machine.
///
/// [channelValue] is the wire string the platform bridge expects; keeping it on
/// the enum stops the UI from re-spelling `'distance'` at every call site.
enum WorkoutGoalKind {
  none('none'),
  distance('distance'),
  time('time');

  const WorkoutGoalKind(this.channelValue);

  final String channelValue;

  static WorkoutGoalKind fromChannel(String? value) {
    for (final kind in WorkoutGoalKind.values) {
      if (kind.channelValue == value) return kind;
    }
    return WorkoutGoalKind.none;
  }
}

/// An immutable target the rider sets before Stride's own timer starts.
///
/// Stride cannot command the belt, so a goal is purely Stride's bookkeeping:
/// [target] is miles for a distance goal and seconds for a time goal. It is a
/// meaningless zero for [WorkoutGoalKind.none].
@immutable
class WorkoutGoal {
  const WorkoutGoal({required this.kind, required this.target});

  const WorkoutGoal.none() : kind = WorkoutGoalKind.none, target = 0;

  /// [miles] is clamped to a non-negative value; a negative distance goal is
  /// nonsense the picker can never produce but the model still refuses.
  const WorkoutGoal.distance(double miles)
    : kind = WorkoutGoalKind.distance,
      target = miles < 0 ? 0.0 : miles;

  WorkoutGoal.time(Duration duration)
    : kind = WorkoutGoalKind.time,
      target = duration.inSeconds < 0 ? 0 : duration.inSeconds.toDouble();

  final WorkoutGoalKind kind;
  final double target;

  bool get isNone => kind == WorkoutGoalKind.none;

  /// A goal with a zero or negative target can never complete, so treat it as
  /// unset for progress and ETA purposes.
  bool get isTrackable => !isNone && target > 0;

  double get targetMiles => kind == WorkoutGoalKind.distance ? target : 0;

  Duration get targetDuration => kind == WorkoutGoalKind.time
      ? Duration(seconds: target.round())
      : Duration.zero;

  /// Fraction complete in the range 0..1, always clamped.
  ///
  /// Distance goals read the belt's odometer; time goals read Stride's own
  /// elapsed clock, which advances whether or not the rider is moving.
  double progressFrom({
    required double distanceMiles,
    required Duration elapsed,
  }) {
    if (!isTrackable) return 0;
    final done = switch (kind) {
      WorkoutGoalKind.distance => distanceMiles,
      WorkoutGoalKind.time => elapsed.inSeconds.toDouble(),
      WorkoutGoalKind.none => 0.0,
    };
    if (done <= 0) return 0;
    return (done / target).clamp(0.0, 1.0);
  }

  /// Projected time to completion at the current average pace, or `null` when
  /// that projection would be a lie.
  ///
  /// It is null when there is no trackable goal, when nothing has happened yet
  /// (zero progress), and — for distance goals — when the belt is not moving,
  /// because dividing a remaining distance by a zero pace is infinity, not an
  /// ETA. An already-met goal returns [Duration.zero], never a negative value.
  Duration? etaFrom({
    required double distanceMiles,
    required Duration elapsed,
  }) {
    if (!isTrackable) return null;

    switch (kind) {
      case WorkoutGoalKind.none:
        return null;
      case WorkoutGoalKind.time:
        final remaining = target - elapsed.inSeconds;
        if (elapsed.inSeconds <= 0) return null;
        if (remaining <= 0) return Duration.zero;
        return Duration(seconds: remaining.round());
      case WorkoutGoalKind.distance:
        final elapsedSeconds = elapsed.inSeconds;
        if (elapsedSeconds <= 0 || distanceMiles <= 0) return null;
        final remainingMiles = target - distanceMiles;
        if (remainingMiles <= 0) return Duration.zero;
        // seconds = remainingMiles / (distanceMiles / elapsedSeconds).
        final seconds = remainingMiles * elapsedSeconds / distanceMiles;
        return Duration(seconds: seconds.round());
    }
  }

  double remainingMiles(double distanceMiles) {
    if (kind != WorkoutGoalKind.distance) return 0;
    final remaining = target - distanceMiles;
    return remaining < 0 ? 0 : remaining;
  }

  Duration remainingTime(Duration elapsed) {
    if (kind != WorkoutGoalKind.time) return Duration.zero;
    final remaining = target - elapsed.inSeconds;
    return remaining < 0 ? Duration.zero : Duration(seconds: remaining.round());
  }

  /// Compact display of the target itself, e.g. `"5.0 mi"`, `"30:00"`.
  String get label => switch (kind) {
    WorkoutGoalKind.none => 'No goal',
    WorkoutGoalKind.distance => '${formatMiles(target)} mi',
    WorkoutGoalKind.time => formatClock(targetDuration),
  };

  /// Short "still to go" phrase for the given live readings.
  String remainingLabel({
    required double distanceMiles,
    required Duration elapsed,
  }) {
    return switch (kind) {
      WorkoutGoalKind.none => 'No goal',
      WorkoutGoalKind.distance =>
        '${formatMiles(remainingMiles(distanceMiles))} mi to go',
      WorkoutGoalKind.time => '${formatClock(remainingTime(elapsed))} left',
    };
  }

  static String formatMiles(double miles) {
    final safe = miles < 0 ? 0.0 : miles;
    return safe.toStringAsFixed(1);
  }

  static String formatClock(Duration duration) {
    final total = duration.inSeconds < 0 ? 0 : duration.inSeconds;
    final hours = total ~/ 3600;
    final minutes = (total % 3600) ~/ 60;
    final seconds = total % 60;
    final mm = minutes.toString().padLeft(2, '0');
    final ss = seconds.toString().padLeft(2, '0');
    if (hours > 0) return '$hours:$mm:$ss';
    return '$mm:$ss';
  }

  @override
  bool operator ==(Object other) =>
      other is WorkoutGoal && other.kind == kind && other.target == target;

  @override
  int get hashCode => Object.hash(kind, target);

  @override
  String toString() => 'WorkoutGoal(${kind.channelValue}, $target)';
}
