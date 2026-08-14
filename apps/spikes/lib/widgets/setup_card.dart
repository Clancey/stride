import 'dart:async';

import 'package:flutter/material.dart';

import '../bridge.dart';
import '../theme/stride_tokens.dart';

/// One thing Stride needs before it can do its job, and how the rider fixes it.
class SetupStep {
  const SetupStep({
    required this.id,
    required this.label,
    required this.consequence,
    required this.done,
    required this.where,
    required this.onFix,
  });

  final String id;
  final String label;

  /// What is broken right now, in the rider's terms — not the name of a permission.
  final String consequence;
  final bool done;

  /// What to look for once the Settings app opens. Only two of these pages can be deep-linked to
  /// Stride's own row; the rest drop the rider in a list, and a list with no instruction is a
  /// dead end.
  final String? where;
  final Future<void> Function() onFix;
}

/// Watches everything Stride needs and never stops asking.
///
/// Two of these grants can be revoked by Android without any user action: uninstalling an
/// unrelated app cleared `enabled_accessibility_services` on this console and silently took the
/// Back button with it. On a machine with no physical buttons that turns every app into a one-way
/// trip, and nothing on screen said so.
///
/// So this re-checks on a timer and on every resume, and the card it drives cannot be dismissed.
/// A setup prompt you can permanently wave away is a setup prompt that will be waved away, and
/// then the rider is stuck in Netflix with no way out.
class SetupStatus extends ChangeNotifier with WidgetsBindingObserver {
  /// [pollInterval] is how often the grants are re-read behind the rider's back. Pass null to poll
  /// only on demand — tests want deterministic timing, and a periodic timer outlives the frame.
  SetupStatus({Duration? pollInterval = const Duration(seconds: 5)}) {
    WidgetsBinding.instance.addObserver(this);
    refresh();
    if (pollInterval != null) {
      _timer = Timer.periodic(pollInterval, (_) => refresh());
    }
  }

  Timer? _timer;
  List<SetupStep> _steps = const [];
  bool _loaded = false;

  List<SetupStep> get steps => _steps;
  List<SetupStep> get outstanding =>
      _steps.where((step) => !step.done).toList(growable: false);
  bool get ready => _loaded && outstanding.isEmpty;
  bool get loaded => _loaded;

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) refresh();
  }

  @override
  void dispose() {
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Future<void> refresh() async {
    try {
      final isHome = await SpikeBridge.isDefaultHome();
      final grants = await SpikeBridge.grantsGet();
      final next = <SetupStep>[
        SetupStep(
          id: 'home',
          label: 'Set Stride as the home app',
          consequence:
              "Until you do, the console's own launcher takes over whenever an app closes.",
          done: isHome,
          where: 'Pick Stride Spikes, then Always.',
          onFix: () async {
            await SpikeBridge.openHomeSettings();
          },
        ),
        for (final grant in grants)
          SetupStep(
            id: grant['id'] as String? ?? '',
            label: grant['label'] as String? ?? '',
            consequence: grant['consequence'] as String? ?? '',
            done: grant['granted'] == true,
            where: grant['id'] == 'overlay'
                ? null
                : 'Find Stride Spikes in the list and switch it on.',
            onFix: () async {
              await SpikeBridge.grantOpenSettings(grant['id'] as String? ?? '');
            },
          ),
      ];
      if (_same(next, _steps) && _loaded) return;
      _steps = next;
      _loaded = true;
      notifyListeners();
    } on Object {
      // A failed read is not evidence that a grant is missing. Leaving the last known state alone
      // stops the card flickering into view every time a channel call loses a race at startup.
    }
  }

  static bool _same(List<SetupStep> a, List<SetupStep> b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i].id != b[i].id || a[i].done != b[i].done) return false;
    }
    return true;
  }
}

/// The persistent banner. Renders nothing once everything is in place.
class SetupCard extends StatelessWidget {
  const SetupCard({super.key, required this.status});

  final SetupStatus status;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: status,
      builder: (context, _) {
        final outstanding = status.outstanding;
        if (!status.loaded || outstanding.isEmpty) {
          return const SizedBox.shrink();
        }
        return Container(
          width: double.infinity,
          margin: const EdgeInsets.only(bottom: StrideSpace.lg),
          padding: const EdgeInsets.all(StrideSpace.lg),
          decoration: BoxDecoration(
            color: StrideColors.panel,
            borderRadius: BorderRadius.circular(StrideRadius.lg),
            border: Border.all(color: StrideColors.warning, width: 1.6),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(
                    Icons.warning_amber_rounded,
                    color: StrideColors.warning,
                    size: 28,
                  ),
                  const SizedBox(width: StrideSpace.sm),
                  Expanded(
                    child: Text(
                      outstanding.length == 1
                          ? 'One thing left to set up'
                          : '${outstanding.length} things left to set up',
                      style: Theme.of(context).textTheme.headlineMedium,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: StrideSpace.md),
              for (final step in outstanding) _SetupRow(step: step),
            ],
          ),
        );
      },
    );
  }
}

class _SetupRow extends StatelessWidget {
  const _SetupRow({required this.step});

  final SetupStep step;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: StrideSpace.sm),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(step.label, style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: StrideSpace.xxs),
                Text(
                  step.where == null
                      ? step.consequence
                      : '${step.consequence} ${step.where}',
                  style: Theme.of(context).textTheme.bodyMedium,
                ),
              ],
            ),
          ),
          const SizedBox(width: StrideSpace.md),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: StrideColors.warning,
              foregroundColor: StrideColors.ink,
              minimumSize: const Size(160, StrideSpace.minTouch),
            ),
            onPressed: step.onFix,
            child: const Text('Fix this'),
          ),
        ],
      ),
    );
  }
}
