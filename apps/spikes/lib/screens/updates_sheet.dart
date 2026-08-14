import 'dart:async';

import 'package:flutter/material.dart';

import '../bridge.dart';
import '../model/appstore.dart';
import '../theme/stride_tokens.dart';
import '../widgets/app_models.dart';
import '../widgets/store_icon.dart';
import '../widgets/stride_sheet.dart';

/// The app store's entire user interface.
///
/// Stride surfaces its own updates; there is no second store app to install,
/// which on a console with no keyboard would be one sideload too many. The
/// service does the work in the background and this sheet is where it becomes
/// visible: what is pending, what is downloading, what is blocked, and what
/// still has to be set up.
Future<void> showUpdatesSheet(BuildContext context) => showStrideSheet<void>(
  context: context,
  builder: (context) => const _UpdatesSheet(),
);

class _UpdatesSheet extends StatefulWidget {
  const _UpdatesSheet();

  @override
  State<_UpdatesSheet> createState() => _UpdatesSheetState();
}

class _UpdatesSheetState extends State<_UpdatesSheet> {
  final AppIconCache _icons = AppIconCache();
  AppstoreStatus _status = AppstoreStatus.empty;
  List<AppstoreSetupStep> _setup = const <AppstoreSetupStep>[];
  Timer? _poll;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _refresh();
    // Polled, like every other platform surface in this app: the work happens
    // in a service that outlives the Flutter engine, so there is no Dart-side
    // state to listen to. Two seconds is enough to animate a download without
    // waking the isolate pointlessly.
    _poll = Timer.periodic(const Duration(seconds: 2), (_) => _refresh());
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    final status = await SpikeBridge.appstoreStatus();
    final setup = await SpikeBridge.appstoreSetupChecklist();
    if (!mounted) return;
    setState(() {
      _status = AppstoreStatus.fromMap(status);
      _setup = setup.map(AppstoreSetupStep.fromMap).toList();
      _loading = false;
    });
  }

  Future<void> _checkNow() async {
    await SpikeBridge.appstoreCheckNow();
    await _refresh();
  }

  Future<void> _install(AppstoreItem item) async {
    if (item.isSelf) {
      final confirmed = await _confirmSelfUpdate(item);
      if (confirmed != true) return;
    }
    await SpikeBridge.appstoreInstall(item.package);
    await _refresh();
  }

  /// Stride restarting itself is the one update with a consequence worth
  /// spelling out: the overlay goes down with the process, and the overlay is
  /// the only Back and Home this console has.
  Future<bool?> _confirmSelfUpdate(AppstoreItem item) => showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      backgroundColor: StrideColors.panelRaised,
      title: const Text('Update Stride?'),
      content: Text(
        'Stride will close while it updates to ${item.availableVersionName}. '
        'The workout HUD, Back, and Home go away until it comes back.\n\n'
        'Do this between workouts, not during one.',
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: const Text('Not now'),
        ),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(true),
          child: const Text('Update Stride'),
        ),
      ],
    ),
  );

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final selfUpdate = _status.selfUpdate;

    return Padding(
      padding: const EdgeInsets.fromLTRB(
        StrideSpace.xl,
        StrideSpace.sm,
        StrideSpace.xl,
        StrideSpace.xl,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              Expanded(
                child: Text('Updates', style: theme.textTheme.headlineMedium),
              ),
              OutlinedButton.icon(
                style: OutlinedButton.styleFrom(
                  minimumSize: const Size(0, StrideSpace.minTouch),
                ),
                onPressed: _status.checking ? null : _checkNow,
                icon: const Icon(Icons.refresh_rounded),
                label: Text(_status.checking ? 'Checking...' : 'Check now'),
              ),
            ],
          ),
          const SizedBox(height: StrideSpace.xs),
          Text(
            _headline(),
            style: theme.textTheme.bodyLarge?.copyWith(
              color: _status.lastError != null
                  ? StrideColors.warning
                  : StrideColors.textMuted,
            ),
          ),
          if (!_status.mayInstallNow && _status.holdReason.isNotEmpty) ...[
            const SizedBox(height: StrideSpace.sm),
            _Notice(text: _status.holdReason),
          ],
          const SizedBox(height: StrideSpace.lg),

          if (_loading)
            const Padding(
              padding: EdgeInsets.all(StrideSpace.lg),
              child: Center(child: CircularProgressIndicator()),
            )
          else ...[
            if (selfUpdate != null) ...[
              _SectionLabel('Stride'),
              _UpdateRow(
                item: selfUpdate,
                iconCache: _icons,
                enabled: _status.mayInstallNow,
                onInstall: () => _install(selfUpdate),
                emphasised: true,
              ),
              const SizedBox(height: StrideSpace.lg),
            ],

            if (_status.updates.isNotEmpty) ...[
              _SectionLabel('App updates'),
              for (final item in _status.updates)
                _UpdateRow(
                  item: item,
                  iconCache: _icons,
                  enabled: _status.mayInstallNow,
                  onInstall: () => _install(item),
                ),
              const SizedBox(height: StrideSpace.lg),
            ],

            // Apps the catalog offers but the console has never had are not updates, and putting
            // them here made a full store look like a pile of pending work: the sheet said
            // "everything is up to date" directly above a list of Install buttons. They live in
            // All apps > Store, which is also the only place that can show what is already
            // installed alongside them. This is a pointer, not a second copy of that list.
            if (_status.available.isNotEmpty) ...[
              _StorePointer(count: _status.available.length),
              const SizedBox(height: StrideSpace.lg),
            ],

            _SectionLabel('Setup'),
            for (final step in _setup)
              _SetupRow(
                step: step,
                onAction: () async {
                  if (step.action == 'openInstallPermission') {
                    await SpikeBridge.appstoreOpenInstallPermission();
                  } else if (step.action == 'checkNow') {
                    await _checkNow();
                  }
                  await _refresh();
                },
              ),

            if (_status.rest.isNotEmpty) ...[
              const SizedBox(height: StrideSpace.lg),
              _SectionLabel('Everything else'),
              for (final item in _status.rest)
                Padding(
                  padding: const EdgeInsets.symmetric(
                    vertical: StrideSpace.xxs,
                  ),
                  child: Text(
                    '${item.name} - ${item.subtitle}',
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: StrideColors.textMuted,
                    ),
                  ),
                ),
            ],
          ],
        ],
      ),
    );
  }

  String _headline() {
    if (_status.lastError != null) return _status.lastError!;
    if (_status.lastCheckWallMs <= 0) {
      return 'No catalog check has completed yet.';
    }
    final pending = _status.pendingCount;
    if (pending == 0) return 'Everything is up to date.';
    return pending == 1 ? '1 update pending.' : '$pending updates pending.';
  }
}

/// Says where the rest of the catalog lives, without becoming a second copy of it.
class _StorePointer extends StatelessWidget {
  const _StorePointer({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      const Icon(Icons.storefront_outlined, color: StrideColors.textMuted),
      const SizedBox(width: StrideSpace.sm),
      Expanded(
        child: Text(
          count == 1
              ? '1 more app is available in All apps, under Store.'
              : '$count more apps are available in All apps, under Store.',
          style: Theme.of(
            context,
          ).textTheme.bodyMedium?.copyWith(color: StrideColors.textMuted),
        ),
      ),
    ],
  );
}

class _SectionLabel extends StatelessWidget {
  const _SectionLabel(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: StrideSpace.xs),
    child: Text(
      text.toUpperCase(),
      style: Theme.of(context).textTheme.labelLarge?.copyWith(
        color: StrideColors.textMuted,
        letterSpacing: 1.4,
      ),
    ),
  );
}

class _Notice extends StatelessWidget {
  const _Notice({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(StrideSpace.sm),
    decoration: BoxDecoration(
      color: StrideColors.panelHigh,
      borderRadius: BorderRadius.circular(StrideRadius.sm),
      border: Border.all(color: StrideColors.warning.withValues(alpha: 0.5)),
    ),
    child: Row(
      children: [
        const Icon(Icons.info_outline_rounded, color: StrideColors.warning),
        const SizedBox(width: StrideSpace.sm),
        Expanded(
          child: Text(
            text,
            style: Theme.of(
              context,
            ).textTheme.bodyMedium?.copyWith(color: StrideColors.text),
          ),
        ),
      ],
    ),
  );
}

class _UpdateRow extends StatelessWidget {
  const _UpdateRow({
    required this.item,
    required this.iconCache,
    required this.enabled,
    required this.onInstall,
    this.emphasised = false,
  });

  final AppstoreItem item;
  final AppIconCache iconCache;
  final bool enabled;
  final VoidCallback onInstall;
  final bool emphasised;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final progress = item.progress;
    // Disabled rather than hidden: a rider who saw an Install button vanish
    // mid-workout would reasonably conclude the app store had crashed.
    final canInstall = enabled && !item.stage.isBusy && item.isActionable;

    return Container(
      margin: const EdgeInsets.only(bottom: StrideSpace.sm),
      padding: const EdgeInsets.all(StrideSpace.sm),
      decoration: BoxDecoration(
        color: StrideColors.panel,
        borderRadius: BorderRadius.circular(StrideRadius.md),
        border: Border.all(
          color: emphasised ? StrideColors.accent : StrideColors.line,
          width: emphasised ? 2 : 1,
        ),
      ),
      child: Row(
        children: [
          StoreIcon(item: item, iconCache: iconCache),
          const SizedBox(width: StrideSpace.sm),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(item.name, style: theme.textTheme.titleMedium),
                const SizedBox(height: StrideSpace.xxs),
                Text(
                  item.subtitle,
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: item.stage == AppstoreStage.failed
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
          FilledButton(
            style: FilledButton.styleFrom(
              minimumSize: const Size(0, StrideSpace.minTouch),
            ),
            onPressed: canInstall ? onInstall : null,
            child: Text(
              item.stage.needsConfirm
                  ? 'Confirm'
                  : item.isSelf
                  ? 'Update Stride'
                  : 'Install',
            ),
          ),
        ],
      ),
    );
  }
}

class _SetupRow extends StatelessWidget {
  const _SetupRow({required this.step, required this.onAction});

  final AppstoreSetupStep step;
  final Future<void> Function() onAction;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      margin: const EdgeInsets.only(bottom: StrideSpace.xs),
      padding: const EdgeInsets.all(StrideSpace.sm),
      decoration: BoxDecoration(
        color: StrideColors.panel,
        borderRadius: BorderRadius.circular(StrideRadius.sm),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            step.done
                ? Icons.check_circle_rounded
                : Icons.radio_button_unchecked_rounded,
            color: step.done ? StrideColors.accent : StrideColors.textMuted,
          ),
          const SizedBox(width: StrideSpace.sm),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(step.title, style: theme.textTheme.titleSmall),
                const SizedBox(height: StrideSpace.xxs),
                Text(
                  step.detail,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: StrideColors.textMuted,
                  ),
                ),
                // Several of these genuinely cannot be granted from the console
                // itself, so the command is printed rather than implied.
                if (!step.done && step.adb != null) ...[
                  const SizedBox(height: StrideSpace.xxs),
                  SelectableText(
                    step.adb!,
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: StrideColors.info,
                      fontFamily: 'monospace',
                    ),
                  ),
                ],
              ],
            ),
          ),
          if (!step.done && step.action != null)
            TextButton(onPressed: onAction, child: const Text('Fix')),
        ],
      ),
    );
  }
}
