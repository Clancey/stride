import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../bridge.dart';

class HudInset extends StatefulWidget {
  const HudInset({super.key, required this.child});

  final Widget child;

  @override
  State<HudInset> createState() => _HudInsetState();
}

class _HudInsetState extends State<HudInset> {
  Timer? _timer;
  EdgeInsets _hudPx = EdgeInsets.zero;

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 2), (_) => _refresh());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    try {
      final next = await SpikeBridge.hudInsetsPx();
      if (!mounted) return;
      if (_differs(next, _hudPx)) setState(() => _hudPx = next);
    } catch (_) {
      if (!mounted) return;
      if (_hudPx != EdgeInsets.zero) setState(() => _hudPx = EdgeInsets.zero);
    }
  }

  static bool _differs(EdgeInsets a, EdgeInsets b) =>
      (a.left - b.left).abs() > 0.5 ||
      (a.top - b.top).abs() > 0.5 ||
      (a.right - b.right).abs() > 0.5 ||
      (a.bottom - b.bottom).abs() > 0.5;

  @override
  Widget build(BuildContext context) {
    final media = MediaQuery.of(context);
    final ratio = media.devicePixelRatio <= 0 ? 1.0 : media.devicePixelRatio;
    final hud = _hudPx / ratio;

    // Take the larger of the system inset and ours on each edge. The overlay sits on top of the
    // system bars, so the two are alternatives rather than additions — summing them would push
    // content twice as far in and leave a visible dead band.
    EdgeInsets merge(EdgeInsets base) => EdgeInsets.fromLTRB(
      math.max(base.left, hud.left),
      math.max(base.top, hud.top),
      math.max(base.right, hud.right),
      math.max(base.bottom, hud.bottom),
    );

    return MediaQuery(
      data: media.copyWith(
        padding: merge(media.padding),
        viewPadding: merge(media.viewPadding),
      ),
      child: widget.child,
    );
  }
}
