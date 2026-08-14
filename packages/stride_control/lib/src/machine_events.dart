/// Out-of-band machine events that are not expressible as a [MetricSample].
///
/// The provided [MachineLink] seam reports continuous telemetry, but a treadmill
/// also produces discrete, safety-relevant events: the physical safety key being
/// pulled, a hardware Stop button on the console, or the machine's own workout
/// state changing because *something other than Stride* acted (PLAN.md 3.5 calls
/// this out explicitly - the `WorkoutStateChanged` stream tells us when a hardware
/// button or the safety key changed things).
///
/// These live in a separate, additive channel ([MachineEventSource]) rather than
/// on [MachineLink] itself, so the existing dumb-link contract is untouched. A
/// link may optionally implement [MachineEventSource]; the coordinator subscribes
/// only if it does. A link that does not implement it simply has no safety-key or
/// external-stop signal, which is a strictly weaker (never less safe) machine.
///
/// As with the rest of the seam, an event source only *reports*. It makes no
/// safety decision. Latching, generation bumping and escalation all belong to the
/// coordinator.
library;

import 'types.dart';

/// A discrete event observed on the machine.
sealed class MachineEvent {
  const MachineEvent();
}

/// The physical safety key was removed.
///
/// This is the only true emergency stop on the machine. When it is pulled the
/// belt is cut in hardware; software cannot override it. The coordinator latches
/// a stopped state that can only be cleared by an explicit local reset - never by
/// a queued command, a companion app, or the key simply being reinserted.
class SafetyKeyRemoved extends MachineEvent {
  const SafetyKeyRemoved();

  @override
  String toString() => 'SafetyKeyRemoved';
}

/// The physical safety key was reinserted.
///
/// Reinsertion deliberately does NOT clear the latch. Rearming after a safety-key
/// pull must be a conscious local action, so the belt can never resume as a side
/// effect of the key going back in.
class SafetyKeyReinserted extends MachineEvent {
  const SafetyKeyReinserted();

  @override
  String toString() => 'SafetyKeyReinserted';
}

/// The machine stopped itself for a reason other than a Stride command - a
/// hardware Stop button, or the native workout being ended on the console.
///
/// Unlike a safety-key pull this does not latch, but it still means an external
/// actor changed motion, so the coordinator discards any in-flight or queued
/// Stride commands (generation bump) and reconciles to stopped.
class ExternalStop extends MachineEvent {
  const ExternalStop();

  @override
  String toString() => 'ExternalStop';
}

/// The machine's speed changed because of an external actor (for example the
/// console's physical speed buttons), not a Stride command. The coordinator
/// reconciles its commanded baseline to the observed value rather than fighting
/// the hardware.
class ExternalSpeedChange extends MachineEvent {
  const ExternalSpeedChange(this.speed);

  final Speed speed;

  @override
  String toString() => 'ExternalSpeedChange($speed)';
}

/// Optional, additive capability a [MachineLink] may expose to deliver
/// [MachineEvent]s. Kept off [MachineLink] so the existing seam is not redesigned.
abstract class MachineEventSource {
  Stream<MachineEvent> get events;
}
