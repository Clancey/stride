import 'dart:io';

import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';

import '../bridge.dart';
import '../cert_extractor.dart';
import '../glassos_probe.dart';
import '../widgets/spike_scaffold.dart';

/// S2 - the existential spike: on-device credential extraction, then a real mTLS gRPC call.
///
/// Credentials land in app-private storage (getApplicationSupportDirectory), never /sdcard.
class GlassOsScreen extends StatefulWidget {
  const GlassOsScreen({super.key});

  @override
  State<GlassOsScreen> createState() => _GlassOsScreenState();
}

class _GlassOsScreenState extends State<GlassOsScreen> with SpikeLog {
  bool _busy = false;
  String? _certDir;

  Future<Directory> _credentialDir() async {
    final base = await getApplicationSupportDirectory();
    final dir = Directory('${base.path}/glassos');
    if (!await dir.exists()) await dir.create(recursive: true);
    return dir;
  }

  Future<void> _locate() => guard('locate iFit console APK', () async {
        final info = await SpikeBridge.ifitApkPaths();
        logLine('installed: ${info['installed']}  package: ${info['package']}');
        final paths = (info['paths'] as List?) ?? const [];
        final readable = (info['readable'] as List?) ?? const [];
        for (var i = 0; i < paths.length; i++) {
          final ok = i < readable.length && readable[i] == true;
          logLine('  ${ok ? "readable" : "UNREADABLE"}  ${paths[i]}');
        }
        final related = (info['related'] as List?) ?? const [];
        if (related.isNotEmpty) {
          logLine('Related iFit/GlassOS packages on this device:');
          for (final r in related) {
            final m = Map<String, dynamic>.from(r as Map);
            logLine('  ${m['package']}  ->  ${m['sourceDir']}');
          }
        }
        if (info['installed'] != true) {
          logLine(
            'FAIL com.ifit.rivendell not installed. Check the "related" list above - this '
            'firmware may name the console package differently.',
          );
        } else if (readable.contains(false)) {
          logLine('FAIL APK is not readable by this app. Extraction cannot proceed.');
        } else {
          logLine('PASS Console APK located and readable.');
        }
      });

  Future<void> _extract() async {
    setState(() => _busy = true);
    await guard('extract credentials', () async {
      final info = await SpikeBridge.ifitApkPaths();
      final paths = ((info['paths'] as List?) ?? const []).cast<String>();
      if (paths.isEmpty) {
        logLine('FAIL No APK path. Run "Locate APK" first.');
        return;
      }

      final all = <DerFinding>[];
      for (final path in paths) {
        logLine('Scanning $path ...');
        final result = await CertExtractor.extractFromApk(path);
        for (final line in result.log) {
          logLine('  $line');
        }
        all.addAll(result.findings);
      }

      final combined = ExtractionResult(findings: all, log: const []);
      if (!combined.isComplete) {
        logLine(
          'FAIL Incomplete credential set: '
          '${combined.certificates.length} cert(s), ${combined.privateKeys.length} key(s). '
          'Need a CA cert, a client cert, and a private key.',
        );
        return;
      }

      final dir = await _credentialDir();
      await File('${dir.path}/ca.pem').writeAsString(combined.caCertificate!.toPem());
      await File('${dir.path}/client.pem').writeAsString(combined.clientCertificate!.toPem());
      await File('${dir.path}/client.key').writeAsString(combined.clientKey!.toPem());
      setState(() => _certDir = dir.path);

      logLine('PASS Wrote ca.pem, client.pem, client.key to app-private storage:');
      logLine('  ${dir.path}');
      logLine(
        'These are secrets. They are deliberately NOT on /sdcard, are never logged, and are '
        'never bundled into a Stride build (plan section 2.2).',
      );
    });
    if (mounted) setState(() => _busy = false);
  }

  Future<void> _probe() async {
    setState(() => _busy = true);
    await guard('GlassOS gRPC probe', () async {
      final dir = await _credentialDir();
      final ca = File('${dir.path}/ca.pem');
      final cert = File('${dir.path}/client.pem');
      final key = File('${dir.path}/client.key');
      if (!await ca.exists() || !await cert.exists() || !await key.exists()) {
        logLine('FAIL No credentials on disk. Run "Extract" first.');
        return;
      }

      final probe = GlassOsProbe(
        caCertPem: await ca.readAsString(),
        clientCertPem: await cert.readAsString(),
        clientKeyPem: await key.readAsString(),
      );
      for (final step in await probe.run()) {
        logLine(step.toString());
      }
      logLine(
        'Reminder: S2 is not finished until you also test iFit backgrounded / force-stopped, '
        'GlassOS restart, app process death, and what the belt does when the controlling '
        'client disappears (plan section 5, hazard row 1).',
      );
    });
    if (mounted) setState(() => _busy = false);
  }

  @override
  Widget build(BuildContext context) {
    return SpikeScaffold(
      title: 'S2 — GlassOS',
      question: 'Extract the console credentials on-device, then complete an mTLS gRPC call '
          'to localhost:54321. No certificates ship with this app.',
      log: log,
      actions: [
        FilledButton.icon(
          onPressed: _busy ? null : _locate,
          icon: const Icon(Icons.search),
          label: const Text('Locate APK'),
        ),
        FilledButton.icon(
          onPressed: _busy ? null : _extract,
          icon: const Icon(Icons.key_outlined),
          label: const Text('Extract certs'),
        ),
        FilledButton.icon(
          onPressed: _busy ? null : _probe,
          icon: const Icon(Icons.cable_outlined),
          label: const Text('Probe gRPC'),
        ),
        if (_busy) const Padding(
          padding: EdgeInsets.all(8),
          child: SizedBox(
            width: 20,
            height: 20,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
        ),
      ],
      body: _certDir == null
          ? null
          : Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Card(
                color: Theme.of(context).colorScheme.primaryContainer,
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Text(
                    'Credentials stored: $_certDir',
                    style: const TextStyle(fontSize: 11, fontFamily: 'monospace'),
                  ),
                ),
              ),
            ),
    );
  }
}
