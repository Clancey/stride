/// Typed view of the app store snapshot the platform side publishes.
///
/// Deliberately tolerant of missing keys: this is decoded from a method-channel
/// map, and a build of the launcher that outlives its platform half must still
/// render — a console that cannot decode an update list has to keep working as
/// a console.
library;

/// Where one package is in the fetch -> verify -> install pipeline.
///
/// Mirrors `AppstoreState.Stage` on the Kotlin side. Unknown values decode to
/// [AppstoreStage.idle] rather than throwing.
enum AppstoreStage {
  idle,
  downloading,

  /// Downloaded and verified, waiting for a moment when installing is allowed.
  ready,
  installing,

  /// The system's confirmation dialog is up, or about to be.
  awaitingUser,
  installed,
  failed;

  static AppstoreStage parse(Object? raw) {
    final value = (raw as String?)?.toLowerCase();
    return switch (value) {
      'downloading' => AppstoreStage.downloading,
      'ready' => AppstoreStage.ready,
      'installing' => AppstoreStage.installing,
      'awaiting_user' => AppstoreStage.awaitingUser,
      'installed' => AppstoreStage.installed,
      'failed' => AppstoreStage.failed,
      _ => AppstoreStage.idle,
    };
  }

  bool get isBusy =>
      this == AppstoreStage.downloading ||
      this == AppstoreStage.installing ||
      this == AppstoreStage.awaitingUser;
}

/// How a catalog entry relates to what is installed. Mirrors `PlanItem`.
enum AppstoreKind {
  update,
  notInstalled,
  upToDate,
  ineligible;

  static AppstoreKind parse(Object? raw) => switch (raw as String?) {
    'update' => AppstoreKind.update,
    'notInstalled' => AppstoreKind.notInstalled,
    'ineligible' => AppstoreKind.ineligible,
    _ => AppstoreKind.upToDate,
  };
}

/// One row of the updates sheet.
class AppstoreItem {
  const AppstoreItem({
    required this.package,
    required this.name,
    required this.kind,
    required this.isSelf,
    required this.availableVersionName,
    required this.installedVersionName,
    required this.sizeBytes,
    required this.stage,
    required this.bytes,
    required this.totalBytes,
    this.message,
    this.releaseNotesUrl,
    this.ineligibleReason,
  });

  factory AppstoreItem.fromMap(Map<String, dynamic> map) {
    int asInt(Object? value) => value is num ? value.toInt() : 0;
    return AppstoreItem(
      package: map['package'] as String? ?? '',
      name: map['name'] as String? ?? map['package'] as String? ?? '',
      kind: AppstoreKind.parse(map['kind']),
      isSelf: map['isSelf'] == true,
      availableVersionName: map['availableVersionName'] as String? ?? '',
      installedVersionName: map['installedVersionName'] as String?,
      sizeBytes: asInt(map['sizeBytes']),
      stage: AppstoreStage.parse(map['stage']),
      bytes: asInt(map['bytes']),
      totalBytes: asInt(map['totalBytes']),
      message: map['message'] as String?,
      releaseNotesUrl: map['releaseNotesUrl'] as String?,
      ineligibleReason: map['ineligibleReason'] as String?,
    );
  }

  final String package;
  final String name;
  final AppstoreKind kind;

  /// True when this row is Stride upgrading itself. Drawn distinctly, because
  /// installing it restarts the launcher and the overlay that supplies Back and
  /// Home.
  final bool isSelf;
  final String availableVersionName;
  final String? installedVersionName;
  final int sizeBytes;
  final AppstoreStage stage;
  final int bytes;
  final int totalBytes;
  final String? message;
  final String? releaseNotesUrl;
  final String? ineligibleReason;

  bool get isActionable =>
      kind == AppstoreKind.update || kind == AppstoreKind.notInstalled;

  double? get progress {
    if (stage != AppstoreStage.downloading || totalBytes <= 0) return null;
    final fraction = bytes / totalBytes;
    return fraction.clamp(0.0, 1.0);
  }

  /// One line under the title: what is happening, or what would happen.
  String get subtitle {
    if (message != null && message!.isNotEmpty) return message!;
    return switch (stage) {
      AppstoreStage.downloading => 'Downloading...',
      AppstoreStage.ready => 'Ready to install',
      AppstoreStage.installing => 'Installing...',
      AppstoreStage.awaitingUser => 'Waiting for you to confirm',
      AppstoreStage.installed => 'Installed',
      AppstoreStage.failed => 'Failed',
      AppstoreStage.idle => switch (kind) {
        AppstoreKind.update =>
          installedVersionName == null
              ? 'Version $availableVersionName available'
              : '$installedVersionName -> $availableVersionName',
        AppstoreKind.notInstalled => 'Not installed - $availableVersionName',
        AppstoreKind.upToDate => 'Up to date ($availableVersionName)',
        AppstoreKind.ineligible => switch (ineligibleReason) {
          'sdk_too_old' => 'Needs a newer Android than this console runs',
          'abi_mismatch' => 'Not built for this console',
          _ => 'Not available for this console',
        },
      },
    };
  }
}

/// One row of the on-device setup checklist.
class AppstoreSetupStep {
  const AppstoreSetupStep({
    required this.id,
    required this.title,
    required this.detail,
    required this.done,
    this.adb,
    this.action,
  });

  factory AppstoreSetupStep.fromMap(Map<String, dynamic> map) =>
      AppstoreSetupStep(
        id: map['id'] as String? ?? '',
        title: map['title'] as String? ?? '',
        detail: map['detail'] as String? ?? '',
        done: map['done'] == true,
        adb: map['adb'] as String?,
        action: map['action'] as String?,
      );

  final String id;
  final String title;
  final String detail;
  final bool done;

  /// The exact command, for the steps this hardware cannot grant on-device.
  final String? adb;

  /// An in-app action key the launcher can wire to a button, when one exists.
  final String? action;
}

/// The whole snapshot.
class AppstoreStatus {
  const AppstoreStatus({
    required this.checking,
    required this.busy,
    required this.serviceRunning,
    required this.canRequestInstalls,
    required this.workoutIdle,
    required this.mayInstallNow,
    required this.holdReason,
    required this.lastCheckWallMs,
    required this.items,
    this.lastError,
    this.catalogUrl,
  });

  factory AppstoreStatus.fromMap(Map<String, dynamic> map) {
    final rawItems = map['items'];
    final items = rawItems is List
        ? rawItems
              .map((e) => AppstoreItem.fromMap(Map<String, dynamic>.from(e as Map)))
              .toList()
        : <AppstoreItem>[];
    return AppstoreStatus(
      checking: map['checking'] == true,
      busy: map['busy'] == true,
      serviceRunning: map['serviceRunning'] == true,
      canRequestInstalls: map['canRequestInstalls'] == true,
      workoutIdle: map['workoutIdle'] == true,
      mayInstallNow: map['mayInstallNow'] == true,
      holdReason: map['holdReason'] as String? ?? '',
      lastCheckWallMs: (map['lastCheckWallMs'] as num?)?.toInt() ?? 0,
      items: items,
      lastError: map['lastError'] as String?,
      catalogUrl: map['catalogUrl'] as String?,
    );
  }

  static const AppstoreStatus empty = AppstoreStatus(
    checking: false,
    busy: false,
    serviceRunning: false,
    canRequestInstalls: false,
    workoutIdle: true,
    mayInstallNow: false,
    holdReason: '',
    lastCheckWallMs: 0,
    items: <AppstoreItem>[],
  );

  final bool checking;
  final bool busy;
  final bool serviceRunning;
  final bool canRequestInstalls;
  final bool workoutIdle;

  /// False whenever raising an install dialog would be unsafe or impossible.
  /// The launcher disables install buttons on this and says why, rather than
  /// hiding them: a control that vanishes mid-workout looks like a bug.
  final bool mayInstallNow;
  final String holdReason;
  final int lastCheckWallMs;
  final List<AppstoreItem> items;
  final String? lastError;
  final String? catalogUrl;

  /// What the launcher badges. Only genuine updates count — something merely
  /// *offered* by the catalog and never installed is not a pending update and
  /// must not nag like one.
  int get pendingCount =>
      items.where((item) => item.kind == AppstoreKind.update).length;

  /// Stride's own pending upgrade, if any. Always needs an explicit tap.
  AppstoreItem? get selfUpdate {
    for (final item in items) {
      if (item.isSelf && item.kind == AppstoreKind.update) return item;
    }
    return null;
  }

  List<AppstoreItem> get updates => items
      .where((item) => item.kind == AppstoreKind.update && !item.isSelf)
      .toList();

  List<AppstoreItem> get available =>
      items.where((item) => item.kind == AppstoreKind.notInstalled).toList();

  List<AppstoreItem> get rest => items
      .where(
        (item) =>
            item.kind == AppstoreKind.upToDate ||
            item.kind == AppstoreKind.ineligible,
      )
      .toList();
}
