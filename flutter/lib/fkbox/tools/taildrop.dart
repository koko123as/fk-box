// FK-box: Taildrop over the running profile's Tailscale endpoints — the files
// received, the transfers being sent, and sending what another app shared to
// FK-box (the share sheet), answered by BoxTools through FkBoxPlugin.
//
// Copyright (C) FK-box contributors. GPL-3.0, as FlClash.

import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/widgets.dart';

import '../fkbox.dart';
import 'common.dart';

/// The Taildrop inbox of one Tailscale endpoint.
class FkInboxView extends StatefulWidget {
  const FkInboxView({super.key, required this.endpoint});

  final String endpoint;

  @override
  State<FkInboxView> createState() => _FkInboxViewState();
}

class _FkInboxViewState extends State<FkInboxView>
    with FkEvents<FkInboxView> {
  Map<String, Object?> _inbox = const {};

  @override
  void initState() {
    super.initState();
    follow('inbox', (value) => setState(() => _inbox = FkBox.asMap(value)));
    unawaited(_load());
  }

  @override
  void dispose() {
    unawaited(FkBox.call<void>('taildrop.unsubscribe').catchError((_) {}));
    super.dispose();
  }

  Future<void> _load() async {
    await run(
      () => FkBox.call<void>('taildrop.subscribe', {
        'endpoint': widget.endpoint,
      }),
    );
    final inbox = await run(() => FkBox.map('taildrop.inbox'));
    if (mounted && inbox != null) setState(() => _inbox = inbox);
  }

  Future<void> _delete(String name) async {
    final ok = await dialogs.showMessage(
      message: TextSpan(text: '${fkStrings.delete}: $name'),
      destructive: true,
    );
    if (ok != true) return;
    await run(
      () => FkBox.call<void>('taildrop.delete', {
        'endpoint': widget.endpoint,
        'name': name,
      }),
    );
  }

  @override
  Widget build(BuildContext context) {
    final receiving = FkBox.asMaps(_inbox['receiving']);
    final files = FkBox.asMaps(_inbox['files']);
    return FkPage(
      title: fkStrings.receivedFiles,
      children: [
        if (receiving.isEmpty && files.isEmpty) fkNote(context, fkStrings.empty),
        generateSection(
          items: [
            for (final file in receiving)
              ListItem(
                title: Text('${file['name'] ?? ''}'),
                subtitle: Text(
                  '${fkBytes(file['receivedBytes'])} / ${fkBytes(file['size'])}'
                  ' · ${file['senderName'] ?? ''}',
                ),
                trailing: IconButton(
                  icon: const GlyphIcon(AppGlyphs.close),
                  tooltip: fkStrings.cancel,
                  onPressed: () => run(
                    () => FkBox.call<void>('taildrop.cancelReceive', {
                      'endpoint': widget.endpoint,
                      'sender': file['senderID'],
                      'name': file['name'],
                    }),
                  ),
                ),
              ),
          ],
        ),
        generateSection(
          title: fkStrings.receivedFiles,
          items: [
            for (final file in files)
              ListItem(
                title: Text('${file['name'] ?? ''}'),
                subtitle: Text(
                  '${fkBytes(file['size'])} · ${file['senderName'] ?? ''}',
                ),
                onTap: () => run(
                  () => FkBox.call<bool>('taildrop.save', {
                    'endpoint': widget.endpoint,
                    'name': file['name'],
                  }),
                ),
                trailing: IconButton(
                  icon: const GlyphIcon(AppGlyphs.delete),
                  tooltip: fkStrings.delete,
                  onPressed: () => _delete('${file['name'] ?? ''}'),
                ),
              ),
          ],
        ),
      ],
    );
  }
}

/// Taildrop send sessions with their progress.
class FkTransfersView extends StatefulWidget {
  const FkTransfersView({super.key});

  @override
  State<FkTransfersView> createState() => _FkTransfersViewState();
}

class _FkTransfersViewState extends State<FkTransfersView>
    with FkEvents<FkTransfersView> {
  List<Map<String, Object?>> _transfers = const [];

  @override
  void initState() {
    super.initState();
    follow(
      'transfers',
      (value) => setState(() => _transfers = FkBox.asMaps(value)),
    );
    unawaited(_load());
  }

  Future<void> _load() async {
    final value = await run(() => FkBox.list('taildrop.transfers'));
    if (mounted && value != null) {
      setState(() => _transfers = FkBox.asMaps(value));
    }
  }

  Widget _transfer(Map<String, Object?> transfer) {
    final finished = transfer['finished'] == true;
    final error = '${transfer['error'] ?? ''}';
    final id = transfer['id'];
    return generateSection(
      title: '${transfer['peerName'] ?? ''}',
      items: [
        for (final file in FkBox.asMaps(transfer['files']))
          ListItem(
            title: Text('${file['name'] ?? ''}'),
            subtitle: Text(
              file['completed'] == true
                  ? fkBytes(file['size'])
                  : '${fkBytes(file['sentBytes'])} / ${fkBytes(file['size'])}',
            ),
            trailing: file['completed'] == true
                ? const GlyphIcon(AppGlyphs.checkCircle)
                : (finished ? null : const ActivityIndicator(size: 20)),
          ),
        if (error.isNotEmpty && error != 'null')
          ListItem(
            leading: const GlyphIcon(AppGlyphs.warning),
            title: Text(fkStrings.error),
            subtitle: Text(error),
          ),
        ListItem(
          title: Text(finished ? fkStrings.done : fkStrings.cancel),
          onTap: () => run(
            () => FkBox.call<void>(
              finished ? 'taildrop.dismissSend' : 'taildrop.cancelSend',
              {'id': id},
            ),
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return FkPage(
      title: fkStrings.transfers,
      children: [
        if (_transfers.isEmpty) fkNote(context, fkStrings.empty),
        for (final transfer in _transfers) _transfer(transfer),
      ],
    );
  }
}

/// Sends what another app shared to FK-box ([token], kept by the activity
/// until sent or discarded) to a Tailscale peer.
class FkShareView extends StatefulWidget {
  const FkShareView({super.key, required this.token});

  final String token;

  @override
  State<FkShareView> createState() => _FkShareViewState();
}

class _FkShareViewState extends State<FkShareView>
    with FkEvents<FkShareView> {
  List<Map<String, Object?>> _endpoints = const [];
  bool _loading = true;
  bool _sent = false;

  @override
  void initState() {
    super.initState();
    follow('tools', (value) {
      setState(
        () => _endpoints = FkBox.asMaps(FkBox.asMap(value)['tailscale']),
      );
    });
    unawaited(_load());
  }

  @override
  void dispose() {
    if (!_sent) {
      unawaited(
        FkBox.call<void>('share.discard', {
          'token': widget.token,
        }).catchError((_) {}),
      );
    }
    super.dispose();
  }

  Future<void> _load() async {
    final snapshot = await run(() => FkBox.map('tools.snapshot'));
    if (!mounted) return;
    setState(() {
      _loading = false;
      if (snapshot != null) _endpoints = FkBox.asMaps(snapshot['tailscale']);
    });
  }

  Future<void> _send(String endpoint, String peer) async {
    setState(() => _loading = true);
    final ok = await run(
      () => FkBox.call<bool>('share.send', {
        'token': widget.token,
        'endpoint': endpoint,
        'peer': peer,
      }),
    );
    if (!mounted) return;
    setState(() => _loading = false);
    if (ok == true) {
      _sent = true;
      Navigator.of(context).pop();
      final root = globalState.navigatorKey.currentContext;
      if (root != null && root.mounted) {
        unawaited(BaseNavigator.push(root, const FkTransfersView()));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final sections = <Widget>[];
    for (final endpoint in _endpoints) {
      if (endpoint['canShareFiles'] != true) continue;
      final tag = '${endpoint['endpointTag'] ?? ''}';
      final self = FkBox.asMap(endpoint['selfPeer'])['stableID'];
      final peers = [
        for (final group in FkBox.asMaps(endpoint['userGroups']))
          for (final peer in FkBox.asMaps(group['peers']))
            if (peer['canReceiveFiles'] == true && peer['stableID'] != self)
              peer,
      ];
      sections.add(
        generateSection(
          title: tag,
          items: [
            for (final peer in peers)
              ListItem(
                title: Text('${peer['hostName'] ?? peer['id'] ?? ''}'),
                subtitle: Text(
                  peer['online'] == true ? fkStrings.online : fkStrings.offline,
                ),
                onTap: peer['online'] == true && !_loading
                    ? () => _send(tag, '${peer['id'] ?? ''}')
                    : null,
              ),
          ],
        ),
      );
    }
    return FkPage(
      title: fkStrings.sendWithTaildrop,
      isLoading: _loading,
      children: [
        if (!_loading && sections.isEmpty)
          fkNote(context, fkStrings.noRecipients),
        ...sections,
      ],
    );
  }
}
