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
  double _hudHeightPx = 0;

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
      final next = await SpikeBridge.hudHeightPx();
      if (!mounted) return;
      if ((next - _hudHeightPx).abs() > 0.5) {
        setState(() => _hudHeightPx = math.max(0, next));
      }
    } catch (_) {
      if (!mounted) return;
      if (_hudHeightPx != 0) setState(() => _hudHeightPx = 0);
    }
  }

  @override
  Widget build(BuildContext context) {
    final media = MediaQuery.of(context);
    final ratio = media.devicePixelRatio <= 0 ? 1.0 : media.devicePixelRatio;
    final hudLogical = _hudHeightPx / ratio;
    final topPadding = math.max(media.padding.top, hudLogical);
    final topViewPadding = math.max(media.viewPadding.top, hudLogical);

    return MediaQuery(
      data: media.copyWith(
        padding: EdgeInsets.fromLTRB(
          media.padding.left,
          topPadding,
          media.padding.right,
          media.padding.bottom,
        ),
        viewPadding: EdgeInsets.fromLTRB(
          media.viewPadding.left,
          topViewPadding,
          media.viewPadding.right,
          media.viewPadding.bottom,
        ),
      ),
      child: widget.child,
    );
  }
}
