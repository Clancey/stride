/// Thin typed wrapper over the `io.stride.spikes/bridge` MethodChannel.
library;

import 'package:flutter/services.dart';

class SpikeBridge {
  static const MethodChannel _channel = MethodChannel('io.stride.spikes/bridge');

  static Future<Map<String, dynamic>> environment() => _map('environment');

  // S1 - launcher
  static Future<bool> isDefaultHome() => _bool('isDefaultHome');
  static Future<List<Map<String, dynamic>>> homeCandidates() => _list('homeCandidates');
  static Future<bool> openHomeSettings() => _bool('openHomeSettings');
  static Future<bool> goHome() => _bool('goHome');

  // S3 - overlay + edge gestures
  static Future<bool> canDrawOverlays() => _bool('canDrawOverlays');
  static Future<bool> startOverlay() => _bool('startOverlay');
  static Future<bool> stopOverlay() => _bool('stopOverlay');
  static Future<Map<String, dynamic>> overlayStatus() => _map('overlayStatus');

  // S4 - app inventory
  static Future<List<Map<String, dynamic>>> listApps() => _list('listApps');
  static Future<bool> launchApp(String package) =>
      _bool('launchApp', {'package': package});

  // S5 - media sessions
  static Future<bool> notificationListenerEnabled() => _bool('notificationListenerEnabled');
  static Future<List<Map<String, dynamic>>> mediaSessions() => _list('mediaSessions');
  static Future<List<String>> pauseAllPlaying() => _strings('pauseAllPlaying');
  static Future<List<String>> resumePausedByUs() => _strings('resumePausedByUs');
  static Future<bool> dispatchMediaKey(int keyCode) =>
      _bool('dispatchMediaKey', {'keyCode': keyCode});

  // S10 - navigation
  static Future<bool> accessibilityConnected() => _bool('accessibilityConnected');
  static Future<bool> accessibilityEnabledInSettings() =>
      _bool('accessibilityEnabledInSettings');
  static Future<bool> goBack() => _bool('goBack');
  static Future<bool> goRecents() => _bool('goRecents');
  static Future<String?> foregroundPackage() async =>
      await _channel.invokeMethod<String>('foregroundPackage');

  // S2 - iFit APK location
  static Future<Map<String, dynamic>> ifitApkPaths() => _map('ifitApkPaths');

  // ---------------------------------------------------------------- helpers

  static Future<bool> _bool(String method, [Map<String, dynamic>? args]) async =>
      await _channel.invokeMethod<bool>(method, args) ?? false;

  static Future<Map<String, dynamic>> _map(String method) async {
    final result = await _channel.invokeMapMethod<String, dynamic>(method);
    return result ?? <String, dynamic>{};
  }

  static Future<List<Map<String, dynamic>>> _list(String method) async {
    final result = await _channel.invokeListMethod<dynamic>(method);
    return (result ?? const [])
        .map((e) => Map<String, dynamic>.from(e as Map))
        .toList();
  }

  static Future<List<String>> _strings(String method) async {
    final result = await _channel.invokeListMethod<String>(method);
    return result ?? const [];
  }
}

/// Heuristic media-likelihood score for an installed app.
///
/// Plan section 3.6: this is *ranking*, not classification. Nothing is auto-pinned on the strength
/// of a score, and every launchable app stays pinnable regardless of what this returns.
int mediaLikelihood(Map<String, dynamic> app) {
  var score = 0;
  if (app['hasMediaBrowserService'] == true) score += 50;
  if (app['leanback'] == true) score += 20;

  const knownMedia = <String>{
    'com.spotify.music',
    'com.netflix.mediaclient',
    'com.netflix.ninja',
    'com.google.android.youtube',
    'com.google.android.youtube.tv',
    'com.plexapp.android',
    'com.amazon.avod.thirdpartyclient',
    'tv.twitch.android.app',
    'com.pandora.android',
    'com.soundcloud.android',
    'deezer.android.app',
    'com.apple.android.music',
    'org.jellyfin.mobile',
    'org.videolan.vlc',
    'com.audible.application',
    'com.hulu.plus',
    'com.disney.disneyplus',
  };
  if (knownMedia.contains(app['package'])) score += 40;

  final pkg = (app['package'] as String? ?? '').toLowerCase();
  for (final hint in const ['music', 'video', 'audio', 'radio', 'player', 'podcast', 'tv']) {
    if (pkg.contains(hint)) {
      score += 10;
      break;
    }
  }
  return score;
}
