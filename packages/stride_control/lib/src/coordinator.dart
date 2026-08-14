/// The Control & Safety Coordinator (PLAN.md 3.1). Nothing commands the motor
/// except through this. It is the sole owner of device and workout motion state;
/// every UI and companion is a client of it.
///
/// The invariants it enforces are the whole reason the class exists, so each is
/// documented where it lives. Future readers: these are not incidental. Removing
/// any of them reintroduces a way for the belt to hurt someone.
library;

import 'dart:async';
import 'dart:collection';

import 'control_limits.dart';
import 'machine_events.dart';
import 'machine_link.dart';
import 'safety_state.dart';
import 'types.dart';

enum _Axis { speed, incline }

/// One queued transport write. Motion writes are the only thing that goes on the
/// serialized queue; a stop deliberately bypasses it (see [Coordinator.stop]).
class _Transmit {
  _Transmit(this.axis, this.requested, this.generation)
      : completer = Completer<CommandResult>();

  final _Axis axis;
  final double requested;
  final int generation;
  final Completer<CommandResult> completer;
}

/// Outcome of racing an ack against a confirming telemetry change and a timeout.
enum _ConfirmKind { ack, telemetry, timeout, error }

/// Pending "did telemetry reach the value I commanded" hook, consulted by the
/// samples listener so a command can be confirmed by observation when the ack is
/// dropped (PLAN.md 3.1: "ack or a confirming telemetry change").
class _PendingConfirm {
  _PendingConfirm(this.axis, this.target, this.tolerance, this.onReached);
  final _Axis axis;
  final double target;
  final double tolerance;
  final void Function() onReached;
}

/// Pending positive-stop-confirmation state. A stop is only "done" on ack PLUS
/// observed deceleration; this tracks both halves (PLAN.md 5.4).
class _StopConfirm {
  _StopConfirm(this.generation);
  final int generation;
  bool ackReceived = false;
  bool decelObserved = false;
  bool settled = false;
  Timer? deadline;
  final Completer<CommandResult> completer = Completer<CommandResult>();
}

class Coordinator {
  Coordinator({
    required MachineLink link,
    ControlLimits limits = const ControlLimits(),
    ProfileLimits? profile,
    DateTime Function()? clock,
  })  // Private named fields cannot be initializing formals, so assign here.
  // ignore: prefer_initializing_formals
      : _link = link,
        // ignore: prefer_initializing_formals
        _limits = limits,
        // ignore: prefer_initializing_formals
        _profile = profile,
        _clock = clock ?? DateTime.now;

  final MachineLink _link;
  final ControlLimits _limits;
  ProfileLimits? _profile;

  /// Injectable wall clock. The acceleration limit is the only logic measured in
  /// real time (everything else uses timers), so tests inject a clock backed by
  /// `fake_async` to keep it deterministic without a package dependency.
  final DateTime Function() _clock;

  final _states = StreamController<SafetyState>.broadcast();
  final _events = StreamController<SafetyEvent>.broadcast();

  StreamSubscription<LinkState>? _linkSub;
  StreamSubscription<MetricSample>? _sampleSub;
  StreamSubscription<MachineEvent>? _eventSub;

  final Queue<_Transmit> _queue = Queue<_Transmit>();
  bool _pumping = false;
  Future<void>? _inFlight;

  _PendingConfirm? _pendingConfirm;
  _StopConfirm? _stopConfirm;
  Timer? _watchdog;

  ControlRanges _ranges = const ControlRanges.unknown();

  /// The generation is the identity of the currently valid command context. It
  /// is bumped on stop, disconnect, reconnect, safety-key latch and external
  /// stop. Every command carries the generation it was issued under; anything
  /// whose generation no longer matches is discarded. This is what prevents a
  /// delayed "set 8 kph" from landing after the user hit stop.
  int _generation = 1;

  double _commandedSpeed = 0;
  double _commandedIncline = 0;
  double _observedSpeed = 0;

  MotionPhase _phase = MotionPhase.offline;
  bool _latched = false;
  bool _keyPresent = true;
  EscalationReason _escalation = EscalationReason.none;
  String? _escalationMessage;

  /// Time of the last transmitted speed/incline setpoint, used to convert the
  /// acceleration limit (a rate) into a per-command maximum step.
  DateTime? _lastSpeedTxAt;
  DateTime? _lastInclineTxAt;

  bool _awaitingReconcile = false;
  bool _disposed = false;

  DateTime _now() => _clock();

  // --- Public surface -------------------------------------------------------

  Stream<SafetyState> get states => _states.stream;
  Stream<SafetyEvent> get events => _events.stream;

  SafetyState get state => _snapshot();

  int get generation => _generation;
  bool get latched => _latched;

  /// Whether the safety key is currently inserted. Reinserting the key does not
  /// clear the latch; see [resetLatch].
  bool get keyPresent => _keyPresent;

  MotionPhase get phase => _phase;
  ControlRanges get ranges => _ranges;
  Speed get commandedSpeed => Speed.kph(_commandedSpeed);
  Speed get observedSpeed => Speed.kph(_observedSpeed);

  /// Subscribe to the link and connect. Connecting never moves the belt.
  ///
  /// No auto-start: nothing in this method (or in any reconnect it later drives)
  /// issues a motion command. Only an explicit [setSpeed]/[setIncline] starts
  /// motion (PLAN.md 5.6).
  Future<void> start() async {
    _linkSub = _link.state.listen(_onLinkState);
    _sampleSub = _link.samples.listen(_onSample);
    final source = _link;
    if (source is MachineEventSource) {
      _eventSub = (source as MachineEventSource).events.listen(_onMachineEvent);
    }
    _awaitingReconcile = true;
    await _link.connect();
    _ranges = _link.ranges;
    _onLinkState(_link.currentState);
  }

  /// Command a target belt speed under the given [generation].
  ///
  /// The generation must be the one in effect when the caller decided to act. If
  /// it is stale by the time the command is enqueued or executed, or by the time
  /// its ack returns, the command is discarded as [CommandStatus.superseded].
  Future<CommandResult> setSpeed(Speed target, {required int generation}) {
    return _enqueueMotion(_Axis.speed, target.kph, generation);
  }

  /// Command a target incline under the given [generation]. See [setSpeed].
  Future<CommandResult> setIncline(Percent target, {required int generation}) {
    return _enqueueMotion(_Axis.incline, target.value, generation);
  }

  /// Stop the belt. Preempts everything: it cancels every queued command, is
  /// never rate-limited and is never ramp-delayed (PLAN.md 3.1/5.2).
  ///
  /// A stop is only reported successful on ack PLUS observed deceleration. If
  /// neither arrives the coordinator escalates to "USE THE SAFETY KEY" rather
  /// than claiming the belt is stopped (PLAN.md 5.4).
  Future<CommandResult> stop() => _performStop();

  /// Clear the safety-key latch. This is the only thing that can clear it, and it
  /// is deliberately a local call: a latch cannot be released by a queued
  /// command, a companion app, or the key merely being reinserted (PLAN.md 3.1).
  ///
  /// Resetting the latch never starts the belt.
  void resetLatch() {
    if (!_latched) return;
    _latched = false;
    _escalation = EscalationReason.none;
    _escalationMessage = null;
    _phase = _link.currentState == LinkState.connected
        ? MotionPhase.idle
        : MotionPhase.offline;
    _events.add(const LatchReset());
    _emit();
  }

  /// Replace the profile limits. Rejected while the belt is moving (PLAN.md 3.6)
  /// and never starts motion. Returns false if rejected.
  bool setProfile(ProfileLimits? profile) {
    if (_phase == MotionPhase.moving || _phase == MotionPhase.stopping) {
      return false;
    }
    _profile = profile;
    _emit();
    return true;
  }

  Future<void> dispose() async {
    _disposed = true;
    _watchdog?.cancel();
    _stopConfirm?.deadline?.cancel();
    await _linkSub?.cancel();
    await _sampleSub?.cancel();
    await _eventSub?.cancel();
    for (final t in _queue) {
      if (!t.completer.isCompleted) {
        t.completer.complete(_result(t, CommandStatus.superseded,
            reason: 'coordinator disposed'));
      }
    }
    _queue.clear();
    await _link.dispose();
    await _states.close();
    await _events.close();
  }

  // --- Command enqueue / execution -----------------------------------------

  Future<CommandResult> _enqueueMotion(_Axis axis, double requested, int gen) {
    // Latch takes precedence over everything. While latched, no motion command
    // may transmit, no matter its generation.
    if (_latched) {
      return Future.value(CommandResult(
        status: CommandStatus.latched,
        requested: requested,
        reason: 'safety-key latch engaged',
      ));
    }
    if (gen != _generation) {
      return Future.value(CommandResult(
        status: CommandStatus.superseded,
        requested: requested,
        reason: 'stale generation $gen (current $_generation)',
      ));
    }
    final range = axis == _Axis.speed ? _ranges.speed : _ranges.incline;
    if (!range.writable) {
      return Future.value(CommandResult(
        status: CommandStatus.rejected,
        requested: requested,
        reason: '${axis.name} is not writable on this machine',
      ));
    }
    // Accepting an explicit command means a session is actively driving the
    // belt, so we must stop trying to silently adopt an observed speed. Attach
    // reconciliation is only for the no-command case right after a connect; if
    // we kept reconciling we would clobber the commanded value with a
    // partially-ramped telemetry reading and fight the operator.
    _awaitingReconcile = false;
    final t = _Transmit(axis, requested, gen);
    _queue.add(t);
    unawaited(_pump());
    return t.completer.future;
  }

  Future<void> _pump() async {
    if (_pumping) return;
    _pumping = true;
    try {
      while (_queue.isNotEmpty) {
        final t = _queue.removeFirst();
        await _execute(t);
      }
    } finally {
      _pumping = false;
    }
  }

  Future<void> _execute(_Transmit t) async {
    // Re-check generation at execution time: a stop, disconnect or latch may
    // have superseded this command while it sat in the queue.
    if (t.generation != _generation) {
      _complete(t, CommandStatus.superseded, reason: 'superseded before send');
      return;
    }
    if (_latched) {
      _complete(t, CommandStatus.latched, reason: 'latched before send');
      return;
    }

    final applied = _applyLimits(t.axis, t.requested);

    if (t.axis == _Axis.speed) {
      _commandedSpeed = applied;
      _lastSpeedTxAt = _now();
    } else {
      _commandedIncline = applied;
      _lastInclineTxAt = _now();
    }
    if (_commandedSpeed > 0) {
      _enterMoving();
    }
    _emit();

    final ackFuture = _send(t.axis, applied);
    _inFlight = ackFuture;

    // Serialization: the worker holds the transport until this write's future
    // settles or the command deadline elapses, so no two motion writes are ever
    // outstanding at once (PLAN.md 3.1). Telemetry can substitute for a missing
    // ack in the reported status, but it does NOT release the transport early -
    // that is what keeps "one in flight" true even when the belt visibly
    // responds before the ack lands.
    var telemetryConfirmed = false;
    final tolerance = t.axis == _Axis.speed
        ? _limits.telemetryToleranceKph
        : _limits.telemetryTolerancePercent;
    _pendingConfirm = _PendingConfirm(t.axis, applied, tolerance, () {
      telemetryConfirmed = true;
    });

    _ConfirmKind kind;
    Object? error;
    try {
      await ackFuture.timeout(_limits.commandTimeout);
      kind = _ConfirmKind.ack;
    } on TimeoutException {
      kind = telemetryConfirmed ? _ConfirmKind.telemetry : _ConfirmKind.timeout;
    } on LinkException catch (e) {
      error = e;
      kind = telemetryConfirmed ? _ConfirmKind.telemetry : _ConfirmKind.error;
    }
    _inFlight = null;
    _pendingConfirm = null;

    // A late ack must not resurrect state after a stop. If the generation moved
    // while we were in flight, the result is void - this is the highest-value
    // safety check in the class (PLAN.md 3.1 generation IDs).
    if (t.generation != _generation) {
      _complete(t, CommandStatus.superseded, reason: 'superseded in flight');
      return;
    }

    switch (kind) {
      case _ConfirmKind.ack:
      case _ConfirmKind.telemetry:
        // Adjusted if what we sent differs from what the caller asked for,
        // whether the difference came from a device/profile clamp or the
        // acceleration limit.
        final adjusted = (applied - t.requested).abs() > 1e-9;
        _complete(t, adjusted ? CommandStatus.adjusted : CommandStatus.applied,
            applied: applied);
      case _ConfirmKind.timeout:
        _complete(t, CommandStatus.timedOut, applied: applied,
            reason: 'no ack or telemetry within ${_limits.commandTimeout}');
      case _ConfirmKind.error:
        _complete(t, CommandStatus.linkFailure, applied: applied,
            reason: '$error');
    }
  }

  Future<void> _send(_Axis axis, double value) {
    return axis == _Axis.speed
        ? _link.sendSpeed(value)
        : _link.sendIncline(value);
  }

  // --- Limits / clamping ----------------------------------------------------

  double _clampToRangeOnly(_Axis axis, double v) {
    if (axis == _Axis.speed) {
      final r = _ranges.speed;
      var out = r.clamp(v);
      final pmax = _profile?.maxSpeed?.kph;
      if (pmax != null && out > pmax) out = pmax;
      return out;
    } else {
      final r = _ranges.incline;
      var out = r.clamp(v);
      final pmin = _profile?.minIncline?.value;
      final pmax = _profile?.maxIncline?.value;
      if (pmin != null && out < pmin) out = pmin;
      if (pmax != null && out > pmax) out = pmax;
      return out;
    }
  }

  /// Apply device+profile clamps and then the acceleration limit.
  ///
  /// The acceleration limit constrains *increases only*. Any decrease is applied
  /// immediately: decelerating is always safe and must never be rate-limited
  /// (PLAN.md 5.2).
  double _applyLimits(_Axis axis, double requested) {
    final clamped = _clampToRangeOnly(axis, requested);
    final current = axis == _Axis.speed ? _commandedSpeed : _commandedIncline;
    if (clamped <= current) return clamped;

    final lastTx = axis == _Axis.speed ? _lastSpeedTxAt : _lastInclineTxAt;
    final rate =
        axis == _Axis.speed ? _limits.maxSpeedAccel : _limits.maxInclineSlew;
    if (lastTx == null || rate <= 0) return clamped;

    final elapsed = _now().difference(lastTx).inMicroseconds / 1e6;
    final maxDelta = rate * elapsed;
    final allowed = current + maxDelta;
    return clamped <= allowed ? clamped : allowed;
  }

  // --- Stop -----------------------------------------------------------------

  Future<CommandResult> _performStop({EscalationReason? escalateOnFail}) async {
    // Bump generation first: this instantly invalidates every queued and
    // in-flight command so nothing can move the belt behind the stop.
    _bumpGeneration();
    _drainQueue(reason: 'preempted by stop');
    _commandedSpeed = 0;
    // Do not downgrade an already-escalated state to "stopping". When the
    // watchdog or a failed stop escalates, that state must persist through the
    // best-effort stop attempt so consumers keep rendering "USE THE SAFETY KEY"
    // rather than a reassuring "stopping".
    if (_phase != MotionPhase.escalated) {
      _phase = MotionPhase.stopping;
    }
    _emit();

    // Serialization: never issue a stop concurrently with a motion write that is
    // still settling. We wait for the in-flight write, but only up to a short
    // grace, so a hung write (dropped ack) cannot delay the emergency stop. Past
    // the grace the in-flight write is presumed lost - its late ack is already
    // void by generation - and the stop transmits regardless.
    final inFlight = _inFlight;
    if (inFlight != null) {
      await Future.any<void>([
        inFlight.catchError((_) {}),
        Future<void>.delayed(_limits.stopPreemptGrace),
      ]);
    }

    final sc = _StopConfirm(_generation);
    _stopConfirm = sc;
    if (_observedSpeed <= _limits.stopConfirmSpeedKph) sc.decelObserved = true;

    Future<void> ackFuture;
    try {
      ackFuture = _link.sendStop();
    } on LinkException catch (e) {
      return _failStop(sc, escalateOnFail ?? EscalationReason.stopNotConfirmed,
          'stop transmit failed: $e');
    }

    unawaited(ackFuture.then((_) {
      sc.ackReceived = true;
      _tryConfirmStop(sc);
    }).catchError((Object e) {
      _failStop(sc, escalateOnFail ?? EscalationReason.stopNotConfirmed,
          'stop link failure: $e');
    }));

    sc.deadline = Timer(_limits.stopConfirmTimeout, () {
      _failStop(sc, escalateOnFail ?? EscalationReason.stopNotConfirmed,
          'stop not confirmed within ${_limits.stopConfirmTimeout}');
    });

    _tryConfirmStop(sc);
    return sc.completer.future;
  }

  void _tryConfirmStop(_StopConfirm sc) {
    if (sc.settled) return;
    if (sc.ackReceived && sc.decelObserved) {
      sc.settled = true;
      sc.deadline?.cancel();
      if (identical(_stopConfirm, sc)) _stopConfirm = null;
      _phase = MotionPhase.idle;
      _escalation = EscalationReason.none;
      _escalationMessage = null;
      _events.add(const StopConfirmed());
      _emit();
      if (!sc.completer.isCompleted) {
        sc.completer.complete(const CommandResult(
          status: CommandStatus.applied,
          requested: 0,
          applied: 0,
        ));
      }
    }
  }

  CommandResult _failStop(_StopConfirm sc, EscalationReason reason, String msg) {
    final result = CommandResult(
      status: CommandStatus.timedOut,
      requested: 0,
      applied: 0,
      reason: msg,
    );
    if (sc.settled) return result;
    sc.settled = true;
    sc.deadline?.cancel();
    if (identical(_stopConfirm, sc)) _stopConfirm = null;
    _escalate(reason, msg);
    if (!sc.completer.isCompleted) sc.completer.complete(result);
    return result;
  }

  // --- Link / telemetry / events -------------------------------------------

  void _onLinkState(LinkState s) {
    if (_disposed) return;
    switch (s) {
      case LinkState.connected:
        _ranges = _link.ranges;
        // Reconcile happens on the first sample after connect so we act on
        // observed motion, not an assumption. If already idle, stay idle.
        if (_phase == MotionPhase.offline && !_latched) {
          _phase = MotionPhase.idle;
        }
      case LinkState.disconnected:
      case LinkState.faulted:
        // A disconnect invalidates any in-flight command and stops the watchdog.
        // We do not know the belt state across a dead link, so we drop to
        // offline and will reconcile from telemetry on reconnect.
        _bumpGeneration();
        _drainQueue(reason: 'link ${s.name}');
        _cancelWatchdog();
        if (!_latched) _phase = MotionPhase.offline;
        _awaitingReconcile = true;
      case LinkState.connecting:
        break;
    }
    _emit();
  }

  void _onSample(MetricSample sample) {
    if (_disposed) return;
    if (sample.kind == MetricKind.speed) {
      _observedSpeed = sample.value;

      if (_awaitingReconcile &&
          _link.currentState == LinkState.connected &&
          !_latched) {
        _awaitingReconcile = false;
        _reconcile(sample.value);
      }

      // Positive stop confirmation: the belt has actually decelerated.
      final sc = _stopConfirm;
      if (sc != null && sample.value <= _limits.stopConfirmSpeedKph) {
        sc.decelObserved = true;
        _tryConfirmStop(sc);
      }
    }

    _maybeConfirmFromTelemetry(sample);
    _feedWatchdog();
    _emit();
  }

  void _reconcile(double observedSpeed) {
    if (observedSpeed > _limits.stopConfirmSpeedKph) {
      // Attach-to-moving recovery: reconcile to observed state rather than
      // assuming zero. We adopt the observed speed as our commanded baseline so
      // later acceleration limiting is measured from reality, and we never
      // command anything to make this happen.
      _commandedSpeed = observedSpeed;
      _enterMoving();
      _events.add(AttachedToMovingBelt(Speed.kph(observedSpeed)));
    } else {
      _phase = _latched ? MotionPhase.latched : MotionPhase.idle;
    }
  }

  void _maybeConfirmFromTelemetry(MetricSample sample) {
    final pc = _pendingConfirm;
    if (pc == null) return;
    final matches = (pc.axis == _Axis.speed && sample.kind == MetricKind.speed) ||
        (pc.axis == _Axis.incline && sample.kind == MetricKind.incline);
    if (!matches) return;
    if ((sample.value - pc.target).abs() <= pc.tolerance) {
      pc.onReached();
    }
  }

  void _onMachineEvent(MachineEvent event) {
    if (_disposed) return;
    switch (event) {
      case SafetyKeyRemoved():
        _engageLatch();
      case SafetyKeyReinserted():
        // Reinsertion arms the key but never clears the latch on its own.
        _keyPresent = true;
        _emit();
      case ExternalStop():
        _awaitingReconcile = false;
        _bumpGeneration();
        _drainQueue(reason: 'external stop');
        _commandedSpeed = 0;
        _cancelWatchdog();
        if (!_latched) {
          _phase = MotionPhase.idle;
          _escalation = EscalationReason.none;
          _escalationMessage = null;
        }
        _emit();
      case ExternalSpeedChange(:final speed):
        // An external actor (console knob) changed the belt. Adopt its value
        // rather than fighting it, and stop treating a future telemetry sample
        // as something to reconcile away.
        _awaitingReconcile = false;
        _commandedSpeed = speed.kph;
        if (speed.kph > _limits.stopConfirmSpeedKph) {
          _enterMoving();
        }
        _emit();
    }
  }

  void _engageLatch() {
    _keyPresent = false;
    _latched = true;
    _bumpGeneration();
    _drainQueue(reason: 'safety-key latch');
    _commandedSpeed = 0;
    _cancelWatchdog();
    _stopConfirm?.deadline?.cancel();
    _stopConfirm = null;
    _phase = MotionPhase.latched;
    _events.add(const Latched());
    _emit();
  }

  // --- Watchdog -------------------------------------------------------------

  void _feedWatchdog() {
    if (_phase != MotionPhase.moving) return;
    _watchdog?.cancel();
    _watchdog = Timer(_limits.telemetryWatchdog, _onWatchdogFired);
  }

  void _cancelWatchdog() {
    _watchdog?.cancel();
    _watchdog = null;
  }

  void _onWatchdogFired() {
    // Telemetry stalled while the belt was commanded to move. We can no longer
    // see what the belt is doing, so we attempt a stop and escalate loudly. The
    // stop itself will almost certainly fail its telemetry confirmation, which
    // keeps us escalated - that is correct.
    _escalate(EscalationReason.telemetryStall,
        'telemetry stalled for ${_limits.telemetryWatchdog} while moving');
    unawaited(_performStop(escalateOnFail: EscalationReason.telemetryStall));
  }

  // --- State plumbing -------------------------------------------------------

  void _enterMoving() {
    if (_phase != MotionPhase.moving) {
      _phase = MotionPhase.moving;
    }
    _feedWatchdog();
  }

  void _bumpGeneration() {
    _generation++;
  }

  void _drainQueue({required String reason}) {
    while (_queue.isNotEmpty) {
      final t = _queue.removeFirst();
      _complete(t, CommandStatus.superseded, reason: reason);
    }
  }

  void _escalate(EscalationReason reason, String message) {
    _escalation = reason;
    _escalationMessage = message;
    _phase = MotionPhase.escalated;
    _events.add(Escalated(reason, message));
    _emit();
  }

  void _complete(_Transmit t, CommandStatus status,
      {double? applied, String? reason}) {
    if (t.completer.isCompleted) return;
    t.completer.complete(_result(t, status, applied: applied, reason: reason));
  }

  CommandResult _result(_Transmit t, CommandStatus status,
      {double? applied, String? reason}) {
    return CommandResult(
      status: status,
      requested: t.requested,
      applied: applied,
      reason: reason,
    );
  }

  SafetyState _snapshot() {
    return SafetyState(
      phase: _phase,
      link: _link.currentState,
      commandedSpeed: Speed.kph(_commandedSpeed),
      observedSpeed: Speed.kph(_observedSpeed),
      commandedIncline: Percent(_commandedIncline),
      generation: _generation,
      latched: _latched,
      escalation: _escalation,
      escalationMessage: _escalationMessage,
    );
  }

  void _emit() {
    if (_disposed || _states.isClosed) return;
    _states.add(_snapshot());
  }
}
