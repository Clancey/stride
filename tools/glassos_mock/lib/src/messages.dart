/// PLAUSIBLE, NOT MEASURED, GlassOS message schema.
///
/// ================== READ THIS BEFORE TRUSTING ANY FIELD NUMBER ==================
/// None of the field numbers below are confirmed against real hardware. The real
/// GlassOS `.proto` files are published in a GPL-3 repo we are deliberately not
/// copying, and the only honest way to learn the true field numbers is to run
/// apps/spikes/android/app/src/main/kotlin/io/stride/spikes/GlassOsClient.kt against an actual console and read the wire
/// dump (see docs/PLAN.md section 2.2 and section 7).
///
/// So this file is a self-consistent GUESS chosen to be:
///   - shaped like the documented messages (ConsoleInfo, SpeedService, etc.),
///   - decodable by the schema-free client,
///   - and isolated: when the real schema arrives, THIS FILE is the only thing
///     that should need to change. The server, physics, and fault code below all
///     go through these classes.
///
/// Field numbers start at 1 and ascend in declaration order, which is the most
/// common protoc authoring pattern, but that is an assumption too.
/// ================================================================================
library;

import 'dart:typed_data';

import 'proto_codec.dart';

/// GlassOS reports many machine kinds; we only model a treadmill. The numeric
/// value is a guess for the enum ordinal.
const int machineTypeTreadmill = 1;

/// Workout lifecycle. Ordinals are a guess. The names mirror docs/PLAN.md 3.5
/// (idle -> running <-> paused -> summary), plus an explicit "ended" terminal.
class WorkoutState {
  static const int idle = 0;
  static const int running = 1;
  static const int paused = 2;
  static const int summary = 3;
  static const int ended = 4;

  static String name(int v) => switch (v) {
    idle => 'idle',
    running => 'running',
    paused => 'paused',
    summary => 'summary',
    ended => 'ended',
    _ => 'unknown($v)',
  };
}

/// Capability + range descriptor from ConsoleService.GetConsole.
///
/// Values are REPRESENTATIVE of a NordicTrack Commercial 1750, not measured:
/// the 1750 is spec'd at 0-12 mph and -3% to +15% incline. We express speed in
/// kph because the documented service is `SetSpeed(kph)` (docs/PLAN.md 2.2):
///   12 mph ~= 19.31 kph, 0 mph = 0 kph.
/// The 1750 has no resistance actuator, so resistance is reported unsupported.
class ConsoleInfo {
  const ConsoleInfo({
    this.machineType = machineTypeTreadmill,
    this.minKph = 0.0,
    this.maxKph = 19.31, // 12 mph, representative for a 1750
    this.minInclinePercent = -3.0, // 1750 supports decline to -3%
    this.maxInclinePercent = 15.0,
    this.minResistance = 0.0,
    this.maxResistance = 0.0,
    this.canSetSpeed = true,
    this.canSetIncline = true,
    this.canSetResistance = false, // 1750 has no resistance control
    this.serial = 'MOCK-1750-000000',
    this.firmwareVersion = 'mock-glassos-0.1.0',
  });

  final int machineType;
  final double minKph;
  final double maxKph;
  final double minInclinePercent;
  final double maxInclinePercent;
  final double minResistance;
  final double maxResistance;
  final bool canSetSpeed;
  final bool canSetIncline;
  final bool canSetResistance;
  final String serial;
  final String firmwareVersion;

  // Guessed field numbers.
  static const int fMachineType = 1;
  static const int fMinKph = 2;
  static const int fMaxKph = 3;
  static const int fMinInclinePercent = 4;
  static const int fMaxInclinePercent = 5;
  static const int fMinResistance = 6;
  static const int fMaxResistance = 7;
  static const int fCanSetSpeed = 8;
  static const int fCanSetIncline = 9;
  static const int fCanSetResistance = 10;
  static const int fSerial = 11;
  static const int fFirmwareVersion = 12;

  Uint8List toBytes() {
    final w = ProtoWriter()
      ..writeEnum(fMachineType, machineType)
      ..writeDouble(fMinKph, minKph)
      ..writeDouble(fMaxKph, maxKph)
      ..writeDouble(fMinInclinePercent, minInclinePercent)
      ..writeDouble(fMaxInclinePercent, maxInclinePercent)
      ..writeDouble(fMinResistance, minResistance)
      ..writeDouble(fMaxResistance, maxResistance)
      ..writeBool(fCanSetSpeed, canSetSpeed)
      ..writeBool(fCanSetIncline, canSetIncline)
      ..writeBool(fCanSetResistance, canSetResistance)
      ..writeString(fSerial, serial)
      ..writeString(fFirmwareVersion, firmwareVersion);
    return w.toBytes();
  }
}

/// Instantaneous speed telemetry. `kph` is the ACTUAL belt speed (post-physics),
/// `targetKph` the last commanded value, so a client can observe deceleration.
class SpeedSample {
  const SpeedSample({
    required this.kph,
    required this.targetKph,
    required this.timestampMs,
  });

  final double kph;
  final double targetKph;
  final int timestampMs;

  static const int fKph = 1;
  static const int fTargetKph = 2;
  static const int fTimestampMs = 3;

  Uint8List toBytes() => (ProtoWriter()
        ..writeDouble(fKph, kph)
        ..writeDouble(fTargetKph, targetKph)
        ..writeInt(fTimestampMs, timestampMs))
      .toBytes();
}

/// Instantaneous incline telemetry.
class InclineSample {
  const InclineSample({
    required this.percent,
    required this.targetPercent,
    required this.timestampMs,
  });

  final double percent;
  final double targetPercent;
  final int timestampMs;

  static const int fPercent = 1;
  static const int fTargetPercent = 2;
  static const int fTimestampMs = 3;

  Uint8List toBytes() => (ProtoWriter()
        ..writeDouble(fPercent, percent)
        ..writeDouble(fTargetPercent, targetPercent)
        ..writeInt(fTimestampMs, timestampMs))
      .toBytes();
}

/// Response to a SetSpeed / SetIncline command.
///
/// `generation` is echoed back to support the stale-command rejection scheme in
/// docs/PLAN.md 3.1 / 5.8: the client tags commands with a generation id and the
/// coordinator discards acks whose generation is older than the latest sent.
class CommandAck {
  const CommandAck({
    required this.accepted,
    required this.appliedTarget,
    required this.generation,
    this.rejectReason = '',
  });

  final bool accepted;
  final double appliedTarget;
  final int generation;
  final String rejectReason;

  static const int fAccepted = 1;
  static const int fAppliedTarget = 2;
  static const int fGeneration = 3;
  static const int fRejectReason = 4;

  Uint8List toBytes() {
    final w = ProtoWriter()
      ..writeBool(fAccepted, accepted)
      ..writeDouble(fAppliedTarget, appliedTarget)
      ..writeInt(fGeneration, generation);
    if (rejectReason.isNotEmpty) w.writeString(fRejectReason, rejectReason);
    return w.toBytes();
  }
}

/// Workout state transition event. `source` distinguishes who caused it, which
/// matters because docs/PLAN.md 3.5 requires the client to treat this stream as
/// an input and react when SOMETHING ELSE (a hardware button, the safety key)
/// changes state underneath it.
class WorkoutStateChanged {
  const WorkoutStateChanged({
    required this.state,
    required this.source,
    required this.timestampMs,
  });

  final int state;
  final String source; // "app" | "console_button" | "safety_key" | "system"
  final int timestampMs;

  static const int fState = 1;
  static const int fSource = 2;
  static const int fTimestampMs = 3;

  Uint8List toBytes() => (ProtoWriter()
        ..writeEnum(fState, state)
        ..writeString(fSource, source)
        ..writeInt(fTimestampMs, timestampMs))
      .toBytes();
}

/// Parsed SetSpeed request. Guessed to carry the target in field 1 as a double
/// (kph) plus an optional generation id in field 2.
class SetValueRequest {
  SetValueRequest(this.value, this.generation);

  final double? value;
  final int? generation;

  static const int fValue = 1;
  static const int fGeneration = 2;

  static SetValueRequest parse(List<int> bytes) {
    final fields = ProtoReader(Uint8List.fromList(bytes)).readAll();
    return SetValueRequest(
      ProtoReader.asDouble(fields[fValue]),
      ProtoReader.asInt(fields[fGeneration]),
    );
  }
}
