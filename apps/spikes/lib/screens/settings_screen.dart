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
  bool _loading = true;
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
                    child: _TrackFloorChoice(
                      chosen: _trackFloorChosen,
                      onChanged: (value) async {
                        await SpikeBridge.trackFloorSet(value);
                        if (!mounted) return;
                        setState(() => _trackFloorChosen = value);
                      },
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
      const SnackBar(content: Text("Android settings wouldn't open on this console.")),
    );
  }

  Future<void> _setTransport(bool direct) async {
    if (direct) {
      final confirmed = await _confirmDirect();
      if (confirmed != true) return;
    }
    final ok = await SpikeBridge.transportSet(direct ? 'direct' : 'glassos');
    if (!mounted) return;
    if (!ok) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text("That transport couldn't be saved.")),
      );
      return;
    }
    await _load();
  }

  Future<bool?> _confirmDirect() => showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      backgroundColor: StrideColors.panelRaised,
      title: const Text('Turn on direct hardware access?'),
      content: const Text(
        'This bypasses iFit and talks to the treadmill controller directly. '
        'It is experimental and unfinished: nothing is connected behind it yet, '
        'so speed, incline and fan will stop responding until you switch back.\n\n'
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
          const Icon(
            Icons.info_outline,
            color: StrideColors.warning,
            size: 26,
          ),
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
  });

  final bool open;
  final VoidCallback onToggle;
  final String transport;
  final ValueChanged<bool> onTransport;

  @override
  Widget build(BuildContext context) {
    final direct = transport == 'direct';
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
                'Stride talks to the treadmill through iFit, which is the only path that works '
                'today.',
            child: Container(
              padding: const EdgeInsets.all(StrideSpace.md),
              decoration: BoxDecoration(
                color: StrideColors.panelHigh,
                borderRadius: BorderRadius.circular(StrideRadius.md),
                border: Border.all(
                  color: direct ? StrideColors.warning : StrideColors.line,
                  width: direct ? 1.6 : 1,
                ),
              ),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Text(
                              'Direct hardware access',
                              style: Theme.of(context).textTheme.titleMedium,
                            ),
                            const SizedBox(width: StrideSpace.xs),
                            const _ExperimentalBadge(),
                          ],
                        ),
                        const SizedBox(height: StrideSpace.xxs),
                        Text(
                          direct
                              ? 'On. Speed, incline and fan will not respond until you turn this '
                                    'back off.'
                              : 'Bypass iFit and drive the controller directly. Unfinished — '
                                    'nothing is connected behind it yet.',
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                      ],
                    ),
                  ),
                  Switch(value: direct, onChanged: onTransport),
                ],
              ),
            ),
          ),
        ],
      ],
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
