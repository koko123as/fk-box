import 'dart:async';
import '../widgets.dart';
import '../pages/profiles.dart' show ProfileCoreChip;
import 'subscription_info.dart';

// FlClash v0.9.2 profile rows, using only metadata supplied by Room.
/// The quota bar's gap and height, added to the row height while any profile shows one.
const profileMeterGap = 6.0;
const profileMeterHeight = 4.0;

double profileCardHeight(BuildContext context, {bool withMeter = false}) {
  final measure = MeasureScope.of(context);
  return measure.textHeight(AppTextStyle.headline) + 4 +
      measure.textHeight(AppTextStyle.footnote) + 2 * listRowVerticalPadding +
      (withMeter ? profileMeterGap + profileMeterHeight : 0);
}

/// Whether any profile in the list shows a quota bar.
bool profilesShowMeter(Iterable<Json> profiles) => profiles.any((profile) => subscriptionInfoOf(profile)?.usage != null);

class ProfileCard extends StatelessWidget {
  const ProfileCard({super.key, required this.profile, required this.selected,
    required this.updating, required this.onPressed, required this.menuItems, this.withMeter = false});
  final Json profile;
  final bool selected, updating, withMeter;
  final VoidCallback? onPressed;
  final List<PopupMenuItem> menuItems;

  @override Widget build(BuildContext context) {
    final titleStyle = AppTextStyle.headline.of(context);
    final info = subscriptionInfoOf(profile);
    final usage = info?.usage;
    return ContextMenuRegion(
      menuItems: updating ? null : menuItems,
      child: Card(radius: AppCorner.xl, isSelected: selected, enterActionsOnRight: true,
        onPressed: onPressed,
        child: ListItem(
          minTileHeight: profileCardHeight(context, withMeter: withMeter), minVerticalPadding: listRowVerticalPadding,
          // FlClash's url-profile row: expiry chip after the title, used / total as the subtitle.
          title: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
            SubscriptionChipTitle(profile['name'].toString(), info: info, style: titleStyle,
              strutStyle: StrutStyle.fromTextStyle(DefaultTextStyle.of(context).style.merge(titleStyle), forceStrutHeight: true)),
            const SizedBox(height: 4),
            // fk-box: the core the profile runs on, as a chip before the subtitle.
            Row(spacing: 6, children: [
              ProfileCoreChip(profile),
              Flexible(child: Text((info == null ? null : subscriptionTrafficLine(context, info)) ?? context.tr(profile['remote'] == true ? 'Remote' : 'Local'),
                style: AppTextStyle.footnote.of(context).toLighter, maxLines: 1, overflow: TextOverflow.ellipsis)),
            ]),
            if (info != null && usage != null) ...[
              const SizedBox(height: profileMeterGap),
              SubscriptionProgressBar(usage, color: subscriptionColor(info, context.colorScheme, DateTime.now())),
            ],
          ]),
          trailing: Row(mainAxisSize: MainAxisSize.min, spacing: 8, children: [
            AnimatedSwitcher(
              duration: context.motionDuration(commonDuration),
              layoutBuilder: (currentChild, previousChildren) => Stack(
                alignment: AlignmentDirectional.centerEnd, children: [...previousChildren, ?currentChild]),
              child: updating ? const ActivityIndicator(key: ValueKey('updating'), size: 24)
                : ProfileUpdateTime(key: const ValueKey('lastUpdate'), milliseconds: (profile['updatedAt'] as num?)?.toInt()),
            ),
            if (selected) const SelectionBadge(size: 24),
          ]),
        ),
      ),
    );
  }
}

/// Uses the reference's 365-day years and 30-day months for compact ages.
String profileUpdateLabel(int? milliseconds, DateTime now, {required bool chinese}) {
  if (milliseconds == null || milliseconds <= 0) return chinese ? '未知' : 'Unknown';
  final elapsed = now.difference(DateTime.fromMillisecondsSinceEpoch(milliseconds));
  for (final (count, en, zh) in [
    (elapsed.inDays ~/ 365, 'y', '年'), (elapsed.inDays ~/ 30, 'mo', '个月'),
    (elapsed.inDays, 'd', '天'), (elapsed.inHours, 'h', '小时'), (elapsed.inMinutes, 'm', '分钟'),
  ]) {
    if (count > 0) return chinese ? '$count ${zh}前' : '$count$en ago';
  }
  return chinese ? '刚刚' : 'Just now';
}

class ProfileUpdateTime extends StatefulWidget {
  const ProfileUpdateTime({super.key, required this.milliseconds, this.style, this.showLabel = false});
  final int? milliseconds;
  final TextStyle? style;
  final bool showLabel;
  @override State<ProfileUpdateTime> createState() => _ProfileUpdateTimeState();
}

class _ProfileUpdateTimeState extends State<ProfileUpdateTime> with WidgetsBindingObserver {
  Timer? _timer;
  bool _active = false;
  @override void initState() { super.initState(); WidgetsBinding.instance.addObserver(this); }
  @override void didChangeDependencies() {
    super.didChangeDependencies();
    _active = PageActivityScope.isActiveOf(context) && TickerMode.of(context);
    _schedule();
  }
  void _schedule() {
    _timer?.cancel();
    final lifecycle = WidgetsBinding.instance.lifecycleState;
    if (_active && (lifecycle == null || lifecycle == AppLifecycleState.resumed)) {
      _timer = Timer.periodic(const Duration(minutes: 1), (_) => setState(() {}));
    }
  }
  @override void didChangeAppLifecycleState(AppLifecycleState state) {
    _schedule();
    if (state == AppLifecycleState.resumed && _active) setState(() {});
  }
  @override void dispose() {
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }
  @override Widget build(BuildContext context) {
    final age = profileUpdateLabel(widget.milliseconds, DateTime.now(),
      chinese: Localizations.localeOf(context).languageCode == 'zh');
    final date = (widget.milliseconds ?? 0) <= 0 ? context.tr('Unknown') : localDate(widget.milliseconds);
    return Tooltip(
      message: '${context.tr('Last updated')}: $date',
      child: Text(widget.showLabel ? '${context.tr('Last updated')} $age' : age,
        maxLines: 1, overflow: TextOverflow.ellipsis,
        style: widget.style ?? AppTextStyle.footnote.of(context).toLighter),
    );
  }
}
