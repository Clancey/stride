/// S2 spike: prove mTLS + HTTP/2 + gRPC to the GlassOS service on the console.
///
/// Deliberately schema-free. The published `.proto` files live in a GPL-3 repo, and we do not know
/// the field numbers independently, so instead of copying them this probe:
///
///   1. connects with `ClientMethod<List<int>, List<int>>` and identity codecs, so no generated
///      stubs are needed at all;
///   2. sends an empty request to a documented method path;
///   3. hex-dumps the response and decodes it with a *generic* protobuf wire-format walker.
///
/// Protobuf's wire format is self-describing enough (field number + wire type) that the schema can
/// be recovered from real responses. That keeps Stride's eventual schema clean-room, and it is the
/// only way to learn the field numbers honestly.
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
  });

  final String host;
  final int port;

  /// GlassOS expects a `client_id` metadata header.
  final String clientId;
  final String authority;
  final Duration timeout;
}

class ProbeStep {
  ProbeStep(this.name, this.ok, this.detail);

  final String name;
  final bool ok;
  final String detail;

  @override
  String toString() => '${ok ? "PASS" : "FAIL"}  $name\n        $detail';
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

  /// Method paths are `/package.Service/Method`. These are the documented GlassOS services; the
  /// probe reports which ones respond so we learn the real surface on this firmware.
  static const List<String> candidateMethods = <String>[
    '/com.ifit.glassos.ConsoleService/GetConsole',
    '/com.ifit.glassos.SpeedService/GetSpeed',
    '/com.ifit.glassos.InclineService/GetIncline',
    '/com.ifit.glassos.WorkoutService/GetWorkoutState',
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

    // Step 2: mTLS handshake on its own, before involving gRPC.
    // This isolates certificate problems from HTTP/2 or ALPN problems.
    try {
      final context = SecurityContext(withTrustedRoots: false)
        ..setTrustedCertificatesBytes(utf8.encode(caCertPem))
        ..useCertificateChainBytes(utf8.encode(clientCertPem))
        ..usePrivateKeyBytes(utf8.encode(clientKeyPem));

      final secure = await SecureSocket.connect(
        config.host,
        config.port,
        context: context,
        timeout: config.timeout,
        supportedProtocols: const ['h2'],
        onBadCertificate: (cert) {
          // The server cert will not match "localhost"; record and accept for the probe only.
          return true;
        },
      );
      final negotiated = secure.selectedProtocol ?? '(none)';
      final peer = secure.peerCertificate;
      await secure.close();
      steps.add(ProbeStep(
        'mTLS handshake',
        true,
        'ALPN negotiated: $negotiated\n        '
        'peer subject: ${peer?.subject}\n        '
        'peer issuer: ${peer?.issuer}',
      ));
      if (negotiated != 'h2') {
        steps.add(ProbeStep(
          'ALPN h2',
          false,
          'Server did not negotiate h2. gRPC needs HTTP/2; check the Android 8/9 TLS stack.',
        ));
      }
    } catch (e) {
      steps.add(ProbeStep('mTLS handshake', false, '$e'));
      steps.add(ProbeStep(
        'diagnosis',
        false,
        'The port is open but the TLS handshake failed. Most likely the extracted client '
        'certificate is not the one this console expects - see spike S2b on whether certs are '
        'per-device or shared.',
      ));
      return steps;
    }

    // Step 3: real gRPC calls with identity codecs - no generated stubs, no GPL protos.
    final channel = ClientChannel(
      config.host,
      port: config.port,
      options: ChannelOptions(
        credentials: ChannelCredentials.secure(
          certificates: utf8.encode(caCertPem),
          authority: config.authority,
          onBadCertificate: (cert, host) => true,
        ),
        connectionTimeout: config.timeout,
      ),
    );

    for (final path in candidateMethods) {
      try {
        final method = ClientMethod<List<int>, List<int>>(
          path,
          (List<int> value) => value,
          (List<int> value) => value,
        );
        final call = channel.createCall(
          method,
          Stream<List<int>>.value(const <int>[]),
          CallOptions(
            metadata: {'client_id': config.clientId},
            timeout: config.timeout,
          ),
        );
        final response = await call.response.first;
        final bytes = Uint8List.fromList(response);
        steps.add(ProbeStep(
          path,
          true,
          'received ${bytes.length} bytes\n${_indent(hexDump(bytes))}\n'
          '        decoded:\n${_indent(ProtobufDump.describe(bytes))}',
        ));
      } catch (e) {
        steps.add(ProbeStep(path, false, '$e'));
      }
    }

    await channel.shutdown();
    return steps;
  }

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

/// Generic protobuf wire-format decoder.
///
/// Recovers field numbers, wire types, and plausible values from a message with no schema. This is
/// how Stride learns the GlassOS schema without copying anyone's `.proto` files.
class ProtobufDump {
  static String describe(Uint8List data, {int depth = 0}) {
    final buffer = StringBuffer();
    final indent = '  ' * depth;
    var p = 0;

    while (p < data.length) {
      final key = _readVarint(data, p);
      if (key == null) {
        buffer.writeln('$indent<malformed at $p>');
        break;
      }
      p = key.next;
      final fieldNumber = key.value >> 3;
      final wireType = key.value & 0x7;

      switch (wireType) {
        case 0: // varint
          final v = _readVarint(data, p);
          if (v == null) return buffer.toString();
          p = v.next;
          buffer.writeln('${indent}field $fieldNumber varint = ${v.value}');
        case 1: // 64-bit
          if (p + 8 > data.length) return buffer.toString();
          final bd = ByteData.sublistView(data, p, p + 8);
          buffer.writeln(
            '${indent}field $fieldNumber fixed64 = ${bd.getUint64(0, Endian.little)} '
            '(double ${bd.getFloat64(0, Endian.little)})',
          );
          p += 8;
        case 2: // length-delimited
          final len = _readVarint(data, p);
          if (len == null) return buffer.toString();
          p = len.next;
          final end = p + len.value;
          if (end > data.length) return buffer.toString();
          final sub = Uint8List.sublistView(data, p, end);
          final text = _asPrintable(sub);
          if (text != null) {
            buffer.writeln('${indent}field $fieldNumber string = "$text"');
          } else {
            buffer.writeln('${indent}field $fieldNumber message/bytes (${sub.length}):');
            buffer.write(describe(sub, depth: depth + 1));
          }
          p = end;
        case 5: // 32-bit
          if (p + 4 > data.length) return buffer.toString();
          final bd = ByteData.sublistView(data, p, p + 4);
          buffer.writeln(
            '${indent}field $fieldNumber fixed32 = ${bd.getUint32(0, Endian.little)} '
            '(float ${bd.getFloat32(0, Endian.little)})',
          );
          p += 4;
        default:
          buffer.writeln('$indent<unsupported wire type $wireType at $p>');
          return buffer.toString();
      }
    }
    return buffer.toString();
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

  static _Varint? _readVarint(Uint8List d, int pos) {
    var result = 0;
    var shift = 0;
    var p = pos;
    while (p < d.length) {
      final byte = d[p];
      result |= (byte & 0x7F) << shift;
      p++;
      if (byte & 0x80 == 0) return _Varint(result, p);
      shift += 7;
      if (shift > 63) return null;
    }
    return null;
  }
}

class _Varint {
  _Varint(this.value, this.next);

  final int value;
  final int next;
}
