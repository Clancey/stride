import 'package:test/test.dart';

import 'support.dart';

void main() {
  group('attach-to-moving-machine recovery (req 8)', () {
    test('reconciles to the observed speed instead of assuming zero', () {
      runControl((h) {
        h.tick(const Duration(milliseconds: 200)); // let telemetry arrive
        expect(h.coord.phase, MotionPhase.moving);
        expect(h.coord.commandedSpeed.kph, closeTo(8, 0.5),
            reason: 'commanded baseline is reconciled to observed motion');
        expect(h.hasEvent<AttachedToMovingBelt>(), isTrue);
        expect(h.link.speedSends, 0,
            reason: 'attaching must never command the belt');
      }, prime: (link) => link.primeMovingBelt(const Speed.kph(8)));
    });

    test('attaching to a stopped belt stays idle', () {
      runControl((h) {
        h.tick(const Duration(milliseconds: 200));
        expect(h.coord.phase, MotionPhase.idle);
        expect(h.hasEvent<AttachedToMovingBelt>(), isFalse);
      });
    });
  });
}
