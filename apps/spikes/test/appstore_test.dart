import 'package:flutter_test/flutter_test.dart';
import 'package:stride_spikes/model/appstore.dart';

/// The app store snapshot is decoded from a method-channel map, so the contract
/// under test is "what happens when the platform half says something unexpected,
/// or nothing at all". A launcher that throws while decoding an update list is a
/// console that cannot be used to run.
void main() {
  Map<String, dynamic> item({
    String package = 'com.example.app',
    String kind = 'update',
    bool isSelf = false,
    String stage = 'idle',
    int bytes = 0,
    int totalBytes = 0,
    String? message,
    String? ineligibleReason,
  }) => <String, dynamic>{
    'package': package,
    'name': 'Example',
    'kind': kind,
    'isSelf': isSelf,
    'availableVersionName': '2.0',
    'installedVersionName': '1.0',
    'sizeBytes': 1024,
    'stage': stage,
    'bytes': bytes,
    'totalBytes': totalBytes,
    'message': message,
    'ineligibleReason': ineligibleReason,
  };

  Map<String, dynamic> status({
    List<Map<String, dynamic>>? items,
    bool mayInstallNow = true,
    bool workoutIdle = true,
    String holdReason = '',
    String? lastError,
  }) => <String, dynamic>{
    'checking': false,
    'busy': false,
    'serviceRunning': true,
    'canRequestInstalls': true,
    'workoutIdle': workoutIdle,
    'mayInstallNow': mayInstallNow,
    'holdReason': holdReason,
    'lastCheckWallMs': 1723600000000,
    'lastError': lastError,
    'items': items ?? const <Map<String, dynamic>>[],
  };

  group('AppstoreStatus decoding', () {
    test('an empty map decodes to a usable, inert status', () {
      final decoded = AppstoreStatus.fromMap(const <String, dynamic>{});
      expect(decoded.items, isEmpty);
      expect(decoded.pendingCount, 0);
      expect(decoded.selfUpdate, isNull);
      // Defaulting mayInstallNow to false is the safe direction: the buttons
      // stay disabled when we cannot tell whether installing is allowed.
      expect(decoded.mayInstallNow, isFalse);
    });

    test('a missing items list does not throw', () {
      final decoded = AppstoreStatus.fromMap(<String, dynamic>{
        'checking': true,
      });
      expect(decoded.checking, isTrue);
      expect(decoded.items, isEmpty);
    });

    test('an unknown stage decodes to idle rather than throwing', () {
      final decoded = AppstoreItem.fromMap(item(stage: 'teleporting'));
      expect(decoded.stage, AppstoreStage.idle);
    });

    test('an unknown kind decodes to upToDate', () {
      final decoded = AppstoreItem.fromMap(item(kind: 'wat'));
      expect(decoded.kind, AppstoreKind.upToDate);
    });
  });

  group('badge count', () {
    test('counts updates, including Stride itself', () {
      final decoded = AppstoreStatus.fromMap(
        status(
          items: [
            item(package: 'com.a'),
            item(package: 'io.stride.spikes', isSelf: true),
          ],
        ),
      );
      expect(decoded.pendingCount, 2);
      expect(decoded.selfUpdate?.package, 'io.stride.spikes');
      // The self-update is drawn in its own section, so it is excluded here.
      expect(decoded.updates.map((e) => e.package), ['com.a']);
    });

    test('apps merely available to install never badge the launcher', () {
      final decoded = AppstoreStatus.fromMap(
        status(
          items: [
            item(package: 'com.a', kind: 'notInstalled'),
            item(package: 'com.b', kind: 'upToDate'),
            item(package: 'com.c', kind: 'ineligible'),
          ],
        ),
      );
      expect(decoded.pendingCount, 0);
      expect(decoded.available.map((e) => e.package), ['com.a']);
      expect(decoded.rest.map((e) => e.package), ['com.b', 'com.c']);
    });
  });

  group('install availability', () {
    test('a workout in progress disables installing and says why', () {
      final decoded = AppstoreStatus.fromMap(
        status(
          items: [item()],
          mayInstallNow: false,
          workoutIdle: false,
          holdReason: 'waiting until the workout ends',
        ),
      );
      expect(decoded.mayInstallNow, isFalse);
      expect(decoded.holdReason, isNotEmpty);
      // The row itself is still actionable; it is the sheet that disables the
      // button, so the control stays visible and explains itself.
      expect(decoded.items.single.isActionable, isTrue);
    });

    test('an in-flight item reports itself busy', () {
      for (final stage in ['downloading', 'installing']) {
        expect(
          AppstoreItem.fromMap(item(stage: stage)).stage.isBusy,
          isTrue,
          reason: stage,
        );
      }
      expect(AppstoreItem.fromMap(item(stage: 'ready')).stage.isBusy, isFalse);
    });

    test('a prompt waiting on the user is not busy, so it stays pressable', () {
      // Raising a second install confirmation destroys the first one and the platform reports
      // nothing back, so a package can sit in awaiting_user indefinitely. Treating that as busy
      // disabled its button forever; it has to stay pressable so the prompt can be re-raised.
      final stage = AppstoreItem.fromMap(item(stage: 'awaiting_user')).stage;
      expect(stage.isBusy, isFalse);
      expect(stage.needsConfirm, isTrue);
    });

    test('an up-to-date or ineligible row is not actionable', () {
      expect(
        AppstoreItem.fromMap(item(kind: 'upToDate')).isActionable,
        isFalse,
      );
      expect(
        AppstoreItem.fromMap(item(kind: 'ineligible')).isActionable,
        isFalse,
      );
    });
  });

  group('progress', () {
    test('is null unless downloading with a known total', () {
      expect(AppstoreItem.fromMap(item()).progress, isNull);
      expect(
        AppstoreItem.fromMap(item(stage: 'downloading', bytes: 5)).progress,
        isNull,
      );
    });

    test('clamps rather than overshooting', () {
      final decoded = AppstoreItem.fromMap(
        item(stage: 'downloading', bytes: 200, totalBytes: 100),
      );
      expect(decoded.progress, 1.0);
    });
  });

  group('subtitles', () {
    test('a platform message wins over any derived text', () {
      final decoded = AppstoreItem.fromMap(
        item(stage: 'failed', message: 'sha256 mismatch'),
      );
      expect(decoded.subtitle, 'sha256 mismatch');
    });

    test('an idle update reads as a version transition', () {
      expect(AppstoreItem.fromMap(item()).subtitle, '1.0 -> 2.0');
    });

    test('ineligibility is explained, not hidden', () {
      expect(
        AppstoreItem.fromMap(
          item(kind: 'ineligible', ineligibleReason: 'abi_mismatch'),
        ).subtitle,
        'Not built for this console',
      );
    });

    test('a Play Services dependency is named as the reason', () {
      // Otherwise this reads as an unexplained missing app, and the obvious next
      // move is to go sideload Play Store, which cannot work here.
      expect(
        AppstoreItem.fromMap(
          item(kind: 'ineligible', ineligibleReason: 'needs_gms'),
        ).subtitle,
        'Needs Google Play Services, which this console cannot run',
      );
    });
  });

  group('AppstoreSetupStep', () {
    test('decodes a step and its adb fallback', () {
      final step = AppstoreSetupStep.fromMap(<String, dynamic>{
        'id': 'overlay',
        'title': 'Draw over other apps',
        'detail': 'The stop control is an overlay window.',
        'done': false,
        'adb':
            'adb shell appops set io.stride.spikes SYSTEM_ALERT_WINDOW allow',
        'action': null,
      });
      expect(step.done, isFalse);
      expect(step.adb, contains('SYSTEM_ALERT_WINDOW'));
      expect(step.action, isNull);
    });

    test(
      'a malformed step decodes to an incomplete one rather than throwing',
      () {
        final step = AppstoreSetupStep.fromMap(const <String, dynamic>{});
        expect(step.done, isFalse);
        expect(step.title, isEmpty);
      },
    );
  });
}
