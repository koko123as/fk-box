// FK-box: USB/IP — share this device's USB devices with a USB/IP server
// endpoint of the running sing-box profile (BoxTools/BoxUsb).
//
// Copyright (C) FK-box contributors. GPL-3.0, as FlClash.

import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/widgets.dart';

import '../fkbox.dart';
import 'common.dart';

class FkUsbView extends StatefulWidget {
  const FkUsbView({super.key});

  @override
  State<FkUsbView> createState() => _FkUsbViewState();
}

class _FkUsbViewState extends State<FkUsbView> with FkEvents<FkUsbView> {
  List<Map<String, Object?>> _servers = const [];
  List<Map<String, Object?>> _local = const [];
  List<Map<String, Object?>> _attached = const [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    follow('tools', (value) {
      setState(() => _servers = FkBox.asMaps(FkBox.asMap(value)['usbip']));
    });
    follow(
      'usbDevices',
      (value) => setState(() => _attached = FkBox.asMaps(value)),
    );
    unawaited(_load());
  }

  Future<void> _load() async {
    final snapshot = await run(() => FkBox.map('tools.snapshot'));
    final local = await run(() => FkBox.list('usb.devices'));
    if (!mounted) return;
    setState(() {
      _loading = false;
      if (snapshot != null) _servers = FkBox.asMaps(snapshot['usbip']);
      if (local != null) _local = FkBox.asMaps(local);
    });
  }

  Future<void> _attach(Map<String, Object?> device) async {
    if (_servers.isEmpty) {
      dialogs.showBanner(fkStrings.noEndpoints, level: BannerLevel.warning);
      return;
    }
    final servers = [
      for (final s in _servers) (s['serverTag'] as String?) ?? '',
    ];
    String? server = servers.first;
    if (servers.length > 1) {
      server = await dialogs.showCommonDialog<String>(
        child: AlertDialog(
          title: fkStrings.servers,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              generateGroup([
                for (final tag in servers)
                  Builder(
                    builder: (context) => ListItem(
                      title: Text(tag),
                      onTap: () => Navigator.of(context).pop(tag),
                    ),
                  ),
              ]),
            ],
          ),
        ),
      );
    }
    if (server == null) return;
    await run(
      () => FkBox.call<void>('usb.attach', {
        'server': server,
        'device': device['id'],
      }),
    );
  }

  @override
  Widget build(BuildContext context) {
    return FkPage(
      title: fkStrings.usbip,
      isLoading: _loading,
      iconActions: [
        IconButtonData(
          glyph: AppGlyphs.refresh,
          tooltip: fkStrings.refresh,
          onPressed: _load,
        ),
      ],
      children: [
        if (!_loading && _servers.isEmpty)
          fkNote(context, fkStrings.noEndpoints),
        generateSection(
          title: fkStrings.devices,
          items: [
            for (final device in _local)
              ListItem(
                leading: const GlyphIcon(AppGlyphs.devices),
                title: Text((device['name'] as String?) ?? ''),
                subtitle: Text(
                  '${(device['vendor'] as num? ?? 0).toInt().toRadixString(16).padLeft(4, '0')}:'
                  '${(device['product'] as num? ?? 0).toInt().toRadixString(16).padLeft(4, '0')}',
                ),
                trailing: TextButton(
                  onPressed: () => _attach(device),
                  child: Text(fkStrings.attach),
                ),
              ),
          ],
        ),
        generateSection(
          title: fkStrings.state,
          items: [
            for (final device in _attached)
              ListItem(
                title: Text((device['label'] as String?) ?? ''),
                subtitle: Text(
                  [
                    device['server'] ?? '',
                    device['state'] ?? '',
                    if ((device['error'] as String? ?? '').isNotEmpty)
                      device['error'],
                  ].join(' · '),
                ),
                trailing: TextButton(
                  onPressed: () => run(
                    () => FkBox.call<void>('usb.detach', {'id': device['id']}),
                  ),
                  child: Text(fkStrings.detach),
                ),
              ),
          ],
        ),
        for (final server in _servers)
          generateSection(
            title: (server['serverTag'] as String?) ?? '',
            items: [
              for (final device in FkBox.asMaps(server['devices']))
                ListItem(
                  title: Text(
                    (device['product'] as String?)?.isNotEmpty == true
                        ? device['product'] as String
                        : (device['busId'] as String?) ?? '',
                  ),
                  subtitle: Text(
                    [device['busId'] ?? '', device['state'] ?? ''].join(' · '),
                  ),
                ),
            ],
          ),
      ],
    );
  }
}
