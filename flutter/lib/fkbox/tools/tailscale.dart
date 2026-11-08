// FK-box: Tailscale endpoints of the running sing-box profile (status,
// login, exit node, peers, ping, SSH), answered by BoxTools through
// FkBoxPlugin.
//
// Copyright (C) FK-box contributors. GPL-3.0, as FlClash.

import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/widgets.dart';

import '../fkbox.dart';
import 'common.dart';
import 'ssh.dart';

class FkTailscaleView extends StatefulWidget {
  const FkTailscaleView({super.key});

  @override
  State<FkTailscaleView> createState() => _FkTailscaleViewState();
}

class _FkTailscaleViewState extends State<FkTailscaleView>
    with FkEvents<FkTailscaleView> {
  List<Map<String, Object?>> _endpoints = const [];
  Map<String, Object?> _ping = const {};
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    follow('tools', (value) {
      setState(
        () => _endpoints = FkBox.asMaps(FkBox.asMap(value)['tailscale']),
      );
    });
    follow('ping', (value) => setState(() => _ping = FkBox.asMap(value)));
    unawaited(_load());
  }

  @override
  void dispose() {
    unawaited(FkBox.call<void>('tailscale.stopPing').catchError((_) {}));
    super.dispose();
  }

  Future<void> _load() async {
    final snapshot = await run(() => FkBox.map('tools.snapshot'));
    if (!mounted) return;
    setState(() {
      _loading = false;
      if (snapshot != null) {
        _endpoints = FkBox.asMaps(snapshot['tailscale']);
      }
    });
  }

  List<Map<String, Object?>> _peers(Map<String, Object?> endpoint) {
    return [
      for (final group in FkBox.asMaps(endpoint['userGroups']))
        ...FkBox.asMaps(group['peers']),
    ];
  }

  Future<void> _peerMenu(String endpointTag, Map<String, Object?> peer) async {
    final id = peer['id'] as String? ?? '';
    final ssh = (peer['sshHostKeys'] as List?)?.isNotEmpty ?? false;
    final name = (peer['hostName'] as String?) ?? id;
    final action = await dialogs.showCommonDialog<String>(
      child: _ChoiceDialog(
        title: name,
        choices: {'ping': fkStrings.ping, if (ssh) 'ssh': fkStrings.ssh},
      ),
    );
    switch (action) {
      case 'ping':
        await run(
          () => FkBox.call<void>('tailscale.ping', {
            'endpoint': endpointTag,
            'peer': id,
          }),
        );
      case 'ssh':
        if (!mounted) return;
        await startSshSession(context, endpointTag, id);
    }
  }

  List<Widget> _endpointSections(Map<String, Object?> endpoint) {
    final tag = (endpoint['endpointTag'] as String?) ?? '';
    final authUrl = (endpoint['authURL'] as String?) ?? '';
    final peers = _peers(endpoint);
    final exitOptions = [
      for (final peer in peers)
        if (peer['exitNodeOption'] == true) peer,
    ];
    final currentExit = FkBox.asMap(endpoint['exitNode'])['id'] as String?;
    final self = FkBox.asMap(endpoint['selfPeer']);
    return [
      generateSection(
        title: tag,
        items: [
          ListItem(
            title: Text(fkStrings.state),
            subtitle: Text(
              [
                endpoint['stateText'] ?? endpoint['backendState'] ?? '',
                if ((endpoint['networkName'] as String? ?? '').isNotEmpty)
                  endpoint['networkName'],
              ].join(' · '),
            ),
          ),
          if (self.isNotEmpty)
            ListItem(
              title: Text((self['hostName'] as String?) ?? ''),
              subtitle: Text(
                ((self['tailscaleIPs'] as List?) ?? const []).join(', '),
              ),
            ),
          if (authUrl.isNotEmpty)
            ListItem(
              leading: const GlyphIcon(AppGlyphs.openExternal),
              title: Text(fkStrings.login),
              subtitle: Text(authUrl, maxLines: 1),
              onTap: () =>
                  run(() => FkBox.call<void>('url.open', {'url': authUrl})),
            ),
          if (exitOptions.isNotEmpty || currentExit != null)
            ListItem<String?>.options(
              title: Text(fkStrings.exitNode),
              options: [
                null,
                for (final peer in exitOptions) peer['id'] as String?,
              ],
              value: currentExit,
              textBuilder: (value) {
                if (value == null) return fkStrings.noExitNode;
                final peer = peers.firstWhere(
                  (item) => item['id'] == value,
                  orElse: () => const {},
                );
                return (peer['hostName'] as String?) ?? value;
              },
              onChanged: (value) => run(
                () => FkBox.call<void>('tailscale.exitNode', {
                  'endpoint': tag,
                  'peer': value,
                }),
              ),
            ),
          if (self.isNotEmpty)
            ListItem(
              leading: const GlyphIcon(AppGlyphs.close),
              title: Text(fkStrings.logout),
              onTap: () async {
                final ok = await dialogs.showMessage(
                  message: TextSpan(text: '${fkStrings.logout}: $tag'),
                  destructive: true,
                );
                if (ok == true) {
                  await run(
                    () =>
                        FkBox.call<void>('tailscale.logout', {'endpoint': tag}),
                  );
                }
              },
            ),
        ],
      ),
      generateSection(
        title: fkStrings.peers,
        items: [
          for (final peer in peers)
            ListItem(
              title: Text((peer['hostName'] as String?) ?? ''),
              subtitle: Text(
                [
                  peer['online'] == true ? fkStrings.online : fkStrings.offline,
                  ((peer['tailscaleIPs'] as List?) ?? const []).join(', '),
                  if (peer['os'] case final String os when os.isNotEmpty) os,
                ].join(' · '),
              ),
              trailing: peer['exitNode'] == true
                  ? TonalChip(
                      label: fkStrings.exitNode,
                      color: context.colorScheme.primary.opacity15,
                      foregroundColor: context.colorScheme.primary,
                    )
                  : null,
              onTap: () => _peerMenu(tag, peer),
            ),
        ],
      ),
    ];
  }

  Widget? _pingResult() {
    if (_ping['hasResult'] != true &&
        _ping['isRunning'] != true &&
        (_ping['error'] as String? ?? '').isEmpty) {
      return null;
    }
    final error = _ping['error'] as String? ?? '';
    final latency = (_ping['latencyMs'] as num?)?.toStringAsFixed(1);
    final route = _ping['isDirect'] == true
        ? (_ping['endpoint'] as String? ?? '')
        : 'DERP ${_ping['derpRegionCode'] ?? ''}';
    return generateSection(
      title: fkStrings.ping,
      items: [
        ListItem(
          title: Text(
            error.isNotEmpty
                ? error
                : latency != null
                ? '$latency ms'
                : '…',
          ),
          subtitle: route.trim().isEmpty ? null : Text(route),
          trailing: _ping['isRunning'] == true
              ? const ActivityIndicator(size: 20)
              : null,
          onTap: () => FkBox.call<void>('tailscale.stopPing'),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return FkPage(
      title: fkStrings.tailscale,
      isLoading: _loading,
      iconActions: [
        IconButtonData(
          glyph: AppGlyphs.refresh,
          tooltip: fkStrings.refresh,
          onPressed: _load,
        ),
      ],
      children: [
        ?_pingResult(),
        if (!_loading && _endpoints.isEmpty)
          fkNote(context, fkStrings.noEndpoints),
        for (final endpoint in _endpoints) ..._endpointSections(endpoint),
      ],
    );
  }
}

/// A short list of choices as a FlClash dialog.
class _ChoiceDialog extends StatelessWidget {
  const _ChoiceDialog({required this.title, required this.choices});

  final String title;
  final Map<String, String> choices;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: title,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          generateGroup([
            for (final entry in choices.entries)
              ListItem(
                title: Text(entry.value),
                onTap: () => Navigator.of(context).pop(entry.key),
              ),
          ]),
        ],
      ),
    );
  }
}
