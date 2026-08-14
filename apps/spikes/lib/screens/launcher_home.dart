import 'package:flutter/material.dart';

import '../bridge.dart';
import '../theme/stride_tokens.dart';
import '../widgets/app_models.dart';
import '../widgets/app_tile.dart';
import '../model/profile_store.dart';
import 'all_apps.dart';
import 'diagnostics_home.dart';

class LauncherHome extends StatefulWidget {
  const LauncherHome({super.key});

  @override
  State<LauncherHome> createState() => LauncherHomeState();
}

class LauncherHomeState extends State<LauncherHome> {
  final ProfileStore _profiles = ProfileStore();
  final AppIconCache _iconCache = AppIconCache();
  final ScrollController _pinnedScroll = ScrollController();

  List<LaunchableApp> _apps = const <LaunchableApp>[];
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _loadApps();
  }

  @override
  void dispose() {
    _profiles.dispose();
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
                          const SizedBox(width: StrideSpace.lg),
                          Expanded(
                            flex: 4,
                            child: _WorkoutPanel(
                              onDiagnostics: _openDiagnostics,
                            ),
                          ),
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
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: StrideColors.panelRaised,
      showDragHandle: true,
      builder: (context) {
        return SafeArea(
          child: Padding(
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
          ),
        );
      },
    ).whenComplete(controller.dispose);
  }

  void _confirmUnpin(LaunchableApp app) {
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: StrideColors.panelRaised,
      showDragHandle: true,
      builder: (context) {
        return SafeArea(
          child: Padding(
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
          ),
        );
      },
    );
  }
}

class _LauncherHeader extends StatelessWidget {
  const _LauncherHeader({required this.onAllApps, required this.onDiagnostics});

  final VoidCallback onAllApps;
  final VoidCallback onDiagnostics;

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
        final includeAddTile = pinned.length < 4;
        final itemCount = pinned.length + (includeAddTile ? 1 : 0);
        final lowCount = itemCount <= 4;
        final columns = lowCount
            ? itemCount.clamp(2, 4).toInt()
            : (constraints.maxWidth / 210).floor().clamp(2, 4).toInt();
        final cellWidth =
            (constraints.maxWidth - (columns - 1) * StrideSpace.md) / columns;
        final aspectRatio = lowCount && constraints.maxHeight.isFinite
            ? cellWidth / constraints.maxHeight
            : 1.28;
        return GridView.builder(
          controller: scrollController,
          physics: lowCount ? const NeverScrollableScrollPhysics() : null,
          itemCount: itemCount,
          gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: columns,
            mainAxisSpacing: StrideSpace.md,
            crossAxisSpacing: StrideSpace.md,
            childAspectRatio: aspectRatio,
          ),
          itemBuilder: (context, index) {
            if (includeAddTile && index == itemCount - 1) {
              return _AddPinnedAppTile(onPressed: onAllApps);
            }
            final app = pinned[index];
            return AppTile(
              app: app,
              iconCache: iconCache,
              pinned: true,
              large: true,
              onLaunch: () => onLaunch(app),
              onLongPress: () => onUnpin(app),
            );
          },
        );
      },
    );
  }
}

class _AddPinnedAppTile extends StatelessWidget {
  const _AddPinnedAppTile({required this.onPressed});

  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: StrideColors.panel,
      borderRadius: BorderRadius.circular(StrideRadius.lg),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onPressed,
        child: Container(
          padding: const EdgeInsets.all(StrideSpace.lg),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(StrideRadius.lg),
            border: Border.all(color: StrideColors.line),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Container(
                width: 72,
                height: 72,
                decoration: BoxDecoration(
                  color: StrideColors.panelHigh,
                  borderRadius: BorderRadius.circular(StrideRadius.md),
                ),
                child: const Icon(
                  Icons.add,
                  size: 34,
                  color: StrideColors.accent,
                ),
              ),
              const SizedBox(height: StrideSpace.md),
              Text('Add app', style: Theme.of(context).textTheme.titleLarge),
            ],
          ),
        ),
      ),
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
  const _WorkoutPanel({required this.onDiagnostics});

  final VoidCallback onDiagnostics;

  @override
  Widget build(BuildContext context) {
    return _SurfacePanel(
      highContrast: true,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(
            Icons.sensors_off_outlined,
            size: 48,
            color: StrideColors.warning,
          ),
          const SizedBox(height: StrideSpace.md),
          Text(
            'Machine not connected',
            style: Theme.of(context).textTheme.headlineMedium,
          ),
          const SizedBox(height: StrideSpace.sm),
          Text(
            'Stride has no telemetry connection and no motor control path in this build. Controls stay locked until the console link is proven.',
            style: Theme.of(context).textTheme.bodyMedium,
          ),
          const SizedBox(height: StrideSpace.lg),
          const _StatusRail(
            label: 'Connection',
            value: 'Not connected',
            icon: Icons.link_off_outlined,
          ),
          const SizedBox(height: StrideSpace.sm),
          const _StatusRail(
            label: 'Workout controls',
            value: 'Unavailable',
            icon: Icons.lock_outline,
          ),
          const SizedBox(height: StrideSpace.lg),
          OutlinedButton.icon(
            onPressed: onDiagnostics,
            icon: const Icon(Icons.science_outlined),
            label: const Text('Hardware diagnostics'),
          ),
        ],
      ),
    );
  }
}

class _StatusRail extends StatelessWidget {
  const _StatusRail({
    required this.label,
    required this.value,
    required this.icon,
  });

  final String label;
  final String value;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return Container(
      constraints: const BoxConstraints(
        minHeight: StrideSpace.minTouch - StrideSpace.xs,
      ),
      padding: const EdgeInsets.symmetric(
        horizontal: StrideSpace.md,
        vertical: StrideSpace.sm,
      ),
      decoration: BoxDecoration(
        color: StrideColors.panel,
        borderRadius: BorderRadius.circular(StrideRadius.md),
        border: Border.all(color: StrideColors.line),
      ),
      child: Row(
        children: [
          Icon(icon, color: StrideColors.textMuted),
          const SizedBox(width: StrideSpace.md),
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: Theme.of(context).textTheme.bodySmall),
                Text(value, style: Theme.of(context).textTheme.titleMedium),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _SurfacePanel extends StatelessWidget {
  const _SurfacePanel({required this.child, this.highContrast = false});

  final Widget child;
  final bool highContrast;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(StrideSpace.lg),
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
