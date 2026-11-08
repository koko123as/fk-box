// FK-box: network diagnostics through the running sing-box profile — the
// NAT type over STUN and the network quality (capacity and responsiveness)
// test, answered by BoxTools.
//
// Copyright (C) FK-box contributors. GPL-3.0, as FlClash.

import 'dart:async';

import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/widgets.dart';

import '../fkbox.dart';
import 'common.dart';

class FkDiagnosticsView extends StatefulWidget {
  const FkDiagnosticsView({super.key});

  @override
  State<FkDiagnosticsView> createState() => _FkDiagnosticsViewState();
}

class _FkDiagnosticsViewState extends State<FkDiagnosticsView>
    with FkEvents<FkDiagnosticsView> {
  Map<String, Object?> _stun = const {};
  Map<String, Object?> _quality = const {};
  List<String> _outbounds = const [];
  String? _outbound;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    follow('stun', (value) => setState(() => _stun = FkBox.asMap(value)));
    follow('quality', (value) => setState(() => _quality = FkBox.asMap(value)));
    follow('outbounds', (value) {
      setState(() {
        _outbounds = [
          for (final item in FkBox.asMaps(value))
            (item['tag'] as String?) ?? '',
        ];
      });
    });
    unawaited(_load());
  }

  Future<void> _load() async {
    final snapshot = await run(() => FkBox.map('tools.snapshot'));
    if (!mounted) return;
    setState(() {
      _loading = false;
      final diagnostics = FkBox.asMap(snapshot?['diagnostics']);
      if (diagnostics.isNotEmpty) {
        _stun = FkBox.asMap(diagnostics['stun']);
        _quality = FkBox.asMap(diagnostics['quality']);
      }
    });
  }

  String _ms(Object? value) {
    final number = value as num?;
    return number == null || number <= 0 ? '—' : '${number.round()} ms';
  }

  String _mbps(Object? value) {
    final number = value as num?;
    if (number == null || number <= 0) return '—';
    return '${(number / 1000000).toStringAsFixed(1)} Mbps';
  }

  Widget _stunSection() {
    final running = _stun['isRunning'] == true;
    return generateSection(
      title: fkStrings.stun,
      items: [
        ListItem.input(
          title: Text(fkStrings.server),
          subtitle: Text((_stun['server'] as String?) ?? ''),
          dialogTitle: fkStrings.server,
          value: (_stun['server'] as String?) ?? '',
          onChanged: (value) {
            if (value == null) return;
            setState(() => _stun = {..._stun, 'server': value.trim()});
          },
        ),
        ListItem(
          title: Text(
            (_stun['externalAddr'] as String?)?.isNotEmpty == true
                ? _stun['externalAddr'] as String
                : (_stun['phase'] ?? '').toString(),
          ),
          subtitle: Text(
            [
              _ms(_stun['latencyMs']),
              if (_stun['mapping'] case final String mapping
                  when mapping.isNotEmpty)
                mapping,
              if (_stun['filtering'] case final String filtering
                  when filtering.isNotEmpty)
                filtering,
            ].join(' · '),
          ),
          trailing: TextButton(
            onPressed: () => run(
              () => running
                  ? FkBox.call<void>('stun.stop')
                  : FkBox.call<void>('stun.start', {
                      'server': _stun['server'],
                      'outbound': _outbound,
                    }),
            ),
            child: Text(running ? fkStrings.stop : fkStrings.start),
          ),
        ),
      ],
    );
  }

  Future<void> _startQuality() async {
    await run(
      () => FkBox.call<void>('quality.start', {
        'url': _quality['configURL'],
        'outbound': _outbound,
        'serial': _quality['serial'] == true,
        'http3': _quality['http3'] == true,
        'seconds': _quality['maxRuntime'],
      }),
    );
  }

  Widget _qualitySection() {
    final running = _quality['isRunning'] == true;
    final metered = _quality['showMeteredWarning'] == true;
    return generateSection(
      title: fkStrings.quality,
      items: [
        ListItem(
          title: Text(
            '↓ ${_mbps(_quality['downloadCapacity'])}  ↑ ${_mbps(_quality['uploadCapacity'])}',
          ),
          subtitle: Text(
            [
              (_quality['phase'] ?? '').toString(),
              'idle ${_ms(_quality['idleLatencyMs'])}',
              'RPM ${_quality['downloadRPM'] ?? '—'}/${_quality['uploadRPM'] ?? '—'}',
            ].join(' · '),
          ),
          trailing: TextButton(
            onPressed: () => running
                ? run(() => FkBox.call<void>('quality.stop'))
                : _startQuality(),
            child: Text(running ? fkStrings.stop : fkStrings.start),
          ),
        ),
        if (metered)
          ListItem(
            leading: const GlyphIcon(AppGlyphs.warning),
            title: const Text('Metered network'),
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextButton(
                  onPressed: () =>
                      run(() => FkBox.call<void>('quality.dismiss')),
                  child: Text(fkStrings.close),
                ),
                TextButton(
                  onPressed: () =>
                      run(() => FkBox.call<void>('quality.confirm')),
                  child: Text(fkStrings.start),
                ),
              ],
            ),
          ),
        ListItem.toggle(
          title: const Text('HTTP/3'),
          value: _quality['http3'] == true,
          onChanged: running
              ? null
              : (value) =>
                    setState(() => _quality = {..._quality, 'http3': value}),
        ),
        ListItem.toggle(
          title: const Text('Serial'),
          value: _quality['serial'] == true,
          onChanged: running
              ? null
              : (value) =>
                    setState(() => _quality = {..._quality, 'serial': value}),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return FkPage(
      title: fkStrings.diagnostics,
      isLoading: _loading,
      children: [
        if (_outbounds.isNotEmpty)
          generateSection(
            items: [
              ListItem<String?>.options(
                title: Text(fkStrings.outbound),
                options: [null, ..._outbounds],
                value: _outbound,
                textBuilder: (value) => value ?? fkStrings.coreAuto,
                onChanged: (value) => setState(() => _outbound = value),
              ),
            ],
          ),
        _stunSection(),
        _qualitySection(),
      ],
    );
  }
}
