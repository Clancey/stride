import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:stride_spikes/model/profile_store.dart';

void main() {
  group('ProfileStore load recovery', () {
    test('recovers to one default profile when JSON is missing', () async {
      final store = ProfileStore(storage: MemoryProfileStorage());

      await store.load();

      expect(store.loaded, isTrue);
      expect(store.profiles, hasLength(1));
      expect(store.active.name, 'Default');
      expect(store.active.pinned, isEmpty);
    });

    test('recovers to one default profile when JSON is empty', () async {
      final store = ProfileStore(
        storage: MemoryProfileStorage(contents: '   '),
      );

      await store.load();

      expect(store.loaded, isTrue);
      expect(store.profiles.single.name, 'Default');
    });

    test('recovers to one default profile when JSON is malformed', () async {
      final store = ProfileStore(
        storage: MemoryProfileStorage(contents: '{ nope'),
      );

      await store.load();

      expect(store.loaded, isTrue);
      expect(store.profiles.single.name, 'Default');
    });

    test(
      'salvages valid profiles and keeps unavailable pinned packages',
      () async {
        final storage = MemoryProfileStorage(
          contents: jsonEncode(<String, dynamic>{
            'version': 1,
            'activeProfileId': 'p-good',
            'profiles': <Object?>[
              <String, dynamic>{
                'id': 'p-good',
                'name': 'Runner',
                'pinned': <String>['com.missing.app', 'com.missing.app'],
              },
              <String, dynamic>{'id': '', 'name': 'bad', 'pinned': <String>[]},
            ],
            'autoOfferedPackages': <String>[],
            'usedProfileIds': <String>['p-good'],
            'nextProfileSerial': 1,
          }),
        );
        final store = ProfileStore(storage: storage);

        await store.load();

        expect(store.profiles, hasLength(1));
        expect(store.active.pinned, <String>['com.missing.app']);
      },
    );
  });

  group('ProfileStore persistence', () {
    test(
      'writes complete JSON and rolls back state if a write fails',
      () async {
        final storage = MemoryProfileStorage();
        final store = ProfileStore(storage: storage);
        await store.load();

        await store.pin('com.spotify.music');
        final committed = storage.contents;
        expect(committed, isNotNull);
        expect(jsonDecode(committed!), isA<Map<String, dynamic>>());
        expect(store.isPinned('com.spotify.music'), isTrue);

        storage.failWrites = 1;
        await expectLater(
          store.pin('com.google.android.youtube'),
          throwsA(isA<FileSystemException>()),
        );

        expect(
          storage.contents,
          committed,
          reason: 'the fake commits only whole successful writes',
        );
        expect(store.isPinned('com.spotify.music'), isTrue);
        expect(
          store.isPinned('com.google.android.youtube'),
          isFalse,
          reason: 'in-memory state must not drift ahead of durable storage',
        );
      },
    );

    test('notifies listeners after each real mutation', () async {
      final store = ProfileStore(storage: MemoryProfileStorage());
      await store.load();
      var notifications = 0;
      store.addListener(() => notifications++);

      await store.pin('com.spotify.music');
      await store.pin('com.spotify.music');
      await store.unpin('com.spotify.music');
      await store.createProfile('Guest');

      expect(notifications, 3);
    });
  });

  group('Profile mutations', () {
    test('refuses to delete the last profile', () async {
      final store = ProfileStore(storage: MemoryProfileStorage());
      await store.load();

      await expectLater(
        store.deleteProfile(store.active.id),
        throwsA(isA<StateError>()),
      );
      expect(store.profiles, hasLength(1));
    });

    test(
      'moves active profile deterministically when deleting the active one',
      () async {
        final store = ProfileStore(storage: MemoryProfileStorage());
        await store.load();
        final defaultId = store.active.id;
        final work = await store.createProfile('Work');
        final guest = await store.createProfile('Guest');

        await store.setActive(work.id);
        await store.deleteProfile(work.id);
        expect(
          store.active.id,
          guest.id,
          reason: 'deleting a middle active profile selects the next row',
        );

        await store.deleteProfile(guest.id);
        expect(
          store.active.id,
          defaultId,
          reason: 'deleting the last active profile selects the previous row',
        );
      },
    );

    test('prevents duplicate pins from API calls and loaded JSON', () async {
      final storage = MemoryProfileStorage(
        contents: jsonEncode(<String, dynamic>{
          'version': 1,
          'activeProfileId': 'profile-1',
          'profiles': <Map<String, dynamic>>[
            <String, dynamic>{
              'id': 'profile-1',
              'name': 'Default',
              'pinned': <String>['com.spotify.music', 'com.spotify.music'],
            },
          ],
          'autoOfferedPackages': <String>[],
          'usedProfileIds': <String>['profile-1'],
          'nextProfileSerial': 2,
        }),
      );
      final store = ProfileStore(storage: storage);

      await store.load();
      await store.pin('com.spotify.music');

      expect(store.active.pinned, <String>['com.spotify.music']);
    });

    test(
      'uses Flutter ReorderableListView index convention when moving down',
      () async {
        final store = ProfileStore(storage: MemoryProfileStorage());
        await store.load();
        for (final package in <String>['a', 'b', 'c', 'd']) {
          await store.pin(package);
        }

        await store.reorderPinned(1, 3);
        expect(store.active.pinned, <String>['a', 'c', 'b', 'd']);

        await store.reorderPinned(3, 1);
        expect(store.active.pinned, <String>['a', 'd', 'c', 'b']);
      },
    );
  });

  group('ProfileStore auto-add media apps', () {
    test(
      'auto-adds high-confidence media apps and ignores weak ranking hints',
      () async {
        final store = ProfileStore(storage: MemoryProfileStorage());
        await store.load();

        final added = await store.autoAddMediaApps(<Map<String, dynamic>>[
          <String, dynamic>{'package': 'com.example.musicname'},
          <String, dynamic>{'package': 'com.example.tvapp', 'leanback': true},
          <String, dynamic>{'package': 'com.google.android.youtube'},
          <String, dynamic>{
            'package': 'com.vendor.player',
            'hasMediaBrowserService': true,
          },
        ]);

        expect(added, <String>[
          'com.google.android.youtube',
          'com.vendor.player',
        ]);
        expect(store.active.pinned, added);
      },
    );

    test(
      'auto-add is once ever, including unpin then rescan and reload',
      () async {
        final storage = MemoryProfileStorage();
        final store = ProfileStore(storage: storage);
        await store.load();
        final spotify = <Map<String, dynamic>>[
          <String, dynamic>{'package': 'com.spotify.music'},
        ];

        expect(await store.autoAddMediaApps(spotify), <String>[
          'com.spotify.music',
        ]);
        await store.unpin('com.spotify.music');
        expect(store.isPinned('com.spotify.music'), isFalse);
        expect(await store.autoAddMediaApps(spotify), isEmpty);
        expect(store.isPinned('com.spotify.music'), isFalse);

        final reloaded = ProfileStore(storage: storage);
        await reloaded.load();
        expect(await reloaded.autoAddMediaApps(spotify), isEmpty);
        expect(reloaded.isPinned('com.spotify.music'), isFalse);
      },
    );

    test('marks already-pinned detected media as offered', () async {
      final store = ProfileStore(storage: MemoryProfileStorage());
      await store.load();
      final youtube = <Map<String, dynamic>>[
        <String, dynamic>{'package': 'com.google.android.youtube'},
      ];

      await store.pin('com.google.android.youtube');
      expect(await store.autoAddMediaApps(youtube), isEmpty);
      await store.unpin('com.google.android.youtube');
      expect(
        await store.autoAddMediaApps(youtube),
        isEmpty,
        reason: 'a manual unpin after the first scan is still respected',
      );
    });

    test('new profiles get their own auto-add offers', () async {
      final store = ProfileStore(storage: MemoryProfileStorage());
      await store.load();
      final media = <Map<String, dynamic>>[
        <String, dynamic>{'package': 'com.spotify.music'},
        <String, dynamic>{'package': 'com.google.android.youtube'},
      ];

      expect(await store.autoAddMediaApps(media), <String>[
        'com.spotify.music',
        'com.google.android.youtube',
      ]);
      final defaultId = store.active.id;

      await store.createProfile('Running');
      expect(store.active.pinned, isEmpty);
      expect(await store.autoAddMediaApps(media), <String>[
        'com.spotify.music',
        'com.google.android.youtube',
      ]);
      expect(store.active.pinned, <String>[
        'com.spotify.music',
        'com.google.android.youtube',
      ]);

      await store.setActive(defaultId);
      expect(store.active.pinned, <String>[
        'com.spotify.music',
        'com.google.android.youtube',
      ]);
    });

    test('unpinning in one profile does not affect another profile', () async {
      final store = ProfileStore(storage: MemoryProfileStorage());
      await store.load();
      final spotify = <Map<String, dynamic>>[
        <String, dynamic>{'package': 'com.spotify.music'},
      ];

      expect(await store.autoAddMediaApps(spotify), <String>[
        'com.spotify.music',
      ]);
      final defaultId = store.active.id;
      await store.unpin('com.spotify.music');
      expect(store.isPinned('com.spotify.music'), isFalse);

      final running = await store.createProfile('Running');
      expect(await store.autoAddMediaApps(spotify), <String>[
        'com.spotify.music',
      ]);
      expect(store.isPinned('com.spotify.music'), isTrue);

      await store.setActive(defaultId);
      expect(await store.autoAddMediaApps(spotify), isEmpty);
      expect(store.isPinned('com.spotify.music'), isFalse);

      await store.setActive(running.id);
      expect(store.isPinned('com.spotify.music'), isTrue);
    });

    test(
      'migrates legacy global offered packages into existing profiles',
      () async {
        final storage = MemoryProfileStorage(
          contents: jsonEncode(<String, dynamic>{
            'version': 1,
            'activeProfileId': 'profile-1',
            'profiles': <Map<String, dynamic>>[
              <String, dynamic>{
                'id': 'profile-1',
                'name': 'Default',
                'pinned': <String>[],
              },
              <String, dynamic>{
                'id': 'profile-2',
                'name': 'Running',
                'pinned': <String>[],
              },
            ],
            'autoOfferedPackages': <String>['com.spotify.music'],
            'usedProfileIds': <String>['profile-1', 'profile-2'],
            'nextProfileSerial': 3,
          }),
        );
        final store = ProfileStore(storage: storage);
        final spotify = <Map<String, dynamic>>[
          <String, dynamic>{'package': 'com.spotify.music'},
        ];

        await store.load();
        expect(store.active.autoOffered, <String>['com.spotify.music']);
        expect(await store.autoAddMediaApps(spotify), isEmpty);
        expect(store.isPinned('com.spotify.music'), isFalse);

        await store.setActive('profile-2');
        expect(store.active.autoOffered, <String>['com.spotify.music']);
        expect(await store.autoAddMediaApps(spotify), isEmpty);

        await store.createProfile('Fresh');
        expect(store.active.autoOffered, isEmpty);
        expect(await store.autoAddMediaApps(spotify), <String>[
          'com.spotify.music',
        ]);

        final saved = jsonDecode(storage.contents!) as Map<String, dynamic>;
        expect(saved['version'], 2);
        expect(saved, isNot(contains('autoOfferedPackages')));
        expect(
          (saved['profiles'] as List)
              .cast<Map<String, dynamic>>()
              .last['autoOffered'],
          <String>['com.spotify.music'],
        );
      },
    );
  });
}

class MemoryProfileStorage implements ProfileStorage {
  MemoryProfileStorage({this.contents});

  String? contents;
  final writes = <String>[];
  int failWrites = 0;

  @override
  Future<String?> read() async => contents;

  @override
  Future<void> write(String contents) async {
    writes.add(contents);
    if (failWrites > 0) {
      failWrites--;
      throw const FileSystemException('injected write failure');
    }
    this.contents = contents;
  }
}
