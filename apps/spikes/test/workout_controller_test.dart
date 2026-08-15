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
}
