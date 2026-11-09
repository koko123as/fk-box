// Translated from FlClash v0.9.3 (be84736) lib/features/form/quick_edit.dart
// and lib/common/input_entries.dart (parseListInput / parseLineInput), GPL-3.0.
import 'dart:async';
import 'package:flutter/services.dart';
import '../widgets.dart';

/// Thrown by an `apply` the user called off, which keeps the editor open.
class QuickEditCancelled implements Exception {
  const QuickEditCancelled();
}

/// An input the editor rejects; its message is already shown to the user as is.
class QuickEditInvalid implements Exception {
  const QuickEditInvalid(this.message);
  final String message;
  @override String toString() => message;
}

enum InputIssueKind { valueTooLong, invalidValue }

/// One rejected value: its 1-based line, the value and why.
class InputIssue {
  const InputIssue(this.line, this.raw, this.kind);
  final int line;
  final String raw;
  final InputIssueKind kind;
}

class ParsedInput {
  const ParsedInput(this.entries, this.issues);
  final List<String> entries;
  final List<InputIssue> issues;
  bool get isValid => issues.isEmpty;
}

final _lineSeparator = RegExp(r'\r?\n');
final _listSeparator = RegExp(r'[,，]');

/// BatchInput.maxShownIssues.
const quickEditMaxShownIssues = 3;

/// Lines copied out of a YAML or JSON sequence keep their `- ` marker, trailing comma and quotes.
String _stripLineMarkup(String line) {
  var value = line.trim();
  if (value.startsWith('- ')) value = value.substring(2).trim();
  if (value.endsWith(',')) value = value.substring(0, value.length - 1).trim();
  return value;
}

String _unquote(String value) {
  if (value.length < 2) return value;
  final first = value[0];
  return (first == '"' || first == "'") && value.endsWith(first) ? value.substring(1, value.length - 1).trim() : value;
}

/// input_entries.dart's parseListInput: one item a line (commas also split), blanks and repeats dropped.
ParsedInput parseListInput(String text, {int? maxLength, bool Function(String value)? isValid}) {
  final entries = <String>[];
  final issues = <InputIssue>[];
  final seen = <String>{};
  final lines = text.split(_lineSeparator);
  for (var index = 0; index < lines.length; index++) {
    for (final raw in _stripLineMarkup(lines[index]).split(_listSeparator)) {
      final value = _unquote(raw.trim());
      if (value.isEmpty) continue;
      if (maxLength != null && value.length > maxLength) {
        issues.add(InputIssue(index + 1, value, InputIssueKind.valueTooLong));
        continue;
      }
      if (isValid != null && !isValid(value)) {
        issues.add(InputIssue(index + 1, value, InputIssueKind.invalidValue));
        continue;
      }
      if (seen.add(value)) entries.add(value);
    }
  }
  return ParsedInput(entries, issues);
}

/// input_entries.dart's parseLineInput: keeps each non-blank line whole, so its commas and quotes stay its own.
List<(int, String)> parseLineInput(String text) => [
  for (final (index, line) in text.split(_lineSeparator).indexed)
    if (line.trim().isNotEmpty) (index + 1, line.trim()),
];

String lineIssue(BuildContext context, int line, String message) =>
    context.tr(r'Line %1$d: %2$s').replaceAll(r'%1$d', '$line').replaceAll(r'%2$s', message);

/// quick_edit.dart's _throwIssues: the first three issues as "Line n: message".
void throwQuickEditIssues(BuildContext context, List<InputIssue> issues, String Function(InputIssue issue) messageOf) {
  if (issues.isEmpty) return;
  throw QuickEditInvalid(issues.take(quickEditMaxShownIssues).map((issue) => lineIssue(context, issue.line, messageOf(issue))).join('\n'));
}

/// quick_edit.dart's showQuickEdit: edits a list as text. Saving an unchanged
/// text closes the page; otherwise [apply] runs, and a failure shows its
/// message (or [invalidMessage] for an invalid number) with "Discard changes?",
/// leaving the editor open unless the user discards. [QuickEditCancelled]
/// keeps it open silently.
class QuickEditPage extends StatefulWidget {
  const QuickEditPage({super.key, required this.title, required this.content, required this.apply, this.invalidMessage, this.hint});
  final String title, content;
  final String? invalidMessage, hint;
  final FutureOr<void> Function(BuildContext context, String content) apply;
  @override State<QuickEditPage> createState() => _QuickEditPageState();
}

class _QuickEditPageState extends State<QuickEditPage> {
  late final _text = TextEditingController(text: widget.content);
  bool _saving = false, _closing = false;

  Future<void> _close() async {
    setState(() => _closing = true);
    await WidgetsBinding.instance.endOfFrame;
    if (mounted) context.safeNestedPop();
  }

  @override void initState() { super.initState(); _text.addListener(_changed); }
  void _changed() { if (mounted) setState(() {}); }
  @override void dispose() { _text.dispose(); super.dispose(); }

  Future<void> _save() async {
    if (_text.text == widget.content) {
      await _close();
      return;
    }
    setState(() => _saving = true);
    Object? failure;
    try {
      await widget.apply(context, _text.text);
    } on QuickEditCancelled {
      if (mounted) setState(() => _saving = false);
      return;
    } catch (error) {
      failure = error;
    }
    if (!mounted) return;
    setState(() => _saving = false);
    if (failure == null) {
      await _close();
      return;
    }
    final message = failure is FormatException && widget.invalidMessage != null ? widget.invalidMessage! : BoxController.describe(failure);
    if (await confirm(context, 'Quick edit', message: '$message\n\n${context.tr('Discard changes?')}', action: 'Discard') && mounted) {
      await _close();
    }
  }

  @override Widget build(BuildContext context) => BoxEditGuard(
    dirty: !_closing && _text.text != widget.content, busy: _saving,
    child: BoxPage(
      title: widget.title,
      loading: _saving,
      confirmationAction: IconButtonData(glyph: AppGlyphs.check, tooltip: context.tr('Save'), onPressed: _saving ? null : _save),
      children: [
        ListFooter(text: context.tr('One item per line.')),
        Padding(padding: const EdgeInsets.only(top: 8), child: TextField(
          controller: _text, readOnly: _saving, minLines: 10, maxLines: 24,
          autocorrect: false, enableSuggestions: false, keyboardType: TextInputType.multiline,
          smartDashesType: SmartDashesType.disabled, smartQuotesType: SmartQuotesType.disabled,
          style: TextStyle(fontFamily: 'JetBrainsMono', fontSize: 13, height: 1.6, color: context.colorScheme.onSurface),
          decoration: InputDecoration(hintText: widget.hint, filled: true),
        )),
      ],
    ),
  );
}

/// quick_edit.dart's showListQuickEdit: one item a line; [validator] names why an item is rejected.
Future<void> openListQuickEdit(BuildContext context, {
  required String title,
  required List<String> items,
  String? hint,
  int? itemMaxLength,
  String? Function(String item)? validator,
  required FutureOr<void> Function(List<String> items) onChanged,
}) => openPage<void>(context, QuickEditPage(
  title: title, content: items.join('\n'), hint: hint,
  apply: (context, content) async {
    final parsed = parseListInput(content, maxLength: itemMaxLength, isValid: validator == null ? null : (item) => validator(item) == null);
    throwQuickEditIssues(context, parsed.issues, (issue) => switch (issue.kind) {
      InputIssueKind.invalidValue => validator?.call(issue.raw) ?? context.tr('Invalid value'),
      InputIssueKind.valueTooLong => context.tr('Value must be at most %d characters').replaceAll('%d', '${itemMaxLength ?? 0}'),
    });
    await onChanged(parsed.entries);
  },
));
