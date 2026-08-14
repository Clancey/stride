import 'dart:async';

import 'package:flutter/material.dart';

import '../bridge.dart';
import '../widgets/spike_scaffold.dart';

/// S3 - overlay survival and edge-gesture interference.
///
/// The counters matter as much as the visual result, and they are split so intentional navigation
/// is not confused with stolen input (plan section 3.3, "the unavoidable cost"):
///   - edge touches      = every touch that landed in a strip (each one is taken from the app)
///   - nav gestures      = touches that became a real navigation swipe (the strip did its job)
///   - stolen touches    = touches that entered a strip but never navigated (pure interference)
///   - cancelled         = gesture streams the system cancelled (cleanup, never a completed swipe)
/// `stolen touches` climbing while you use a media app underneath is the direct measure of harmful
/// interference. `last fg` attributes the most recent touch to the foreground app.
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
        OutlinedButton.icon(
          onPressed: () => guard('reset counters', () async {
            await SpikeBridge.resetOverlayCounters();
            logLine('Interference counters reset. Attribute the next run to one foreground app.');
            await _refreshQuiet();
          }),
          icon: const Icon(Icons.restart_alt),
          label: const Text('Reset counters'),
        ),
      ],
      body: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        child: SingleChildScrollView(
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
                  'edge touches (stolen while down): ${_status['edgeTouchCount'] ?? '-'}\n'
                  'nav gestures (intentional):       ${_status['navGestureCount'] ?? '-'}\n'
                  'stolen touches (no navigation):   ${_status['stolenTouchCount'] ?? '-'}\n'
                  'cancelled gestures:               ${_status['cancelledGestureCount'] ?? '-'}\n'
                  'last touch fg package:            ${_status['lastTouchForegroundPackage'] ?? '-'}\n'
                  'last gesture:                     ${_status['lastGesture'] ?? '-'}',
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
      ),
    );
  }
}
