import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../theme/stride_tokens.dart';
import 'app_models.dart';

class AppTile extends StatelessWidget {
  const AppTile({
    super.key,
    required this.app,
    required this.iconCache,
    required this.pinned,
    required this.onLaunch,
    this.onPinToggle,
    this.onLongPress,
    this.large = false,
  });

  final LaunchableApp app;
  final AppIconCache iconCache;
  final bool pinned;
  final VoidCallback onLaunch;
  final VoidCallback? onPinToggle;
  final VoidCallback? onLongPress;
  final bool large;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final iconSize = large ? 72.0 : 56.0;

    return Material(
      color: large ? StrideColors.panelRaised : StrideColors.panel,
      borderRadius: BorderRadius.circular(StrideRadius.lg),
      clipBehavior: Clip.antiAlias,
      child: Container(
        constraints: BoxConstraints(
          minHeight: large ? 150 : 118,
          minWidth: large ? 168 : 144,
        ),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(StrideRadius.lg),
          border: Border.all(
            color: pinned ? StrideColors.accent : StrideColors.line,
            width: pinned ? 2 : 1,
          ),
        ),
        child: onPinToggle == null
            ? InkWell(
                borderRadius: BorderRadius.circular(StrideRadius.lg),
                onTap: onLaunch,
                onLongPress: onLongPress,
                child: _LaunchTileContent(
                  app: app,
                  iconCache: iconCache,
                  iconSize: iconSize,
                  large: large,
                  textStyle: large
                      ? theme.textTheme.titleLarge
                      : theme.textTheme.titleMedium,
                ),
              )
            : Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Expanded(
                    child: InkWell(
                      onTap: onLaunch,
                      child: _LaunchTileContent(
                        app: app,
                        iconCache: iconCache,
                        iconSize: iconSize,
                        large: large,
                        textStyle: theme.textTheme.titleMedium,
                      ),
                    ),
                  ),
                  DecoratedBox(
                    decoration: const BoxDecoration(
                      border: Border(
                        left: BorderSide(color: StrideColors.line),
                      ),
                    ),
                    child: SizedBox(
                      width: StrideSpace.minTouch,
                      child: InkWell(
                        onTap: onPinToggle,
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Icon(
                              pinned
                                  ? Icons.remove_circle_outline
                                  : Icons.push_pin_outlined,
                              size: 28,
                              color: pinned
                                  ? StrideColors.accent
                                  : StrideColors.text,
                            ),
                            const SizedBox(height: StrideSpace.xs),
                            Text(
                              pinned ? 'Pinned' : 'Pin',
                              style: Theme.of(context).textTheme.bodySmall,
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ],
              ),
      ),
    );
  }
}

class _LaunchTileContent extends StatelessWidget {
  const _LaunchTileContent({
    required this.app,
    required this.iconCache,
    required this.iconSize,
    required this.large,
    required this.textStyle,
  });

  final LaunchableApp app;
  final AppIconCache iconCache;
  final double iconSize;
  final bool large;
  final TextStyle? textStyle;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.all(large ? StrideSpace.lg : StrideSpace.md),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          AppIconMark(app: app, iconCache: iconCache, size: iconSize),
          const SizedBox(height: StrideSpace.md),
          Text(
            app.label,
            maxLines: large ? 2 : 1,
            overflow: TextOverflow.ellipsis,
            style: textStyle,
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
