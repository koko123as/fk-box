import '../widgets.dart';
import 'package:flutter/rendering.dart';
import '../theme_settings.dart';

/// Keeps each page alive while using FlClash's slide/fade timings.
class PageSwitcher extends StatefulWidget {
  const PageSwitcher({super.key, required this.index, required this.animation, required this.children, required this.onSelected});
  final int index;
  final TabAnimation animation;
  final List<Widget> children;
  final ValueChanged<int> onSelected;
  @override State<PageSwitcher> createState() => _PageSwitcherState();
}
class _PageSwitcherState extends State<PageSwitcher> with SingleTickerProviderStateMixin {
  late final _motion = AnimationController(vsync: this, value: 1);
  int? _previous;
  @override void didUpdateWidget(PageSwitcher oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.index == widget.index) return;
    _previous = oldWidget.index;
    _motion.duration = context.motionDuration(widget.animation == TabAnimation.fade ? fadeTabDuration : kTabScrollDuration);
    _motion.forward(from: 0).orCancel.then((_) { if (mounted) setState(() => _previous = null); }).catchError((_) {});
  }
  @override void dispose() { _motion.dispose(); super.dispose(); }
  @override Widget build(BuildContext context) => AnimatedBuilder(animation: _motion, builder: (context, _) {
      final fade = widget.animation == TabAnimation.fade;
      final progress = (fade ? fadeTabCurve : Curves.easeOut).transform(_motion.value);
      final direction = _previous == null || widget.index >= _previous! ? 1.0 : -1.0;
      return Stack(fit: StackFit.expand, children: [
        for (var i = 0; i < widget.children.length; i++) Offstage(
          offstage: i != widget.index && i != _previous,
          child: _TabStage(hidden: i != widget.index && i != _previous, child: ExcludeSemantics(excluding: i != widget.index, child: IgnorePointer(ignoring: i != widget.index,
            child: Opacity(opacity: fade && _previous != null ? (i == widget.index ? progress : 1 - progress) : 1,
              child: FractionalTranslation(translation: Offset(fade || _previous == null ? 0 :
                i == widget.index ? direction * (1 - progress) : -direction * progress, 0), child: widget.children[i])),
          ))),
        ),
      ]);
    });
}

/// Zeroes the paint transform of a hidden tab, as a kept-alive page in a
/// scrolling view has. prism_ui's bar items measure where they rest through
/// that transform: a hidden tab's items would otherwise lift into the root
/// overlay as a page is pushed and paint over it as ghosts.
class _TabStage extends SingleChildRenderObjectWidget {
  const _TabStage({required this.hidden, super.child});
  final bool hidden;
  @override RenderObject createRenderObject(BuildContext context) => _RenderTabStage(hidden);
  @override void updateRenderObject(BuildContext context, _RenderTabStage renderObject) => renderObject.hidden = hidden;
}

class _RenderTabStage extends RenderProxyBox {
  _RenderTabStage(this.hidden);
  bool hidden;
  @override void applyPaintTransform(RenderBox child, Matrix4 transform) {
    if (hidden) {
      transform.setZero();
    } else {
      super.applyPaintTransform(child, transform);
    }
  }
}
