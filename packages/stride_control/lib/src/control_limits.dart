/// Tunable safety limits for the coordinator.
///
/// Numbers here are policy, not physics. The device's absolute clamps come from
/// the machine itself via [ControlRanges]; these values govern how aggressively
/// the coordinator is willing to *change* things and how long it waits before it
/// stops trusting the link.
library;

import 'types.dart';

/// Coordinator-level safety policy.
///
/// All defaults are deliberately conservative. Tests override them with small,
/// round numbers so timing is easy to reason about under `fake_async`.
class ControlLimits {
  const ControlLimits({
    this.maxSpeedAccel = 2.0,
    this.maxInclineSlew = 3.0,
    this.commandTimeout = const Duration(seconds: 2),
    this.stopConfirmTimeout = const Duration(seconds: 3),
    this.telemetryWatchdog = const Duration(seconds: 2),
    this.stopPreemptGrace = const Duration(milliseconds: 250),
    this.stopConfirmSpeedKph = 0.5,
    this.telemetryToleranceKph = 0.3,
    this.telemetryTolerancePercent = 0.3,
  });

  /// Maximum belt acceleration, km/h per second. Applies to *increases* only.
  /// Deceleration and stop are never rate-limited (PLAN.md 5.2).
  final double maxSpeedAccel;

  /// Maximum incline slew, percent per second. Increases only, as with speed.
  final double maxInclineSlew;

  /// How long a single write may wait for an ack or a confirming telemetry
  /// change before it is reported as [CommandStatus.timedOut].
  final Duration commandTimeout;

  /// How long a stop may take to be positively confirmed (ack *and* observed
  /// deceleration) before the coordinator escalates to "USE THE SAFETY KEY".
  final Duration stopConfirmTimeout;

  /// If telemetry stalls for longer than this while the belt is commanded to
  /// move, the coordinator attempts a stop and escalates loudly.
  final Duration telemetryWatchdog;

  /// How long a stop will wait for an already in-flight motion write to settle
  /// before transmitting anyway. Bounds how long a hung write can delay a stop;
  /// see [ControlLimits] and the coordinator's stop path for the reasoning.
  final Duration stopPreemptGrace;

  /// Observed belt speed at or below this counts as "decelerated / stopped" for
  /// positive stop confirmation.
  final double stopConfirmSpeedKph;

  /// How close observed speed must get to the commanded value for telemetry to
  /// stand in for a missing ack.
  final double telemetryToleranceKph;

  /// As [telemetryToleranceKph], for incline.
  final double telemetryTolerancePercent;
}

/// Optional per-user limits layered on top of the device clamps.
///
/// A profile may only *tighten* the device's absolute range, never widen it
/// (PLAN.md 3.6). Any field left null imposes no additional constraint. These are
/// modelled as a separate object precisely so it is impossible to express
/// "profile raises the ceiling": the coordinator always takes the more
/// restrictive of device and profile.
class ProfileLimits {
  const ProfileLimits({
    this.maxSpeed,
    this.minIncline,
    this.maxIncline,
  });

  /// Additional speed ceiling. Ignored if it is above the device maximum.
  final Speed? maxSpeed;

  /// Additional incline floor. Ignored if it is below the device minimum.
  final Percent? minIncline;

  /// Additional incline ceiling. Ignored if it is above the device maximum.
  final Percent? maxIncline;
}
