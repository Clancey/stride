/// Interactive stdin REPL for driving fault injection while the server runs.
///
/// Every command here maps to a required failure-mode test in docs/PLAN.md
/// section 5. Type `help` at the prompt for the list.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'fault_injection.dart';
import 'messages.dart';
import 'server.dart';

class FaultRepl {
  FaultRepl(this.host);

  final GlassOsMockHost host;

  StreamSubscription<String>? _sub;

  void start() {
    _printHelp();
    stdout.write('glassos> ');
    _sub = stdin
        .transform(utf8.decoder)
        .transform(const LineSplitter())
        .listen(_handle);
  }

  Future<void> stop() async {
    await _sub?.cancel();
    _sub = null;
  }

  Future<void> _handle(String line) async {    final cmd = line.trim();
    try {
      await _dispatch(cmd);
    } catch (e) {
      stdout.writeln('error: $e');
    }
    stdout.write('glassos> ');
  }

  Future<void> _dispatch(String cmd) async {
    final parts = cmd.split(RegExp(r'\s+'));
    final name = parts.isEmpty ? '' : parts.first;
    final arg = parts.length > 1 ? parts[1] : null;
    final faults = host.faults;
    final machine = host.machine;

    switch (name) {
      case '':
        return;
      case 'help' || '?':
        _printHelp();
      case 'status':
        _printStatus();

      // --- ack faults ---
      case 'ack-delay':
        faults.ackDelay = Duration(milliseconds: int.parse(arg ?? '0'));
        stdout.writeln('ack delay = ${faults.ackDelay.inMilliseconds} ms');
      case 'drop-acks':
        faults.dropAcks = _boolArg(arg, faults.dropAcks);
        stdout.writeln('drop acks = ${faults.dropAcks}');

      // --- telemetry fault ---
      case 'stall-telemetry':
        faults.stallTelemetry = _boolArg(arg, faults.stallTelemetry);
        stdout.writeln('stall telemetry = ${faults.stallTelemetry} '
            '(belt keeps moving: ${machine.speedKph.toStringAsFixed(2)} kph)');

      // --- server / link faults ---
      case 'die':
        await host.die();
        stdout.writeln('server died. belt policy applied: '
            '${faults.clientLostBehavior.name}. type "restart" to bring it back.');
      case 'restart':
        await host.start();
        stdout.writeln('server restarted.');
      case 'link-drop':
        final secs = int.tryParse(arg ?? '2') ?? 2;
        stdout.writeln('dropping link for ${secs}s...');
        await host.linkDrop(down: Duration(seconds: secs));

      // --- client-lost policy + trigger ---
      case 'client-lost-policy':
        faults.clientLostBehavior = switch (arg) {
          'stop' => ClientLostBehavior.stopBelt,
          'keep' => ClientLostBehavior.keepMoving,
          _ => throw ArgumentError('use: client-lost-policy stop|keep'),
        };
        stdout.writeln('client-lost policy = ${faults.clientLostBehavior.name}');
      case 'client-lost':
        machine.onClientLost();
        await host.stop(reason: 'controlling client disappeared');
        await host.start();
        stdout.writeln('client-lost simulated (policy '
            '${faults.clientLostBehavior.name}). transport re-opened.');

      // --- safety key ---
      case 'key-pull':
        machine.pullSafetyKey();
        stdout.writeln('SAFETY KEY PULLED. belt cut, latched to paused.');
      case 'key-insert':
        machine.reinsertSafetyKey();
        stdout.writeln('safety key reinserted.');

      // --- hardware button changes workout state under the client ---
      case 'button':
        switch (arg) {
          case 'start':
            machine.startWorkout(source: 'console_button');
          case 'pause':
            machine.pauseWorkout(source: 'console_button');
          case 'resume':
            machine.resumeWorkout(source: 'console_button');
          case 'stop':
            machine.stopWorkout(source: 'console_button');
          default:
            throw ArgumentError('use: button start|pause|resume|stop');
        }
        stdout.writeln('hardware button -> $arg (source: console_button)');

      // --- manual actuation (for eyeballing physics) ---
      case 'speed':
        final r = machine.setSpeed(double.parse(arg ?? '0'));
        stdout.writeln('target speed = ${r.appliedTarget} kph (gen ${r.generation})');
      case 'incline':
        final r = machine.setIncline(double.parse(arg ?? '0'));
        stdout.writeln('target incline = ${r.appliedTarget}% (gen ${r.generation})');

      case 'quit' || 'exit':
        stdout.writeln('shutting down...');
        await host.dispose();
        await stop();
        exit(0);

      default:
        stdout.writeln('unknown command "$name". type "help".');
    }
  }

  bool _boolArg(String? arg, bool current) => switch (arg) {
    'on' || 'true' || '1' => true,
    'off' || 'false' || '0' => false,
    null => !current, // toggle
    _ => throw ArgumentError('use: on|off'),
  };

  void _printStatus() {
    final m = host.machine;
    stdout.writeln('--- status ---');
    stdout.writeln('serving        : ${host.isServing}');
    stdout.writeln('speed          : ${m.speedKph.toStringAsFixed(2)} kph');
    stdout.writeln('incline        : ${m.inclinePercent.toStringAsFixed(2)} %');
    stdout.writeln('workout state  : ${WorkoutState.name(m.workoutState)}');
    stdout.writeln('safety key     : ${m.safetyKeyPresent ? "in" : "OUT"}');
    stdout.writeln('faults         : ${host.faults.describe()}');
  }

  void _printHelp() {
    stdout.writeln('''
GlassOS mock - fault injection REPL. Each command maps to a PLAN.md section 5 test.
  help | status
  ack-delay <ms>              delayed acknowledgements
  drop-acks [on|off]          dropped acknowledgements
  stall-telemetry [on|off]    telemetry stalls while belt keeps moving
  die                         server dies mid-command (applies client-lost policy)
  restart                     bring a died server back up
  link-drop [secs]            link failure then reconnection
  client-lost-policy stop|keep  what the belt does when the client disappears
  client-lost                 simulate controlling client disappearing
  key-pull | key-insert       safety-key pull and reinsertion
  button start|pause|resume|stop   hardware button changes workout state
  speed <kph> | incline <pct>  manual actuation (watch the ramp in telemetry)
  quit''');
  }
}
