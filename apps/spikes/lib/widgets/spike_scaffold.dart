/// Shared UI scaffolding for spike screens: an action bar plus an append-only result log.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class SpikeScaffold extends StatelessWidget {
  const SpikeScaffold({
    super.key,
    required this.title,
    required this.question,
    required this.actions,
    required this.log,
    this.body,
  });

  final String title;
  final String question;
  final List<Widget> actions;
  final List<String> log;
  final Widget? body;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(title),
        actions: [
          IconButton(
            tooltip: 'Copy log',
            icon: const Icon(Icons.copy_all_outlined),
            onPressed: () {
              Clipboard.setData(ClipboardData(text: log.join('\n')));
              ScaffoldMessenger.of(
                context,
              ).showSnackBar(const SnackBar(content: Text('Log copied')));
            },
          ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                question,
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(12),
            child: Wrap(spacing: 8, runSpacing: 8, children: actions),
          ),
          if (body != null) Flexible(child: body!),
          const Divider(height: 1),
          Expanded(child: LogView(log: log)),
        ],
      ),
    );
  }
}

class LogView extends StatelessWidget {
  const LogView({super.key, required this.log});

  final List<String> log;

  @override
  Widget build(BuildContext context) {
    if (log.isEmpty) {
      return const Center(child: Text('No results yet.'));
    }
    return ListView.builder(
      padding: const EdgeInsets.all(12),
      reverse: true,
      itemCount: log.length,
      itemBuilder: (context, i) {
        final line = log[log.length - 1 - i];
        final isFail = line.contains('FAIL') || line.contains('ERROR');
        final isPass = line.contains('PASS') || line.contains('FOUND');
        return Padding(
          padding: const EdgeInsets.only(bottom: 4),
          child: SelectableText(
            line,
            style: TextStyle(
              fontFamily: 'monospace',
              fontSize: 11,
              color: isFail
                  ? Theme.of(context).colorScheme.error
                  : isPass
                  ? Theme.of(context).colorScheme.primary
                  : null,
            ),
          ),
        );
      },
    );
  }
}

/// Mixin providing a timestamped log buffer for spike screens.
mixin SpikeLog<T extends StatefulWidget> on State<T> {
  final List<String> log = <String>[];

  void logLine(String line) {
    if (!mounted) return;
    final ts = DateTime.now().toIso8601String().substring(11, 19);
    setState(() {
      for (final l in line.split('\n')) {
        log.add('$ts  $l');
      }
    });
  }

  /// Run [action], logging its failure. Keeps error handling uniform across every screen.
  Future<void> guard(String label, Future<void> Function() action) async {
    logLine('> $label');
    try {
      await action();
    } catch (e, st) {
      logLine('ERROR $label: $e');
      logLine(st.toString().split('\n').take(4).join('\n'));
    }
  }
}
