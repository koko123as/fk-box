// FK-box: remote control — the saved remote sing-box/FK-box servers, and
// connecting to or leaving one (RemoteControlManager through FkBoxPlugin).
//
// Copyright (C) FK-box contributors. GPL-3.0, as FlClash.

import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/widgets.dart';

import '../fkbox.dart';
import 'common.dart';

class FkRemoteView extends StatefulWidget {
  const FkRemoteView({super.key});

  @override
  State<FkRemoteView> createState() => _FkRemoteViewState();
}

class _FkRemoteViewState extends State<FkRemoteView>
    with FkEvents<FkRemoteView> {
  List<Map<String, Object?>> _servers = const [];
  Map<String, Object?> _state = const {};
  bool _loading = true;
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
    // The connection state has no event of its own; follow it while shown.
    _poll = Timer.periodic(const Duration(seconds: 2), (_) => _loadState());
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _loadState() async {
    try {
      final state = await FkBox.map('remote.state');
      if (mounted) setState(() => _state = state);
    } catch (_) {}
  }

  Future<void> _load() async {
    final servers = await run(() => FkBox.list('remote.list'));
    await _loadState();
    if (!mounted) return;
    setState(() {
      _loading = false;
      if (servers != null) _servers = FkBox.asMaps(servers);
    });
  }

  Future<void> _edit([Map<String, Object?> server = const {}]) async {
    final name = await fkInput(
      fkStrings.name,
      value: '${server['name'] ?? ''}',
      label: fkStrings.name,
    );
    if (name == null) return;
    final url = await fkInput(
      fkStrings.address,
      value: '${server['url'] ?? ''}',
      label: 'https://host:port',
    );
    if (url == null) return;
    final secret = await fkInput(
      fkStrings.secret,
      value: '${server['secret'] ?? ''}',
      label: fkStrings.secret,
      obscure: true,
    );
    if (secret == null) return;
    await run(
      () => FkBox.call<Object?>('remote.save', {
        'id': server['id'],
        'name': name.trim(),
        'url': url.trim(),
        'secret': secret,
      }),
    );
    await _load();
  }

  Future<void> _menu(Map<String, Object?> server) async {
    final current = FkBox.asMap(_state['server'])['id'] == server['id'];
    final action = await fkChoose('${server['name'] ?? ''}', {
      if (!current) 'connect': fkStrings.connect,
      if (current) 'disconnect': fkStrings.disconnect,
      'edit': fkStrings.edit,
      'delete': fkStrings.delete,
    });
    switch (action) {
      case 'connect':
        await run(
          () => FkBox.call<void>('remote.connect', {'id': server['id']}),
        );
      case 'disconnect':
        await run(() => FkBox.call<void>('remote.disconnect'));
      case 'edit':
        await _edit(server);
        return;
      case 'delete':
        final ok = await dialogs.showMessage(
          message: TextSpan(text: '${fkStrings.delete}: ${server['name']}'),
          destructive: true,
        );
        if (ok != true) return;
        await run(() => FkBox.call<void>('remote.delete', {'id': server['id']}));
      default:
        return;
    }
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final current = FkBox.asMap(_state['server']);
    final connected = _state['connected'] == true;
    return FkPage(
      title: fkStrings.remote,
      isLoading: _loading,
      iconActions: [
        IconButtonData(
          glyph: AppGlyphs.add,
          tooltip: fkStrings.addServer,
          onPressed: _edit,
        ),
      ],
      children: [
        if (current.isNotEmpty)
          generateSection(
            title: fkStrings.state,
            items: [
              ListItem(
                leading: const GlyphIcon(AppGlyphs.link),
                title: Text('${current['name'] ?? current['url'] ?? ''}'),
                subtitle: Text(
                  connected ? fkStrings.connected : fkStrings.connecting,
                ),
                trailing: connected ? null : const ActivityIndicator(size: 20),
              ),
              ListItem(
                leading: const GlyphIcon(AppGlyphs.close),
                title: Text(fkStrings.disconnect),
                onTap: () async {
                  await run(() => FkBox.call<void>('remote.disconnect'));
                  await _load();
                },
              ),
            ],
          ),
        if (!_loading && _servers.isEmpty) fkNote(context, fkStrings.empty),
        generateSection(
          title: fkStrings.servers,
          items: [
            for (final server in _servers)
              ListItem(
                leading: const GlyphIcon(AppGlyphs.devices),
                title: Text('${server['name'] ?? ''}'),
                subtitle: Text('${server['url'] ?? ''}'),
                trailing: current['id'] == server['id']
                    ? TonalChip(
                        label: connected
                            ? fkStrings.connected
                            : fkStrings.connecting,
                        color: context.colorScheme.primary.opacity15,
                        foregroundColor: context.colorScheme.primary,
                      )
                    : null,
                onTap: () => _menu(server),
              ),
          ],
        ),
      ],
    );
  }
}
