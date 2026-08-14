// Smoke test for the Phase 0 spike harness.
//
// Deliberately shallow: every spike screen calls into the platform channel on init, which is not
// available under `flutter test`. Real verification for S1-S10 happens on the console and is
// recorded in docs/SPIKES.md - not here.

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stride_spikes/main.dart';

void main() {
  testWidgets('spike index lists every spike', (WidgetTester tester) async {
    tester.view.physicalSize = const Size(1280, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(const SpikeApp());

    for (final id in <String>['ENV', 'S1', 'S2', 'S3', 'S4', 'S5', 'S10']) {
      expect(
        find.textContaining('$id — '),
        findsOneWidget,
        reason: 'spike $id should appear exactly once on the index',
      );
    }
    expect(find.textContaining('Nothing here commands the motor'), findsOneWidget);
  });
}
