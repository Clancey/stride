/// Thin typed wrapper over the `io.stride.spikes/bridge` MethodChannel.
library;

import 'dart:math' as math;

import 'package:flutter/painting.dart' show EdgeInsets;
import 'package:flutter/services.dart';

class SpikeBridge {
  static const MethodChannel _channel = MethodChannel(
    'io.stride.spikes/bridge',
  );

  /// Callbacks the *host* pushes to us, rather than us polling it.
  static void Function()? onHomePressed;
  static void Function(String state)? onWorkoutStateChanged;

  /// Wire the host->Flutter direction. Call once at startup.
  ///
  /// The HOME button is the reason this exists. MainActivity is `singleTask`, so pressing Home
  /// delivers `onNewIntent` to the already-running activity instead of recreating it. Without this,
  /// Flutter keeps whatever route it was on and Home appears completely dead - which it was.
  static void install() {
    _channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onHomePressed':
          onHomePressed?.call();
          return null;
        case 'onWorkoutStateChanged':
          final state = call.arguments;
          if (state is String) onWorkoutStateChanged?.call(state);
          return null;
        default:
          return null;
      }
    });
  }

  static Future<Map<String, dynamic>> environment() => _map('environment');

  /// The console's GSF device id plus whether Play Services is present.
  ///
  /// A sideloaded Play Store reports "device isn't Play Protect certified" until this id is
  /// registered at the returned `registrationUrl`. Paste `gsfAndroidId` (decimal) there, never
  /// `gsfAndroidIdHex`. See docs/APPSTORE.md §11.5.
  static Future<Map<String, dynamic>> playCertification() =>
      _map('playCertification');

  // S1 - launcher
  static Future<bool> isDefaultHome() => _bool('isDefaultHome');
  static Future<List<Map<String, dynamic>>> homeCandidates() =>
      _list('homeCandidates');
  static Future<bool> openHomeSettings() => _bool('openHomeSettings');
  static Future<bool> goHome() => _bool('goHome');

  // S3 - overlay + edge gestures
  static Future<bool> canDrawOverlays() => _bool('canDrawOverlays');
  static Future<bool> startOverlay() => _bool('startOverlay');
  static Future<bool> stopOverlay() => _bool('stopOverlay');
  static Future<Map<String, dynamic>> overlayStatus() => _map('overlayStatus');
  static Future<bool> resetOverlayCounters() => _bool('resetOverlayCounters');

  // Workout + console controls
  static Future<String> workoutState() async =>
      await _channel.invokeMethod<String>('workoutState') ?? 'idle';
  static Future<int> workoutElapsedMs() => _int('workoutElapsedMs');
  static Future<bool> workoutStart() => _bool('workoutStart');
  static Future<bool> workoutPause() => _bool('workoutPause');
  static Future<bool> workoutResume() => _bool('workoutResume');
  static Future<int> workoutStop() => _int('workoutStop');
  static Future<bool> workoutCancelStart() => _bool('workoutCancelStart');
  static Future<Map<String, dynamic>> volumeGet() => _map('volumeGet');
  static Future<bool> volumeSet(int level) =>
      _bool('volumeSet', {'level': level});
  static Future<Map<String, dynamic>> machineSnapshot() =>
      _map('machineSnapshot');

  /// Persist the rider's chosen goal. [kind] is `'none'`, `'distance'`, or
  /// `'time'`; [target] is miles or seconds respectively.
  ///
  /// Degrades to `false` when the platform side is missing so the goal picker
  /// can still hand off to Stride's timer in environments without the bridge.
  static Future<bool> goalSet({required String kind, required double target}) =>
      _boolSafe('goalSet', {'kind': kind, 'target': target});

  /// Current goal plus its live progress, or an empty-but-valid map when the
  /// platform side is unavailable. Callers must tolerate missing keys.
  static Future<Map<String, dynamic>> goalGet() => _mapSafe('goalGet');

  /// Height in **physical pixels** of the always-on HUD strip along the top edge.
  ///
  /// The HUD is a separate system window drawn over everything, so Flutter has no idea it is there
  /// and happily renders its app bar underneath it. Content must be inset by this much.
  static Future<double> hudHeightPx() async =>
      (await _channel.invokeMethod<num>('hudHeightPx'))?.toDouble() ?? 0;

  /// Every edge the overlay occupies, in **physical pixels**, as one consistent snapshot.
  ///
  /// The side rails cover the launcher's app grid just as surely as the top strip covers its app
  /// bar, so all four edges have to be inset. Fetched in a single call because four separate calls
  /// could straddle an overlay rebuild and return a mix of old and new geometry.
  static Future<EdgeInsets> hudInsetsPx() async {
    final raw = await _channel.invokeMapMethod<String, num>('hudInsetsPx');
    if (raw == null) return EdgeInsets.zero;
    double at(String key) => math.max(0, (raw[key] ?? 0).toDouble());
    return EdgeInsets.fromLTRB(
      at('left'),
      at('top'),
      at('right'),
      at('bottom'),
    );
  }

  // S4 - app inventory
  static Future<List<Map<String, dynamic>>> listApps() => _list('listApps');
  static Future<bool> launchApp(String package) =>
      _bool('launchApp', {'package': package});

  /// Hands [package] to the system uninstaller.
  ///
  /// Returns false when the platform refuses outright — a system-image app, or a console with no
  /// uninstaller activity — so the caller can say so instead of leaving the rider waiting for a
  /// dialog that will never appear. True only means the system dialog was raised: the rider still
  /// confirms there, and the app list has to be re-read to learn what actually happened.
  static Future<bool> uninstallApp(String package) =>
      _bool('uninstallApp', {'package': package});

  /// PNG bytes for an app's launcher icon, or null if it cannot be rendered.
  ///
  /// Kept out of [listApps] deliberately: icons are large, and inlining ~40 of them turns a cheap
  /// listing into a multi-megabyte channel payload that janks the first frame.
  static Future<Uint8List?> appIcon(String package, {int sizePx = 192}) =>
      _channel.invokeMethod<Uint8List>('appIcon', {
        'package': package,
        'sizePx': sizePx,
      });

  // S5 - media sessions
  static Future<bool> notificationListenerEnabled() =>
      _bool('notificationListenerEnabled');
  static Future<List<Map<String, dynamic>>> mediaSessions() =>
      _list('mediaSessions');
  static Future<List<String>> pauseAllPlaying() => _strings('pauseAllPlaying');
  static Future<List<String>> resumePausedByUs() =>
      _strings('resumePausedByUs');
  static Future<bool> dispatchMediaKey(int keyCode) =>
      _bool('dispatchMediaKey', {'keyCode': keyCode});

  // S10 - navigation
  static Future<bool> accessibilityConnected() =>
      _bool('accessibilityConnected');
  static Future<bool> accessibilityEnabledInSettings() =>
      _bool('accessibilityEnabledInSettings');
  static Future<bool> goBack() => _bool('goBack');
  static Future<bool> goRecents() => _bool('goRecents');
  static Future<String?> foregroundPackage() async =>
      await _channel.invokeMethod<String>('foregroundPackage');

  // Settings + system grants
  static Future<Map<String, dynamic>> settingsGet() => _map('settingsGet');
  static Future<bool> transportSet(String transport) =>
      _bool('transportSet', <String, dynamic>{'transport': transport});
  static Future<bool> heartRateStrapSet(bool enabled) =>
      _bool('heartRateStrapSet', <String, dynamic>{'enabled': enabled});

  /// How the incline quick-pick column is spaced: `'fine'` (every 1%, the
  /// default) or `'coarse'` (5% climbing, 3% declining).
  ///
  /// The key is `spacing`, and the platform refuses the call if it is missing
  /// rather than falling back to the default — the same guard `trackFloorSet`
  /// needed after reading a key Dart never sent, which made every press look
  /// like it worked and store nothing.
  static Future<bool> inclineSpacingSet(String spacing) =>
      _bool('inclineSpacingSet', <String, dynamic>{'spacing': spacing});
  static Future<bool> usbPermissionRequest() => _bool('usbPermissionRequest');
  static Future<List<Map<String, dynamic>>> grantsGet() => _list('grantsGet');
  static Future<bool> grantOpenSettings(String id) =>
      _bool('grantOpenSettings', <String, dynamic>{'id': id});
  static Future<bool> openSystemSettings() => _bool('openSystemSettings');
  static Future<Map<String, dynamic>> trackFloorGet() => _map('trackFloorGet');
  static Future<bool> trackFloorSet(bool? chosen) =>
      _bool('trackFloorSet', <String, dynamic>{'chosen': chosen});

  /// Whether the launcher stands down to a plain backdrop while the track floor
  /// is on screen. Two-state: there is no "automatic", because the launcher is
  /// only ever behind the track once the rider has asked for the track.
  static Future<bool> trackBackdropSet(bool blank) =>
      _bool('trackBackdropSet', <String, dynamic>{'blank': blank});

  // App store - catalog, updates, installs
  //
  // Every one of these is a *Safe variant: the app store is the one surface that must never take
  // the launcher down with it. A console that cannot reach its catalog still has to boot, run a
  // workout, and launch Spotify.
  static Future<Map<String, dynamic>> appstoreStatus() =>
      _mapSafe('appstoreStatus');
  static Future<bool> appstoreCheckNow() => _boolSafe('appstoreCheckNow');
  static Future<bool> appstoreInstall(String package) =>
      _boolSafe('appstoreInstall', {'package': package});
  static Future<bool> appstoreCancel(String package) =>
      _boolSafe('appstoreCancel', {'package': package});

  /// Install every member of a bundle in the catalog's order, from one tap.
  /// Each member still raises its own system confirmation.
  static Future<bool> appstoreInstallBundle(String bundle) =>
      _boolSafe('appstoreInstallBundle', {'bundle': bundle});

  /// Dismiss a finished or failed bundle run so its message stops occupying the sheet.
  static Future<bool> appstoreClearBundle() => _boolSafe('appstoreClearBundle');
  static Future<List<Map<String, dynamic>>> appstoreSetupChecklist() =>
      _listSafe('appstoreSetupChecklist');
  static Future<bool> appstoreCanRequestInstalls() =>
      _boolSafe('appstoreCanRequestInstalls');
  static Future<bool> appstoreOpenInstallPermission() =>
      _boolSafe('appstoreOpenInstallPermission');

  // ---------------------------------------------------------------- helpers

  static Future<bool> _bool(
    String method, [
    Map<String, dynamic>? args,
  ]) async => await _channel.invokeMethod<bool>(method, args) ?? false;

  /// Like [_bool] but never lets a missing or throwing platform method reach the
  /// UI — an unset goal channel simply reads as `false`.
  static Future<bool> _boolSafe(
    String method, [
    Map<String, dynamic>? args,
  ]) async {
    try {
      return await _bool(method, args);
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    } on Object {
      return false;
    }
  }

  static Future<int> _int(String method) async =>
      (await _channel.invokeMethod<num>(method))?.round() ?? 0;

  static Future<Map<String, dynamic>> _map(String method) async {
    final result = await _channel.invokeMapMethod<String, dynamic>(method);
    return result ?? <String, dynamic>{};
  }

  /// Like [_map] but degrades to an empty map when the platform side is missing
  /// or throws, so goal readback can never crash the launcher.
  static Future<Map<String, dynamic>> _mapSafe(String method) async {
    try {
      return await _map(method);
    } on MissingPluginException {
      return <String, dynamic>{};
    } on PlatformException {
      return <String, dynamic>{};
    } on Object {
      return <String, dynamic>{};
    }
  }

  static Future<List<Map<String, dynamic>>> _list(String method) async {
    final result = await _channel.invokeListMethod<dynamic>(method);
    return (result ?? const [])
        .map((e) => Map<String, dynamic>.from(e as Map))
        .toList();
  }

  /// Like [_list] but degrades to an empty list rather than letting a missing
  /// platform method reach the UI.
  static Future<List<Map<String, dynamic>>> _listSafe(String method) async {
    try {
      return await _list(method);
    } on MissingPluginException {
      return const [];
    } on PlatformException {
      return const [];
    } on Object {
      return const [];
    }
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
  for (final hint in const [
    'music',
    'video',
    'audio',
    'radio',
    'player',
    'podcast',
    'tv',
  ]) {
    if (pkg.contains(hint)) {
      score += 10;
      break;
    }
  }
  return score;
}
