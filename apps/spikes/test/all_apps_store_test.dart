// The Store tab of the "All apps" screen.
//
// The behaviour worth pinning here is not the layout, it is the reconciliation:
// an app installed from the catalog has to appear in the Installed tab without
// anyone leaving the screen, and the poll that notices it must not then reload
// the inventory forever.

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:stride_spikes/model/profile_store.dart';
import 'package:stride_spikes/screens/all_apps.dart';
import 'package:stride_spikes/widgets/app_models.dart';
import 'package:stride_spikes/widgets/app_tile.dart';

const MethodChannel _channel = MethodChannel('io.stride.spikes/bridge');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  /// Installed inventory the fake platform reports, and the catalog snapshot
  /// beside it. Tests mutate these to simulate an install completing.
  late List<Map<String, Object?>> installed;
  late Map<String, Object?> status;
  late int listAppsCalls;
  late List<String> installRequests;

  Map<String, Object?> item({
    required String package,
    required String name,
    required String kind,
    String stage = 'idle',
    String? ineligibleReason,
  }) => <String, Object?>{
    'package': package,
    'name': name,
    'kind': kind,
    'isSelf': false,
    'availableVersionName': '1.0',
    'sizeBytes': 1024,
    'stage': stage,
    'bytes': 0,
    'totalBytes': 0,
    'ineligibleReason': ?ineligibleReason,
  };

  Map<String, Object?> statusWith(List<Map<String, Object?>> items) =>
      <String, Object?>{
        'checking': false,
        'busy': false,
        'serviceRunning': true,
        'canRequestInstalls': true,
        'workoutIdle': true,
        'mayInstallNow': true,
        'holdReason': '',
        'lastCheckWallMs': 1,
        'items': items,
      };

  setUp(() {
    listAppsCalls = 0;
    installRequests = <String>[];
    installed = <Map<String, Object?>>[
      {'package': 'com.example.one', 'label': 'One'},
    ];
    status = statusWith([
      item(package: 'com.spotify.music', name: 'Spotify', kind: 'notInstalled'),
      item(
        package: 'com.needs.gms',
        name: 'Needs GMS',
        kind: 'ineligible',
        ineligibleReason: 'needs_gms',
      ),
      item(package: 'com.example.one', name: 'One', kind: 'upToDate'),
    ]);

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
          switch (call.method) {
            case 'listApps':
              listAppsCalls++;
              return installed;
            case 'appstoreStatus':
              return status;
            case 'appstoreInstall':
              installRequests.add(
                (call.arguments as Map)['package'] as String? ?? '',
              );
              return true;
            case 'appstoreCheckNow':
              return true;
            default:
              return null;
          }
        });
    SharedPreferencesStub.install();
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, null);
  });

  /// Tab labels carry live counts, and "Installed" also appears in row text,
  /// so address the tabs through the TabBar rather than by loose text match.
  Finder tab(String label) => find.descendant(
    of: find.byType(TabBar),
    matching: find.textContaining(label),
  );

  Future<void> pumpScreen(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: AllAppsScreen(
          apps: <LaunchableApp>[
            LaunchableApp.fromMap(const <String, dynamic>{
              'package': 'com.example.one',
              'label': 'One',
            }),
          ],
          profiles: ProfileStore(),
          iconCache: AppIconCache(),
          onLaunch: (_) async {},
        ),
      ),
    );
    await tester.pump();
  }

  testWidgets('Store tab lists catalog apps that are not installed', (
    tester,
  ) async {
    await pumpScreen(tester);
    await tester.tap(tab('Store'));
    await tester.pumpAndSettle();

    expect(find.text('Spotify'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Install'), findsOneWidget);
  });

  testWidgets('ineligible catalog apps are shown with a reason, not hidden', (
    tester,
  ) async {
    await pumpScreen(tester);
    await tester.tap(tab('Store'));
    await tester.pumpAndSettle();

    expect(find.text('Needs GMS'), findsOneWidget);
    expect(
      find.text('Needs Google Play, which is not installed yet'),
      findsOneWidget,
    );
    // Ineligible rows get no button at all - there is nothing to press.
    expect(find.widgetWithText(FilledButton, 'Install'), findsOneWidget);
  });

  testWidgets('an app already installed is not offered again in Store', (
    tester,
  ) async {
    await pumpScreen(tester);
    await tester.tap(tab('Store'));
    await tester.pumpAndSettle();

    // com.example.one is upToDate, so it belongs to the updates sheet, not here.
    expect(find.widgetWithText(FilledButton, 'Install'), findsOneWidget);
  });

  testWidgets('installing from Store refreshes the installed inventory', (
    tester,
  ) async {
    await pumpScreen(tester);
    await tester.tap(tab('Store'));
    await tester.pumpAndSettle();

    final before = listAppsCalls;

    await tester.tap(find.widgetWithText(FilledButton, 'Install'));
    await tester.pump();
    expect(installRequests, contains('com.spotify.music'));

    // The platform now reports it installed, and present in the inventory.
    installed = <Map<String, Object?>>[
      {'package': 'com.example.one', 'label': 'One'},
      {'package': 'com.spotify.music', 'label': 'Spotify'},
    ];
    status = statusWith([
      item(
        package: 'com.spotify.music',
        name: 'Spotify',
        kind: 'notInstalled',
        stage: 'installed',
      ),
    ]);

    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();

    expect(
      listAppsCalls,
      greaterThan(before),
      reason: 'a completed install must re-read the installed app list',
    );

    await tester.tap(tab('Installed'));
    await tester.pumpAndSettle();
    // Scoped to the grid tile: TabBarView keeps the Store tab alive, so a bare
    // text match would also find the catalog row it was installed from.
    expect(find.widgetWithText(AppTile, 'Spotify'), findsOneWidget);
  });

  testWidgets(
    'a completed install reloads the inventory once, not every poll',
    (tester) async {
      await pumpScreen(tester);
      await tester.tap(tab('Store'));
      await tester.pumpAndSettle();

      status = statusWith([
        item(
          package: 'com.spotify.music',
          name: 'Spotify',
          kind: 'notInstalled',
          stage: 'installed',
        ),
      ]);

      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
      final afterFirst = listAppsCalls;

      // Several more polls with the same 'installed' snapshot.
      await tester.pump(const Duration(seconds: 3));
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();

      expect(
        listAppsCalls,
        afterFirst,
        reason:
            'the same completed install must not re-read the inventory forever',
      );
    },
  );

  testWidgets('a prompt the user missed can be raised again', (
    WidgetTester tester,
  ) async {
    // The platform destroys a confirmation dialog when a second one opens, and reports
    // nothing back. If that leaves the row disabled, the app the rider asked for is the
    // one they can never install - so the button has to stay live and say what it does.
    status = statusWith([
      item(
        package: 'com.netflix.mediaclient',
        name: 'Netflix',
        kind: 'notInstalled',
        stage: 'awaiting_user',
      ),
    ]);

    await pumpScreen(tester);
    await tester.tap(tab('Store'));
    await tester.pumpAndSettle();

    final button = find.widgetWithText(FilledButton, 'Confirm');
    expect(button, findsOneWidget);
    expect(tester.widget<FilledButton>(button).onPressed, isNotNull);

    await tester.tap(button);
    await tester.pumpAndSettle();
    expect(installRequests, contains('com.netflix.mediaclient'));
  });

  testWidgets('an update to an installed app stays out of the Store tab', (
    tester,
  ) async {
    // The Store offers what the console does not have. An update belongs to the updates sheet, and
    // showing it in both places gives two buttons for one install and two places to disagree about
    // what happened.
    status = statusWith([
      item(package: 'com.example.one', name: 'One', kind: 'update'),
      item(package: 'com.spotify.music', name: 'Spotify', kind: 'notInstalled'),
    ]);

    await pumpScreen(tester);
    await tester.tap(tab('Store'));
    await tester.pumpAndSettle();

    expect(find.text('Spotify'), findsOneWidget);
    expect(find.text('One'), findsNothing);
  });

  testWidgets('a store row shows a letter when no icon can be loaded', (
    tester,
  ) async {
    // appIcon is unanswered by the fake platform, which is exactly what happens for an app the
    // console has never installed. The row still needs a mark, not a hole.
    await pumpScreen(tester);
    await tester.tap(tab('Store'));
    await tester.pumpAndSettle();

    expect(find.text('S'), findsOneWidget);
  });
}

/// ProfileStore reads SharedPreferences; give it an empty in-memory store.
class SharedPreferencesStub {
  static void install() {
    const channel = MethodChannel('plugins.flutter.io/shared_preferences');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'getAll') return <String, Object>{};
          return true;
        });
  }
}
