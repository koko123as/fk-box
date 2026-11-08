// FK-box: crash, OOM and battery reports (BoxReports) — list, read, export
// with the running config and logs, delete.
//
// Copyright (C) FK-box contributors. GPL-3.0, as FlClash.

import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/widgets.dart';

import '../fkbox.dart';
import 'common.dart';

class FkReportsView extends StatefulWidget {
  const FkReportsView({super.key});

  @override
  State<FkReportsView> createState() => _FkReportsViewState();
}

class _FkReportsViewState extends State<FkReportsView>
    with FkEvents<FkReportsView> {
  static const _kinds = ['crash', 'oom', 'power'];
  final Map<String, List<Map<String, Object?>>> _reports = {};
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  String _kindLabel(String kind) => switch (kind) {
    'crash' => fkStrings.crash,
    'oom' => fkStrings.oom,
    _ => fkStrings.battery,
  };

  Future<void> _load() async {
    for (final kind in _kinds) {
      final value = await run(() => FkBox.list('reports.list', {'kind': kind}));
      _reports[kind] = FkBox.asMaps(value);
    }
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _open(String kind, Map<String, Object?> report) async {
    final id = report['id']?.toString();
    final files = await run(
      () => FkBox.list('reports.files', {'kind': kind, 'id': id}),
    );
    if (files == null || !mounted) return;
    await BaseNavigator.push(
      context,
      _ReportFilesView(
        title: '${_kindLabel(kind)} ${fkDate(report['date'])}',
        kind: kind,
        id: id,
        files: FkBox.asMaps(files),
      ),
    );
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final empty = _reports.values.every((list) => list.isEmpty);
    return FkPage(
      title: fkStrings.reports,
      isLoading: _loading,
      children: [
        if (!_loading && empty) fkNote(context, fkStrings.empty),
        for (final kind in _kinds)
          generateSection(
            title: _kindLabel(kind),
            actions: [
              if ((_reports[kind] ?? const []).isNotEmpty)
                IconButtonData(
                  glyph: AppGlyphs.clearAll,
                  tooltip: fkStrings.clear,
                  onPressed: () async {
                    final ok = await dialogs.showMessage(
                      message: TextSpan(
                        text: '${fkStrings.clear}: ${_kindLabel(kind)}',
                      ),
                      destructive: true,
                    );
                    if (ok != true) return;
                    await run(
                      () => FkBox.call<void>('reports.clear', {'kind': kind}),
                    );
                    await _load();
                  },
                ),
            ],
            items: [
              for (final report
                  in _reports[kind] ?? const <Map<String, Object?>>[])
                ListItem(
                  title: Text(fkDate(report['date'])),
                  trailing: report['read'] == true
                      ? null
                      : TonalChip(
                          label: 'new',
                          color: context.colorScheme.primary.opacity15,
                          foregroundColor: context.colorScheme.primary,
                        ),
                  onTap: () => _open(kind, report),
                ),
            ],
          ),
      ],
    );
  }
}

class _ReportFilesView extends StatelessWidget {
  const _ReportFilesView({
    required this.title,
    required this.kind,
    required this.id,
    required this.files,
  });

  final String title;
  final String kind;
  final String? id;
  final List<Map<String, Object?>> files;

  Future<void> _call(String method, [Map<String, Object?>? args]) async {
    try {
      await FkBox.call<Object?>(method, {'kind': kind, 'id': id, ...?args});
    } catch (error) {
      dialogs.showBanner(fkErrorText(error), level: BannerLevel.error);
    }
  }

  @override
  Widget build(BuildContext context) {
    return FkPage(
      title: title,
      iconActions: [
        IconButtonData(
          glyph: AppGlyphs.export,
          tooltip: fkStrings.export,
          onPressed: () => _call('reports.export', {
            'config': true,
            'logs': true,
            'encrypt': false,
          }),
        ),
        IconButtonData(
          glyph: AppGlyphs.delete,
          tooltip: fkStrings.delete,
          onPressed: () async {
            await _call('reports.delete');
            if (context.mounted) Navigator.of(context).maybePop();
          },
        ),
      ],
      children: [
        generateSection(
          items: [
            for (final file in files)
              ListItem(
                title: Text((file['label'] as String?) ?? ''),
                subtitle: Text(fkBytes(file['size'])),
                onTap: () async {
                  if (file['kind'] == 'PROFILE') {
                    await _call('reports.save', {'name': file['name']});
                    return;
                  }
                  try {
                    final text = await FkBox.call<Object?>('reports.read', {
                      'kind': kind,
                      'id': id,
                      'name': file['name'],
                    });
                    if (!context.mounted) return;
                    await BaseNavigator.push(
                      context,
                      FkTextView(
                        title: (file['label'] as String?) ?? '',
                        text: '$text',
                      ),
                    );
                  } catch (error) {
                    dialogs.showBanner(
                      fkErrorText(error),
                      level: BannerLevel.error,
                    );
                  }
                },
              ),
          ],
        ),
      ],
    );
  }
}
