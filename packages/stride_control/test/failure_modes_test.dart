import 'package:test/test.dart';

import 'support.dart';

void main() {
  group('failure-mode checklist (PLAN.md 5)', () {
    test('network loss mid-command surfaces a link failure', () {
      runControl((h) {
        h.link.ackMode = AckMode.fail;
        final c = capture(h.setSpeed(8));
        h.tick(const Duration(milliseconds: 200));
        expect(c.result!.status, CommandStatus.linkFailure);
      });
    });

    test('a link fault drops to offline and invalidates the generation', () {
      runControl((h) {
        cruise(h, 8);
        final g = h.gen;
        h.link.fault();
        h.tick(const Duration(milliseconds: 50));
        expect(h.coord.phase, MotionPhase.offline);
        expect(h.coord.generation, greaterThan(g));
      });
    });

    test('GlassOS restart while the belt runs reconciles to moving', () {
      runControl((h) {
        cruise(h, 8);
        h.tick(const Duration(milliseconds: 400)); // reach ~8 kph observed
        h.link.fault(); // belt keeps running; physics frozen at speed
        h.tick(const Duration(milliseconds: 100));
        expect(h.coord.phase, MotionPhase.offline);

        h.link.restart();
        h.tick(const Duration(milliseconds: 300));
        expect(h.coord.phase, MotionPhase.moving);
        expect(h.hasEvent<AttachedToMovingBelt>(), isTrue);
      });
    });

    test('a hardware Stop button reconciles to stopped without latching', () {
      runControl((h) {
        cruise(h, 8);
        h.link.pressStopButton();
        h.tick(const Duration(milliseconds: 200));
        expect(h.coord.phase, MotionPhase.idle);
        expect(h.coord.latched, isFalse);
        expect(h.coord.commandedSpeed.kph, 0);
      });
    });

    test('external console speed change is reconciled, not fought', () {
      runControl((h) {
        h.link.externalSetSpeed(const Speed.kph(5));
        h.tick(const Duration(milliseconds: 200));
        expect(h.coord.phase, MotionPhase.moving);
        expect(h.coord.commandedSpeed.kph, closeTo(5, 1e-9));
        expect(h.link.speedSends, 0, reason: 'we did not command this');
      });
    });

    test('safety-key pull then reinsert leaves the belt latched and stopped',
        () {
      runControl((h) {
        cruise(h, 8);
        h.link.pullSafetyKey();
        h.tick(const Duration(milliseconds: 50));
        h.link.insertSafetyKey();
        h.tick(const Duration(milliseconds: 50));
        expect(h.coord.latched, isTrue);
        expect(h.coord.commandedSpeed.kph, 0);
      });
    });
  });
}
