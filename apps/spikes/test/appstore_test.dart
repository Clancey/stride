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

    test('a Play dependency is named as the reason, and as fixable', () {
      // This used to say Play "cannot run" here, which was true until Stride
      // could install it. Now the one-tap bundle makes it a missing
      // prerequisite rather than a permanent verdict, and the copy has to say
      // so or the rider will not go looking for the button that fixes it.
      expect(
        AppstoreItem.fromMap(
          item(kind: 'ineligible', ineligibleReason: 'needs_gms'),
        ).subtitle,
        'Needs Google Play, which is not installed yet',
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

  group('bundles', () {
    Map<String, dynamic> bundle({
      String id = 'google-play',
      String state = 'missing',
      int installedCount = 0,
      int totalCount = 4,
      bool running = false,
      bool failed = false,
      bool restartPending = false,
      String? message,
    }) => <String, dynamic>{
      'id': id,
      'name': 'Google Play',
      'detail': 'Play Store and the services it needs',
      'state': state,
      'installedCount': installedCount,
      'totalCount': totalCount,
      'sizeBytes': 4096,
      'restartRequired': true,
      'running': running,
      'failed': failed,
      'restartPending': restartPending,
      'message': message,
    };

    Map<String, dynamic> statusWith({
      List<Map<String, dynamic>> items = const <Map<String, dynamic>>[],
      List<Map<String, dynamic>> bundles = const <Map<String, dynamic>>[],
    }) => <String, dynamic>{...status(items: items), 'bundles': bundles};

    test('a status without bundles decodes to none', () {
      expect(AppstoreStatus.fromMap(status()).bundles, isEmpty);
      expect(AppstoreStatus.fromMap(status()).offerableBundles, isEmpty);
    });

    test('members of a bundle are not offered as separate apps', () {
      // Four rows saying "Install" in an order that matters is exactly the trap
      // the bundle exists to close. They belong to the bundle row or nowhere.
      final decoded = AppstoreStatus.fromMap(
        statusWith(
          items: <Map<String, dynamic>>[
            item(package: 'com.android.vending', kind: 'notInstalled')
              ..['bundleId'] = 'google-play',
            item(package: 'com.google.android.gsf', kind: 'update')
              ..['bundleId'] = 'google-play',
            item(package: 'com.example.solo', kind: 'notInstalled'),
          ],
          bundles: <Map<String, dynamic>>[bundle()],
        ),
      );
      expect(
        decoded.available.map((i) => i.package),
        <String>['com.example.solo'],
      );
      expect(decoded.updates, isEmpty);
      expect(decoded.pendingCount, 0);
    });

    test('a half installed bundle offers to finish rather than restart', () {
      final b = AppstoreStatus.fromMap(
        statusWith(
          bundles: <Map<String, dynamic>>[
            bundle(state: 'partial', installedCount: 2),
          ],
        ),
      ).bundles.single;
      expect(b.actionLabel, 'Finish installing');
      expect(b.subtitle, '2 of 4 parts installed');
      expect(b.isComplete, isFalse);
    });

    test('a complete bundle stops being offered', () {
      final decoded = AppstoreStatus.fromMap(
        statusWith(
          bundles: <Map<String, dynamic>>[
            bundle(state: 'installed', installedCount: 4),
          ],
        ),
      );
      expect(decoded.bundles.single.isComplete, isTrue);
      expect(decoded.offerableBundles, isEmpty);
    });

    test('a bundle awaiting its reboot is still shown', () {
      // The one step Stride cannot take itself. If the row vanished on the last
      // install the rider would never be told to power cycle, and Play would sit
      // there broken.
      final decoded = AppstoreStatus.fromMap(
        statusWith(
          bundles: <Map<String, dynamic>>[
            bundle(
              state: 'installed',
              installedCount: 4,
              restartPending: true,
              message: 'Restart the console to finish',
            ),
          ],
        ),
      );
      expect(decoded.offerableBundles, hasLength(1));
      expect(decoded.offerableBundles.single.subtitle, contains('Restart'));
    });

    test('an offerable bundle badges the header even with no updates', () {
      // pendingCount deliberately counts only updates to installed apps, so on a
      // console with nothing stale it is zero - and the header fell back to an
      // unlabelled icon while "Install Google Play", the most consequential
      // thing the sheet can offer, sat behind it giving no reason to tap.
      final decoded = AppstoreStatus.fromMap(
        statusWith(
          items: <Map<String, dynamic>>[
            item(package: 'com.android.vending', kind: 'notInstalled')
              ..['bundleId'] = 'google-play',
          ],
          bundles: <Map<String, dynamic>>[bundle()],
        ),
      );
      expect(decoded.pendingCount, 0);
      expect(decoded.actionableCount, 1);
    });

    test('a finished bundle stops badging the header', () {
      final decoded = AppstoreStatus.fromMap(
        statusWith(
          bundles: <Map<String, dynamic>>[
            bundle(state: 'installed', installedCount: 4),
          ],
        ),
      );
      expect(decoded.actionableCount, 0);
    });

    test('the badge counts updates and bundles together', () {
      final decoded = AppstoreStatus.fromMap(
        statusWith(
          items: <Map<String, dynamic>>[
            item(package: 'com.example.one', kind: 'update'),
            item(package: 'com.example.two', kind: 'update'),
          ],
          bundles: <Map<String, dynamic>>[bundle()],
        ),
      );
      expect(decoded.actionableCount, 3);
    });
  });
}
