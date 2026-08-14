class Profile {
  const Profile({
    required this.id,
    required this.name,
    required this.pinned,
    this.autoOffered = const <String>[],
  });

  /// Stable profile identity. Display names can change; ids cannot.
  final String id;
  final String name;

  /// Android package names in launcher display order.
  final List<String> pinned;

  /// Media packages this profile has already seen through auto-add.
  ///
  /// This is intentionally profile-scoped: unpinning Spotify from one profile
  /// must not starve a later-created profile whose user never made that choice.
  final List<String> autoOffered;

  Profile copyWith({
    String? name,
    List<String>? pinned,
    List<String>? autoOffered,
  }) => Profile(
    id: id,
    name: name ?? this.name,
    pinned: List<String>.unmodifiable(pinned ?? this.pinned),
    autoOffered: List<String>.unmodifiable(autoOffered ?? this.autoOffered),
  );

  Map<String, dynamic> toJson() => <String, dynamic>{
    'id': id,
    'name': name,
    'pinned': List<String>.of(pinned),
    'autoOffered': List<String>.of(autoOffered),
  };

  static Profile fromJson(Map<String, dynamic> json) {
    final id = json['id'];
    final name = json['name'];
    final pinned = json['pinned'];
    final autoOffered = json['autoOffered'];
    if (id is! String || id.trim().isEmpty) {
      throw const FormatException('profile id must be a non-empty string');
    }
    if (name is! String) {
      throw const FormatException('profile name must be a string');
    }
    if (pinned is! List) {
      throw const FormatException('profile pinned apps must be a list');
    }
    if (autoOffered != null && autoOffered is! List) {
      throw const FormatException('profile auto-offered apps must be a list');
    }
    return Profile(
      id: id,
      name: name,
      pinned: List<String>.unmodifiable(_dedupeStrings(pinned)),
      autoOffered: List<String>.unmodifiable(
        _dedupeStrings(autoOffered is List ? autoOffered : const <Object?>[]),
      ),
    );
  }

  static List<String> _dedupeStrings(Iterable<Object?> values) {
    final seen = <String>{};
    final out = <String>[];
    for (final value in values) {
      if (value is! String) continue;
      final package = value.trim();
      if (package.isEmpty || !seen.add(package)) continue;
      out.add(package);
    }
    return out;
  }
}
