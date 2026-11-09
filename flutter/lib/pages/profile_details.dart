import 'package:flutter/services.dart';
import '../widgets.dart';
import '../components/profile_panel.dart';
import '../components/subscription_info.dart';
import 'profiles.dart';
import 'profile_editor.dart';
import 'configuration.dart';

class ProfileDetailsPage extends StatelessWidget {
  const ProfileDetailsPage({super.key, this.profile});
  final Json? profile;
  @override Widget build(BuildContext context) {
    final box = context.box;
    final p = box.remote ? null : profile == null ? box.selectedProfile : box.profiles.where((p) => p['id'] == profile!['id']).firstOrNull;
    if (!box.remote && profile != null && p == null) return const BoxPage(title: 'Edit', children: [EmptyState('Profile no longer exists')]);
    if (p == null) return BoxPage(title: 'Edit', children: [DetailRows({
      'Name': '—', 'Remote control': box.dashboard['remote'] ?? '—',
      'Proxy groups': box.groups.length,
    })]);
    return BoxPage(title: 'Edit', actions: [
      IconButtonData(glyph: AppGlyphs.edit, tooltip: context.tr('Edit'), onPressed: () => openForm(context, ProfileForm(initial: p))),
      if (p['remote'] == true) IconButtonData(glyph: AppGlyphs.sync, tooltip: context.tr('Update'), onPressed: () => box.act('profiles.update', {'id': p['id']})),
    ], children: [
      Padding(padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 12), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, spacing: 14, children: [
        EmojiText(p['name'].toString(), style: AppTextStyle.title2.of(context, emphasized: true)),
        ProfileStats(profile: p),
      ])),
      // profile_detail.dart's subscription section, shown only when the provider sent subscription-userinfo.
      if (subscriptionInfoOf(p) case final info?) subscriptionInfoSection(context, info, subscriptionWebPageOf(p), (url) => box.act('url.open', {'url': url}))
      else if (subscriptionWebPageOf(p) case final webPage?) generateSection(title: context.tr('Subscription info'), items: [
        ListItem(title: Text(context.tr('Open website')), subtitle: Text(webPage, maxLines: 1, overflow: TextOverflow.ellipsis),
          onTap: () => box.act('url.open', {'url': webPage})),
      ]),
      generateSection(items: [
        if (p['remote'] == true) ListItem(title: Text(context.tr('URL')), subtitle: SelectableText(p['url'].toString()),
          trailing: IconButton(icon: const GlyphIcon(AppGlyphs.copy), tooltip: context.tr('Copy'), onPressed: () => Clipboard.setData(ClipboardData(text: p['url'].toString())))),
        ListItem(title: Text(context.tr('Last updated')), subtitle: Text(
          (p['updatedAt'] as num? ?? 0) <= 0 ? context.tr('Unknown') : localDate(p['updatedAt']))),
        if (p['remote'] == true) ...[
          ListItem(title: Text(context.tr('Auto update')), trailing: Text(context.tr(p['autoUpdate'] == true ? 'Turn on' : 'Turn off'))),
          ListItem(title: Text(context.tr('Update interval (minutes)')), trailing: Text(p['interval'].toString())),
        ],
      ]),
      generateSection(items: [
        // The structured editor understands sing-box options only.
        if (runsSingBox(p)) ListItem.open(leading: const GlyphIcon(AppGlyphs.customize), title: Text(context.tr('Advanced configuration')), widget: ProfileConfigurationPage(profile: p)),
        ListItem.open(leading: const GlyphIcon(AppGlyphs.code), title: Text(context.tr('Open configuration')), widget: ProfileEditor(profile: p)),
      ]),
    ]);
  }
}
