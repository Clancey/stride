/// Simulated treadmill: belt physics, incline motor, workout state, safety key.
///
/// The belt does NOT jump to the commanded speed. It ramps at a bounded rate and
/// telemetry reports the actual instantaneous speed. This matters because the
/// coordinator built in parallel confirms a stop by OBSERVING deceleration in
/// telemetry, not just by receiving an ack (docs/PLAN.md 5.4).
library;

import 'dart:async';

import 'fault_injection.dart';
import 'messages.dart';

class MockMachine {
  MockMachine({ConsoleInfo? console, FaultState? faults})
    : console = console ?? const ConsoleInfo(),
      faults = faults ?? FaultState();

  final ConsoleInfo console;
  final FaultState faults;

  // --- Actuator state (units: kph, percent) ---
  double _speedKph = 0.0;
  double _targetSpeedKph = 0.0;
  double _inclinePercent = 0.0;
  double _targetInclinePercent = 0.0;

  // Generation ids for stale-command rejection (docs/PLAN.md 3.1 / 5.8).
  int _speedGeneration = 0;
  int _inclineGeneration = 0;

  int _workoutState = WorkoutState.idle;
  bool _safetyKeyPresent = true;

  // --- Ramp rates ---
  // Representative, not measured. A treadmill reaches speed over a couple of
  // seconds. Acceleration is bounded (safety: ramp-limited). Deceleration is
  // faster and is NEVER ramp-limited in the coordinator; here it is a fast but
  // finite ramp so telemetry actually SHOWS deceleration rather than teleporting
  // to zero. A safety-key pull decelerates faster still (belt power cut).
  static const double _accelKphPerSec = 1.6;
  static const double _decelKphPerSec = 4.0;
  static const double _keyCutDecelKphPerSec = 12.0;
  static const double _inclineSlewPercentPerSec = 2.0;

  Timer? _ticker;
  static const Duration tickInterval = Duration(milliseconds: 100);

  final StreamController<SpeedSample> _speed =
      StreamController<SpeedSample>.broadcast();
  final StreamController<InclineSample> _incline =
      StreamController<InclineSample>.broadcast();
  final StreamController<WorkoutStateChanged> _workout =
      StreamController<WorkoutStateChanged>.broadcast();

  Stream<SpeedSample> get speedStream => _speed.stream;
  Stream<InclineSample> get inclineStream => _incline.stream;
  Stream<WorkoutStateChanged> get workoutStream => _workout.stream;

  double get speedKph => _speedKph;
  double get inclinePercent => _inclinePercent;
  int get workoutState => _workoutState;
  bool get safetyKeyPresent => _safetyKeyPresent;

  int get _nowMs => DateTime.now().millisecondsSinceEpoch;

  void start() {
    _ticker ??= Timer.periodic(tickInterval, (_) => _tick());
  }

  Future<void> dispose() async {
    _ticker?.cancel();
    _ticker = null;
    await _speed.close();
    await _incline.close();
    await _workout.close();
  }

  double _clampSpeed(double v) => v.clamp(console.minKph, console.maxKph);
  double _clampIncline(double v) =>
      v.clamp(console.minInclinePercent, console.maxInclinePercent);

  /// Commands a new target speed. Returns the applied target and the generation
  /// id assigned to this command. If the safety key is out the target is forced
  /// to zero regardless of the request.
  ({double appliedTarget, int generation}) setSpeed(double kph) {
    _speedGeneration++;
    if (!_safetyKeyPresent) {
      _targetSpeedKph = 0.0;
      return (appliedTarget: 0.0, generation: _speedGeneration);
    }
    _targetSpeedKph = _clampSpeed(kph);
    return (appliedTarget: _targetSpeedKph, generation: _speedGeneration);
  }

  ({double appliedTarget, int generation}) setIncline(double percent) {
    _inclineGeneration++;
    _targetInclinePercent = _clampIncline(percent);
    return (appliedTarget: _targetInclinePercent, generation: _inclineGeneration);
  }

  // --- Workout lifecycle ---

  void startWorkout({String source = 'app'}) =>
      _setWorkout(WorkoutState.running, source);

  void pauseWorkout({String source = 'app'}) {
    // Pausing brings the belt to a stop but keeps the session resumable.
    _targetSpeedKph = 0.0;
    _setWorkout(WorkoutState.paused, source);
  }

  void resumeWorkout({String source = 'app'}) =>
      _setWorkout(WorkoutState.running, source);

  void stopWorkout({String source = 'app'}) {
    _targetSpeedKph = 0.0;
    _setWorkout(WorkoutState.summary, source);
  }

  void _setWorkout(int state, String source) {
    _workoutState = state;
    _emitWorkout(source);
  }

  void _emitWorkout(String source) {
    if (_workout.isClosed) return;
    _workout.add(
      WorkoutStateChanged(
        state: _workoutState,
        source: source,
        timestampMs: _nowMs,
      ),
    );
  }

  // --- Fault: safety key ---

  /// Pulls the safety key. Belt is cut hard and the state latches to paused
  /// until [reinsertSafetyKey]. docs/PLAN.md 5.1: safety-key removal latches
  /// until explicit local reset, and the key is the only true emergency stop.
  void pullSafetyKey() {
    _safetyKeyPresent = false;
    _targetSpeedKph = 0.0;
    _setWorkout(WorkoutState.paused, 'safety_key');
  }

  void reinsertSafetyKey() {
    _safetyKeyPresent = true;
    _emitWorkout('safety_key');
  }

  /// Fault: the controlling client vanished. Applies the configured policy.
  void onClientLost() {
    switch (faults.clientLostBehavior) {
      case ClientLostBehavior.keepMoving:
        // Intentionally do nothing: belt keeps running.
        break;
      case ClientLostBehavior.stopBelt:
        _targetSpeedKph = 0.0;
    }
  }

  void _tick() {
    final dt = tickInterval.inMilliseconds / 1000.0;

    // Speed ramp.
    if (_speedKph != _targetSpeedKph) {
      final decelRate = _safetyKeyPresent ? _decelKphPerSec : _keyCutDecelKphPerSec;
      final rate = _targetSpeedKph > _speedKph ? _accelKphPerSec : decelRate;
      final step = rate * dt;
      if ((_targetSpeedKph - _speedKph).abs() <= step) {
        _speedKph = _targetSpeedKph;
      } else {
        _speedKph += _targetSpeedKph > _speedKph ? step : -step;
      }
    }

    // Incline slew.
    if (_inclinePercent != _targetInclinePercent) {
      final step = _inclineSlewPercentPerSec * dt;
      if ((_targetInclinePercent - _inclinePercent).abs() <= step) {
        _inclinePercent = _targetInclinePercent;
      } else {
        _inclinePercent += _targetInclinePercent > _inclinePercent ? step : -step;
      }
    }

    // Telemetry. When stallTelemetry is set we suppress emission even though the
    // belt above is still moving. That is the whole point of that fault.
    if (!faults.stallTelemetry) {
      if (!_speed.isClosed) {
        _speed.add(
          SpeedSample(
            kph: _speedKph,
            targetKph: _targetSpeedKph,
            timestampMs: _nowMs,
          ),
        );
      }
      if (!_incline.isClosed) {
        _incline.add(
          InclineSample(
            percent: _inclinePercent,
            targetPercent: _targetInclinePercent,
            timestampMs: _nowMs,
          ),
        );
      }
    }
  }
}
