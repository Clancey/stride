import 'package:flutter/material.dart';

import '../model/appstore.dart';
import '../theme/stride_tokens.dart';

/// The one-tap install for a group of packages that only work together.
///
/// Drawn like the Stride self-update row rather than an ordinary app row,
/// because it has the same shape: one prominent thing, with a consequence worth
/// reading, that the rider has to opt into.
class BundleRow extends StatelessWidget {
  const BundleRow({
    super.key,
    required this.bundle,
    required this.enabled,
    required this.onInstall,
    required this.onDismiss,
  });

  final AppstoreBundle bundle;
  final bool enabled;
  final VoidCallback onInstall;
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    // Once every part is in, the only thing left is the reboot Stride cannot do
    // itself - so the row stops offering an install and just says so.
    final done = bundle.restartPending;
    return Container(
      padding: const EdgeInsets.all(StrideSpace.md),
      decoration: BoxDecoration(
        color: StrideColors.panelRaised,
        borderRadius: BorderRadius.circular(StrideRadius.md),
        border: Border.all(
          color: bundle.failed
              ? StrideColors.warning
              : StrideColors.accent.withValues(alpha: 0.6),
        ),
      ),
      child: Row(
        children: [
          Icon(
            done ? Icons.restart_alt : Icons.shop_outlined,
            color: done ? StrideColors.accent : StrideColors.text,
          ),
          const SizedBox(width: StrideSpace.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(bundle.name, style: theme.textTheme.titleMedium),
                const SizedBox(height: 2),
                Text(
                  bundle.subtitle,
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: bundle.failed
                        ? StrideColors.warning
                        : StrideColors.textMuted,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: StrideSpace.md),
          if (bundle.running)
            const SizedBox(
              width: 24,
              height: 24,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          else if (done)
            TextButton(onPressed: onDismiss, child: const Text('Dismiss'))
          else
            FilledButton(
              onPressed: enabled ? onInstall : null,
              child: Text(bundle.failed ? 'Try again' : bundle.actionLabel),
            ),
        ],
      ),
    );
  }
}

/// One tap, several confirmations. Saying so up front matters: four system
/// dialogs in a row look like something has gone wrong if nobody warned you,
/// and the rider needs to know that cancelling one stops the rest.
Future<bool?> confirmBundleInstall(
  BuildContext context,
  AppstoreBundle bundle,
) {
  return showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      backgroundColor: StrideColors.panelRaised,
      title: Text('Install ${bundle.name}?'),
      content: Text(
        'This installs ${bundle.totalCount} packages one after another, and '
        'Android will ask you to confirm each one. Say yes to all of them.\n\n'
        '${bundle.restartRequired ? 'When it finishes, restart the console.' : ''}',
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: const Text('Not now'),
        ),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(true),
          child: Text(bundle.actionLabel),
        ),
      ],
    ),
  );
}
