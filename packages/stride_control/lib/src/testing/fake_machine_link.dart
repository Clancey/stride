/// A fully in-memory [MachineLink] for tests. It transmits and reports, exactly
/// like a real link, and makes no safety decisions. On top of that it exposes
/// fault injection and a crude belt physics model so failure modes can be driven
/// deterministically under `fake_async`.
///
/// Everything time-based here runs on [Timer]s so `fake_async` controls it; no
/// test ever sleeps.
library;

import 'dart:async';

import '../machine_events.dart';
import '../machine_link.dart';
import '../types.dart';

/// How a send should behave, for fault injection.
enum AckMode {
  /// Ack after [FakeMachineLink.ackDelay].
  normal,

  /// Never ack. Models a lost ack / half-open link.
  drop,

  /// Complete the send future with a [LinkException]. Models network loss.
  fail,
}

class FakeMachineLink implements MachineLink, MachineEventSource {
  FakeMachineLink({
    ControlRanges? ranges,
    this.ackDelay = const Duration(milliseconds: 50),
    this.telemetryPeriod = const Duration(milliseconds: 100),
    this.beltAccelKph = 8.0,
    this.inclineSlewPercent = 8.0,
    DateTime Function()? clock,
  })  : _ranges = ranges ?? _defaultRanges,
        _clock = clock ?? DateTime.now;

  static const ControlRanges _defaultRanges = ControlRanges(
    speed: Range(min: 0, max: 19.3, step: 0.1, writable: true),
    incline: Range(min: -3, max: 15, step: 0.5, writable: true),
    resistance: Range.readOnly(),
    machineType: 'fake-1750',
  );

  final Duration ackDelay;
  final Duration telemetryPeriod;
  final double beltAccelKph;
  final double inclineSlewPercent;
  final DateTime Function() _clock;

  ControlRanges _ranges;

  final _stateCtrl = StreamController<LinkState>.broadcast();
  final _sampleCtrl = StreamController<MetricSample>.broadcast();
  final _eventCtrl = StreamController<MachineEvent>.broadcast();

  LinkState _state = LinkState.disconnected;
  Timer? _physics;

  double _actualSpeed = 0;
  double _targetSpeed = 0;
  double _actualIncline = 0;
  double _targetIncline = 0;

  // Fault-injection switches.
  AckMode ackMode = AckMode.normal;
  bool telemetryStalled = false;
  bool failConnect = false;

  // Concurrency instrumentation: how many motion sends are awaiting ack right
  // now, and the high-water mark. The coordinator's serialization contract means
  // [maxConcurrentMotionSends] must never exceed 1.
  int _inFlightMotionSends = 0;
  int maxConcurrentMotionSends = 0;

  // Call counters, for "no auto-start" and preemption assertions.
  int speedSends = 0;
  int inclineSends = 0;
  int stopSends = 0;

  @override
  Stream<LinkState> get state => _stateCtrl.stream;

  @override
  LinkState get currentState => _state;

  @override
  ControlRanges get ranges => _ranges;

  @override
  Stream<MetricSample> get samples => _sampleCtrl.stream;

  @override
  Stream<MachineEvent> get events => _eventCtrl.stream;

  @override
  Future<void> connect() async {
    _setState(LinkState.connecting);
    if (failConnect) {
      _setState(LinkState.faulted);
      throw LinkException('connect refused');
    }
    _setState(LinkState.connected);
    _startPhysics();
  }

  @override
  Future<void> sendSpeed(double kph) {
    speedSends++;
    _targetSpeed = kph;
    return _motionAck();
  }

  @override
  Future<void> sendIncline(double percent) {
    inclineSends++;
    _targetIncline = percent;
    return _motionAck();
  }

  @override
  Future<void> sendStop() {
    stopSends++;
    _targetSpeed = 0;
    // A native stop is authoritative regardless of the speed-set ack policy, so
    // it always acks unless the link itself is configured to fail.
    if (ackMode == AckMode.fail) {
      return Future.error(LinkException('stop send failed'));
    }
    final c = Completer<void>();
    Timer(ackDelay, () {
      if (!c.isCompleted) c.complete();
    });
    return c.future;
  }

  @override
  Future<void> dispose() async {
    _physics?.cancel();
    _physics = null;
    await _stateCtrl.close();
    await _sampleCtrl.close();
    await _eventCtrl.close();
  }

  // --- Test controls --------------------------------------------------------

  /// Preload belt motion so a coordinator can attach to an already-moving belt.
  void primeMovingBelt(Speed speed) {
    _actualSpeed = speed.kph;
    _targetSpeed = speed.kph;
  }

  void setRanges(ControlRanges ranges) => _ranges = ranges;

  /// Drop the link (transport fault) without a clean disconnect.
  void fault() {
    _physics?.cancel();
    _physics = null;
    _setState(LinkState.faulted);
  }

  /// Bring the link back and resume telemetry (models a GlassOS restart).
  void restart() {
    _setState(LinkState.connecting);
    _setState(LinkState.connected);
    _startPhysics();
  }

  /// Pull the physical safety key: hardware cuts the belt immediately and the
  /// removal is reported as an event.
  void pullSafetyKey() {
    _targetSpeed = 0;
    _actualSpeed = 0;
    _emitSpeed();
    _eventCtrl.add(const SafetyKeyRemoved());
  }

  void insertSafetyKey() => _eventCtrl.add(const SafetyKeyReinserted());

  /// Press the console's hardware Stop button.
  void pressStopButton() {
    _targetSpeed = 0;
    _actualSpeed = 0;
    _emitSpeed();
    _eventCtrl.add(const ExternalStop());
  }

  /// Change speed from the console's physical buttons.
  void externalSetSpeed(Speed speed) {
    _targetSpeed = speed.kph;
    _eventCtrl.add(ExternalSpeedChange(speed));
  }

  double get actualSpeedKph => _actualSpeed;

  // --- Internals ------------------------------------------------------------

  Future<void> _motionAck() {
    _inFlightMotionSends++;
    if (_inFlightMotionSends > maxConcurrentMotionSends) {
      maxConcurrentMotionSends = _inFlightMotionSends;
    }
    switch (ackMode) {
      case AckMode.normal:
        final c = Completer<void>();
        Timer(ackDelay, () {
          _inFlightMotionSends--;
          if (!c.isCompleted) c.complete();
        });
        return c.future;
      case AckMode.drop:
        // Never acks, but it is no longer occupying the transport for the
        // purpose of the concurrency check once the coordinator times it out;
        // decrement immediately so a later stop is not counted as concurrent.
        _inFlightMotionSends--;
        return Completer<void>().future;
      case AckMode.fail:
        _inFlightMotionSends--;
        return Future.error(LinkException('send failed'));
    }
  }

  void _setState(LinkState s) {
    _state = s;
    if (!_stateCtrl.isClosed) _stateCtrl.add(s);
  }

  void _startPhysics() {
    _physics?.cancel();
    _physics = Timer.periodic(telemetryPeriod, (_) => _tick());
  }

  void _tick() {
    final dt = telemetryPeriod.inMicroseconds / 1e6;
    _actualSpeed = _approach(_actualSpeed, _targetSpeed, beltAccelKph * dt);
    _actualIncline =
        _approach(_actualIncline, _targetIncline, inclineSlewPercent * dt);
    if (!telemetryStalled) {
      _emitSpeed();
      _emitIncline();
    }
  }

  void _emitSpeed() {
    if (_sampleCtrl.isClosed) return;
    _sampleCtrl.add(MetricSample(
      kind: MetricKind.speed,
      value: _actualSpeed,
      timestamp: _clock(),
      source: 'fake',
    ));
  }

  void _emitIncline() {
    if (_sampleCtrl.isClosed) return;
    _sampleCtrl.add(MetricSample(
      kind: MetricKind.incline,
      value: _actualIncline,
      timestamp: _clock(),
      source: 'fake',
    ));
  }

  static double _approach(double current, double target, double maxStep) {
    final delta = target - current;
    if (delta.abs() <= maxStep) return target;
    return current + (delta.isNegative ? -maxStep : maxStep);
  }
}
