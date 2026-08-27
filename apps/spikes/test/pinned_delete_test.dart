// Deleting an app from the pinned grid's arrange mode.
//
// The behaviour worth pinning here is the guard rail, not the layout: a delete
// is two deliberate taps away from a tile, and it is never offered for a package
// the console refuses to uninstall.

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stride_spikes/model/profile_store.dart';
import 'package:stride_spikes/screens/launcher_home.dart';
import 'package:stride_spikes/widgets/app_tile.dart';

const MethodChannel _channel = MethodChannel('io.stride.spikes/bridge');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late List<Map<String, Object?>> installed;
  late List<String> uninstallRequests;
  late bool uninstallSucceeds;

  Map<String, Object?> app({
    required String package,
    required String label,
    required bool removable,
  }) => <String, Object?>{
    'package': package,
    'label': label,
    // A MediaBrowserService scores high enough for ProfileStore to pin the app
    // on the first scan, which is what puts it in the grid this test drives.
    'hasMediaBrowserService': true,
    'removable': removable,
  };

  setUp(() {
    uninstallRequests = <String>[];
    uninstallSucceeds = true;
    installed = <Map<String, Object?>>[
      app(package: 'com.example.player', label: 'Player', removable: true),
      app(package: 'com.example.system', label: 'Builtin', removable: false),
    ];

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
          switch (call.method) {
            case 'listApps':
              return installed;
            case 'appstoreStatus':
              return <String, Object?>{
                'initialization': 'ready',
                'items': <Object?>[],
              };
            case 'uninstallApp':
              uninstallRequests.add(
                (call.arguments as Map)['package'] as String? ?? '',
              );
              return uninstallSucceeds;
            default:
              return null;
          }
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, null);
  });

  Future<void> pumpLauncher(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: LauncherHome(profiles: ProfileStore(storage: _MemoryStorage())),
      ),
    );
    await tester.pumpAndSettle();
  }

  /// Arrange mode is armed by holding a tile, the same gesture that starts a
  /// drag. There is no separate button to press.
  Future<void> enterArrangeMode(WidgetTester tester, String label) async {
    await tester.longPress(find.text(label));
    await tester.pumpAndSettle();
    expect(find.text('Arrange apps'), findsOneWidget);
  }

  Future<void> openTileMenu(WidgetTester tester, String label) async {
    final remove = find.descendant(
      of: find.ancestor(of: find.text(label), matching: find.byType(AppTile)),
      matching: find.byIcon(Icons.close),
    );
    await tester.tap(remove.first);
    await tester.pumpAndSettle();
  }

  testWidgets('a removable app can be deleted, behind its own confirmation', (
    tester,
  ) async {
    await pumpLauncher(tester);
    await enterArrangeMode(tester, 'Player');
    await openTileMenu(tester, 'Player');

    // Nothing is destroyed by the first sheet: it only offers the choice.
    expect(find.text('Delete from console'), findsOneWidget);
    expect(uninstallRequests, isEmpty);

    await tester.tap(find.text('Delete from console'));
    await tester.pumpAndSettle();

    // The second sheet is the confirmation, and it still has not asked the
    // platform for anything.
    expect(find.text('Delete Player from this console?'), findsOneWidget);
    expect(uninstallRequests, isEmpty);

    await tester.tap(find.text('Delete Player'));
    await tester.pumpAndSettle();

    expect(uninstallRequests, <String>['com.example.player']);
  });

  testWidgets('the confirmation can be backed out of', (tester) async {
    await pumpLauncher(tester);
    await enterArrangeMode(tester, 'Player');
    await openTileMenu(tester, 'Player');
    await tester.tap(find.text('Delete from console'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();

    expect(uninstallRequests, isEmpty);
    // Cancelling a delete must not quietly unpin instead.
    expect(find.text('Player'), findsOneWidget);
  });

  testWidgets('an app the console will not remove is never offered a delete', (
    tester,
  ) async {
    await pumpLauncher(tester);
    await enterArrangeMode(tester, 'Builtin');
    await openTileMenu(tester, 'Builtin');

    expect(find.text('Unpin app'), findsOneWidget);
    expect(find.text('Delete from console'), findsNothing);
  });

  testWidgets('a refused uninstall says so instead of failing silently', (
    tester,
  ) async {
    uninstallSucceeds = false;
    await pumpLauncher(tester);
    await enterArrangeMode(tester, 'Player');
    await openTileMenu(tester, 'Player');
    await tester.tap(find.text('Delete from console'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Delete Player'));
    await tester.pumpAndSettle();

    expect(
      find.text('Player cannot be deleted on this console'),
      findsOneWidget,
    );
    // Still pinned: a delete that never started must not cost the rider the pin.
    expect(find.text('Player'), findsOneWidget);
  });
}

/// The real store writes JSON through path_provider, which a widget test cannot
/// drive; the pins only have to survive the test.
class _MemoryStorage implements ProfileStorage {
  String? _contents;

  @override
  Future<String?> read() async => _contents;

  @override
  Future<void> write(String contents) async => _contents = contents;
}
