/// S2 spike: prove mTLS + HTTP/2 + gRPC to the GlassOS service on the console.
///
/// Deliberately schema-free. The published `.proto` files live in a GPL-3 repo, and we do not know
/// the field numbers independently, so instead of copying them this probe:
///
///   1. connects with `ClientMethod<List<int>, List<int>>` and identity codecs, so no generated
///      stubs are needed at all;
///   2. sends an empty request to a documented method path;
///   3. hex-dumps the response and runs a *generic, heuristic* protobuf wire-format walker over it.
///
/// The wire walker recovers field numbers and wire types (those are self-describing), but it can
/// NOT recover the schema: it cannot tell a signed varint from an unsigned one, a float from an
/// int, or an embedded message from a byte array. It therefore reports every plausible reading of
/// each field rather than pretending to know which one is correct. See [ProtobufWireInspector].
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:grpc/grpc.dart';

class GlassOsProbeConfig {
  const GlassOsProbeConfig({
    this.host = 'localhost',
    this.port = 54321,
    this.clientId = 'com.ifit.dev_app',
    this.authority = 'localhost',
    this.timeout = const Duration(seconds: 10),
    this.streamMessageLimit = 3,
    this.streamTimeout = const Duration(seconds: 5),
  });

  final String host;
  final int port;

  /// GlassOS expects a `client_id` metadata header.
  final String clientId;
  final String authority;
  final Duration timeout;

  /// A server-streaming telemetry probe reads at most this many messages before unsubscribing, so
  /// the probe stays bounded and read-only.
  final int streamMessageLimit;
  final Duration streamTimeout;
}

class ProbeStep {
  ProbeStep(this.name, this.ok, this.detail);

  final String name;
  final bool ok;
  final String detail;

  @override
  String toString() => '${ok ? "PASS" : "FAIL"}  $name\n        $detail';
}

/// Result of trying to prove that a presented server chain is genuinely signed by the CA we
/// extracted, with the "localhost" hostname mismatch factored out.
enum _CaCheck {
  /// The chain verifies against the extracted CA (only the hostname was wrong, and that was
  /// accepted deliberately).
  verified,

  /// The chain does NOT verify against the extracted CA. This is a credential mismatch (S2b), not
  /// a hostname problem, and must not be reported as a passing handshake.
  mismatch,

  /// Verification could not be isolated (for example the server certificate exposed no usable CN to
  /// re-check the chain against). Treated as "not proven", and flagged loudly.
  inconclusive,
}

class _ServerCertReport {
  _ServerCertReport({
    required this.subject,
    required this.issuer,
    required this.notBefore,
    required this.notAfter,
    required this.fingerprintSha1,
    required this.caCheck,
    required this.caCheckDetail,
  });

  final String subject;
  final String issuer;
  final DateTime notBefore;
  final DateTime notAfter;
  final String fingerprintSha1;
  final _CaCheck caCheck;
  final String caCheckDetail;

  bool get expired {
    final now = DateTime.now();
    return now.isBefore(notBefore) || now.isAfter(notAfter);
  }
}

class GlassOsProbe {
  GlassOsProbe({
    required this.caCertPem,
    required this.clientCertPem,
    required this.clientKeyPem,
    this.config = const GlassOsProbeConfig(),
  });

  final String caCertPem;
  final String clientCertPem;
  final String clientKeyPem;
  final GlassOsProbeConfig config;

  /// Unary method paths are `/package.Service/Method`. These are the documented GlassOS services;
  /// the probe reports which ones respond so we learn the real surface on this firmware.
  static const List<String> candidateMethods = <String>[
    '/com.ifit.glassos.ConsoleService/GetConsole',
    '/com.ifit.glassos.SpeedService/GetSpeed',
    '/com.ifit.glassos.InclineService/GetIncline',
    '/com.ifit.glassos.WorkoutService/GetWorkoutState',
  ];

  /// Server-streaming candidates. Per PLAN.md section 6, S2 must confirm a telemetry subscription,
  /// not just unary reads. These are read-only subscriptions; the probe unsubscribes after a few
  /// messages. It never sends a control/command RPC.
  static const List<String> candidateStreamMethods = <String>[
    '/com.ifit.glassos.WorkoutService/WorkoutStateChanged',
    '/com.ifit.glassos.SpeedService/SpeedChanged',
    '/com.ifit.glassos.InclineService/InclineChanged',
  ];

  Future<List<ProbeStep>> run() async {
    final steps = <ProbeStep>[];

    // Step 1: raw TCP reachability. Distinguishes "nothing listening" from "TLS rejected".
    try {
      final socket = await Socket.connect(config.host, config.port, timeout: config.timeout);
      await socket.close();
      steps.add(ProbeStep('TCP connect ${config.host}:${config.port}', true, 'port is open'));
    } catch (e) {
      steps.add(ProbeStep('TCP connect ${config.host}:${config.port}', false, '$e'));
      steps.add(ProbeStep(
        'diagnosis',
        false,
        'Nothing is listening. Either GlassOS is not running, it is bound to a unix domain '
        'socket rather than TCP on this firmware, or SELinux is blocking a non-system app.',
      ));
      return steps;
    }

    // Step 2: mTLS handshake on its own, before involving gRPC. This isolates certificate problems
    // from HTTP/2 or ALPN problems, and - critically - proves whether the presented server chain is
    // actually signed by the CA we extracted, instead of blanket-accepting any certificate.
    _CaCheck caCheck;
    try {
      final report = await _inspectServerChain();
      caCheck = report.caCheck;

      final validity = report.expired
          ? 'validity: ${report.notBefore.toIso8601String()} to '
              '${report.notAfter.toIso8601String()}  *** OUTSIDE VALIDITY WINDOW ***'
          : 'validity: ${report.notBefore.toIso8601String()} to '
              '${report.notAfter.toIso8601String()}';

      final ok = report.caCheck == _CaCheck.verified;
      steps.add(ProbeStep(
        'mTLS handshake + CA verification',
        ok,
        'ALPN negotiated: h2\n        '
        'server subject: ${report.subject}\n        '
        'server issuer:  ${report.issuer}\n        '
        '$validity\n        '
        'SHA-1 fingerprint: ${report.fingerprintSha1}\n        '
        'CA check: ${report.caCheckDetail}',
      ));
    } catch (e) {
      steps.add(ProbeStep('mTLS handshake + CA verification', false, '$e'));
      steps.add(ProbeStep(
        'diagnosis',
        false,
        'The port is open but the TLS handshake failed. Most likely the extracted client '
        'certificate is not the one this console expects - see spike S2b on whether certs are '
        'per-device or shared.',
      ));
      return steps;
    }

    // If the chain is definitively NOT signed by our CA, refuse to proceed: reporting RPC results
    // over an unverified chain would convert a credential-selection error into a false S2 PASS.
    if (caCheck == _CaCheck.mismatch) {
      steps.add(ProbeStep(
        'gRPC probe',
        false,
        'Aborted. The server chain is not signed by the extracted CA, so any RPC "success" here '
        'would be meaningless. Re-run credential extraction (S2b: certs may be per-device).',
      ));
      return steps;
    }

    // Step 3: real gRPC calls. The channel is built with a ChannelCredentials subclass that injects
    // a full mutual-TLS SecurityContext (client cert + key), which the stock
    // ChannelCredentials.secure() cannot do. See MutualTlsCredentials for why this reaches the
    // connect path.
    final channel = _buildChannel(caCheck);

    for (final path in candidateMethods) {
      steps.add(await _unaryProbe(channel, path));
    }

    // Step 4a: telemetry stream subscription (server-streaming), read-only.
    for (final path in candidateStreamMethods) {
      steps.add(await _streamProbe(channel, path));
    }

    await channel.shutdown();

    // Step 4b: reconnect-after-failure. Tear the channel down (simulating a dropped connection /
    // GlassOS restart) and prove a fresh channel can re-establish mTLS and read again.
    steps.add(await _reconnectProbe(caCheck));

    return steps;
  }

  ClientChannel _buildChannel(_CaCheck caCheck) {
    return ClientChannel(
      config.host,
      port: config.port,
      options: ChannelOptions(
        credentials: MutualTlsCredentials(
          caBytes: utf8.encode(caCertPem),
          certBytes: utf8.encode(clientCertPem),
          keyBytes: utf8.encode(clientKeyPem),
          authority: config.authority,
          // Only accept the bad-certificate condition when we independently proved the chain is
          // signed by our CA. That leaves the "localhost" hostname mismatch as the sole reason the
          // platform rejected, which we accept knowingly. A genuinely wrong CA (mismatch) never
          // reaches here; an inconclusive check is allowed through so we can still gather wire data,
          // but Step 2 already flagged it.
          onBadCertificate: (cert, host) => caCheck != _CaCheck.mismatch,
        ),
        connectionTimeout: config.timeout,
      ),
    );
  }

  Future<ProbeStep> _unaryProbe(ClientChannel channel, String path) async {
    try {
      final call = channel.createCall(
        _identityMethod(path, false),
        Stream<List<int>>.value(const <int>[]),
        CallOptions(
          metadata: {'client_id': config.clientId},
          timeout: config.timeout,
        ),
      );
      final response = await call.response.first;
      final bytes = Uint8List.fromList(response);
      return ProbeStep(
        path,
        true,
        'received ${bytes.length} bytes\n${_indent(hexDump(bytes))}\n'
        '        heuristic wire inspection:\n${_indent(ProtobufWireInspector.describe(bytes))}',
      );
    } catch (e) {
      return ProbeStep(path, false, '$e');
    }
  }

  Future<ProbeStep> _streamProbe(ClientChannel channel, String path) async {
    try {
      final call = channel.createCall(
        _identityMethod(path, true),
        Stream<List<int>>.value(const <int>[]),
        CallOptions(
          metadata: {'client_id': config.clientId},
          // No overall call timeout: a telemetry stream stays open. We bound it ourselves below.
        ),
      );

      final received = <Uint8List>[];
      final sub = call.response.listen(
        (msg) => received.add(Uint8List.fromList(msg)),
        cancelOnError: true,
      );
      // Read a bounded number of messages, then unsubscribe. Read-only throughout.
      try {
        await Future.any<void>([
          () async {
            while (received.length < config.streamMessageLimit) {
              await Future<void>.delayed(const Duration(milliseconds: 50));
            }
          }(),
          Future<void>.delayed(config.streamTimeout),
        ]);
      } finally {
        await sub.cancel();
        await call.cancel();
      }

      if (received.isEmpty) {
        return ProbeStep(
          'stream $path',
          false,
          'subscribed but received no telemetry within ${config.streamTimeout.inSeconds}s',
        );
      }
      final first = received.first;
      return ProbeStep(
        'stream $path',
        true,
        'received ${received.length} message(s); first is ${first.length} bytes\n'
        '        heuristic wire inspection of first message:\n'
        '${_indent(ProtobufWireInspector.describe(first))}',
      );
    } catch (e) {
      return ProbeStep('stream $path', false, '$e');
    }
  }

  Future<ProbeStep> _reconnectProbe(_CaCheck caCheck) async {
    final channel = _buildChannel(caCheck);
    try {
      final call = channel.createCall(
        _identityMethod(candidateMethods.first, false),
        Stream<List<int>>.value(const <int>[]),
        CallOptions(
          metadata: {'client_id': config.clientId},
          timeout: config.timeout,
        ),
      );
      final bytes = Uint8List.fromList(await call.response.first);
      return ProbeStep(
        'reconnect after teardown',
        true,
        'fresh channel re-established mTLS and read ${bytes.length} bytes from '
        '${candidateMethods.first}',
      );
    } catch (e) {
      return ProbeStep('reconnect after teardown', false, '$e');
    } finally {
      await channel.shutdown();
    }
  }

  static ClientMethod<List<int>, List<int>> _identityMethod(String path, bool streaming) {
    return ClientMethod<List<int>, List<int>>(
      path,
      (List<int> value) => value,
      (List<int> value) => value,
    );
  }

  /// Performs a lenient handshake to capture the server certificate, then re-verifies the chain
  /// strictly against the extracted CA while supplying the certificate's own CN as the expected
  /// hostname. That isolates the deliberate "localhost" hostname mismatch from a genuine
  /// wrong-CA/untrusted-chain failure - the platform's own trust evaluation does the signature
  /// check, we just remove hostname from the equation.
  Future<_ServerCertReport> _inspectServerChain() async {
    final caBytes = utf8.encode(caCertPem);

    // Pass 1 (discovery): trust only our CA, but accept the (expected) hostname mismatch so we can
    // read the presented certificate.
    final lenientCtx = SecurityContext(withTrustedRoots: false)
      ..setTrustedCertificatesBytes(caBytes)
      ..useCertificateChainBytes(utf8.encode(clientCertPem))
      ..usePrivateKeyBytes(utf8.encode(clientKeyPem))
      ..setAlpnProtocols(['h2'], false);

    var badCertFired = false;
    final secure = await SecureSocket.connect(
      config.host,
      config.port,
      context: lenientCtx,
      timeout: config.timeout,
      supportedProtocols: const ['h2'],
      onBadCertificate: (cert) {
        badCertFired = true;
        return true;
      },
    );
    final peer = secure.peerCertificate;
    await secure.close();

    if (peer == null) {
      throw const HandshakeException('server presented no certificate');
    }

    final fingerprint = _hex(peer.sha1);

    // If the platform did not complain at all, the chain and hostname both validated against our CA.
    if (!badCertFired) {
      return _ServerCertReport(
        subject: peer.subject,
        issuer: peer.issuer,
        notBefore: peer.startValidity,
        notAfter: peer.endValidity,
        fingerprintSha1: fingerprint,
        caCheck: _CaCheck.verified,
        caCheckDetail: 'VERIFIED - chain signed by the extracted CA and hostname matched.',
      );
    }

    // Pass 2 (strict): re-verify against our CA using the certificate's own CN as the hostname, so
    // the only thing that can still fail is the chain of trust (or validity dates).
    final cn = _commonName(peer.subject);
    if (cn == null || cn.isEmpty) {
      return _ServerCertReport(
        subject: peer.subject,
        issuer: peer.issuer,
        notBefore: peer.startValidity,
        notAfter: peer.endValidity,
        fingerprintSha1: fingerprint,
        caCheck: _CaCheck.inconclusive,
        caCheckDetail:
            'INCONCLUSIVE - could not extract a CN from the server subject, so the chain could not '
            'be re-verified with hostname factored out. Do not treat downstream RPCs as CA-proven.',
      );
    }

    final strictCtx = SecurityContext(withTrustedRoots: false)
      ..setTrustedCertificatesBytes(caBytes)
      ..useCertificateChainBytes(utf8.encode(clientCertPem))
      ..usePrivateKeyBytes(utf8.encode(clientKeyPem))
      ..setAlpnProtocols(['h2'], false);

    try {
      final raw = await Socket.connect(config.host, config.port, timeout: config.timeout);
      final strict = await SecureSocket.secure(
        raw,
        host: cn,
        context: strictCtx,
        supportedProtocols: const ['h2'],
        onBadCertificate: (cert) => false,
      );
      await strict.close();
      return _ServerCertReport(
        subject: peer.subject,
        issuer: peer.issuer,
        notBefore: peer.startValidity,
        notAfter: peer.endValidity,
        fingerprintSha1: fingerprint,
        caCheck: _CaCheck.verified,
        caCheckDetail:
            'VERIFIED - chain signed by the extracted CA (re-checked against CN "$cn"). The '
            'hostname "${config.host}" mismatch was accepted deliberately.',
      );
    } on HandshakeException catch (e) {
      final now = DateTime.now();
      final dateProblem = now.isBefore(peer.startValidity) || now.isAfter(peer.endValidity);
      return _ServerCertReport(
        subject: peer.subject,
        issuer: peer.issuer,
        notBefore: peer.startValidity,
        notAfter: peer.endValidity,
        fingerprintSha1: fingerprint,
        caCheck: _CaCheck.mismatch,
        caCheckDetail: dateProblem
            ? 'MISMATCH - chain did not validate; the certificate is outside its validity window. '
                '($e)'
            : 'MISMATCH - the presented chain is NOT signed by the CA we extracted. This is a '
                'credential mismatch (see S2b), not a hostname problem. ($e)',
      );
    }
  }

  /// Extracts the Common Name from an X.509 subject string such as `/C=US/O=iFit/CN=console.local`
  /// or `CN=console.local,O=iFit`.
  static String? _commonName(String subject) {
    final match = RegExp(r'CN\s*=\s*([^/,]+)').firstMatch(subject);
    return match?.group(1)?.trim();
  }

  static String _hex(List<int> bytes) =>
      bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join(':');

  static String _indent(String s) =>
      s.split('\n').map((l) => '        $l').join('\n');

  static String hexDump(Uint8List data, {int maxBytes = 512}) {
    final buffer = StringBuffer();
    final limit = data.length > maxBytes ? maxBytes : data.length;
    for (var i = 0; i < limit; i += 16) {
      final end = (i + 16 > limit) ? limit : i + 16;
      final hex = <String>[];
      final ascii = StringBuffer();
      for (var j = i; j < end; j++) {
        hex.add(data[j].toRadixString(16).padLeft(2, '0'));
        final c = data[j];
        ascii.write(c >= 0x20 && c < 0x7F ? String.fromCharCode(c) : '.');
      }
      buffer.writeln(
        '${i.toRadixString(16).padLeft(4, '0')}  '
        '${hex.join(' ').padRight(47)}  $ascii',
      );
    }
    if (data.length > maxBytes) {
      buffer.writeln('... ${data.length - maxBytes} more bytes');
    }
    return buffer.toString().trimRight();
  }
}

/// A [ChannelCredentials] that injects a full mutual-TLS [SecurityContext] into the gRPC connect
/// path.
///
/// The stock `ChannelCredentials.secure(certificates: ...)` only sets *trusted roots* - grpc 4.3.1
/// exposes no client-cert/key parameter at all, so a channel built with it never presents the
/// client certificate and a correctly configured mTLS endpoint rejects every RPC.
///
/// `ChannelCredentials` is an ordinary subclassable class whose `securityContext` is a plain
/// getter, and `Http2ClientConnection.connect()` reads `credentials.securityContext` and hands the
/// result straight to `SecureSocket.secure(...)`. Overriding the getter therefore installs a
/// context carrying the CA (as trusted root), the client certificate chain, and the private key -
/// so the client certificate is presented on every RPC on that channel. `onBadCertificate` still
/// flows through `credentials.onBadCertificate`, which we set via the super constructor.
class MutualTlsCredentials extends ChannelCredentials {
  MutualTlsCredentials({
    required this.caBytes,
    required this.certBytes,
    required this.keyBytes,
    super.authority,
    super.onBadCertificate,
  }) : super.secure();

  final List<int> caBytes;
  final List<int> certBytes;
  final List<int> keyBytes;

  @override
  SecurityContext? get securityContext => SecurityContext(withTrustedRoots: false)
    ..setTrustedCertificatesBytes(caBytes)
    ..useCertificateChainBytes(certBytes)
    ..usePrivateKeyBytes(keyBytes)
    // grpc's connect() does not pass supportedProtocols, so ALPN must be set on the context.
    ..setAlpnProtocols(['h2'], false);
}

/// Result of reading a single base-128 varint.
class VarintReadResult {
  const VarintReadResult(this.bits, this.nextOffset);

  /// The raw 64 bits of the varint, stored in a Dart int (which is a 64-bit two's-complement
  /// integer). Interpret with the helpers on [ProtobufWireInspector] - the same bits mean different
  /// numbers depending on the (unknown) field type.
  final int bits;
  final int nextOffset;
}

/// Generic, heuristic protobuf wire-format inspector.
///
/// This is NOT schema recovery. The wire format encodes only a field number and a wire type; it
/// does not say whether a varint is signed, unsigned, zigzag, or a bool; whether a 32/64-bit field
/// is an integer or a float; or whether a length-delimited field is a string, raw bytes, or an
/// embedded message. This inspector therefore prints every plausible interpretation of each field
/// and lets a human decide. It rejects malformed input (over-long varints, truncated fields) rather
/// than guessing past it.
class ProtobufWireInspector {
  /// Human-readable dump of all plausible interpretations of [data].
  static String describe(Uint8List data, {int depth = 0}) {
    final out = StringBuffer();
    _decode(data, depth, out);
    return out.toString();
  }

  /// Returns true if [data] parses cleanly as a protobuf message with no leftover or malformed
  /// bytes. Used to decide whether a length-delimited field is *plausibly* an embedded message.
  static bool isWellFormedMessage(Uint8List data, {int maxDepth = 8}) {
    if (data.isEmpty) return false;
    return _validate(data, 0, maxDepth);
  }

  /// Reads a base-128 varint at [pos]. Returns null if the bytes are truncated or encode an
  /// over-long (more than 10-byte, or 10-byte-with-overflow) varint. Protobuf caps a varint at 64
  /// bits, i.e. at most 10 groups of 7 bits, and the 10th byte may only carry the single high bit.
  static VarintReadResult? readVarint(Uint8List d, int pos) {
    var result = 0;
    var shift = 0;
    var p = pos;
    var bytes = 0;
    while (p < d.length) {
      final byte = d[p];
      bytes++;
      if (bytes == 10) {
        // 9 * 7 = 63 bits already consumed; the final byte contributes bit 63 only. Any other bit
        // (0x7E) set, or a continuation bit, means the value does not fit in 64 bits.
        if ((byte & 0x7E) != 0) return null;
        if ((byte & 0x80) != 0) return null;
        result |= (byte & 0x01) << 63;
        return VarintReadResult(result, p + 1);
      }
      result |= (byte & 0x7F) << shift;
      p++;
      if (byte & 0x80 == 0) return VarintReadResult(result, p);
      shift += 7;
    }
    return null; // truncated: ran out of bytes with the continuation bit still set
  }

  static void _decode(Uint8List data, int depth, StringBuffer out) {
    final indent = '  ' * depth;
    var p = 0;

    while (p < data.length) {
      final key = readVarint(data, p);
      if (key == null) {
        out.writeln('$indent<malformed field key / over-long varint at offset $p>');
        return;
      }
      p = key.nextOffset;
      final fieldNumber = key.bits >> 3;
      final wireType = key.bits & 0x7;

      switch (wireType) {
        case 0: // varint
          final v = readVarint(data, p);
          if (v == null) {
            out.writeln('$indent<malformed varint for field $fieldNumber at offset $p>');
            return;
          }
          p = v.nextOffset;
          out.writeln('${indent}field $fieldNumber (wire 0, varint):');
          out.writeln('$indent  uint64        = ${unsigned64(v.bits)}');
          out.writeln('$indent  sint64/zigzag = ${zigzag64(v.bits)}');
          out.writeln('$indent  int64         = ${v.bits}');
          out.writeln('$indent  bool          = ${boolOf(v.bits)}');
        case 1: // 64-bit fixed
          if (p + 8 > data.length) {
            out.writeln('$indent<truncated fixed64 for field $fieldNumber at offset $p>');
            return;
          }
          final bd = ByteData.sublistView(data, p, p + 8);
          out.writeln('${indent}field $fieldNumber (wire 1, fixed64):');
          out.writeln('$indent  uint64 = ${bd.getUint64(0, Endian.little)}');
          out.writeln('$indent  int64  = ${bd.getInt64(0, Endian.little)}');
          out.writeln('$indent  double = ${bd.getFloat64(0, Endian.little)}');
          p += 8;
        case 2: // length-delimited
          final len = readVarint(data, p);
          if (len == null) {
            out.writeln('$indent<malformed length for field $fieldNumber at offset $p>');
            return;
          }
          p = len.nextOffset;
          final end = p + len.bits;
          if (len.bits < 0 || end > data.length) {
            out.writeln('$indent<truncated length-delimited field $fieldNumber '
                '(len ${len.bits}) at offset $p>');
            return;
          }
          final sub = Uint8List.sublistView(data, p, end);
          out.writeln('${indent}field $fieldNumber (wire 2, length-delimited, ${sub.length} bytes):');
          final text = _asPrintable(sub);
          if (text != null) {
            out.writeln('$indent  string = "$text"');
          }
          out.writeln('$indent  bytes  = ${_shortHex(sub)}');
          if (isWellFormedMessage(sub)) {
            out.writeln('$indent  message? (parses cleanly as a submessage):');
            _decode(sub, depth + 2, out);
          }
          p = end;
        case 5: // 32-bit fixed
          if (p + 4 > data.length) {
            out.writeln('$indent<truncated fixed32 for field $fieldNumber at offset $p>');
            return;
          }
          final bd = ByteData.sublistView(data, p, p + 4);
          out.writeln('${indent}field $fieldNumber (wire 5, fixed32):');
          out.writeln('$indent  uint32 = ${bd.getUint32(0, Endian.little)}');
          out.writeln('$indent  int32  = ${bd.getInt32(0, Endian.little)}');
          out.writeln('$indent  float  = ${bd.getFloat32(0, Endian.little)}');
          p += 4;
        case 3: // start group (deprecated)
        case 4: // end group (deprecated)
          out.writeln('$indent<legacy group wire type $wireType for field $fieldNumber; '
              'not decoded>');
          return;
        default:
          out.writeln('$indent<invalid wire type $wireType for field $fieldNumber at offset $p>');
          return;
      }
    }
  }

  /// Strict structural validation used to classify possible embedded messages. Returns false on any
  /// malformed or leftover byte.
  static bool _validate(Uint8List data, int depth, int maxDepth) {
    if (depth > maxDepth) return false;
    var p = 0;
    while (p < data.length) {
      final key = readVarint(data, p);
      if (key == null) return false;
      p = key.nextOffset;
      final wireType = key.bits & 0x7;
      switch (wireType) {
        case 0:
          final v = readVarint(data, p);
          if (v == null) return false;
          p = v.nextOffset;
        case 1:
          if (p + 8 > data.length) return false;
          p += 8;
        case 2:
          final len = readVarint(data, p);
          if (len == null) return false;
          p = len.nextOffset;
          if (len.bits < 0 || p + len.bits > data.length) return false;
          p += len.bits;
        case 5:
          if (p + 4 > data.length) return false;
          p += 4;
        default:
          // Groups (3/4) and invalid types (6/7) are not accepted as clean submessages.
          return false;
      }
    }
    return p == data.length;
  }

  /// Unsigned 64-bit reading of raw varint [bits]. Dart ints are signed, so a value with bit 63 set
  /// reads as negative; recover the true magnitude via BigInt for display.
  static String unsigned64(int bits) =>
      bits >= 0 ? '$bits' : (BigInt.from(bits) + (BigInt.one << 64)).toString();

  /// ZigZag decoding (proto sint32/sint64): `(n >>> 1) ^ -(n & 1)`.
  static int zigzag64(int bits) => (bits >>> 1) ^ -(bits & 1);

  static String boolOf(int bits) {
    if (bits == 0) return 'false';
    if (bits == 1) return 'true';
    return 'n/a (not 0 or 1)';
  }

  static String? _asPrintable(Uint8List b) {
    if (b.isEmpty) return '';
    var printable = 0;
    for (final c in b) {
      if (c == 0x09 || c == 0x0A || c == 0x0D || (c >= 0x20 && c < 0x7F)) printable++;
    }
    if (printable / b.length < 0.9) return null;
    try {
      return utf8.decode(b);
    } catch (_) {
      return null;
    }
  }

  static String _shortHex(Uint8List b, {int max = 32}) {
    final limit = b.length > max ? max : b.length;
    final hex = <String>[
      for (var i = 0; i < limit; i++) b[i].toRadixString(16).padLeft(2, '0'),
    ];
    return b.length > max ? '${hex.join(' ')} ... (+${b.length - max})' : hex.join(' ');
  }
}
