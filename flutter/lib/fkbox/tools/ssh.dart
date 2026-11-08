// FK-box: Tailscale SSH sessions and their Ghostty terminal. The terminal
// surface is the native view FkBoxPlugin registers (io.fkbox/terminal);
// sessions, prompts and keys are FlClash-style Flutter.
//
// Copyright (C) FK-box contributors. GPL-3.0, as FlClash.

import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import '../fkbox.dart';
import 'common.dart';
import 'tailscale.dart';

/// Asks for the user name (prefilled from the last session with this peer),
/// starts the session and opens its terminal.
Future<void> startSshSession(
  BuildContext context,
  String endpoint,
  String peer,
) async {
  try {
    final options = await FkBox.map('ssh.options', {
      'endpoint': endpoint,
      'peer': peer,
    });
    final username = await fkInput(
      fkStrings.ssh,
      value: (options['username'] as String?) ?? 'root',
      label: fkStrings.username,
    );
    if (username == null || username.trim().isEmpty) return;
    final snapshot = await FkBox.map('ssh.start', {
      'endpoint': endpoint,
      'peer': peer,
      'username': username.trim(),
      'terminalType': options['terminalType'],
      'remember': options['remember'] == true,
    });
    final id = snapshot['activeId'] as String?;
    if (id == null || !context.mounted) return;
    await BaseNavigator.push(context, FkTerminalView(sessionId: id));
  } catch (error) {
    dialogs.showBanner(fkErrorText(error), level: BannerLevel.error);
  }
}

class FkSshView extends StatefulWidget {
  const FkSshView({super.key});

  @override
  State<FkSshView> createState() => _FkSshViewState();
}

class _FkSshViewState extends State<FkSshView> with FkEvents<FkSshView> {
  Map<String, Object?> _terminal = const {};
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    follow(
      'terminal',
      (value) => setState(() => _terminal = FkBox.asMap(value)),
    );
    unawaited(_load());
  }

  Future<void> _load() async {
    final value = await run(() => FkBox.map('ssh.snapshot'));
    if (!mounted) return;
    setState(() {
      _loading = false;
      if (value != null) _terminal = value;
    });
  }

  @override
  Widget build(BuildContext context) {
    final sessions = FkBox.asMaps(_terminal['sessions']);
    final active = _terminal['activeId'] as String?;
    return FkPage(
      title: fkStrings.ssh,
      isLoading: _loading,
      iconActions: [
        IconButtonData(
          glyph: AppGlyphs.add,
          tooltip: fkStrings.newSession,
          onPressed: () => BaseNavigator.push(context, const FkTailscaleView()),
        ),
      ],
      children: [
        if (!_loading && sessions.isEmpty) fkNote(context, fkStrings.empty),
        generateSection(
          title: fkStrings.sessions,
          items: [
            for (final session in sessions)
              ListItem(
                title: Text((session['name'] as String?) ?? ''),
                subtitle: Text(
                  [
                    '${session['username']}@${session['host']}',
                    session['phase'],
                    if (session['directory'] case final String dir
                        when dir.isNotEmpty)
                      dir,
                  ].join(' · '),
                ),
                trailing: session['id'] == active
                    ? const SelectionBadge(size: 24)
                    : null,
                onTap: () async {
                  final id = session['id'] as String;
                  await run(() => FkBox.map('ssh.select', {'id': id}));
                  if (!context.mounted) return;
                  await BaseNavigator.push(
                    context,
                    FkTerminalView(sessionId: id),
                  );
                },
              ),
          ],
        ),
      ],
    );
  }
}

class FkTerminalView extends StatefulWidget {
  const FkTerminalView({super.key, required this.sessionId});

  final String sessionId;

  @override
  State<FkTerminalView> createState() => _FkTerminalViewState();
}

class _FkTerminalViewState extends State<FkTerminalView> {
  static const _viewType = 'io.fkbox/terminal';
  static const _keys = [
    'Esc',
    'Tab',
    'Ctrl',
    'Alt',
    '←',
    '↑',
    '↓',
    '→',
    'Home',
    'End',
    'PgUp',
    'PgDn',
    'Paste',
    'Keyboard',
  ];

  MethodChannel? _channel;
  Map<String, Object?> _modifiers = const {};

  bool get _dark => context.colorScheme.brightness == Brightness.dark;

  Map<String, Object?> get _params => {'id': widget.sessionId, 'dark': _dark};

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    unawaited(_channel?.invokeMethod<void>('update', _params));
  }

  void _attach(int id) {
    final channel = MethodChannel('$_viewType/$id');
    channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'modifiers':
          if (mounted) setState(() => _modifiers = FkBox.asMap(call.arguments));
          return null;
        case 'unsafePaste':
        case 'clipboardRead':
          return dialogs.showMessage(
            message: TextSpan(
              text: call.method == 'unsafePaste'
                  ? 'Paste text that may run commands?'
                  : 'Allow the remote program to read the clipboard?',
            ),
          );
        case 'link':
          final url = call.arguments as String? ?? '';
          final res = await dialogs.showMessage(
            title: url,
            message: TextSpan(text: url),
            confirmText: 'Open',
          );
          return res == true ? 'open' : 'copy';
      }
      return null;
    });
    _channel = channel;
  }

  @override
  void dispose() {
    _channel?.setMethodCallHandler(null);
    super.dispose();
  }

  Widget _keyBar() {
    final colorScheme = context.colorScheme;
    return SizedBox(
      height: 44,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 8),
        children: [
          for (final key in _keys)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 6),
              child: TonalChip(
                label: key,
                color: (_modifiers[key.toLowerCase()] ?? 'Off') != 'Off'
                    ? colorScheme.primary.opacity30
                    : colorScheme.primary.opacity15,
                foregroundColor: colorScheme.primary,
                onPressed: () =>
                    _channel?.invokeMethod<void>('key', {'key': key}),
              ),
            ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      title: fkStrings.ssh,
      iconActions: [
        IconButtonData(
          glyph: AppGlyphs.copy,
          tooltip: fkStrings.duplicate,
          onPressed: () async {
            try {
              final snapshot = await FkBox.map('ssh.duplicate');
              final id = snapshot['activeId'] as String?;
              if (id == null || !context.mounted) return;
              await BaseNavigator.push(context, FkTerminalView(sessionId: id));
            } catch (error) {
              dialogs.showBanner(fkErrorText(error), level: BannerLevel.error);
            }
          },
        ),
        IconButtonData(
          glyph: AppGlyphs.close,
          tooltip: fkStrings.close,
          onPressed: () async {
            await FkBox.call<Object?>('ssh.close', {
              'id': widget.sessionId,
            }).catchError((_) => null);
            if (context.mounted) Navigator.of(context).maybePop();
          },
        ),
      ],
      body: Padding(
        padding: EdgeInsets.only(bottom: context.bottomInset),
        child: Column(
          children: [
            Expanded(
              child: AndroidView(
                viewType: _viewType,
                creationParams: _params,
                creationParamsCodec: const StandardMessageCodec(),
                onPlatformViewCreated: _attach,
                gestureRecognizers:
                    const <Factory<OneSequenceGestureRecognizer>>{
                      Factory<OneSequenceGestureRecognizer>(
                        EagerGestureRecognizer.new,
                      ),
                    },
              ),
            ),
            _keyBar(),
          ],
        ),
      ),
    );
  }
}
