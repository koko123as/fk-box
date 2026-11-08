// FK-box: HTTP(S) request capture and the MITM CA behind HTTPS capture and
// the profiles' experimental.http_rewrite, answered by BoxCapture through
// FkBoxPlugin (see app.md, 请求抓取与 MITM).
//
// Copyright (C) FK-box contributors. GPL-3.0, as FlClash.

import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/widgets.dart';

import '../fkbox.dart';
import 'common.dart';

class FkCaptureView extends StatefulWidget {
  const FkCaptureView({super.key});

  @override
  State<FkCaptureView> createState() => _FkCaptureViewState();
}

class _FkCaptureViewState extends State<FkCaptureView>
    with FkEvents<FkCaptureView> {
  Map<String, Object?> _ca = const {};
  Map<String, Object?> _settings = const {};
  Map<String, Object?> _state = const {};
  Map<String, Object?> _page = const {};
  String _query = '';
  bool _loading = true;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
    _timer = Timer.periodic(const Duration(seconds: 2), (_) {
      if (_state['active'] == true) unawaited(_refresh());
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    final ca = await run(() => FkBox.map('capture.ca'));
    final settings = await run(() => FkBox.map('capture.settings'));
    if (!mounted) return;
    setState(() {
      _ca = ca ?? const {};
      _settings = settings ?? const {};
    });
    await _refresh();
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _refresh() async {
    final value = await run(
      () => FkBox.map('capture.snapshot', {'limit': 200, 'query': _query}),
    );
    if (!mounted || value == null) return;
    setState(() {
      _state = FkBox.asMap(value['state']);
      _page = FkBox.asMap(value['page']);
    });
  }

  Future<void> _toggleCapture() async {
    final active = _state['active'] == true;
    await run(
      () => FkBox.call<Object?>(active ? 'capture.stop' : 'capture.start'),
    );
    await _refresh();
  }

  Future<void> _setHttps(bool value) => _configure('https', value);

  Future<void> _configure(String key, Object? value) async {
    final config = Map<String, Object?>.from(FkBox.asMap(_settings['config']));
    config[key] = value;
    final saved = await run(
      () => FkBox.map('capture.configure', {
        'revision': _settings['revision'],
        'config': config,
      }),
    );
    if (saved != null && mounted) setState(() => _settings = saved);
  }

  Future<void> _generateCa() async {
    if (_ca['exists'] == true) {
      final ok = await dialogs.showMessage(
        message: TextSpan(
          text: '${fkStrings.generate}: ${fkStrings.certificate}?',
        ),
        destructive: true,
      );
      if (ok != true) return;
    }
    await run(
      () => FkBox.call<Object?>('capture.caGenerate', {
        'revision': _ca['revision'],
      }),
    );
    final ca = await run(() => FkBox.map('capture.ca'));
    if (mounted && ca != null) setState(() => _ca = ca);
  }

  Widget _caSection() {
    final exists = _ca['exists'] == true;
    final trusted = _ca['userTrusted'] == true || _ca['systemTrusted'] == true;
    return generateSection(
      title: fkStrings.certificate,
      items: [
        if (exists)
          ListItem(
            title: Text((_ca['subject'] as String?) ?? ''),
            subtitle: Text(
              [
                ((_ca['fingerprint'] as String?) ?? '').substring(
                  0,
                  ((_ca['fingerprint'] as String?) ?? '').length.clamp(0, 23),
                ),
                fkDate(_ca['expiresAt']),
                if (_ca['systemTrusted'] == true) 'system',
                if (_ca['userTrusted'] == true) 'user',
                if (!trusted) '—',
              ].join(' · '),
            ),
          ),
        ListItem(
          leading: const GlyphIcon(AppGlyphs.key),
          title: Text(fkStrings.generate),
          onTap: _generateCa,
        ),
        if (exists) ...[
          ListItem(
            leading: const GlyphIcon(AppGlyphs.verified),
            title: Text(fkStrings.install),
            onTap: () => run(
              () => FkBox.call<Object?>('capture.installCertificate', {
                'revision': _ca['revision'],
              }),
            ),
          ),
          ListItem(
            leading: const GlyphIcon(AppGlyphs.export),
            title: Text(fkStrings.export),
            onTap: () => run(
              () => FkBox.call<Object?>('capture.exportCertificate', {
                'revision': _ca['revision'],
                'module': false,
              }),
            ),
          ),
          ListItem(
            leading: const GlyphIcon(AppGlyphs.puzzle),
            title: Text(fkStrings.exportModule),
            onTap: () => run(
              () => FkBox.call<Object?>('capture.exportCertificate', {
                'revision': _ca['revision'],
                'module': true,
              }),
            ),
          ),
        ],
      ],
    );
  }

  List<String> _list(Object? value) => [
    for (final item in (value as List?) ?? const []) '$item',
  ];

  /// A list setting edited as comma-separated text.
  Future<void> _editList(
    String key,
    String title, {
    bool numbers = false,
  }) async {
    final config = FkBox.asMap(_settings['config']);
    final value = await fkInput(
      title,
      value: _list(config[key]).join(', '),
      label: title,
    );
    if (value == null) return;
    final items = [
      for (final part in value.split(RegExp(r'[,\s]+')))
        if (part.trim().isNotEmpty) part.trim(),
    ];
    if (numbers) {
      final ports = <int>[];
      for (final item in items) {
        final port = int.tryParse(item);
        if (port == null || port < 1 || port > 65535) {
          dialogs.showBanner('$title: $item', level: BannerLevel.error);
          return;
        }
        ports.add(port);
      }
      await _configure(key, ports);
    } else {
      await _configure(key, items);
    }
  }

  /// Hosts seen in the captured requests, added to the HTTPS hosts.
  Future<void> _pickSeenHost() async {
    final hosts = await run(
      () => FkBox.list('capture.domains', {'query': _query}),
    );
    if (hosts == null || !mounted) return;
    final names = <String>[
      for (final host in hosts)
        if (host is Map) '${host['host'] ?? host['domain'] ?? ''}' else '$host',
    ].where((name) => name.isNotEmpty).toList();
    if (names.isEmpty) {
      dialogs.showBanner(fkStrings.empty);
      return;
    }
    final chosen = await fkChoose(fkStrings.seenHosts, {
      for (final name in names) name: name,
    });
    if (chosen == null) return;
    final config = FkBox.asMap(_settings['config']);
    final current = _list(config['decryptHosts']);
    if (current.contains(chosen)) return;
    await _configure('decryptHosts', [...current, chosen]);
  }

  Widget _settingsSection() {
    final active = _state['active'] == true;
    final config = FkBox.asMap(_settings['config']);
    String summary(String key) {
      final items = _list(config[key]);
      return items.isEmpty ? fkStrings.all : items.join(', ');
    }

    final bodyLimit = (config['bodyLimit'] as num?)?.toInt() ?? 0;
    return generateSection(
      title: fkStrings.captureSettings,
      footer: active ? fkStrings.stopToEdit : null,
      items: [
        ListItem(
          title: Text(fkStrings.captureApps),
          subtitle: Text(summary('packages'), maxLines: 2),
          onTap: active
              ? null
              : () => _editList('packages', fkStrings.captureApps),
        ),
        ListItem(
          title: Text(fkStrings.capturePorts),
          subtitle: Text(
            _list(config['ports']).isEmpty
                ? '80, 443, 8080, 8443'
                : summary('ports'),
            maxLines: 2,
          ),
          onTap: active
              ? null
              : () => _editList(
                  'ports',
                  fkStrings.capturePorts,
                  numbers: true,
                ),
        ),
        ListItem(
          title: Text(fkStrings.decryptHosts),
          subtitle: Text(summary('decryptHosts'), maxLines: 2),
          onTap: active
              ? null
              : () => _editList('decryptHosts', fkStrings.decryptHosts),
        ),
        ListItem(
          leading: const GlyphIcon(AppGlyphs.add),
          title: Text(fkStrings.seenHosts),
          onTap: active ? null : _pickSeenHost,
        ),
        ListItem(
          title: Text(fkStrings.bypassHosts),
          subtitle: Text(
            _list(config['decryptBypassHosts']).isEmpty
                ? fkStrings.noExitNode
                : _list(config['decryptBypassHosts']).join(', '),
            maxLines: 2,
          ),
          onTap: active
              ? null
              : () => _editList('decryptBypassHosts', fkStrings.bypassHosts),
        ),
        ListItem(
          title: Text(fkStrings.bodyLimit),
          subtitle: Text(bodyLimit > 0 ? fkBytes(bodyLimit) : '—'),
          onTap: active
              ? null
              : () async {
                  final value = await fkInput(
                    fkStrings.bodyLimit,
                    value: '${bodyLimit ~/ 1024}',
                    label: 'KiB',
                  );
                  final kib = int.tryParse(value?.trim() ?? '');
                  if (kib == null || kib < 0) return;
                  await _configure('bodyLimit', kib * 1024);
                },
        ),
        ListItem.toggle(
          title: Text(fkStrings.blockQuic),
          value: config['blockQUIC'] == true,
          onChanged: active ? null : (value) => _configure('blockQUIC', value),
        ),
        ListItem.toggle(
          title: Text(fkStrings.extractHosts),
          value: config['extractHosts'] == true,
          onChanged: active
              ? null
              : (value) => _configure('extractHosts', value),
        ),
      ],
    );
  }

  Widget _captureSection() {
    final active = _state['active'] == true;
    final config = FkBox.asMap(_settings['config']);
    return generateSection(
      title: fkStrings.capture,
      items: [
        ListItem.toggle(
          title: Text(fkStrings.capturing),
          subtitle: (_state['error'] as String? ?? '').isEmpty
              ? Text(
                  '${_state['recordedRequests'] ?? 0} · '
                  '${fkBytes(_page['storedBytes'])}'
                  '${_state['rewriteActive'] == true ? ' · rewrite ${_state['rewriteRules'] ?? 0}' : ''}',
                )
              : Text(_state['error'] as String),
          value: active,
          onChanged: (_) => _toggleCapture(),
        ),
        ListItem.toggle(
          title: Text(fkStrings.https),
          value: config['https'] == true,
          onChanged: active ? null : _setHttps,
        ),
        ListItem(
          leading: const GlyphIcon(AppGlyphs.logs),
          title: Text(fkStrings.logs),
          onTap: () async {
            final logs = await run(() => FkBox.map('capture.logs'));
            if (logs == null || !mounted) return;
            final text = [
              for (final log in FkBox.asMaps(logs['logs']))
                '${fkDate(log['receivedAt'])} [${log['level']}] ${log['message']}',
            ].join('\n');
            await BaseNavigator.push(
              context,
              FkTextView(title: fkStrings.logs, text: text),
            );
          },
        ),
        ListItem(
          leading: const GlyphIcon(AppGlyphs.export),
          title: Text('${fkStrings.export} HAR'),
          onTap: () => run(
            () => FkBox.call<Object?>('capture.export', {
              'format': 'har',
              'query': _query,
            }),
          ),
        ),
        ListItem(
          leading: const GlyphIcon(AppGlyphs.clearAll),
          title: Text(fkStrings.clear),
          onTap: () async {
            await run(() => FkBox.call<void>('capture.clear'));
            await _refresh();
          },
        ),
      ],
    );
  }

  Widget _requests() {
    final items = FkBox.asMaps(_page['items']);
    return generateSection(
      title: '${fkStrings.requests} (${_page['total'] ?? items.length})',
      items: [
        for (final item in items)
          ListItem(
            title: Text(
              '${item['method'] ?? ''} ${item['url'] ?? ''}',
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
            subtitle: Text(
              [
                if ((item['status'] as num? ?? 0) > 0) '${item['status']}',
                item['protocol'] ?? '',
                fkBytes(item['bytes']),
                '${item['durationMs'] ?? 0} ms',
                ...((item['packages'] as List?) ?? const []),
                if ((item['error'] as String? ?? '').isNotEmpty) item['error'],
              ].join(' · '),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
            onTap: () async {
              final detail = await run(
                () => FkBox.map('capture.detail', {'id': item['id']}),
              );
              if (detail == null || !mounted) return;
              await BaseNavigator.push(
                context,
                FkRequestDetailView(detail: detail),
              );
            },
          ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return FkPage(
      title: fkStrings.capture,
      isLoading: _loading,
      iconActions: [
        IconButtonData(
          glyph: AppGlyphs.search,
          tooltip: 'Filter',
          isSelected: _query.isNotEmpty,
          onPressed: () async {
            final value = await fkInput('Filter', value: _query);
            if (value == null) return;
            setState(() => _query = value.trim());
            await _refresh();
          },
        ),
        IconButtonData(
          glyph: AppGlyphs.refresh,
          tooltip: fkStrings.refresh,
          onPressed: _load,
        ),
      ],
      children: [
        _captureSection(),
        _settingsSection(),
        _caSection(),
        _requests(),
      ],
    );
  }
}

/// One captured request: overview, connection, headers and body previews.
class FkRequestDetailView extends StatelessWidget {
  const FkRequestDetailView({super.key, required this.detail});

  final Map<String, Object?> detail;

  List<Widget> _message(
    BuildContext context,
    String title,
    Map<String, Object?> message,
    Map<String, Object?> preview,
  ) {
    final headers = FkBox.asMaps(message['headers']);
    final text = '${preview['text'] ?? ''}';
    return [
      generateSection(
        title: title,
        items: [
          for (final header in headers)
            ListItem(
              title: Text('${header['name'] ?? ''}'),
              subtitle: Text('${header['value'] ?? ''}'),
            ),
          ListItem(
            title: Text(fkStrings.body),
            subtitle: Text(
              [
                fkBytes(message['bodyBytes']),
                if (message['bodyTruncated'] == true ||
                    preview['truncated'] == true)
                  fkStrings.truncated,
                if ('${preview['encoding'] ?? ''}'.isNotEmpty)
                  '${preview['encoding']}',
              ].join(' · '),
            ),
            onTap: text.isEmpty
                ? null
                : () => BaseNavigator.push(
                    context,
                    FkTextView(title: '$title · ${fkStrings.body}', text: text),
                  ),
          ),
        ],
      ),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final request = FkBox.asMap(detail['request']);
    final response = FkBox.asMap(detail['response']);
    final metadata = FkBox.asMap(detail['metadata']);
    final tls = FkBox.asMap(metadata['tls']);
    final status = (response['status'] as num?)?.toInt() ?? 0;
    return FkPage(
      title: '${request['method'] ?? ''} ${status > 0 ? status : ''}'.trim(),
      menuItems: [
        PopupMenuItem(
          label: fkStrings.rawJson,
          onPressed: () => BaseNavigator.push(
            context,
            FkTextView(title: fkStrings.rawJson, text: fkPrettyJson(detail)),
          ),
        ),
      ],
      children: [
        fkDetails(null, {
          'URL': request['url'],
          if ('${detail['originalUrl'] ?? ''}'.isNotEmpty)
            fkStrings.originalUrl: detail['originalUrl'],
          fkStrings.state: status > 0 ? '$status' : null,
          fkStrings.protocol: response['protocol'] ?? request['protocol'],
          fkStrings.duration: '${detail['durationMs'] ?? 0} ms',
          fkStrings.time: fkDate(detail['startedAt']),
          fkStrings.error: detail['error'],
          fkStrings.note: detail['note'],
        }),
        fkDetails(fkStrings.connection, {
          fkStrings.host: '${metadata['host'] ?? ''}:${metadata['port'] ?? ''}',
          fkStrings.destination: metadata['destination'],
          fkStrings.outbound: metadata['outbound'],
          fkStrings.rule: metadata['rule'],
          fkStrings.apps: metadata['packages'],
          'TLS': tls.isEmpty
              ? null
              : [
                  tls['serverName'],
                  tls['clientVersion'],
                  tls['alpn'],
                ].where((v) => '${v ?? ''}'.isNotEmpty).join(' · '),
        }),
        ..._message(
          context,
          fkStrings.request,
          request,
          FkBox.asMap(detail['requestPreview']),
        ),
        ..._message(
          context,
          fkStrings.response,
          response,
          FkBox.asMap(detail['responsePreview']),
        ),
      ],
    );
  }
}
