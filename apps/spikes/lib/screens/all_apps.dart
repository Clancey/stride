import 'dart:async';

import 'package:flutter/material.dart';

import '../bridge.dart';
import '../theme/stride_tokens.dart';
import '../widgets/stride_sheet.dart';
import '../widgets/app_models.dart';
import '../widgets/app_tile.dart';
import '../model/appstore.dart';
import '../model/profile_store.dart';

class AllAppsScreen extends StatefulWidget {
  const AllAppsScreen({
    super.key,
    required this.apps,
    required this.profiles,
    required this.iconCache,
    required this.onLaunch,
    this.onAppsChanged,
  });

  /// Seed inventory, so the grid paints immediately instead of flashing empty
  /// while this screen re-reads it. After an install the screen refreshes itself.
  final List<LaunchableApp> apps;
  final ProfileStore profiles;
  final AppIconCache iconCache;
  final Future<void> Function(LaunchableApp app) onLaunch;

  /// Told when the installed set changes, so the launcher underneath can refresh
  /// its pinned row and update badge. This screen owns its own copy of the list
  /// because a pushed route does not rebuild when the launcher's state does.
  final Future<void> Function()? onAppsChanged;

  @override
  State<AllAppsScreen> createState() => _AllAppsScreenState();
}

class _AllAppsScreenState extends State<AllAppsScreen>
    with SingleTickerProviderStateMixin {
  final TextEditingController _search = TextEditingController();
  late final TabController _tabs;

  late List<LaunchableApp> _apps;
  AppstoreStatus _store = AppstoreStatus.empty;
  Timer? _poll;

  /// Packages already reconciled into [_apps]. An install that completes while
  /// this screen is open reports `installed` on every poll thereafter, so
  /// without this the inventory would reload every two seconds forever.
  final Set<String> _reconciled = <String>{};

  @override
  void initState() {
    super.initState();
    _apps = widget.apps;
    _tabs = TabController(length: 2, vsync: this)
      ..addListener(() => setState(() {}));
    _refreshStore();
    // Same cadence as the updates sheet: the install pipeline lives in a service
    // that outlives the Flutter engine, so polling is the only way to see it.
    _poll = Timer.periodic(const Duration(seconds: 2), (_) => _refreshStore());
  }

  @override
  void dispose() {
    _poll?.cancel();
    _tabs.dispose();
    _search.dispose();
    super.dispose();
  }

  /// Never throws: a catalog that cannot be reached must not take the app list
  /// down with it, since browsing and pinning installed apps still works offline.
  Future<void> _refreshStore() async {
    final raw = await SpikeBridge.appstoreStatus();
    if (!mounted) return;
    final status = AppstoreStatus.fromMap(raw);
    setState(() => _store = status);

    // An app that just finished installing is not in the inventory this screen
    // is holding. Re-read it so the Installed tab can actually show the thing
    // the rider just installed - that is the whole point of installing it here.
    final freshlyInstalled = status.items
        .where((item) => item.stage == AppstoreStage.installed)
        .map((item) => item.package)
        .where((package) => !_reconciled.contains(package))
        .toList();
    if (freshlyInstalled.isEmpty) return;
    _reconciled.addAll(freshlyInstalled);
    await _reloadInstalled();
  }

  Future<void> _reloadInstalled() async {
    try {
      final raw = await SpikeBridge.listApps();
      final apps =
          raw
              .map(LaunchableApp.fromMap)
              .where((app) => app.package.isNotEmpty)
              .toList()
            ..sort((a, b) {
              final score = b.mediaScore.compareTo(a.mediaScore);
              if (score != 0) return score;
              return a.label.toLowerCase().compareTo(b.label.toLowerCase());
            });
      if (!mounted) return;
      setState(() => _apps = List<LaunchableApp>.unmodifiable(apps));
      // The launcher owns profile side effects such as auto-pinning newly
      // installed media apps; doing it here too would run that logic twice.
      await widget.onAppsChanged?.call();
    } catch (_) {
      // Keep the list we already have. A failed re-read is not worth emptying
      // a working screen over.
    }
  }

  Future<void> _install(AppstoreItem item) async {
    await SpikeBridge.appstoreInstall(item.package);
    await _refreshStore();
  }

  @override
  Widget build(BuildContext context) {
    final storeCount = _store.available
        .where((item) => item.isActionable)
        .length;
    return Scaffold(
      appBar: AppBar(
        title: const Text('All apps'),
        bottom: TabBar(
          controller: _tabs,
          tabs: [
            Tab(text: 'Installed (${_apps.length})'),
            Tab(text: storeCount > 0 ? 'Store ($storeCount)' : 'Store'),
          ],
        ),
      ),
      // The overlay's rails sit on top of this screen, so the drawer has to keep
      // out of their way or the first and last column of apps end up underneath
      // the speed and incline columns.
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(
            StrideSpace.xl,
            StrideSpace.md,
            StrideSpace.xl,
            StrideSpace.xl,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SizedBox(
                height: StrideSpace.minTouch,
                child: TextField(
                  controller: _search,
                  autofocus: false,
                  style: Theme.of(context).textTheme.bodyLarge,
                  decoration: InputDecoration(
                    hintText: _tabs.index == 0
                        ? 'Search installed apps'
                        : 'Search the Stride catalog',
                    prefixIcon: const Icon(Icons.search),
                  ),
                ),
              ),
              const SizedBox(height: StrideSpace.lg),
              Expanded(
                child: TabBarView(
                  controller: _tabs,
                  children: [_buildInstalled(), _buildStore()],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildInstalled() {
    return AnimatedBuilder(
      animation: widget.profiles,
      builder: (context, _) {
        final query = _search.text.trim().toLowerCase();
        final apps = _apps.where((app) {
          if (query.isEmpty) return true;
          return app.label.toLowerCase().contains(query) ||
              app.package.toLowerCase().contains(query);
        }).toList();

        if (apps.isEmpty) {
          return const _NoSearchResults();
        }

        // Wrap, not a fixed-aspect grid: the tile sizes itself from its icon and
        // label, and pinning it to a ratio clips the label on the browse metrics.
        return SingleChildScrollView(
          child: Wrap(
            spacing: AppTileMetrics.gutter,
            runSpacing: AppTileMetrics.gutter,
            children: [
              for (final app in apps)
                AppTile(
                  app: app,
                  iconCache: widget.iconCache,
                  metrics: AppTileMetrics.browse,
                  pinned: widget.profiles.isPinned(app.package),
                  onLaunch: () => widget.onLaunch(app),
                  onPinToggle: () =>
                      _togglePin(app, widget.profiles.isPinned(app.package)),
                ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildStore() {
    final query = _search.text.trim().toLowerCase();
    bool matches(AppstoreItem item) =>
        query.isEmpty ||
        item.name.toLowerCase().contains(query) ||
        item.package.toLowerCase().contains(query);

    // Only apps the console does not have. Updates to installed apps are the
    // updates sheet's job; duplicating them here would give two places to press
    // the same button and two places for it to disagree.
    final offered = _store.available.where(matches).toList();
    final blocked = _store.items
        .where((item) => item.kind == AppstoreKind.ineligible && matches(item))
        .toList();

    if (offered.isEmpty && blocked.isEmpty) {
      return _StoreEmpty(
        status: _store,
        searching: query.isNotEmpty,
        onCheckNow: () async {
          await SpikeBridge.appstoreCheckNow();
          await _refreshStore();
        },
      );
    }

    return ListView(
      children: [
        if (!_store.mayInstallNow && _store.holdReason.isNotEmpty) ...[
          _Notice(text: _store.holdReason),
          const SizedBox(height: StrideSpace.md),
        ],
        for (final item in offered)
          _StoreRow(
            item: item,
            enabled: _store.mayInstallNow,
            onInstall: () => _install(item),
          ),
        if (blocked.isNotEmpty) ...[
          const SizedBox(height: StrideSpace.md),
          Padding(
            padding: const EdgeInsets.only(bottom: StrideSpace.xs),
            child: Text(
              'Not available for this console',
              style: Theme.of(
                context,
              ).textTheme.titleSmall?.copyWith(color: StrideColors.textMuted),
            ),
          ),
          for (final item in blocked)
            _StoreRow(item: item, enabled: false, onInstall: () {}),
        ],
      ],
    );
  }

  Future<void> _togglePin(LaunchableApp app, bool pinned) async {
    if (!pinned) {
      await widget.profiles.pin(app.package);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            '${app.label} pinned to ${widget.profiles.active.name}',
          ),
        ),
      );
      return;
    }

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
                'Unpin ${app.label}?',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: StrideSpace.sm),
              Text(
                'It will be removed from ${widget.profiles.active.name}. You can pin it again here later.',
                style: Theme.of(context).textTheme.bodyMedium,
              ),
              const SizedBox(height: StrideSpace.lg),
              FilledButton.icon(
                onPressed: () async {
                  Navigator.of(context).pop();
                  await widget.profiles.unpin(app.package);
                  if (!mounted) return;
                  ScaffoldMessenger.of(this.context).showSnackBar(
                    SnackBar(content: Text('${app.label} unpinned')),
                  );
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

/// One catalog entry: what it is, and the one button that acts on it.
class _StoreRow extends StatelessWidget {
  const _StoreRow({
    required this.item,
    required this.enabled,
    required this.onInstall,
  });

  final AppstoreItem item;
  final bool enabled;
  final VoidCallback onInstall;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final progress = item.progress;
    // Disabled rather than hidden, matching the updates sheet: a button that
    // disappears mid-workout reads as a crash, not as a safety measure.
    final canInstall = enabled && !item.stage.isBusy && item.isActionable;
    final failed = item.stage == AppstoreStage.failed;

    return Container(
      margin: const EdgeInsets.only(bottom: StrideSpace.sm),
      padding: const EdgeInsets.all(StrideSpace.sm),
      decoration: BoxDecoration(
        color: StrideColors.panel,
        borderRadius: BorderRadius.circular(StrideRadius.md),
        border: Border.all(color: StrideColors.line),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  item.name,
                  style: theme.textTheme.titleMedium?.copyWith(
                    color: item.isActionable
                        ? StrideColors.text
                        : StrideColors.textMuted,
                  ),
                ),
                const SizedBox(height: StrideSpace.xxs),
                Text(
                  item.subtitle,
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: failed
                        ? StrideColors.danger
                        : StrideColors.textMuted,
                  ),
                ),
                if (progress != null) ...[
                  const SizedBox(height: StrideSpace.xs),
                  LinearProgressIndicator(value: progress),
                ],
              ],
            ),
          ),
          const SizedBox(width: StrideSpace.sm),
          if (item.stage == AppstoreStage.installed)
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: StrideSpace.sm),
              child: Icon(Icons.check_circle, color: StrideColors.accent),
            )
          else if (item.isActionable)
            FilledButton(
              style: FilledButton.styleFrom(
                minimumSize: const Size(0, StrideSpace.minTouch),
              ),
              onPressed: canInstall ? onInstall : null,
              child: Text(
                item.stage.needsConfirm
                    ? 'Confirm'
                    : failed
                    ? 'Retry'
                    : 'Install',
              ),
            ),
        ],
      ),
    );
  }
}

/// Why installing is being held right now, in the rider's words.
class _Notice extends StatelessWidget {
  const _Notice({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(StrideSpace.sm),
    decoration: BoxDecoration(
      color: StrideColors.panelRaised,
      borderRadius: BorderRadius.circular(StrideRadius.md),
      border: Border.all(color: StrideColors.warning),
    ),
    child: Row(
      children: [
        const Icon(Icons.info_outline, color: StrideColors.warning),
        const SizedBox(width: StrideSpace.sm),
        Expanded(
          child: Text(text, style: Theme.of(context).textTheme.bodyMedium),
        ),
      ],
    ),
  );
}

class _StoreEmpty extends StatelessWidget {
  const _StoreEmpty({
    required this.status,
    required this.searching,
    required this.onCheckNow,
  });

  final AppstoreStatus status;
  final bool searching;
  final Future<void> Function() onCheckNow;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final String headline;
    final String detail;
    if (searching) {
      headline = 'Nothing in the catalog matches';
      detail = 'Try a different app name or package.';
    } else if (status.lastError != null && status.lastError!.isNotEmpty) {
      headline = 'Catalog unavailable';
      detail = status.lastError!;
    } else if (status.lastCheckWallMs == 0) {
      headline = 'Not checked yet';
      detail =
          'Stride has not reached the catalog since it started. '
          'This is safe to do between workouts.';
    } else {
      headline = 'Everything on offer is installed';
      detail =
          'The catalog has nothing this console is missing. '
          'Updates to installed apps appear under Updates.';
    }

    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 480),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(
              Icons.storefront_outlined,
              size: 64,
              color: StrideColors.textMuted,
            ),
            const SizedBox(height: StrideSpace.md),
            Text(headline, style: theme.textTheme.headlineMedium),
            const SizedBox(height: StrideSpace.sm),
            Text(
              detail,
              textAlign: TextAlign.center,
              style: theme.textTheme.bodyMedium,
            ),
            if (!searching) ...[
              const SizedBox(height: StrideSpace.lg),
              OutlinedButton.icon(
                onPressed: status.checking ? null : () => onCheckNow(),
                icon: const Icon(Icons.refresh),
                label: Text(status.checking ? 'Checking...' : 'Check now'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _NoSearchResults extends StatelessWidget {
  const _NoSearchResults();

  @override
  Widget build(BuildContext context) {
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 480),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(
              Icons.search_off_outlined,
              size: 64,
              color: StrideColors.textMuted,
            ),
            const SizedBox(height: StrideSpace.md),
            Text(
              'No apps found',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
            const SizedBox(height: StrideSpace.sm),
            Text(
              'Try a different app name or package.',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyMedium,
            ),
          ],
        ),
      ),
    );
  }
}
