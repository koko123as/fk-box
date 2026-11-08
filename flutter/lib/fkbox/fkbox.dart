// FK-box: FK-box's own features inside FlClash's UI.
//
// FlClash's channels stay as FlClash defines them. This channel
// (io.fkbox/bridge, answered by app/.../plugins/FkBoxPlugin.kt) carries what
// FlClash has no Core method for: the per-profile core choice, legacy Room
// profiles, split multi-config imports, links/files/shares sent to FK-box and
// the FK-box tools.
//
// Copyright (C) FK-box contributors. GPL-3.0, as FlClash.

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/painting.dart' show TextSpan;
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'core.dart';
import 'strings.dart';
import 'tools/taildrop.dart';

export 'core.dart';
export 'strings.dart';

/// The calls and events of FK-box's own channel.
abstract final class FkBox {
  static const channel = MethodChannel('io.fkbox/bridge');
  static const _events = EventChannel('io.fkbox/events');

  static Stream<Map<String, Object?>>? _stream;

  static bool get available => Platform.isAndroid;

  static Future<T?> call<T>(String method, [Map<String, Object?>? args]) {
    return channel.invokeMethod<T>(method, args);
  }

  static Future<Map<String, Object?>> map(
    String method, [
    Map<String, Object?>? args,
  ]) async {
    final value = await channel.invokeMethod<Object?>(method, args);
    return asMap(value);
  }

  static Future<List<Object?>> list(
    String method, [
    Map<String, Object?>? args,
  ]) async {
    final value = await channel.invokeMethod<Object?>(method, args);
    return value is List ? value : const [];
  }

  /// FK-box's state stream ({type, value} maps) while a page listens.
  static Stream<Map<String, Object?>> get events {
    return _stream ??= _events
        .receiveBroadcastStream()
        .map((event) => asMap(event))
        .asBroadcastStream();
  }

  /// The events of one [type], as their value.
  static Stream<Object?> eventsOf(String type) {
    return events
        .where((event) => event['type'] == type)
        .map((event) => event['value']);
  }

  static Map<String, Object?> asMap(Object? value) {
    if (value is Map) {
      return value.map((key, value) => MapEntry(key.toString(), value));
    }
    return const {};
  }

  static List<Map<String, Object?>> asMaps(Object? value) {
    if (value is! List) return const [];
    return [for (final item in value) asMap(item)];
  }
}

/// Runs once FlClash's app is initialized (bootstrap.dart): answers the
/// channel's calls into Dart and moves FK-box's former profiles over.
Future<void> startFkBox(ProviderContainer container) async {
  if (!FkBox.available) return;
  FkBox.channel.setMethodCallHandler((call) async {
    switch (call.method) {
      case 'splitProfiles':
        unawaited(_addSplitProfiles(container, FkBox.asMap(call.arguments)));
        return null;
      case 'intentAvailable':
        unawaited(_takeIntents(container));
        return null;
    }
    throw MissingPluginException(call.method);
  });
  await refreshCoreOverrides();
  await _migrateLegacyProfiles(container);
  unawaited(_takeIntents(container));
}

bool _takingIntents = false;

/// The intents FK-box's activity queued (BoxActivity.handleIntent): profile
/// links (`sing-box://import-remote-profile`, http(s)), opened configuration
/// files and the share sheet. FlClash's own `clash://install-config` links
/// stay with FlClash's link handler.
Future<void> _takeIntents(ProviderContainer container) async {
  if (_takingIntents) return;
  _takingIntents = true;
  try {
    while (true) {
      final value = await FkBox.call<String>('intent.take');
      if (value == null || value.isEmpty) break;
      try {
        await _handleIntent(container, value);
      } catch (error) {
        dialogs.showBanner('$error', level: BannerLevel.error);
      }
    }
  } catch (error) {
    commonPrint.log('FK-box intents: $error', logLevel: LogLevel.warning);
  } finally {
    _takingIntents = false;
  }
}

Future<bool> _confirmImport(String source) async {
  final ok = await dialogs.showMessage(
    title: fkStrings.importProfile,
    message: TextSpan(text: fkStrings.importProfileFrom(source)),
  );
  return ok == true;
}

bool _isProfileLink(String value) {
  final lower = value.toLowerCase();
  return value.isShareLink ||
      lower.startsWith('http://') ||
      lower.startsWith('https://') ||
      lower.startsWith('sing-box://');
}

/// A remote-profile link as FlClash's URL import takes it: sing-box's import
/// links carry the subscription URL and name.
Future<({String url, String? label})> _resolveLink(String link) async {
  if (!link.toLowerCase().startsWith('sing-box://')) {
    return (url: link, label: null);
  }
  final info = await FkBox.map('intent.link', {'value': link});
  final name = (info['name'] as String?) ?? '';
  return (
    url: (info['url'] as String?) ?? link,
    label: name.isEmpty ? null : name,
  );
}

Future<void> _saveContent(
  ProviderContainer container,
  String label,
  List<int> bytes,
) async {
  final core = container.read(coreHandlerProvider);
  final profile = await Profile.normal(label: label).saveFile(
    Uint8List.fromList(bytes),
    validate: (path, key) => core.validateProfile(path, key),
  );
  container.read(profilesActionProvider.notifier).putProfile(profile);
}

Future<void> _handleIntent(ProviderContainer container, String value) async {
  final action = container.read(profilesActionProvider.notifier);
  if (value.startsWith('bbox-share:')) {
    final token = value.substring('bbox-share:'.length);
    final text = await FkBox.call<String>('share.text', {'token': token});
    if (text == null) {
      // Shared files go to a Tailscale peer with Taildrop.
      final context = globalState.navigatorKey.currentContext;
      if (context == null || !context.mounted) {
        await FkBox.call<void>('share.discard', {'token': token});
        return;
      }
      await BaseNavigator.push(context, FkShareView(token: token));
      return;
    }
    await FkBox.call<void>('share.discard', {'token': token});
    final links = [
      for (final part in text.split(RegExp(r'\s+')))
        if (part.isNotEmpty && _isProfileLink(part)) part,
    ];
    if (links.isNotEmpty) {
      if (!await _confirmImport(links.first)) return;
      if (links.length == 1) {
        final link = await _resolveLink(links.first);
        await action.addProfileFromLink(link.url, label: link.label);
      } else {
        await action.addProfilesFromLinks([
          for (final link in links) (await _resolveLink(link)).url,
        ]);
      }
      return;
    }
    if (!await _confirmImport(fkStrings.importProfile)) return;
    await _saveContent(container, 'Shared', utf8.encode(text));
    return;
  }
  final uri = Uri.tryParse(value);
  final scheme = uri?.scheme.toLowerCase() ?? '';
  if (scheme == 'sing-box' || scheme == 'http' || scheme == 'https') {
    final link = await _resolveLink(value);
    if (!await _confirmImport(link.url)) return;
    await action.addProfileFromLink(link.url, label: link.label);
    return;
  }
  if (scheme == 'content' || scheme == 'file') {
    final read = await FkBox.map('intent.read', {'value': value});
    final name = (read['name'] as String?) ?? 'Profile';
    final content = read['content'];
    if (content is! List<int> || content.isEmpty) return;
    if (!await _confirmImport(name)) return;
    await _saveContent(container, name, content);
  }
}

/// A JSON array of independent configurations: the profile being added keeps
/// the first (core.dart's validateConfig in FkCore), each other one becomes a
/// profile of its own, named by its remarks, as FK-box's import did.
Future<void> _addSplitProfiles(
  ProviderContainer container,
  Map<String, Object?> split,
) async {
  final core = container.read(coreHandlerProvider);
  final action = container.read(profilesActionProvider.notifier);
  final first = FkBox.asMap(split['first']);
  final extras = FkBox.asMaps(split['extras']);
  final added = <String>[];
  for (final extra in extras) {
    final label = (extra['label'] as String?) ?? '';
    final content = (extra['content'] as String?) ?? '';
    if (content.isEmpty) continue;
    // A subscription update of the same array must not add them again.
    final profiles = container.read(profilesProvider);
    if (label.isNotEmpty && profiles.any((item) => item.label == label)) {
      continue;
    }
    try {
      final profile = await Profile.normal(label: label).saveFile(
        utf8.encode(content),
        validate: (path, key) => core.validateProfile(path, key),
      );
      action.putProfile(profile);
      added.add(label);
    } catch (error) {
      commonPrint.log(
        'FK-box split import of $label failed: $error',
        logLevel: LogLevel.warning,
      );
    }
  }
  unawaited(_renameFirst(container, first));
  if (added.isNotEmpty) {
    dialogs.showBanner(
      fkStrings.splitImported(added.length + 1),
      level: BannerLevel.success,
    );
  }
}

/// The profile that kept the first configuration takes its remarks once it
/// is in the list, unless it was named already.
Future<void> _renameFirst(
  ProviderContainer container,
  Map<String, Object?> first,
) async {
  final label = (first['label'] as String?) ?? '';
  final content = (first['content'] as String?) ?? '';
  if (label.isEmpty || content.isEmpty) return;
  for (var attempt = 0; attempt < 20; attempt++) {
    await Future<void>.delayed(const Duration(milliseconds: 500));
    final profiles = container.read(profilesProvider);
    if (profiles.any((item) => item.label == label)) return;
    for (final profile in profiles) {
      if (profile.type == ProfileType.custom) continue;
      final file = File(await appPath.getProfilePath(profile.id.toString()));
      if (!await file.exists()) continue;
      String text;
      try {
        text = await file.readAsString();
      } catch (_) {
        continue;
      }
      if (text != content) continue;
      final current = profile.label;
      if (current.isNotEmpty &&
          current != profile.defaultLabel &&
          !_looksLikeFileName(current)) {
        return;
      }
      container
          .read(profilesProvider.notifier)
          .put(profile.copyWith(label: label));
      return;
    }
  }
}

bool _looksLikeFileName(String label) {
  final lower = label.toLowerCase();
  return lower.endsWith('.json') ||
      lower.endsWith('.txt') ||
      lower.endsWith('.conf') ||
      lower.endsWith('.yaml') ||
      lower.endsWith('.yml');
}

/// FK-box's Room profiles (before the FlClash UI) become FlClash profiles on
/// the first launch, with their subscription, update interval and core.
Future<void> _migrateLegacyProfiles(ProviderContainer container) async {
  List<Object?> legacy;
  try {
    legacy = await FkBox.list('legacy.list');
  } catch (error) {
    commonPrint.log('FK-box legacy profiles: $error');
    return;
  }
  if (legacy.isEmpty) {
    unawaited(FkBox.call<void>('legacy.done').catchError((_) {}));
    return;
  }
  final core = container.read(coreHandlerProvider);
  final action = container.read(profilesActionProvider.notifier);
  var migrated = 0;
  var failed = 0;
  for (final item in FkBox.asMaps(legacy)) {
    final name = (item['name'] as String?) ?? '';
    final url = (item['url'] as String?) ?? '';
    final content = (item['content'] as String?) ?? '';
    if (content.isEmpty) continue;
    final existing = container.read(profilesProvider);
    if (existing.any(
      (profile) => profile.label == name && (url.isEmpty || profile.url == url),
    )) {
      continue;
    }
    final interval = (item['interval'] as num?)?.toInt() ?? 0;
    final upload = (item['upload'] as num?)?.toInt() ?? 0;
    final download = (item['download'] as num?)?.toInt() ?? 0;
    final total = (item['total'] as num?)?.toInt() ?? 0;
    final expire = (item['expire'] as num?)?.toInt() ?? 0;
    var profile = Profile.normal(label: name, url: url).copyWith(
      autoUpdate: url.isNotEmpty && item['autoUpdate'] == true,
      autoUpdateDuration: interval > 0
          ? Duration(minutes: interval)
          : defaultUpdateDuration,
      subscriptionInfo: url.isNotEmpty && (total > 0 || expire > 0)
          ? SubscriptionInfo(
              upload: upload,
              download: download,
              total: total,
              expire: expire,
            )
          : null,
    );
    try {
      profile = await profile.saveFile(
        utf8.encode(content),
        validate: (path, key) => core.validateProfile(path, key),
      );
      final declared = (item['core'] as String?) ?? FkCores.auto;
      if (declared != FkCores.auto) {
        await setProfileCore(profile.id, declared);
      }
      action.putProfile(profile);
      migrated++;
    } catch (error) {
      failed++;
      commonPrint.log(
        'FK-box legacy profile $name was not moved: $error',
        logLevel: LogLevel.warning,
      );
    }
  }
  if (failed == 0) {
    unawaited(FkBox.call<void>('legacy.done').catchError((_) {}));
  }
  if (migrated > 0) {
    dialogs.showBanner(
      fkStrings.legacyMigrated(migrated),
      level: BannerLevel.success,
    );
  }
}

@visibleForTesting
bool fkLooksLikeFileName(String label) => _looksLikeFileName(label);
