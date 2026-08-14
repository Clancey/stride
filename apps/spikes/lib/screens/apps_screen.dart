import 'package:flutter/material.dart';

import '../bridge.dart';
import '../widgets/spike_scaffold.dart';

/// S4 - what actually installs and launches on a non-GMS, uncertified console.
///
/// The list is sorted by media *likelihood*, not split into media/non-media. Plan section 3.6:
/// ranking, not classification - a hard classifier is wrong in both directions.
class AppsScreen extends StatefulWidget {
  const AppsScreen({super.key});

  @override
  State<AppsScreen> createState() => _AppsScreenState();
}

class _AppsScreenState extends State<AppsScreen> with SpikeLog {
  List<Map<String, dynamic>> _apps = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() => guard('list apps', () async {
    final apps = await SpikeBridge.listApps();
    apps.sort((a, b) {
      final byScore = mediaLikelihood(b).compareTo(mediaLikelihood(a));
      if (byScore != 0) return byScore;
      return (a['label'] as String? ?? '').toLowerCase().compareTo(
        (b['label'] as String? ?? '').toLowerCase(),
      );
    });
    setState(() => _apps = apps);
    logLine('${apps.length} launchable app(s).');
    final likely = apps.where((a) => mediaLikelihood(a) >= 40).toList();
    logLine('${likely.length} ranked as likely media apps:');
    for (final a in likely) {
      logLine(
        '  ${mediaLikelihood(a).toString().padLeft(3)}  '
        '${a['label']}  (${a['package']})',
      );
    }
  });

  @override
  Widget build(BuildContext context) {
    return SpikeScaffold(
      title: 'S4 — Apps',
      question:
          'Which apps install and launch here? Netflix on a likely Widevine-L3, '
          'uncertified console is the expected problem child.',
      log: log,
      actions: [
        FilledButton.icon(
          onPressed: _load,
          icon: const Icon(Icons.refresh),
          label: const Text('Rescan'),
        ),
      ],
      body: SizedBox(
        height: 260,
        child: ListView.builder(
          itemCount: _apps.length,
          itemBuilder: (context, i) {
            final app = _apps[i];
            final score = mediaLikelihood(app);
            return ListTile(
              dense: true,
              leading: CircleAvatar(
                radius: 14,
                backgroundColor: score >= 40
                    ? Theme.of(context).colorScheme.primaryContainer
                    : Theme.of(context).colorScheme.surfaceContainerHighest,
                child: Text('$score', style: const TextStyle(fontSize: 10)),
              ),
              title: Text(
                app['label']?.toString() ?? '?',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              subtitle: Text(
                '${app['package']}'
                '${app['hasMediaBrowserService'] == true ? '  · MediaBrowserService' : ''}'
                '${app['leanback'] == true ? '  · leanback' : ''}',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 10),
              ),
              trailing: IconButton(
                icon: const Icon(Icons.launch, size: 18),
                onPressed: () => guard('launch ${app['package']}', () async {
                  final ok = await SpikeBridge.launchApp(
                    app['package'] as String,
                  );
                  logLine(ok ? 'Launched.' : 'FAIL No launch intent.');
                }),
              ),
            );
          },
        ),
      ),
    );
  }
}
