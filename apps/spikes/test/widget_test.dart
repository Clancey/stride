// Smoke test for the launcher shell and preserved Phase 0 diagnostics.

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stride_spikes/main.dart';

void main() {
  testWidgets('launcher exposes diagnostics with every spike', (
    WidgetTester tester,
  ) async {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(const SpikeApp());
    await tester.pumpAndSettle();

    expect(find.text('Stride'), findsOneWidget);
    expect(find.text('Workout'), findsOneWidget);
    expect(find.text('Elapsed time'), findsOneWidget);
    expect(find.text('Controls locked'), findsOneWidget);
    expect(find.text('Start workout'), findsOneWidget);
    expect(find.text('Media volume'), findsOneWidget);
    expect(
      find.textContaining(
        "Stride doesn't control the treadmill. Use the console's own controls or the safety key.",
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        "Stride can't read the treadmill. The belt may be moving.",
      ),
      findsOneWidget,
    );
    expect(find.text('All apps'), findsOneWidget);

    await tester.tap(find.byTooltip('Diagnostics'));
    await tester.pumpAndSettle();

    for (final id in <String>['ENV', 'S1', 'S2', 'S3', 'S4', 'S5', 'S10']) {
      expect(
        find.textContaining('$id — '),
        findsOneWidget,
        reason: 'spike $id should appear exactly once in diagnostics',
      );
    }
    expect(
      find.textContaining('Nothing here commands the motor'),
      findsOneWidget,
    );
  });
}
