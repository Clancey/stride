import 'dart:typed_data';

import '../bridge.dart';

class LaunchableApp {
  LaunchableApp({
    required this.package,
    required this.label,
    required this.activity,
    required this.leanback,
    required this.hasMediaBrowserService,
    required this.raw,
    this.removable = false,
  });

  factory LaunchableApp.fromMap(Map<String, dynamic> map) {
    return LaunchableApp(
      package: map['package'] as String? ?? '',
      label:
          map['label'] as String? ?? map['package'] as String? ?? 'Unknown app',
      activity: map['activity'] as String?,
      leanback: map['leanback'] == true,
      hasMediaBrowserService: map['hasMediaBrowserService'] == true,
      // Absent means "not removable": an older platform side that does not report
      // this must not make Stride offer a delete the system will reject.
      removable: map['removable'] == true,
      raw: map,
    );
  }

  final String package;
  final String label;
  final String? activity;
  final bool leanback;
  final bool hasMediaBrowserService;

  /// Whether the console will let the rider uninstall this app. System-image apps
  /// and Stride itself are not removable.
  final bool removable;
  final Map<String, dynamic> raw;

  int get mediaScore => mediaLikelihood(raw);

  String get fallbackLetter {
    final source = label.trim().isNotEmpty ? label.trim() : package;
    if (source.isEmpty) return '?';
    return String.fromCharCode(source.runes.first).toUpperCase();
  }
}

class AppIconCache {
  final Map<String, Future<Uint8List?>> _icons = <String, Future<Uint8List?>>{};

  Future<Uint8List?> iconFor(String package, {int sizePx = 192}) {
    return _icons.putIfAbsent(package, () async {
      try {
        return await SpikeBridge.appIcon(package, sizePx: sizePx);
      } catch (_) {
        return null;
      }
    });
  }
}
