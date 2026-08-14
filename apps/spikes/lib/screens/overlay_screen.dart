import 'dart:async';

import 'package:flutter/material.dart';

import '../bridge.dart';
import '../widgets/spike_scaffold.dart';

/// S3 - overlay survival and edge-gesture interference.
///
/// The counters matter as much as the visual result: `edgeTouchCount` rising while you use a media
/// app underneath is the direct measure of how much input the edge strips steal (plan section 3.3,
/// "the unavoidable cost").
class OverlayScreen extends StatefulWidget {
  const OverlayScreen({super.key});

  @override
  State<OverlayScreen> createState() => _OverlayScreenState();
}

class _OverlayScreenState extends State<OverlayScreen> with SpikeLog {
  Timer? _poll;
  Map<String, dynamic> _status = const {};

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
      final status = await SpikeBridge.overlayStatus();
      if (mounted) setState(() => _status = status);
    } catch (_) {
      // Polling failures are noise; the explicit Refresh button surfaces real errors.
    }
  }

  Future<void> _refresh() => guard('overlay status', () async {
        final status = await SpikeBridge.overlayStatus();
        setState(() => _status = status);
        logLine(status.entries.map((e) => '${e.key}=${e.value}').join('  '));
        if (status['canDrawOverlays'] != true) {
          logLine(
            'FAIL SYSTEM_ALERT_WINDOW not granted. Run:\n'
            '  adb shell appops set io.stride.spikes SYSTEM_ALERT_WINDOW allow',
          );
        }
      });

  @override
  Widget build(BuildContext context) {
    final running = _status['running'] == true;
    return SpikeScaffold(
      title: 'S3 — Overlay',
      question: 'Does the overlay survive real apps, and how much touch do the edge strips steal?',
      log: log,
      actions: [
        FilledButton.icon(
          onPressed: () => guard('start overlay', () async {
            final ok = await SpikeBridge.startOverlay();
            logLine(ok ? 'Overlay service started.' : 'FAIL Could not start - check permission.');
            await _refreshQuiet();
          }),
          icon: const Icon(Icons.play_arrow),
          label: const Text('Start'),
        ),
        OutlinedButton.icon(
          onPressed: () => guard('stop overlay', () async {
            await SpikeBridge.stopOverlay();
            logLine('Overlay service stopped.');
            await _refreshQuiet();
          }),
          icon: const Icon(Icons.stop),
          label: const Text('Stop'),
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
                      running ? Icons.check_circle : Icons.cancel,
                      size: 18,
                      color: running
                          ? Theme.of(context).colorScheme.primary
                          : Theme.of(context).colorScheme.error,
                    ),
                    const SizedBox(width: 8),
                    Text(running ? 'Overlay running' : 'Overlay stopped'),
                  ],
                ),
                const SizedBox(height: 8),
                Text(
                  'edge touches:      ${_status['edgeTouchCount'] ?? '-'}\n'
                  'consumed gestures: ${_status['consumedGestureCount'] ?? '-'}\n'
                  'last gesture:      ${_status['lastGesture'] ?? '-'}',
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 11),
                ),
                const SizedBox(height: 8),
                Text(
                  'Checklist: reboot, low-memory kill, fullscreen/immersive apps, rotation, '
                  'screen off/on, an hours-long run, and genuine touch pass-through to the app '
                  'underneath.',
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
