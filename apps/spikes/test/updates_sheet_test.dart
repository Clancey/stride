// The updates sheet.
//
// The behaviour worth pinning here is what counts as an update. An app the catalog offers but the
// console has never had is not pending work, and listing it here made the sheet contradict itself:
// "Everything is up to date" directly above a column of Install buttons.

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stride_spikes/screens/updates_sheet.dart';

const MethodChannel _channel = MethodChannel('io.stride.spikes/bridge');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Map<String, Object?> status;

  Map<String, Object?> item({
    required String package,
    required String name,
    required String kind,
    bool isSelf = false,
    String? installedVersionName,
  }) => <String, Object?>{
    'package': package,
    'name': name,
    'kind': kind,
    'isSelf': isSelf,
    'availableVersionName': '2.0',
    'installedVersionName': ?installedVersionName,
    'sizeBytes': 1024,
    'stage': 'idle',
    'bytes': 0,
    'totalBytes': 0,
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
    status = statusWith(<Map<String, Object?>>[]);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
          switch (call.method) {
            case 'appstoreStatus':
              return status;
            case 'appstoreSetupChecklist':
              return <Map<String, Object?>>[];
            case 'appstoreCheckNow':
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

  Future<void> openSheet(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: ElevatedButton(
                onPressed: () => showUpdatesSheet(context),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  testWidgets('an app that was never installed is not listed as an update', (
    tester,
  ) async {
    status = statusWith([
      item(package: 'com.spotify.music', name: 'Spotify', kind: 'notInstalled'),
    ]);

    await openSheet(tester);

    expect(find.text('Everything is up to date.'), findsOneWidget);
    expect(find.text('Spotify'), findsNothing);
    expect(find.text('App updates'.toUpperCase()), findsNothing);
    // It is not hidden, just relocated: the sheet says where it went.
    expect(
      find.textContaining('1 more app is available in All apps'),
      findsOneWidget,
    );
  });

  testWidgets('a genuine update is listed, with its version change', (
    tester,
  ) async {
    status = statusWith([
      item(
        package: 'com.example.one',
        name: 'One',
        kind: 'update',
        installedVersionName: '1.0',
      ),
    ]);

    await openSheet(tester);

    expect(find.text('1 update pending.'), findsOneWidget);
    expect(find.text('One'), findsOneWidget);
    expect(find.text('1.0 -> 2.0'), findsOneWidget);
  });

  testWidgets('updates and merely-available apps are counted separately', (
    tester,
  ) async {
    status = statusWith([
      item(
        package: 'com.example.one',
        name: 'One',
        kind: 'update',
        installedVersionName: '1.0',
      ),
      item(package: 'com.a', name: 'A', kind: 'notInstalled'),
      item(package: 'com.b', name: 'B', kind: 'notInstalled'),
    ]);

    await openSheet(tester);

    // One pending update, not three.
    expect(find.text('1 update pending.'), findsOneWidget);
    expect(
      find.textContaining('2 more apps are available in All apps'),
      findsOneWidget,
    );
    expect(find.text('A'), findsNothing);
    expect(find.text('B'), findsNothing);
  });

  testWidgets('Stride updating itself is drawn apart from ordinary apps', (
    tester,
  ) async {
    status = statusWith([
      item(
        package: 'io.stride.spikes',
        name: 'Stride',
        kind: 'update',
        isSelf: true,
        installedVersionName: '1.0',
      ),
      item(
        package: 'com.example.one',
        name: 'One',
        kind: 'update',
        installedVersionName: '1.0',
      ),
    ]);

    await openSheet(tester);

    expect(find.text('Stride'.toUpperCase()), findsOneWidget);
    expect(find.text('App updates'.toUpperCase()), findsOneWidget);
    // Its button names the consequence, because installing it restarts the launcher.
    expect(find.text('Update Stride'), findsOneWidget);
  });

  testWidgets('an app with no icon falls back to a letter rather than a gap', (
    tester,
  ) async {
    // appIcon returns null here, which is the ordinary case for an app the console does not have.
    status = statusWith([
      item(
        package: 'com.example.one',
        name: 'One',
        kind: 'update',
        installedVersionName: '1.0',
      ),
    ]);

    await openSheet(tester);

    expect(find.text('O'), findsOneWidget);
  });
}
