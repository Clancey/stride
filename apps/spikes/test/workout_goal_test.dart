import 'package:flutter_test/flutter_test.dart';
import 'package:stride_spikes/model/workout_goal.dart';

void main() {
  group('WorkoutGoalKind channel mapping', () {
    test('round-trips known kinds', () {
      expect(WorkoutGoalKind.fromChannel('none'), WorkoutGoalKind.none);
      expect(WorkoutGoalKind.fromChannel('distance'), WorkoutGoalKind.distance);
      expect(WorkoutGoalKind.fromChannel('time'), WorkoutGoalKind.time);
      expect(WorkoutGoalKind.distance.channelValue, 'distance');
    });

    test('falls back to none for unknown or null', () {
      expect(WorkoutGoalKind.fromChannel(null), WorkoutGoalKind.none);
      expect(WorkoutGoalKind.fromChannel('speed'), WorkoutGoalKind.none);
    });
  });

  group('construction and labels', () {
    test('none is not trackable and labels honestly', () {
      const goal = WorkoutGoal.none();
      expect(goal.isNone, isTrue);
      expect(goal.isTrackable, isFalse);
      expect(goal.label, 'No goal');
    });

    test('distance label shows one decimal', () {
      expect(WorkoutGoal.distance(5).label, '5.0 mi');
      expect(WorkoutGoal.distance(3.1).label, '3.1 mi');
    });

    test('time label shows clock, with hours only when needed', () {
      expect(WorkoutGoal.time(const Duration(minutes: 30)).label, '30:00');
      expect(
        WorkoutGoal.time(const Duration(hours: 1, minutes: 5)).label,
        '1:05:00',
      );
    });

    test('negative targets are clamped to zero and untrackable', () {
      expect(WorkoutGoal.distance(-5).target, 0);
      expect(WorkoutGoal.distance(-5).isTrackable, isFalse);
      expect(WorkoutGoal.time(const Duration(seconds: -10)).target, 0);
    });
  });

  group('progressFrom', () {
    test('distance progress is the ratio, clamped to 0..1', () {
      final goal = WorkoutGoal.distance(5);
      expect(
        goal.progressFrom(distanceMiles: 1, elapsed: const Duration(minutes: 5)),
        closeTo(0.2, 1e-9),
      );
      // Exceeded goal clamps at 1.0 rather than reporting 1.2.
      expect(
        goal.progressFrom(distanceMiles: 6, elapsed: const Duration(minutes: 5)),
        1.0,
      );
    });

    test('time progress reads the elapsed clock, clamped at 1.0', () {
      final goal = WorkoutGoal.time(const Duration(minutes: 30));
      expect(
        goal.progressFrom(distanceMiles: 0, elapsed: const Duration(minutes: 10)),
        closeTo(1 / 3, 1e-9),
      );
      expect(
        goal.progressFrom(
          distanceMiles: 0,
          elapsed: const Duration(minutes: 40),
        ),
        1.0,
      );
    });

    test('zero elapsed and zero distance yield zero progress', () {
      final distance = WorkoutGoal.distance(5);
      expect(
        distance.progressFrom(distanceMiles: 0, elapsed: Duration.zero),
        0,
      );
      final time = WorkoutGoal.time(const Duration(minutes: 30));
      expect(time.progressFrom(distanceMiles: 2, elapsed: Duration.zero), 0);
    });

    test('an untrackable goal is always zero progress', () {
      const goal = WorkoutGoal.none();
      expect(
        goal.progressFrom(distanceMiles: 3, elapsed: const Duration(hours: 1)),
        0,
      );
    });
  });

  group('etaFrom', () {
    test('no goal never projects an ETA', () {
      const goal = WorkoutGoal.none();
      expect(
        goal.etaFrom(distanceMiles: 3, elapsed: const Duration(minutes: 30)),
        isNull,
      );
    });

    test('distance ETA is remaining distance at the average pace', () {
      // 1.0 mi in 10:00 => 4.0 mi left at 10:00/mi => 40:00 to go.
      final goal = WorkoutGoal.distance(5);
      expect(
        goal.etaFrom(distanceMiles: 1, elapsed: const Duration(minutes: 10)),
        const Duration(minutes: 40),
      );
    });

    test('a second hand-computed distance case', () {
      // 2.0 mi in 20:00 => 1.0 mi left at 10:00/mi => 10:00 to go.
      final goal = WorkoutGoal.distance(3);
      expect(
        goal.etaFrom(distanceMiles: 2, elapsed: const Duration(minutes: 20)),
        const Duration(minutes: 10),
      );
    });

    test('time ETA is simply the remaining time', () {
      final goal = WorkoutGoal.time(const Duration(minutes: 30));
      expect(
        goal.etaFrom(distanceMiles: 0, elapsed: const Duration(minutes: 20)),
        const Duration(minutes: 10),
      );
    });

    test('a stationary rider yields null, never infinity', () {
      final goal = WorkoutGoal.distance(5);
      expect(
        goal.etaFrom(distanceMiles: 0, elapsed: const Duration(minutes: 10)),
        isNull,
      );
    });

    test('zero elapsed yields null for both kinds', () {
      expect(
        WorkoutGoal.distance(5).etaFrom(distanceMiles: 1, elapsed: Duration.zero),
        isNull,
      );
      expect(
        WorkoutGoal.time(
          const Duration(minutes: 30),
        ).etaFrom(distanceMiles: 0, elapsed: Duration.zero),
        isNull,
      );
    });

    test('an already-met goal returns zero, never a negative duration', () {
      final distance = WorkoutGoal.distance(5);
      final distanceEta = distance.etaFrom(
        distanceMiles: 6,
        elapsed: const Duration(minutes: 30),
      );
      expect(distanceEta, Duration.zero);

      final time = WorkoutGoal.time(const Duration(minutes: 30));
      final timeEta = time.etaFrom(
        distanceMiles: 0,
        elapsed: const Duration(minutes: 45),
      );
      expect(timeEta, Duration.zero);
    });
  });

  group('remaining helpers', () {
    test('distance remaining clamps at zero', () {
      final goal = WorkoutGoal.distance(5);
      expect(goal.remainingMiles(2), closeTo(3, 1e-9));
      expect(goal.remainingMiles(6), 0);
      expect(
        goal.remainingLabel(distanceMiles: 2, elapsed: Duration.zero),
        '3.0 mi to go',
      );
    });

    test('time remaining clamps at zero', () {
      final goal = WorkoutGoal.time(const Duration(minutes: 30));
      expect(
        goal.remainingTime(const Duration(minutes: 20)),
        const Duration(minutes: 10),
      );
      expect(
        goal.remainingTime(const Duration(minutes: 40)),
        Duration.zero,
      );
      expect(
        goal.remainingLabel(
          distanceMiles: 0,
          elapsed: const Duration(minutes: 20),
        ),
        '10:00 left',
      );
    });
  });

  test('value equality', () {
    expect(WorkoutGoal.distance(5), WorkoutGoal.distance(5));
    expect(WorkoutGoal.distance(5), isNot(WorkoutGoal.distance(6)));
    expect(
      WorkoutGoal.time(const Duration(minutes: 30)),
      isNot(WorkoutGoal.distance(5)),
    );
  });
}
