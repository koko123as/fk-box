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

  Future<void> _setHttps(bool value) async {
    final config = Map<String, Object?>.from(FkBox.asMap(_settings['config']));
    config['https'] = value;
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
                FkTextView(
                  title: '${item['method'] ?? ''} ${item['status'] ?? ''}',
                  text: fkPrettyJson(detail),
                ),
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
      children: [_captureSection(), _caSection(), _requests()],
    );
  }
}
