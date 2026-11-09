import 'dart:async';
import 'dart:math' as math;
import '../widgets.dart';

// FlClash v0.9.2 widgets/subscription_info_view.dart, widgets/chip_title.dart's
// ExpireChip and dashboard/widgets/profiles.dart's _UsageMeter, fed by the
// provider's subscription-userinfo header saved with each remote profile
// (Compose SubscriptionInfoView.kt).

/// The traffic/expiry a remote profile's provider reported.
class SubscriptionInfo {
  const SubscriptionInfo({this.upload = 0, this.download = 0, this.total = 0, this.expire = 0});
  final int upload, download, total, expire;
  int get used => upload + download;
  int get remaining => math.max(0, total - used);
  /// Share of the quota used, or null when the provider sent no total.
  double? get usage => total > 0 ? (used / total).clamp(0.0, 1.0) : null;
  bool get isEmpty => total <= 0 && expire <= 0 && upload <= 0 && download <= 0;
}

int _count(Object? value) => value is num ? value.toInt() : 0;

/// The subscription info of a remote profile, or null when its provider sent none.
SubscriptionInfo? subscriptionInfoOf(Json? profile) {
  if (profile == null || profile['remote'] != true) return null;
  final info = SubscriptionInfo(upload: _count(profile['upload']), download: _count(profile['download']),
    total: _count(profile['total']), expire: _count(profile['expire']));
  return info.isEmpty ? null : info;
}

/// profile-web-page-url of a remote profile, http/https only (checked natively).
String? subscriptionWebPageOf(Json profile) {
  final url = profile['webPageUrl'];
  return profile['remote'] == true && url is String && url.isNotEmpty ? url : null;
}

/// Days within which an expiry or a 90% used quota reads as a warning.
const _soonDays = 7;
const _nearlyUsedShare = 0.9;

DateTime? _expireTime(SubscriptionInfo info) =>
  info.expire > 0 ? DateTime.fromMillisecondsSinceEpoch(math.min(info.expire, 8640000000000) * 1000) : null;

/// widgets.dart's DateTime.show: the local calendar date.
String? subscriptionExpireDate(SubscriptionInfo info) {
  final time = _expireTime(info);
  if (time == null) return null;
  String two(int value) => value.toString().padLeft(2, '0');
  return '${time.year}-${two(time.month)}-${two(time.day)}';
}

int _daysBetween(DateTime from, DateTime to) => to.difference(from).inMilliseconds ~/ Duration.millisecondsPerDay;

/// Error once the quota is used up or the subscription has expired, warning when
/// 90% is used or it expires within a week, otherwise the primary color.
Color subscriptionColor(SubscriptionInfo info, ColorScheme scheme, DateTime now) {
  final usage = info.usage;
  final expire = _expireTime(info);
  if ((usage != null && usage >= 1) || (expire != null && !expire.isAfter(now))) return scheme.error;
  if ((usage != null && usage >= _nearlyUsedShare) || (expire != null && _daysBetween(now, expire) < _soonDays)) return scheme.warning;
  return scheme.primary;
}

String _daysText(BuildContext context, SubscriptionInfo info, DateTime now) {
  final expire = _expireTime(info);
  if (expire == null) return context.tr('No expiry');
  if (!expire.isAfter(now)) return context.tr('Expired');
  return context.tr('%d days left').replaceAll('%d', math.max(0, _daysBetween(now, expire)).toString());
}

/// Re-reads the clock each minute so countdowns stay current on a visible page.
class _Now extends StatefulWidget {
  const _Now({required this.builder});
  final Widget Function(BuildContext context, DateTime now) builder;
  @override State<_Now> createState() => _NowState();
}

class _NowState extends State<_Now> {
  late final Timer _timer = Timer.periodic(const Duration(minutes: 1), (_) { if (mounted) setState(() {}); });
  @override void initState() { super.initState(); _timer; }
  @override void dispose() { _timer.cancel(); super.dispose(); }
  @override Widget build(BuildContext context) => widget.builder(context, DateTime.now());
}

/// A tinted pill (the expiry and core chips).
class TintedChip extends StatelessWidget {
  const TintedChip(this.label, {super.key, required this.color});
  final String label;
  final Color color;
  @override Widget build(BuildContext context) => DecoratedBox(
    decoration: ShapeDecoration(color: color.withValues(alpha: 0.15), shape: AppShape.full),
    child: Padding(padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      child: Text(label, maxLines: 1, style: AppTextStyle.caption1.of(context, emphasized: true).copyWith(color: color))),
  );
}

/// ExpireChip: the expiry date on a tinted pill, error once past and warning within a week.
class SubscriptionExpireChip extends StatelessWidget {
  const SubscriptionExpireChip(this.info, {super.key});
  final SubscriptionInfo info;
  @override Widget build(BuildContext context) {
    final date = subscriptionExpireDate(info);
    if (date == null) return const SizedBox.shrink();
    return _Now(builder: (context, now) {
      final scheme = context.colorScheme;
      final expire = _expireTime(info)!;
      final color = !expire.isAfter(now) ? scheme.error : _daysBetween(now, expire) < _soonDays ? scheme.warning : scheme.primary;
      return TintedChip(date, color: color);
    });
  }
}

/// ChipTitle: a one-line title followed by the expiry chip, which gives way
/// before it would squeeze the title below half the row.
class SubscriptionChipTitle extends StatelessWidget {
  const SubscriptionChipTitle(this.title, {super.key, required this.info, required this.style, this.strutStyle});
  final String title;
  final SubscriptionInfo? info;
  final TextStyle style;
  final StrutStyle? strutStyle;
  @override Widget build(BuildContext context) {
    final text = Text(title, style: style, strutStyle: strutStyle, maxLines: 1, overflow: TextOverflow.ellipsis, textWidthBasis: TextWidthBasis.longestLine);
    final date = info == null ? null : subscriptionExpireDate(info!);
    if (date == null) return text;
    final scale = MediaQuery.textScalerOf(context);
    final base = DefaultTextStyle.of(context).style;
    double measure(String value, TextStyle textStyle) => (TextPainter(
      text: TextSpan(text: value, style: base.merge(textStyle)), maxLines: 1,
      textDirection: Directionality.of(context), textScaler: scale)..layout()).width;
    return LayoutBuilder(builder: (context, constraints) {
      final width = constraints.maxWidth;
      final titleWidth = measure(title, style);
      final chipWidth = measure(date, AppTextStyle.caption1.of(context, emphasized: true)) + 16 + 8;
      final showChip = width - chipWidth >= math.min(titleWidth, width / 2);
      return Row(spacing: 8, children: [
        Flexible(child: text),
        if (showChip) SubscriptionExpireChip(info!),
      ]);
    });
  }
}

/// The 4dp rounded quota bar of SubscriptionInfoView's LinearProgressIndicator.
class SubscriptionProgressBar extends StatelessWidget {
  const SubscriptionProgressBar(this.share, {super.key, required this.color});
  final double share;
  final Color color;
  @override Widget build(BuildContext context) => SizedBox(height: 4, width: double.infinity,
    child: CustomPaint(painter: _BarPainter(share, color)));
}

class _BarPainter extends CustomPainter {
  _BarPainter(this.share, this.color);
  final double share;
  final Color color;
  @override void paint(Canvas canvas, Size size) {
    final stroke = size.height, mid = size.height / 2, start = stroke / 2, end = size.width - stroke / 2;
    final paint = Paint()..strokeWidth = stroke..strokeCap = StrokeCap.round;
    canvas.drawLine(Offset(start, mid), Offset(end, mid), paint..color = color.withValues(alpha: 0.15));
    if (share > 0) canvas.drawLine(Offset(start, mid), Offset(start + (end - start) * share.clamp(0, 1), mid), paint..color = color);
  }
  @override bool shouldRepaint(_BarPainter old) => old.share != share || old.color != color;
}

/// Profile card subtitle: "used / total · remaining" when the provider reported a quota.
String? subscriptionTrafficLine(BuildContext context, SubscriptionInfo info) {
  if (info.total <= 0) return null;
  return '${bytes(info.used)} / ${bytes(info.total)} · ${context.tr('Remaining')} ${bytes(info.remaining)}';
}

/// SubscriptionInfoView: used / total with the expiry on one line and the quota bar beneath.
class SubscriptionInfoView extends StatelessWidget {
  const SubscriptionInfoView(this.info, {super.key});
  final SubscriptionInfo info;
  @override Widget build(BuildContext context) => _Now(builder: (context, now) {
    final scheme = context.colorScheme;
    final usage = info.usage;
    final color = subscriptionColor(info, scheme, now);
    final valueStyle = AppTextStyle.subheadline.of(context, emphasized: true).copyWith(color: scheme.onSurfaceVariant);
    final metaStyle = AppTextStyle.footnote.of(context).copyWith(color: scheme.onSurface.withValues(alpha: 0.7));
    final date = subscriptionExpireDate(info);
    final expire = date == null ? context.tr('No expiry') : '${context.tr('Expires')} $date · ${_daysText(context, info, now)}';
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: [
      Row(children: [
        Expanded(child: Text(info.total > 0 ? '${bytes(info.used)} / ${bytes(info.total)}' : context.tr('Traffic not reported'),
          maxLines: 1, overflow: TextOverflow.ellipsis, style: valueStyle)),
        const SizedBox(width: 12),
        Text(expire, maxLines: 1, overflow: TextOverflow.ellipsis,
          style: color == scheme.primary ? metaStyle : metaStyle.copyWith(color: color)),
      ]),
      if (usage != null) ...[const SizedBox(height: 4), SubscriptionProgressBar(usage, color: color)],
    ]);
  });
}

/// SubscriptionInfoDetailView rows: used, total, remaining, expiry, and the provider's website.
Widget subscriptionInfoSection(BuildContext context, SubscriptionInfo info, String? webPage, ValueChanged<String> openWebPage) {
  return _Now(builder: (context, now) {
    final date = subscriptionExpireDate(info);
    // subscription_info_view.dart wraps the detail in a SelectionArea.
    return SelectionArea(child: generateSection(title: context.tr('Subscription info'), items: [
      ListItem(title: SubscriptionInfoView(info)),
      if (info.total > 0) ...[
        ListItem(title: Text(context.tr('Used traffic')), trailing: Text(bytes(info.used))),
        ListItem(title: Text(context.tr('Total traffic')), trailing: Text(bytes(info.total))),
        ListItem(title: Text(context.tr('Remaining traffic')), trailing: Text(bytes(info.remaining))),
      ],
      ListItem(title: Text(context.tr('Expire time')), subtitle: date == null ? null : Text(_daysText(context, info, now)),
        trailing: Text(date ?? context.tr('No expiry'))),
      if (webPage != null) ListItem(title: Text(context.tr('Open website')),
        subtitle: Text(webPage, maxLines: 1, overflow: TextOverflow.ellipsis), onTap: () => openWebPage(webPage)),
    ]));
  });
}

/// profiles.dart's dashboard _UsageMeter: percent used, used / total, and the wavy quota bar.
class SubscriptionUsageMeter extends StatelessWidget {
  const SubscriptionUsageMeter(this.info, {super.key});
  final SubscriptionInfo info;
  @override Widget build(BuildContext context) {
    final share = info.usage;
    if (share == null) return const SizedBox.shrink();
    return _Now(builder: (context, now) {
      final scheme = context.colorScheme;
      final color = subscriptionColor(info, scheme, now);
      final unit = AppTextStyle.footnote.of(context).copyWith(color: scheme.onSurfaceVariant);
      return Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: [
        Row(crossAxisAlignment: CrossAxisAlignment.end, spacing: 12, children: [
          Row(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.end, children: [
            Text('${(share * 100).toInt().clamp(0, 100)}%', maxLines: 1,
              style: AppTextStyle.title3.of(context, emphasized: true).toTabular.copyWith(letterSpacing: 0, color: scheme.onSurface)),
            Padding(padding: const EdgeInsets.only(bottom: 2), child: Text(' ${context.tr('used')}', maxLines: 1, style: unit)),
          ]),
          Expanded(child: Padding(padding: const EdgeInsets.only(bottom: 2), child: Text('${bytes(info.used)} / ${bytes(info.total)}',
            maxLines: 1, overflow: TextOverflow.ellipsis, textAlign: TextAlign.end, style: unit.toTabular))),
        ]),
        const SizedBox(height: 8),
        SizedBox(height: 12, width: double.infinity,
          child: CustomPaint(painter: _WavyPainter(share, color, scheme.onSurface.withValues(alpha: 0.12)))),
      ]);
    });
  }
}

/// _WavyProgressPainter: a wave up to the value, a gap, then a flat track ending in a stop dot.
class _WavyPainter extends CustomPainter {
  _WavyPainter(this.value, this.active, this.track);
  final double value;
  final Color active, track;
  @override void paint(Canvas canvas, Size size) {
    const stroke = 4.0, amplitude = 3.0, wavelength = 12.0, gap = 6.0, stop = 4.0;
    final mid = size.height / 2, start = stroke / 2, end = size.width - stroke / 2;
    final share = value.clamp(0.0, 1.0);
    final split = start + (end - start) * share;
    final paint = Paint()..strokeWidth = stroke..strokeCap = StrokeCap.round..style = PaintingStyle.stroke;
    if (share > 0) {
      final wave = Path()..moveTo(start, mid);
      for (var x = start; x <= split; x += 1) {
        wave.lineTo(x, mid + amplitude * math.sin((x - start) / wavelength * 2 * math.pi));
      }
      canvas.drawPath(wave, paint..color = active);
    }
    final trackStart = share > 0 ? split + gap + stroke : start;
    if (trackStart < end - stop) canvas.drawLine(Offset(trackStart, mid), Offset(end - stop - gap, mid), paint..color = track);
    if (share < 1) canvas.drawCircle(Offset(end - stop / 2, mid), stop / 2, Paint()..color = active);
  }
  @override bool shouldRepaint(_WavyPainter old) => old.value != value || old.active != active || old.track != track;
}

/// The dashboard's end label: days left (warning within a week) when the provider reported an expiry.
(String, Color?)? subscriptionDaysLabel(BuildContext context, SubscriptionInfo? info) {
  if (info == null) return null;
  final expire = _expireTime(info);
  if (expire == null) return null;
  final now = DateTime.now();
  final scheme = context.colorScheme;
  final color = !expire.isAfter(now) ? scheme.error : _daysBetween(now, expire) < _soonDays ? scheme.warning : null;
  return (_daysText(context, info, now), color);
}
