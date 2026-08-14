/// S2 spike: locate and extract the GlassOS mTLS client credentials from the iFit console APK.
///
/// Background (docs/PLAN.md section 2.2): the iFit console package `com.ifit.rivendell` stores its
/// GlassOS client credentials inside its own APK, disguised as files with JPEG magic bytes. There
/// is no proprietary obfuscation involved - the payload is ordinary DER-encoded ASN.1, and this
/// extractor finds it by *structure* alone.
///
/// Deliberate design choices:
///   * Nothing is copied from any GPL project. This is a structural scanner written from the
///     public description of the technique.
///   * No certificate is ever bundled with Stride. Credentials are only ever read from the
///     console's own installed APK, at the user's explicit request.
///   * Output is written to app-private storage by the caller, never to /sdcard.
library;

import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:archive/archive.dart';

/// What a discovered DER blob appears to be.
enum DerKind { certificate, privateKeyPkcs8, privateKeyPkcs1, unknown }

class DerFinding {
  DerFinding({
    required this.kind,
    required this.bytes,
    required this.zipEntry,
    required this.offset,
    this.subject,
    this.issuer,
    this.selfSigned = false,
  });

  final DerKind kind;
  final Uint8List bytes;
  final String zipEntry;
  final int offset;
  final String? subject;
  final String? issuer;
  final bool selfSigned;

  String get pemLabel => switch (kind) {
        DerKind.certificate => 'CERTIFICATE',
        DerKind.privateKeyPkcs8 => 'PRIVATE KEY',
        DerKind.privateKeyPkcs1 => 'RSA PRIVATE KEY',
        DerKind.unknown => 'UNKNOWN',
      };

  /// Standard PEM encoding, 64-character lines.
  String toPem() {
    final b64 = base64.encode(bytes);
    final buffer = StringBuffer('-----BEGIN $pemLabel-----\n');
    for (var i = 0; i < b64.length; i += 64) {
      buffer.writeln(b64.substring(i, i + 64 > b64.length ? b64.length : i + 64));
    }
    buffer.write('-----END $pemLabel-----\n');
    return buffer.toString();
  }

  String describe() {
    final where = '$zipEntry@$offset';
    return switch (kind) {
      DerKind.certificate =>
        'certificate ${selfSigned ? "(self-signed / CA)" : "(leaf)"} '
            'subject=${subject ?? "?"} issuer=${issuer ?? "?"} [$where]',
      DerKind.privateKeyPkcs8 => 'PKCS#8 private key (${bytes.length} bytes) [$where]',
      DerKind.privateKeyPkcs1 => 'PKCS#1 RSA private key (${bytes.length} bytes) [$where]',
      DerKind.unknown => 'unknown DER blob (${bytes.length} bytes) [$where]',
    };
  }
}

class ExtractionResult {
  ExtractionResult({required this.findings, required this.log});

  final List<DerFinding> findings;
  final List<String> log;

  List<DerFinding> get certificates =>
      findings.where((f) => f.kind == DerKind.certificate).toList();

  List<DerFinding> get privateKeys => findings
      .where((f) =>
          f.kind == DerKind.privateKeyPkcs8 || f.kind == DerKind.privateKeyPkcs1)
      .toList();

  DerFinding? get caCertificate {
    final certs = certificates;
    for (final c in certs) {
      if (c.selfSigned) return c;
    }
    return certs.isEmpty ? null : certs.last;
  }

  DerFinding? get clientCertificate {
    final certs = certificates;
    for (final c in certs) {
      if (!c.selfSigned) return c;
    }
    return certs.isEmpty ? null : certs.first;
  }

  DerFinding? get clientKey => privateKeys.isEmpty ? null : privateKeys.first;

  bool get isComplete =>
      caCertificate != null && clientCertificate != null && clientKey != null;
}

class CertExtractor {
  /// Entries larger than this are skipped - credentials are small, and scanning multi-megabyte
  /// resources (dex, images, native libs) wastes time on a slow console SoC.
  static const int maxEntryBytes = 256 * 1024;

  /// Smallest plausible DER blob worth reporting.
  static const int minBlobBytes = 256;

  static Future<ExtractionResult> extractFromApk(String apkPath) async {
    final log = <String>[];
    final findings = <DerFinding>[];

    final file = File(apkPath);
    if (!await file.exists()) {
      log.add('APK not found: $apkPath');
      return ExtractionResult(findings: findings, log: log);
    }

    log.add('Reading $apkPath (${await file.length()} bytes)');
    final archive = ZipDecoder().decodeBytes(await file.readAsBytes(), verify: false);
    log.add('Archive contains ${archive.files.length} entries');

    var scanned = 0;
    var skipped = 0;
    for (final entry in archive.files) {
      if (!entry.isFile) continue;
      if (entry.size > maxEntryBytes) {
        skipped++;
        continue;
      }
      final bytes = entry.readBytes();
      if (bytes == null || bytes.length < minBlobBytes) continue;
      scanned++;

      for (final finding in _scanBytes(bytes, entry.name)) {
        findings.add(finding);
        log.add('FOUND ${finding.describe()}');
      }
    }

    log.add('Scanned $scanned entries, skipped $skipped oversized entries');
    log.add('Total findings: ${findings.length}');
    return ExtractionResult(findings: findings, log: log);
  }

  /// Scan a byte range for DER SEQUENCE headers that plausibly begin a certificate or key.
  ///
  /// The credentials are stored behind fake JPEG magic, so the payload does not start at offset 0.
  /// Rather than special-casing JPEG, this scans every position - correct regardless of what
  /// wrapper iFit uses now or later.
  static List<DerFinding> _scanBytes(Uint8List data, String entryName) {
    final out = <DerFinding>[];
    final seen = <int>{};

    for (var i = 0; i + 4 < data.length; i++) {
      // DER SEQUENCE with a 2-byte definite length: 0x30 0x82 hi lo
      if (data[i] != 0x30 || data[i + 1] != 0x82) continue;

      final declared = (data[i + 2] << 8) | data[i + 3];
      final total = declared + 4;
      if (declared < minBlobBytes) continue;
      if (i + total > data.length) continue;
      if (seen.contains(i)) continue;

      final blob = Uint8List.sublistView(data, i, i + total);
      final kind = _classify(blob);
      if (kind == DerKind.unknown) continue;

      seen.add(i);
      if (kind == DerKind.certificate) {
        final names = _certificateNames(blob);
        out.add(DerFinding(
          kind: kind,
          bytes: blob,
          zipEntry: entryName,
          offset: i,
          subject: names?.subject,
          issuer: names?.issuer,
          selfSigned: names != null && names.issuerRaw == names.subjectRaw,
        ));
      } else {
        out.add(DerFinding(kind: kind, bytes: blob, zipEntry: entryName, offset: i));
      }
      // Skip past this blob; nested matches inside a cert are not separate credentials.
      i += total - 1;
    }
    return out;
  }

  // ------------------------------------------------------------------ DER walking

  static DerKind _classify(Uint8List blob) {
    final children = _children(blob, 0);
    if (children == null || children.isEmpty) return DerKind.unknown;

    // X.509 Certificate ::= SEQUENCE { tbsCertificate SEQUENCE,
    //                                  signatureAlgorithm SEQUENCE,
    //                                  signatureValue BIT STRING }
    if (children.length == 3 &&
        children[0].tag == 0x30 &&
        children[1].tag == 0x30 &&
        children[2].tag == 0x03) {
      return DerKind.certificate;
    }

    // PKCS#8 PrivateKeyInfo ::= SEQUENCE { version INTEGER,
    //                                      privateKeyAlgorithm SEQUENCE,
    //                                      privateKey OCTET STRING }
    if (children.length >= 3 &&
        children[0].tag == 0x02 &&
        children[1].tag == 0x30 &&
        children[2].tag == 0x04) {
      return DerKind.privateKeyPkcs8;
    }

    // PKCS#1 RSAPrivateKey ::= SEQUENCE of 9 INTEGERs
    if (children.length == 9 && children.every((c) => c.tag == 0x02)) {
      return DerKind.privateKeyPkcs1;
    }

    return DerKind.unknown;
  }

  static _CertNames? _certificateNames(Uint8List cert) {
    final top = _children(cert, 0);
    if (top == null || top.isEmpty) return null;
    final tbs = top.first;

    final tbsChildren = _childrenInRange(cert, tbs.contentStart, tbs.contentEnd);
    if (tbsChildren == null) return null;

    // Skip the optional [0] EXPLICIT version tag.
    var idx = 0;
    if (tbsChildren.isNotEmpty && tbsChildren[0].tag == 0xA0) idx = 1;

    // serialNumber, signature, issuer, validity, subject
    final issuerIdx = idx + 2;
    final subjectIdx = idx + 4;
    if (subjectIdx >= tbsChildren.length) return null;

    final issuer = tbsChildren[issuerIdx];
    final subject = tbsChildren[subjectIdx];
    if (issuer.tag != 0x30 || subject.tag != 0x30) return null;

    return _CertNames(
      issuerRaw: _hex(cert, issuer.start, issuer.contentEnd),
      subjectRaw: _hex(cert, subject.start, subject.contentEnd),
      issuer: _commonName(cert, issuer),
      subject: _commonName(cert, subject),
    );
  }

  /// Pull the first printable string out of an X.501 Name, which in practice reads as the CN.
  static String? _commonName(Uint8List data, _Tlv name) {
    final rdns = _childrenInRange(data, name.contentStart, name.contentEnd);
    if (rdns == null) return null;
    String? last;
    for (final rdn in rdns) {
      final attrs = _childrenInRange(data, rdn.contentStart, rdn.contentEnd);
      if (attrs == null) continue;
      for (final attr in attrs) {
        final parts = _childrenInRange(data, attr.contentStart, attr.contentEnd);
        if (parts == null || parts.length < 2) continue;
        final value = parts[1];
        // UTF8String, PrintableString, IA5String, T61String
        if (value.tag == 0x0C ||
            value.tag == 0x13 ||
            value.tag == 0x16 ||
            value.tag == 0x14) {
          final text = String.fromCharCodes(
            data.sublist(value.contentStart, value.contentEnd),
          );
          last = text;
        }
      }
    }
    return last;
  }

  static String _hex(Uint8List d, int start, int end) {
    final b = StringBuffer();
    for (var i = start; i < end; i++) {
      b.write(d[i].toRadixString(16).padLeft(2, '0'));
    }
    return b.toString();
  }

  /// Parse the immediate children within an explicit content range.
  static List<_Tlv>? _childrenInRange(Uint8List d, int start, int end) {
    final out = <_Tlv>[];
    var p = start;
    while (p < end) {
      final tlv = _readTlv(d, p, end);
      if (tlv == null) return out.isEmpty ? null : out;
      out.add(tlv);
      p = tlv.contentEnd;
    }
    return out.isEmpty ? null : out;
  }

  /// Parse the immediate children of the constructed TLV that begins at [outerStart].
  static List<_Tlv>? _children(Uint8List d, int outerStart) {
    final outer = _readTlv(d, outerStart, d.length);
    if (outer == null) return null;
    return _childrenInRange(d, outer.contentStart, outer.contentEnd);
  }

  static _Tlv? _readTlv(Uint8List d, int pos, int limit) {
    if (pos + 2 > limit) return null;
    final tag = d[pos];
    var p = pos + 1;
    var length = d[p];
    p++;
    if (length & 0x80 != 0) {
      final count = length & 0x7F;
      if (count == 0 || count > 4 || p + count > limit) return null;
      length = 0;
      for (var i = 0; i < count; i++) {
        length = (length << 8) | d[p + i];
      }
      p += count;
    }
    final contentStart = p;
    final contentEnd = p + length;
    if (contentEnd > limit) return null;
    return _Tlv(
      tag: tag,
      start: pos,
      contentStart: contentStart,
      contentEnd: contentEnd,
    );
  }
}

class _Tlv {
  _Tlv({
    required this.tag,
    required this.start,
    required this.contentStart,
    required this.contentEnd,
  });

  final int tag;
  final int start;
  final int contentStart;
  final int contentEnd;
}

class _CertNames {
  _CertNames({
    required this.issuerRaw,
    required this.subjectRaw,
    required this.issuer,
    required this.subject,
  });

  final String issuerRaw;
  final String subjectRaw;
  final String? issuer;
  final String? subject;
}
