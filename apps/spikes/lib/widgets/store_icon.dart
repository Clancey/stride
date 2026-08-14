import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../model/appstore.dart';
import '../theme/stride_tokens.dart';
import 'app_models.dart';

/// The icon for one row of the store or the updates sheet.
///
/// Where the picture comes from depends on whether the app is on the device:
///
/// * Installed, so the real launcher icon is already local. Read it through [AppIconCache], the
///   same path the home grid uses, so a row and its tile never disagree about what an app looks
///   like.
/// * Not installed, so there is nothing local to read. Fetch the catalog's icon if it offers one.
/// * Neither, so draw the letter tile the launcher already falls back to.
///
/// Every failure lands on the letter tile. An icon is decoration on a row whose real job is a name
/// and a button, and a broken image placeholder in a list of apps reads as a broken app.
class StoreIcon extends StatelessWidget {
  const StoreIcon({
    super.key,
    required this.item,
    required this.iconCache,
    this.size = 44,
  });

  final AppstoreItem item;
  final AppIconCache iconCache;
  final double size;

  @override
  Widget build(BuildContext context) {
    if (item.isInstalled) {
      return FutureBuilder<Uint8List?>(
        future: iconCache.iconFor(item.package, sizePx: 128),
        builder: (context, snapshot) {
          final bytes = snapshot.data;
          if (bytes == null) return _letter();
          return _rounded(
            Image.memory(bytes, width: size, height: size, fit: BoxFit.cover),
          );
        },
      );
    }

    final url = item.iconUrl;
    if (url == null || url.isEmpty) return _letter();
    return _rounded(
      Image.network(
        url,
        width: size,
        height: size,
        fit: BoxFit.cover,
        // Nothing is installed from this URL, so a failure is cosmetic. Fall back silently rather
        // than showing a rider a broken-image glyph next to an app they might otherwise install.
        errorBuilder: (context, _, _) => _letter(),
        frameBuilder: (context, child, frame, wasSynchronous) {
          if (wasSynchronous || frame != null) return child;
          return _letter();
        },
      ),
    );
  }

  Widget _rounded(Widget child) => ClipRRect(
    borderRadius: BorderRadius.circular(size * 0.24),
    child: SizedBox(width: size, height: size, child: child),
  );

  Widget _letter() {
    final source = item.name.trim().isNotEmpty
        ? item.name.trim()
        : item.package;
    final letter = source.isEmpty
        ? '?'
        : String.fromCharCode(source.runes.first).toUpperCase();
    final color =
        StrideColors.appFallbacks[item.package.hashCode.abs() %
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
        letter,
        style: TextStyle(
          color: StrideColors.ink,
          fontSize: size * 0.42,
          fontWeight: FontWeight.w900,
        ),
      ),
    );
  }
}
