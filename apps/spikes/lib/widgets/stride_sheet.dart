import 'package:flutter/material.dart';

import '../theme/stride_tokens.dart';

/// Shows a bottom sheet that fits the console's *visible* window.
///
/// The overlay's HUD draws its own bars over the top and bottom of the screen in separate system
/// windows, which Flutter cannot see. A stock [showModalBottomSheet] therefore sizes itself against
/// the full 1080-pixel window and runs its last rows underneath the HUD's bottom bar — every sheet
/// in the launcher was clipped this way, and the overlay-off warning was clipped mid-sentence.
///
/// This constrains the sheet to the height left over once the HUD's top inset is removed (the
/// bottom is handled by the [SafeArea] around the content, which reads the same merged padding),
/// and scrolls the body so long copy degrades into a scroll rather than an overflow.
Future<T?> showStrideSheet<T>({
  required BuildContext context,
  required WidgetBuilder builder,
  bool scrollable = true,
}) {
  final media = MediaQuery.of(context);
  return showModalBottomSheet<T>(
    context: context,
    backgroundColor: StrideColors.panelRaised,
    showDragHandle: true,
    isScrollControlled: true,
    constraints: BoxConstraints(
      maxHeight: (media.size.height - media.padding.top - StrideSpace.lg).clamp(
        240.0,
        media.size.height,
      ),
    ),
    builder: (context) {
      final content = SafeArea(child: builder(context));
      return scrollable ? SingleChildScrollView(child: content) : content;
    },
  );
}
