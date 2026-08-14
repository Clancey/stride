import 'package:flutter/material.dart';

import '../bridge.dart';
import '../model/overlay_prefs.dart';
import '../theme/stride_tokens.dart';
import '../widgets/stride_sheet.dart';
import '../widgets/app_models.dart';
import '../widgets/app_tile.dart';
import '../model/profile_store.dart';
import '../model/workout_controller.dart';
import '../model/workout_goal.dart';
import 'all_apps.dart';
import 'diagnostics_home.dart';
import 'start_workout.dart';

class LauncherHome extends StatefulWidget {
  const LauncherHome({super.key});

  @override
  State<LauncherHome> createState() => LauncherHomeState();
}

class LauncherHomeState extends State<LauncherHome> {
  final ProfileStore _profiles = ProfileStore();
  final WorkoutController _workout = WorkoutController();
  final OverlayPrefs _overlayPrefs = OverlayPrefs();
  final AppIconCache _iconCache = AppIconCache();
  final ScrollController _pinnedScroll = ScrollController();

  List<LaunchableApp> _apps = const <LaunchableApp>[];
  bool _loading = true;
  bool _overlayLoading = true;
  bool _overlayRunning = false;
  String? _error;
  String? _overlayStatus;
  WorkoutGoal _goal = const WorkoutGoal.none();

  @override
  void initState() {
    super.initState();
    _loadApps();
    _workout.load();
    _loadGoal();
    _loadOverlayPreference();
    _workout.addListener(_syncGoalWithSession);
  }

  /// Ending a workout clears its goal on the platform side, because the goal
  /// belongs to the session. Re-read it here so the header stops advertising a
  /// target that no longer exists.
  void _syncGoalWithSession() {
    if (_workout.isIdle && _goal.isTrackable) _loadGoal();
  }

  @override
  void dispose() {
    _workout.removeListener(_syncGoalWithSession);
    _profiles.dispose();
    _workout.dispose();
    _pinnedScroll.dispose();
    super.dispose();
  }

  void resetToLauncherRoot() {
    if (_pinnedScroll.hasClients) {
      _pinnedScroll.animateTo(
        0,
        duration: StrideMotion.standard,
        curve: Curves.easeOutCubic,
      );
    }
  }

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

  List<LaunchableApp> _pinnedApps() {
    final byPackage = <String, LaunchableApp>{
      for (final app in _apps) app.package: app,
    };
    return [
      for (final package in _profiles.active.pinned)
        if (byPackage[package] != null) byPackage[package]!,
    ];
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
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _LauncherHeader(
                      onAllApps: _openAllApps,
                      onDiagnostics: _openDiagnostics,
                      onGoal: _startWorkoutFlow,
                      goal: _goal,
                      overlayRunning: _overlayRunning,
                      overlayLoading: _overlayLoading,
                      onEnableOverlay: _enableOverlay,
                      onDisableOverlay: _confirmDisableOverlay,
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
                              onCreateProfile: _createProfile,
                              onRenameProfile: _showRenameProfileSheet,
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

  Future<void> _createProfile() async {
    try {
      await _profiles.createProfile(_nextProfileName());
    } catch (_) {
      if (!mounted) return;
      _showMessage('Could not create profile');
    }
  }

  String _nextProfileName() {
    final used = _profiles.profiles.map((profile) => profile.name).toSet();
    for (final name in const <String>[
      'Walk',
      'Run',
      'Recovery',
      'Intervals',
      'Guest',
    ]) {
      if (!used.contains(name)) return name;
    }
    return 'Workout ${_profiles.profiles.length + 1}';
  }

  void _openAllApps() {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (context) => AllAppsScreen(
          apps: _apps,
          profiles: _profiles,
          iconCache: _iconCache,
          onLaunch: _launch,
        ),
      ),
    );
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

  Future<void> _loadOverlayPreference() async {
    bool running = false;
    String? message;
    try {
      final status = await SpikeBridge.overlayStatus();
      running = status['running'] == true;
    } catch (_) {
      message = 'Overlay bridge unavailable in this environment.';
    }

    final desired = await _overlayPrefs.readEnabled();
    if (desired == true && !running) {
      try {
        running = await SpikeBridge.startOverlay();
        message = running
            ? 'Overlay navigation restored from your saved preference.'
            : 'Overlay navigation is saved on, but could not start.';
      } catch (_) {
        message =
            'Overlay navigation is saved on, but the bridge is unavailable.';
      }
    } else if (desired == null && running) {
      try {
        await _overlayPrefs.writeEnabled(true);
      } catch (_) {
        message = 'Overlay is running; preference could not be saved.';
      }
    }

    if (!mounted) return;
    setState(() {
      _overlayRunning = running;
      _overlayLoading = false;
      _overlayStatus = message;
    });
  }

  Future<void> _enableOverlay() async {
    setState(() => _overlayLoading = true);
    try {
      final ok = await SpikeBridge.startOverlay();
      if (ok) await _overlayPrefs.writeEnabled(true);
      if (!mounted) return;
      setState(() {
        _overlayRunning = ok;
        _overlayStatus = ok
            ? 'Overlay on: Back and Home controls stay visible over apps.'
            : 'Could not turn overlay on. Check overlay permission in diagnostics.';
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _overlayStatus = 'Could not turn overlay on from this environment.';
      });
    } finally {
      if (mounted) setState(() => _overlayLoading = false);
    }
  }

  Future<void> _confirmDisableOverlay() async {
    final confirmed = await showStrideSheet<bool>(
      context: context,
      builder: (context) {
        return Padding(
          padding: const EdgeInsets.all(StrideSpace.lg),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Turn off overlay navigation?',
                style: Theme.of(context).textTheme.headlineMedium,
              ),
              const SizedBox(height: StrideSpace.md),
              Text(
                'Turning off the overlay removes Stride’s on-screen Back and Home controls. '
                'While you are inside Netflix or another app, you lose the way back to the '
                'launcher from the treadmill console. The physical safety key remains the '
                'only true emergency stop.',
                style: Theme.of(context).textTheme.bodyLarge,
              ),
              const SizedBox(height: StrideSpace.lg),
              FilledButton.icon(
                style: FilledButton.styleFrom(
                  backgroundColor: StrideColors.warning,
                  foregroundColor: StrideColors.ink,
                ),
                onPressed: () => Navigator.of(context).pop(true),
                icon: const Icon(Icons.visibility_off_outlined),
                label: const Text('Turn overlay off'),
              ),
              const SizedBox(height: StrideSpace.sm),
              OutlinedButton(
                onPressed: () => Navigator.of(context).pop(false),
                child: const Text('Keep overlay on'),
              ),
            ],
          ),
        );
      },
    );
    if (confirmed == true) await _disableOverlay();
  }

  Future<void> _disableOverlay() async {
    setState(() => _overlayLoading = true);
    try {
      await SpikeBridge.stopOverlay();
      await _overlayPrefs.writeEnabled(false);
      if (!mounted) return;
      setState(() {
        _overlayRunning = false;
        _overlayStatus =
            'Overlay off: Back and Home controls are no longer on screen.';
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _overlayStatus = 'Could not turn overlay off.');
    } finally {
      if (mounted) setState(() => _overlayLoading = false);
    }
  }

  Future<void> _renameProfile(String id, String name) async {
    try {
      await _profiles.renameProfile(id, name);
    } catch (_) {
      if (!mounted) return;
      _showMessage('Could not rename profile');
    }
  }

  void _showRenameProfileSheet(String id, String currentName) {
    final controller = TextEditingController(text: currentName);
    showStrideSheet<void>(
      context: context,
      builder: (context) {
        return Padding(
          padding: EdgeInsets.only(
            left: StrideSpace.lg,
            right: StrideSpace.lg,
            bottom: MediaQuery.of(context).viewInsets.bottom + StrideSpace.lg,
            top: StrideSpace.sm,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Rename profile',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: StrideSpace.md),
              SizedBox(
                height: StrideSpace.minTouch,
                child: TextField(
                  controller: controller,
                  autofocus: true,
                  style: Theme.of(context).textTheme.bodyLarge,
                  decoration: const InputDecoration(hintText: 'Profile name'),
                  textInputAction: TextInputAction.done,
                  onSubmitted: (_) {
                    final name = controller.text.trim();
                    if (name.isEmpty) return;
                    Navigator.of(context).pop();
                    _renameProfile(id, name);
                  },
                ),
              ),
              const SizedBox(height: StrideSpace.lg),
              FilledButton(
                onPressed: () {
                  final name = controller.text.trim();
                  if (name.isEmpty) return;
                  Navigator.of(context).pop();
                  _renameProfile(id, name);
                },
                child: const Text('Save name'),
              ),
            ],
          ),
        );
      },
    ).whenComplete(controller.dispose);
  }

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
                'Remove ${app.label} from ${_profiles.active.name}?',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: StrideSpace.lg),
              FilledButton.icon(
                onPressed: () {
                  Navigator.of(context).pop();
                  _profiles.unpin(app.package);
                },
                icon: const Icon(Icons.remove_circle_outline),
                label: const Text('Unpin app'),
              ),
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
}

class _LauncherHeader extends StatelessWidget {
  const _LauncherHeader({
    required this.onAllApps,
    required this.onDiagnostics,
    required this.onGoal,
    required this.goal,
    required this.overlayRunning,
    required this.overlayLoading,
    required this.onEnableOverlay,
    required this.onDisableOverlay,
  });

  final VoidCallback onAllApps;
  final VoidCallback onDiagnostics;
  final VoidCallback onGoal;
  final WorkoutGoal goal;
  final bool overlayRunning;
  final bool overlayLoading;
  final Future<void> Function() onEnableOverlay;
  final Future<void> Function() onDisableOverlay;

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
        SizedBox(
          width: 216,
          child: FilledButton.icon(
            style: FilledButton.styleFrom(
              minimumSize: const Size(0, StrideSpace.minTouch),
              backgroundColor: overlayRunning
                  ? StrideColors.panelHigh
                  : StrideColors.accent,
              foregroundColor: overlayRunning
                  ? StrideColors.text
                  : StrideColors.ink,
            ),
            onPressed: overlayLoading
                ? null
                : (overlayRunning ? onDisableOverlay : onEnableOverlay),
            icon: Icon(
              overlayRunning
                  ? Icons.visibility_outlined
                  : Icons.visibility_off_outlined,
            ),
            label: Text(
              overlayLoading
                  ? 'Overlay...'
                  : overlayRunning
                  ? 'Overlay on'
                  : 'Turn overlay on',
            ),
          ),
        ),
        const SizedBox(width: StrideSpace.sm),
        FilledButton.icon(
          onPressed: onAllApps,
          icon: const Icon(Icons.apps_outlined),
          label: const Text('All apps'),
        ),
        const SizedBox(width: StrideSpace.sm),
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
    required this.onCreateProfile,
    required this.onRenameProfile,
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
  final Future<void> Function() onCreateProfile;
  final void Function(String id, String currentName) onRenameProfile;

  @override
  Widget build(BuildContext context) {
    return _SurfacePanel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ProfileSwitcher(
            profiles: profiles,
            onCreateProfile: onCreateProfile,
            onRenameProfile: onRenameProfile,
          ),
          const SizedBox(height: StrideSpace.lg),
          Row(
            children: [
              Text(
                'Pinned apps',
                style: Theme.of(context).textTheme.headlineMedium,
              ),
              const Spacer(),
              TextButton.icon(
                onPressed: onAllApps,
                icon: const Icon(Icons.add_circle_outline),
                label: const Text('Pin more'),
              ),
            ],
          ),
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
              for (final app in pinned)
                AppTile(
                  app: app,
                  iconCache: iconCache,
                  pinned: true,
                  onLaunch: () => onLaunch(app),
                  onLongPress: () => onUnpin(app),
                ),
              AddAppTile(onPressed: onAllApps),
            ],
          ),
        );
      },
    );
  }
}

class _ProfileSwitcher extends StatelessWidget {
  const _ProfileSwitcher({
    required this.profiles,
    required this.onCreateProfile,
    required this.onRenameProfile,
  });

  final ProfileStore profiles;
  final Future<void> Function() onCreateProfile;
  final void Function(String id, String currentName) onRenameProfile;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 84,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: profiles.profiles.length + 1,
        separatorBuilder: (context, index) =>
            const SizedBox(width: StrideSpace.sm),
        itemBuilder: (context, index) {
          if (index == profiles.profiles.length) {
            return _ProfilePill(
              label: 'Add profile',
              selected: false,
              icon: Icons.add,
              onTap: onCreateProfile,
            );
          }
          final profile = profiles.profiles[index];
          final selected = profile.id == profiles.active.id;
          return _ProfilePill(
            label: profile.name,
            selected: selected,
            onTap: () => profiles.setActive(profile.id),
            onLongPress: () => onRenameProfile(profile.id, profile.name),
          );
        },
      ),
    );
  }
}

class _ProfilePill extends StatelessWidget {
  const _ProfilePill({
    required this.label,
    required this.selected,
    required this.onTap,
    this.icon,
    this.onLongPress,
  });

  final String label;
  final bool selected;
  final VoidCallback onTap;
  final IconData? icon;
  final VoidCallback? onLongPress;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(StrideRadius.xl),
      child: InkWell(
        borderRadius: BorderRadius.circular(StrideRadius.xl),
        onTap: onTap,
        onLongPress: onLongPress,
        child: AnimatedContainer(
          duration: StrideMotion.quick,
          width: 208,
          padding: const EdgeInsets.symmetric(horizontal: StrideSpace.lg),
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: selected ? StrideColors.accent : StrideColors.panel,
            borderRadius: BorderRadius.circular(StrideRadius.xl),
            border: Border.all(
              color: selected ? StrideColors.accent : StrideColors.line,
            ),
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              if (icon != null) ...[
                Icon(
                  icon,
                  color: selected ? StrideColors.ink : StrideColors.text,
                ),
                const SizedBox(width: StrideSpace.xs),
              ],
              Flexible(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    color: selected ? StrideColors.ink : StrideColors.text,
                    fontWeight: FontWeight.w900,
                  ),
                ),
              ),
            ],
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
                const SizedBox(height: StrideSpace.sm),
                _LockedMachineControls(machine: controller.machine),
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

class _LockedMachineControls extends StatelessWidget {
  const _LockedMachineControls({required this.machine});

  final MachineSnapshot machine;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(StrideSpace.sm),
      decoration: BoxDecoration(
        color: StrideColors.panel.withValues(alpha: 0.9),
        borderRadius: BorderRadius.circular(StrideRadius.lg),
        border: Border.all(color: StrideColors.line),
      ),
      child: Column(
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              SizedBox(
                width: 120,
                child: Text(
                  'Controls locked',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ),
              const SizedBox(width: StrideSpace.xs),
              Expanded(
                child: _LockedMiniControl(
                  label: 'Speed',
                  unit: 'mph',
                  noReadingLabel: machine.noReadingLabel,
                ),
              ),
              const SizedBox(width: 6),
              Expanded(
                child: _LockedMiniControl(
                  label: 'Incline',
                  unit: '%',
                  noReadingLabel: machine.noReadingLabel,
                ),
              ),
              const SizedBox(width: 6),
              Expanded(
                child: _LockedMiniControl(
                  label: 'Fan',
                  unit: 'level',
                  noReadingLabel: machine.noReadingLabel,
                ),
              ),
            ],
          ),
          const SizedBox(height: StrideSpace.xs),
          // Full width, and never ellipsized. This line explains *why* Stride will not touch the
          // machine; a safety explanation clipped mid-word is worse than no explanation, because
          // the rider is left guessing at the half they cannot read.
          Align(
            alignment: Alignment.centerLeft,
            child: Text(
              machine.reason,
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
        ],
      ),
    );
  }
}

class _LockedMiniControl extends StatelessWidget {
  const _LockedMiniControl({
    required this.label,
    required this.unit,
    required this.noReadingLabel,
  });

  final String label;
  final String unit;
  final String noReadingLabel;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: const Color(0xFF151A1E),
      borderRadius: BorderRadius.circular(StrideRadius.md),
      child: InkWell(
        borderRadius: BorderRadius.circular(StrideRadius.md),
        onTap: () => _showPanelMessage(
          context,
          "Stride can't control the belt yet. Use the console's own controls.",
        ),
        child: Container(
          height: 72,
          padding: const EdgeInsets.all(StrideSpace.xs),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(StrideRadius.md),
            border: Border.all(color: StrideColors.warning, width: 1.6),
          ),
          child: Row(
            children: [
              const Icon(
                Icons.lock_outline,
                color: StrideColors.warning,
                size: 18,
              ),
              const SizedBox(width: StrideSpace.xxs),
              Expanded(
                child: FittedBox(
                  fit: BoxFit.scaleDown,
                  alignment: Alignment.centerLeft,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(label, style: Theme.of(context).textTheme.bodySmall),
                      Text(
                        '$noReadingLabel $unit',
                        style: Theme.of(context).textTheme.titleMedium
                            ?.copyWith(color: StrideColors.textMuted),
                      ),
                    ],
                  ),
                ),
              ),
              const Icon(
                Icons.touch_app_outlined,
                color: StrideColors.warning,
                size: 18,
              ),
            ],
          ),
        ),
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
