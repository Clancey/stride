import 'package:flutter/material.dart';

import '../bridge.dart';
import '../theme/stride_tokens.dart';

/// Settings for the overlay, reached from the launcher rather than from the overlay itself.
///
/// The overlay is what a rider touches mid-walk, and everything on it has to be worth a hand
/// leaving the rail. Configuration is not: it is done once, standing still, and it belongs on the
/// launcher where there is room to explain what each choice costs.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen>
    with WidgetsBindingObserver {
  List<Map<String, dynamic>> _grants = const [];
  Map<String, dynamic> _settings = const {};
  bool? _trackFloorChosen;
  bool _trackBackdropBlank = false;
  bool _loading = true;

  /// True while a transport switch is in flight, so the screen can say so
  /// rather than showing the old link's capabilities under the new setting.
  bool _switching = false;
  bool _advancedOpen = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // The rider fixes a grant in the system Settings app and comes back. Re-reading on resume is
    // what stops this screen from still claiming a grant is missing after they just granted it.
    if (state == AppLifecycleState.resumed) _load();
  }

  Future<void> _load() async {
    try {
      final grants = await SpikeBridge.grantsGet();
      final settings = await SpikeBridge.settingsGet();
      final floor = await SpikeBridge.trackFloorGet();
      if (!mounted) return;
      setState(() {
        _grants = grants;
        _settings = settings;
        _trackFloorChosen = floor['chosen'] as bool?;
        _trackBackdropBlank = floor['backdrop'] as bool? ?? false;
        _loading = false;
      });
    } on Object {
      if (!mounted) return;
      setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      // The overlay's rails and bars sit on top of this screen in their own windows, so the
      // content has to keep inside what is actually visible.
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : ListView(
                padding: const EdgeInsets.fromLTRB(
                  StrideSpace.xl,
                  StrideSpace.md,
                  StrideSpace.xl,
                  StrideSpace.xxl,
                ),
                children: [
                  _Section(
                    title: 'System access',
                    subtitle:
                        'These can be switched off by Android without telling you. '
                        'Stride checks them every time you open this screen.',
                    child: Column(
                      children: [
                        for (final grant in _grants)
                          _GrantRow(
                            grant: grant,
                            onOpen: () => SpikeBridge.grantOpenSettings(
                              grant['id'] as String? ?? '',
                            ),
                          ),
                      ],
                    ),
                  ),
                  const SizedBox(height: StrideSpace.lg),
                  _Section(
                    title: 'Track floor',
                    subtitle:
                        'The lap oval behind the controls. Automatic hides it while a video is '
                        'playing and shows it the rest of the time.',
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        _TrackFloorChoice(
                          chosen: _trackFloorChosen,
                          onChanged: (value) async {
                            // Only mirror what the platform actually accepted.
                            // This screen used to record the tap regardless, so
                            // a refused write looked like it had worked right up
                            // until the next reload put the old value back.
                            final ok = await SpikeBridge.trackFloorSet(value);
                            if (!mounted || !ok) return;
                            setState(() => _trackFloorChosen = value);
                          },
                        ),
                        const SizedBox(height: StrideSpace.lg),
                        Text(
                          'Behind the track',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        const SizedBox(height: StrideSpace.xxs),
                        Text(
                          _trackFloorChosen == true
                              ? 'Plain replaces the app grid with a quiet backdrop while the '
                                    'track is up. Tap the screen to bring the launcher back.'
                              : 'Only applies while the track floor is Always on — the rest of '
                                    'the time the track is not over the launcher at all.',
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                        const SizedBox(height: StrideSpace.md),
                        _TrackBackdropChoice(
                          blank: _trackBackdropBlank,
                          onChanged: (value) async {
                            final ok = await SpikeBridge.trackBackdropSet(
                              value,
                            );
                            if (!mounted || !ok) return;
                            setState(() => _trackBackdropBlank = value);
                          },
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: StrideSpace.lg),
                  _Section(
                    title: 'Android settings',
                    subtitle:
                        "Wi-Fi, sound, display and everything else that belongs to the console "
                        "rather than to Stride.",
                    child: _AndroidSettingsRow(onOpen: _openSystemSettings),
                  ),
                  const SizedBox(height: StrideSpace.lg),
                  _AdvancedSection(
                    open: _advancedOpen,
                    onToggle: () =>
                        setState(() => _advancedOpen = !_advancedOpen),
                    transport: _settings['transport'] as String? ?? 'glassos',
                    onTransport: _setTransport,
                    machineDetail: _settings['machineDetail'] as String?,
                    machineLinked: _settings['machineLinked'] as bool? ?? false,
                    machineCapabilities:
                        (_settings['machineCapabilities'] as Map?)
                            ?.cast<String, dynamic>(),
                    switching: _switching,
                    heartRateStrap:
                        _settings['heartRateStrap'] as bool? ?? false,
                    heartRateStrapLinked:
                        _settings['heartRateStrapLinked'] as bool? ?? false,
                    heartRateStrapName:
                        _settings['heartRateStrapName'] as String?,
                    heartRateStrapBattery:
                        _settings['heartRateStrapBattery'] as int?,
                    onHeartRateStrap: _setHeartRateStrap,
                    inclineSpacing:
                        _settings['inclineSpacing'] as String? ?? 'fine',
                    onInclineSpacing: _setInclineSpacing,
                    transportAutomatic:
                        _settings['transportAutomatic'] as bool? ?? true,
                    transportDetail: _settings['transportDetail'] as String?,
                  ),
                ],
              ),
      ),
    );
  }

  Future<void> _openSystemSettings() async {
    final ok = await SpikeBridge.openSystemSettings();
    if (!mounted || ok) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text("Android settings wouldn't open on this console."),
      ),
    );
  }

  Future<void> _setTransport(String transport) async {
    final current = _settings['transport'] as String? ?? 'glassos';
    final automatic = _settings['transportAutomatic'] as bool? ?? true;
    final linked = _settings['machineLinked'] as bool? ?? false;
    // Tapping the row already in use does something in two cases, and nothing
    // only in the third.
    //
    // While the transport is automatic, that tap is the rider *pinning* it,
    // which has to be written down -- otherwise the selection silently stays
    // automatic and can change under them on the next restart. Someone
    // deliberately holding a console on iFit is exactly who that would surprise.
    //
    // And while it is chosen but not connected, the tap is a retry. That is the
    // only way back for a rider whose machine was switched on, or plugged in,
    // or granted permission, after the last attempt failed -- without it the row
    // they are already on is inert and the app looks stuck.
    if (transport == current && !automatic && linked) return;
    // Only *leaving* iFit needs a warning. Coming back to it is the recovery
    // path, and putting a confirmation in front of the way out of a broken
    // link is how a rider ends up stuck on a transport that does not answer.
    // Pinning the transport already in use is not leaving anything, so it does
    // not warn either -- the machine is already running on it.
    if (transport != 'glassos' && transport != current) {
      final confirmed = await _confirmLeavingIfit(transport);
      if (confirmed != true) return;
    }
    // Remembered before the switch, because what we are waiting for is this
    // number changing. Reading it afterwards would race the very work we are
    // trying to wait for.
    final before = _settings['retargetCount'] as int?;
    final ok = await SpikeBridge.transportSet(transport);
    if (!mounted) return;
    if (!ok) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text("That transport couldn't be saved.")),
      );
      return;
    }
    // Ask for USB access as part of the tap, not from the background retry.
    // Stride keeps an overlay above everything it launches, so a dialog raised
    // from a poll can appear behind it and never be seen -- the rider taps the
    // setting, nothing visibly happens, and it gets reported as not working.
    if (transport == 'direct') {
      await SpikeBridge.usbPermissionRequest();
      if (!mounted) return;
    }
    setState(() => _switching = true);
    try {
      await _awaitRetarget(before);
    } finally {
      if (mounted) setState(() => _switching = false);
    }
  }

  /// Re-read settings until the transport switch has actually finished.
  ///
  /// Switching closes one link and opens another, and opening the direct one
  /// runs a full handshake — address discovery, `DEVICE_INFO`, a probe — which
  /// takes long enough to see. Reading once when `transportSet` returns shows
  /// the *old* transport's findings under the new setting, which reads as the
  /// switch having silently failed.
  ///
  /// Bounded, and it refreshes on the way out either way: a switch that never
  /// reports completion still leaves the screen showing the truth as of now,
  /// which beats spinning forever on a treadmill that will not answer.
  Future<void> _awaitRetarget(int? before) async {
    const step = Duration(milliseconds: 300);
    const attempts = 30; // ~9s, longer than a BLE connect and probe.
    for (var i = 0; i < attempts; i++) {
      await Future<void>.delayed(step);
      if (!mounted) return;
      await _load();
      if (!mounted) return;
      final now = _settings['retargetCount'] as int?;
      if (now != null && now != before) return;
    }
  }

  /// Turn the heart rate strap on or off.
  ///
  /// No confirmation either way. A strap cannot move anything, so the warning
  /// the transport switch needs would be noise here — and turning it back off
  /// is the recovery path, which should never sit behind a dialog.
  Future<void> _setHeartRateStrap(bool enabled) async {
    final ok = await SpikeBridge.heartRateStrapSet(enabled);
    if (!mounted) return;
    if (!ok) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text("That setting couldn't be saved.")),
      );
      return;
    }
    // Connecting runs a GATT handshake, so re-read for a moment rather than
    // once: reading immediately would show "not found" for a strap that is
    // still being connected to.
    setState(() => _switching = true);
    try {
      for (var i = 0; i < 12; i++) {
        await Future<void>.delayed(const Duration(milliseconds: 300));
        if (!mounted) return;
        await _load();
        if (!mounted) return;
        if ((_settings['heartRateStrapLinked'] as bool? ?? false) == enabled) {
          return;
        }
      }
    } finally {
      if (mounted) setState(() => _switching = false);
    }
  }

  /// Re-space the incline quick-pick column.
  ///
  /// No confirmation and no waiting loop, unlike the two above. This changes
  /// which buttons are drawn and nothing else — it cannot take the treadmill
  /// away, so there is nothing to warn about and no handshake to wait out.
  ///
  /// The local state is only updated once the platform says it stored the
  /// choice. Recording the tap regardless is what made the track-floor pills
  /// look correct right up until the next reload put the real value back.
  Future<void> _setInclineSpacing(String spacing) async {
    final ok = await SpikeBridge.inclineSpacingSet(spacing);
    if (!mounted) return;
    if (!ok) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text("That setting couldn't be saved.")),
      );
      return;
    }
    setState(() => _settings = <String, dynamic>{
      ..._settings,
      'inclineSpacing': spacing,
    });
  }

  Future<bool?> _confirmLeavingIfit(String transport) {    final ftms = transport == 'ftms';
    return showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: StrideColors.panelRaised,
        title: Text(
          ftms
              ? 'Use a Bluetooth fitness machine?'
              : 'Turn on direct hardware access?',
        ),
        content: Text(
          ftms
              ? 'This leaves iFit and talks to a Bluetooth machine that '
                    'supports the standard fitness machine profile.\n\n'
                    'It is experimental, and it is for equipment other than '
                    'this console. Pair the machine in Android settings first '
                    "— Stride can't discover an unpaired one. If nothing "
                    'answers, speed and incline stop responding until you '
                    'switch back.\n\n'
                    'The safety key remains the only emergency stop either way.'
              : 'This bypasses iFit and talks to the treadmill controller '
                    'directly over USB or Bluetooth.\n\n'
                    'It is experimental. Stride will ask the treadmill which '
                    'controls it has and only offer those, so what works '
                    'depends on your machine — and if nothing answers, speed, '
                    'incline and fan stop responding until you switch back.\n\n'
                    'Switching back may not be enough on its own. Taking the '
                    "USB connection means taking it from iFit, and iFit does "
                    'not always get it back — on this console it stayed '
                    'disconnected from the belt until the machine was '
                    'restarted. Expect to reboot before iFit works again.\n\n'
                    'The safety key remains the only emergency stop either way.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Keep using iFit'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: StrideColors.warning,
              foregroundColor: StrideColors.ink,
            ),
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Turn it on anyway'),
          ),
        ],
      ),
    );
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.title, this.subtitle, required this.child});

  final String title;
  final String? subtitle;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(StrideSpace.lg),
      decoration: BoxDecoration(
        color: StrideColors.panel,
        borderRadius: BorderRadius.circular(StrideRadius.lg),
        border: Border.all(color: StrideColors.line),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: Theme.of(context).textTheme.headlineMedium),
          if (subtitle != null) ...[
            const SizedBox(height: StrideSpace.xs),
            Text(subtitle!, style: Theme.of(context).textTheme.bodyMedium),
          ],
          const SizedBox(height: StrideSpace.md),
          child,
        ],
      ),
    );
  }
}

class _AndroidSettingsRow extends StatelessWidget {
  const _AndroidSettingsRow({required this.onOpen});

  final Future<void> Function() onOpen;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(StrideSpace.md),
      decoration: BoxDecoration(
        color: StrideColors.panelHigh,
        borderRadius: BorderRadius.circular(StrideRadius.md),
        border: Border.all(color: StrideColors.line),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.info_outline, color: StrideColors.warning, size: 26),
          const SizedBox(width: StrideSpace.sm),
          // The rider needs this before they tap, not after: Android blanks non-system overlays
          // over its own settings pages, so Stride's Back and Home disappear in there and this
          // console has no physical buttons to fall back on.
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Stride’s controls are hidden in there',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: StrideSpace.xxs),
                Text(
                  'Android blanks them over its own settings screens. To come back, swipe down '
                  'from the top of the screen and tap Stride.',
                  style: Theme.of(context).textTheme.bodyMedium,
                ),
              ],
            ),
          ),
          const SizedBox(width: StrideSpace.sm),
          FilledButton(
            onPressed: onOpen,
            child: const Text('Open Android settings'),
          ),
        ],
      ),
    );
  }
}

class _GrantRow extends StatelessWidget {
  const _GrantRow({required this.grant, required this.onOpen});

  final Map<String, dynamic> grant;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final granted = grant['granted'] == true;
    final label = grant['label'] as String? ?? '';
    final consequence = grant['consequence'] as String? ?? '';
    return Padding(
      padding: const EdgeInsets.only(bottom: StrideSpace.sm),
      child: Container(
        padding: const EdgeInsets.all(StrideSpace.md),
        decoration: BoxDecoration(
          color: StrideColors.panelHigh,
          borderRadius: BorderRadius.circular(StrideRadius.md),
          border: Border.all(
            color: granted ? StrideColors.line : StrideColors.warning,
            width: granted ? 1 : 1.6,
          ),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              granted ? Icons.check_circle : Icons.error_outline,
              color: granted ? StrideColors.accent : StrideColors.warning,
              size: 26,
            ),
            const SizedBox(width: StrideSpace.sm),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(label, style: Theme.of(context).textTheme.titleMedium),
                  const SizedBox(height: StrideSpace.xxs),
                  // Only the missing ones explain themselves. A granted row reciting what would
                  // break is noise the rider has to read past to find the row that matters.
                  Text(
                    granted ? 'Granted' : consequence,
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                ],
              ),
            ),
            if (!granted) ...[
              const SizedBox(width: StrideSpace.sm),
              FilledButton(
                onPressed: onOpen,
                child: const Text('Open settings'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _TrackFloorChoice extends StatelessWidget {
  const _TrackFloorChoice({required this.chosen, required this.onChanged});

  /// Null means automatic. Three states, not two: "off" and "never said" behave differently.
  final bool? chosen;
  final ValueChanged<bool?> onChanged;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        _pill(context, 'Automatic', chosen == null, () => onChanged(null)),
        const SizedBox(width: StrideSpace.sm),
        _pill(context, 'Always on', chosen == true, () => onChanged(true)),
        const SizedBox(width: StrideSpace.sm),
        _pill(context, 'Always off', chosen == false, () => onChanged(false)),
      ],
    );
  }

  Widget _pill(
    BuildContext context,
    String label,
    bool selected,
    VoidCallback onTap,
  ) {
    return Expanded(
      child: Material(
        color: selected ? StrideColors.accent : StrideColors.panelHigh,
        borderRadius: BorderRadius.circular(StrideRadius.md),
        child: InkWell(
          borderRadius: BorderRadius.circular(StrideRadius.md),
          onTap: onTap,
          child: Container(
            height: StrideSpace.minTouch,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(StrideRadius.md),
              border: Border.all(
                color: selected ? StrideColors.accent : StrideColors.line,
              ),
            ),
            child: Text(
              label,
              style: TextStyle(
                fontSize: 17,
                fontWeight: FontWeight.w700,
                color: selected ? StrideColors.ink : StrideColors.text,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Whether Stride's own launcher stands down while the track floor is drawn.
///
/// Two pills rather than three: unlike the track floor there is no useful
/// "automatic" here. The launcher is only ever behind the track once the rider
/// has asked for the track, so "decide for me" and "leave it alone" would be
/// the same answer.
class _TrackBackdropChoice extends StatelessWidget {
  const _TrackBackdropChoice({required this.blank, required this.onChanged});

  final bool blank;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        _pill(context, 'Launcher', !blank, () => onChanged(false)),
        const SizedBox(width: StrideSpace.sm),
        _pill(context, 'Plain', blank, () => onChanged(true)),
      ],
    );
  }

  Widget _pill(
    BuildContext context,
    String label,
    bool selected,
    VoidCallback onTap,
  ) {
    return Expanded(
      child: Material(
        color: selected ? StrideColors.accent : StrideColors.panelHigh,
        borderRadius: BorderRadius.circular(StrideRadius.md),
        child: InkWell(
          borderRadius: BorderRadius.circular(StrideRadius.md),
          onTap: onTap,
          child: Container(
            height: StrideSpace.minTouch,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(StrideRadius.md),
              border: Border.all(
                color: selected ? StrideColors.accent : StrideColors.line,
              ),
            ),
            child: Text(
              label,
              style: TextStyle(
                fontSize: 17,
                fontWeight: FontWeight.w700,
                color: selected ? StrideColors.ink : StrideColors.text,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Kept shut by default and behind a second tap.
///
/// The one switch in here can stop the treadmill responding, so it should not sit in the path of
/// someone who came to turn the lap oval off.
class _AdvancedSection extends StatelessWidget {
  const _AdvancedSection({
    required this.open,
    required this.onToggle,
    required this.transport,
    required this.onTransport,
    this.machineDetail,
    this.machineLinked = false,
    this.machineCapabilities,
    this.switching = false,
    this.heartRateStrap = false,
    this.heartRateStrapLinked = false,
    this.heartRateStrapName,
    this.heartRateStrapBattery,
    required this.onHeartRateStrap,
    this.inclineSpacing = 'fine',
    required this.onInclineSpacing,
    this.transportAutomatic = true,
    this.transportDetail,
  });

  final bool open;
  final VoidCallback onToggle;
  final String transport;
  final ValueChanged<String> onTransport;

  /// What the handshake concluded, written for a rider. Null before it has run.
  final String? machineDetail;

  /// Whether a machine is actually answering on the selected transport.
  final bool machineLinked;

  /// True while the switch is still opening the new link and handshaking.
  final bool switching;

  /// The machine's own answer about which controls it implements, or null if
  /// it was never asked. Each value may itself be null, meaning "unknown".
  final Map<String, dynamic>? machineCapabilities;

  /// Whether the rider has asked Stride to use a heart rate strap.
  final bool heartRateStrap;

  /// Whether a strap is actually connected right now. Distinct from the above:
  /// asking for one is not the same as finding one.
  final bool heartRateStrapLinked;

  final String? heartRateStrapName;
  final int? heartRateStrapBattery;
  final ValueChanged<bool> onHeartRateStrap;

  /// How the incline column is spaced: `'fine'` or `'coarse'`. A string rather
  /// than a bool because the platform owns the vocabulary, and a third spacing
  /// should not have to change this type to be added.
  final String inclineSpacing;
  final ValueChanged<String> onInclineSpacing;

  /// Whether the connection in use was detected rather than chosen by the rider.
  final bool transportAutomatic;

  /// What the detection probe actually found, written for a rider.
  final String? transportDetail;

  /// The transports a rider can pick, in the order they are offered.
  ///
  /// iFit first because it is the default and the recovery path. The other two
  /// are both experimental and both leave iFit, but they are not variants of
  /// one setting: one talks to the controller *underneath* this console, the
  /// other talks to a different machine entirely. A single switch could not say
  /// that, which is why this is a list rather than a toggle.
  static const List<_TransportOption> _options = [
    _TransportOption(
      id: 'glassos',
      title: 'iFit (GlassOS)',
      experimental: false,
      blurb: 'Talk to the treadmill through the console\'s own iFit service.',
    ),
    _TransportOption(
      id: 'direct',
      title: 'Direct hardware access',
      experimental: true,
      blurb:
          'Bypass iFit and drive this console\'s controller over USB or '
          'Bluetooth. Stride asks the treadmill what it supports.',
    ),
    _TransportOption(
      id: 'ftms',
      title: 'Bluetooth fitness machine',
      experimental: true,
      blurb:
          'Drive separate equipment that supports the standard fitness '
          'machine profile. Pair it in Android settings first.',
    ),
  ];

  /// What to say about one option.
  ///
  /// Every branch reports something that has actually been established rather
  /// than asserting in advance what will not work. The old copy said flatly
  /// that speed, incline and fan would not respond, which was written when
  /// nothing was wired behind the switch; on a machine that implements them it
  /// was simply untrue, and telling a rider their controls are dead when they
  /// are live is the wrong direction to be wrong in.
  String _summary(_TransportOption option) {
    // An option the rider is not on describes itself. Only the selected one has
    // findings, and attributing them to the others would report a failed BLE
    // scan underneath the iFit row.
    if (option.id != transport) return option.blurb;

    // While the switch is running, neither the old findings nor the absence of
    // new ones is the truth, so say what is actually happening instead.
    if (switching) {
      return option.id == 'glassos'
          ? 'Handing control back to iFit…'
          : 'Connecting to the machine…';
    }

    if (option.id == 'glassos') {
      return 'On. Stride is talking to the treadmill through iFit.';
    }

    final detail = machineDetail;
    if (detail != null && !machineLinked) return detail;
    if (machineLinked) {
      return 'On, and the machine answered. Stride is using the controls it '
          'said it has.';
    }
    return option.id == 'ftms'
        ? 'On. Stride is looking for a paired fitness machine.'
        : 'On. Stride is looking for the treadmill over USB and Bluetooth.';
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        TextButton.icon(
          onPressed: onToggle,
          icon: Icon(open ? Icons.expand_less : Icons.expand_more),
          label: const Text('Advanced'),
        ),
        if (open) ...[
          const SizedBox(height: StrideSpace.xs),
          _Section(
            title: 'Machine connection',
            subtitle:
                'Stride talks to the treadmill through iFit by default. The '
                'other two leave iFit: one drives this console\'s controller '
                'directly, the other drives separate Bluetooth equipment.',
            child: Column(
              children: [
                if (transportDetail != null) ...[
                  // What the probe found, and whether Stride is acting on it.
                  // A rider on a console where the automatic choice is wrong
                  // can see that in one line instead of guessing.
                  Padding(
                    padding: const EdgeInsets.only(bottom: StrideSpace.xs),
                    child: Text(
                      transportAutomatic
                          ? '$transportDetail Chosen automatically — pick one '
                                'below to override.'
                          : '$transportDetail You picked this one.',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ),
                ],
                for (final option in _options) ...[
                  if (option != _options.first)
                    const SizedBox(height: StrideSpace.xs),
                  _TransportRow(
                    option: option,
                    selected: option.id == transport,
                    summary: _summary(option),
                    // Disabled mid-switch. Choosing again while a handshake is
                    // running would queue a second one behind it, and the rider
                    // would be told about a link that had already been replaced.
                    onTap: switching ? null : () => onTransport(option.id),
                    busy: switching && option.id == transport,
                    capabilities:
                        option.id == transport &&
                            option.id != 'glassos' &&
                            machineLinked
                        ? machineCapabilities
                        : null,
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(height: StrideSpace.lg),
          _Section(
            title: 'Heart rate',
            subtitle:
                'A Bluetooth chest strap, separate from how Stride reaches the '
                'treadmill. It works on any connection above.',
            child: _HeartRateRow(
              enabled: heartRateStrap,
              linked: heartRateStrapLinked,
              name: heartRateStrapName,
              battery: heartRateStrapBattery,
              busy: switching,
              onChanged: onHeartRateStrap,
            ),
          ),
          const SizedBox(height: StrideSpace.lg),
          _Section(
            title: 'Incline quick picks',
            subtitle:
                'How far apart the buttons in the incline column are. Only '
                'applies where Stride builds that column itself — on iFit the '
                'console publishes its own quick picks and those are used as '
                'they are.',
            child: _InclineSpacingChoice(
              spacing: inclineSpacing,
              onChanged: onInclineSpacing,
            ),
          ),
        ],
      ],
    );
  }
}

/// How far apart the incline column's buttons sit.
///
/// Three pills. The coarse one is labelled with both of its steps rather than
/// as "coarse" — the two sides of flat are not spaced the same, 5% climbing
/// and 3% declining, because a treadmill that declines at all rarely passes
/// -6%, so a single 5% step would give a rider one decline button and call it
/// a range. A label that hid that would make the column look wrong on
/// arrival. The third mirrors one specific console's own physical incline
/// buttons (-6, -3, 0, 3, 6, 10, 15, 20, 25, 30, 35, 40 — confirmed against an
/// X22i's keypad) rather than any general shape, so it may offer a shorter or
/// even empty column on a machine whose range or button layout differs.
class _InclineSpacingChoice extends StatelessWidget {
  const _InclineSpacingChoice({required this.spacing, required this.onChanged});

  /// `'fine'`, `'coarse'`, or `'physical'`. Anything else is treated as the
  /// default rather than as no selection: a pill row with nothing lit looks
  /// broken, and the platform has already resolved an unreadable value to
  /// fine.
  final String spacing;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    final coarse = spacing == 'coarse';
    final physical = spacing == 'physical';
    final fine = !coarse && !physical;
    return Row(
      children: [
        _pill(context, 'Every 1%', fine, () => onChanged('fine')),
        const SizedBox(width: StrideSpace.sm),
        _pill(context, '5% up, 3% down', coarse, () => onChanged('coarse')),
        const SizedBox(width: StrideSpace.sm),
        _pill(context, 'Console buttons', physical, () => onChanged('physical')),
      ],
    );
  }

  Widget _pill(
    BuildContext context,
    String label,
    bool selected,
    VoidCallback onTap,
  ) {
    return Expanded(
      child: Material(
        color: selected ? StrideColors.accent : StrideColors.panelHigh,
        borderRadius: BorderRadius.circular(StrideRadius.md),
        child: InkWell(
          borderRadius: BorderRadius.circular(StrideRadius.md),
          onTap: onTap,
          child: Container(
            height: StrideSpace.minTouch,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(StrideRadius.md),
              border: Border.all(
                color: selected ? StrideColors.accent : StrideColors.line,
              ),
            ),
            child: Text(
              label,
              style: TextStyle(
                fontSize: 17,
                fontWeight: FontWeight.w700,
                color: selected ? StrideColors.ink : StrideColors.text,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// The heart rate strap switch and whatever it has found.///
/// Separate from the transport chooser because a strap is an accessory rather
/// than a way of reaching the machine: a rider on any of the three transports
/// can wear one, and burying it in that list would imply a choice between them.
class _HeartRateRow extends StatelessWidget {
  const _HeartRateRow({
    required this.enabled,
    required this.linked,
    required this.name,
    required this.battery,
    required this.busy,
    required this.onChanged,
  });

  final bool enabled;
  final bool linked;
  final String? name;
  final int? battery;
  final bool busy;
  final ValueChanged<bool> onChanged;

  /// What to say, reporting only what has actually been established.
  String _summary() {
    if (!enabled) {
      return 'Off. Stride shows the heart rate your machine reports, if it '
          'reports one.';
    }
    if (linked) {
      final battery = this.battery;
      final suffix = battery == null ? '' : ' Battery $battery%.';
      return 'On, and connected to ${name ?? 'a strap'}.$suffix';
    }
    // Named specifically because pairing is the fix, and Stride does not scan:
    // in a gym full of straps, scanning would offer a stranger's.
    return "On, but no strap found. Pair it in Android's Bluetooth settings "
        'first — Stride only connects to a strap you have already paired.';
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(StrideSpace.md),
      decoration: BoxDecoration(
        color: StrideColors.panelHigh,
        borderRadius: BorderRadius.circular(StrideRadius.md),
        border: Border.all(
          color: enabled && linked ? StrideColors.accent : StrideColors.line,
          width: enabled && linked ? 1.6 : 1,
        ),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Bluetooth chest strap',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: StrideSpace.xxs),
                Text(_summary(), style: Theme.of(context).textTheme.bodyMedium),
              ],
            ),
          ),
          if (busy)
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: StrideSpace.sm),
              child: SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            )
          else
            Switch(value: enabled, onChanged: onChanged),
        ],
      ),
    );
  }
}

/// One selectable transport.
class _TransportOption {
  const _TransportOption({
    required this.id,
    required this.title,
    required this.experimental,
    required this.blurb,
  });

  /// Matches `StrideSettings.Transport`, lowercased. Sent to the bridge as-is.
  final String id;
  final String title;
  final bool experimental;

  /// What this transport does, shown when it is not the selected one.
  final String blurb;
}

/// One row of the transport chooser.
class _TransportRow extends StatelessWidget {
  const _TransportRow({
    required this.option,
    required this.selected,
    required this.summary,
    required this.onTap,
    required this.busy,
    this.capabilities,
  });

  final _TransportOption option;
  final bool selected;
  final String summary;
  final VoidCallback? onTap;
  final bool busy;
  final Map<String, dynamic>? capabilities;

  @override
  Widget build(BuildContext context) {
    // Warning-coloured only when a non-default transport is actually in use.
    // Colouring the unselected rows would make merely reading this screen look
    // like something was wrong.
    final border = selected
        ? (option.experimental ? StrideColors.warning : StrideColors.accent)
        : StrideColors.line;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(StrideRadius.md),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(StrideSpace.md),
        decoration: BoxDecoration(
          color: StrideColors.panelHigh,
          borderRadius: BorderRadius.circular(StrideRadius.md),
          border: Border.all(color: border, width: selected ? 1.6 : 1),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.only(top: 2, right: StrideSpace.sm),
              child: Icon(
                selected
                    ? Icons.radio_button_checked
                    : Icons.radio_button_unchecked,
                color: selected ? border : StrideColors.textMuted,
                size: 22,
              ),
            ),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Flexible(
                        child: Text(
                          option.title,
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                      ),
                      if (option.experimental) ...[
                        const SizedBox(width: StrideSpace.xs),
                        const _ExperimentalBadge(),
                      ],
                    ],
                  ),
                  const SizedBox(height: StrideSpace.xxs),
                  Text(summary, style: Theme.of(context).textTheme.bodyMedium),
                  if (capabilities != null) ...[
                    const SizedBox(height: StrideSpace.xs),
                    _CapabilityList(capabilities: capabilities),
                  ],
                ],
              ),
            ),
            if (busy)
              const Padding(
                padding: EdgeInsets.only(left: StrideSpace.sm),
                child: SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// The machine's own answer about which controls it implements.
///
/// Sourced from the supported-register bitmask the treadmill returns during the
/// direct handshake, so this is reporting what the hardware said rather than
/// predicting what it will do. Three states, not two: a control the machine did
/// not list is genuinely absent, but one we could not ask about is unknown, and
/// showing those the same way is how the previous copy came to be wrong.
class _CapabilityList extends StatelessWidget {
  const _CapabilityList({required this.capabilities});

  final Map<String, dynamic>? capabilities;

  @override
  Widget build(BuildContext context) {
    final caps = capabilities;
    if (caps == null) return const SizedBox.shrink();
    const labels = {'speed': 'Speed', 'incline': 'Incline', 'fan': 'Fan'};
    final machineType = caps['machineType'] as String?;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Only the FTMS path knows what kind of machine it found. Naming it is
        // how a rider discovers Stride bound to the wrong peripheral: "rower"
        // under a treadmill answers the question far faster than a readout that
        // is merely blank.
        if (machineType != null) ...[
          Text(
            'Reporting as a $machineType.',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          const SizedBox(height: StrideSpace.xxs),
        ],
        for (final entry in labels.entries)
          _CapabilityRow(
            label: entry.value,
            supported: caps[entry.key] as bool?,
            detail: _range(caps, entry.key),
          ),
      ],
    );
  }

  /// The range the treadmill reported for this control, or null if it did not.
  ///
  /// Shown next to the tick because it is the most convincing evidence a rider
  /// has that the direct link is genuinely working: these numbers came off the
  /// machine, and if the framing were wrong they would be nonsense rather than
  /// a believable "0.5 – 12.0 mph".
  static String? _range(Map<String, dynamic> caps, String key) {
    final (lo, hi, unit) = switch (key) {
      'speed' => (caps['minSpeedMph'], caps['maxSpeedMph'], ' mph'),
      'incline' => (caps['minIncline'], caps['maxIncline'], '%'),
      _ => (null, null, ''),
    };
    if (lo is! num || hi is! num) return null;
    return '${lo.toStringAsFixed(1)} – ${hi.toStringAsFixed(1)}$unit';
  }
}

class _CapabilityRow extends StatelessWidget {
  const _CapabilityRow({
    required this.label,
    required this.supported,
    this.detail,
  });

  final String label;
  final bool? supported;

  /// The machine's reported range for this control, appended when it gave one.
  final String? detail;

  @override
  Widget build(BuildContext context) {
    final (icon, colour, text) = switch (supported) {
      true => (Icons.check_circle_outline, StrideColors.accent, 'Available'),
      false => (
        Icons.remove_circle_outline,
        StrideColors.textMuted,
        'Not on this treadmill',
      ),
      // Unknown stays visually distinct from unsupported. The rider should be
      // able to tell "your machine hasn't got one" from "we couldn't ask".
      null => (Icons.help_outline, StrideColors.textMuted, 'Unknown'),
    };
    // The range only means anything when the control exists, so it is appended
    // rather than shown on its own line: "Speed — Available (0.5 – 12.0 mph)".
    final suffix = supported == true && detail != null ? ' ($detail)' : '';
    return Padding(
      padding: const EdgeInsets.only(top: StrideSpace.xxs),
      child: Row(
        children: [
          Icon(icon, size: 16, color: colour),
          const SizedBox(width: StrideSpace.xs),
          Expanded(
            child: Text(
              '$label — $text$suffix',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
        ],
      ),
    );
  }
}

class _ExperimentalBadge extends StatelessWidget {
  const _ExperimentalBadge();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(
        horizontal: StrideSpace.xs,
        vertical: 2,
      ),
      decoration: BoxDecoration(
        color: StrideColors.warning,
        borderRadius: BorderRadius.circular(StrideRadius.sm),
      ),
      child: const Text(
        'EXPERIMENTAL',
        style: TextStyle(
          color: StrideColors.ink,
          fontSize: 12,
          fontWeight: FontWeight.w900,
          letterSpacing: 0.4,
        ),
      ),
    );
  }
}
