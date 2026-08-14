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
///
/// Correctness stance: a confidently wrong credential set produces an S2 failure that looks like a
/// transport problem and wastes hours. So selection here never guesses. It requires two positive,
/// checkable signals - the private key's RSA modulus must equal the client certificate's public
/// modulus, and the client certificate's issuer name must equal a present CA certificate's subject
/// name - and reports an honest "ambiguous" or "incomplete" (with a reason) when it cannot.
library;

import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:archive/archive.dart';

/// What a discovered DER blob appears to be.
enum DerKind { certificate, privateKeyPkcs8, privateKeyPkcs1, unknown }

/// Public-key algorithm of a certificate or private key, as far as the scanner can tell from the
/// AlgorithmIdentifier OID. `unknown` means the OID was absent or unrecognized.
enum KeyAlgorithm { rsa, ec, unknown }

/// Outcome of trying to pick a single (CA, client cert, client key) set from the findings.
enum SelectionConfidence {
  /// A single set was found and the key is cryptographically bound to the client certificate.
  confident,

  /// More than one plausible set exists; refusing to guess.
  ambiguous,

  /// A required piece is missing or could not be bound.
  incomplete,
}

class DerFinding {
  DerFinding({
    required this.kind,
    required this.bytes,
    required this.zipEntry,
    required this.offset,
    this.subject,
    this.issuer,
    this.selfSigned = false,
    this.keyAlgorithm = KeyAlgorithm.unknown,
    this.subjectDer,
    this.issuerDer,
    this.rsaModulus,
  });

  final DerKind kind;
  final Uint8List bytes;
  final String zipEntry;
  final int offset;
  final String? subject;
  final String? issuer;
  final bool selfSigned;

  /// RSA vs EC vs unknown. Set for certificates (from SubjectPublicKeyInfo) and for private keys
  /// (from PrivateKeyInfo). EC material is classified and reported but cannot be modulus-bound.
  final KeyAlgorithm keyAlgorithm;

  /// Hex of the exact issuer / subject Name DER (tag+length+content). Chaining compares these raw
  /// encodings, not the pretty-printed CN, because only the exact DER is what a TLS stack matches.
  final String? subjectDer;
  final String? issuerDer;

  /// Normalized RSA modulus (leading zero bytes stripped) from the certificate's public key or the
  /// private key. Null for EC or unparseable material. This is the value used for binding.
  final Uint8List? rsaModulus;

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
    final alg = switch (keyAlgorithm) {
      KeyAlgorithm.rsa => 'RSA',
      KeyAlgorithm.ec => 'EC',
      KeyAlgorithm.unknown => 'unknown-alg',
    };
    return switch (kind) {
      DerKind.certificate =>
        'certificate ${selfSigned ? "(self-signed / CA)" : "(leaf)"} $alg '
            'subject=${subject ?? "?"} issuer=${issuer ?? "?"} [$where]',
      // Never include byte contents of a private key; only its shape and algorithm.
      DerKind.privateKeyPkcs8 => 'PKCS#8 private key $alg (${bytes.length} bytes) [$where]',
      DerKind.privateKeyPkcs1 => 'PKCS#1 RSA private key (${bytes.length} bytes) [$where]',
      DerKind.unknown => 'unknown DER blob (${bytes.length} bytes) [$where]',
    };
  }
}

/// The chosen credential set, or an explanation of why no confident choice was possible.
class CredentialSelection {
  CredentialSelection({
    required this.confidence,
    required this.reason,
    this.ca,
    this.client,
    this.key,
    this.bindingVerified = false,
  });

  final SelectionConfidence confidence;

  /// Human-readable justification. On success it states the two signals that were satisfied; on
  /// failure it states exactly what was missing or ambiguous so an S2 failure is diagnosable.
  final String reason;

  final DerFinding? ca;
  final DerFinding? client;
  final DerFinding? key;

  /// True only when the client key's RSA modulus was proven equal to the client certificate's.
  final bool bindingVerified;

  bool get isConfident => confidence == SelectionConfidence.confident;
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

  CredentialSelection? _cachedSelection;

  /// Lazily computed, cached. Safe to call repeatedly and after merging findings from several
  /// APK splits, because it depends only on [findings].
  CredentialSelection get selection => _cachedSelection ??= _select();

  DerFinding? get caCertificate => selection.ca;
  DerFinding? get clientCertificate => selection.client;
  DerFinding? get clientKey => selection.key;

  /// Complete means: a single confident set was selected AND the key is bound to the client cert.
  /// An ambiguous or incomplete result is deliberately not "complete" - writing the wrong pair is
  /// worse than writing nothing.
  bool get isComplete => selection.isConfident;

  CredentialSelection _select() {
    final certs = certificates;
    final keys = privateKeys;
    if (certs.isEmpty || keys.isEmpty) {
      return CredentialSelection(
        confidence: SelectionConfidence.incomplete,
        reason: 'need at least one certificate and one private key; found '
            '${certs.length} certificate(s) and ${keys.length} key(s)',
      );
    }

    // Signal 1: bind a private key to a certificate by equal RSA modulus. This is the strongest
    // correctness signal available without full signature verification - it turns "hope we picked
    // the right pair" into a checkable fact.
    final pairs = <_BoundPair>[];
    for (final k in keys) {
      final km = k.rsaModulus;
      if (km == null) continue; // EC or unparseable key: cannot modulus-bind.
      for (final c in certs) {
        final cm = c.rsaModulus;
        if (cm != null && _bytesEqual(cm, km)) {
          pairs.add(_BoundPair(client: c, key: k));
        }
      }
    }

    if (pairs.isEmpty) {
      final hasEc = keys.any((k) => k.keyAlgorithm == KeyAlgorithm.ec) ||
          certs.any((c) => c.keyAlgorithm == KeyAlgorithm.ec);
      return CredentialSelection(
        confidence: SelectionConfidence.incomplete,
        reason: hasEc
            ? 'no RSA key/certificate modulus pair matched; EC material is present but EC binding '
                'is not implemented, so the key could not be bound to a certificate'
            : 'no private key modulus matches any certificate public modulus; cannot bind a client '
                'certificate to a key',
      );
    }

    // Signal 2: chain the bound client certificate to a *different* certificate whose subject Name
    // DER equals the client certificate's issuer Name DER. This is what makes the CA the CA, rather
    // than "whatever happened to be self-signed" (which would also match the APK signing cert).
    final chains = <_Chain>[];
    for (final pr in pairs) {
      final issuer = pr.client.issuerDer;
      if (issuer == null) continue;
      for (final c in certs) {
        if (identical(c, pr.client)) continue;
        if (c.subjectDer != null && c.subjectDer == issuer) {
          chains.add(_Chain(client: pr.client, key: pr.key, ca: c));
        }
      }
    }

    if (chains.isEmpty) {
      return CredentialSelection(
        confidence: SelectionConfidence.incomplete,
        reason: 'found ${pairs.length} bound key/certificate pair(s), but none is issued by a CA '
            'certificate also present in the archive; cannot establish a chain',
      );
    }

    final distinctClients = <String>{};
    for (final ch in chains) {
      distinctClients.add(ch.client.subjectDer ?? _hexBytes(ch.client.bytes));
    }
    if (distinctClients.length > 1) {
      return CredentialSelection(
        confidence: SelectionConfidence.ambiguous,
        reason: 'multiple distinct client certificates (${distinctClients.length}) are each bound '
            'to a key and chain to a present CA; refusing to guess which is the GlassOS client',
      );
    }

    final caCerts = <DerFinding>[];
    for (final ch in chains) {
      if (!caCerts.any((e) => identical(e, ch.ca))) caCerts.add(ch.ca);
    }
    if (caCerts.length > 1) {
      return CredentialSelection(
        confidence: SelectionConfidence.ambiguous,
        reason: 'the client certificate chains to ${caCerts.length} different CA certificates with '
            'the same subject; refusing to guess which CA to trust',
      );
    }

    final chosen = chains.first;
    return CredentialSelection(
      confidence: SelectionConfidence.confident,
      bindingVerified: true,
      ca: chosen.ca,
      client: chosen.client,
      key: chosen.key,
      reason: 'client key modulus matches the client certificate, and the client certificate '
          'issuer matches the CA subject',
    );
  }

  static bool _bytesEqual(Uint8List a, Uint8List b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }

  static String _hexBytes(Uint8List b) {
    final s = StringBuffer();
    for (final x in b) {
      s.write(x.toRadixString(16).padLeft(2, '0'));
    }
    return s.toString();
  }
}

class _BoundPair {
  _BoundPair({required this.client, required this.key});
  final DerFinding client;
  final DerFinding key;
}

class _Chain {
  _Chain({required this.client, required this.key, required this.ca});
  final DerFinding client;
  final DerFinding key;
  final DerFinding ca;
}

class CertExtractor {
  /// Entries larger than this are skipped. The credentials we want (a CA cert, a client cert, a
  /// PKCS#8 key, at most a small PKCS#7 bundle) are only a few kilobytes each; 256 KiB is far above
  /// any of them while still excluding the multi-megabyte dex, native libs, and image assets that
  /// dominate a real APK. Scanning those byte-by-byte would be pointless work on a slow console
  /// SoC. The count of skipped entries is reported in the log so a "found nothing" run is
  /// diagnosable rather than silent.
  static const int maxEntryBytes = 256 * 1024;

  /// Smallest DER blob worth reporting, measured on the SEQUENCE *content* length. Kept low enough
  /// that a compact EC private key or EC certificate (a couple hundred bytes) is not excluded, but
  /// high enough to skip trivial nested structures. Real credentials comfortably exceed this.
  static const int minBlobBytes = 64;

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
    var skippedOversized = 0;
    var skippedMetaInf = 0;
    for (final entry in archive.files) {
      if (!entry.isFile) continue;
      // META-INF holds the APK signing block (the *.RSA / *.SF / MANIFEST.MF), not application
      // data. Its *.RSA is a PKCS#7 wrapping the app's self-signed code-signing certificate, which
      // is structurally indistinguishable from a real CA cert and would otherwise be mis-selected
      // as the GlassOS CA. Skip the whole directory.
      if (entry.name.startsWith('META-INF/')) {
        skippedMetaInf++;
        continue;
      }
      if (entry.size > maxEntryBytes) {
        skippedOversized++;
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

    log.add('Scanned $scanned entries; skipped $skippedOversized oversized, '
        '$skippedMetaInf META-INF entries');
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

    for (var i = 0; i < data.length; i++) {
      // A certificate and every key form we care about is a top-level SEQUENCE (0x30). We accept
      // *any* definite length encoding here (short form and long forms 0x81..0x84) via _readTlv;
      // restricting to 0x30 0x82 as an earlier version did silently dropped every DER object under
      // 256 bytes, which includes compact EC keys and small certificates.
      if (data[i] != 0x30) continue;
      final tlv = _readTlv(data, i, data.length);
      if (tlv == null) continue;

      final contentLen = tlv.contentEnd - tlv.contentStart;
      if (contentLen < minBlobBytes) continue;

      final blob = Uint8List.sublistView(data, i, tlv.contentEnd);
      final kind = _classify(blob);
      if (kind == DerKind.unknown) continue;

      if (kind == DerKind.certificate) {
        final names = _certificateNames(blob);
        final pub = _certificatePublicKey(blob);
        out.add(DerFinding(
          kind: kind,
          bytes: blob,
          zipEntry: entryName,
          offset: i,
          subject: names?.subject,
          issuer: names?.issuer,
          subjectDer: names?.subjectRaw,
          issuerDer: names?.issuerRaw,
          selfSigned: names != null && names.issuerRaw == names.subjectRaw,
          keyAlgorithm: pub.algorithm,
          rsaModulus: pub.modulus,
        ));
      } else {
        final key = _privateKeyInfo(blob, kind);
        out.add(DerFinding(
          kind: kind,
          bytes: blob,
          zipEntry: entryName,
          offset: i,
          keyAlgorithm: key.algorithm,
          rsaModulus: key.modulus,
        ));
      }
      // Skip past this blob; nested matches inside a cert are not separate credentials.
      i = tlv.contentEnd - 1;
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
    // This shape matches both RSA and EC keys; the algorithm is distinguished later by OID.
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
    final tbsChildren = _tbsChildren(cert);
    if (tbsChildren == null) return null;

    // Skip the optional [0] EXPLICIT version tag.
    var idx = 0;
    if (tbsChildren.isNotEmpty && tbsChildren[0].tag == 0xA0) idx = 1;

    // TBSCertificate fields: serialNumber, signature, issuer, validity, subject, ...
    final issuerIdx = idx + 2;
    final subjectIdx = idx + 4;
    if (subjectIdx >= tbsChildren.length) return null;

    final issuer = tbsChildren[issuerIdx];
    final subject = tbsChildren[subjectIdx];
    if (issuer.tag != 0x30 || subject.tag != 0x30) return null;

    return _CertNames(
      // The full Name DER including tag+length is what a TLS stack compares for chaining, so hash
      // the exact bytes from the tag byte (issuer.start) through the content end.
      issuerRaw: _hex(cert, issuer.start, issuer.contentEnd),
      subjectRaw: _hex(cert, subject.start, subject.contentEnd),
      issuer: _commonName(cert, issuer),
      subject: _commonName(cert, subject),
    );
  }

  /// Extract the RSA modulus / EC classification from a certificate's SubjectPublicKeyInfo.
  ///
  /// SubjectPublicKeyInfo ::= SEQUENCE { algorithm AlgorithmIdentifier,
  ///                                     subjectPublicKey BIT STRING }
  /// For RSA the BIT STRING content is a leading 0x00 unused-bits byte followed by
  ///   RSAPublicKey ::= SEQUENCE { modulus INTEGER, publicExponent INTEGER }.
  static _PublicKeyInfo _certificatePublicKey(Uint8List cert) {
    final tbsChildren = _tbsChildren(cert);
    if (tbsChildren == null) return const _PublicKeyInfo(KeyAlgorithm.unknown, null);

    var idx = 0;
    if (tbsChildren.isNotEmpty && tbsChildren[0].tag == 0xA0) idx = 1;
    final spkiIdx = idx + 5; // serial, sigAlg, issuer, validity, subject, spki
    if (spkiIdx >= tbsChildren.length) {
      return const _PublicKeyInfo(KeyAlgorithm.unknown, null);
    }

    final spki = tbsChildren[spkiIdx];
    if (spki.tag != 0x30) return const _PublicKeyInfo(KeyAlgorithm.unknown, null);
    final spkiChildren = _childrenInRange(cert, spki.contentStart, spki.contentEnd);
    if (spkiChildren == null || spkiChildren.length < 2) {
      return const _PublicKeyInfo(KeyAlgorithm.unknown, null);
    }

    final alg = _algorithmOid(cert, spkiChildren[0]);
    if (alg == KeyAlgorithm.ec) return const _PublicKeyInfo(KeyAlgorithm.ec, null);
    if (alg != KeyAlgorithm.rsa) return const _PublicKeyInfo(KeyAlgorithm.unknown, null);

    final bitString = spkiChildren[1];
    if (bitString.tag != 0x03 || bitString.contentEnd - bitString.contentStart < 2) {
      return const _PublicKeyInfo(KeyAlgorithm.rsa, null);
    }
    // Skip the unused-bits count byte, then read the RSAPublicKey SEQUENCE.
    final seq = _readTlv(cert, bitString.contentStart + 1, bitString.contentEnd);
    if (seq == null || seq.tag != 0x30) {
      return const _PublicKeyInfo(KeyAlgorithm.rsa, null);
    }
    final rsaChildren = _childrenInRange(cert, seq.contentStart, seq.contentEnd);
    if (rsaChildren == null || rsaChildren.isEmpty || rsaChildren[0].tag != 0x02) {
      return const _PublicKeyInfo(KeyAlgorithm.rsa, null);
    }
    return _PublicKeyInfo(KeyAlgorithm.rsa, _integerBytes(cert, rsaChildren[0]));
  }

  /// Extract the RSA modulus / EC classification from a private key.
  ///
  /// PKCS#8: the privateKey OCTET STRING wraps
  ///   RSAPrivateKey ::= SEQUENCE { version, modulus INTEGER, ... }   (RSA), or
  ///   ECPrivateKey (EC, no modulus).
  /// PKCS#1: the SEQUENCE of 9 INTEGERs is { version, modulus, ... } directly.
  static _PublicKeyInfo _privateKeyInfo(Uint8List blob, DerKind kind) {
    if (kind == DerKind.privateKeyPkcs1) {
      final children = _children(blob, 0);
      if (children == null || children.length < 2 || children[1].tag != 0x02) {
        return const _PublicKeyInfo(KeyAlgorithm.rsa, null);
      }
      return _PublicKeyInfo(KeyAlgorithm.rsa, _integerBytes(blob, children[1]));
    }

    final children = _children(blob, 0);
    if (children == null || children.length < 3) {
      return const _PublicKeyInfo(KeyAlgorithm.unknown, null);
    }
    final alg = _algorithmOid(blob, children[1]);
    if (alg == KeyAlgorithm.ec) return const _PublicKeyInfo(KeyAlgorithm.ec, null);
    if (alg != KeyAlgorithm.rsa) return const _PublicKeyInfo(KeyAlgorithm.unknown, null);

    final pk = children[2]; // OCTET STRING
    final seq = _readTlv(blob, pk.contentStart, pk.contentEnd);
    if (seq == null || seq.tag != 0x30) {
      return const _PublicKeyInfo(KeyAlgorithm.rsa, null);
    }
    final inner = _childrenInRange(blob, seq.contentStart, seq.contentEnd);
    if (inner == null || inner.length < 2 || inner[1].tag != 0x02) {
      return const _PublicKeyInfo(KeyAlgorithm.rsa, null);
    }
    return _PublicKeyInfo(KeyAlgorithm.rsa, _integerBytes(blob, inner[1]));
  }

  /// Read the first child of an AlgorithmIdentifier SEQUENCE (the OID) and map it to RSA / EC.
  static KeyAlgorithm _algorithmOid(Uint8List d, _Tlv algSeq) {
    if (algSeq.tag != 0x30) return KeyAlgorithm.unknown;
    final children = _childrenInRange(d, algSeq.contentStart, algSeq.contentEnd);
    if (children == null || children.isEmpty || children[0].tag != 0x06) {
      return KeyAlgorithm.unknown;
    }
    final oid = Uint8List.sublistView(d, children[0].contentStart, children[0].contentEnd);
    if (_bytesEqual(oid, _rsaEncryptionOid)) return KeyAlgorithm.rsa;
    if (_bytesEqual(oid, _ecPublicKeyOid)) return KeyAlgorithm.ec;
    return KeyAlgorithm.unknown;
  }

  static List<_Tlv>? _tbsChildren(Uint8List cert) {
    final top = _children(cert, 0);
    if (top == null || top.isEmpty) return null;
    final tbs = top.first;
    return _childrenInRange(cert, tbs.contentStart, tbs.contentEnd);
  }

  // rsaEncryption: 1.2.840.113549.1.1.1
  static final Uint8List _rsaEncryptionOid =
      Uint8List.fromList([0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01]);

  // id-ecPublicKey: 1.2.840.10045.2.1
  static final Uint8List _ecPublicKeyOid =
      Uint8List.fromList([0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01]);

  /// The content bytes of a DER INTEGER, with the sign-preserving leading 0x00 (if any) stripped,
  /// so two encodings of the same modulus compare equal.
  static Uint8List _integerBytes(Uint8List d, _Tlv integer) {
    var s = integer.contentStart;
    final e = integer.contentEnd;
    while (s < e - 1 && d[s] == 0x00) {
      s++;
    }
    return Uint8List.sublistView(d, s, e);
  }

  static bool _bytesEqual(Uint8List a, Uint8List b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
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

  /// Parse the immediate children within an explicit content range, requiring the children to
  /// *exactly* fill the range. A TLV whose declared length would leave trailing bytes, or overrun
  /// the range, is not valid DER, so we return null rather than accepting a valid prefix followed
  /// by garbage. This exactness is load-bearing: it is what rejects a decoy that begins with a
  /// well-formed structure and then trails malformed bytes. Do not "simplify" it to tolerate
  /// leftovers.
  static List<_Tlv>? _childrenInRange(Uint8List d, int start, int end) {
    final out = <_Tlv>[];
    var p = start;
    while (p < end) {
      final tlv = _readTlv(d, p, end);
      if (tlv == null) return null;
      out.add(tlv);
      p = tlv.contentEnd;
    }
    if (p != end) return null; // did not consume the range exactly
    return out.isEmpty ? null : out;
  }

  /// Parse the immediate children of the constructed TLV that begins at [outerStart].
  static List<_Tlv>? _children(Uint8List d, int outerStart) {
    final outer = _readTlv(d, outerStart, d.length);
    if (outer == null) return null;
    return _childrenInRange(d, outer.contentStart, outer.contentEnd);
  }

  /// Read one DER TLV, supporting every definite length form: short form (0x00..0x7F) and long
  /// forms with 1..4 length bytes (0x81..0x84). Indefinite length (0x80) is rejected because DER
  /// forbids it, and lengths needing more than 4 bytes are rejected as implausible for credentials.
  static _Tlv? _readTlv(Uint8List d, int pos, int limit) {
    if (pos + 2 > limit) return null;
    final tag = d[pos];
    var p = pos + 1;
    var length = d[p];
    p++;
    if (length & 0x80 != 0) {
      final count = length & 0x7F;
      // count == 0 is the indefinite form (0x80): forbidden in DER. count > 4 is beyond anything we
      // expect and guards against absurd allocations from random data.
      if (count == 0 || count > 4 || p + count > limit) return null;
      length = 0;
      for (var i = 0; i < count; i++) {
        length = (length << 8) | d[p + i];
      }
      p += count;
    }
    final contentStart = p;
    final contentEnd = p + length;
    if (contentEnd > limit || contentEnd < contentStart) return null;
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

class _PublicKeyInfo {
  const _PublicKeyInfo(this.algorithm, this.modulus);
  final KeyAlgorithm algorithm;
  final Uint8List? modulus;
}
