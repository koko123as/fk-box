// FK-box: OpenVPN and OpenConnect endpoints of the running sing-box profile —
// state, tunnel details and the servers' authentication challenges
// (credentials, verification codes, OpenConnect auth forms, sign-in pages),
// answered by BoxTools through FkBoxPlugin.
//
// Copyright (C) FK-box contributors. GPL-3.0, as FlClash.

import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/widgets.dart';

import '../fkbox.dart';
import 'common.dart';

/// OpenVPN ([kind] `openvpn`) or OpenConnect ([kind] `openconnect`) endpoints.
class FkEndpointsView extends StatefulWidget {
  const FkEndpointsView({super.key, required this.kind});

  final String kind;

  @override
  State<FkEndpointsView> createState() => _FkEndpointsViewState();
}

class _FkEndpointsViewState extends State<FkEndpointsView>
    with FkEvents<FkEndpointsView> {
  List<Map<String, Object?>> _endpoints = const [];
  bool _loading = true;

  bool get _openVpn => widget.kind == 'openvpn';

  String get _title => _openVpn ? fkStrings.openvpn : fkStrings.openconnect;

  @override
  void initState() {
    super.initState();
    follow('tools', (value) {
      setState(
        () => _endpoints = FkBox.asMaps(FkBox.asMap(value)[widget.kind]),
      );
    });
    unawaited(_load());
  }

  Future<void> _load() async {
    final snapshot = await run(() => FkBox.map('tools.snapshot'));
    if (!mounted) return;
    setState(() {
      _loading = false;
      if (snapshot != null) _endpoints = FkBox.asMaps(snapshot[widget.kind]);
    });
  }

  Map<String, Object?> _challenge(Map<String, Object?> endpoint) {
    return FkBox.asMap(endpoint[_openVpn ? 'challenge' : 'authChallenge']);
  }

  Future<void> _cancel(String tag, String id) async {
    await run(
      () => FkBox.call<void>('${widget.kind}.cancel', {
        'endpoint': tag,
        'challenge': id,
      }),
    );
  }

  Future<String?> _ask(String label, {String value = '', bool obscure = false}) {
    return fkInput(label, value: value, label: label, obscure: obscure);
  }

  /// OpenVPN: credentials, a static/dynamic challenge secret or a sign-in URL.
  Future<void> _answerOpenVpn(String tag, Map<String, Object?> challenge) async {
    final id = '${challenge['id'] ?? ''}';
    final kind = '${challenge['kind'] ?? ''}';
    if (kind == 'open-url') {
      await run(
        () => FkBox.call<void>('url.open', {'url': challenge['url']}),
      );
      return;
    }
    String username = '${challenge['username'] ?? ''}';
    String password = '';
    String secret = '';
    if (kind == 'credentials') {
      final user = await _ask(fkStrings.username, value: username);
      if (user == null) return;
      final pass = await _ask(fkStrings.password, obscure: true);
      if (pass == null) return;
      username = user;
      password = pass;
    }
    final secretMessage = '${challenge['secretMessage'] ?? ''}';
    if (kind == 'secret' || secretMessage.isNotEmpty) {
      final value = await _ask(
        secretMessage.isNotEmpty ? secretMessage : fkStrings.verificationCode,
        obscure: challenge['echo'] != true,
      );
      if (value == null) return;
      secret = value;
    }
    await run(
      () => FkBox.call<void>('openvpn.submit', {
        'endpoint': tag,
        'challenge': id,
        'username': username,
        'password': password,
        'secret': secret,
      }),
    );
  }

  /// OpenConnect: the server's auth form, field by field; a web sign-in
  /// opens in the browser (the embedded auth browser is not part of FlClash's
  /// UI).
  Future<void> _answerOpenConnect(
    String tag,
    Map<String, Object?> challenge,
  ) async {
    final id = '${challenge['id'] ?? ''}';
    final browser = FkBox.asMap(challenge['browser']);
    final form = FkBox.asMap(challenge['form']);
    if (form.isEmpty) {
      if (browser.isNotEmpty) {
        dialogs.showBanner(fkStrings.signInBrowserOnly);
        await run(
          () => FkBox.call<void>('url.open', {'url': browser['url']}),
        );
      }
      return;
    }
    final values = <String, String>{};
    for (final field in FkBox.asMaps(form['fields'])) {
      final key = '${field['submissionKey'] ?? ''}';
      final label = '${field['label'] ?? field['name'] ?? key}';
      final current = '${field['value'] ?? ''}';
      if (field['kind'] == 'select') {
        final options = FkBox.asMaps(field['options']);
        final chosen = await fkChoose(label, {
          for (final option in options)
            '${option['value'] ?? ''}': '${option['label'] ?? option['value']}',
        });
        if (chosen == null) return;
        values[key] = chosen;
      } else if (field['kind'] == 'hidden') {
        values[key] = current;
      } else {
        final value = await _ask(
          label,
          value: current,
          obscure: field['kind'] == 'password',
        );
        if (value == null) return;
        values[key] = value;
      }
    }
    await run(
      () => FkBox.call<void>('openconnect.submit', {
        'endpoint': tag,
        'challenge': id,
        'values': values,
      }),
    );
  }

  List<Widget> _endpointSections(Map<String, Object?> endpoint) {
    final tag = '${endpoint['endpointTag'] ?? ''}';
    final challenge = _challenge(endpoint);
    final error = '${endpoint['error'] ?? ''}';
    final info = FkBox.asMap(endpoint['tunnelInfo']);
    final notes = [
      for (final key in ['banner', 'message', 'error', 'previousError'])
        if ('${challenge[key] ?? ''}'.isNotEmpty) '${challenge[key]}',
    ];
    return [
      generateSection(
        title: tag,
        items: [
          ListItem(
            title: Text(fkStrings.state),
            subtitle: Text('${endpoint['stateText'] ?? endpoint['state'] ?? ''}'),
          ),
          if (error.isNotEmpty)
            ListItem(
              leading: const GlyphIcon(AppGlyphs.warning),
              title: Text(fkStrings.error),
              subtitle: Text(error),
            ),
        ],
      ),
      if (challenge.isNotEmpty)
        generateSection(
          title: fkStrings.authentication,
          items: [
            ListItem(
              leading: const GlyphIcon(AppGlyphs.key),
              title: Text(
                challenge['kind'] == 'open-url'
                    ? fkStrings.openSignIn
                    : fkStrings.authenticate,
              ),
              subtitle: notes.isEmpty ? null : Text(notes.join('\n')),
              onTap: () => _openVpn
                  ? _answerOpenVpn(tag, challenge)
                  : _answerOpenConnect(tag, challenge),
            ),
            ListItem(
              leading: const GlyphIcon(AppGlyphs.close),
              title: Text(fkStrings.cancel),
              onTap: () => _cancel(tag, '${challenge['id'] ?? ''}'),
            ),
          ],
        ),
      if (info.isNotEmpty)
        fkDetails(fkStrings.tunnel, {
          for (final entry in info.entries)
            entry.key: entry.key == 'connectedSince'
                ? fkDate(entry.value)
                : entry.value,
        }),
    ];
  }

  @override
  Widget build(BuildContext context) {
    return FkPage(
      title: _title,
      isLoading: _loading,
      iconActions: [
        IconButtonData(
          glyph: AppGlyphs.refresh,
          tooltip: fkStrings.refresh,
          onPressed: _load,
        ),
      ],
      children: [
        if (!_loading && _endpoints.isEmpty)
          fkNote(context, fkStrings.noEndpoints),
        for (final endpoint in _endpoints) ..._endpointSections(endpoint),
      ],
    );
  }
}
