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
    logLine(
      sdk >= 26
          ? 'PASS SDK $sdk meets the minSdk 26 target.'
          : 'FAIL SDK $sdk is below minSdk 26.',
    );
    if (sdk >= 30) {
      logLine(
        'NOTE SDK $sdk enforces package-visibility filtering and foreground-service '
        'types. The app inventory in S4 may come back short - see plan section 9.',
      );
    }
    await _dumpPlayCertification();
  });

  /// Google certifies a *build*, and nobody ever submitted this console's, so a sideloaded Play
  /// Store refuses to sign in until the device id below is registered against an account. That id
  /// is normally read with a device-id app off the Play Store, which is circular here, so this
  /// screen is where a tester gets it.
  Future<void> _dumpPlayCertification() async {
    final play = await SpikeBridge.playCertification();
    final hasGms = play['hasGms'] as bool? ?? false;
    final id = play['gsfAndroidId'] as String?;
    final hex = play['gsfAndroidIdHex'] as String?;
    final url = play['registrationUrl'] as String? ?? '';

    logLine('');
    logLine('-- Google Play certification --');
    if (!hasGms) {
      logLine(
        'NOTE Play Services is not installed. Nothing to certify yet - install the '
        'Google Play bundle first, then re-read this screen.',
      );
      return;
    }
    if (id == null) {
      logLine(
        'WARN Play Services is installed but GSF has no device id yet. It is written '
        'when GSF first reaches Google, so give the console a network and a reboot, '
        'then re-read. An id of 0 means the same thing.',
      );
      return;
    }
    logLine('GSF device id  $id');
    if (hex != null) logLine('  same in hex  $hex');
    logLine('');
    logLine('If Play says "device isn\'t Play Protect certified":');
    logLine('  1. Open $url');
    logLine('     signed in as the account the console will use.');
    logLine('  2. Paste the DECIMAL id above. Hex is rejected without saying why,');
    logLine('     which is the usual reason this step appears not to work.');
    logLine('  3. Force stop Play Services and the Play Store, clear their data,');
    logLine('     then reboot. Registration can take a few minutes to take effect.');
  }

  @override
  Widget build(BuildContext context) {
    return SpikeScaffold(
      title: 'Environment',
      question:
          'Establishes which Android constraints actually apply on this console.',
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
