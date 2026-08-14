import 'package:flutter/material.dart';

import '../theme/stride_tokens.dart';
import '../widgets/stride_sheet.dart';
import '../widgets/app_models.dart';
import '../widgets/app_tile.dart';
import '../model/profile_store.dart';

class AllAppsScreen extends StatefulWidget {
  const AllAppsScreen({
    super.key,
    required this.apps,
    required this.profiles,
    required this.iconCache,
    required this.onLaunch,
  });

  final List<LaunchableApp> apps;
  final ProfileStore profiles;
  final AppIconCache iconCache;
  final Future<void> Function(LaunchableApp app) onLaunch;

  @override
  State<AllAppsScreen> createState() => _AllAppsScreenState();
}

class _AllAppsScreenState extends State<AllAppsScreen> {
  final TextEditingController _search = TextEditingController();

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('All apps')),
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
                  decoration: const InputDecoration(
                    hintText: 'Search installed apps',
                    prefixIcon: Icon(Icons.search),
                  ),
                  onChanged: (_) => setState(() {}),
                ),
              ),
              const SizedBox(height: StrideSpace.lg),
              Expanded(
                child: AnimatedBuilder(
                  animation: widget.profiles,
                  builder: (context, _) {
                    final query = _search.text.trim().toLowerCase();
                    final apps = widget.apps.where((app) {
                      if (query.isEmpty) return true;
                      return app.label.toLowerCase().contains(query) ||
                          app.package.toLowerCase().contains(query);
                    }).toList();

                    if (apps.isEmpty) {
                      return const _NoSearchResults();
                    }

                    return LayoutBuilder(
                      builder: (context, constraints) {
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
                                  onPinToggle: () => _togglePin(
                                    app,
                                    widget.profiles.isPinned(app.package),
                                  ),
                                ),
                            ],
                          ),
                        );
                      },
                    );
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _togglePin(LaunchableApp app, bool pinned) async {
    if (!pinned) {
      await widget.profiles.pin(app.package);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            '${app.label} pinned to your launcher',
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
                'It will be removed from your launcher. You can pin it again here later.',
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
