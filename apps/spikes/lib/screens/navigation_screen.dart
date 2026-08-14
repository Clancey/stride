import 'dart:async';

import 'package:flutter/material.dart';

import '../bridge.dart';
import '../widgets/spike_scaffold.dart';

/// S10 - Back / Home / Recents on a console with no physical buttons.
///
/// Back has exactly one implementation available to a non-system app:
/// `AccessibilityService.performGlobalAction(GLOBAL_ACTION_BACK)`. `input keyevent 4` needs
/// signature-level INJECT_EVENTS. There is no fallback, so a failure here degrades navigation to
/// Home-only (plan sections 3.3 and 9).
class NavigationScreen extends StatefulWidget {
  const NavigationScreen({super.key});

  @override
  State<NavigationScreen> createState() => _NavigationScreenState();
}

class _NavigationScreenState extends State<NavigationScreen> with SpikeLog {
  Timer? _poll;
  bool _connected = false;
  bool _enabledInSettings = false;
  String? _foreground;

  @override
  void initState() {
    super.initState();
    _refresh();
    _poll = Timer.periodic(const Duration(seconds: 2), (_) => _refreshQuiet());
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _refreshQuiet() async {
    try {
      final connected = await SpikeBridge.accessibilityConnected();
      final enabled = await SpikeBridge.accessibilityEnabledInSettings();
      final fg = await SpikeBridge.foregroundPackage();
      if (mounted) {
        setState(() {
          _connected = connected;
          _enabledInSettings = enabled;
          _foreground = fg;
        });
      }
    } catch (_) {
      // Polling failures are noise; the Refresh button surfaces real errors.
    }
  }

  Future<void> _refresh() => guard('accessibility status', () async {
        await _refreshQuiet();
        logLine('service connected: $_connected');
        logLine('listed in secure settings: $_enabledInSettings');
        logLine('foreground package: ${_foreground ?? "(unknown)"}');
        if (!_connected) {
          logLine(
            'FAIL Not connected. Enable with:\n'
            '  adb shell settings put secure enabled_accessibility_services '
            'io.stride.spikes/io.stride.spikes.StrideAccessibilityService\n'
            '  adb shell settings put secure accessibility_enabled 1',
          );
        } else {
          logLine('PASS Service connected. Now reboot and re-check - persistence is the '
              'real question, and it has no workaround.');
        }
      });

  @override
  Widget build(BuildContext context) {
    return SpikeScaffold(
      title: 'S10 — Navigation',
      question: 'Can we send Back to third-party apps, and does the service survive reboot?',
      log: log,
      actions: [
        FilledButton.icon(
          onPressed: () => guard('GLOBAL_ACTION_BACK', () async {
            final ok = await SpikeBridge.goBack();
            logLine(ok ? 'PASS Back dispatched.' : 'FAIL Service unavailable.');
          }),
          icon: const Icon(Icons.arrow_back),
          label: const Text('Back'),
        ),
        FilledButton.icon(
          onPressed: () => guard('home intent', () async {
            await SpikeBridge.goHome();
            logLine('Home intent sent (needs no accessibility service).');
          }),
          icon: const Icon(Icons.home),
          label: const Text('Home'),
        ),
        FilledButton.icon(
          onPressed: () => guard('GLOBAL_ACTION_RECENTS', () async {
            final ok = await SpikeBridge.goRecents();
            logLine(ok ? 'PASS Recents dispatched.' : 'FAIL Service unavailable.');
          }),
          icon: const Icon(Icons.layers),
          label: const Text('Recents'),
        ),
        OutlinedButton.icon(
          onPressed: _refresh,
          icon: const Icon(Icons.refresh),
          label: const Text('Refresh'),
        ),
      ],
      body: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        child: Card(
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Row(
                  children: [
                    Icon(
                      _connected ? Icons.check_circle : Icons.cancel,
                      size: 18,
                      color: _connected
                          ? Theme.of(context).colorScheme.primary
                          : Theme.of(context).colorScheme.error,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(_connected
                          ? 'Accessibility service connected'
                          : 'Accessibility service NOT connected'),
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                Text(
                  'foreground: ${_foreground ?? "(unknown)"}',
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 11),
                ),
                const SizedBox(height: 6),
                Text(
                  'To test Back properly: launch a media app from S4, use an edge swipe to '
                  'raise the overlay, and press Back there. Testing Back from this screen only '
                  'proves the API call succeeds, not that it reaches another app.',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
