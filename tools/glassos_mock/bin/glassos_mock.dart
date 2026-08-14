/// Mock GlassOS server entry point.
///
/// Usage:
///   dart run bin/glassos_mock.dart [--port N] [--no-repl] [--certs DIR]
///                                  [--no-enforce-client-id]
///                                  [--client-lost-policy stop|keep]
///
/// Faults can be preset from the environment (useful for CI / non-interactive
/// runs); the interactive REPL is the primary way to drive them live. See the
/// tool README for the full list.
library;

import 'dart:io';

import 'package:glassos_mock/glassos_mock.dart';
import 'package:glassos_mock/src/repl.dart';

Future<void> main(List<String> args) async {
  final opts = _Options.parse(args);

  final MockCerts certs;
  try {
    certs = MockCerts.load(dir: opts.certsDir);
  } on StateError catch (e) {
    stderr.writeln(e.message);
    exitCode = 2;
    return;
  }

  final faults = FaultState();
  _applyEnvFaults(faults);
  if (opts.clientLostPolicy != null) {
    faults.clientLostBehavior = opts.clientLostPolicy!;
  }

  final machine = MockMachine(faults: faults);
  final host = GlassOsMockHost(
    tls: MutualTlsCredentials(
      serverCertChain: certs.serverCert,
      serverKey: certs.serverKey,
      trustedCa: certs.caCert,
    ),
    machine: machine,
    faults: faults,
    config: MockServerConfig(
      port: opts.port,
      enforceClientId: opts.enforceClientId,
      requireClientCert: opts.requireClientCert,
    ),
  );

  await host.start();
  stdout.writeln('Mock GlassOS console up. Faults: ${faults.describe()}');

  if (opts.repl && stdin.hasTerminal) {
    FaultRepl(host).start();
  } else {
    stdout.writeln('REPL disabled; running headless. Ctrl-C to stop.');
    ProcessSignal.sigint.watch().listen((_) async {
      await host.dispose();
      exit(0);
    });
  }
}

/// Env-var fault presets, so a headless/CI run can start already-faulted.
void _applyEnvFaults(FaultState faults) {
  final env = Platform.environment;
  final delay = env['GLASSOS_ACK_DELAY_MS'];
  if (delay != null) {
    faults.ackDelay = Duration(milliseconds: int.tryParse(delay) ?? 0);
  }
  faults.dropAcks = env['GLASSOS_DROP_ACKS'] == '1';
  faults.stallTelemetry = env['GLASSOS_STALL_TELEMETRY'] == '1';
  if (env['GLASSOS_CLIENT_LOST_POLICY'] == 'stop') {
    faults.clientLostBehavior = ClientLostBehavior.stopBelt;
  }
}

class _Options {
  _Options({
    required this.port,
    required this.repl,
    required this.certsDir,
    required this.enforceClientId,
    required this.requireClientCert,
    required this.clientLostPolicy,
  });

  final int port;
  final bool repl;
  final String certsDir;
  final bool enforceClientId;
  final bool requireClientCert;
  final ClientLostBehavior? clientLostPolicy;

  static _Options parse(List<String> args) {
    var port = 54321;
    var repl = true;
    var certsDir = 'certs';
    var enforceClientId = true;
    var requireClientCert = false;
    ClientLostBehavior? clientLostPolicy;

    for (var i = 0; i < args.length; i++) {
      final a = args[i];
      String next() => (++i < args.length)
          ? args[i]
          : throw ArgumentError('missing value for $a');
      switch (a) {
        case '--port':
          port = int.parse(next());
        case '--no-repl':
          repl = false;
        case '--certs':
          certsDir = next();
        case '--no-enforce-client-id':
          enforceClientId = false;
        case '--require-client-cert':
          requireClientCert = true;
        case '--client-lost-policy':
          clientLostPolicy = switch (next()) {
            'stop' => ClientLostBehavior.stopBelt,
            'keep' => ClientLostBehavior.keepMoving,
            final v => throw ArgumentError('bad --client-lost-policy: $v'),
          };
        case '--help' || '-h':
          stdout.writeln(
            'usage: glassos_mock [--port N] [--no-repl] [--certs DIR] '
            '[--no-enforce-client-id] [--require-client-cert] '
            '[--client-lost-policy stop|keep]',
          );
          exit(0);
        default:
          throw ArgumentError('unknown flag: $a');
      }
    }
    return _Options(
      port: port,
      repl: repl,
      certsDir: certsDir,
      enforceClientId: enforceClientId,
      requireClientCert: requireClientCert,
      clientLostPolicy: clientLostPolicy,
    );
  }
}
