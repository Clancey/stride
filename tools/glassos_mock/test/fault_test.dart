/// Exercises every fault-injection path required by docs/PLAN.md section 5.
/// The REPL commands are thin wrappers over exactly these calls, so proving
/// these works proves the faults are triggerable.
library;

import 'dart:async';

import 'package:glassos_mock/glassos_mock.dart';
import 'package:test/test.dart';

void main() {
  late MockMachine machine;
  late FaultState faults;

  setUp(() {
    faults = FaultState();
    machine = MockMachine(faults: faults);
    machine.start();
  });

  tearDown(() async {
    await machine.dispose();
  });

  Future<void> settle([int ticks = 12]) => Future<void>.delayed(
        MockMachine.tickInterval * ticks,
      );

  test('belt ramps up gradually and reaches target', () async {
    machine.setSpeed(6.0);
    await settle(2);
    expect(machine.speedKph, greaterThan(0));
    expect(machine.speedKph, lessThan(6.0));
    await settle(60);
    expect(machine.speedKph, closeTo(6.0, 0.2));
  });

  test('stop is a visible deceleration, not instant', () async {
    machine.setSpeed(8.0);
    await settle(70);
    machine.setSpeed(0.0);
    await settle(2);
    // Still moving right after the stop command: the coordinator can observe
    // the deceleration in telemetry (docs/PLAN.md 5.4).
    expect(machine.speedKph, greaterThan(0));
    await settle(40);
    expect(machine.speedKph, closeTo(0.0, 0.2));
  });

  test('stalled telemetry: belt keeps moving but no samples emit', () async {
    machine.setSpeed(5.0);
    await settle(50);
    faults.stallTelemetry = true;
    final samples = <SpeedSample>[];
    final sub = machine.speedStream.listen(samples.add);
    await settle(10);
    await sub.cancel();
    expect(samples, isEmpty);
    expect(machine.speedKph, greaterThan(0)); // still running
  });

  test('safety-key pull cuts belt and latches to paused', () async {
    machine.startWorkout();
    machine.setSpeed(8.0);
    await settle(40);
    machine.pullSafetyKey();
    expect(machine.safetyKeyPresent, isFalse);
    expect(machine.workoutState, WorkoutState.paused);
    await settle(40);
    expect(machine.speedKph, closeTo(0.0, 0.2));
    // While the key is out, a speed command cannot move the belt.
    machine.setSpeed(6.0);
    await settle(30);
    expect(machine.speedKph, closeTo(0.0, 0.2));
    machine.reinsertSafetyKey();
    expect(machine.safetyKeyPresent, isTrue);
  });

  test('client-lost policy keep vs stop', () async {
    faults.clientLostBehavior = ClientLostBehavior.keepMoving;
    machine.setSpeed(7.0);
    await settle(60);
    machine.onClientLost();
    await settle(10);
    expect(machine.speedKph, greaterThan(1.0)); // kept moving

    faults.clientLostBehavior = ClientLostBehavior.stopBelt;
    machine.onClientLost();
    await settle(40);
    expect(machine.speedKph, closeTo(0.0, 0.3)); // stopped
  });

  test('hardware button changes workout state with console_button source',
      () async {
    final events = <WorkoutStateChanged>[];
    final sub = machine.workoutStream.listen(events.add);
    machine.startWorkout(source: 'console_button');
    machine.pauseWorkout(source: 'console_button');
    await Future<void>.delayed(const Duration(milliseconds: 50));
    await sub.cancel();
    expect(events.map((e) => e.source), everyElement('console_button'));
    expect(events.map((e) => e.state),
        containsAll([WorkoutState.running, WorkoutState.paused]));
  });
}
