import 'package:flutter/material.dart';

import '../bridge.dart';
import '../widgets/spike_scaffold.dart';

class EnvironmentScreen extends StatefulWidget {
  const EnvironmentScreen({super.key});

  @override
  State<EnvironmentScreen> createState() => _EnvironmentScreenState();
}

class _EnvironmentScreenState extends State<EnvironmentScreen> with SpikeLog {
  @override
  void initState() {
    super.initState();
    _dump();
  }

  Future<void> _dump() => guard('environment', () async {
        final env = await SpikeBridge.environment();
        for (final entry in env.entries) {
          logLine('${entry.key.padRight(14)} ${entry.value}');
        }
        final sdk = env['sdkInt'] as int? ?? 0;
        logLine('');
        logLine(sdk >= 26
            ? 'PASS SDK $sdk meets the minSdk 26 target.'
            : 'FAIL SDK $sdk is below minSdk 26.');
        if (sdk >= 30) {
          logLine(
            'NOTE SDK $sdk enforces package-visibility filtering and foreground-service '
            'types. The app inventory in S4 may come back short - see plan section 9.',
          );
        }
      });

  @override
  Widget build(BuildContext context) {
    return SpikeScaffold(
      title: 'Environment',
      question: 'Establishes which Android constraints actually apply on this console.',
      log: log,
      actions: [
        FilledButton.icon(
          onPressed: _dump,
          icon: const Icon(Icons.refresh),
          label: const Text('Re-read'),
        ),
      ],
    );
  }
}
