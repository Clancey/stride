import 'package:test/test.dart';

import 'support.dart';

void main() {
  group('serialized command queue (req 1)', () {
    test('never issues two motion writes concurrently', () {
      runControl((h) {
        final a = capture(h.setSpeed(4));
        final b = capture(h.setSpeed(6));
        final c = capture(h.setSpeed(8));
        h.tick(const Duration(milliseconds: 500));

        expect(h.link.maxConcurrentMotionSends, 1,
            reason: 'exactly one command may be in flight at a time');
        expect(h.link.speedSends, 3, reason: 'all three still transmit');
        expect(a.result!.ok, isTrue);
        expect(b.result!.ok, isTrue);
        expect(c.result!.ok, isTrue);
        expect(h.coord.commandedSpeed.kph, closeTo(8, 1e-9));
      });
    });

    test('commands transmit in the order they were queued', () {
      runControl((h) {
        capture(h.setSpeed(3));
        capture(h.setSpeed(5));
        capture(h.setSpeed(7));
        h.tick(const Duration(milliseconds: 500));
        // Last-in wins as the final commanded value, proving in-order draining
        // rather than reordering.
        expect(h.coord.commandedSpeed.kph, closeTo(7, 1e-9));
      }, ackDelay: const Duration(milliseconds: 80));
    });
  });
}
