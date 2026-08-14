import 'dart:async';

import 'package:flutter/material.dart';

import '../bridge.dart';
import '../widgets/spike_scaffold.dart';

/// S5 - MediaSession observation and control.
///
/// The ownership semantics are the point, not the pause itself: Stride pauses only what is actually
/// playing, records exactly which packages it touched, and on resume restores only those - and only
/// if the user has not already pressed play themselves (plan section 3.2, Phase 3).
class MediaScreen extends StatefulWidget {
  const MediaScreen({super.key});

  @override
  State<MediaScreen> createState() => _MediaScreenState();
}

class _MediaScreenState extends State<MediaScreen> with SpikeLog {
  Timer? _poll;
  List<Map<String, dynamic>> _sessions = const [];
  bool _listenerEnabled = false;

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
      final enabled = await SpikeBridge.notificationListenerEnabled();
      final sessions = enabled
          ? await SpikeBridge.mediaSessions()
          : const <Map<String, dynamic>>[];
      if (mounted) {
        setState(() {
          _listenerEnabled = enabled;
          _sessions = sessions;
        });
      }
    } catch (_) {
      // Polling failures are noise; the Refresh button surfaces real errors.
    }
  }

  Future<void> _refresh() => guard('media sessions', () async {
    final enabled = await SpikeBridge.notificationListenerEnabled();
    setState(() => _listenerEnabled = enabled);
    if (!enabled) {
      logLine(
        'FAIL NotificationListenerService not connected. Grant it with:\n'
        '  adb shell cmd notification allow_listener '
        'io.stride.spikes/io.stride.spikes.StrideNotificationListener\n'
        'Without it, MediaSessionManager.getActiveSessions() throws SecurityException.',
      );
      return;
    }
    final sessions = await SpikeBridge.mediaSessions();
    setState(() => _sessions = sessions);
    logLine('PASS Listener connected. ${sessions.length} active session(s):');
    for (final s in sessions) {
      logLine(
        '  ${s['package']}  state=${s['state']} playing=${s['isPlaying']} '
        '"${s['title'] ?? ''}"',
      );
    }
  });

  @override
  Widget build(BuildContext context) {
    return SpikeScaffold(
      title: 'S5 — Media',
      question:
          'Can we observe and control sessions for the apps that survived S4, '
          'with correct pause/resume ownership?',
      log: log,
      actions: [
        FilledButton.icon(
          onPressed: () => guard('pause all playing', () async {
            final paused = await SpikeBridge.pauseAllPlaying();
            logLine(
              paused.isEmpty
                  ? 'Nothing was playing; nothing paused.'
                  : 'Paused (and now owned by Stride): ${paused.join(', ')}',
            );
            await _refreshQuiet();
          }),
          icon: const Icon(Icons.pause),
          label: const Text('Pause playing'),
        ),
        FilledButton.icon(
          onPressed: () => guard('resume paused by us', () async {
            final resumed = await SpikeBridge.resumePausedByUs();
            logLine(
              resumed.isEmpty
                  ? 'Nothing to resume - either we paused nothing, or the user already pressed play.'
                  : 'Resumed: ${resumed.join(', ')}',
            );
            await _refreshQuiet();
          }),
          icon: const Icon(Icons.play_arrow),
          label: const Text('Resume ours'),
        ),
        OutlinedButton.icon(
          // KEYCODE_MEDIA_PLAY_PAUSE. Nondeterministic - last resort only.
          onPressed: () => guard('dispatch media key', () async {
            await SpikeBridge.dispatchMediaKey(85);
            logLine(
              'Dispatched KEYCODE_MEDIA_PLAY_PAUSE (fallback path, nondeterministic).',
            );
          }),
          icon: const Icon(Icons.keyboard),
          label: const Text('Media key'),
        ),
        OutlinedButton.icon(
          onPressed: _refresh,
          icon: const Icon(Icons.refresh),
          label: const Text('Refresh'),
        ),
      ],
      body: SizedBox(
        height: 200,
        child: _sessions.isEmpty
            ? Center(
                child: Text(
                  _listenerEnabled
                      ? 'No active media sessions.\nStart playback in a pinned app.'
                      : 'Notification listener not granted.',
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              )
            : ListView.builder(
                itemCount: _sessions.length,
                itemBuilder: (context, i) {
                  final s = _sessions[i];
                  return ListTile(
                    dense: true,
                    leading: Icon(
                      s['isPlaying'] == true
                          ? Icons.play_circle
                          : Icons.pause_circle,
                      color: s['isPlaying'] == true
                          ? Theme.of(context).colorScheme.primary
                          : null,
                    ),
                    title: Text(
                      s['title']?.toString() ?? s['package'].toString(),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    subtitle: Text(
                      '${s['package']}'
                      '${s['pausedByStride'] == true ? '  · paused by Stride' : ''}',
                      style: const TextStyle(fontSize: 10),
                    ),
                  );
                },
              ),
      ),
    );
  }
}
