// FK-box: shared pieces of the FK-box tool pages, built from FlClash's own
// Scaffold, sections and list rows (prism_ui), so they look and move like
// FlClash's pages.
//
// Copyright (C) FK-box contributors. GPL-3.0, as FlClash.

import 'dart:async';
import 'dart:convert';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/widgets.dart';

import '../fkbox.dart';

/// A FlClash page body: sections in a padded list.
class FkPage extends StatelessWidget {
  const FkPage({
    super.key,
    required this.title,
    required this.children,
    this.iconActions = const [],
    this.menuItems = const [],
    this.isLoading = false,
  });

  final String title;
  final List<Widget> children;
  final List<IconButtonData> iconActions;
  final List<PopupMenuItem> menuItems;
  final bool isLoading;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      title: title,
      isLoading: isLoading,
      iconActions: iconActions,
      menuItems: menuItems,
      body: ListView(
        padding: const EdgeInsets.symmetric(horizontal: 16).copyWith(
          top: context.sectionsTopPadding,
          bottom: 16 + context.bottomInset,
        ),
        children: children,
      ),
    );
  }
}

/// A page state that follows some of FK-box's event types and can call the
/// bridge with FlClash's banners for errors.
mixin FkEvents<T extends StatefulWidget> on State<T> {
  final List<StreamSubscription<Object?>> _subscriptions = [];

  void follow(String type, void Function(Object? value) onValue) {
    _subscriptions.add(
      FkBox.eventsOf(type).listen((value) {
        if (mounted) onValue(value);
      }, onError: (_) {}),
    );
  }

  Future<R?> run<R>(Future<R?> Function() task) async {
    try {
      return await task();
    } catch (error) {
      dialogs.showBanner(fkErrorText(error), level: BannerLevel.error);
      return null;
    }
  }

  @override
  void dispose() {
    for (final subscription in _subscriptions) {
      unawaited(subscription.cancel());
    }
    super.dispose();
  }
}

String fkErrorText(Object error) {
  final text = error.toString();
  final match = RegExp(r'PlatformException\([^,]*, (.*?), ').firstMatch(text);
  return match?.group(1) ?? text;
}

String fkBytes(Object? value) {
  final bytes = (value as num?)?.toInt() ?? 0;
  return bytes.traffic.show;
}

String fkDate(Object? millis) {
  final value = (millis as num?)?.toInt();
  if (value == null || value <= 0) return '';
  final date = DateTime.fromMillisecondsSinceEpoch(value);
  String two(int n) => n.toString().padLeft(2, '0');
  return '${date.year}-${two(date.month)}-${two(date.day)} '
      '${two(date.hour)}:${two(date.minute)}';
}

String fkPrettyJson(Object? value) {
  try {
    return const JsonEncoder.withIndent('  ').convert(value);
  } catch (_) {
    return '$value';
  }
}

/// A plain text page, as FlClash's preview pages show text.
class FkTextView extends StatelessWidget {
  const FkTextView({super.key, required this.title, required this.text});

  final String title;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      title: title,
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(
          16,
        ).copyWith(bottom: 16 + context.bottomInset),
        child: SelectableText(
          text,
          style: AppTextStyle.footnote
              .of(context)
              .copyWith(fontFamily: 'monospace'),
        ),
      ),
    );
  }
}

/// The note shown when a tool needs the service or has nothing to show.
Widget fkNote(BuildContext context, String text) {
  return Padding(
    padding: const EdgeInsets.symmetric(vertical: 24),
    child: Center(
      child: Text(
        text,
        textAlign: TextAlign.center,
        style: AppTextStyle.footnote.of(context).toLighter,
      ),
    ),
  );
}

Future<String?> fkInput(
  String title, {
  String value = '',
  String? label,
  bool obscure = false,
}) {
  return dialogs.showCommonDialog<String>(
    child: InputDialog(
      title: title,
      value: value,
      labelText: label,
      obscureText: obscure,
    ),
  );
}

/// A short list of choices as a FlClash dialog; pops the chosen key.
class FkChoiceDialog extends StatelessWidget {
  const FkChoiceDialog({super.key, required this.title, required this.choices});

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

Future<String?> fkChoose(String title, Map<String, String> choices) {
  return dialogs.showCommonDialog<String>(
    child: FkChoiceDialog(title: title, choices: choices),
  );
}

/// Key/value rows (tunnel info, details) as one FlClash section.
Widget fkDetails(String? title, Map<String, Object?> values) {
  final items = <Widget>[
    for (final entry in values.entries)
      if (entry.value != null && '${entry.value}'.isNotEmpty)
        ListItem(
          title: Text(entry.key),
          subtitle: Text(
            entry.value is List
                ? (entry.value as List).join('\n')
                : '${entry.value}',
          ),
        ),
  ];
  return generateSection(title: title, items: items);
}
