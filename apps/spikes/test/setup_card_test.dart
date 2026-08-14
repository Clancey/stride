// The setup prompt is the only thing standing between a fresh install and a rider stranded inside
// a full-screen app with no way out, so its two failure modes are worth pinning down: it must not
// stay quiet when a grant is missing, and it must not nag once everything is in place.

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stride_spikes/widgets/setup_card.dart';

const MethodChannel _channel = MethodChannel('io.stride.spikes/bridge');

Map<String, Object?> _grant(String id, String label, bool granted) => {
  'id': id,
  'label': label,
  'consequence': '$label is off.',
  'granted': granted,
};

void _host({required bool isHome, required Set<String> granted}) {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(_channel, (call) async {
        switch (call.method) {
          case 'isDefaultHome':
            return isHome;
          case 'grantsGet':
            return <Object?>[
              _grant('overlay', 'Draw over other apps', granted.contains('overlay')),
              _grant(
                'accessibility',
                'Accessibility service',
                granted.contains('accessibility'),
              ),
              _grant(
                'notifications',
                'Notification access',
                granted.contains('notifications'),
              ),
            ];
        }
        return null;
      });
}

Future<void> _pump(WidgetTester tester, SetupStatus status) async {
  await tester.pumpWidget(
    MaterialApp(home: Scaffold(body: SetupCard(status: status))),
  );
  await tester.pumpAndSettle();
}

void main() {
  const everything = {'overlay', 'accessibility', 'notifications'};

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, null);
  });

  testWidgets('says nothing once every grant is in place', (tester) async {
    _host(isHome: true, granted: everything);
    final status = SetupStatus(pollInterval: null);
    addTearDown(status.dispose);
    await _pump(tester, status);

    expect(status.ready, isTrue);
    expect(find.byType(SetupCard), findsOneWidget);
    expect(find.textContaining('left to set up'), findsNothing);
  });

  testWidgets('names the one grant that is missing', (tester) async {
    // The exact case that bit us on hardware: reinstalling Stride clears
    // enabled_accessibility_services, Back silently stops working, and nothing on screen says so.
    _host(isHome: true, granted: const {'overlay', 'notifications'});
    final status = SetupStatus(pollInterval: null);
    addTearDown(status.dispose);
    await _pump(tester, status);

    expect(find.text('One thing left to set up'), findsOneWidget);
    expect(find.text('Accessibility service'), findsOneWidget);
    expect(find.text('Draw over other apps'), findsNothing);
    // A list is a dead end without an instruction, so the row must say what to look for.
    expect(
      find.textContaining('Find Stride Spikes in the list'),
      findsOneWidget,
    );
    expect(find.text('Fix this'), findsOneWidget);
  });

  testWidgets('offers to become the home app before anything else', (
    tester,
  ) async {
    _host(isHome: false, granted: everything);
    final status = SetupStatus(pollInterval: null);
    addTearDown(status.dispose);
    await _pump(tester, status);

    expect(find.text('Set Stride as the home app'), findsOneWidget);
    expect(status.outstanding.first.id, 'home');
  });

  testWidgets('counts every outstanding step, and offers no way to dismiss', (
    tester,
  ) async {
    _host(isHome: false, granted: const {});
    final status = SetupStatus(pollInterval: null);
    addTearDown(status.dispose);
    await _pump(tester, status);

    expect(find.text('4 things left to set up'), findsOneWidget);
    // Deliberately no close affordance: a prompt that can be waved away permanently is a prompt
    // that will be, and then the rider has no Back button and no explanation.
    expect(find.byIcon(Icons.close), findsNothing);
    expect(find.text('Dismiss'), findsNothing);
  });

  testWidgets('a failed host read does not invent a missing grant', (
    tester,
  ) async {
    _host(isHome: true, granted: everything);
    final status = SetupStatus(pollInterval: null);
    addTearDown(status.dispose);
    await _pump(tester, status);
    expect(status.ready, isTrue);

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
          throw PlatformException(code: 'boom');
        });
    await status.refresh();
    await tester.pumpAndSettle();

    // Losing a channel race at startup is not evidence that anything was revoked. Flickering the
    // card into view on every dropped call would train the rider to ignore it.
    expect(status.ready, isTrue);
    expect(find.textContaining('left to set up'), findsNothing);
  });
}
