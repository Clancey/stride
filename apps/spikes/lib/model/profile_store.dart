import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

import '../bridge.dart';
import 'profile.dart';

/// Scores at or above this line have either explicit platform media evidence
/// (`MediaBrowserService`) or a curated package-list hit. Name hints and TV UI
/// support are useful ranking signals, but too weak to silently pin by themselves
/// on a treadmill console where the launcher is the recovery surface.
const int _autoAddMediaThreshold = 40;

class ProfileStore extends ChangeNotifier {
  ProfileStore({ProfileStorage? storage})
    : _storage = storage ?? FileProfileStorage() {
    _installDefault();
  }

  final ProfileStorage _storage;

  final List<Profile> _profiles = <Profile>[];
  final Set<String> _usedProfileIds = <String>{};

  String? _activeId;
  bool _loaded = false;
  int _nextProfileSerial = 1;

  List<Profile> get profiles =>
      List<Profile>.unmodifiable(_profiles.map(_cloneProfile));

  Profile get active => _cloneProfile(_activeProfile);

  bool get loaded => _loaded;

  Future<void> load() async {
    final String? contents;
    try {
      contents = await _storage.read();
    } on Object {
      // Treat an unreadable config the same as a missing one. The app can still
      // come up with a safe launcher instead of trapping the user at boot.
      _installDefault();
      _loaded = true;
      notifyListeners();
      return;
    }

    if (contents == null || contents.trim().isEmpty) {
      _installDefault();
      _loaded = true;
      notifyListeners();
      return;
    }

    try {
      final decoded = jsonDecode(contents);
      if (decoded is! Map<String, dynamic>) {
        throw const FormatException('profile store root must be an object');
      }
      _loadFromDocument(decoded);
    } on Object {
      // Boot must survive a torn or hand-edited file. We do not attempt clever
      // partial recovery from invalid JSON; a launcher crash-loop is worse than
      // falling back to one safe profile until the user edits settings again.
      _installDefault();
    }

    _loaded = true;
    notifyListeners();
  }

  Future<void> setActive(String id) async {
    _requireProfile(id);
    if (_activeId == id) return;
    await _commit(() {
      _activeId = id;
      return true;
    });
  }

  Future<Profile> createProfile(String name) async {
    late Profile created;
    await _commit(() {
      created = Profile(
        id: _newProfileId(),
        name: _normalizeName(name),
        pinned: const <String>[],
      );
      _profiles.add(created);
      _activeId = created.id;
      return true;
    });
    return _cloneProfile(created);
  }

  Future<void> renameProfile(String id, String name) async {
    final index = _requireProfile(id);
    final normalized = _normalizeName(name);
    if (_profiles[index].name == normalized) return;
    await _commit(() {
      _profiles[index] = _profiles[index].copyWith(name: normalized);
      return true;
    });
  }

  Future<void> deleteProfile(String id) async {
    if (_profiles.length == 1) {
      throw StateError('cannot delete the last profile');
    }
    final index = _requireProfile(id);
    await _commit(() {
      final deletingActive = _activeId == id;
      _profiles.removeAt(index);
      if (deletingActive) {
        // Prefer the next visible row; if the deleted profile was last, fall
        // back to the previous row. That matches what a settings list leaves in
        // front of the user and is deterministic across runs.
        final replacement = index < _profiles.length
            ? index
            : _profiles.length - 1;
        _activeId = _profiles[replacement].id;
      }
      return true;
    });
  }

  bool isPinned(String package) => _activeProfile.pinned.contains(package);

  Future<void> pin(String package) async {
    final normalized = _normalizePackage(package);
    if (isPinned(normalized)) return;
    await _commit(() {
      final pinned = List<String>.of(_activeProfile.pinned)..add(normalized);
      _replaceActive(pinned: pinned);
      return true;
    });
  }

  Future<void> unpin(String package) async {
    final normalized = _normalizePackage(package);
    if (!isPinned(normalized)) return;
    await _commit(() {
      final pinned = List<String>.of(_activeProfile.pinned)..remove(normalized);
      _replaceActive(pinned: pinned);
      return true;
    });
  }

  Future<void> reorderPinned(int oldIndex, int newIndex) async {
    final pinned = List<String>.of(_activeProfile.pinned);
    RangeError.checkValidIndex(oldIndex, pinned, 'oldIndex');
    if (newIndex < 0 || newIndex > pinned.length) {
      throw RangeError.range(newIndex, 0, pinned.length, 'newIndex');
    }

    var target = newIndex;
    if (oldIndex < target) target -= 1;
    if (oldIndex == target) return;

    await _commit(() {
      final reordered = List<String>.of(_activeProfile.pinned);
      final moved = reordered.removeAt(oldIndex);
      reordered.insert(target, moved);
      _replaceActive(pinned: reordered);
      return true;
    });
  }

  Future<List<String>> autoAddMediaApps(List<Map<String, dynamic>> apps) async {
    final added = <String>[];
    final seenThisScan = <String>{};

    await _commit(() {
      var changed = false;
      final active = _activeProfile;
      final pinned = List<String>.of(active.pinned);
      final autoOffered = Set<String>.of(active.autoOffered);

      for (final app in apps) {
        final package = _packageFromApp(app);
        if (package == null || !seenThisScan.add(package)) continue;

        final scoredApp = package == app['package']
            ? app
            : <String, dynamic>{...app, 'package': package};
        if (mediaLikelihood(scoredApp) < _autoAddMediaThreshold) continue;

        final alreadyOffered = autoOffered.contains(package);
        if (!alreadyOffered) {
          // Offered-ness is profile-scoped. An unpin is a real user decision for
          // this profile, but it must not starve another profile created later.
          autoOffered.add(package);
          changed = true;
        }

        if (alreadyOffered || pinned.contains(package)) continue;
        pinned.add(package);
        added.add(package);
        changed = true;
      }

      if (changed) {
        _replaceActive(pinned: pinned, autoOffered: autoOffered.toList());
      }
      return changed;
    });

    return List<String>.unmodifiable(added);
  }

  Profile get _activeProfile {
    if (_profiles.isEmpty) _installDefault();
    final activeIndex = _profiles.indexWhere((p) => p.id == _activeId);
    return activeIndex == -1 ? _profiles.first : _profiles[activeIndex];
  }

  void _installDefault() {
    _profiles.clear();
    _usedProfileIds.clear();
    _nextProfileSerial = 1;
    final profile = Profile(
      id: _newProfileId(),
      name: 'Default',
      pinned: const <String>[],
    );
    _profiles.add(profile);
    _activeId = profile.id;
  }

  void _loadFromDocument(Map<String, dynamic> json) {
    final rawProfiles = json['profiles'];
    if (rawProfiles is! List) {
      throw const FormatException('profiles must be a list');
    }

    final parsed = <Profile>[];
    final seenProfileIds = <String>{};
    final legacyAutoOffered = _stringSet(json['autoOfferedPackages']);
    for (final raw in rawProfiles) {
      if (raw is! Map) continue;
      try {
        final profileJson = Map<String, dynamic>.from(raw);
        final profile = Profile.fromJson(profileJson);
        if (!seenProfileIds.add(profile.id)) continue;
        final autoOffered = profileJson.containsKey('autoOffered')
            ? profile.autoOffered
            : legacyAutoOffered;
        parsed.add(
          profile.copyWith(
            pinned: _dedupePackages(profile.pinned),
            autoOffered: _dedupePackages(autoOffered),
          ),
        );
      } on Object {
        // Salvage the rest of the file when one profile entry is bad. A single
        // corrupt row should not make every other user's launcher disappear.
      }
    }

    if (parsed.isEmpty) {
      _installDefault();
      return;
    }

    _profiles
      ..clear()
      ..addAll(parsed);

    _usedProfileIds
      ..clear()
      ..addAll(_stringSet(json['usedProfileIds']))
      ..addAll(_profiles.map((p) => p.id));

    _nextProfileSerial = _positiveInt(json['nextProfileSerial']) ?? 1;
    final maxSeenSerial = _usedProfileIds
        .map(_profileSerial)
        .whereType<int>()
        .fold<int>(0, (max, serial) => serial > max ? serial : max);
    if (_nextProfileSerial <= maxSeenSerial) {
      _nextProfileSerial = maxSeenSerial + 1;
    }

    final activeId = json['activeProfileId'];
    _activeId = activeId is String && _profiles.any((p) => p.id == activeId)
        ? activeId
        : _profiles.first.id;
  }

  Future<bool> _commit(bool Function() change) async {
    final before = _snapshot();
    final changed = change();
    if (!changed) return false;
    try {
      await _persist();
    } on Object {
      _restore(before);
      rethrow;
    }
    notifyListeners();
    return true;
  }

  Future<void> _persist() => _storage.write(
    const JsonEncoder.withIndent('  ').convert(<String, dynamic>{
      'version': 2,
      'activeProfileId': _activeId,
      'profiles': _profiles.map((p) => p.toJson()).toList(),
      'usedProfileIds': _sorted(_usedProfileIds),
      'nextProfileSerial': _nextProfileSerial,
    }),
  );

  _ProfileStoreSnapshot _snapshot() => _ProfileStoreSnapshot(
    profiles: _profiles.map(_cloneProfile).toList(),
    activeId: _activeId,
    usedProfileIds: Set<String>.of(_usedProfileIds),
    nextProfileSerial: _nextProfileSerial,
    loaded: _loaded,
  );

  void _restore(_ProfileStoreSnapshot snapshot) {
    _profiles
      ..clear()
      ..addAll(snapshot.profiles.map(_cloneProfile));
    _activeId = snapshot.activeId;
    _usedProfileIds
      ..clear()
      ..addAll(snapshot.usedProfileIds);
    _nextProfileSerial = snapshot.nextProfileSerial;
    _loaded = snapshot.loaded;
  }

  int _requireProfile(String id) {
    final index = _profiles.indexWhere((p) => p.id == id);
    if (index == -1) throw ArgumentError.value(id, 'id', 'unknown profile');
    return index;
  }

  void _replaceActive({List<String>? pinned, List<String>? autoOffered}) {
    final index = _requireProfile(_activeProfile.id);
    _profiles[index] = _profiles[index].copyWith(
      pinned: pinned == null ? null : _dedupePackages(pinned),
      autoOffered: autoOffered == null ? null : _dedupePackages(autoOffered),
    );
  }

  String _newProfileId() {
    String id;
    do {
      id = 'profile-${_nextProfileSerial++}';
    } while (_usedProfileIds.contains(id));
    _usedProfileIds.add(id);
    return id;
  }

  static Profile _cloneProfile(Profile profile) => profile.copyWith();

  static String _normalizeName(String name) {
    final trimmed = name.trim();
    return trimmed.isEmpty ? 'Profile' : trimmed;
  }

  static String _normalizePackage(String package) {
    final trimmed = package.trim();
    if (trimmed.isEmpty) {
      throw ArgumentError.value(
        package,
        'package',
        'package must be non-empty',
      );
    }
    return trimmed;
  }

  static String? _packageFromApp(Map<String, dynamic> app) {
    final package = app['package'];
    if (package is! String) return null;
    final trimmed = package.trim();
    return trimmed.isEmpty ? null : trimmed;
  }

  static List<String> _dedupePackages(Iterable<String> packages) {
    final seen = <String>{};
    final out = <String>[];
    for (final package in packages) {
      final normalized = _normalizePackage(package);
      if (seen.add(normalized)) out.add(normalized);
    }
    return out;
  }

  static Set<String> _stringSet(Object? value) {
    if (value is! Iterable) return <String>{};
    return value
        .whereType<String>()
        .map((s) => s.trim())
        .where((s) => s.isNotEmpty)
        .toSet();
  }

  static int? _positiveInt(Object? value) =>
      value is int && value > 0 ? value : null;

  static int? _profileSerial(String id) {
    const prefix = 'profile-';
    if (!id.startsWith(prefix)) return null;
    return int.tryParse(id.substring(prefix.length));
  }

  static List<String> _sorted(Set<String> values) => values.toList()..sort();
}

abstract class ProfileStorage {
  Future<String?> read();
  Future<void> write(String contents);
}

class FileProfileStorage implements ProfileStorage {
  FileProfileStorage({this.fileName = 'profiles.json'});

  final String fileName;

  @override
  Future<String?> read() async {
    try {
      final file = await _file();
      if (!await file.exists()) return null;
      return file.readAsString();
    } on Object {
      return null;
    }
  }

  @override
  Future<void> write(String contents) async {
    final file = await _file();
    await file.parent.create(recursive: true);
    final temp = File('${file.path}.tmp');
    await temp.writeAsString(contents, flush: true);

    // The temporary file lives beside the destination so POSIX rename is atomic
    // on Android's app-private filesystem; a power cut can leave either the old
    // complete file or the new complete file, but not a torn JSON document.
    await temp.rename(file.path);
  }

  Future<File> _file() async {
    final directory = await getApplicationDocumentsDirectory();
    final separator = Platform.pathSeparator;
    final base = directory.path.endsWith(separator)
        ? directory.path
        : '${directory.path}$separator';
    return File('$base$fileName');
  }
}

class _ProfileStoreSnapshot {
  const _ProfileStoreSnapshot({
    required this.profiles,
    required this.activeId,
    required this.usedProfileIds,
    required this.nextProfileSerial,
    required this.loaded,
  });

  final List<Profile> profiles;
  final String? activeId;
  final Set<String> usedProfileIds;
  final int nextProfileSerial;
  final bool loaded;
}
