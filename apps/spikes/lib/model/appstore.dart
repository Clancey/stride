/// Typed view of the app store snapshot the platform side publishes.
///
/// Deliberately tolerant of missing keys: this is decoded from a method-channel
/// map, and a build of the launcher that outlives its platform half must still
/// render — a console that cannot decode an update list has to keep working as
/// a console.
library;

/// Whether the platform has answered the launch-time catalog question yet.
enum AppstoreInitialization {
  notStarted,
  loading,
  ready,
  failed;

  static AppstoreInitialization parse(Object? raw) => switch (raw as String?) {
    'not_started' => AppstoreInitialization.notStarted,
    'loading' => AppstoreInitialization.loading,
    'ready' => AppstoreInitialization.ready,
    'failed' => AppstoreInitialization.failed,
    _ => AppstoreInitialization.notStarted,
  };
}

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

  /// Work is in flight and pressing anything would be pointless.
  ///
  /// Deliberately excludes [awaitingUser]: the system dialog can be dismissed, or buried by a
  /// second one, without the platform ever telling us. Treating that as busy leaves the row
  /// disabled forever, so the one app the rider asked for becomes the one they cannot install.
  /// It stays pressable, and pressing it raises the prompt again.
  bool get isBusy =>
      this == AppstoreStage.downloading || this == AppstoreStage.installing;

  /// A prompt is up, or was raised and never answered. Pressing re-raises it.
  bool get needsConfirm => this == AppstoreStage.awaitingUser;
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

/// What changed in one released version, as plain text.
///
/// Plain text is the contract, not a simplification: this is rendered in a dialog
/// on a television by a device with no browser, so markdown would show its own
/// punctuation and a link would go nowhere. `tools/changelog.sh` flattens the
/// markdown before it is ever written into the catalog.
class ReleaseNote {
  const ReleaseNote({
    required this.versionName,
    required this.notes,
    this.date,
  });

  factory ReleaseNote.fromMap(Map<String, dynamic> map) => ReleaseNote(
    versionName: map['versionName'] as String? ?? '',
    notes: map['notes'] as String? ?? '',
    date: map['date'] as String?,
  );

  final String versionName;
  final String notes;
  final String? date;
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
    this.releaseNotes = const [],
    this.ineligibleReason,
    this.iconUrl,
    this.bundleId,
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
      releaseNotes: [
        for (final note in (map['releaseNotes'] as List<Object?>? ?? const []))
          if (note is Map) ReleaseNote.fromMap(note.cast<String, dynamic>()),
      ],
      ineligibleReason: map['ineligibleReason'] as String?,
      iconUrl: map['iconUrl'] as String?,
      bundleId: map['bundleId'] as String?,
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

  /// What changed, per version, newest first — and only the versions this console
  /// has not already run. The native side does that filtering, because it is the
  /// side that knows what is installed.
  ///
  /// Usually one entry. More than one means the console skipped releases, which
  /// happens whenever it has been off for a while.
  final List<ReleaseNote> releaseNotes;
  final String? ineligibleReason;

  /// Where to fetch an icon for an app that is not on the device yet. Null is ordinary and just
  /// means a letter tile; see [isInstalled] for the case where the device has the real one.
  final String? iconUrl;

  /// Set when this package is only installable as one step of a bundle.
  ///
  /// Such rows are hidden from every list. "Google Services Framework Login" is not something a
  /// rider asked for, and offering it on its own is offering a way to end up with half of Google
  /// Play - which is worse than none, because Play Store without Play Services is an icon that
  /// opens onto a sign-in loop. The bundle is offered instead, as one row.
  final String? bundleId;

  /// True when the device already has this app, so its real launcher icon can be read locally
  /// instead of fetched. Everything except [AppstoreKind.notInstalled] is by definition installed.
  bool get isInstalled => kind != AppstoreKind.notInstalled;

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
          'needs_gms' => 'Needs Google Play, which is not installed yet',
          _ => 'Not available for this console',
        },
      },
    };
  }
}

/// How much of a multi-package bundle is on the device. Mirrors `BundleState`.
enum AppstoreBundleState {
  /// None of it. Offered as a single install.
  missing,

  /// Some of it. Offered as "finish installing" - a half-installed bundle is the
  /// state most in need of the button, and the one a rider reaches by cancelling
  /// a confirmation partway through.
  partial,

  /// All of it. The row disappears.
  installed;

  static AppstoreBundleState parse(Object? raw) => switch (raw as String?) {
    'partial' => AppstoreBundleState.partial,
    'installed' => AppstoreBundleState.installed,
    _ => AppstoreBundleState.missing,
  };
}

/// An ordered group of packages that are only useful together.
///
/// Google Play is the reason this exists: restoring it on an AOSP console is four
/// installs in a specific order, one of which is a split. The rider should press
/// one button and not have to know any of that.
class AppstoreBundle {
  const AppstoreBundle({
    required this.id,
    required this.name,
    required this.state,
    required this.installedCount,
    required this.totalCount,
    required this.sizeBytes,
    required this.restartRequired,
    required this.running,
    required this.failed,
    required this.restartPending,
    this.detail,
    this.message,
    this.iconUrl,
  });

  factory AppstoreBundle.fromMap(Map<String, dynamic> map) {
    int asInt(Object? v) => (v as num?)?.toInt() ?? 0;
    return AppstoreBundle(
      id: map['id'] as String? ?? '',
      name: map['name'] as String? ?? '',
      detail: map['detail'] as String?,
      iconUrl: map['iconUrl'] as String?,
      state: AppstoreBundleState.parse(map['state']),
      installedCount: asInt(map['installedCount']),
      totalCount: asInt(map['totalCount']),
      sizeBytes: asInt(map['sizeBytes']),
      restartRequired: map['restartRequired'] == true,
      running: map['running'] == true,
      failed: map['failed'] == true,
      restartPending: map['restartPending'] == true,
      message: map['message'] as String?,
    );
  }

  final String id;
  final String name;
  final String? detail;
  final String? iconUrl;
  final AppstoreBundleState state;
  final int installedCount;
  final int totalCount;
  final int sizeBytes;

  /// True when a reboot is needed before the bundle actually works. Stride cannot
  /// reboot an unrooted console, so all it can do is say so.
  final bool restartRequired;
  final bool running;
  final bool failed;

  /// Every member landed and the restart is the only thing left.
  final bool restartPending;
  final String? message;

  /// Nothing left to offer.
  bool get isComplete => state == AppstoreBundleState.installed;

  /// What the button says. "Finish installing" is deliberately different from
  /// "Install": it tells a rider who cancelled a prompt that they have not lost
  /// the work already done.
  String get actionLabel =>
      state == AppstoreBundleState.partial ? 'Finish installing' : 'Install';

  String get subtitle {
    if (message != null && message!.isNotEmpty) return message!;
    if (state == AppstoreBundleState.partial) {
      return '$installedCount of $totalCount parts installed';
    }
    return detail ?? '$totalCount parts';
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
    required this.initialization,
    required this.checking,
    required this.busy,
    required this.serviceRunning,
    required this.canRequestInstalls,
    required this.workoutIdle,
    required this.mayInstallNow,
    required this.holdReason,
    required this.lastCheckWallMs,
    required this.items,
    this.bundles = const <AppstoreBundle>[],
    this.lastError,
    this.catalogUrl,
  });

  factory AppstoreStatus.fromMap(Map<String, dynamic> map) {
    final rawItems = map['items'];
    final items = rawItems is List
        ? rawItems
              .map(
                (e) =>
                    AppstoreItem.fromMap(Map<String, dynamic>.from(e as Map)),
              )
              .toList()
        : <AppstoreItem>[];
    return AppstoreStatus(
      initialization: AppstoreInitialization.parse(map['initialization']),
      checking: map['checking'] == true,
      busy: map['busy'] == true,
      serviceRunning: map['serviceRunning'] == true,
      canRequestInstalls: map['canRequestInstalls'] == true,
      workoutIdle: map['workoutIdle'] == true,
      mayInstallNow: map['mayInstallNow'] == true,
      holdReason: map['holdReason'] as String? ?? '',
      lastCheckWallMs: (map['lastCheckWallMs'] as num?)?.toInt() ?? 0,
      items: items,
      bundles: switch (map['bundles']) {
        final List raw =>
          raw
              .map(
                (e) =>
                    AppstoreBundle.fromMap(Map<String, dynamic>.from(e as Map)),
              )
              .toList(),
        _ => const <AppstoreBundle>[],
      },
      lastError: map['lastError'] as String?,
      catalogUrl: map['catalogUrl'] as String?,
    );
  }

  static const AppstoreStatus empty = AppstoreStatus(
    initialization: AppstoreInitialization.notStarted,
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

  final AppstoreInitialization initialization;
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
  final List<AppstoreBundle> bundles;
  final String? lastError;
  final String? catalogUrl;

  bool get initializing =>
      initialization == AppstoreInitialization.notStarted ||
      initialization == AppstoreInitialization.loading;

  /// Genuine updates to already-installed apps. Something merely *offered* by
  /// the catalog and never installed is not a pending update and must not nag
  /// like one.
  int get pendingCount =>
      _standalone.where((item) => item.kind == AppstoreKind.update).length;

  /// What the launcher badges: everything the rider could act on right now.
  ///
  /// This is [pendingCount] *plus* offerable bundles, which is not a detail —
  /// "Install Google Play" is the single most consequential thing the sheet can
  /// offer, and counting only updates left it invisible behind an unlabelled
  /// icon that gave no reason to tap it. A bundle mid-run still counts: an
  /// install that needs a confirmation the rider has not given yet is exactly
  /// when the header should be saying so.
  int get actionableCount => pendingCount + offerableBundles.length;

  /// Rows a rider can act on individually. Bundle members are excluded: they are
  /// offered as their bundle, never one at a time.
  Iterable<AppstoreItem> get _standalone =>
      items.where((item) => item.bundleId == null);

  /// Bundles with something left to install. A complete one has nothing to say.
  List<AppstoreBundle> get offerableBundles =>
      bundles.where((b) => !b.isComplete || b.restartPending).toList();

  /// Stride's own pending upgrade, if any. Always needs an explicit tap.
  AppstoreItem? get selfUpdate {
    for (final item in items) {
      if (item.isSelf && item.kind == AppstoreKind.update) return item;
    }
    return null;
  }

  List<AppstoreItem> get updates => _standalone
      .where((item) => item.kind == AppstoreKind.update && !item.isSelf)
      .toList();

  List<AppstoreItem> get available => _standalone
      .where((item) => item.kind == AppstoreKind.notInstalled)
      .toList();

  List<AppstoreItem> get rest => _standalone
      .where(
        (item) =>
            item.kind == AppstoreKind.upToDate ||
            item.kind == AppstoreKind.ineligible,
      )
      .toList();
}
