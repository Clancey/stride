import 'package:flutter/material.dart';

import '../bridge.dart';
import '../widgets/spike_scaffold.dart';

/// S1 - can the launcher be replaced *and reverted*?
///
/// The revert path matters more than the replace path. Confirm iFit is still a HOME candidate
/// before making Stride the default, and keep docs/RUNBOOK.md open while doing it.
class LauncherScreen extends StatefulWidget {
  const LauncherScreen({super.key});

  @override
  State<LauncherScreen> createState() => _LauncherScreenState();
}

class _LauncherScreenState extends State<LauncherScreen> with SpikeLog {
  @override
  void initState() {
    super.initState();
    _check();
  }

  Future<void> _check() => guard('home candidates', () async {
        final isDefault = await SpikeBridge.isDefaultHome();
        final candidates = await SpikeBridge.homeCandidates();

        logLine('Stride is default HOME: $isDefault');
        logLine('${candidates.length} HOME candidate(s):');
        for (final c in candidates) {
          final marker = c['isCurrentDefault'] == true ? '*' : ' ';
          logLine('  $marker ${c['label']}  (${c['package']})');
        }

        final others = candidates.where((c) => c['package'] != 'io.stride.spikes').length;
        if (others == 0) {
          logLine(
            'FAIL No other HOME candidate exists. Do NOT set Stride as default until an '
            'escape hatch is confirmed - you would have no way back to iFit without adb.',
          );
        } else {
          logLine('PASS $others fallback HOME app(s) available as an escape hatch.');
        }
      });

  @override
  Widget build(BuildContext context) {
    return SpikeScaffold(
      title: 'S1 — Launcher',
      question: 'Can Stride become HOME, and can we get back to iFit? '
          'Verify the revert path BEFORE setting the default.',
      log: log,
      actions: [
        FilledButton.icon(
          onPressed: _check,
          icon: const Icon(Icons.refresh),
          label: const Text('Check'),
        ),
        OutlinedButton.icon(
          onPressed: () => guard('open HOME settings', () async {
            final ok = await SpikeBridge.openHomeSettings();
            logLine(ok
                ? 'Opened a settings screen. Pick the default HOME app there.'
                : 'FAIL No settings activity resolved. Use: adb shell cmd package '
                    'set-home-activity io.stride.spikes/.MainActivity');
          }),
          icon: const Icon(Icons.settings_outlined),
          label: const Text('HOME settings'),
        ),
        OutlinedButton.icon(
          onPressed: () => guard('go home', () async {
            await SpikeBridge.goHome();
            logLine('Sent ACTION_MAIN + CATEGORY_HOME.');
          }),
          icon: const Icon(Icons.home_outlined),
          label: const Text('Go home'),
        ),
      ],
      body: const Padding(
        padding: EdgeInsets.symmetric(horizontal: 16),
        child: Card(
          child: Padding(
            padding: EdgeInsets.all(12),
            child: Text(
              'Revert without a UI:\n'
              '  adb shell cmd package set-home-activity <pkg>/<activity>\n'
              'or clear the default:\n'
              '  adb shell pm clear android\n\n'
              'S1 passes only after a full reboot with Stride as HOME, followed by a '
              'successful return to iFit.',
              style: TextStyle(fontFamily: 'monospace', fontSize: 11),
            ),
          ),
        ),
      ),
    );
  }
}
