import 'package:test/test.dart';

import 'support.dart';

void main() {
  group('no auto-start, ever (req 11)', () {
    test('connecting to a stopped belt never commands motion', () {
      runControl((h) {
        h.tick(const Duration(milliseconds: 500));
        expect(h.link.speedSends, 0);
        expect(h.coord.phase, MotionPhase.idle);
      });
    });

    test('a profile change never starts the belt', () {
      runControl((h) {
        final ok = h.coord.setProfile(const ProfileLimits(maxSpeed: Speed.kph(6)));
        h.tick(const Duration(milliseconds: 200));
        expect(ok, isTrue);
        expect(h.link.speedSends, 0);
      });
    });

    test('profile changes are refused while the belt is moving', () {
      runControl((h) {
        cruise(h, 8);
        final sends = h.link.speedSends;
        final ok = h.coord.setProfile(const ProfileLimits(maxSpeed: Speed.kph(4)));
        expect(ok, isFalse);
        expect(h.coord.phase, MotionPhase.moving);
        expect(h.link.speedSends, sends);
      });
    });

    test('reconnect and latch reset never start the belt', () {
      runControl((h) {
        h.link.fault();
        h.tick(const Duration(milliseconds: 100));
        h.link.restart();
        h.tick(const Duration(milliseconds: 300));

        h.link.pullSafetyKey();
        h.tick(const Duration(milliseconds: 50));
        h.coord.resetLatch();
        h.tick(const Duration(milliseconds: 100));

        expect(h.link.speedSends, 0);
      });
    });
  });
}
