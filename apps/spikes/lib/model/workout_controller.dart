import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../bridge.dart';

abstract class WorkoutBridgeClient {
  Future<String> workoutState();
  Future<int> workoutElapsedMs();
  Future<bool> workoutStart();
  Future<bool> workoutPause();
  Future<bool> workoutResume();
  Future<int> workoutStop();
  Future<bool> workoutCancelStart();
  Future<WorkoutVolume> volumeGet();
  Future<bool> volumeSet(int level);
  Future<MachineSnapshot> machineSnapshot();
}

class MethodChannelWorkoutBridge implements WorkoutBridgeClient {
  const MethodChannelWorkoutBridge();

  @override
  Future<String> workoutState() => SpikeBridge.workoutState();

  @override
  Future<int> workoutElapsedMs() => SpikeBridge.workoutElapsedMs();

  @override
  Future<bool> workoutStart() => SpikeBridge.workoutStart();

  @override
  Future<bool> workoutPause() => SpikeBridge.workoutPause();

  @override
  Future<bool> workoutResume() => SpikeBridge.workoutResume();

  @override
  Future<int> workoutStop() => SpikeBridge.workoutStop();

  @override
  Future<bool> workoutCancelStart() => SpikeBridge.workoutCancelStart();

  @override
  Future<WorkoutVolume> volumeGet() async =>
      WorkoutVolume.fromMap(await SpikeBridge.volumeGet());

  @override
  Future<bool> volumeSet(int level) => SpikeBridge.volumeSet(level);

  @override
  Future<MachineSnapshot> machineSnapshot() async =>
      MachineSnapshot.fromMap(await SpikeBridge.machineSnapshot());
}

@immutable
class WorkoutVolume {
  const WorkoutVolume({required this.level, required this.max});

  factory WorkoutVolume.fromMap(Map<String, dynamic> map) {
    final max = _asInt(map['max']).clamp(0, 1000);
    final level = _asInt(map['level']).clamp(0, max);
    return WorkoutVolume(level: level, max: max);
  }

  static const unavailable = WorkoutVolume(level: 0, max: 0);

  final int level;
  final int max;

  bool get available => max > 0;
}

/// Fallback safety copy for when the host has not answered yet.
///
/// It deliberately matches `MachineLink.CANNOT_READ_NOTICE` on the Kotlin side. "We have not heard
/// from the machine" and "we cannot read the machine" are the same claim from the rider's point of
/// view, and it is the safer of the two sentences to guess.
const String _cannotReadNotice =
    "Stride can't read the treadmill. The belt may be moving.";

@immutable
class MachineSnapshot {
  const MachineSnapshot({
    required this.status,
    required this.reason,
    required this.canCommand,
    required this.noReadingLabel,
    required this.metricsNotice,
    this.speedMph,
    this.inclinePercent,
    this.distanceMiles,
    this.paceMinPerMile,
    this.fanLevel,
  });

  factory MachineSnapshot.fromMap(Map<String, dynamic> map) {
    return MachineSnapshot(
      status: (map['status'] as String?)?.trim().isNotEmpty == true
          ? map['status'] as String
          : 'unknown',
      reason: (map['reason'] as String?)?.trim().isNotEmpty == true
          ? map['reason'] as String
          : 'Console link unavailable in this build.',
      speedMph: _asDoubleOrNull(map['speedMph']),
      inclinePercent: _asDoubleOrNull(map['inclinePercent']),
      distanceMiles: _asDoubleOrNull(map['distanceMiles']),
      paceMinPerMile: _asDoubleOrNull(map['paceMinPerMile']),
      fanLevel: map['fanLevel'] == null ? null : _asInt(map['fanLevel']),
      canCommand: map['canCommand'] == true,
      noReadingLabel:
          _asNonEmptyString(map['noReading'] ?? map['noReadingLabel']) ??
          'Not measured',
      metricsNotice:
          _asNonEmptyString(map['metricsNotice']) ?? _cannotReadNotice,
    );
  }

  factory MachineSnapshot.unavailable([String? reason]) {
    return MachineSnapshot(
      status: 'unavailable',
      reason: reason ?? 'Console link unavailable in this build.',
      canCommand: false,
      noReadingLabel: 'Not measured',
      metricsNotice: _cannotReadNotice,
    );
  }

  final String status;
  final String reason;
  final String noReadingLabel;
  final double? speedMph;
  final double? inclinePercent;
  final double? distanceMiles;
  final double? paceMinPerMile;
  final int? fanLevel;
  final bool canCommand;

  /// The safety sentence to print beside these metrics, chosen by the host from what is actually
  /// true right now. Never concatenate the read and control warnings: claiming both at once is a
  /// visible contradiction, and safety copy that is obviously wrong in the easy case is not
  /// believed in the hard case.
  final String metricsNotice;
}

class WorkoutController extends ChangeNotifier {
  WorkoutController({
    WorkoutBridgeClient bridge = const MethodChannelWorkoutBridge(),
    Duration tickInterval = const Duration(seconds: 1),
  }) : this._(bridge, tickInterval);

  WorkoutController._(this._bridge, this._tickInterval) {
    SpikeBridge.onWorkoutStateChanged = _handleHostWorkoutStateChanged;
  }

  final WorkoutBridgeClient _bridge;
  final Duration _tickInterval;
  Timer? _ticker;
  bool _disposed = false;
  bool _tickInFlight = false;

  String _state = 'idle';
  int _elapsedMs = 0;
  WorkoutVolume _volume = WorkoutVolume.unavailable;
  MachineSnapshot _machine = MachineSnapshot.unavailable();
  String? _lastError;

  String get state => _state;
  int get elapsedMs => _elapsedMs;
  WorkoutVolume get volume => _volume;
  MachineSnapshot get machine => _machine;
  String? get lastError => _lastError;

  bool get isRunning => _state == 'running';
  bool get isPaused => _state == 'paused';
  bool get isIdle => _state == 'idle';

  /// The rider has asked to start and the treadmill has not answered yet.
  ///
  /// Its own state rather than a flavour of running, because nothing is running: the belt is still,
  /// the clock has not begun, and the only honest thing to show is that we are waiting.
  bool get isStarting => _state == 'starting';

  Future<void> load() async {
    await _refreshWorkout();
    await refreshVolume();
    await refreshMachine();
  }

  Future<bool> startWorkout() async {
    final ok = await _safe(() => _bridge.workoutStart(), false);
    if (!ok) return false;
    // Not 'running'. The host is the only thing that knows whether the treadmill agreed, and it
    // says so on the state channel; claiming it here would put the launcher back to showing a
    // workout in progress over a stationary belt, which is the bug this state exists to fix.
    _state = 'starting';
    await _refreshAfterControl();
    return true;
  }

  Future<bool> pauseWorkout() async {
    final ok = await _safe(() => _bridge.workoutPause(), false);
    if (!ok) return false;
    _state = 'paused';
    await _refreshAfterControl();
    return true;
  }

  Future<bool> resumeWorkout() async {
    final ok = await _safe(() => _bridge.workoutResume(), false);
    if (!ok) return false;
    _state = 'running';
    await _refreshAfterControl();
    return true;
  }

  /// Call off a start the treadmill has not answered yet.
  ///
  /// Kept apart from [finishWorkout] because the goal survives a cancelled start: the rider set a
  /// target moments ago and never got a workout, so taking it away would charge them for the
  /// machine's delay.
  Future<bool> cancelStart() async {
    final ok = await _safe(() => _bridge.workoutCancelStart(), false);
    if (!ok) return false;
    _state = 'idle';
    _elapsedMs = 0;
    _syncTicker();
    _notify();
    unawaited(refreshMachine());
    return true;
  }

  Future<int?> finishWorkout() async {
    final total = await _safe<int?>(() async => _bridge.workoutStop(), null);
    if (total == null) return null;
    _state = 'idle';
    _elapsedMs = total;
    _syncTicker();
    _notify();
    unawaited(refreshMachine());
    return total;
  }

  Future<void> refreshVolume() async {
    _volume = await _safe(() => _bridge.volumeGet(), WorkoutVolume.unavailable);
    _notify();
  }

  Future<bool> setVolume(int level) async {
    if (!_volume.available) await refreshVolume();
    if (!_volume.available) return false;
    final next = level.clamp(0, _volume.max);
    final ok = await _safe(() => _bridge.volumeSet(next), false);
    if (!ok) return false;
    _volume = WorkoutVolume(level: next, max: _volume.max);
    _notify();
    unawaited(refreshVolume());
    return true;
  }

  Future<void> refreshMachine() async {
    _machine = await _safe(
      () => _bridge.machineSnapshot(),
      MachineSnapshot.unavailable(_lastError),
    );
    _notify();
  }

  Future<void> _refreshWorkout() async {
    final nextState = await _safe(() => _bridge.workoutState(), _state);
    final nextElapsed = await _safe(
      () => _bridge.workoutElapsedMs(),
      _elapsedMs,
    );
    _state = _normalizeState(nextState);
    _elapsedMs = nextElapsed < 0 ? 0 : nextElapsed;
    _syncTicker();
    _notify();
  }

  Future<void> _refreshAfterControl() async {
    await _refreshWorkout();
    await refreshMachine();
  }

  void _handleHostWorkoutStateChanged(String state) {
    _state = _normalizeState(state);
    _syncTicker();
    _notify();
    unawaited(_refreshWorkout());
    unawaited(refreshMachine());
  }

  void _syncTicker() {
    // Also ticks while starting. The host owns this state and announces the change, but a missed
    // event would otherwise leave the launcher showing "Starting…" forever with no poll to correct
    // it — so the pending state re-reads the host rather than trusting one notification.
    if (_state != 'running' && _state != 'starting') {
      _ticker?.cancel();
      _ticker = null;
      return;
    }
    _ticker ??= Timer.periodic(_tickInterval, (_) => _refreshTick());
  }

  Future<void> _refreshTick() async {
    if (_tickInFlight) return;
    _tickInFlight = true;
    try {
      if (_state == 'starting') {
        // The one thing worth knowing while starting is whether it still is.
        final hostState = await _safe(() => _bridge.workoutState(), _state);
        if (_disposed) return;
        final next = _normalizeState(hostState);
        if (next != _state) {
          _state = next;
          _syncTicker();
        }
      }
      final nextElapsed = await _safe(
        () => _bridge.workoutElapsedMs(),
        _elapsedMs,
      );
      if (_disposed) return;
      _elapsedMs = nextElapsed < 0 ? 0 : nextElapsed;
      _machine = await _safe(
        () => _bridge.machineSnapshot(),
        MachineSnapshot.unavailable(_lastError),
      );
      if (_disposed) return;
      _notify();
    } finally {
      _tickInFlight = false;
    }
  }

  Future<T> _safe<T>(Future<T> Function() run, T fallback) async {
    try {
      _lastError = null;
      return await run();
    } on MissingPluginException {
      _lastError = 'Console bridge unavailable in this environment.';
      return fallback;
    } on PlatformException catch (error) {
      _lastError = error.message ?? 'Console bridge returned an error.';
      return fallback;
    } on Object {
      _lastError = 'Console bridge unavailable in this environment.';
      return fallback;
    }
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _ticker?.cancel();
    if (SpikeBridge.onWorkoutStateChanged == _handleHostWorkoutStateChanged) {
      SpikeBridge.onWorkoutStateChanged = null;
    }
    super.dispose();
  }
}

String _normalizeState(String state) {
  return switch (state) {
    'starting' => 'starting',
    'running' => 'running',
    'paused' => 'paused',
    _ => 'idle',
  };
}

int _asInt(Object? value) {
  if (value is int) return value;
  if (value is num) return value.round();
  return int.tryParse('$value') ?? 0;
}

double? _asDoubleOrNull(Object? value) {
  if (value == null) return null;
  if (value is num) return value.toDouble();
  return double.tryParse('$value');
}

String? _asNonEmptyString(Object? value) {
  final string = value as String?;
  if (string == null || string.trim().isEmpty) return null;
  return string;
}
