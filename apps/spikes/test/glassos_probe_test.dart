// Tests for the heuristic protobuf wire inspector in glassos_probe.dart.
//
// The GlassOS probe cannot ship generated stubs (the .proto files are GPL-3), so it leans on a
// schema-free wire walker to read real responses on the console. That walker is the one piece of
// the S2 spike that is fully testable off-hardware, and getting it wrong - accepting a malformed
// varint, or silently guessing a field type - would corrupt the very field-number evidence the
// spike exists to gather. All fixtures here are encoded by hand so the expected bytes are explicit.

import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:stride_spikes/glassos_probe.dart';

Uint8List bytes(List<int> b) => Uint8List.fromList(b);

// Wire-format tag byte: (fieldNumber << 3) | wireType. Valid only for single-byte field numbers.
int tag(int field, int wire) => (field << 3) | wire;

void main() {
  group('readVarint', () {
    test('reads a single-byte value', () {
      final r = ProtobufWireInspector.readVarint(bytes([0x01]), 0)!;
      expect(r.bits, 1);
      expect(r.nextOffset, 1);
    });

    test('reads a two-byte value (300)', () {
      // 300 = 0b100101100 -> groups 0101100 (0x2C|cont) then 0000010 (0x02).
      final r = ProtobufWireInspector.readVarint(bytes([0xAC, 0x02]), 0)!;
      expect(r.bits, 300);
      expect(r.nextOffset, 2);
    });

    test('reads the maximum unsigned 64-bit value as all-ones bits', () {
      // 0xFFFFFFFFFFFFFFFF encodes as nine 0xFF bytes plus a final 0x01 (bit 63).
      final r = ProtobufWireInspector.readVarint(
        bytes([0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01]),
        0,
      )!;
      expect(r.bits, -1); // all 64 bits set, read as signed Dart int
      expect(r.nextOffset, 10);
      expect(ProtobufWireInspector.unsigned64(r.bits), '18446744073709551615');
    });

    test('rejects a truncated varint (continuation bit with no more bytes)', () {
      expect(ProtobufWireInspector.readVarint(bytes([0x80]), 0), isNull);
      expect(
        ProtobufWireInspector.readVarint(bytes([0xFF, 0xFF, 0xFF]), 0),
        isNull,
      );
    });

    test('rejects an over-long 10-byte varint whose final byte overflows 64 bits', () {
      // Ninth byte still continues; tenth byte carries more than the single allowed high bit.
      final overflow = bytes([0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x02]);
      expect(ProtobufWireInspector.readVarint(overflow, 0), isNull);
    });

    test('rejects an 11th continuation byte', () {
      final tooLong =
          bytes([0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01]);
      expect(ProtobufWireInspector.readVarint(tooLong, 0), isNull);
    });
  });

  group('numeric interpretations', () {
    test('zigzag decodes small values', () {
      expect(ProtobufWireInspector.zigzag64(0), 0);
      expect(ProtobufWireInspector.zigzag64(1), -1);
      expect(ProtobufWireInspector.zigzag64(2), 1);
      expect(ProtobufWireInspector.zigzag64(3), -2);
      expect(ProtobufWireInspector.zigzag64(300), 150);
    });

    test('unsigned64 renders bit-63 values as their true magnitude', () {
      expect(ProtobufWireInspector.unsigned64(0), '0');
      expect(ProtobufWireInspector.unsigned64(42), '42');
      expect(ProtobufWireInspector.unsigned64(-1), '18446744073709551615');
    });

    test('boolOf distinguishes 0, 1, and other', () {
      expect(ProtobufWireInspector.boolOf(0), 'false');
      expect(ProtobufWireInspector.boolOf(1), 'true');
      expect(ProtobufWireInspector.boolOf(5), contains('n/a'));
    });
  });

  group('describe: varint field', () {
    test('shows unsigned, zigzag, signed, and bool readings', () {
      // field 1, wire 0 (varint), value 300.
      final msg = bytes([tag(1, 0), 0xAC, 0x02]);
      final out = ProtobufWireInspector.describe(msg);
      expect(out, contains('field 1 (wire 0, varint)'));
      expect(out, contains('uint64        = 300'));
      expect(out, contains('sint64/zigzag = 150'));
      expect(out, contains('int64         = 300'));
      expect(out, contains('bool          = n/a'));
    });

    test('renders a value of 1 as bool true', () {
      final msg = bytes([tag(2, 0), 0x01]);
      final out = ProtobufWireInspector.describe(msg);
      expect(out, contains('field 2 (wire 0, varint)'));
      expect(out, contains('bool          = true'));
    });
  });

  group('describe: fixed fields', () {
    test('fixed32 shows uint32, int32, and float', () {
      // field 3, wire 5 (fixed32), float 1.5 = bytes 00 00 C0 3F (little-endian).
      final msg = bytes([tag(3, 5), 0x00, 0x00, 0xC0, 0x3F]);
      final out = ProtobufWireInspector.describe(msg);
      expect(out, contains('field 3 (wire 5, fixed32)'));
      expect(out, contains('float  = 1.5'));
      expect(out, contains('uint32 = 1069547520'));
      expect(out, contains('int32  = 1069547520'));
    });

    test('fixed64 shows uint64, int64, and double', () {
      // field 4, wire 1 (fixed64), double 1.5 = 00 00 00 00 00 00 F8 3F (little-endian).
      final msg = bytes([tag(4, 1), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xF8, 0x3F]);
      final out = ProtobufWireInspector.describe(msg);
      expect(out, contains('field 4 (wire 1, fixed64)'));
      expect(out, contains('double = 1.5'));
    });

    test('reports a truncated fixed32', () {
      final msg = bytes([tag(3, 5), 0x00, 0x00]); // only 2 of 4 bytes
      final out = ProtobufWireInspector.describe(msg);
      expect(out, contains('truncated fixed32'));
    });
  });

  group('describe: length-delimited fields', () {
    test('shows a printable string alongside its raw bytes', () {
      // field 5, wire 2, "hello".
      final msg = bytes([tag(5, 2), 0x05, 0x68, 0x65, 0x6C, 0x6C, 0x6F]);
      final out = ProtobufWireInspector.describe(msg);
      expect(out, contains('field 5 (wire 2, length-delimited, 5 bytes)'));
      expect(out, contains('string = "hello"'));
      expect(out, contains('bytes  = 68 65 6c 6c 6f'));
    });

    test('does not offer a string reading for binary bytes', () {
      // field 6, wire 2, three non-printable bytes.
      final msg = bytes([tag(6, 2), 0x03, 0x00, 0xFF, 0x01]);
      final out = ProtobufWireInspector.describe(msg);
      expect(out, contains('field 6 (wire 2, length-delimited, 3 bytes)'));
      expect(out, isNot(contains('string =')));
      expect(out, contains('bytes  = 00 ff 01'));
    });

    test('decodes an embedded message when it parses cleanly', () {
      // Outer field 3, wire 2, whose payload is itself {field 1 varint = 1}.
      final inner = [tag(1, 0), 0x01];
      final msg = bytes([tag(3, 2), inner.length, ...inner]);
      final out = ProtobufWireInspector.describe(msg);
      expect(out, contains('message? (parses cleanly as a submessage)'));
      // The nested field is decoded, indented under the outer field.
      expect(out, contains('field 1 (wire 0, varint)'));
      expect(out, contains('bool          = true'));
    });

    test('reports a truncated length-delimited field', () {
      // Declares length 5 but only 2 bytes follow.
      final msg = bytes([tag(5, 2), 0x05, 0x68, 0x65]);
      final out = ProtobufWireInspector.describe(msg);
      expect(out, contains('truncated length-delimited'));
    });
  });

  group('describe: malformed input', () {
    test('flags an over-long varint instead of accepting it', () {
      // field 1, wire 0, then a 10-byte varint that overflows 64 bits.
      final msg = bytes([
        tag(1, 0),
        0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x02,
      ]);
      final out = ProtobufWireInspector.describe(msg);
      expect(out, contains('malformed varint'));
    });

    test('flags a malformed field key', () {
      final msg = bytes([0x80]); // continuation bit set, no following byte
      final out = ProtobufWireInspector.describe(msg);
      expect(out, contains('malformed field key'));
    });

    test('reports a legacy group wire type without decoding it', () {
      final msg = bytes([tag(7, 3)]); // start-group
      final out = ProtobufWireInspector.describe(msg);
      expect(out, contains('legacy group wire type 3'));
    });
  });

  group('isWellFormedMessage', () {
    test('accepts a clean message', () {
      expect(
        ProtobufWireInspector.isWellFormedMessage(bytes([tag(1, 0), 0x01])),
        isTrue,
      );
    });

    test('rejects an empty buffer', () {
      expect(ProtobufWireInspector.isWellFormedMessage(bytes([])), isFalse);
    });

    test('rejects leftover bytes', () {
      // A fixed32 tag but only two trailing bytes.
      expect(
        ProtobufWireInspector.isWellFormedMessage(bytes([tag(1, 5), 0x00, 0x00])),
        isFalse,
      );
    });

    test('rejects an invalid wire type', () {
      expect(
        ProtobufWireInspector.isWellFormedMessage(bytes([tag(1, 6)])),
        isFalse,
      );
    });
  });

  group('multi-field message', () {
    test('walks several fields in sequence', () {
      final msg = bytes([
        tag(1, 0), 0x2A, // field 1 varint = 42
        tag(2, 2), 0x02, 0x68, 0x69, // field 2 string = "hi"
        tag(3, 5), 0x00, 0x00, 0x80, 0x3F, // field 3 fixed32 float 1.0
      ]);
      final out = ProtobufWireInspector.describe(msg);
      expect(out, contains('field 1 (wire 0, varint)'));
      expect(out, contains('uint64        = 42'));
      expect(out, contains('string = "hi"'));
      expect(out, contains('field 3 (wire 5, fixed32)'));
      expect(out, contains('float  = 1.0'));
    });
  });
}
