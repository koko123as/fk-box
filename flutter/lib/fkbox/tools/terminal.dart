// FK-box: the Ghostty terminal settings of the SSH sessions — light and dark
// themes, font (system, imported or the theme's), font size and the raw
// Ghostty configuration per brightness (BoxTerminalSettings via FkBoxPlugin).
//
// Copyright (C) FK-box contributors. GPL-3.0, as FlClash.

import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/widgets.dart';

import '../fkbox.dart';
import 'common.dart';

class FkTerminalSettingsView extends StatefulWidget {
  const FkTerminalSettingsView({super.key});

  @override
  State<FkTerminalSettingsView> createState() => _FkTerminalSettingsViewState();
}

class _FkTerminalSettingsViewState extends State<FkTerminalSettingsView>
    with FkEvents<FkTerminalSettingsView> {
  Map<String, Object?> _settings = const {};
  Map<String, Object?> _fonts = const {};
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    final settings = await run(() => FkBox.map('terminal.settings'));
    final fonts = await run(() => FkBox.map('terminal.fonts'));
    if (!mounted) return;
    setState(() {
      _loading = false;
      _settings = settings ?? const {};
      _fonts = fonts ?? const {};
    });
  }

  Future<void> _set(String key, Object? value) async {
    final settings = await run(
      () => FkBox.map('terminal.set', {'key': key, 'value': value}),
    );
    if (settings != null && mounted) setState(() => _settings = settings);
  }

  Future<void> _pickTheme(bool dark) async {
    final key = dark ? 'darkTheme' : 'lightTheme';
    final chosen = await BaseNavigator.push<String>(
      context,
      _FkThemePicker(dark: dark, current: '${_settings[key] ?? ''}'),
    );
    if (chosen != null) await _set(key, chosen);
  }

  Future<void> _pickFont() async {
    final system = [
      for (final name in (_fonts['system'] as List?) ?? const []) '$name',
    ];
    final imported = FkBox.asMaps(_fonts['imported']);
    final chosen = await fkChoose(fkStrings.font, {
      'theme': fkStrings.fontFromTheme,
      for (final name in system) 'family:$name': name,
      for (final font in imported)
        'path:${font['path']}': '${font['name'] ?? font['path']}',
      'import': fkStrings.importFont,
    });
    if (chosen == null) return;
    if (chosen == 'theme') {
      await _set('fontFollowTheme', true);
    } else if (chosen == 'import') {
      final settings = await run(() => FkBox.map('terminal.importFont'));
      if (settings != null && settings.isNotEmpty && mounted) {
        setState(() => _settings = settings);
      }
      await _load();
    } else if (chosen.startsWith('family:')) {
      await _set('fontFamily', chosen.substring('family:'.length));
    } else if (chosen.startsWith('path:')) {
      await _set('fontPath', chosen.substring('path:'.length));
    }
  }

  Future<void> _deleteFont() async {
    final imported = FkBox.asMaps(_fonts['imported']);
    if (imported.isEmpty) {
      dialogs.showBanner(fkStrings.empty);
      return;
    }
    final chosen = await fkChoose(fkStrings.delete, {
      for (final font in imported)
        '${font['path']}': '${font['name'] ?? font['path']}',
    });
    if (chosen == null) return;
    final settings = await run(
      () => FkBox.map('terminal.deleteFont', {'path': chosen}),
    );
    if (settings != null && mounted) setState(() => _settings = settings);
    await _load();
  }

  String _fontText() {
    if (_settings['fontFollowTheme'] == true) return fkStrings.fontFromTheme;
    final path = '${_settings['fontPath'] ?? ''}';
    if (path.isNotEmpty) return path.split('/').last;
    final family = '${_settings['fontFamily'] ?? ''}';
    return family.isEmpty ? 'monospace' : family;
  }

  Future<void> _editConfig(bool dark) async {
    final key = dark ? 'darkConfig' : 'lightConfig';
    final value = await BaseNavigator.push<String>(
      context,
      _FkConfigEditor(
        title: dark ? fkStrings.darkConfig : fkStrings.lightConfig,
        value: '${_settings[key] ?? ''}',
      ),
    );
    if (value != null) await _set(key, value);
  }

  @override
  Widget build(BuildContext context) {
    String theme(String key, String fallback) {
      final name = '${_settings[key] ?? ''}';
      return name.isEmpty ? '${_settings[fallback] ?? ''}' : name;
    }

    final size = (_settings['fontSize'] as num?)?.toInt() ?? 0;
    return FkPage(
      title: fkStrings.terminalSettings,
      isLoading: _loading,
      children: [
        generateSection(
          title: fkStrings.theme,
          items: [
            ListItem(
              leading: const GlyphIcon(AppGlyphs.sun),
              title: Text(fkStrings.lightTheme),
              subtitle: Text(theme('lightTheme', 'defaultLightTheme')),
              onTap: () => _pickTheme(false),
            ),
            ListItem(
              leading: const GlyphIcon(AppGlyphs.pureBlack),
              title: Text(fkStrings.darkTheme),
              subtitle: Text(theme('darkTheme', 'defaultDarkTheme')),
              onTap: () => _pickTheme(true),
            ),
          ],
        ),
        generateSection(
          title: fkStrings.font,
          items: [
            ListItem(
              leading: const GlyphIcon(AppGlyphs.font),
              title: Text(fkStrings.font),
              subtitle: Text(_fontText()),
              onTap: _pickFont,
            ),
            ListItem(
              leading: const GlyphIcon(AppGlyphs.textSize),
              title: Text(fkStrings.fontSize),
              subtitle: Text(size > 0 ? '$size' : '—'),
              onTap: () async {
                final value = await fkInput(
                  fkStrings.fontSize,
                  value: size > 0 ? '$size' : '',
                  label: '8–48',
                );
                final parsed = int.tryParse(value?.trim() ?? '');
                if (parsed == null) return;
                await _set('fontSize', parsed);
              },
            ),
            ListItem(
              leading: const GlyphIcon(AppGlyphs.delete),
              title: Text(fkStrings.deleteImportedFont),
              onTap: _deleteFont,
            ),
          ],
        ),
        generateSection(
          title: fkStrings.ghosttyConfig,
          items: [
            ListItem(
              leading: const GlyphIcon(AppGlyphs.code),
              title: Text(fkStrings.lightConfig),
              onTap: () => _editConfig(false),
            ),
            ListItem(
              leading: const GlyphIcon(AppGlyphs.code),
              title: Text(fkStrings.darkConfig),
              onTap: () => _editConfig(true),
            ),
          ],
        ),
      ],
    );
  }
}

/// Ghostty's bundled themes for one brightness, with their colors.
class _FkThemePicker extends StatefulWidget {
  const _FkThemePicker({required this.dark, required this.current});

  final bool dark;
  final String current;

  @override
  State<_FkThemePicker> createState() => _FkThemePickerState();
}

class _FkThemePickerState extends State<_FkThemePicker>
    with FkEvents<_FkThemePicker> {
  List<Map<String, Object?>> _themes = const [];
  String _query = '';
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    final themes = await run(
      () => FkBox.list('terminal.themes', {'dark': widget.dark}),
    );
    if (!mounted) return;
    setState(() {
      _loading = false;
      _themes = FkBox.asMaps(themes);
    });
  }

  Widget _swatch(Object? value) {
    final color = (value as num?)?.toInt();
    return Container(
      width: 12,
      height: 12,
      margin: const EdgeInsets.only(right: 2),
      decoration: BoxDecoration(
        color: color == null ? null : Color(color),
        borderRadius: BorderRadius.circular(3),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final query = _query.toLowerCase();
    final themes = [
      for (final theme in _themes)
        if (query.isEmpty || '${theme['name']}'.toLowerCase().contains(query))
          theme,
    ];
    return FkPage(
      title: widget.dark ? fkStrings.darkTheme : fkStrings.lightTheme,
      isLoading: _loading,
      iconActions: [
        IconButtonData(
          glyph: AppGlyphs.search,
          tooltip: 'Filter',
          isSelected: _query.isNotEmpty,
          onPressed: () async {
            final value = await fkInput('Filter', value: _query);
            if (value != null) setState(() => _query = value.trim());
          },
        ),
      ],
      children: [
        generateSection(
          items: [
            ListItem(
              title: Text(fkStrings.defaultValue),
              trailing: widget.current.isEmpty
                  ? const GlyphIcon(AppGlyphs.check)
                  : null,
              onTap: () => Navigator.of(context).pop(''),
            ),
            for (final theme in themes)
              ListItem(
                title: Text('${theme['name'] ?? ''}'),
                subtitle: Row(
                  children: [
                    _swatch(theme['background']),
                    _swatch(theme['foreground']),
                    for (final color
                        in ((theme['palette'] as List?) ?? const []).take(8))
                      _swatch(color),
                  ],
                ),
                trailing: widget.current == theme['name']
                    ? const GlyphIcon(AppGlyphs.check)
                    : null,
                onTap: () => Navigator.of(context).pop('${theme['name']}'),
              ),
          ],
        ),
      ],
    );
  }
}

/// A raw Ghostty configuration, edited as text.
class _FkConfigEditor extends StatefulWidget {
  const _FkConfigEditor({required this.title, required this.value});

  final String title;
  final String value;

  @override
  State<_FkConfigEditor> createState() => _FkConfigEditorState();
}

class _FkConfigEditorState extends State<_FkConfigEditor> {
  late final TextEditingController _controller = TextEditingController(
    text: widget.value,
  );

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      title: widget.title,
      iconActions: [
        IconButtonData(
          glyph: AppGlyphs.check,
          tooltip: fkStrings.save,
          onPressed: () => Navigator.of(context).pop(_controller.text),
        ),
      ],
      body: Padding(
        padding: const EdgeInsets.all(16).copyWith(
          top: context.sectionsTopPadding,
          bottom: 16 + context.bottomInset,
        ),
        child: TextField(
          controller: _controller,
          maxLines: null,
          expands: true,
          autocorrect: false,
          enableSuggestions: false,
          keyboardType: TextInputType.multiline,
          style: AppTextStyle.footnote
              .of(context)
              .copyWith(fontFamily: 'monospace'),
        ),
      ),
    );
  }
}
