// Adapted from FlClash v0.9.2 network_takeover.dart (VpnCard) and card.dart (GPL-3.0).
import 'package:prism_ui/shapes/shapes.dart';
import '../widgets.dart';
import 'shape_badge.dart';
import 'system_proxy_glyph.dart';

class SystemProxyPanel extends StatelessWidget {
  const SystemProxyPanel({super.key});

  @override
  Widget build(BuildContext context) {
    final box = context.box, colors = context.colorScheme;
    final enabled = box.dashboard['systemProxy'] == true;
    final title = context.tr('System HTTP proxy');
    void change(bool value) => box.act('systemProxy.set', {'enabled': value});
    // network_takeover.dart's _tileMetricsOf: the grid's control-row tile, the icon and switch inset as from the edges.
    final density = DashboardDensityScope.of(context), tile = density.isDesktop;
    final icon = !tile ? 48.0 : density.isCompact ? 36.0 : 40.0;
    return Semantics(
      container: true,
      label: title,
      child: BoxPanel(
        padding: !tile ? const EdgeInsets.symmetric(horizontal: 20, vertical: 16)
          : density.isCompact ? const EdgeInsetsDirectional.only(start: 10, end: 12) : const EdgeInsetsDirectional.only(start: 12, end: 16),
        clipBehavior: Clip.none,
        onTap: () => change(!enabled),
        child: Row(spacing: 14, children: [
          ShapeBadge(
            shape: enabled ? MaterialShapes.cookie6Sided : MaterialShapes.circle,
            color: enabled ? colors.primary : SurfaceLevel.block.colorOf(colors),
            size: icon,
            child: GlyphIcon(systemProxyGlyph, size: icon * 22 / 48,
              color: enabled ? colors.onPrimary : colors.onSurfaceVariant),
          ),
          Expanded(child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title, maxLines: tile ? 1 : null, overflow: tile ? TextOverflow.ellipsis : null, style: AppTextStyle.callout.of(context, emphasized: true)),
            if (!density.isCompact) Text(context.tr(enabled ? 'Enabled' : 'Disabled'),
              maxLines: 1, overflow: TextOverflow.ellipsis,
              style: AppTextStyle.footnote.of(context).copyWith(color: colors.onSurfaceVariant)),
          ])),
          SizedBox(
            width: Switch.trackWidth,
            height: Switch.trackHeight,
            child: OverflowBox(
              maxWidth: double.infinity,
              maxHeight: double.infinity,
              child: Switch(value: enabled, semanticLabel: title, onChanged: change),
            ),
          ),
        ]),
      ),
    );
  }
}
