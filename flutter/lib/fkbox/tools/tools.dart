// FK-box: the one FK-box entry on FlClash's Tools page and the page it opens:
// Tailscale (with Taildrop), OpenVPN, OpenConnect, SSH terminal, HTTP
// capture/MITM, USB/IP, network diagnostics, diagnostic reports, remote
// control, and FK-box's own service settings.
//
// Copyright (C) FK-box contributors. GPL-3.0, as FlClash.

import 'dart:async';

import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/widgets.dart';

import '../fkbox.dart';
import 'capture.dart';
import 'common.dart';
import 'diagnostics.dart';
import 'endpoints.dart';
import 'remote.dart';
import 'reports.dart';
import 'ssh.dart';
import 'tailscale.dart';
import 'usbip.dart';

/// The Tools page row (views/tools.dart) that opens [FkToolsView].
class FkToolsItem extends StatelessWidget {
  const FkToolsItem({super.key});

  @override
  Widget build(BuildContext context) {
    return ListItem.open(
      leading: const GlyphIcon(AppGlyphs.layers),
      title: Text(fkStrings.fkboxTools),
      widget: const FkToolsView(),
    );
  }
}

class FkToolsView extends StatefulWidget {
  const FkToolsView({super.key});

  @override
  State<FkToolsView> createState() => _FkToolsViewState();
}

class _FkToolsViewState extends State<FkToolsView> with FkEvents<FkToolsView> {
  Map<String, Object?> _info = const {};

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    final info = await run(() => FkBox.map('info'));
    if (mounted && info != null) setState(() => _info = info);
  }

  Map<String, Object?> get _settings => FkBox.asMap(_info['settings']);

  Future<void> _set(String key, Object? value, {bool restart = false}) async {
    final settings = await run(
      () => FkBox.map('settings.set', {
        'key': key,
        'value': value,
        'restart': restart,
      }),
    );
    if (settings != null && mounted) {
      setState(() => _info = {..._info, 'settings': settings});
    }
  }

  Widget _toggle(String key, String title, {bool restart = false}) {
    return ListItem.toggle(
      title: Text(title),
      value: _settings[key] == true,
      onChanged: _settings.isEmpty
          ? null
          : (value) => _set(key, value, restart: restart),
    );
  }

  Widget _open(Glyph glyph, String title, Widget page) {
    return ListItem.open(
      leading: GlyphIcon(glyph),
      title: Text(title),
      widget: page,
    );
  }

  @override
  Widget build(BuildContext context) {
    return FkPage(
      title: fkStrings.fkboxTools,
      children: [
        generateSection(
          items: [
            _open(AppGlyphs.vpn, fkStrings.tailscale, const FkTailscaleView()),
            _open(
              AppGlyphs.lock,
              fkStrings.openvpn,
              const FkEndpointsView(kind: 'openvpn'),
            ),
            _open(
              AppGlyphs.lock,
              fkStrings.openconnect,
              const FkEndpointsView(kind: 'openconnect'),
            ),
            _open(AppGlyphs.code, fkStrings.ssh, const FkSshView()),
            _open(AppGlyphs.requests, fkStrings.capture, const FkCaptureView()),
            _open(AppGlyphs.devices, fkStrings.usbip, const FkUsbView()),
            _open(
              AppGlyphs.speed,
              fkStrings.diagnostics,
              const FkDiagnosticsView(),
            ),
            _open(AppGlyphs.warning, fkStrings.reports, const FkReportsView()),
            _open(AppGlyphs.link, fkStrings.remote, const FkRemoteView()),
          ],
        ),
        generateSection(
          title: fkStrings.coreSwitches,
          footer: [
            if (_info['singBoxVersion'] case final String v) 'sing-box $v',
            if (_info['xrayVersion'] case final String v) 'Xray $v',
            if (_info['mihomoVersion'] case final String v) 'mihomo $v',
          ].join(' · '),
          items: [
            _toggle('singBoxEnabled', 'sing-box'),
            _toggle('xrayEnabled', 'Xray'),
            _toggle('mihomoEnabled', 'mihomo'),
          ],
        ),
        generateSection(
          title: 'FK-box',
          items: [
            _toggle('perAppManaged', fkStrings.perAppManaged),
            _toggle('autoRedirect', 'Auto redirect (root)', restart: true),
            _toggle(
              'dynamicNotification',
              'Dynamic notification',
              restart: true,
            ),
            _toggle(
              'powerReportEnabled',
              '${fkStrings.battery} ${fkStrings.reports}',
            ),
            _toggle('oomKillerEnabled', 'OOM killer'),
          ],
        ),
      ],
    );
  }
}
