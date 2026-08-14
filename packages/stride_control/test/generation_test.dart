import 'package:test/test.dart';

import 'support.dart';

void main() {
  group('generation IDs / stale-command rejection (req 5)', () {
    // The highest-value test in the suite: a delayed "set 8 kph" that lands
    // after the user hit stop must be discarded, not applied.
    test('a delayed ack from before a stop is discarded, belt stays stopped',
        () {
      runControl((h) {
        final g = h.gen;
        final set = capture(
            h.coord.setSpeed(const Speed.kph(8), generation: g));
        // The write is now in flight (ack is 300ms away).
        h.tick(const Duration(milliseconds: 50));

        final stop = capture(h.coord.stop());
        h.tick(const Duration(milliseconds: 600));

        // The set's ack arrived *after* the stop; it must not have taken effect.
        expect(set.result!.status, CommandStatus.superseded);
        expect(stop.result!.ok, isTrue);
        expect(h.coord.commandedSpeed.kph, 0,
            reason: 'a stale ack must never resurrect a commanded speed');
      }, ackDelay: const Duration(milliseconds: 300));
    });

    test('a command issued under a stale generation is rejected at enqueue', () {
      runControl((h) {
        final stale = h.gen;
        capture(h.coord.stop()); // bumps the generation
        h.tick(const Duration(milliseconds: 200));

        final c = capture(
            h.coord.setSpeed(const Speed.kph(8), generation: stale));
        h.tick(const Duration(milliseconds: 200));
        expect(c.result!.status, CommandStatus.superseded);
        expect(c.result!.didTransmit, isFalse);
      });
    });

    test('a queued command is discarded if the generation bumps before it runs',
        () {
      runControl((h) {
        final g = h.gen;
        // First command occupies the worker (long ack); second is queued.
        capture(h.coord.setSpeed(const Speed.kph(4), generation: g));
        final queued = capture(
            h.coord.setSpeed(const Speed.kph(9), generation: g));
        h.tick(const Duration(milliseconds: 40));

        capture(h.coord.stop()); // bumps generation, drains the queue
        h.tick(const Duration(milliseconds: 600));

        expect(queued.result!.status, CommandStatus.superseded);
        expect(queued.result!.didTransmit, isFalse);
      }, ackDelay: const Duration(milliseconds: 300));
    });

    test('generation bumps on disconnect so in-flight replies go stale', () {
      runControl((h) {
        final g = h.gen;
        h.link.ackMode = AckMode.drop;
        final set = capture(
            h.coord.setSpeed(const Speed.kph(8), generation: g));
        h.tick(const Duration(milliseconds: 50));

        h.link.fault();
        // Flush the link-state delivery so the coordinator processes the fault.
        h.tick(Duration.zero);
        expect(h.coord.generation, greaterThan(g));

        h.tick(const Duration(milliseconds: 600));
        expect(set.result!.status, CommandStatus.superseded);
      });
    });
  });
}
