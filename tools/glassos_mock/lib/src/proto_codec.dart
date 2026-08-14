/// Minimal protobuf wire-format encoder and decoder.
///
/// We deliberately do NOT use generated protobuf stubs. The real GlassOS schema
/// is unknown (its `.proto` files live in a GPL-3 repo we are not copying, and
/// the true field numbers can only be confirmed by running the probe on real
/// hardware). So the mock hand-encodes messages with this tiny codec.
///
/// The wire format is self-describing: every field is `(field_number << 3) |
/// wire_type` followed by the payload. That is exactly what the generic decoder
/// in apps/spikes/android/app/src/main/kotlin/io/stride/spikes/GlassOsClient.kt walks, so anything written here is
/// decodable there. This encoder is the inverse of that decoder.
///
/// Wire types used:
///   0 varint            (int, bool, enum)
///   1 64-bit            (double / sfixed64)
///   2 length-delimited  (string, bytes, embedded message)
///   5 32-bit            (float / sfixed32)
library;

import 'dart:convert';
import 'dart:typed_data';

class ProtoWriter {
  final BytesBuilder _out = BytesBuilder(copy: false);

  Uint8List toBytes() => _out.toBytes();

  void _tag(int fieldNumber, int wireType) {
    _writeVarint((fieldNumber << 3) | wireType);
  }

  void _writeVarint(int value) {
    // Protobuf varints are unsigned little-endian base-128. Negative ints are
    // encoded as their two's-complement 64-bit pattern, matching protoc.
    var v = value;
    if (v < 0) {
      // Emit the full 10-byte two's-complement form for negative values.
      for (var i = 0; i < 9; i++) {
        _out.addByte((v & 0x7f) | 0x80);
        v >>= 7;
      }
      _out.addByte(1);
      return;
    }
    while (v >= 0x80) {
      _out.addByte((v & 0x7f) | 0x80);
      v >>= 7;
    }
    _out.addByte(v & 0x7f);
  }

  void writeInt(int fieldNumber, int value) {
    _tag(fieldNumber, 0);
    _writeVarint(value);
  }

  void writeBool(int fieldNumber, bool value) {
    _tag(fieldNumber, 0);
    _writeVarint(value ? 1 : 0);
  }

  void writeEnum(int fieldNumber, int value) => writeInt(fieldNumber, value);

  void writeDouble(int fieldNumber, double value) {
    _tag(fieldNumber, 1);
    final bd = ByteData(8)..setFloat64(0, value, Endian.little);
    _out.add(bd.buffer.asUint8List());
  }

  void writeFloat(int fieldNumber, double value) {
    _tag(fieldNumber, 5);
    final bd = ByteData(4)..setFloat32(0, value, Endian.little);
    _out.add(bd.buffer.asUint8List());
  }

  void writeString(int fieldNumber, String value) {
    _tag(fieldNumber, 2);
    final bytes = utf8.encode(value);
    _writeVarint(bytes.length);
    _out.add(bytes);
  }

  void writeBytes(int fieldNumber, List<int> value) {
    _tag(fieldNumber, 2);
    _writeVarint(value.length);
    _out.add(value);
  }

  /// Writes an embedded message (length-delimited).
  void writeMessage(int fieldNumber, ProtoWriter nested) {
    writeBytes(fieldNumber, nested.toBytes());
  }
}

/// A single decoded protobuf field.
class ProtoField {
  ProtoField(this.number, this.wireType, this.raw, {this.varint, this.bytes});

  final int number;
  final int wireType;

  /// The raw payload bytes for this field (for length-delimited fields).
  final Uint8List raw;

  /// Decoded varint value, when wireType == 0.
  final int? varint;

  /// Length-delimited payload, when wireType == 2.
  final Uint8List? bytes;
}

/// Just enough of a reader to pull specific request fields out on the server
/// side. Requests we care about (SetSpeed, SetIncline) are tiny, so a flat scan
/// is fine. Returns the first occurrence of each field number.
class ProtoReader {
  ProtoReader(this._data);

  final Uint8List _data;

  Map<int, ProtoField> readAll() {
    final fields = <int, ProtoField>{};
    var p = 0;
    while (p < _data.length) {
      final tag = _readVarint(p);
      if (tag == null) break;
      p = tag.next;
      final fieldNumber = tag.value >> 3;
      final wireType = tag.value & 0x7;
      switch (wireType) {
        case 0:
          final v = _readVarint(p);
          if (v == null) return fields;
          fields.putIfAbsent(
            fieldNumber,
            () => ProtoField(fieldNumber, 0, Uint8List(0), varint: v.value),
          );
          p = v.next;
        case 1:
          if (p + 8 > _data.length) return fields;
          fields.putIfAbsent(
            fieldNumber,
            () => ProtoField(
              fieldNumber,
              1,
              Uint8List.sublistView(_data, p, p + 8),
            ),
          );
          p += 8;
        case 2:
          final len = _readVarint(p);
          if (len == null) return fields;
          p = len.next;
          final end = p + len.value;
          if (end > _data.length) return fields;
          fields.putIfAbsent(
            fieldNumber,
            () => ProtoField(
              fieldNumber,
              2,
              Uint8List.sublistView(_data, p, end),
              bytes: Uint8List.sublistView(_data, p, end),
            ),
          );
          p = end;
        case 5:
          if (p + 4 > _data.length) return fields;
          fields.putIfAbsent(
            fieldNumber,
            () => ProtoField(
              fieldNumber,
              5,
              Uint8List.sublistView(_data, p, p + 4),
            ),
          );
          p += 4;
        default:
          return fields;
      }
    }
    return fields;
  }

  static double? asDouble(ProtoField? f) {
    if (f == null) return null;
    if (f.wireType == 1 && f.raw.length == 8) {
      return ByteData.sublistView(f.raw).getFloat64(0, Endian.little);
    }
    if (f.wireType == 5 && f.raw.length == 4) {
      return ByteData.sublistView(f.raw).getFloat32(0, Endian.little);
    }
    if (f.wireType == 0 && f.varint != null) {
      return f.varint!.toDouble();
    }
    return null;
  }

  static int? asInt(ProtoField? f) => f?.varint;

  _Varint? _readVarint(int pos) {
    var result = 0;
    var shift = 0;
    var p = pos;
    while (p < _data.length) {
      final byte = _data[p];
      result |= (byte & 0x7f) << shift;
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
