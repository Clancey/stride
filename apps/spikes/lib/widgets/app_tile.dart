import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../theme/stride_tokens.dart';
import 'app_models.dart';

/// Metrics for one app in a launcher grid.
///
/// Tiles are a fixed size, always. A launcher whose icons grow because you only
/// pinned two apps reads as a row of buttons rather than a set of apps, and the
/// whole grid reshuffles under the rider's hand every time they pin one more.
class AppTileMetrics {
  const AppTileMetrics._({
    required this.icon,
    required this.width,
    required this.labelLines,
  });

  /// The home grid. Larger, because this is what a rider reaches for mid-walk.
  static const home = AppTileMetrics._(icon: 96, width: 168, labelLines: 2);

  /// The browse sheet, used standing still, where seeing more at once wins.
  static const browse = AppTileMetrics._(icon: 76, width: 148, labelLines: 2);

  final double icon;
  final double width;
  final int labelLines;

  static const double _labelSize = 16;
  static const double _labelLeading = 1.25;
  static const double gutter = StrideSpace.md;

  double get labelHeight => _labelSize * _labelLeading * labelLines;

  /// Icon, gap, and label — so a [Wrap] can reserve identical rows.
  ///
  /// The icon block is [StrideSpace.xs] taller than the icon itself when the tile
  /// can be pinned: the pin badge rides above the icon's top edge. Leaving that
  /// out overflows every pinnable tile by exactly that much.
  double get height =>
      icon + StrideSpace.xs + StrideSpace.sm + labelHeight + StrideSpace.md * 2;
}

class AppTile extends StatelessWidget {
  const AppTile({
    super.key,
    required this.app,
    required this.iconCache,
    required this.pinned,
    required this.onLaunch,
    this.onPinToggle,
    this.onLongPress,
    this.metrics = AppTileMetrics.home,
  });

  final LaunchableApp app;
  final AppIconCache iconCache;
  final bool pinned;
  final VoidCallback onLaunch;
  final VoidCallback? onPinToggle;
  final VoidCallback? onLongPress;
  final AppTileMetrics metrics;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: metrics.width,
      height: metrics.height,
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(StrideRadius.lg),
        child: InkWell(
          borderRadius: BorderRadius.circular(StrideRadius.lg),
          onTap: onLaunch,
          onLongPress: onLongPress,
          child: Padding(
            padding: const EdgeInsets.symmetric(
              vertical: StrideSpace.md,
              horizontal: StrideSpace.xs,
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                _Mark(
                  app: app,
                  iconCache: iconCache,
                  size: metrics.icon,
                  pinned: pinned,
                  onPinToggle: onPinToggle,
                ),
                const SizedBox(height: StrideSpace.sm),
                SizedBox(
                  height: metrics.labelHeight,
                  child: Text(
                    app.label,
                    maxLines: metrics.labelLines,
                    textAlign: TextAlign.center,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: AppTileMetrics._labelSize,
                      height: AppTileMetrics._labelLeading,
                      fontWeight: FontWeight.w600,
                      color: StrideColors.text,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Mark extends StatelessWidget {
  const _Mark({
    required this.app,
    required this.iconCache,
    required this.size,
    required this.pinned,
    required this.onPinToggle,
  });

  final LaunchableApp app;
  final AppIconCache iconCache;
  final double size;
  final bool pinned;
  final VoidCallback? onPinToggle;

  @override
  Widget build(BuildContext context) {
    final icon = AppIconMark(app: app, iconCache: iconCache, size: size);
    if (onPinToggle == null) {
      return icon;
    }
    // The pin rides on the icon's corner rather than splitting the tile in two.
    // Launching is the common act; pinning is the occasional one, and it should
    // not cost half the tile.
    return SizedBox(
      width: size + StrideSpace.md,
      height: size + StrideSpace.xs,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Positioned(left: 0, top: StrideSpace.xs, child: icon),
          Positioned(
            right: 0,
            top: 0,
            child: Material(
              color: pinned ? StrideColors.accent : StrideColors.panelHigh,
              shape: const CircleBorder(
                side: BorderSide(color: StrideColors.ink, width: 2),
              ),
              clipBehavior: Clip.antiAlias,
              child: InkWell(
                onTap: onPinToggle,
                child: SizedBox(
                  width: 38,
                  height: 38,
                  child: Icon(
                    pinned ? Icons.push_pin : Icons.push_pin_outlined,
                    size: 20,
                    color: pinned ? StrideColors.ink : StrideColors.text,
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class AppIconMark extends StatelessWidget {
  const AppIconMark({
    super.key,
    required this.app,
    required this.iconCache,
    required this.size,
  });

  final LaunchableApp app;
  final AppIconCache iconCache;
  final double size;

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<Uint8List?>(
      future: iconCache.iconFor(app.package),
      builder: (context, snapshot) {
        final bytes = snapshot.data;
        if (bytes != null) {
          return ClipRRect(
            borderRadius: BorderRadius.circular(size * 0.24),
            child: Image.memory(
              bytes,
              width: size,
              height: size,
              fit: BoxFit.cover,
            ),
          );
        }
        return _FallbackIcon(app: app, size: size);
      },
    );
  }
}

class _FallbackIcon extends StatelessWidget {
  const _FallbackIcon({required this.app, required this.size});

  final LaunchableApp app;
  final double size;

  @override
  Widget build(BuildContext context) {
    final color =
        StrideColors.appFallbacks[app.package.hashCode.abs() %
            StrideColors.appFallbacks.length];
    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(size * 0.24),
      ),
      child: Text(
        app.fallbackLetter,
        style: TextStyle(
          color: StrideColors.ink,
          fontSize: size * 0.42,
          fontWeight: FontWeight.w900,
        ),
      ),
    );
  }
}

/// The trailing cell of the home grid. Same footprint as an app, so the row
/// reads as one continuous set rather than a grid plus a button.
class AddAppTile extends StatelessWidget {
  const AddAppTile({
    super.key,
    required this.onPressed,
    this.metrics = AppTileMetrics.home,
  });

  final VoidCallback onPressed;
  final AppTileMetrics metrics;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: metrics.width,
      height: metrics.height,
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(StrideRadius.lg),
        child: InkWell(
          borderRadius: BorderRadius.circular(StrideRadius.lg),
          onTap: onPressed,
          child: Padding(
            padding: const EdgeInsets.symmetric(
              vertical: StrideSpace.md,
              horizontal: StrideSpace.xs,
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  width: metrics.icon,
                  height: metrics.icon,
                  decoration: BoxDecoration(
                    color: StrideColors.panel,
                    borderRadius: BorderRadius.circular(metrics.icon * 0.24),
                    border: Border.all(color: StrideColors.line),
                  ),
                  child: Icon(
                    Icons.add,
                    size: metrics.icon * 0.38,
                    color: StrideColors.accent,
                  ),
                ),
                const SizedBox(height: StrideSpace.sm),
                SizedBox(
                  height: metrics.labelHeight,
                  child: const Text(
                    'Add app',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: AppTileMetrics._labelSize,
                      height: AppTileMetrics._labelLeading,
                      fontWeight: FontWeight.w600,
                      color: StrideColors.textMuted,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
