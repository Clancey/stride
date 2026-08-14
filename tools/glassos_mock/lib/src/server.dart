/// The mock GlassOS gRPC server: mTLS transport, raw-byte services, and a host
/// wrapper that can stop/restart the transport for fault injection.
///
/// Services use identity (raw byte) codecs so no generated stubs are needed
/// and the schema-free probe (apps/spikes/lib/glassos_probe.dart) can call them
/// directly. Message bodies are built by lib/src/messages.dart.
library;

import 'dart:async';
import 'dart:io';

import 'package:grpc/grpc.dart';

import 'fault_injection.dart';
import 'machine.dart';
import 'messages.dart';

/// mTLS credentials that also trust a CA for validating CLIENT certificates.
///
/// grpc's built-in ServerTlsCredentials only installs the server key+chain; it
/// does not add a trusted CA, so it cannot validate a required client cert. We
/// subclass ServerCredentials to add the CA. Combined with
/// `requireClientCertificate: true` in serve(), this enforces true mutual TLS -
/// which is the whole point of modelling the GlassOS transport risk.
class MutualTlsCredentials extends ServerCredentials {
  MutualTlsCredentials({
    required this.serverCertChain,
    required this.serverKey,
    required this.trustedCa,
  });

  final List<int> serverCertChain;
  final List<int> serverKey;
  final List<int> trustedCa;

  @override
  SecurityContext get securityContext {
    return createSecurityContext(true)
      ..useCertificateChainBytes(serverCertChain)
      ..usePrivateKeyBytes(serverKey)
      ..setTrustedCertificatesBytes(trustedCa);
  }

  @override
  bool validateClient(Socket socket) => true;
}

/// Client-side mTLS credentials that PRESENT a client certificate.
///
/// grpc's built-in ChannelCredentials.secure can only set trusted roots; it
/// cannot present a client cert/key, so it cannot complete mutual TLS. This
/// subclass overrides the SecurityContext to add the client chain and key. The
/// real Stride device layer will need the same shape to talk to GlassOS.
class MutualTlsChannelCredentials extends ChannelCredentials {
  MutualTlsChannelCredentials({
    required this.trustedCa,
    required this.clientCertChain,
    required this.clientKey,
    super.authority = 'localhost',
  }) : super.secure();

  final List<int> trustedCa;
  final List<int> clientCertChain;
  final List<int> clientKey;

  @override
  SecurityContext get securityContext {
    return createSecurityContext(false)
      ..setTrustedCertificatesBytes(trustedCa)
      ..useCertificateChainBytes(clientCertChain)
      ..usePrivateKeyBytes(clientKey);
  }
}

class MockServerConfig {
  const MockServerConfig({
    this.port = 54321,
    this.expectedClientId = 'com.ifit.dev_app',
    this.enforceClientId = true,
    this.telemetryHz = 4,
    this.requireClientCert = false,
  });

  /// Default matches the documented GlassOS endpoint (docs/PLAN.md 2.2).
  final int port;

  /// GlassOS requires a `client_id: com.ifit.dev_app` metadata header.
  final String expectedClientId;

  /// When true, calls missing the client_id header are rejected. The value is
  /// only warned about, not enforced, since its real semantics are unknown.
  final bool enforceClientId;

  final int telemetryHz;

  /// Strict mTLS: abort the TLS handshake if the client does not present a
  /// certificate. Real GlassOS behaves this way, so it is worth exercising.
  ///
  /// It defaults to FALSE for one deliberate reason: the schema-free probe in
  /// apps/spikes/lib/glassos_probe.dart does not present a client cert on its
  /// gRPC channel (only on its standalone handshake test), and the task requires
  /// that unmodified probe to be able to call GetConsole. So the default is
  /// "request + validate the client cert if presented" (still genuine mutual TLS
  /// when a cert is offered, as the smoke test proves) without hard-requiring
  /// one. Set requireClientCert=true (CLI --require-client-cert) to model the
  /// real console's stricter behavior.
  final bool requireClientCert;
}

typedef LogSink = void Function(String message);

/// Host that owns the machine + faults and manages the gRPC [Server] lifecycle.
/// Stopping and restarting the transport (without discarding the machine) is how
/// we inject "server dies mid-command" and "link failure / reconnection".
class GlassOsMockHost {
  GlassOsMockHost({
    required this.tls,
    required this.machine,
    required this.faults,
    this.config = const MockServerConfig(),
    LogSink? log,
  }) : _log = log ?? print;

  final MutualTlsCredentials tls;
  final MockMachine machine;
  final FaultState faults;
  final MockServerConfig config;
  final LogSink _log;

  Server? _server;
  bool get isServing => _server != null;

  /// The port the transport is listening on, or null when down.
  int? get port => _server?.port;

  Future<void> start() async {
    if (_server != null) return;
    machine.start();
    final server = Server.create(services: _buildServices());
    await server.serve(
      address: InternetAddress.loopbackIPv4,
      port: config.port,
      security: tls,
      // Mutual TLS: always request the client certificate and (via the trusted
      // CA in MutualTlsCredentials) validate it when presented. requireClientCert
      // additionally aborts the handshake when no cert is offered - see the note
      // on MockServerConfig.requireClientCert.
      requestClientCertificate: true,
      requireClientCertificate: config.requireClientCert,
    );
    _server = server;
    _log('gRPC serving on localhost:${config.port} (mTLS, client cert required)');
  }

  /// Stops the transport but keeps the machine running. The belt physics tick
  /// keeps going, which is exactly what we want to characterize: what does the
  /// belt do while no client can reach it?
  Future<void> stop({String reason = 'stopped'}) async {
    final server = _server;
    if (server == null) return;
    _server = null;
    await server.shutdown();
    _log('gRPC transport down ($reason)');
  }

  /// Fault: the server process dies mid-command. The belt behavior follows the
  /// configured client-lost policy, because a dead server means a dead client
  /// connection too (docs/PLAN.md hazard row 1).
  Future<void> die() async {
    await stop(reason: 'server died (fault injection)');
    machine.onClientLost();
  }

  /// Fault: transient link failure then reconnection. Transport blips but the
  /// server is otherwise fine; belt state is untouched.
  Future<void> linkDrop({Duration down = const Duration(seconds: 2)}) async {
    await stop(reason: 'link failure (fault injection)');
    await Future<void>.delayed(down);
    await start();
    _log('link restored');
  }

  Future<void> dispose() async {
    await stop(reason: 'dispose');
    await machine.dispose();
  }

  // --- Service wiring ---

  List<Service> _buildServices() => [
    _RawService('com.ifit.glassos.ConsoleService', [
      _unary('GetConsole', _getConsole),
    ]),
    _RawService('com.ifit.glassos.SpeedService', [
      _unary('GetSpeed', _getSpeed),
      _unary('SetSpeed', _setSpeed),
      _serverStream('StreamSpeed', _streamSpeed),
    ]),
    _RawService('com.ifit.glassos.InclineService', [
      _unary('GetIncline', _getIncline),
      _unary('SetIncline', _setIncline),
      _serverStream('StreamIncline', _streamIncline),
    ]),
    _RawService('com.ifit.glassos.WorkoutService', [
      _unary('GetWorkoutState', _getWorkoutState),
      _unary('StartNewWorkout', _startWorkout),
      _unary('Pause', _pauseWorkout),
      _unary('Resume', _resumeWorkout),
      _unary('Stop', _stopWorkout),
      _serverStream('WorkoutStateChanged', _streamWorkout),
    ]),
  ];

  void _checkClientId(ServiceCall call, String method) {
    final id = call.clientMetadata?['client_id'];
    if (id == null) {
      if (config.enforceClientId) {
        throw GrpcError.unauthenticated('missing client_id metadata header');
      }
      _log('WARN $method: no client_id header');
      return;
    }
    if (id != config.expectedClientId) {
      // Warn only: the real value semantics are unconfirmed.
      _log('WARN $method: client_id="$id" (expected "${config.expectedClientId}")');
    }
  }

  // --- Console ---

  Future<List<int>> _getConsole(ServiceCall call, List<int> request) async {
    _checkClientId(call, 'GetConsole');
    return machine.console.toBytes();
  }

  // --- Speed ---

  Future<List<int>> _getSpeed(ServiceCall call, List<int> request) async {
    _checkClientId(call, 'GetSpeed');
    return SpeedSample(
      kph: machine.speedKph,
      targetKph: machine.speedKph,
      timestampMs: DateTime.now().millisecondsSinceEpoch,
    ).toBytes();
  }

  Future<List<int>> _setSpeed(ServiceCall call, List<int> request) async {
    _checkClientId(call, 'SetSpeed');
    final req = SetValueRequest.parse(request);
    final applied = machine.setSpeed(req.value ?? 0.0);
    return _ackOrFault(applied.appliedTarget, applied.generation);
  }

  Stream<List<int>> _streamSpeed(ServiceCall call, List<int> request) async* {
    _checkClientId(call, 'StreamSpeed');
    yield* machine.speedStream.map((s) => s.toBytes());
  }

  // --- Incline ---

  Future<List<int>> _getIncline(ServiceCall call, List<int> request) async {
    _checkClientId(call, 'GetIncline');
    return InclineSample(
      percent: machine.inclinePercent,
      targetPercent: machine.inclinePercent,
      timestampMs: DateTime.now().millisecondsSinceEpoch,
    ).toBytes();
  }

  Future<List<int>> _setIncline(ServiceCall call, List<int> request) async {
    _checkClientId(call, 'SetIncline');
    final req = SetValueRequest.parse(request);
    final applied = machine.setIncline(req.value ?? 0.0);
    return _ackOrFault(applied.appliedTarget, applied.generation);
  }

  Stream<List<int>> _streamIncline(ServiceCall call, List<int> request) async* {
    _checkClientId(call, 'StreamIncline');
    yield* machine.inclineStream.map((s) => s.toBytes());
  }

  // --- Workout ---

  Future<List<int>> _getWorkoutState(ServiceCall call, List<int> request) async {
    _checkClientId(call, 'GetWorkoutState');
    return WorkoutStateChanged(
      state: machine.workoutState,
      source: 'system',
      timestampMs: DateTime.now().millisecondsSinceEpoch,
    ).toBytes();
  }

  Future<List<int>> _startWorkout(ServiceCall call, List<int> request) async {
    _checkClientId(call, 'StartNewWorkout');
    machine.startWorkout();
    return _workoutAck();
  }

  Future<List<int>> _pauseWorkout(ServiceCall call, List<int> request) async {
    _checkClientId(call, 'Pause');
    machine.pauseWorkout();
    return _workoutAck();
  }

  Future<List<int>> _resumeWorkout(ServiceCall call, List<int> request) async {
    _checkClientId(call, 'Resume');
    machine.resumeWorkout();
    return _workoutAck();
  }

  Future<List<int>> _stopWorkout(ServiceCall call, List<int> request) async {
    _checkClientId(call, 'Stop');
    machine.stopWorkout();
    return _workoutAck();
  }

  Stream<List<int>> _streamWorkout(ServiceCall call, List<int> request) async* {
    _checkClientId(call, 'WorkoutStateChanged');
    // Emit the current state immediately so a late subscriber is not blind.
    yield WorkoutStateChanged(
      state: machine.workoutState,
      source: 'system',
      timestampMs: DateTime.now().millisecondsSinceEpoch,
    ).toBytes();
    yield* machine.workoutStream.map((e) => e.toBytes());
  }

  // --- Shared ack path with ack-related fault injection ---

  Future<List<int>> _ackOrFault(double appliedTarget, int generation) async {
    if (faults.ackDelay > Duration.zero) {
      await Future<void>.delayed(faults.ackDelay);
    }
    if (faults.dropAcks) {
      // The command already reached the belt (setSpeed/setIncline ran above);
      // the client just never gets a clean confirmation. This models a lost
      // reply on a flaky link - "no ack" in docs/PLAN.md hazard row 4.
      throw GrpcError.unavailable('ack dropped by fault injection');
    }
    return CommandAck(
      accepted: true,
      appliedTarget: appliedTarget,
      generation: generation,
    ).toBytes();
  }

  Future<List<int>> _workoutAck() async {
    if (faults.ackDelay > Duration.zero) {
      await Future<void>.delayed(faults.ackDelay);
    }
    if (faults.dropAcks) {
      throw GrpcError.unavailable('ack dropped by fault injection');
    }
    return WorkoutStateChanged(
      state: machine.workoutState,
      source: 'app',
      timestampMs: DateTime.now().millisecondsSinceEpoch,
    ).toBytes();
  }

  ServiceMethod<List<int>, List<int>> _unary(
    String name,
    Future<List<int>> Function(ServiceCall, List<int>) handler,
  ) {
    return ServiceMethod<List<int>, List<int>>(
      name,
      (ServiceCall call, Future<List<int>> request) async =>
          handler(call, await request),
      false,
      false,
      (List<int> b) => b,
      (List<int> b) => b,
    );
  }

  ServiceMethod<List<int>, List<int>> _serverStream(
    String name,
    Stream<List<int>> Function(ServiceCall, List<int>) handler,
  ) {
    return ServiceMethod<List<int>, List<int>>(
      name,
      (ServiceCall call, Future<List<int>> request) =>
          request.asStream().asyncExpand((r) => handler(call, r)),
      false,
      true,
      (List<int> b) => b,
      (List<int> b) => b,
    );
  }
}

/// A concrete gRPC [Service] whose name and methods are supplied directly, so we
/// can serve arbitrary `/package.Service/Method` paths with raw-byte codecs.
class _RawService extends Service {
  _RawService(this._name, List<ServiceMethod<List<int>, List<int>>> methods) {
    for (final m in methods) {
      $addMethod(m);
    }
  }

  final String _name;

  @override
  String get $name => _name;
}
