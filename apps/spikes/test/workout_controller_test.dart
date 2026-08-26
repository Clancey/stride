import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stride_spikes/model/workout_controller.dart';

void main() {
  test('loads workout state, volume, and unknown machine metrics', () async {
    final bridge = _FakeWorkoutBridge(
      state: 'paused',
      elapsedMs: 65 * 1000,
      volume: const WorkoutVolume(level: 7, max: 15),
      machine: MachineSnapshot.unavailable('Machine bridge disabled for test.'),
    );
    final controller = WorkoutController(
      bridge: bridge,
      tickInterval: const Duration(milliseconds: 10),
    );
    addTearDown(controller.dispose);

    await controller.load();

    expect(controller.state, 'paused');
    expect(controller.elapsedMs, 65000);
    expect(controller.volume.level, 7);
    expect(controller.machine.distanceMiles, isNull);
    expect(controller.machine.canCommand, isFalse);
  });

  test('ticks elapsed time while running and cancels on dispose', () async {
    final bridge = _FakeWorkoutBridge(state: 'running', elapsedMs: 1000);
    final controller = WorkoutController(
      bridge: bridge,
      tickInterval: const Duration(milliseconds: 5),
    );

    await controller.load();
    await Future<void>.delayed(const Duration(milliseconds: 25));

    expect(controller.elapsedMs, greaterThan(1000));
    controller.dispose();
    final disposedElapsed = controller.elapsedMs;
    await Future<void>.delayed(const Duration(milliseconds: 25));
    expect(controller.elapsedMs, disposedElapsed);
  });

  test('handles a missing channel without throwing', () async {
    final controller = WorkoutController(
      bridge: _ThrowingWorkoutBridge(),
      tickInterval: const Duration(milliseconds: 5),
    );
    addTearDown(controller.dispose);

    await controller.load();

    expect(controller.state, 'idle');
    expect(controller.elapsedMs, 0);
    expect(controller.volume.available, isFalse);
    expect(controller.machine.canCommand, isFalse);
    expect(controller.lastError, contains('unavailable'));
    // And a bridge that cannot answer must not manufacture a safety alarm out
    // of its own silence. The host is the only thing that raises this latch.
    expect(controller.stopEscalation.active, isFalse);
  });

  test('a stop that could not be confirmed is surfaced, and can be cleared', () async {
    // This is the case the launcher has to cover on its own: no overlay is
    // running — never granted SYSTEM_ALERT_WINDOW, stopped by the rider, or
    // killed under memory pressure — so the card the overlay would have drawn
    // does not exist and this screen is the only thing that can warn anybody.
    final bridge = _FakeWorkoutBridge(state: 'idle')
      ..escalation = const StopEscalationState(
        active: true,
        reason: 'NOT_OBSERVED',
        detail: 'Stride told the treadmill to stop and the console accepted '
            'it, but no telemetry came back to show the belt actually slowing.',
        instruction: 'Do not assume the belt has stopped. Pull the safety key.',
      );
    final controller = WorkoutController(
      bridge: bridge,
      tickInterval: const Duration(milliseconds: 10),
    );
    addTearDown(controller.dispose);

    await controller.load();
    expect(controller.stopEscalation.active, isTrue);
    expect(controller.stopEscalation.detail, contains('no telemetry'));
    expect(controller.stopEscalation.instruction, contains('safety key'));

    // The only way out, and it has to work from here: the host refuses to start
    // a workout while the latch is up, so a screen that could show the warning
    // but not clear it would leave a rider with a dead Start button for good.
    await controller.acknowledgeStopEscalation();
    expect(controller.stopEscalation.active, isFalse);
  });

  test('leaving "stopping" re-reads the latch', () async {
    // The verdict lands asynchronously and the session goes idle either way, so
    // the transition out of "stopping" is the moment an unconfirmed stop has
    // just raised the warning — and the ticker stops at idle, so there may be
    // no later poll to notice it.
    final bridge = _FakeWorkoutBridge(state: 'stopping');
    final controller = WorkoutController(
      bridge: bridge,
      tickInterval: const Duration(milliseconds: 5),
    );
    addTearDown(controller.dispose);

    await controller.load();
    expect(controller.state, 'stopping');
    expect(controller.stopEscalation.active, isFalse);

    bridge
      ..state = 'idle'
      ..escalation = const StopEscalationState(
        active: true,
        reason: 'STILL_MOVING',
        detail: 'The machine is still reporting that the belt is moving.',
        instruction: 'Pull the safety key.',
      );
    await Future<void>.delayed(const Duration(milliseconds: 60));

    expect(controller.state, 'idle');
    expect(controller.stopEscalation.active, isTrue);
  });
}

class _FakeWorkoutBridge implements WorkoutBridgeClient {
  _FakeWorkoutBridge({
    this.state = 'idle',
    this.elapsedMs = 0,
    this.volume = WorkoutVolume.unavailable,
    MachineSnapshot? machine,
  }) : machine = machine ?? MachineSnapshot.unavailable('No machine link.');

  String state;
  int elapsedMs;
  WorkoutVolume volume;
  MachineSnapshot machine;

  @override
  Future<String> workoutState() async => state;

  @override
  Future<int> workoutElapsedMs() async {
    if (state == 'running') elapsedMs += 1000;
    return elapsedMs;
  }

  @override
  Future<bool> workoutStart() async {
    // Mirrors the host: a start is a request, and the treadmill answers it later.
    state = 'starting';
    return true;
  }

  @override
  Future<bool> workoutPause() async {
    state = 'paused';
    return true;
  }

  @override
  Future<bool> workoutResume() async {
    state = 'running';
    return true;
  }

  @override
  Future<int> workoutStop() async {
    state = 'idle';
    return elapsedMs;
  }

  @override
  Future<bool> workoutCancelStart() async {
    if (state != 'starting') return false;
    state = 'idle';
    return true;
  }

  @override
  Future<WorkoutVolume> volumeGet() async => volume;

  @override
  Future<bool> volumeSet(int level) async {
    volume = WorkoutVolume(level: level, max: volume.max);
    return true;
  }

  @override
  Future<MachineSnapshot> machineSnapshot() async => machine;

  /// The safety-key latch. Defaults to clear, so a fake that says nothing about
  /// it cannot manufacture an alarm.
  StopEscalationState escalation = const StopEscalationState.clear();

  @override
  Future<StopEscalationState> stopEscalation() async => escalation;

  @override
  Future<bool> stopEscalationAcknowledge() async {
    escalation = const StopEscalationState.clear();
    return true;
  }
}

class _ThrowingWorkoutBridge implements WorkoutBridgeClient {
  Never _missing() => throw MissingPluginException('missing');

  @override
  Future<String> workoutState() async => _missing();

  @override
  Future<int> workoutElapsedMs() async => _missing();

  @override
  Future<bool> workoutStart() async => _missing();

  @override
  Future<bool> workoutPause() async => _missing();

  @override
  Future<bool> workoutResume() async => _missing();

  @override
  Future<int> workoutStop() async => _missing();

  @override
  Future<bool> workoutCancelStart() async => _missing();

  @override
  Future<WorkoutVolume> volumeGet() async => _missing();

  @override
  Future<bool> volumeSet(int level) async => _missing();

  @override
  Future<MachineSnapshot> machineSnapshot() async => _missing();

  @override
  Future<StopEscalationState> stopEscalation() async => _missing();

  @override
  Future<bool> stopEscalationAcknowledge() async => _missing();
}
