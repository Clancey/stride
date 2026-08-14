import 'dart:async';

import 'package:flutter/material.dart';

import '../bridge.dart';
import '../model/appstore.dart';
import '../theme/stride_tokens.dart';
import '../widgets/stride_sheet.dart';
import '../widgets/app_models.dart';
import '../widgets/app_tile.dart';
import '../widgets/setup_card.dart';
import 'settings_screen.dart';
import '../model/profile_store.dart';
import '../model/workout_controller.dart';
import '../model/workout_goal.dart';
import 'all_apps.dart';
import 'diagnostics_home.dart';
import 'start_workout.dart';
import 'updates_sheet.dart';

class LauncherHome extends StatefulWidget {
  const LauncherHome({super.key, this.profiles});

  /// Pin storage to use instead of the on-disk one. Only tests pass this: the
  /// real store writes through path_provider, which a widget test cannot drive.
  final ProfileStore? profiles;

  @override
  State<LauncherHome> createState() => LauncherHomeState();
}

class LauncherHomeState extends State<LauncherHome>
    with WidgetsBindingObserver {
  late final ProfileStore _profiles = widget.profiles ?? ProfileStore();
  final WorkoutController _workout = WorkoutController();
  final AppIconCache _iconCache = AppIconCache();
  final ScrollController _pinnedScroll = ScrollController();

  List<LaunchableApp> _apps = const <LaunchableApp>[];
  bool _loading = true;
  bool _overlayRunning = false;
  String? _error;
  String? _overlayStatus;
  WorkoutGoal _goal = const WorkoutGoal.none();
  AppstoreStatus _appstore = AppstoreStatus.empty;
  Timer? _appstorePoll;
  bool _editingPins = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadApps();
    _workout.load();
    _loadGoal();
    _ensureOverlay();
    _workout.addListener(_syncGoalWithSession);
    _refreshAppstore();
    // Slow on purpose. The badge only has to be right the next time someone
    // looks at the launcher; the service is what keeps the console current, and
    // polling it hard would burn cycles on a screen that is usually idle.
    _appstorePoll = Timer.periodic(
      const Duration(seconds: 30),
      (_) => _refreshAppstore(),
    );
  }

  /// Ending a workout clears its goal on the platform side, because the goal
  /// belongs to the session. Re-read it here so the header stops advertising a
  /// target that no longer exists.
  void _syncGoalWithSession() {
    if (_workout.isIdle && _goal.isTrackable) _loadGoal();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _appstorePoll?.cancel();
    _workout.removeListener(_syncGoalWithSession);
    _profiles.dispose();
    _workout.dispose();
    _pinnedScroll.dispose();
    _setup.dispose();
    super.dispose();
  }

  /// Re-read the inventory whenever the launcher comes back to the front.
  ///
  /// Uninstalling leaves through a system dialog, so the only honest moment to
  /// learn whether the rider went through with it is when we get the screen
  /// back. Installs and removals made outside Stride land here too.
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _loadApps();
  }

  void resetToLauncherRoot() {
    if (_editingPins) setState(() => _editingPins = false);
    if (_pinnedScroll.hasClients) {
      _pinnedScroll.animateTo(
        0,
        duration: StrideMotion.standard,
        curve: Curves.easeOutCubic,
      );
    }
  }

  final SetupStatus _setup = SetupStatus();

  Future<void> _loadApps() async {
    try {
      await _profiles.load();
      final rawApps = await SpikeBridge.listApps();
      await _profiles.autoAddMediaApps(rawApps);
      final apps =
          rawApps
              .map(LaunchableApp.fromMap)
              .where((app) => app.package.isNotEmpty)
              .toList()
            ..sort((a, b) {
              final score = b.mediaScore.compareTo(a.mediaScore);
              if (score != 0) return score;
              return a.label.toLowerCase().compareTo(b.label.toLowerCase());
            });
      if (!mounted) return;
      setState(() {
        _apps = List<LaunchableApp>.unmodifiable(apps);
        _loading = false;
        _error = null;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = 'App inventory unavailable';
      });
    }
  }

  Future<void> _launch(LaunchableApp app) async {
    try {
      final launched = await SpikeBridge.launchApp(app.package);
      if (!mounted || launched) return;
      _showMessage('Could not launch ${app.label}');
    } catch (_) {
      if (!mounted) return;
      _showMessage('Could not launch ${app.label}');
    }
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  /// Reads the app store snapshot for the header badge. Never throws: an update
  /// service that cannot reach its catalog must not take the launcher with it.
  Future<void> _refreshAppstore() async {
    final raw = await SpikeBridge.appstoreStatus();
    if (!mounted) return;
    setState(() => _appstore = AppstoreStatus.fromMap(raw));
  }

  Future<void> _openUpdates() async {
    await showUpdatesSheet(context);
    if (!mounted) return;
    await _refreshAppstore();
  }

  List<LaunchableApp> _pinnedApps() {
    final byPackage = <String, LaunchableApp>{
      for (final app in _apps) app.package: app,
    };
    return [
      for (final package in _profiles.active.pinned)
        if (byPackage[package] != null) byPackage[package]!,
    ];
  }

  /// Moves a pinned app within the grid the rider can actually see.
  ///
  /// The visible grid drops packages that are no longer installed, so its
  /// indices are not the stored ones. Translating through packages keeps a
  /// dangling pin from silently absorbing the move.
  Future<void> _reorderPinned(int oldVisible, int newVisible) async {
    final visible = _pinnedApps();
    if (oldVisible < 0 || oldVisible >= visible.length) return;
    final stored = _profiles.active.pinned;
    final oldIndex = stored.indexOf(visible[oldVisible].package);
    if (oldIndex == -1) return;
    final newIndex = newVisible >= visible.length
        ? stored.length
        : stored.indexOf(visible[newVisible].package);
    if (newIndex == -1) return;
    try {
      await _profiles.reorderPinned(oldIndex, newIndex);
    } catch (_) {
      if (!mounted) return;
      _showMessage('Could not save the new order');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: DecoratedBox(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: <Color>[
              StrideColors.ink,
              Color(0xFF071119),
              StrideColors.ink,
            ],
          ),
        ),
        child: SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(
              StrideSpace.xl,
              StrideSpace.lg,
              StrideSpace.xl,
              StrideSpace.xl,
            ),
            child: AnimatedBuilder(
              animation: _profiles,
              builder: (context, _) {
                final pinned = _pinnedApps();
                final editing = _editingPins && pinned.isNotEmpty;
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SetupCard(status: _setup),
                    _LauncherHeader(
                      onAllApps: _openAllApps,
                      onDiagnostics: _openDiagnostics,
                      onSettings: _openSettings,
                      onGoal: _startWorkoutFlow,
                      goal: _goal,
                      updateCount: _appstore.pendingCount,
                      onUpdates: _openUpdates,
                    ),
                    const SizedBox(height: StrideSpace.lg),
                    Expanded(
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Expanded(
                            flex: 7,
                            child: _LauncherPanel(
                              profiles: _profiles,
                              loading: _loading,
                              error: _error,
                              pinned: pinned,
                              iconCache: _iconCache,
                              scrollController: _pinnedScroll,
                              onLaunch: _launch,
                              onAllApps: _openAllApps,
                              onUnpin: _confirmUnpin,
                              editing: editing,
                              onStartEditing: () =>
                                  setState(() => _editingPins = true),
                              onDoneEditing: () =>
                                  setState(() => _editingPins = false),
                              onReorder: _reorderPinned,
                            ),
                          ),
                          // Only one workout surface at a time. When the overlay is up it owns the
                          // workout entirely, and drawing a second panel behind it produced two
                          // sets of controls and two safety notices that disagreed with each other
                          // — the overlay showing live speed while this panel still read
                          // "Not measured". Contradictory safety copy is worse than none, because
                          // it teaches the rider to stop believing the notice that matters.
                          if (!_overlayRunning) ...[
                            const SizedBox(width: StrideSpace.lg),
                            Expanded(
                              flex: 5,
                              child: _WorkoutPanel(
                                controller: _workout,
                                goal: _goal,
                                onStart: _startWorkoutFlow,
                                overlayStatus: _overlayStatus,
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ],
                );
              },
            ),
          ),
        ),
      ),
    );
  }

  void _openAllApps() {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (context) => AllAppsScreen(
          apps: _apps,
          profiles: _profiles,
          iconCache: _iconCache,
          onLaunch: _launch,
          // Installing from the store changes what the launcher itself shows:
          // the pinned row, and the update badge that just lost an entry.
          onAppsChanged: () async {
            await _loadApps();
            await _refreshAppstore();
          },
        ),
      ),
    );
  }

  void _openSettings() {
    Navigator.of(context)
        .push(
          MaterialPageRoute<void>(builder: (context) => const SettingsScreen()),
        )
        // Grants can change while that screen is open, and the setup card is the thing that has to
        // notice.
        .then((_) => _setup.refresh());
  }

  void _openDiagnostics() {
    Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (context) => const DiagnosticsHome()),
    );
  }

  Future<void> _loadGoal() async {
    final map = await SpikeBridge.goalGet();
    if (!mounted) return;
    final kind = WorkoutGoalKind.fromChannel(map['kind'] as String?);
    final target = (map['target'] as num?)?.toDouble() ?? 0;
    setState(() => _goal = WorkoutGoal(kind: kind, target: target));
  }

  // The launcher's "start workout" affordance now routes through the goal
  // picker instead of starting the timer blind. The goal set there is the
  // authoritative one we surface, so it is not re-read from the bridge on
  // return — an environment without the platform side would answer that read
  // with "no goal" and wipe what the rider just chose.
  Future<void> _startWorkoutFlow() async {
    await Navigator.of(context).push<bool>(
      MaterialPageRoute<bool>(
        builder: (context) => StartWorkoutScreen(
          initialGoal: _goal,
          workoutUnderway: !_workout.isIdle,
          onConfirm: (goal) async {
            await SpikeBridge.goalSet(
              kind: goal.kind.channelValue,
              target: goal.target,
            );
            // Reachable mid-workout now that the header carries the entry, so
            // only start a session when there isn't one. Restarting a running
            // workout to change its goal would silently discard the elapsed
            // time the rider has already put in.
            final ok = _workout.isIdle ? await _workout.startWorkout() : true;
            if (ok && mounted) setState(() => _goal = goal);
            return ok;
          },
        ),
      ),
    );
  }

  /// Make sure the overlay is up. There is no switch for this, by design.
  ///
  /// The overlay is not a feature a rider opts into — it carries Back and Home on a console with
  /// no physical buttons, and it is the only place the workout can be paused once an app is
  /// full-screen. Turning it off strands you inside Netflix. This used to be a saved preference
  /// with an on/off button in the header, which meant one stray tap could take the way out away.
  ///
  /// So: start it if it isn't running, every time the launcher comes up. If it won't start, the
  /// cause is the "Draw over other apps" grant, and the setup card already names that and fixes it.
  Future<void> _ensureOverlay() async {
    bool running = false;
    String? message;
    try {
      final status = await SpikeBridge.overlayStatus();
      running = status['running'] == true;
      if (!running) running = await SpikeBridge.startOverlay();
      if (!running) {
        message =
            "Stride's controls can't appear over other apps yet. "
            'Grant "Draw over other apps" above.';
      }
    } catch (_) {
      message = 'Overlay bridge unavailable in this environment.';
    }

    if (!mounted) return;
    setState(() {
      _overlayRunning = running;
      _overlayStatus = message;
    });
  }

  /// The ✕ on a pinned tile offers both ways of getting rid of an app.
  ///
  /// Unpinning and uninstalling look identical from the grid — the tile goes
  /// away either way — so they are presented together, with the destructive one
  /// clearly the second choice and behind its own confirmation. Uninstall is
  /// hidden entirely for apps the console will not let go of, rather than shown
  /// and then failing at the system dialog.
  void _confirmUnpin(LaunchableApp app) {
    showStrideSheet<void>(
      context: context,
      builder: (context) {
        return Padding(
          padding: const EdgeInsets.all(StrideSpace.lg),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Remove ${app.label} from your pinned apps?',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: StrideSpace.lg),
              FilledButton.icon(
                onPressed: () {
                  Navigator.of(context).pop();
                  _unpin(app);
                },
                icon: const Icon(Icons.remove_circle_outline),
                label: const Text('Unpin app'),
              ),
              if (app.removable) ...[
                const SizedBox(height: StrideSpace.sm),
                OutlinedButton.icon(
                  onPressed: () {
                    Navigator.of(context).pop();
                    _confirmUninstall(app);
                  },
                  icon: const Icon(Icons.delete_outline),
                  label: const Text('Delete from console'),
                ),
              ],
              const SizedBox(height: StrideSpace.sm),
              OutlinedButton(
                onPressed: () => Navigator.of(context).pop(),
                child: const Text('Keep pinned'),
              ),
            ],
          ),
        );
      },
    );
  }

  /// Second gate before an uninstall.
  ///
  /// Android raises its own confirmation too, but that one arrives as a
  /// full-screen system activity over a console with no hardware buttons. Asking
  /// here first means the rider chooses to leave Stride, instead of being taken
  /// out of it by a mis-tap on a tile they only meant to unpin.
  void _confirmUninstall(LaunchableApp app) {
    showStrideSheet<void>(
      context: context,
      builder: (context) {
        return Padding(
          padding: const EdgeInsets.all(StrideSpace.lg),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Delete ${app.label} from this console?',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: StrideSpace.sm),
              const Text(
                'The app and its data are removed. Android will ask you to '
                'confirm once more.',
                style: TextStyle(color: StrideColors.textMuted),
              ),
              const SizedBox(height: StrideSpace.lg),
              FilledButton.icon(
                style: FilledButton.styleFrom(
                  backgroundColor: StrideColors.danger,
                  foregroundColor: StrideColors.ink,
                ),
                onPressed: () {
                  Navigator.of(context).pop();
                  _uninstall(app);
                },
                icon: const Icon(Icons.delete_outline),
                label: Text('Delete ${app.label}'),
              ),
              const SizedBox(height: StrideSpace.sm),
              OutlinedButton(
                onPressed: () => Navigator.of(context).pop(),
                child: const Text('Cancel'),
              ),
            ],
          ),
        );
      },
    );
  }

  /// Sends the app to the system uninstaller and reconciles once we are back.
  ///
  /// The pin is left alone here on purpose: the rider may cancel at the system
  /// dialog, and dropping the pin first would silently rearrange the grid for a
  /// delete that never happened. [_loadApps] on resume is what settles it — a
  /// pin whose package is gone already stops rendering.
  Future<void> _uninstall(LaunchableApp app) async {
    bool started;
    try {
      started = await SpikeBridge.uninstallApp(app.package);
    } catch (_) {
      started = false;
    }
    if (!mounted) return;
    if (!started) {
      _showMessage('${app.label} cannot be deleted on this console');
    }
  }

  /// Unpinning the last app leaves nothing to arrange, so edit mode ends with
  /// it rather than lying in wait for the next pin.
  Future<void> _unpin(LaunchableApp app) async {
    await _profiles.unpin(app.package);
    if (!mounted) return;
    if (_editingPins && _pinnedApps().isEmpty) {
      setState(() => _editingPins = false);
    }
  }
}

class _LauncherHeader extends StatelessWidget {
  const _LauncherHeader({
    required this.onAllApps,
    required this.onDiagnostics,
    required this.onSettings,
    required this.onGoal,
    required this.goal,
    required this.updateCount,
    required this.onUpdates,
  });

  final VoidCallback onAllApps;
  final VoidCallback onDiagnostics;
  final VoidCallback onSettings;
  final VoidCallback onGoal;
  final WorkoutGoal goal;

  /// Updates waiting to be installed, including Stride's own.
  final int updateCount;
  final VoidCallback onUpdates;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Stride', style: Theme.of(context).textTheme.displayLarge),
              const SizedBox(height: StrideSpace.xs),
              Text(
                'Your workout apps, one tap away.',
                style: Theme.of(context).textTheme.bodyLarge,
              ),
            ],
          ),
        ),
        // The workout panel — and with it the goal picker — is hidden while the
        // overlay is up, which is the normal configuration. Without an entry
        // here a rider running with the overlay on could never set a goal at
        // all, and the overlay's goal ring had nothing to draw.
        OutlinedButton.icon(
          style: OutlinedButton.styleFrom(
            minimumSize: const Size(0, StrideSpace.minTouch),
            foregroundColor: goal.isTrackable
                ? StrideColors.accent
                : StrideColors.text,
          ),
          onPressed: onGoal,
          icon: const Icon(Icons.flag_rounded),
          label: Text(goal.isTrackable ? 'Goal ${goal.label}' : 'Set a goal'),
        ),
        const SizedBox(width: StrideSpace.sm),
        FilledButton.icon(
          onPressed: onAllApps,
          icon: const Icon(Icons.apps_outlined),
          label: const Text('All apps'),
        ),
        const SizedBox(width: StrideSpace.sm),
        // Quiet when there is nothing to do, and never louder than the goal or
        // overlay controls next to it: an update badge must not compete with
        // the two things that matter while someone is standing on a belt.
        if (updateCount > 0)
          FilledButton.icon(
            style: FilledButton.styleFrom(
              minimumSize: const Size(0, StrideSpace.minTouch),
              backgroundColor: StrideColors.info,
              foregroundColor: StrideColors.ink,
            ),
            onPressed: onUpdates,
            icon: const Icon(Icons.system_update_alt_rounded),
            label: Text('Updates ($updateCount)'),
          )
        else
          IconButton(
            tooltip: 'Updates',
            onPressed: onUpdates,
            icon: const Icon(Icons.system_update_alt_rounded),
          ),
        const SizedBox(width: StrideSpace.sm),
        IconButton(
          tooltip: 'Settings',
          onPressed: onSettings,
          icon: const Icon(Icons.settings_outlined),
        ),
        IconButton(
          tooltip: 'Diagnostics',
          onPressed: onDiagnostics,
          icon: const Icon(Icons.tune_outlined),
        ),
      ],
    );
  }
}

class _LauncherPanel extends StatelessWidget {
  const _LauncherPanel({
    required this.profiles,
    required this.loading,
    required this.error,
    required this.pinned,
    required this.iconCache,
    required this.scrollController,
    required this.onLaunch,
    required this.onAllApps,
    required this.onUnpin,
    required this.editing,
    required this.onStartEditing,
    required this.onDoneEditing,
    required this.onReorder,
  });

  final ProfileStore profiles;
  final bool loading;
  final String? error;
  final List<LaunchableApp> pinned;
  final AppIconCache iconCache;
  final ScrollController scrollController;
  final Future<void> Function(LaunchableApp app) onLaunch;
  final VoidCallback onAllApps;
  final void Function(LaunchableApp app) onUnpin;
  final bool editing;
  final VoidCallback onStartEditing;
  final VoidCallback onDoneEditing;
  final void Function(int oldIndex, int newIndex) onReorder;

  @override
  Widget build(BuildContext context) {
    return _SurfacePanel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Profiles are hidden, not removed: ProfileStore still backs the pinned grid through a
          // single active profile, and every rider sees exactly one set of pinned apps. The
          // switcher was pulled because swapping pin sets needs a better design than a row of
          // pills. Restoring it is a UI change, not a migration.
          //
          // No "Pin more" button here either: the Add app tile in the grid does the same job at
          // the point the rider is already looking, and the header still has All apps.
          Row(
            children: [
              Expanded(
                child: Text(
                  editing ? 'Arrange apps' : 'Pinned apps',
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
              ),
              if (editing)
                FilledButton.icon(
                  onPressed: onDoneEditing,
                  icon: const Icon(Icons.check),
                  label: const Text('Done'),
                ),
            ],
          ),
          if (editing) ...[
            const SizedBox(height: StrideSpace.xs),
            const Text(
              'Hold an app to move it. Tap ✕ to unpin or delete it.',
              style: TextStyle(color: StrideColors.textMuted),
            ),
          ],
          const SizedBox(height: StrideSpace.md),
          Expanded(child: _pinnedBody()),
        ],
      ),
    );
  }

  Widget _pinnedBody() {
    if (loading) {
      return const _LoadingPinnedApps();
    }
    if (error != null) {
      return _PinnedMessage(
        icon: Icons.warning_amber_rounded,
        title: error!,
        action: 'Retry from diagnostics',
      );
    }
    if (pinned.isEmpty) {
      return _PinnedMessage(
        icon: Icons.push_pin_outlined,
        title: 'No pinned apps yet',
        action: 'Open All apps and pin the apps you use while walking.',
        buttonLabel: 'Browse apps',
        onPressed: onAllApps,
      );
    }
    return LayoutBuilder(
      builder: (context, constraints) {
        // A launcher page: fixed tiles flowing from the top left, with the add
        // cell trailing the set. Nothing stretches to fill, so pinning a fourth
        // app doesn't resize the first three.
        return SingleChildScrollView(
          controller: scrollController,
          child: Wrap(
            spacing: AppTileMetrics.gutter,
            runSpacing: AppTileMetrics.gutter,
            children: [
              for (var index = 0; index < pinned.length; index++)
                _ReorderableSlot(
                  index: index,
                  onReorder: onReorder,
                  onDragStarted: onStartEditing,
                  child: AppTile(
                    app: pinned[index],
                    iconCache: iconCache,
                    pinned: true,
                    highlighted: editing,
                    onLaunch: editing ? () {} : () => onLaunch(pinned[index]),
                    onRemove: editing ? () => onUnpin(pinned[index]) : null,
                  ),
                ),
              if (editing)
                _ReorderableSlot(
                  index: pinned.length,
                  onReorder: onReorder,
                  draggable: false,
                  child: const _EndOfGridSlot(),
                )
              else
                AddAppTile(onPressed: onAllApps),
            ],
          ),
        );
      },
    );
  }
}

/// One grid position that can both be picked up and dropped onto.
///
/// The pick-up is a long press, which is also what turns on edit mode: the
/// rider's first hold both arms the grid and carries the app, instead of
/// making them hold, wait for a mode to appear, and then start over.
///
/// Drop index semantics match [ProfileStore.reorderPinned]: the target is the
/// slot the app should end up in, so dropping a tile on the app to its right
/// lands it exactly there rather than one short of it.
class _ReorderableSlot extends StatelessWidget {
  const _ReorderableSlot({
    required this.index,
    required this.onReorder,
    required this.child,
    this.onDragStarted,
    this.draggable = true,
  });

  final int index;
  final bool draggable;
  final void Function(int oldIndex, int newIndex) onReorder;
  final VoidCallback? onDragStarted;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return DragTarget<int>(
      onWillAcceptWithDetails: (details) => details.data != index,
      onAcceptWithDetails: (details) {
        final from = details.data;
        onReorder(from, from < index ? index + 1 : index);
      },
      builder: (context, candidate, _) {
        final target = _DropIndicator(
          active: candidate.isNotEmpty,
          child: child,
        );
        if (!draggable) return target;
        // A long press, not a plain drag: the grid scrolls, and stealing every
        // vertical drag would make a full page of pinned apps unreachable.
        return LongPressDraggable<int>(
          data: index,
          onDragStarted: onDragStarted,
          dragAnchorStrategy: pointerDragAnchorStrategy,
          feedback: Transform.translate(
            offset: Offset(
              -AppTileMetrics.home.width / 2,
              -AppTileMetrics.home.height / 2,
            ),
            child: Material(color: Colors.transparent, child: child),
          ),
          childWhenDragging: Opacity(opacity: 0.3, child: child),
          child: target,
        );
      },
    );
  }
}

class _DropIndicator extends StatelessWidget {
  const _DropIndicator({required this.active, required this.child});

  final bool active;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(StrideRadius.lg),
        border: Border.all(
          color: active ? StrideColors.accent : Colors.transparent,
          width: 2,
        ),
      ),
      child: child,
    );
  }
}

/// The trailing drop slot in edit mode, so an app can be moved to the end
/// without having to land on another tile.
class _EndOfGridSlot extends StatelessWidget {
  const _EndOfGridSlot();

  @override
  Widget build(BuildContext context) {
    const metrics = AppTileMetrics.home;
    return SizedBox(
      width: metrics.width,
      height: metrics.height,
      child: Center(
        child: Container(
          width: metrics.icon,
          height: metrics.icon,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(metrics.icon * 0.24),
            border: Border.all(color: StrideColors.line),
          ),
          child: const Icon(
            Icons.south_east,
            color: StrideColors.textMuted,
            semanticLabel: 'Move to end',
          ),
        ),
      ),
    );
  }
}

class _WorkoutPanel extends StatelessWidget {
  const _WorkoutPanel({
    required this.controller,
    required this.goal,
    required this.onStart,
    this.overlayStatus,
  });

  final WorkoutController controller;
  final WorkoutGoal goal;
  final VoidCallback onStart;
  final String? overlayStatus;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        final elapsed = Duration(milliseconds: controller.elapsedMs);
        final distanceMiles = controller.machine.distanceMiles ?? 0;
        return _SurfacePanel(
          highContrast: true,
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _WorkoutHeader(state: controller.state),
                const SizedBox(height: StrideSpace.sm),
                _SafetyNotice(text: controller.machine.metricsNotice),
                const SizedBox(height: StrideSpace.sm),
                _ElapsedHero(elapsedMs: controller.elapsedMs),
                if (goal.isTrackable) ...[
                  const SizedBox(height: StrideSpace.sm),
                  _GoalStrip(
                    goal: goal,
                    distanceMiles: distanceMiles,
                    elapsed: elapsed,
                  ),
                ],
                const SizedBox(height: StrideSpace.sm),
                Row(
                  children: [
                    Expanded(
                      child: _WorkoutActions(
                        controller: controller,
                        onStart: onStart,
                      ),
                    ),
                    const SizedBox(width: StrideSpace.sm),
                    SizedBox(
                      width: 196,
                      child: _InlineVolumeControl(controller: controller),
                    ),
                  ],
                ),
                const SizedBox(height: StrideSpace.sm),
                _MetricsGrid(machine: controller.machine),
                if (overlayStatus != null) ...[
                  const SizedBox(height: StrideSpace.md),
                  Text(
                    overlayStatus!,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ],
            ),
          ),
        );
      },
    );
  }
}

class _WorkoutHeader extends StatelessWidget {
  const _WorkoutHeader({required this.state});

  final String state;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: Text(
            'Workout',
            style: Theme.of(context).textTheme.headlineMedium,
          ),
        ),
        _StatePill(state: state),
      ],
    );
  }
}

class _StatePill extends StatelessWidget {
  const _StatePill({required this.state});

  final String state;

  @override
  Widget build(BuildContext context) {
    final running = state == 'running';
    final paused = state == 'paused';
    return Container(
      constraints: const BoxConstraints(minHeight: 44),
      padding: const EdgeInsets.symmetric(
        horizontal: StrideSpace.md,
        vertical: StrideSpace.xs,
      ),
      decoration: BoxDecoration(
        color: running
            ? StrideColors.accent
            : paused
            ? StrideColors.warning
            : StrideColors.panelHigh,
        borderRadius: BorderRadius.circular(StrideRadius.xl),
      ),
      child: Center(
        child: Text(
          running
              ? 'RUNNING'
              : paused
              ? 'PAUSED'
              : 'READY',
          style: Theme.of(context).textTheme.labelMedium?.copyWith(
            color: running || paused ? StrideColors.ink : StrideColors.text,
            fontWeight: FontWeight.w900,
          ),
        ),
      ),
    );
  }
}

class _ElapsedHero extends StatelessWidget {
  const _ElapsedHero({required this.elapsedMs});

  final int elapsedMs;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(
        horizontal: StrideSpace.sm,
        vertical: StrideSpace.xxs,
      ),
      decoration: BoxDecoration(
        color: StrideColors.ink,
        borderRadius: BorderRadius.circular(StrideRadius.lg),
        border: Border.all(color: StrideColors.accentStrong, width: 1.4),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Elapsed time', style: Theme.of(context).textTheme.bodyMedium),
          const SizedBox(height: StrideSpace.xs),
          FittedBox(
            fit: BoxFit.scaleDown,
            alignment: Alignment.centerLeft,
            child: Text(
              _formatElapsed(elapsedMs),
              style: const TextStyle(
                color: StrideColors.text,
                fontSize: 40,
                height: 0.95,
                fontWeight: FontWeight.w900,
                letterSpacing: -1.5,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _GoalStrip extends StatelessWidget {
  const _GoalStrip({
    required this.goal,
    required this.distanceMiles,
    required this.elapsed,
  });

  final WorkoutGoal goal;
  final double distanceMiles;
  final Duration elapsed;

  @override
  Widget build(BuildContext context) {
    final progress = goal.progressFrom(
      distanceMiles: distanceMiles,
      elapsed: elapsed,
    );
    final percent = (progress * 100).round();
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(
        horizontal: StrideSpace.sm,
        vertical: StrideSpace.xs,
      ),
      decoration: BoxDecoration(
        color: StrideColors.panel,
        borderRadius: BorderRadius.circular(StrideRadius.lg),
        border: Border.all(color: StrideColors.line),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(
                Icons.flag_rounded,
                size: 20,
                color: StrideColors.accent,
              ),
              const SizedBox(width: StrideSpace.xs),
              Expanded(
                child: Text(
                  'Goal ${goal.label}',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ),
              Text(
                '$percent%',
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                  color: StrideColors.accent,
                  fontWeight: FontWeight.w900,
                ),
              ),
            ],
          ),
          const SizedBox(height: StrideSpace.xs),
          ClipRRect(
            borderRadius: BorderRadius.circular(StrideRadius.sm),
            child: LinearProgressIndicator(
              value: progress,
              minHeight: 10,
              backgroundColor: StrideColors.panelHigh,
              valueColor: const AlwaysStoppedAnimation<Color>(
                StrideColors.accent,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _SafetyNotice extends StatelessWidget {
  const _SafetyNotice({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(
        horizontal: StrideSpace.sm,
        vertical: StrideSpace.xs,
      ),
      decoration: BoxDecoration(
        color: const Color(0xFF2A1905),
        borderRadius: BorderRadius.circular(StrideRadius.md),
        border: Border.all(color: StrideColors.warning, width: 1.2),
      ),
      child: Text(
        text,
        style: Theme.of(context).textTheme.bodySmall?.copyWith(
          color: const Color(0xFFFFE2A3),
          fontSize: 12,
          height: 1.08,
          fontWeight: FontWeight.w900,
        ),
      ),
    );
  }
}

class _WorkoutActions extends StatelessWidget {
  const _WorkoutActions({required this.controller, required this.onStart});

  final WorkoutController controller;
  final VoidCallback onStart;

  @override
  Widget build(BuildContext context) {
    if (controller.isIdle) {
      return FilledButton.icon(
        style: FilledButton.styleFrom(
          minimumSize: const Size(double.infinity, StrideSpace.minTouch),
          backgroundColor: StrideColors.accent,
          foregroundColor: StrideColors.ink,
        ),
        onPressed: onStart,
        icon: const Icon(Icons.play_arrow_rounded, size: 34),
        label: const _ButtonLabel('Start workout'),
      );
    }

    return Row(
      children: [
        Expanded(
          child: FilledButton.icon(
            style: FilledButton.styleFrom(
              minimumSize: const Size(0, StrideSpace.minTouch),
              backgroundColor: controller.isPaused
                  ? StrideColors.accent
                  : StrideColors.warning,
              foregroundColor: StrideColors.ink,
            ),
            onPressed: () => _runBool(
              context,
              controller.isPaused
                  ? controller.resumeWorkout
                  : controller.pauseWorkout,
              controller.isPaused
                  ? 'Could not resume timer.'
                  : 'Could not pause timer.',
            ),
            icon: Icon(
              controller.isPaused
                  ? Icons.play_arrow_rounded
                  : Icons.pause_rounded,
              size: 32,
            ),
            label: _ButtonLabel(
              controller.isPaused ? 'Resume timer' : 'Pause timer',
            ),
          ),
        ),
        const SizedBox(width: StrideSpace.sm),
        SizedBox(
          width: 108,
          child: OutlinedButton(
            style: OutlinedButton.styleFrom(
              minimumSize: const Size(0, StrideSpace.minTouch),
            ),
            onPressed: () async {
              final total = await controller.finishWorkout();
              if (total == null && context.mounted) {
                _showPanelMessage(context, 'Could not end workout.');
              }
            },
            child: const _ButtonLabel('End workout'),
          ),
        ),
      ],
    );
  }
}

class _ButtonLabel extends StatelessWidget {
  const _ButtonLabel(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return FittedBox(fit: BoxFit.scaleDown, child: Text(text, maxLines: 1));
  }
}

class _MetricsGrid extends StatelessWidget {
  const _MetricsGrid({required this.machine});

  final MachineSnapshot machine;

  @override
  Widget build(BuildContext context) {
    final metrics = <Widget>[
      _MetricTile(
        label: 'Distance',
        value: _formatDecimal(
          machine.distanceMiles,
          machine.noReadingLabel,
          fractionDigits: 2,
        ),
        unit: 'mi',
        noReadingLabel: machine.noReadingLabel,
      ),
      _MetricTile(
        label: 'Pace',
        value: _formatPace(machine.paceMinPerMile, machine.noReadingLabel),
        unit: '/mi',
        noReadingLabel: machine.noReadingLabel,
      ),
      _MetricTile(
        label: 'Speed',
        value: _formatDecimal(machine.speedMph, machine.noReadingLabel),
        unit: 'mph',
        noReadingLabel: machine.noReadingLabel,
      ),
      _MetricTile(
        label: 'Incline',
        value: _formatDecimal(machine.inclinePercent, machine.noReadingLabel),
        unit: '%',
        noReadingLabel: machine.noReadingLabel,
      ),
    ];
    return SizedBox(
      height: 58,
      child: Row(
        children: [
          for (var index = 0; index < metrics.length; index++) ...[
            Expanded(child: metrics[index]),
            if (index != metrics.length - 1)
              const SizedBox(width: StrideSpace.xs),
          ],
        ],
      ),
    );
  }
}

class _MetricTile extends StatelessWidget {
  const _MetricTile({
    required this.label,
    required this.value,
    required this.unit,
    required this.noReadingLabel,
  });

  final String label;
  final String value;
  final String unit;
  final String noReadingLabel;

  @override
  Widget build(BuildContext context) {
    final unknown = value == noReadingLabel;
    return Container(
      padding: const EdgeInsets.symmetric(
        horizontal: StrideSpace.md,
        vertical: StrideSpace.sm,
      ),
      decoration: BoxDecoration(
        color: StrideColors.panel,
        borderRadius: BorderRadius.circular(StrideRadius.md),
        border: Border.all(color: StrideColors.line),
      ),
      child: FittedBox(
        fit: BoxFit.scaleDown,
        alignment: Alignment.centerLeft,
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: Theme.of(context).textTheme.bodySmall),
                Text(
                  value,
                  style: TextStyle(
                    color: unknown ? StrideColors.textMuted : StrideColors.text,
                    fontSize: 30,
                    height: 1,
                    fontWeight: FontWeight.w900,
                  ),
                ),
              ],
            ),
            const SizedBox(width: StrideSpace.xs),
            Padding(
              padding: const EdgeInsets.only(bottom: 3),
              child: Text(unit, style: Theme.of(context).textTheme.bodySmall),
            ),
          ],
        ),
      ),
    );
  }
}

class _InlineVolumeControl extends StatelessWidget {
  const _InlineVolumeControl({required this.controller});

  final WorkoutController controller;

  @override
  Widget build(BuildContext context) {
    final volume = controller.volume;
    final enabled = volume.available;
    return Container(
      height: StrideSpace.minTouch,
      padding: EdgeInsets.zero,
      decoration: BoxDecoration(
        color: StrideColors.panel.withValues(alpha: 0.9),
        borderRadius: BorderRadius.circular(StrideRadius.lg),
        border: Border.all(color: StrideColors.line),
      ),
      child: Row(
        children: [
          _RoundConsoleButton(
            icon: Icons.remove,
            enabled: enabled && volume.level > 0,
            onPressed: () => controller.setVolume(volume.level - 1),
          ),
          Expanded(
            child: FittedBox(
              fit: BoxFit.scaleDown,
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(
                    'Media volume',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  Text(
                    enabled ? '${volume.level}/${volume.max}' : 'Unavailable',
                    style: const TextStyle(
                      color: StrideColors.text,
                      fontSize: 26,
                      fontWeight: FontWeight.w900,
                    ),
                  ),
                ],
              ),
            ),
          ),
          _RoundConsoleButton(
            icon: Icons.add,
            enabled: enabled && volume.level < volume.max,
            onPressed: () => controller.setVolume(volume.level + 1),
          ),
        ],
      ),
    );
  }
}

class _RoundConsoleButton extends StatelessWidget {
  const _RoundConsoleButton({
    required this.icon,
    required this.enabled,
    this.onPressed,
  });

  final IconData icon;
  final bool enabled;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    return SizedBox.square(
      dimension: StrideSpace.minTouch,
      child: FilledButton(
        style: FilledButton.styleFrom(
          padding: EdgeInsets.zero,
          backgroundColor: enabled
              ? StrideColors.panelHigh
              : StrideColors.panelHigh.withValues(alpha: 0.55),
          foregroundColor: enabled ? StrideColors.text : StrideColors.textMuted,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(StrideRadius.md),
          ),
        ),
        onPressed: enabled && onPressed != null ? onPressed : null,
        child: Icon(icon, size: 30),
      ),
    );
  }
}

String _formatElapsed(int ms) {
  final duration = Duration(milliseconds: ms < 0 ? 0 : ms);
  final hours = duration.inHours;
  final minutes = duration.inMinutes.remainder(60).toString().padLeft(2, '0');
  final seconds = duration.inSeconds.remainder(60).toString().padLeft(2, '0');
  if (hours > 0) return '$hours:$minutes:$seconds';
  return '$minutes:$seconds';
}

String _formatDecimal(
  double? value,
  String noReadingLabel, {
  int fractionDigits = 1,
}) {
  if (value == null) return noReadingLabel;
  if (value == 0) return '0';
  return value.toStringAsFixed(fractionDigits);
}

String _formatPace(double? paceMinPerMile, String noReadingLabel) {
  if (paceMinPerMile == null) return noReadingLabel;
  if (paceMinPerMile == 0) return '0:00';
  final totalSeconds = (paceMinPerMile * 60).round();
  final minutes = totalSeconds ~/ 60;
  final seconds = (totalSeconds % 60).toString().padLeft(2, '0');
  return '$minutes:$seconds';
}

Future<void> _runBool(
  BuildContext context,
  Future<bool> Function() run,
  String failureMessage,
) async {
  final ok = await run();
  if (!ok && context.mounted) _showPanelMessage(context, failureMessage);
}

void _showPanelMessage(BuildContext context, String message) {
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
}

class _SurfacePanel extends StatelessWidget {
  const _SurfacePanel({required this.child, this.highContrast = false});

  final Widget child;
  final bool highContrast;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: highContrast
          ? const EdgeInsets.all(StrideSpace.md)
          : const EdgeInsets.all(StrideSpace.lg),
      decoration: BoxDecoration(
        color: highContrast
            ? StrideColors.panelRaised
            : StrideColors.panel.withValues(alpha: 0.94),
        borderRadius: BorderRadius.circular(StrideRadius.xl),
        border: Border.all(color: StrideColors.line),
        boxShadow: const <BoxShadow>[
          BoxShadow(
            color: Color(0x99000000),
            offset: Offset(0, 18),
            blurRadius: 36,
          ),
        ],
      ),
      child: child,
    );
  }
}

class _LoadingPinnedApps extends StatelessWidget {
  const _LoadingPinnedApps();

  @override
  Widget build(BuildContext context) {
    return GridView.count(
      crossAxisCount: 3,
      mainAxisSpacing: StrideSpace.md,
      crossAxisSpacing: StrideSpace.md,
      childAspectRatio: 1.28,
      children: List<Widget>.generate(6, (index) {
        return DecoratedBox(
          decoration: BoxDecoration(
            color: StrideColors.panel,
            borderRadius: BorderRadius.circular(StrideRadius.lg),
            border: Border.all(color: StrideColors.line),
          ),
        );
      }),
    );
  }
}

class _PinnedMessage extends StatelessWidget {
  const _PinnedMessage({
    required this.icon,
    required this.title,
    required this.action,
    this.buttonLabel,
    this.onPressed,
  });

  final IconData icon;
  final String title;
  final String action;
  final String? buttonLabel;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 480),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 72, color: StrideColors.textMuted),
            const SizedBox(height: StrideSpace.md),
            Text(
              title,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.headlineMedium,
            ),
            const SizedBox(height: StrideSpace.sm),
            Text(
              action,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyLarge,
            ),
            if (buttonLabel != null && onPressed != null) ...[
              const SizedBox(height: StrideSpace.lg),
              FilledButton(onPressed: onPressed, child: Text(buttonLabel!)),
            ],
          ],
        ),
      ),
    );
  }
}
