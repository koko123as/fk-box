// Adapted from FlClash v0.9.3 lib/views/dashboard/dashboard.dart (_DesktopDashboard)
// and the desktop cards in widgets/ (traffic.dart SpeedCard, outbound_mode.dart
// OutboundModeBar, connection_stats.dart, proxy_groups.dart ProxyGroupsCard and
// ProxyGroupsSummaryCard), GPL-3.0. fk-box has no service-status probe or DNS
// feed, so the grid has no ServiceStatus card and the stats card has no DNS
// cell; the profile card takes the freed middle cell.
import 'dart:math' as math;
import '../widgets.dart';
import '../pages/proxies.dart';
import '../pages/requests.dart';
import 'connected_buttons.dart';
import 'dashboard_chip.dart';
import 'data_subscription.dart';
import 'intranet_panel.dart';
import 'memory_panel.dart';
import 'profile_panel.dart';
import 'proxy_groups_panel.dart';
import 'status_panel.dart';
import 'system_proxy_panel.dart';

const _maxColumnWidth = 440.0;

/// connections_table.dart's connectionsTableMinHeight: tab inset + tab + gap + header + three rows.
double _groupListMinHeight(DashboardDensity density, double scale) {
  final tab = density.isCompact ? 32.0 : 36.0, row = density.isCompact ? 32.0 : 34.0;
  return dashboardCardRadius(density) - tab / 2 + tab * scale + 12 + (32 + row * 3) * scale;
}

/// Four columns from 860 dp, else two; every column at most 440 dp (× text
/// scale) with the grid centred. A window too short for the group list, and
/// every two-column one, gets the summary cards, so no list scrolls inside a
/// page that scrolls.
class DashboardGrid extends StatelessWidget {
  const DashboardGrid({super.key, required this.density, required this.scale, required this.columns,
    required this.gap, required this.top, required this.bottom, required this.banners, required this.controller});
  final DashboardDensity density;
  final double scale, gap, top, bottom;
  final int columns;
  final List<Widget> banners;
  final ScrollController controller;

  @override Widget build(BuildContext context) {
    final box = context.box;
    final compact = density.isCompact;
    final topHeight = (compact ? 176 : 208) * scale;
    final controlHeight = (compact ? 56 : 64) * scale;
    final middleHeight = (compact ? 152 : 176) * scale;
    final summaryHeight = (compact ? 112 : 120) * scale;
    final listHeight = _groupListMinHeight(density, scale);
    final modes = (box.dashboard['modes'] as List? ?? const []).isNotEmpty;
    final systemProxy = box.dashboard['systemProxyAvailable'] == true;
    return DashboardDensityScope(density: density, scale: scale, child: LayoutBuilder(builder: (context, constraints) {
      const startPad = 16.0, endPad = 24.0;
      final room = constraints.maxWidth - startPad - endPad - gap * (columns - 1);
      final column = math.max(0.0, math.min(room / columns, _maxColumnWidth * scale));
      final inset = math.max(0.0, (room - column * columns) / 2);
      double span(int count) => column * count + gap * (count - 1);
      Widget gridRow(double height, List<(int, Widget)> cards) => SizedBox(height: height, child: Row(spacing: gap, children: [
        for (final (count, card) in cards) SizedBox(width: span(count), child: card),
      ]));
      // The control row: the mode bar beside fk-box's System HTTP proxy tile (FlClash's VPN tile slot).
      final half = columns ~/ 2;
      final controls = <(int, Widget)>[
        if (modes) (systemProxy && columns == 4 ? half : columns, const _OutboundModeBar()),
        if (systemProxy) (modes && columns == 4 ? half : columns, const SystemProxyPanel()),
      ];
      final padding = EdgeInsetsDirectional.fromSTEB(startPad + inset, top, endPad + inset, 24 + bottom);
      if (columns == 2) {
        return SingleChildScrollView(key: const PageStorageKey('dashboard-grid'), controller: controller, padding: padding,
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, spacing: gap, children: [
            ...banners,
            gridRow(topHeight, [(2, const StatusPanel())]),
            gridRow(topHeight, [(1, const _SpeedCard(upload: true)), (1, const _SpeedCard(upload: false))]),
            // dashboard.dart stacks the mode bar and the switches in two columns.
            for (final control in controls) gridRow(controlHeight, [(2, control.$2)]),
            gridRow(middleHeight, [(1, const IntranetPanel()), (1, const ProfilePanel())]),
            gridRow(middleHeight, [(1, const MemoryPanel()), (1, const _ProxyGroupsSummaryCard())]),
            gridRow(summaryHeight, [(2, const _ConnectionStatsCard())]),
          ]));
      }
      final rowsHeight = top + 24 + bottom + topHeight + middleHeight + gap * 2 + (controls.isEmpty ? 0 : controlHeight + gap);
      final hasBanners = banners.isNotEmpty;
      final listFits = !hasBanners && constraints.maxHeight >= rowsHeight + gap + listHeight;
      final fills = !hasBanners && constraints.maxHeight >= rowsHeight + gap + summaryHeight;
      final bottomCards = <(int, Widget)>[
        (2, const _ConnectionStatsCard()),
        (2, listFits ? const _ProxyGroupsCard() : const _ProxyGroupsSummaryCard()),
      ];
      final children = <Widget>[
        ...banners,
        gridRow(topHeight, [(2, const StatusPanel()), (1, const _SpeedCard(upload: true)), (1, const _SpeedCard(upload: false))]),
        if (controls.isNotEmpty) gridRow(controlHeight, controls),
        gridRow(middleHeight, [(1, const IntranetPanel()), (2, const ProfilePanel()), (1, const MemoryPanel())]),
        if (fills) Expanded(child: Row(spacing: gap, children: [
          for (final (count, card) in bottomCards) SizedBox(width: span(count), child: card),
        ])) else gridRow(summaryHeight, bottomCards),
      ];
      if (fills) {
        return Padding(padding: padding, child: Column(crossAxisAlignment: CrossAxisAlignment.start, spacing: gap, children: children));
      }
      return SingleChildScrollView(key: const PageStorageKey('dashboard-grid'), controller: controller, padding: padding,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, spacing: gap, children: children));
    }));
  }
}

/// widgets/traffic.dart SpeedCard.
class _SpeedCard extends StatelessWidget {
  const _SpeedCard({required this.upload});
  final bool upload;
  @override Widget build(BuildContext context) {
    final box = context.watchBox(BoxAspect.traffic), colors = context.colorScheme;
    final density = DashboardDensityScope.of(context), scale = DashboardDensityScope.scaleOf(context);
    final compact = density.isCompact;
    final (padX, padY) = dashboardCardPadding(density);
    final history = upload ? box.uploadHistory : box.downloadHistory;
    final current = bytes(box.rates[upload ? 'upload' : 'download'] as num? ?? 0).split(' ');
    final peak = history.fold<double>(0, math.max);
    final label = AppTextStyle.footnote.of(context).toTabular.copyWith(color: colors.onSurfaceVariant);
    final figure = compact ? AppTextStyle.largeTitle : AppTextStyle.display;
    return BoxPanel(padding: EdgeInsets.zero, child: Stack(fit: StackFit.expand, children: [
      Positioned(left: 0, right: 0, bottom: 0, height: compact ? 62 : 88, child: LineChart(
        values: List.of(history), revision: box.sampleRevision, capacity: 30, minScale: 8 * 1024, baselineRatio: 1,
        color: upload ? Color.alphaBlend(colors.primary.withValues(alpha: .45), SurfaceLevel.card.colorOf(colors)) : colors.primary,
      )),
      Padding(padding: EdgeInsets.symmetric(horizontal: padX, vertical: padY), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(height: 24 * scale, child: Row(spacing: 8, children: [
          GlyphIcon(upload ? AppGlyphs.arrowUp : AppGlyphs.arrowDown, size: 18, color: upload ? colors.onSurfaceVariant : colors.primary),
          Text(context.tr(upload ? 'Upload' : 'Download'), maxLines: 1, style: AppTextStyle.subheadline.of(context, emphasized: true)),
          Expanded(child: Text('${context.tr('Peak')} ${bytes(peak)}/s', maxLines: 1, overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.end, style: label)),
        ])),
        SizedBox(height: compact ? 6 : 10),
        Text.rich(TextSpan(children: [
          TextSpan(text: current.first, style: figure.of(context, emphasized: true).toTabular.copyWith(letterSpacing: -.02 * figure.size)),
          TextSpan(text: ' ${current.last}/s', style: AppTextStyle.subheadline.of(context, emphasized: true).copyWith(color: colors.onSurfaceVariant)),
        ]), maxLines: 1, overflow: TextOverflow.clip),
        const SizedBox(height: 2),
        Text('${context.tr('Total')} ${box.dashboard[upload ? 'uploadTotal' : 'downloadTotal'] ?? '0 B'}',
          maxLines: 1, overflow: TextOverflow.ellipsis, style: label),
      ])),
    ]));
  }
}

const _modeLabels = {'rule': 'Rule', 'global': 'Global', 'direct': 'Direct'};
final _modeGlyphs = {'rule': AppGlyphs.rules, 'global': AppGlyphs.proxies, 'direct': AppGlyphs.target};

/// widgets/outbound_mode.dart OutboundModeBar: the control row's mode switch;
/// its buttons sit as far in from the card's ends as from its edges.
class _OutboundModeBar extends StatelessWidget {
  const _OutboundModeBar();
  @override Widget build(BuildContext context) {
    final box = context.box, colors = context.colorScheme, density = DashboardDensityScope.of(context);
    const inset = 8.0;
    final outer = dashboardCardRadius(density) - inset;
    final modes = (box.dashboard['modes'] as List? ?? const []).whereType<String>().toList();
    final selected = (box.dashboard['mode'] as String? ?? '').toLowerCase();
    return BoxPanel(padding: EdgeInsetsDirectional.fromSTEB(dashboardCardPadding(density).$1, inset, inset, inset),
      child: LayoutBuilder(builder: (context, constraints) => Row(spacing: 16, children: [
        GlyphIcon(AppGlyphs.split, size: 18, color: colors.onSurfaceVariant),
        ConstrainedBox(constraints: BoxConstraints(maxWidth: constraints.maxWidth / 4), child: Text(context.tr('Outbound mode'),
          maxLines: 1, overflow: TextOverflow.ellipsis, style: AppTextStyle.subheadline.of(context, emphasized: true))),
        Expanded(child: DashboardConnectedGroup(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          for (final (index, mode) in modes.indexed) Expanded(key: ValueKey(mode), child: DashboardConnectedButton(
            selected: mode.toLowerCase() == selected, first: index == 0, last: index == modes.length - 1,
            outer: outer, inner: 10, background: SurfaceLevel.block.colorOf(colors),
            semanticLabel: context.tr(_modeLabels[mode.toLowerCase()] ?? mode),
            onTap: () { if (mode.toLowerCase() != selected) box.act('mode.set', {'mode': mode}); },
            builder: (foreground) => Row(mainAxisAlignment: MainAxisAlignment.center, spacing: 6, children: [
              GlyphIcon(_modeGlyphs[mode.toLowerCase()] ?? AppGlyphs.split, size: 18, color: foreground),
              Flexible(child: Text(context.tr(_modeLabels[mode.toLowerCase()] ?? mode), maxLines: 1, overflow: TextOverflow.ellipsis,
                style: AppTextStyle.subheadline.of(context, emphasized: true).copyWith(color: foreground))),
            ]),
          )),
        ])),
      ])));
  }
}

/// connections_table.dart's dashboardCount: thousands shortened past 9999.
String _dashboardCount(int count) => count < 10000 ? '$count' : count < 1000000 ? '${count ~/ 1000}K' : '${count ~/ 1000000}M';

/// widgets/connection_stats.dart: the counts of the live and recent feeds,
/// each opening its list. Counts come from the connections feed the Requests
/// page already reads, subscribed while the card is visible.
class _ConnectionStatsCard extends StatelessWidget {
  const _ConnectionStatsCard();
  @override Widget build(BuildContext context) {
    final box = context.watchBox(BoxAspect.connections), density = DashboardDensityScope.of(context);
    final closed = box.connections.where((connection) => (connection['closedAt'] as num? ?? 0) > 0).length;
    final live = box.connections.length - closed;
    const inset = 8.0;
    final shape = AppShape.all(dashboardCardRadius(density) - inset);
    Widget cell(int count, String label, String feed) => Expanded(child: PressSurface(fill: Colors.transparent, shape: shape,
      childFills: true, onPressed: () => openForm(context, RequestsPage(initialFeed: feed)),
      child: Padding(padding: const EdgeInsets.symmetric(vertical: 10), child: Column(mainAxisAlignment: MainAxisAlignment.center, spacing: 2, children: [
        Text(_dashboardCount(count), maxLines: 1, style: AppTextStyle.title2.of(context, emphasized: true).toTabular.copyWith(letterSpacing: 0)),
        Text(context.tr(label), maxLines: 1, overflow: TextOverflow.ellipsis,
          style: AppTextStyle.footnote.of(context).copyWith(color: context.colorScheme.onSurfaceVariant)),
      ]))));
    return BoxDataSubscription(data: BoxData.connections, child: BoxPanel(padding: const EdgeInsets.all(inset), child: Row(
      crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        cell(live, 'Live connections', 'live'),
        cell(closed, 'Recent requests', 'recent'),
      ])));
  }
}

class _GroupsHeader extends StatelessWidget {
  const _GroupsHeader({required this.count});
  final bool count;
  @override Widget build(BuildContext context) {
    final groups = context.box.groups;
    final timeout = <String>{for (final group in groups) for (final node in rows(group['items']))
      if ((node['testedAt'] as num? ?? 0) > 0 && (node['delay'] as num? ?? 0) <= 0) node['tag'].toString()};
    return PanelTitle('Proxy groups', AppGlyphs.layers, trailing: Row(mainAxisSize: MainAxisSize.min, spacing: 6, children: [
      if (count && groups.isNotEmpty) DashboardChip(label: '${groups.length}'),
      if (timeout.isNotEmpty) DashboardChip(label: '${context.tr('Timeout')} ${timeout.length}', tone: DashboardChipTone.warn),
      Padding(padding: const EdgeInsetsDirectional.only(start: 2), child: SizedBox.square(dimension: 32, child: IconButton(
        padding: EdgeInsets.zero, tooltip: context.tr('Proxy groups'), icon: const GlyphIcon(AppGlyphs.chevronForward, size: 18),
        onPressed: groups.isEmpty ? null : () => openForm(context, const ProxiesPage())))),
    ]));
  }
}

class _GroupsEmptySummary extends StatelessWidget {
  const _GroupsEmptySummary();
  @override Widget build(BuildContext context) => Text(
    context.tr(context.box.running ? 'No proxy groups in this configuration' : 'Start the service to view proxy groups'),
    maxLines: 2, overflow: TextOverflow.ellipsis, style: AppTextStyle.subheadline.of(context, emphasized: true));
}

/// The group count, for a desktop page too short for the tiles.
class _ProxyGroupsSummaryCard extends StatelessWidget {
  const _ProxyGroupsSummaryCard();
  @override Widget build(BuildContext context) {
    final groups = context.box.groups, density = DashboardDensityScope.of(context);
    final (padX, padY) = dashboardCardPadding(density);
    final figure = density.isCompact ? AppTextStyle.largeTitle : AppTextStyle.display;
    return BoxPanel(padding: EdgeInsets.symmetric(horizontal: padX, vertical: padY), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const _GroupsHeader(count: false),
      const Spacer(),
      if (groups.isEmpty) const _GroupsEmptySummary() else Row(spacing: 12, children: [
        Expanded(child: Text.rich(TextSpan(children: [
          TextSpan(text: '${groups.length}', style: figure.of(context, emphasized: true).toTabular.copyWith(letterSpacing: 0)),
          TextSpan(text: ' ${context.tr('Groups')}', style: AppTextStyle.subheadline.of(context).copyWith(color: context.colorScheme.onSurfaceVariant)),
        ]), maxLines: 1, overflow: TextOverflow.clip)),
        GroupIconStack(groups: groups, size: density.isCompact ? 34 : 40),
      ]),
      const Spacer(),
    ]));
  }
}

/// The tall list of group tiles, for a desktop window with room below the rows.
class _ProxyGroupsCard extends StatelessWidget {
  const _ProxyGroupsCard();
  @override Widget build(BuildContext context) {
    final colors = context.colorScheme, groups = context.box.groups;
    final density = DashboardDensityScope.of(context), scale = DashboardDensityScope.scaleOf(context);
    final compact = density.isCompact;
    final (padX, padY) = dashboardCardPadding(density);
    final tileHeight = (compact ? 52 : 60) * scale, tileGap = compact ? 6.0 : 8.0;
    const blockInset = 8.0;
    return BoxPanel(padding: EdgeInsetsDirectional.fromSTEB(padX, padY, padX, 0), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const _GroupsHeader(count: true),
      const SizedBox(height: 12),
      Expanded(child: groups.isEmpty
        ? Padding(padding: EdgeInsets.only(bottom: padY), child: const Center(child: _GroupsEmptySummary()))
        : LayoutBuilder(builder: (context, constraints) {
            final columns = constraints.maxWidth >= 172 * scale * 2 + tileGap ? 2 : 1;
            return GridView.builder(
              padding: EdgeInsets.only(bottom: padY),
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(crossAxisCount: columns,
                mainAxisSpacing: tileGap, crossAxisSpacing: tileGap, mainAxisExtent: tileHeight),
              itemCount: groups.length,
              itemBuilder: (context, index) {
                final group = groups[index], tag = group['tag'].toString();
                final selected = group['selected']?.toString() ?? '';
                final icon = tileHeight - blockInset * 2;
                return PressSurface(key: ValueKey(tag), fill: SurfaceLevel.block.colorOf(colors), shape: AppShape.all(20),
                  onPressed: () => openForm(context, const ProxiesPage()),
                  child: Padding(padding: const EdgeInsets.all(blockInset), child: Row(spacing: 10, children: [
                    Container(width: icon, height: icon, alignment: Alignment.center,
                      decoration: ShapeDecoration(color: SurfaceLevel.inset.colorOf(colors), shape: AppShape.all(14)),
                      child: EmojiText(String.fromCharCodes(tag.runes.take(2)).toUpperCase(), maxLines: 1,
                        style: AppTextStyle.caption1.of(context, emphasized: true).copyWith(color: colors.onSurfaceVariant))),
                    Expanded(child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
                      EmojiText(tag, maxLines: 1, overflow: TextOverflow.ellipsis, style: AppTextStyle.subheadline.of(context, emphasized: true)),
                      if (selected.isNotEmpty) EmojiText(selected, maxLines: 1, overflow: TextOverflow.ellipsis,
                        style: AppTextStyle.caption1.of(context).copyWith(color: colors.onSurfaceVariant)),
                    ])),
                  ])));
              },
            );
          })),
    ]));
  }
}
