// Integration test for Bug 1: does the gRPC client actually PRESENT the client certificate on the
// wire?
//
// The rest of the S2 evidence for Bug 1 is a reading of grpc-4.3.1's source (connect() consulting
// `credentials.securityContext`). That is necessary but not sufficient: if the client cert is not
// really sent, spike S2 fails on the treadmill and we wrongly conclude GlassOS is unreachable - an
// existential wrong answer we cannot debug on-device. This test removes the reasoning and observes
// the handshake directly.
//
// It stands up a local TLS server that *requires* a client certificate (`requireClientCertificate:
// true`) on an ephemeral loopback port, drives the real production `MutualTlsCredentials` through a
// real grpc `ClientChannel`, and asserts the server reads back a peer certificate whose exact DER
// bytes equal the client cert we supplied. The negative control - stock
// `ChannelCredentials.secure(certificates: ...)`, the buggy path with no client key - must fail
// against the same server, otherwise the test proves nothing.
//
// The throwaway CA/server/client certs are generated at setup time into an OS temp directory and
// deleted afterwards, so nothing cert-shaped is ever written into the source tree. This keeps the
// test hermetic and avoids committing key material (docs/PLAN.md section 2.2 - never put GlassOS key
// material, or anything shaped like it, in the repo). Generation needs `openssl`; if it is absent
// the whole group is skipped with an install hint rather than failing opaquely. This test never
// prints key material.

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:grpc/grpc.dart';
import 'package:stride_spikes/glassos_probe.dart';

/// Strips PEM armor and whitespace and base64-decodes to the raw DER bytes. Comparing DER (rather
/// than re-encoded PEM text) makes the "same certificate" assertion exact and wrapping-insensitive.
Uint8List derOf(String pem) {
  final body = pem
      .replaceAll(RegExp(r'-----BEGIN [A-Z ]+-----'), '')
      .replaceAll(RegExp(r'-----END [A-Z ]+-----'), '')
      .replaceAll(RegExp(r'\s'), '');
  return base64.decode(body);
}

void main() {
  // Dart's SecurityContext consumes PEM but cannot mint certificates, so openssl is required to
  // build the fixture. Decide up front: if it is missing, skip the group with a clear reason.
  final opensslVersion = _detectOpenssl();
  final skipReason = opensslVersion == null
      ? 'openssl not found on PATH - install it (macOS: `brew install openssl`; Debian/Ubuntu: '
          '`apt-get install openssl`) to run the mTLS integration test'
      : null;

  late Directory tmp;
  late String caCrt;
  late String serverCrt;
  late String serverKey;
  late String clientCrt;
  late String clientKey;
  late Uint8List clientDer;

  setUpAll(() {
    // Generate into an OS temp dir; never touch the source tree.
    tmp = Directory.systemTemp.createTempSync('stride_mtls_');
    _generateFixtures(tmp);
    String read(String name) => File('${tmp.path}/$name').readAsStringSync();
    caCrt = read('ca.crt');
    serverCrt = read('server.crt');
    serverKey = read('server.key');
    clientCrt = read('client.crt');
    clientKey = read('client.key');
    clientDer = derOf(clientCrt);
  });

  tearDownAll(() {
    try {
      tmp.deleteSync(recursive: true);
    } catch (_) {
      // Best-effort cleanup; the OS reclaims its temp dir regardless.
    }
  });

  group('mTLS client certificate presentation', () {
    late SecureServerSocket server;
    late StreamSubscription<SecureSocket> sub;
    X509Certificate? capturedPeerCert;
    String? capturedAlpn;
    Object? capturedServerError;

    setUp(() async {
      capturedPeerCert = null;
      capturedAlpn = null;
      capturedServerError = null;

      final serverCtx = SecurityContext()
        ..useCertificateChainBytes(utf8.encode(serverCrt))
        ..usePrivateKeyBytes(utf8.encode(serverKey))
        // Trust our CA so the server can validate the client chain it demands.
        ..setTrustedCertificatesBytes(utf8.encode(caCrt))
        ..setAlpnProtocols(['h2'], true);

      server = await SecureServerSocket.bind(
        InternetAddress.loopbackIPv4,
        0, // ephemeral port
        serverCtx,
        requireClientCertificate: true,
        supportedProtocols: const ['h2'],
      );

      sub = server.listen(
        (socket) {
          // Reached only if the TLS handshake (including client-cert verification) succeeded.
          capturedPeerCert = socket.peerCertificate;
          capturedAlpn = socket.selectedProtocol;
          socket.destroy();
        },
        onError: (Object e) => capturedServerError = e,
      );
    });

    tearDown(() async {
      await sub.cancel();
      await server.close();
    });

    ClientChannel channelWith(ChannelCredentials creds) => ClientChannel(
          '127.0.0.1',
          port: server.port,
          options: ChannelOptions(
            credentials: creds,
            connectionTimeout: const Duration(seconds: 5),
          ),
        );

    // Triggers a real connection attempt over the channel. The placeholder method will error
    // because the server is not a real gRPC endpoint - that is fine and expected. All we need is for
    // grpc to perform the TLS handshake, which happens before any HTTP/2 traffic.
    //
    // The `.timeout` is a hard bound we impose ourselves: when the handshake fails, grpc-dart parks
    // the call and retries the connection with exponential backoff, and the CallOptions deadline
    // does not reliably fire while the channel is stuck reconnecting. For our purposes any of a
    // GrpcError, a HandshakeException, or a TimeoutException means the same thing - the RPC did NOT
    // succeed - so we return whichever surfaces first and never hang the test.
    Future<Object?> pokeAndCaptureError(ClientChannel channel) async {
      final method = ClientMethod<List<int>, List<int>>(
        '/probe.Handshake/Ping',
        (List<int> v) => v,
        (List<int> v) => v,
      );
      try {
        await channel
            .createCall(
              method,
              Stream<List<int>>.value(const <int>[]),
              CallOptions(timeout: const Duration(seconds: 5)),
            )
            .response
            .first
            .timeout(const Duration(seconds: 8));
        return null; // no error - only expected in a (broken) case where mTLS silently succeeded
      } catch (e) {
        return e;
      }
    }

    test('MutualTlsCredentials presents the exact client certificate to the server', () async {
      final channel = channelWith(
        MutualTlsCredentials(
          caBytes: utf8.encode(caCrt),
          certBytes: utf8.encode(clientCrt),
          keyBytes: utf8.encode(clientKey),
          authority: 'localhost',
          // Server cert is valid for localhost and CA-signed; accept anyway so this test is about
          // client-cert presentation, not server-cert policy (which glassos_probe_test covers).
          onBadCertificate: (cert, host) => true,
        ),
      );
      addTearDown(() => channel.shutdown());

      // The RPC itself fails (no real gRPC server); we only care that the handshake happened.
      await pokeAndCaptureError(channel);

      // Wait for the server to have accepted the completed handshake.
      await _until(() => capturedPeerCert != null || capturedServerError != null,
          timeout: const Duration(seconds: 5));

      expect(capturedServerError, isNull,
          reason: 'server-side handshake errored: $capturedServerError');
      expect(capturedPeerCert, isNotNull,
          reason: 'server required a client certificate but received none - the client channel did '
              'NOT present the certificate. This is exactly the Bug 1 failure.');

      // The decisive assertion: the certificate the server received is byte-for-byte the client
      // certificate we configured on the channel.
      expect(
        capturedPeerCert!.der,
        equals(clientDer),
        reason: 'the peer certificate the server saw is not the client cert we supplied',
      );

      // ALPN is the other thing that could silently differ on the Android 8/9 TLS stack.
      expect(capturedAlpn, 'h2', reason: 'expected HTTP/2 (h2) to be negotiated via ALPN');
    });

    test('negative control: stock ChannelCredentials.secure (no client key) is rejected', () async {
      // This is the buggy path Bug 1 fixed: ChannelCredentials.secure only sets trusted roots and
      // cannot attach a client key, so it presents no client certificate.
      final channel = channelWith(
        ChannelCredentials.secure(
          certificates: utf8.encode(caCrt),
          authority: 'localhost',
          onBadCertificate: (cert, host) => true,
        ),
      );
      addTearDown(() => channel.shutdown());

      final error = await pokeAndCaptureError(channel);

      // The RPC must not succeed: with no client cert, a requireClientCertificate server aborts the
      // handshake (the error surfaces as a GrpcError/HandshakeException, or as our own timeout while
      // grpc keeps retrying the doomed connection). If this "succeeds", the test proves nothing, so
      // we assert on it explicitly.
      expect(error, isNotNull,
          reason: 'negative control unexpectedly succeeded - the server did not enforce client '
              'certificates, so the positive test proves nothing');

      // The decisive contrast with the positive test: the server never accepted a completed client
      // handshake, so it never saw a peer certificate.
      expect(capturedPeerCert, isNull,
          reason: 'server captured a peer certificate without a client key configured');
    });
  }, skip: skipReason);
}

/// Returns the installed openssl version string, or null if openssl is not runnable on PATH.
String? _detectOpenssl() {
  try {
    final r = Process.runSync('openssl', ['version']);
    if (r.exitCode == 0) return (r.stdout as String).trim();
    return null;
  } on ProcessException {
    return null;
  }
}

/// Generates a throwaway CA plus CA-signed server and client leaf certificates into [dir].
///
/// Every certificate is created with `openssl req` -> CSR -> `openssl x509 -req -extfile`, which
/// only relies on portable subcommands (no `-addext`, which older openssl/LibreSSL builds lack). The
/// extensions matter for the Dart/BoringSSL TLS stack: the CA must carry keyCertSign, and the leaves
/// need the matching extendedKeyUsage, or verification fails. The client key is emitted as an
/// unencrypted PKCS#8 RSA key ("BEGIN PRIVATE KEY"), the same shape the probe expects on-device.
void _generateFixtures(Directory dir) {
  void run(List<String> args) {
    final r = Process.runSync('openssl', args, workingDirectory: dir.path);
    if (r.exitCode != 0) {
      // Surface stderr so a generation failure is diagnosable, never a silent half-fixture.
      throw StateError('openssl ${args.join(' ')} failed (${r.exitCode}): ${r.stderr}');
    }
  }

  File('${dir.path}/ca.ext').writeAsStringSync(
    'basicConstraints=critical,CA:TRUE\n'
    'keyUsage=critical,keyCertSign,cRLSign\n',
  );
  File('${dir.path}/server.ext').writeAsStringSync(
    'subjectAltName=DNS:localhost,IP:127.0.0.1\n'
    'basicConstraints=CA:FALSE\n'
    'keyUsage=critical,digitalSignature,keyEncipherment\n'
    'extendedKeyUsage=serverAuth\n',
  );
  File('${dir.path}/client.ext').writeAsStringSync(
    'basicConstraints=CA:FALSE\n'
    'keyUsage=critical,digitalSignature\n'
    'extendedKeyUsage=clientAuth\n',
  );

  const days = '3650';

  // Self-signed CA.
  run(['req', '-newkey', 'rsa:2048', '-nodes', '-keyout', 'ca.key', '-out', 'ca.csr',
      '-subj', '/CN=Stride Test CA']);
  run(['x509', '-req', '-in', 'ca.csr', '-signkey', 'ca.key', '-days', days,
      '-extfile', 'ca.ext', '-out', 'ca.crt']);

  // Server leaf, CN/SAN localhost, signed by the CA.
  run(['req', '-newkey', 'rsa:2048', '-nodes', '-keyout', 'server.key', '-out', 'server.csr',
      '-subj', '/CN=localhost']);
  run(['x509', '-req', '-in', 'server.csr', '-CA', 'ca.crt', '-CAkey', 'ca.key',
      '-CAcreateserial', '-days', days, '-extfile', 'server.ext', '-out', 'server.crt']);

  // Client leaf, signed by the CA.
  run(['req', '-newkey', 'rsa:2048', '-nodes', '-keyout', 'client.key', '-out', 'client.csr',
      '-subj', '/CN=stride-client']);
  run(['x509', '-req', '-in', 'client.csr', '-CA', 'ca.crt', '-CAkey', 'ca.key',
      '-CAcreateserial', '-days', days, '-extfile', 'client.ext', '-out', 'client.crt']);
}

/// Polls [condition] until true or [timeout] elapses. Returns normally either way; callers assert on
/// the observed state afterwards.
Future<void> _until(bool Function() condition, {required Duration timeout}) async {
  final deadline = DateTime.now().add(timeout);
  while (!condition() && DateTime.now().isBefore(deadline)) {
    await Future<void>.delayed(const Duration(milliseconds: 20));
  }
}
