// FK-box: which of libbox's three cores (sing-box, Xray, mihomo) runs a
// FlClash profile. The core is detected from the content (Clash YAML →
// mihomo, Xray JSON → Xray, other JSON → sing-box); a JSON profile can be
// set to sing-box or Xray by hand (FkBoxPlugin.effectiveCore). The badge and
// the menu item are added to FlClash's profile card (views/profiles).
//
// Copyright (C) FK-box contributors. GPL-3.0, as FlClash.

import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'fkbox.dart';

abstract final class FkCores {
  static const auto = 'auto';
  static const singBox = 'sing-box';
  static const xray = 'xray';
  static const mihomo = 'mihomo';

  static String label(String core) => switch (core) {
    singBox => 'sing-box',
    xray => 'Xray',
    mihomo => 'mihomo',
    _ => fkStrings.coreAuto,
  };
}

/// The cores chosen by hand, by FlClash profile id.
final fkCoreOverrides = ValueNotifier<Map<String, String>>(const {});

Future<void> refreshCoreOverrides() async {
  try {
    final value = await FkBox.map('profileCore.overrides');
    fkCoreOverrides.value = {
      for (final entry in value.entries)
        if (entry.value is String) entry.key: entry.value as String,
    };
  } catch (_) {}
}

Future<void> setProfileCore(int profileId, String core) async {
  final value = await FkBox.map('profileCore.set', {
    'id': profileId.toString(),
    'core': core,
  });
  fkCoreOverrides.value = {
    for (final entry in value.entries)
      if (entry.value is String) entry.key: entry.value as String,
  };
}

typedef FkProfileCore = ({
  String detected,
  String override,
  String effective,
  List<String> choices,
});

final Map<String, FkProfileCore> _coreCache = {};

Future<FkProfileCore?> profileCoreOf(Profile profile) async {
  if (!FkBox.available || profile.type == ProfileType.custom) return null;
  final path = await appPath.getProfilePath(profile.id.toString());
  final key =
      '${profile.id}:${profile.lastUpdateDate?.millisecondsSinceEpoch}:'
      '${fkCoreOverrides.value[profile.id.toString()]}';
  if (_coreCache[key] case final cached?) return cached;
  try {
    final value = await FkBox.map('profileCore.get', {'path': path});
    final result = (
      detected: (value['detected'] as String?) ?? FkCores.mihomo,
      override: (value['override'] as String?) ?? FkCores.auto,
      effective: (value['effective'] as String?) ?? FkCores.mihomo,
      choices: [
        for (final choice in (value['choices'] as List?) ?? const [])
          choice.toString(),
      ],
    );
    _coreCache[key] = result;
    return result;
  } catch (_) {
    return null;
  }
}

/// The core a profile runs with, as a chip beside its update time.
class FkCoreBadge extends StatefulWidget {
  const FkCoreBadge({super.key, required this.profile});

  final Profile profile;

  @override
  State<FkCoreBadge> createState() => _FkCoreBadgeState();
}

class _FkCoreBadgeState extends State<FkCoreBadge> {
  FkProfileCore? _core;

  @override
  void initState() {
    super.initState();
    fkCoreOverrides.addListener(_load);
    _load();
  }

  @override
  void didUpdateWidget(covariant FkCoreBadge oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.profile.lastUpdateDate != widget.profile.lastUpdateDate ||
        oldWidget.profile.id != widget.profile.id) {
      _load();
    }
  }

  @override
  void dispose() {
    fkCoreOverrides.removeListener(_load);
    super.dispose();
  }

  Future<void> _load() async {
    final core = await profileCoreOf(widget.profile);
    if (!mounted) return;
    setState(() => _core = core);
  }

  @override
  Widget build(BuildContext context) {
    final core = _core;
    if (core == null) return const SizedBox.shrink();
    final colorScheme = context.colorScheme;
    final color = switch (core.effective) {
      FkCores.singBox => colorScheme.primary,
      FkCores.xray => colorScheme.tertiary,
      _ => colorScheme.secondary,
    };
    return TonalChip(
      label: FkCores.label(core.effective),
      color: color.opacity15,
      foregroundColor: color,
    );
  }
}

/// The profile menu's "Core" entry: automatic or a core the content allows.
PopupMenuItem? fkProfileCoreMenuItem(
  BuildContext context,
  WidgetRef ref,
  Profile profile,
) {
  if (!FkBox.available || profile.type == ProfileType.custom) return null;
  final overrideCore =
      fkCoreOverrides.value[profile.id.toString()] ?? FkCores.auto;
  return PopupMenuItem(
    glyph: AppGlyphs.cpu,
    label: fkStrings.core,
    subtitle: FkCores.label(overrideCore),
    subItems: [
      for (final core in const [FkCores.auto, FkCores.singBox, FkCores.xray])
        PopupMenuItem(
          label: FkCores.label(core),
          checked: core == overrideCore,
          onPressed: () => unawaited(_choose(ref, profile, core)),
        ),
    ],
  );
}

Future<void> _choose(WidgetRef ref, Profile profile, String core) async {
  final info = await profileCoreOf(profile);
  if (info != null && core != FkCores.auto && !info.choices.contains(core)) {
    dialogs.showBanner(
      fkStrings.coreNotAllowed(FkCores.label(core)),
      level: BannerLevel.warning,
    );
    return;
  }
  try {
    await setProfileCore(profile.id, core);
  } catch (error) {
    dialogs.showBanner('$error', level: BannerLevel.error);
    return;
  }
  // The running profile is set up again so the chosen core takes over.
  if (ref.read(currentProfileIdProvider) == profile.id) {
    unawaited(ref.read(setupActionProvider.notifier).applyProfile());
  }
}
