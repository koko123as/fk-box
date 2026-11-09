import 'package:flutter/services.dart';
import '../widgets.dart';
import '../components/record.dart';
import '../components/record_filter.dart';
import '../components/data_subscription.dart';

/// The app a connection came from: its first package, else the process path's last segment.
String connectionApp(Json c) {
  final process = c['process'];
  if (process is! Map) return '';
  final packages = process['packages'];
  if (packages is List) {
    for (final item in packages) {
      if (item is String && item.isNotEmpty) return item;
    }
  }
  final path = process['path'];
  return path is String ? path.split('/').last : '';
}

/// The outbounds a connection went through, the matched outbound first.
List<String> connectionChain(Json c) {
  final chain = [for (final item in c['chain'] as List? ?? const []) if (item is String && item.isNotEmpty) item];
  if (chain.isNotEmpty) return chain;
  final outbound = c['outbound'];
  return outbound is String && outbound.isNotEmpty ? [outbound] : const [];
}

/// common.dart's trackerInfoFacets for fk-box's connection records (no MITM flag on a connection).
final connectionFacets = <RecordFacet<Json>>[
  RecordFacet<Json>(RecordFacetKind.network, (c) => [(c['network']?.toString() ?? '').toLowerCase()], offered: true, order: const ['tcp', 'udp'], labelOf: (value) => value.toUpperCase()),
  RecordFacet<Json>(RecordFacetKind.rule, (c) => [c['rule'] is String ? c['rule'] as String : ''], offered: true),
  RecordFacet<Json>(RecordFacetKind.app, (c) => [connectionApp(c)]),
  RecordFacet<Json>(RecordFacetKind.proxy, connectionChain),
];

/// common.dart's trackerInfoGroupers.
final connectionGroupers = <RecordGrouper<Json>>[
  RecordGrouper<Json>(RecordGrouping.app, connectionApp),
  RecordGrouper<Json>(RecordGrouping.host, (c) => (c['displayDestination'] ?? c['destination'])?.toString() ?? ''),
  RecordGrouper<Json>(RecordGrouping.rule, (c) => c['rule'] is String ? c['rule'] as String : ''),
  RecordGrouper<Json>(RecordGrouping.exit, (c) => connectionChain(c).lastOrNull ?? ''),
];

/// requests.dart's _RecentFeed ([recent]: closed connections, the kept history,
/// or every connection when "All" is chosen) and _LiveFeed (running
/// connections with close-all), each with its own record filter and grouping.
class ConnectionsFeed extends StatefulWidget {
  const ConnectionsFeed({super.key, required this.recent, required this.title, this.titleMenuItems = const []});
  final bool recent;
  final String title;
  final List<PopupMenuItem> titleMenuItems;
  @override State<ConnectionsFeed> createState() => _ConnectionsFeedState();
}

class _ConnectionsFeedState extends State<ConnectionsFeed> {
  String _query = '';
  late String _filter = widget.recent ? 'Closed' : 'Active', _sort = widget.recent ? 'Created' : 'Speed';
  String get _pageId => widget.recent ? 'recentRequests' : 'connections';
  late final RecordFilterController<Json> _filters = RecordFilterController<Json>(
    facets: connectionFacets,
    groupers: connectionGroupers,
    grouping: savedRecordGrouping(context.box.preferences, _pageId),
    onGroupingChanged: (grouping) => saveRecordGrouping(context.box, _pageId, grouping),
  );

  @override void dispose() { _filters.dispose(); super.dispose(); }

  Future<void> _closeAll() async {
    final box = context.box;
    if (await confirm(context, 'Close all active connections?', action: 'Close')) await box.act('connections.closeAll');
  }

  @override Widget build(BuildContext context) {
    final box = context.watchBox(BoxAspect.connections);
    final words = _query.toLowerCase().split(' ');
    final blank = _query.trim().isEmpty;
    final searched = box.connections.where((connection) {
      final closed = (connection['closedAt'] as num? ?? 0) > 0;
      if (_filter == 'Active' && closed || _filter == 'Closed' && !closed) return false;
      if (blank) return true;
      final search = [
        connection['destination'], connection['domain'], connection['outbound'],
        connection['network'], connection['rule'], connection['source'], connection['process'],
      ].join(' ').toLowerCase();
      return words.every(search.contains);
    }).toList();
    WidgetsBinding.instance.addPostFrameCallback((_) { if (mounted) _filters.offer(searched); });
    return BoxDataSubscription(data: BoxData.connections, child: ListenableBuilder(
      listenable: Listenable.merge([_filters, _filters.options]),
      builder: (context, _) {
        final key = {'Created': 'createdAt', 'Upload': 'uploadTotal', 'Download': 'downloadTotal'}[_sort];
        num rank(Json c) => key == null ? (c['upload'] as num? ?? 0) + (c['download'] as num? ?? 0) : c[key] as num? ?? 0;
        final list = List.of(_filters.apply(searched))..sort((a, b) => rank(b).compareTo(rank(a)));
        final groups = _filters.groupsOf(list);
        final pinned = !ViewScope.isMobileOf(context);
        final padding = EdgeInsets.only(top: context.contentTopPadding, bottom: 16 + context.bottomInset);
        return Scaffold(
          title: widget.title,
          titleMenuItems: widget.titleMenuItems,
          searchState: AppBarSearchState(onSearch: (query) => setState(() => _query = query)),
          keywords: _filters.keywordsOf(pinned: pinned),
          iconActions: [
            if (!widget.recent) IconButtonData(glyph: AppGlyphs.clearAll, tooltip: context.tr('Close all connections'), onPressed: box.running ? _closeAll : null),
          ],
          // The running feed is the active list; the recent feed keeps its closed/all choice.
          menuItems: [
            if (widget.recent) for (final filter in ['Closed', 'All'])
              PopupMenuItem(label: context.tr(filter), checked: filter == _filter,
                section: filter == 'Closed' ? PopupMenuSection(title: context.tr('Connections')) : null,
                onPressed: () => setState(() => _filter = filter)),
            for (final sort in ['Speed', 'Created', 'Upload', 'Download'])
              PopupMenuItem(glyph: AppGlyphs.sort, label: context.tr(sort), checked: sort == _sort, onPressed: () => setState(() => _sort = sort)),
            ..._filters.menuItems(context, pinned: pinned),
          ],
          body: RecordFilterScope(onFilter: _filters.add, child: NullStatusSwitcher(
            isEmpty: list.isEmpty, isSearching: !blank || _filters.isFiltering,
            nullStatus: NullStatus(label: context.tr('No connections'), illustration: NullStatusIllustration.connections),
            child: groups != null ? RecordGroupedList<Json>(
              groups: groups,
              grouping: _filters.value.grouping!,
              padding: padding,
              itemBuilder: (context, connection, _) => Padding(
                padding: const EdgeInsets.only(bottom: recordListGap),
                child: ConnectionRecord(key: ValueKey(connection['id']), connection: connection),
              ),
            ) : ListView.separated(
              padding: padding,
              itemCount: list.length,
              separatorBuilder: (_, _) => const SizedBox(height: recordListGap),
              itemBuilder: (context, index) => ConnectionRecord(key: ValueKey(list[index]['id']), connection: list[index]),
            ),
          )),
        );
      },
    ));
  }
}

class ConnectionRecord extends StatelessWidget {
  const ConnectionRecord({super.key, required this.connection});
  final Json connection;
  @override Widget build(BuildContext context) {
    final c = connection, box = context.box, colors = context.colorScheme;
    final styles = RecordTextStyles.of(context);
    final closed = (c['closedAt'] as num? ?? 0) > 0;
    final network = (c['network']?.toString() ?? '').toLowerCase();
    final app = connectionApp(c);
    final rule = c['rule'] is String ? c['rule'] as String : '';
    final chain = connectionChain(c);
    return RecordListItem(
      onTap: () => openPage(context, ConnectionDetail(initial: c)),
      header: RecordHeader(
        children: [
          RecordTimestamp(recordTime(c['createdAt'])),
          RecordLabel(label: network.toUpperCase(), onPressed: network.isEmpty ? null : () => RecordFilterScope.add(context, RecordFilter(RecordFacetKind.network, network))),
          if (app.isNotEmpty) RecordLabel(label: app, onPressed: () => RecordFilterScope.add(context, RecordFilter(RecordFacetKind.app, app))),
          if (closed) RecordLabel(label: context.tr('Closed')),
        ],
        trailing: !closed && box.running ? Tooltip(
          message: context.tr('Close this connection'), child: Semantics(button: true, child: PressRegion(
            shape: AppShape.circle, overlay: PressRegion.hoverOverlay, onTap: () => box.act('connections.close', {'id': c['id']}),
            child: SizedBox.square(dimension: 28, child: Center(child: GlyphIcon(AppGlyphs.block, size: 16, color: colors.onSurfaceVariant))),
          )),
        ) : null,
      ),
      body: Column(crossAxisAlignment: CrossAxisAlignment.start, spacing: 4, children: [
        Text(c['displayDestination']?.toString() ?? c['destination']?.toString() ?? '—', style: styles.title, maxLines: 2, overflow: TextOverflow.ellipsis),
        // tracker_info_item.dart: the rule, then each outbound of the chain; a tap filters on it.
        Wrap(crossAxisAlignment: WrapCrossAlignment.center, spacing: 6, runSpacing: 4, children: [
          if (rule.isNotEmpty) ...[
            PressRegion(shape: AppShape.sm, onTap: () => RecordFilterScope.add(context, RecordFilter(RecordFacetKind.rule, rule)), child: Text(rule, style: styles.secondary)),
            const RecordArrow(),
          ],
          if (chain.isEmpty) Text('—', style: AppTextStyle.footnote.of(context, emphasized: true).copyWith(color: colors.primary)),
          for (final (index, outbound) in chain.indexed) ...[
            if (index > 0) const RecordArrow(),
            TonalChip(
              label: outbound,
              color: index == chain.length - 1 ? colors.primary.withValues(alpha: .12) : context.raisedSurfaceLevel.colorOf(colors),
              foregroundColor: index == chain.length - 1 ? colors.primary : colors.onSurfaceVariant,
              onPressed: () => RecordFilterScope.add(context, RecordFilter(RecordFacetKind.proxy, outbound)),
            ),
          ],
        ]),
        Text(c['source']?.toString() ?? '—', style: styles.secondary, maxLines: 1, overflow: TextOverflow.ellipsis),
        Wrap(spacing: 16, runSpacing: 4, children: [for (final upload in [true, false]) Row(mainAxisSize: MainAxisSize.min, spacing: 4, children: [
          GlyphIcon(upload ? AppGlyphs.arrowUp : AppGlyphs.arrowDown, size: 12, color: colors.onSurfaceVariant),
          Text('${bytes(c[upload ? 'uploadTotal' : 'downloadTotal'] as num? ?? 0)} · ${bytes(c[upload ? 'upload' : 'download'] as num? ?? 0)}/s', style: styles.secondary.toTabular),
        ])]),
      ]),
    );
  }
}

class ConnectionDetail extends StatefulWidget {
  const ConnectionDetail({super.key, required this.initial});
  final Json initial;
  @override State<ConnectionDetail> createState() => _ConnectionDetailState();
}
class _ConnectionDetailState extends State<ConnectionDetail> {
  late Json _cached = widget.initial;
  @override Widget build(BuildContext context) {
    final box = context.watchBox(BoxAspect.connections);
    var present = false;
    for (final connection in box.connections) {
      if (connection['id'] == widget.initial['id']) { _cached = connection; present = true; break; }
    }
    final c = _cached;
    final values = <String, dynamic>{
      'Destination': c['displayDestination'], 'Source': c['source'], 'Network': c['network'],
      'Protocol': c['protocol'], 'Inbound': c['inbound'], 'Outbound': c['outbound'],
      'Rule matched': c['rule'], 'Chain': (c['chain'] as List? ?? []).join(' → '),
      'Created': localDate(c['createdAt']),
      if ((c['closedAt'] as num? ?? 0) > 0) 'Closed': localDate(c['closedAt']),
      'Upload': bytes(c['uploadTotal'] as num? ?? 0), 'Download': bytes(c['downloadTotal'] as num? ?? 0),
      if (c['process'] != null) 'Process': json(c['process']).entries.map((e) => e.key + ': ' + e.value.toString()).join('\n'),
    };
    return BoxPage(
      title: 'Details',
      actions: [
        IconButtonData(glyph: AppGlyphs.copy, tooltip: context.tr('Copy'), onPressed: () async {
          await Clipboard.setData(ClipboardData(text: values.entries.map((e) => e.key + ': ' + e.value.toString()).join('\n')));
          box.message(context.tr('Copied'));
        }),
        IconButtonData(glyph: AppGlyphs.close, tooltip: context.tr('Close this connection'),
          onPressed: box.running && present && (c['closedAt'] as num? ?? 0) == 0 ? () => box.act('connections.close', {'id': c['id']}) : null),
      ],
      children: [generateGroup([for (final value in values.entries) DetailRow.text(title: context.tr(value.key), value: value.value?.toString() ?? '—')])],
    );
  }
}
