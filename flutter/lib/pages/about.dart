import '../widgets.dart';

// Hero and link badges adapted from FlClash views/about.dart.
class AboutPage extends StatefulWidget {
  const AboutPage({super.key});
  @override State<AboutPage> createState() => _AboutPageState();
}

class _AboutPageState extends State<AboutPage> {
  bool _checking = false;

  Future<void> _check() async {
    if (_checking) return;
    final box = context.box;
    setState(() => _checking = true);
    try {
      final result = json(await box.call('updates.check'));
      if (!mounted) return;
      setState(() => _checking = false);
      final available = result['available'] == true, noMetadata = result['hasMetadata'] == false;
      final notes = result['notes'] is String ? result['notes'] as String : '';
      await showBoxDialog<void>(context, builder: (context) => AlertDialog(
        title: context.tr(available ? 'Update available' : 'Check for updates'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: Text(context.tr('Close'))),
          if (available || noMetadata) TextButton(onPressed: () {
            Navigator.pop(context);
            box.act('url.open', {'url': result['downloadUrl'] ?? result['url'] ?? 'https://github.com/koko123as/fk-box/releases'});
          }, child: Text(context.tr(result['downloadUrl'] == null ? 'Releases' : 'Download'))),
        ],
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, spacing: 12, children: [
          Text(available ? 'FK-box ${result['version']}' : context.tr(noMetadata ? 'No release metadata available' : 'You are up to date')),
          if (available && notes.isNotEmpty) SelectableText(notes),
        ]),
      ));
    } catch (error) { box.report(error); }
    finally { if (mounted) setState(() => _checking = false); }
  }

  Widget _link(Glyph glyph, String title, String label, String url) => ListItem(
    leading: _LinkBadge(glyph: glyph), title: Text(context.tr(title)),
    subtitle: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis),
    trailing: const GlyphIcon(AppGlyphs.openExternal),
    onTap: () => context.box.act('url.open', {'url': url}),
  );

  @override Widget build(BuildContext context) {
    final box = context.box, colors = context.colorScheme;
    String joined(List<String> parts) => parts.where((part) => part.isNotEmpty).join(' · ');
    return Scaffold(title: context.tr('About'), isLoading: _checking,
      body: ListView(padding: EdgeInsets.fromLTRB(16, context.contentTopPadding, 16, 32 + context.bottomInset), children: [
        Padding(padding: const EdgeInsets.fromLTRB(24, 16, 24, 0), child: Column(children: [
          DecoratedBox(decoration: ShapeDecoration(color: context.raisedSurfaceLevel.colorOf(colors), shape: AppShape.all(28)),
            child: Padding(padding: const EdgeInsets.all(14), child: Image.asset('assets/images/icon.png', width: 68, height: 68))),
          const SizedBox(height: 20),
          Text('FK-box', style: AppTextStyle.title2.of(context, emphasized: true)),
          const SizedBox(height: 10),
          Wrap(spacing: 8, runSpacing: 8, alignment: WrapAlignment.center, children: [
            _Pill(label: 'v${box.version}', color: colors.primary, foreground: colors.onPrimary),
            _Pill(label: 'GPL-3.0', color: context.raisedSurfaceLevel.colorOf(colors), foreground: colors.onSurfaceVariant),
          ]),
          const SizedBox(height: 16),
          ConstrainedBox(constraints: const BoxConstraints(maxWidth: 420), child: Text(context.tr('Flutter interface, Kotlin backend, three-core client'),
            textAlign: TextAlign.center, style: AppTextStyle.subheadline.of(context).copyWith(color: colors.onSurfaceVariant, height: 1.5))),
          const SizedBox(height: 20),
          TonalButton(size: TonalButtonSize.bar, glyph: AppGlyphs.sync, label: context.tr('Check for updates'), onPressed: _checking ? null : _check),
        ])),
        const SizedBox(height: 8),
        generateSection(title: context.tr('More'), items: [
          _link(AppGlyphs.code, 'Source code', 'github.com/koko123as/fk-box', 'https://github.com/koko123as/fk-box'),
          _link(AppGlyphs.code, 'Based on sing-box-for-android', 'SagerNet/sing-box-for-android · GPL-3.0', 'https://github.com/SagerNet/sing-box-for-android'),
          _link(AppGlyphs.code, 'Predecessor: B-box', 'giturass/B-box · GPL-3.0', 'https://github.com/giturass/B-box'),
          _link(AppGlyphs.cpu, 'sing-box', joined([box.coreVersion, 'SagerNet/sing-box']), 'https://github.com/SagerNet/sing-box'),
          _link(AppGlyphs.cpu, 'Xray', joined([box.xrayVersion, 'XTLS/Xray-core']), 'https://github.com/XTLS/Xray-core'),
          _link(AppGlyphs.cpu, 'mihomo', joined([box.mihomoVersion, 'MetaCubeX/mihomo']), 'https://github.com/MetaCubeX/mihomo'),
          _link(AppGlyphs.paintbrush, 'FlClash design', 'v0.9.3 · prism_ui · GPL-3.0', 'https://github.com/chen08209/FlClash/tree/be84736605527034689c95bf27828ff27d912c5e'),
          ListItem(leading: const _LinkBadge(glyph: AppGlyphs.info), title: Text(context.tr('Open-source licenses')),
            onTap: () => openPage(context, const LicensesPage())),
        ]),
      ]),
    );
  }
}

/// The projects FK-box ships or borrows from, with license, what was taken and source.
typedef _Credit = ({String name, String license, String taken, String url});

const _credits = <_Credit>[
  (name: 'FK-box', license: 'GPL-3.0', taken: 'This app', url: 'https://github.com/koko123as/fk-box'),
  (name: 'sing-box-for-android', license: 'GPL-3.0', taken: 'Base Android app and backend', url: 'https://github.com/SagerNet/sing-box-for-android'),
  (name: 'B-box', license: 'GPL-3.0', taken: 'Direct predecessor: core extensions, MITM, multi-core', url: 'https://github.com/giturass/B-box'),
  (name: 'sing-box', license: 'GPL-3.0', taken: 'Proxy core (libbox)', url: 'https://github.com/SagerNet/sing-box'),
  (name: 'Xray-core', license: 'MPL-2.0', taken: 'Second core', url: 'https://github.com/XTLS/Xray-core'),
  (name: 'mihomo', license: 'GPL-3.0', taken: 'Third core (Clash YAML)', url: 'https://github.com/MetaCubeX/mihomo'),
  (name: 'FlClash', license: 'GPL-3.0', taken: 'Interface (v0.9.3)', url: 'https://github.com/chen08209/FlClash'),
  (name: 'prism_ui', license: 'GPL-3.0', taken: 'UI component library', url: 'https://github.com/chen08209/prism_ui'),
  (name: 'Flutter', license: 'BSD-3-Clause', taken: 'UI framework', url: 'https://github.com/flutter/flutter'),
  (name: 'mobile_scanner', license: 'BSD-3-Clause', taken: 'QR code scanning', url: 'https://github.com/juliansteenbakker/mobile_scanner'),
  (name: 'shadowsocks-android', license: 'GPL-3.0', taken: 'DefaultNetworkListener', url: 'https://github.com/shadowsocks/shadowsocks-android'),
  (name: 'Android Open Source Project', license: 'Apache-2.0', taken: 'ParceledListSlice', url: 'https://github.com/androidx/androidx'),
  (name: 'libghostty', license: 'MIT', taken: 'Terminal', url: 'https://github.com/ghostty-org/ghostty'),
  (name: 'Shizuku API', license: 'MIT', taken: 'Shizuku access', url: 'https://github.com/RikkaApps/Shizuku-API'),
  (name: 'libsu', license: 'Apache-2.0', taken: 'Root shell and service', url: 'https://github.com/topjohnwu/libsu'),
  (name: 'smali / dexlib2', license: 'BSD-3-Clause', taken: 'Per-app proxy scanning', url: 'https://github.com/google/smali'),
  (name: 'Goja', license: 'MIT', taken: 'JavaScript for HTTP rewrite scripts', url: 'https://github.com/dop251/goja'),
  (name: 'JetBrains Mono', license: 'OFL-1.1', taken: 'Monospace font', url: 'https://github.com/JetBrains/JetBrainsMono'),
  (name: 'Twemoji', license: 'CC-BY-4.0', taken: 'Emoji font', url: 'https://github.com/mozilla/twemoji-colr'),
];

class LicensesPage extends StatelessWidget {
  const LicensesPage({super.key});
  @override Widget build(BuildContext context) {
    final box = context.box;
    return BoxPage(title: 'Open-source licenses', children: [
      Padding(padding: const EdgeInsets.all(16), child: Text('FK-box ${box.version}', style: AppTextStyle.headline.of(context))),
      generateSection(items: [
        for (final credit in _credits) ListItem(
          title: Text(credit.name),
          subtitle: Text('${credit.license} · ${context.tr(credit.taken)}'),
          trailing: const GlyphIcon(AppGlyphs.openExternal),
          onTap: () => box.act('url.open', {'url': credit.url}),
        ),
      ]),
    ]);
  }
}

class _Pill extends StatelessWidget {
  const _Pill({required this.label, required this.color, required this.foreground});
  final String label;
  final Color color, foreground;
  @override Widget build(BuildContext context) => DecoratedBox(decoration: ShapeDecoration(color: color, shape: AppShape.full),
    child: Padding(padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4), child: Text(label,
      style: AppTextStyle.caption1.of(context, emphasized: true).copyWith(color: foreground))));
}

class _LinkBadge extends StatelessWidget {
  const _LinkBadge({required this.glyph});
  final Glyph glyph;
  @override Widget build(BuildContext context) => DecoratedBox(
    decoration: ShapeDecoration(color: context.raisedSurfaceLevel.colorOf(context.colorScheme), shape: AppShape.md),
    child: SizedBox.square(dimension: 40, child: Center(child: GlyphIcon(glyph, size: 20, color: context.colorScheme.onSurfaceVariant))));
}
