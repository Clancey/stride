import 'package:test/test.dart';

import 'support.dart';

void main() {
  group('safety-key latch (req 9)', () {
    test('a safety-key pull latches a stopped state', () {
      runControl((h) {
        cruise(h, 8);
        h.link.pullSafetyKey();
        h.tick(const Duration(milliseconds: 50));

        expect(h.coord.latched, isTrue);
        expect(h.coord.phase, MotionPhase.latched);
        expect(h.coord.commandedSpeed.kph, 0);
        expect(h.hasEvent<Latched>(), isTrue);
      });
    });

    test('motion commands are refused while latched', () {
      runControl((h) {
        h.link.pullSafetyKey();
        h.tick(const Duration(milliseconds: 50));

        final c = capture(h.setSpeed(5));
        h.tick(const Duration(milliseconds: 100));
        expect(c.result!.status, CommandStatus.latched);
        expect(c.result!.didTransmit, isFalse);
      });
    });

    test('reinserting the key does NOT clear the latch', () {
      runControl((h) {
        h.link.pullSafetyKey();
        h.tick(const Duration(milliseconds: 50));
        h.link.insertSafetyKey();
        h.tick(const Duration(milliseconds: 50));

        expect(h.coord.latched, isTrue, reason: 'only a local reset may clear');
        expect(h.coord.keyPresent, isTrue);
      });
    });

    test('an explicit local reset clears the latch without moving the belt', () {
      runControl((h) {
        h.link.pullSafetyKey();
        h.tick(const Duration(milliseconds: 50));
        final sendsBefore = h.link.speedSends;

        h.coord.resetLatch();
        h.tick(const Duration(milliseconds: 50));

        expect(h.coord.latched, isFalse);
        expect(h.coord.phase, MotionPhase.idle);
        expect(h.hasEvent<LatchReset>(), isTrue);
        expect(h.link.speedSends, sendsBefore,
            reason: 'resetting the latch must never start the belt');
      });
    });
  });
}
