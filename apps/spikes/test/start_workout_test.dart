import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stride_spikes/model/workout_goal.dart';
import 'package:stride_spikes/screens/start_workout.dart';
import 'package:stride_spikes/theme/stride_theme.dart';

void main() {
  Future<WorkoutGoal?> pumpAndCapture(
    WidgetTester tester, {
    bool startSucceeds = true,
  }) async {
    tester.view.physicalSize = const Size(1920, 1080);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    WorkoutGoal? captured;
    await tester.pumpWidget(
      MaterialApp(
        theme: StrideTheme.dark(),
        home: StartWorkoutScreen(
          onConfirm: (goal) async {
            captured = goal;
            return startSucceeds;
          },
        ),
      ),
    );
    await tester.pumpAndSettle();
    return captured;
  }

  testWidgets('defaults to a confirmable distance goal (5K)', (tester) async {
    await pumpAndCapture(tester);

    // The 5K preset is pre-selected and the summary reflects it immediately.
    expect(find.text('5K'), findsOneWidget);
    // Shown in both the custom stepper readout and the confirm summary.
    expect(find.text('3.1 mi'), findsNWidgets(2));
    expect(
      find.widgetWithText(FilledButton, 'Start workout'),
      findsOneWidget,
    );
  });

  testWidgets('confirming a distance preset hands off that goal', (
    tester,
  ) async {
    WorkoutGoal? captured;
    tester.view.physicalSize = const Size(1920, 1080);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        theme: StrideTheme.dark(),
        home: StartWorkoutScreen(
          onConfirm: (goal) async {
            captured = goal;
            return true;
          },
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('10K'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Start workout'));
    await tester.pumpAndSettle();

    expect(captured, isNotNull);
    expect(captured!.kind, WorkoutGoalKind.distance);
    expect(captured!.target, closeTo(6.2, 1e-9));
  });

  testWidgets('no goal is a first-class, confirmable choice', (tester) async {
    WorkoutGoal? captured;
    tester.view.physicalSize = const Size(1920, 1080);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        theme: StrideTheme.dark(),
        home: StartWorkoutScreen(
          onConfirm: (goal) async {
            captured = goal;
            return true;
          },
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('No goal'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Start workout'));
    await tester.pumpAndSettle();

    expect(captured, isNotNull);
    expect(captured!.kind, WorkoutGoalKind.none);
  });

  testWidgets('a time goal can be chosen from the time presets', (
    tester,
  ) async {
    WorkoutGoal? captured;
    tester.view.physicalSize = const Size(1920, 1080);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        theme: StrideTheme.dark(),
        home: StartWorkoutScreen(
          onConfirm: (goal) async {
            captured = goal;
            return true;
          },
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('Time'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('45 min'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Start workout'));
    await tester.pumpAndSettle();

    expect(captured!.kind, WorkoutGoalKind.time);
    expect(captured!.target, 45 * 60);
  });
}
