/// Test-only surface: an in-memory [MachineLink] with fault injection and belt
/// physics. Deliberately kept out of the main barrel so production code cannot
/// depend on it.
library;

export 'src/testing/fake_machine_link.dart';
