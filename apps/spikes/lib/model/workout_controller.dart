import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../bridge.dart';

/// The "USE THE SAFETY KEY" latch, as the host reports it.
///
/// Stride told the belt to stop and could not confirm it did — a stop is done
/// on an ack *plus* telemetry showing the belt at rest (`docs/PLAN.md` §5.4),
/// and this is the state where one or both never arrived.
///
/// Every string here is resolved on the host. `MachineLink` and
/// `StopEscalation` own the safety wording, and a second copy of it in Dart is
/// a second thing to get wrong on the one screen where wording matters most.
@immutable
class StopEscalationState {
  const StopEscalationState({
    required this.active,
    this.reason,
    this.detail = '',
    this.instruction = '',
  });

  const StopEscalationState.clear()
    : active = false,
      reason = null,
      detail = '',
      instruction = '';

  factory StopEscalationState.fromMap(Map<String, dynamic> map) {
    return StopEscalationState(
      // Absent must read as "not raised", never as raised: this value gates a
      // modal warning, and a bridge that failed to answer must not manufacture
      // an alarm out of nothing. The host is the only thing that raises it.
      active: map['active'] == true,
      reason: map['reason'] as String?,
      detail: (map['detail'] as String?) ?? '',
      instruction: (map['instruction'] as String?) ?? '',
    );
  }

  final bool active;

  /// Which check failed, as a `StopUnconfirmed` name. For display only.
  final String? reason;

  /// What the host saw, in a sentence.
  final String detail;

  /// What to do about it. Identical on every branch, because there is only one
  /// thing to do about any of them.
  final String instruction;
}

abstract class WorkoutBridgeClient {  Future<String> workoutState();
  Future<int> workoutElapsedMs();
  Future<bool> workoutStart();
  Future<bool> workoutPause();
  Future<bool> workoutResume();
  Future<int> workoutStop();
  Future<bool> workoutCancelStart();
  Future<StopEscalationState> stopEscalation();
  Future<bool> stopEscalationAcknowledge();
  Future<WorkoutVolume> volumeGet();
  Future<bool> volumeSet(int level);
  Future<MachineSnapshot> machineSnapshot();
}

class MethodChannelWorkoutBridge implements WorkoutBridgeClient {  const MethodChannelWorkoutBridge();

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
  Future<StopEscalationState> stopEscalation() async =>
      StopEscalationState.fromMap(await SpikeBridge.stopEscalation());

  @override
  Future<bool> stopEscalationAcknowledge() =>
      SpikeBridge.stopEscalationAcknowledge();

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
    this.canCommandSpeed = false,
    this.canCommandIncline = false,
    this.canCommandFan = false,
    this.speedNotice,
    this.inclineNotice,
    this.fanNotice,
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
      canCommandSpeed: map['canCommandSpeed'] == true,
      canCommandIncline: map['canCommandIncline'] == true,
      canCommandFan: map['canCommandFan'] == true,
      speedNotice: _asNonEmptyString(map['speedNotice']),
      inclineNotice: _asNonEmptyString(map['inclineNotice']),
      fanNotice: _asNonEmptyString(map['fanNotice']),
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

  /// Whether each individual control can be used right now.
  ///
  /// Separate from [canCommand] because they answer different questions.
  /// [canCommand] is about Stride's link; these are about whether this machine
  /// accepts that particular setpoint at this moment — a treadmill with no
  /// incline motor never will, and one sitting idle will not until a workout
  /// starts. A control that fails either must be drawn unavailable rather than
  /// left live to silently do nothing.
  final bool canCommandSpeed;
  final bool canCommandIncline;
  final bool canCommandFan;

  /// What to say when each control is tapped while unavailable. Resolved by the
  /// host, which owns every safety sentence; a second copy of the rule here
  /// would be a second thing to get wrong.
  final String? speedNotice;
  final String? inclineNotice;
  final String? fanNotice;

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
  StopEscalationState _stopEscalation = const StopEscalationState.clear();
  String? _lastError;

  String get state => _state;
  int get elapsedMs => _elapsedMs;
  WorkoutVolume get volume => _volume;
  MachineSnapshot get machine => _machine;
  String? get lastError => _lastError;

  /// The "USE THE SAFETY KEY" latch. Non-dismissible while [
  /// StopEscalationState.active], and the host refuses to start a workout while
  /// it is up, so a screen that did not draw it would leave a rider with a dead
  /// Start button and no explanation.
  StopEscalationState get stopEscalation => _stopEscalation;

  /// Clear the latch, because the rider says they have dealt with it.
  Future<void> acknowledgeStopEscalation() async {
    await _safe(() => _bridge.stopEscalationAcknowledge(), false);
    await refreshStopEscalation();
  }

  Future<void> refreshStopEscalation() async {
    _stopEscalation = await _safe(
      () => _bridge.stopEscalation(),
      // A bridge that could not answer is not evidence of a raised latch. The
      // host is the only thing that raises one.
      const StopEscalationState.clear(),
    );
    _notify();
  }

  bool get isRunning => _state == 'running';
  bool get isPaused => _state == 'paused';
  bool get isIdle => _state == 'idle';

  /// The rider has asked to start and the treadmill has not answered yet.
  ///
  /// Its own state rather than a flavour of running, because nothing is running: the belt is still,
  /// the clock has not begun, and the only honest thing to show is that we are waiting.
  bool get isStarting => _state == 'starting';

  /// A stop has been sent and nothing has confirmed the belt is at rest.
  ///
  /// The mirror of [isStarting], and issue #39. A stop is done on ack *plus* observed deceleration,
  /// and this is the state in which neither has arrived — so it is emphatically **not** idle, and
  /// anything that would offer to start a belt must treat it as such.
  bool get isStopping => _state == 'stopping';

  Future<void> load() async {
    await _refreshWorkout();
    await refreshVolume();
    await refreshMachine();
    await refreshStopEscalation();
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
    // Not 'idle', for the same reason [startWorkout] is not 'running'. The stop has been sent and
    // nothing has confirmed the belt is at rest; the host owns that answer and announces it on the
    // state channel. Claiming idle here would be the launcher telling a rider their treadmill has
    // stopped on the strength of having asked it to — issue #39.
    _state = 'stopping';
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
    // Every path that can raise the latch ends in a state change — a stop that
    // settled unconfirmed, or an abandoned start whose stop could not be
    // confirmed either. This is what makes the warning appear on a launcher
    // with no overlay up.
    unawaited(refreshStopEscalation());
  }

  void _syncTicker() {
    // Also ticks while starting and while stopping. The host owns both states and announces the
    // change, but a missed event would otherwise leave the launcher showing "Starting…" or
    // "Stopping…" forever with no poll to correct it — so the pending states re-read the host
    // rather than trusting one notification. Stopping matters more than starting here: the state
    // it is waiting to leave is the one that withholds the Start control.
    if (_state != 'running' && _state != 'starting' && _state != 'stopping') {
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
      if (_state == 'starting' || _state == 'stopping') {
        // The one thing worth knowing while starting or stopping is whether it still is.
        final hostState = await _safe(() => _bridge.workoutState(), _state);
        if (_disposed) return;
        final next = _normalizeState(hostState);
        if (next != _state) {
          final wasStopping = _state == 'stopping';
          _state = next;
          _syncTicker();
          // Leaving "Stopping…" is the moment the verdict landed, and an
          // unconfirmed one has just raised the latch. Read it now rather than
          // waiting for the next state change, because there may not be one:
          // the session is idle and the ticker has just stopped.
          if (wasStopping) unawaited(refreshStopEscalation());
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
    // The host says this while a stop is on the wire and nothing has confirmed the belt is at
    // rest. Folding it into 'idle' — which is what the catch-all below used to do to it — would
    // put "Start workout" back on screen over exactly the belt this state exists to be honest
    // about. See WorkoutSession.State.STOPPING and issue #39.
    'stopping' => 'stopping',
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
