import 'package:flutter/services.dart';
import '../widgets.dart';
import '../common/http_message.dart';
import '../components/capture_poll.dart';
import '../components/captured_body.dart';
import '../components/record.dart';
import '../components/record_filter.dart';
import 'connections.dart';
import 'request_settings.dart';
import 'capture_logs.dart';
import 'mitm_certificate.dart';

const _localOnly = 'HTTP debugging is only available for the local service';

/// enum.dart's RequestFeed, with fk-box's HTTP capture list as a third feed.
const requestFeeds = ['recent', 'live', 'captured'];

String requestFeedLabel(String feed) => switch (feed) {
  'recent' => 'Recent requests',
  'live' => 'Running',
  _ => 'Captured',
};

/// requests.dart's RequestsPage: one page whose title menu switches between
/// recent (closed) and running connections and the captured HTTP requests.
/// The chosen feed persists as `requestFeed` unless [initialFeed] opens one.
/// HTTP capture is a sing-box feature: its feed stays greyed on Xray/mihomo.
class RequestsPage extends StatefulWidget {
  const RequestsPage({super.key, this.initialFeed});
  final String? initialFeed;
  @override State<RequestsPage> createState() => _RequestsPageState();
}

class _RequestsPageState extends State<RequestsPage> {
  String? _feed;

  @override Widget build(BuildContext context) {
    final box = context.watchBox(BoxAspect.state);
    final captureAvailable = (box.selectedProfile?['resolvedCore'] as String? ?? 'sing-box') == 'sing-box';
    final feed = _feed ?? widget.initialFeed ?? box.preferences['requestFeed'] as String? ?? 'recent';
    final shown = !requestFeeds.contains(feed) || feed == 'captured' && !captureAvailable ? 'recent' : feed;
    final titleMenuItems = [
      for (final item in requestFeeds) PopupMenuItem(
        label: context.tr(requestFeedLabel(item)),
        subtitle: item == 'captured' && !captureAvailable ? context.tr('HTTP capture needs the sing-box core') : null,
        checked: item == shown,
        onPressed: item == 'captured' && !captureAvailable ? null : () {
          if (item == shown) return;
          setState(() => _feed = item);
          if (widget.initialFeed == null) box.setPreference('requestFeed', item);
        },
      ),
    ];
    final title = context.tr(requestFeedLabel(shown));
    // Each feed owns its search, record filter and grouping.
    return KeyedSubtree(
      key: ValueKey(shown),
      child: shown == 'captured'
          ? _CapturedFeed(title: title, titleMenuItems: titleMenuItems)
          : ConnectionsFeed(recent: shown == 'recent', title: title, titleMenuItems: titleMenuItems),
    );
  }
}

String _captureApp(Json item) {
  for (final value in item['packages'] as List? ?? const []) {
    if (value is String && value.isNotEmpty) return value;
  }
  return '';
}

/// How a captured request was read: HTTPS through the MITM CA, plain HTTP, or the domain only.
String _captureMitm(Json item) {
  if (item['domainOnly'] == true) return 'domain';
  final url = item['url'];
  return url is String && url.toLowerCase().startsWith('https://') ? 'mitm' : 'http';
}

String _captureHost(Json item) {
  final url = item['url'];
  if (url is! String) return '';
  final index = url.indexOf('://');
  var host = index < 0 ? url : url.substring(index + 3);
  host = host.split('/').first.split('?').first;
  return host.substring(host.lastIndexOf('@') + 1);
}

String _mitmLabel(String value) => switch (value) { 'mitm' => 'MITM', 'http' => 'HTTP', _ => 'Domain only' };

/// common.dart's mitm facet for fk-box's capture records, with the app they came from.
final _captureFacets = <RecordFacet<Json>>[
  RecordFacet<Json>(RecordFacetKind.mitm, (item) => [_captureMitm(item)], offered: true, order: const ['mitm', 'http', 'domain'], labelOf: _mitmLabel),
  RecordFacet<Json>(RecordFacetKind.app, (item) => [for (final p in item['packages'] as List? ?? const []) if (p is String && p.isNotEmpty) p]),
];

final _captureGroupers = <RecordGrouper<Json>>[
  RecordGrouper<Json>(RecordGrouping.app, _captureApp),
  RecordGrouper<Json>(RecordGrouping.host, _captureHost),
];

/// The HTTP capture list (the former Requests page), its settings, certificate and logs menu unchanged.
class _CapturedFeed extends StatefulWidget {
  const _CapturedFeed({required this.title, required this.titleMenuItems});
  final String title;
  final List<PopupMenuItem> titleMenuItems;
  @override State<_CapturedFeed> createState() => _CapturedFeedState();
}

class _CapturedFeedState extends State<_CapturedFeed> {
  String _query = '';
  int _limit = 50, _revision = 0;
  bool _busy = false;
  // Built on first use; a remote session never shows the list, so it may never exist.
  RecordFilterController<Json>? _filtersOrNull;
  RecordFilterController<Json> get _filters => _filtersOrNull ??= RecordFilterController<Json>(
    facets: _captureFacets,
    groupers: _captureGroupers,
    grouping: savedRecordGrouping(context.box.preferences, 'captured'),
    onGroupingChanged: (grouping) => saveRecordGrouping(context.box, 'captured', grouping),
  );

  @override void dispose() { _filtersOrNull?.dispose(); super.dispose(); }

  Future<void> _act(String method, [Json arguments = const {}]) async {
    if (_busy) return;
    setState(() => _busy = true);
    await context.box.act(method, arguments);
    if (mounted) setState(() { _busy = false; _revision++; });
  }

  Widget _record(BuildContext context, Json item) {
    final styles = RecordTextStyles.of(context);
    final status = (item['status'] as num? ?? 0).toInt();
    final mitm = _captureMitm(item), app = _captureApp(item);
    void onMitm() => RecordFilterScope.add(context, RecordFilter(RecordFacetKind.mitm, mitm));
    return Padding(padding: const EdgeInsets.only(bottom: recordListGap), child: RecordListItem(
      key: ValueKey(item['id']), onTap: () => openPage(context, RequestDetailPage(id: item['id'].toString())),
      header: RecordHeader(children: [
        RecordTimestamp(recordTime(item['startedAt'])),
        RecordLabel(label: item['method']?.toString() ?? ''),
        // Tapping how the request was read (MITM, HTTP, domain only) or its app filters on it.
        RecordLabel(
          label: item['domainOnly'] == true ? context.tr('Domain only') : status > 0 ? '$status' : context.tr(item['complete'] == true ? 'Error' : 'Active'),
          onPressed: mitm == 'domain' ? onMitm : null,
        ),
        if (mitm != 'domain') RecordLabel(label: _mitmLabel(mitm), onPressed: onMitm),
        if (app.isNotEmpty) RecordLabel(label: app, onPressed: () => RecordFilterScope.add(context, RecordFilter(RecordFacetKind.app, app))),
      ]),
      body: Column(crossAxisAlignment: CrossAxisAlignment.start, spacing: 4, children: [
        Text(item['url']?.toString() ?? '', maxLines: 2, overflow: TextOverflow.ellipsis, style: styles.title),
        Text('${item['protocol'] ?? ''} · ${item['durationMs'] ?? 0} ms · ${bytes(item['bytes'] as num? ?? 0)}', style: styles.secondary),
        if ((item['packages'] as List? ?? []).isNotEmpty) Text((item['packages'] as List).join(', '), maxLines: 1, overflow: TextOverflow.ellipsis, style: styles.secondary),
        if ((item['error'] as String? ?? '').isNotEmpty) Text(item['error'].toString(), maxLines: 2, overflow: TextOverflow.ellipsis, style: styles.secondary.copyWith(color: context.colorScheme.error)),
      ]),
    ));
  }

  @override Widget build(BuildContext context) {
    final box = context.box;
    if (box.remote) {
      return Scaffold(title: widget.title, titleMenuItems: widget.titleMenuItems,
        body: Padding(padding: EdgeInsets.only(top: context.contentTopPadding), child: const EmptyState(_localOnly)));
    }
    return CapturePoll(method: 'capture.snapshot', arguments: {'query': _query, 'limit': _limit}, revision: _revision,
      builder: (context, snapshot, error, refresh) {
        final state = json(snapshot?['state']), page = json(snapshot?['page']);
        final active = state['active'] == true, entries = rows(page['items']);
        WidgetsBinding.instance.addPostFrameCallback((_) { if (mounted) _filters.offer(entries); });
        return ListenableBuilder(listenable: Listenable.merge([_filters, _filters.options]), builder: (context, _) {
          final shown = _filters.apply(entries);
          final groups = _filters.groupsOf(shown);
          final pinned = !ViewScope.isMobileOf(context);
          final rewrite = state['rewriteActive'] == true;
          final header = Padding(padding: const EdgeInsets.symmetric(horizontal: 16), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            generateGroup([
              ListItem.toggle(key: const ValueKey('capture-toggle'), title: Text(context.tr('Capture requests')),
                subtitle: Text(context.tr(active ? 'Capturing new connections' : 'Capture stopped')), value: active,
                onChanged: _busy || snapshot == null || !box.running || box.transitioning ? null : (_) => _act(active ? 'capture.stop' : 'capture.start')),
              if (rewrite) ListItem(title: Text(context.tr('Profile HTTP rewriting is active')),
                subtitle: Text('${state['rewriteRules'] ?? 0} · ${context.tr('Rules remain active when request recording is stopped.')}')),
            ]),
            if (!box.running) Padding(padding: const EdgeInsets.all(12), child: Text(context.tr('Start the local service before capturing requests'))),
            if (snapshot != null) Padding(padding: const EdgeInsets.all(12), child: Text(
              '${page['total'] ?? 0} ${context.tr('Requests')} · ${bytes(page['storedBytes'] as num? ?? 0)} / 32 MiB',
              style: context.listSubtitleStyle,
            )),
            if ((page['error'] as String? ?? '').isNotEmpty) Text(page['error'].toString(), style: context.listSubtitleStyle.copyWith(color: context.colorScheme.error)),
            if (error != null) ErrorState(error, retry: refresh),
            if (snapshot == null && error == null) const Center(child: ActivityIndicator()),
            if (snapshot != null && shown.isEmpty) EmptyState(_query.isEmpty && !_filters.isFiltering ? 'No captured requests' : 'No results',
              message: _query.isEmpty ? 'HTTP and HTTPS capture follow the local service. HTTPS requires a trusted MITM CA. Start capture, then reconnect the target app.' : null),
          ]));
          final more = (page['total'] as num? ?? 0) > entries.length
            ? Padding(padding: const EdgeInsets.symmetric(horizontal: 16), child: TextButton(onPressed: () => setState(() => _limit = (_limit + 50).clamp(1, 300).toInt()), child: Text(context.tr('Show more'))))
            : const SizedBox.shrink();
          return Scaffold(
            title: widget.title, titleMenuItems: widget.titleMenuItems, isLoading: _busy,
            keywords: _filters.keywordsOf(pinned: pinned),
            searchState: AppBarSearchState(onSearch: (query) => setState(() { _query = query; _limit = 50; })),
            iconActions: [IconButtonData(glyph: AppGlyphs.refresh, tooltip: context.tr('Refresh'), onPressed: refresh)],
            menuItems: [
              PopupMenuItem(glyph: AppGlyphs.sliders, label: context.tr('Capture settings'), onPressed: _busy || active ? null : () async {
                setState(() => _busy = true);
                try {
                  final source = json(await context.box.call('capture.settings'));
                  if (context.mounted) await openPage(context, RequestSettingsPage(initial: json(source['config']), source: source));
                } catch (error) { if (context.mounted) context.box.report(error); }
                finally { if (mounted) setState(() { _busy = false; _revision++; }); }
              }),
              PopupMenuItem(glyph: AppGlyphs.lock, label: context.tr('MITM certificate'), onPressed: () => openPage(context, const MitmCertificatePage())),
              PopupMenuItem(glyph: AppGlyphs.logs, label: context.tr('Capture logs'), onPressed: () => openPage(context, const CaptureLogsPage())),
              PopupMenuItem(glyph: AppGlyphs.arrowDown, label: context.tr('Export HAR'), onPressed: _busy || entries.isEmpty ? null : () => _act('capture.export', {'format': 'har', 'query': _query})),
              PopupMenuItem(glyph: AppGlyphs.delete, label: context.tr('Clear requests'), onPressed: _busy ? null : () async {
                if (await confirm(context, 'Clear captured requests?') && mounted) await _act('capture.clear');
              }),
              ..._filters.menuItems(context, pinned: pinned),
            ],
            body: RecordFilterScope(onFilter: _filters.add, child: CustomScrollView(slivers: [
              SliverPadding(padding: EdgeInsets.only(top: context.contentTopPadding), sliver: SliverToBoxAdapter(child: header)),
              if (groups != null) SliverCollapsibleGroups(
                key: ValueKey(_filters.value.grouping),
                estimatedItemExtent: recordListEstimatedExtent,
                groups: [for (final group in groups) CollapsibleGroup(key: group.key, label: group.key.isEmpty ? context.tr('Other') : group.key, itemCount: group.items.length)],
                itemBuilder: (context, group, index) => _record(context, groups[group].items[index]),
              ) else SliverList.builder(itemCount: shown.length, itemBuilder: (context, index) => _record(context, shown[index])),
              SliverPadding(padding: EdgeInsets.only(bottom: 24 + context.bottomInset), sliver: SliverToBoxAdapter(child: more)),
            ])),
          );
        });
      });
  }
}

class RequestDetailPage extends StatefulWidget {
  const RequestDetailPage({super.key, required this.id});
  final String id;
  @override State<RequestDetailPage> createState() => _RequestDetailPageState();
}

class _RequestDetailPageState extends State<RequestDetailPage> {
  String _part = 'Response';
  bool _busy = false;

  Future<void> _copy(String text) async {
    await Clipboard.setData(ClipboardData(text: text));
    if (mounted) context.box.message(context.tr('Copied'));
  }

  Widget _headers(Json message, bool response) {
    final headers = rows(message['headers']);
    return generateSection(title: context.tr(response ? 'Response headers' : 'Request headers'), actions: [
      IconButtonData(glyph: AppGlyphs.copy, tooltip: context.tr('Copy headers'), onPressed: headers.isEmpty ? null : () => _copy(
        headers.map((header) => '${header['name']}: ${header['value']}').join('\n'))),
    ], items: [
      if (headers.isEmpty) ListItem(title: Text(context.tr('No headers captured'))),
      for (final header in headers) DetailRow.text(title: header['name']?.toString() ?? '',
        value: header['value']?.toString() ?? '', stacked: true),
    ], footer: message['headersTruncated'] == true ? context.tr('Headers truncated') : null);
  }

  Widget _cookies(Json message, bool response) {
    final cookies = capturedCookies(rows(message['headers']), response: response);
    return generateSection(title: context.tr(response ? 'Response cookies' : 'Request cookies'), actions: [
      IconButtonData(glyph: AppGlyphs.copy, tooltip: context.tr('Copy cookies'), onPressed: cookies.isEmpty ? null : () => _copy(
        cookies.map((cookie) => cookie.raw).join('\n'))),
    ], items: [
      if (cookies.isEmpty) ListItem(title: Text(context.tr('No cookies captured'))),
      for (final cookie in cookies) DetailRow(title: cookie.name, copyText: cookie.raw, stacked: true,
        value: Column(crossAxisAlignment: CrossAxisAlignment.start, spacing: 6, children: [
          Text(cookie.value.isEmpty ? context.tr('Empty value') : cookie.value),
          if (cookie.attributes.isNotEmpty) Text(cookie.attributes.join('\n'), style: context.listCaptionStyle),
        ])),
    ], footer: message['headersTruncated'] == true ? context.tr('Headers truncated') : null);
  }

  @override Widget build(BuildContext context) {
    if (context.box.remote) return const BoxPage(title: 'Requests', children: [EmptyState('HTTP debugging is only available for the local service')]);
    return CapturePoll(method: 'capture.detail', arguments: {'id': widget.id}, builder: (context, detail, error, refresh) {
      final metadata = json(detail?['metadata']), request = json(detail?['request']);
      final socket = json(metadata['socket']);
      final tls = json(metadata['tls']);
      final response = _part == 'Response';
      final message = json(detail?[response ? 'response' : 'request']);
      final preview = json(detail?[response ? 'responsePreview' : 'requestPreview']);
      final values = <String, dynamic>{
        'URL': request['url'], 'Method': request['method'], 'Created': localDate(detail?['startedAt']),
        if (detail?['originalUrl'] != null) 'Original URL': detail?['originalUrl'],
        'Duration': '${detail?['durationMs'] ?? 0} ms', 'Status': json(detail?['response'])['status'],
        'Protocol': request['protocol'],
        (detail?['originalUrl'] != null ? 'Original outbound' : 'Outbound'): metadata['outbound'],
        (detail?['originalUrl'] != null ? 'Original rule matched' : 'Rule matched'): metadata['rule'],
        'App': (metadata['packages'] as List? ?? []).join(', '), 'UID': metadata['uid'],
        if (metadata['captureSource'] != null) 'Capture source': metadata['captureSource'],
        if (metadata['pid'] != null) 'PID': metadata['pid'],
        if ((metadata['tlsAdapter'] as String? ?? '').isNotEmpty) 'TLS implementation': metadata['tlsAdapter'],
        if (tls.isNotEmpty) 'TLS server name': tls['serverName'],
        if (tls.isNotEmpty) 'Client TLS': tls['clientVersion'],
        if (tls.isNotEmpty) 'Upstream TLS': tls['upstreamVersion'],
        if ((tls['upstreamServerName'] as String? ?? '').isNotEmpty) 'Upstream TLS server name': tls['upstreamServerName'],
        if (tls.isNotEmpty) 'ALPN': tls['alpn']?.toString().isNotEmpty == true ? tls['alpn'] : context.tr('Not negotiated'),
        if (tls.isNotEmpty) 'Upstream certificate SHA-256': tls['upstreamCertificate'],
        if (socket.isNotEmpty) 'Socket FD': socket['fd'],
        if (socket.isNotEmpty) 'Socket inode': socket['inode'],
        'Source': metadata['source'], 'Destination': metadata['destination'],
        if (metadata['originalDestination'] != null) 'Original destination': metadata['originalDestination'],
        if ((detail?['error'] as String? ?? '').isNotEmpty) 'Error': detail?['error'],
        if ((detail?['note'] as String? ?? '').isNotEmpty) 'Note': detail?['note'],
      };
      return BoxPage(title: 'Request details', loading: _busy, actions: [
        IconButtonData(glyph: AppGlyphs.copy, tooltip: context.tr('Copy URL'), onPressed: detail == null ? null : () => _copy(request['url']?.toString() ?? '')),
        IconButtonData(glyph: AppGlyphs.arrowDown, tooltip: context.tr('Export cURL'), onPressed: detail == null || _busy || request['bodyComplete'] != true || request['bodyTruncated'] == true || request['headersTruncated'] == true || rows(request['trailers']).isNotEmpty || request['trailersTruncated'] == true || request['method'] == 'TLS' ? null : () async {
          setState(() => _busy = true);
          await context.box.act('capture.export', {'format': 'curl', 'id': widget.id});
          if (mounted) setState(() => _busy = false);
        }),
      ], menuItems: [
        for (final part in const ['Request', 'Response', 'Cookies']) PopupMenuItem(label: context.tr(part),
          section: part == 'Request' ? PopupMenuSection(title: context.tr('Message type')) : null,
          checked: part == _part, onPressed: () => setState(() => _part = part)),
      ], children: [
        if (error != null) ErrorState(error, retry: refresh),
        if (detail == null && error == null) const Center(child: ActivityIndicator()),
        if (detail != null) ...[
          generateGroup([for (final value in values.entries) DetailRow.text(title: context.tr(value.key),
            value: value.value?.toString() ?? '—', stacked: const ['URL', 'Original URL', 'App', 'Error', 'Note', 'Upstream certificate SHA-256'].contains(value.key))]),
          if (_part == 'Cookies') ...[
            _cookies(request, false),
            _cookies(json(detail['response']), true),
          ] else ...[
            _headers(message, response),
            CapturedBody(key: ValueKey('body-$_part'), message: message, preview: preview),
            if (rows(message['trailers']).isNotEmpty) generateSection(title: context.tr('Trailers'), items: [
              for (final trailer in rows(message['trailers'])) DetailRow.text(title: trailer['name']?.toString() ?? '', value: trailer['value']?.toString() ?? '', stacked: true),
            ], footer: message['trailersTruncated'] == true ? context.tr('Headers truncated') : null),
          ],
        ],
      ]);
    });
  }
}
