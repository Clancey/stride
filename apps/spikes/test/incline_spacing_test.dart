// The incline quick-pick spacing choice (issue #27), and the seam it crosses.
//
// The behaviour worth pinning here is not that the pills light up. It is that the tap reaches the
// platform under the key the platform actually reads, and that this screen refuses to pretend
// otherwise when it does not.
//
// That is a bug this repo has already shipped once. `SpikeBridge.trackFloorSet` read argument key
// `"on"` while Dart sent `"chosen"`, so `argument` answered null on every call, every press stored
// the default, and the settings screen looked perfectly correct — because it recorded the tap in its
// own state — until the next reload put the real value back. Nothing threw, and nothing logged.
//
// So these tests assert the argument map itself, not just the pixels.

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stride_spikes/screens/settings_screen.dart';
import 'package:stride_spikes/theme/stride_tokens.dart';

const MethodChannel _channel = MethodChannel('io.stride.spikes/bridge');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Map<String, Object?> settings;
  late List<Map<String, Object?>> spacingWrites;

  /// Whether the platform accepts the write. False is not hypothetical: the
  /// setter refuses a missing key or an unknown spacing outright.
  late bool accept;

  setUp(() {
    accept = true;
    spacingWrites = <Map<String, Object?>>[];
    settings = <String, Object?>{
      'transport': 'glassos',
      'inclineSpacing': 'fine',
    };

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
          switch (call.method) {
            case 'settingsGet':
              return settings;
            case 'grantsGet':
              return <Object?>[];
            case 'trackFloorGet':
              return <String, Object?>{'chosen': null, 'backdrop': false};
            case 'inclineSpacingSet':
              final args = Map<String, Object?>.from(call.arguments as Map);
              spacingWrites.add(args);
              if (!accept) return false;
              settings = <String, Object?>{
                ...settings,
                'inclineSpacing': args['spacing'],
              };
              return true;
            default:
              return null;
          }
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, null);
  });

  Future<void> openAdvanced(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1280, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(const MaterialApp(home: SettingsScreen()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Advanced'));
    await tester.pumpAndSettle();
  }

  /// Advanced is the last thing on a scrolling page, so the pills sit below the
  /// fold on any console this would run on.
  Future<void> tapPill(WidgetTester tester, String label) async {
    final pill = find.text(label);
    await tester.ensureVisible(pill);
    await tester.pumpAndSettle();
    await tester.tap(pill);
    await tester.pumpAndSettle();
  }

  /// Whether a pill is lit, read the way `_pill` sets it rather than inferred.
  ///
  /// This is the assertion the track-floor bug needed and did not have: the
  /// screen kept its own copy of the choice, so the lit pill proved only that a
  /// tap had been received — never that anything had been stored.
  bool lit(WidgetTester tester, String label) {
    final material = tester.widget<Material>(
      find.ancestor(of: find.text(label), matching: find.byType(Material)).first,
    );
    return material.color == StrideColors.accent;
  }

  /// The default, and the one that matters most: a rider who never opens this
  /// gets the column they already had.
  testWidgets('the fine column is selected until the rider chooses otherwise', (
    tester,
  ) async {
    await openAdvanced(tester);
    await tester.ensureVisible(find.text('Every 1%'));
    await tester.pumpAndSettle();

    expect(lit(tester, 'Every 1%'), isTrue);
    expect(lit(tester, '5% up, 3% down'), isFalse);
    // Nothing was written just by looking at the screen.
    expect(spacingWrites, isEmpty);
  });

  /// The seam. The key has to be `spacing`, because that is the key
  /// `SpikeBridge.inclineSpacingSet` refuses the call without.
  testWidgets('choosing the coarse column sends spacing, not some other key', (
    tester,
  ) async {
    await openAdvanced(tester);
    await tapPill(tester, '5% up, 3% down');

    expect(spacingWrites, <Map<String, Object?>>[
      <String, Object?>{'spacing': 'coarse'},
    ]);
    expect(lit(tester, '5% up, 3% down'), isTrue);
    expect(lit(tester, 'Every 1%'), isFalse);
  });

  testWidgets('choosing the fine column again sends fine', (tester) async {
    settings = <String, Object?>{
      'transport': 'glassos',
      'inclineSpacing': 'coarse',
    };
    await openAdvanced(tester);
    expect(lit(tester, '5% up, 3% down'), isTrue);

    await tapPill(tester, 'Every 1%');

    expect(spacingWrites.single['spacing'], 'fine');
    expect(lit(tester, 'Every 1%'), isTrue);
  });

  /// A refused write must not leave the screen claiming the new value.
  ///
  /// This is the half of the track-floor bug that made it invisible: the pills
  /// were repainted from local state regardless of what the platform did, so a
  /// write that never landed looked identical to one that did.
  testWidgets('a refused write leaves the old choice showing', (tester) async {
    accept = false;
    await openAdvanced(tester);
    await tapPill(tester, '5% up, 3% down');

    expect(spacingWrites, hasLength(1));
    expect(find.text("That setting couldn't be saved."), findsOneWidget);

    // Nothing was stored, so nothing may look stored.
    expect(settings['inclineSpacing'], 'fine');
    expect(lit(tester, 'Every 1%'), isTrue);
    expect(lit(tester, '5% up, 3% down'), isFalse);
  });

  /// A platform that answers with a spacing this build does not know about
  /// must not leave the row with nothing lit — an unlit pill row reads as a
  /// broken control rather than an unrecognised value.
  testWidgets('an unknown spacing from the platform falls back to fine', (
    tester,
  ) async {
    settings = <String, Object?>{
      'transport': 'glassos',
      'inclineSpacing': 'every-other-percent',
    };
    await openAdvanced(tester);
    await tester.ensureVisible(find.text('Every 1%'));
    await tester.pumpAndSettle();

    expect(lit(tester, 'Every 1%'), isTrue);
    expect(lit(tester, '5% up, 3% down'), isFalse);
  });

  /// The third pill, mirroring one console's own physical incline buttons.
  testWidgets('choosing the console-buttons column sends physical', (
    tester,
  ) async {
    await openAdvanced(tester);
    await tapPill(tester, 'Console buttons');

    expect(spacingWrites, <Map<String, Object?>>[
      <String, Object?>{'spacing': 'physical'},
    ]);
    expect(lit(tester, 'Console buttons'), isTrue);
    expect(lit(tester, 'Every 1%'), isFalse);
    expect(lit(tester, '5% up, 3% down'), isFalse);
  });

  testWidgets('the platform reporting physical lights only that pill', (
    tester,
  ) async {
    settings = <String, Object?>{
      'transport': 'glassos',
      'inclineSpacing': 'physical',
    };
    await openAdvanced(tester);
    await tester.ensureVisible(find.text('Console buttons'));
    await tester.pumpAndSettle();

    expect(lit(tester, 'Console buttons'), isTrue);
    expect(lit(tester, 'Every 1%'), isFalse);
    expect(lit(tester, '5% up, 3% down'), isFalse);
  });

  /// The copy has to say where the setting does and does not apply. On iFit the
  /// console publishes its own quick picks and this choice cannot re-space
  /// them, and a rider who is not told that will report the setting as broken.
  testWidgets('the section says it does not apply to the iFit column', (
    tester,
  ) async {
    await openAdvanced(tester);

    expect(find.textContaining('console publishes its own'), findsOneWidget);
  });
}
