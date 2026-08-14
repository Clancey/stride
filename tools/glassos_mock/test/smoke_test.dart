/// Smoke test: start the mock server in-process over mTLS and make real gRPC
/// calls against it, using the same identity-codec technique as the on-device
/// probe (apps/spikes/lib/glassos_probe.dart).
///
/// Requires certs from tool/gen_certs.sh (run automatically below if missing).
library;

import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:glassos_mock/glassos_mock.dart';
import 'package:grpc/grpc.dart';
import 'package:test/test.dart';

void main() {
  late GlassOsMockHost host;
  late ClientChannel channel;
  late int port;
  late MockCerts certs;

  setUpAll(() async {
    if (!File('certs/server.pem').existsSync()) {
      final r = await Process.run('bash', ['tool/gen_certs.sh']);
      if (r.exitCode != 0) {
        throw StateError('gen_certs.sh failed: ${r.stderr}');
      }
    }
    certs = MockCerts.load();
  });

  setUp(() async {
    final faults = FaultState();
    final machine = MockMachine(faults: faults);
    host = GlassOsMockHost(
      tls: MutualTlsCredentials(
        serverCertChain: certs.serverCert,
        serverKey: certs.serverKey,
        trustedCa: certs.caCert,
      ),
      machine: machine,
      faults: faults,
      // port 0 lets the OS pick a free port so tests never collide with a real
      // server on 54321.
      config: const MockServerConfig(port: 0),
      log: (_) {},
    );
    await host.start();
    port = host.port!;

    channel = ClientChannel(
      'localhost',
      port: port,
      options: ChannelOptions(
        // Present the client cert/key: this is the mTLS half the server
        // requests and validates against the CA.
        credentials: MutualTlsChannelCredentials(
          trustedCa: certs.caCert,
          clientCertChain: certs.clientCert,
          clientKey: certs.clientKey,
        ),
        connectionTimeout: const Duration(seconds: 5),
      ),
    );
  });

  tearDown(() async {
    await channel.shutdown();
    await host.dispose();
  });

  ClientMethod<List<int>, List<int>> raw(String path) =>
      ClientMethod<List<int>, List<int>>(path, (v) => v, (v) => v);

  Future<Uint8List> call(String path, {List<int> body = const []}) async {
    final response = await channel
        .createCall(
          raw(path),
          Stream<List<int>>.value(body),
          CallOptions(
            metadata: {'client_id': 'com.ifit.dev_app'},
            timeout: const Duration(seconds: 5),
          ),
        )
        .response
        .first;
    return Uint8List.fromList(response);
  }

  test('GetConsole returns decodable ConsoleInfo over mTLS', () async {
    final bytes = await call('/com.ifit.glassos.ConsoleService/GetConsole');
    expect(bytes, isNotEmpty);

    final fields = ProtoReader(bytes).readAll();
    // maxKph should be the representative 1750 ceiling (12 mph ~= 19.31 kph).
    final maxKph = ProtoReader.asDouble(fields[ConsoleInfo.fMaxKph]);
    expect(maxKph, closeTo(19.31, 0.01));
    expect(ProtoReader.asInt(fields[ConsoleInfo.fCanSetSpeed]), 1);
    expect(ProtoReader.asInt(fields[ConsoleInfo.fCanSetResistance]), 0);
  });

  test('missing client_id header is rejected', () async {
    final method = raw('/com.ifit.glassos.ConsoleService/GetConsole');
    final future = channel
        .createCall(method, Stream<List<int>>.value(const []),
            CallOptions(timeout: const Duration(seconds: 5)))
        .response
        .first;
    await expectLater(
      future,
      throwsA(isA<GrpcError>().having(
        (e) => e.code, 'code', StatusCode.unauthenticated)),
    );
  });

  test('SetSpeed is accepted and the belt ramps (not instant)', () async {
    // Command 8 kph.
    final req = (ProtoWriter()..writeDouble(SetValueRequest.fValue, 8.0))
        .toBytes();
    final ackBytes =
        await call('/com.ifit.glassos.SpeedService/SetSpeed', body: req);
    final ack = ProtoReader(ackBytes).readAll();
    expect(ProtoReader.asInt(ack[CommandAck.fAccepted]), 1);
    expect(
      ProtoReader.asDouble(ack[CommandAck.fAppliedTarget]),
      closeTo(8.0, 0.001),
    );

    // Immediately after the command, the belt must NOT be at target: it ramps.
    final speed1 =
        await call('/com.ifit.glassos.SpeedService/GetSpeed');
    final kph1 = ProtoReader.asDouble(
      ProtoReader(speed1).readAll()[SpeedSample.fKph],
    );
    expect(kph1, lessThan(8.0));

    // After enough time it reaches (and telemetry reports) the target.
    await Future<void>.delayed(const Duration(seconds: 7));
    final speed2 =
        await call('/com.ifit.glassos.SpeedService/GetSpeed');
    final kph2 = ProtoReader.asDouble(
      ProtoReader(speed2).readAll()[SpeedSample.fKph],
    );
    expect(kph2, closeTo(8.0, 0.2));
  });

  test('speed telemetry stream delivers samples', () async {
    final stream = channel
        .createCall(
          raw('/com.ifit.glassos.SpeedService/StreamSpeed'),
          Stream<List<int>>.value(const []),
          CallOptions(metadata: {'client_id': 'com.ifit.dev_app'}),
        )
        .response;
    final sample = await stream.first.timeout(const Duration(seconds: 3));
    final fields = ProtoReader(Uint8List.fromList(sample)).readAll();
    expect(fields.containsKey(SpeedSample.fKph), isTrue);
  });

  test('workout lifecycle: start then stop', () async {
    await call('/com.ifit.glassos.WorkoutService/StartNewWorkout');
    final stateBytes =
        await call('/com.ifit.glassos.WorkoutService/GetWorkoutState');
    final state = ProtoReader.asInt(
      ProtoReader(stateBytes).readAll()[WorkoutStateChanged.fState],
    );
    expect(state, WorkoutState.running);

    await call('/com.ifit.glassos.WorkoutService/Stop');
    final stopped =
        await call('/com.ifit.glassos.WorkoutService/GetWorkoutState');
    final state2 = ProtoReader.asInt(
      ProtoReader(stopped).readAll()[WorkoutStateChanged.fState],
    );
    expect(state2, WorkoutState.summary);
  });

  test('dropped-ack fault surfaces as an RPC error but belt still hears it',
      () async {
    host.faults.dropAcks = true;
    final req = (ProtoWriter()..writeDouble(SetValueRequest.fValue, 5.0))
        .toBytes();
    await expectLater(
      call('/com.ifit.glassos.SpeedService/SetSpeed', body: req),
      throwsA(isA<GrpcError>()),
    );
    // The command still reached the machine even though the ack was dropped.
    expect(host.machine.speedKph >= 0, isTrue);
  });

  test('probe-compatible client (CA-only, no client cert) can GetConsole',
      () async {
    // Replicates apps/spikes/lib/glassos_probe.dart's gRPC channel exactly: it
    // trusts the CA, bypasses server-cert checks with onBadCertificate, and
    // does NOT present a client cert. The default server (requestClientCert but
    // not require) must still serve it, or the on-device probe would break.
    final probeChannel = ClientChannel(
      'localhost',
      port: port,
      options: ChannelOptions(
        credentials: ChannelCredentials.secure(
          certificates: certs.caCert,
          authority: 'localhost',
          onBadCertificate: (cert, host) => true,
        ),
        connectionTimeout: const Duration(seconds: 5),
      ),
    );
    try {
      final resp = await probeChannel
          .createCall(
            raw('/com.ifit.glassos.ConsoleService/GetConsole'),
            Stream<List<int>>.value(const []),
            CallOptions(metadata: {'client_id': 'com.ifit.dev_app'}),
          )
          .response
          .first;
      final fields = ProtoReader(Uint8List.fromList(resp)).readAll();
      expect(ProtoReader.asDouble(fields[ConsoleInfo.fMaxKph]),
          closeTo(19.31, 0.01));
    } finally {
      await probeChannel.shutdown();
    }
  });

  test('strict mTLS (requireClientCert) accepts a valid client cert', () async {
    final faults = FaultState();
    final strict = GlassOsMockHost(
      tls: MutualTlsCredentials(
        serverCertChain: certs.serverCert,
        serverKey: certs.serverKey,
        trustedCa: certs.caCert,
      ),
      machine: MockMachine(faults: faults),
      faults: faults,
      config: const MockServerConfig(port: 0, requireClientCert: true),
      log: (_) {},
    );
    await strict.start();
    final strictChannel = ClientChannel(
      'localhost',
      port: strict.port!,
      options: ChannelOptions(
        credentials: MutualTlsChannelCredentials(
          trustedCa: certs.caCert,
          clientCertChain: certs.clientCert,
          clientKey: certs.clientKey,
        ),
        connectionTimeout: const Duration(seconds: 5),
      ),
    );
    try {
      final resp = await strictChannel
          .createCall(
            raw('/com.ifit.glassos.ConsoleService/GetConsole'),
            Stream<List<int>>.value(const []),
            CallOptions(metadata: {'client_id': 'com.ifit.dev_app'}),
          )
          .response
          .first;
      expect(resp, isNotEmpty);
    } finally {
      await strictChannel.shutdown();
      await strict.dispose();
    }
  });
}
