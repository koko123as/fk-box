import 'dart:async';
import 'package:fk_box_ui/main.dart';
import 'package:fk_box_ui/widgets.dart';
import 'package:fk_box_ui/components/shape_badge.dart';
import 'package:flutter/services.dart';
import 'package:prism_ui/shapes/shapes.dart';
import 'package:flutter_test/flutter_test.dart';

/// Compose's minimal style: corners at half scale (4 dp floor), hairline
/// outlines in place of shadows, no decorative status cookie, static presses.
void main() {
  testWidgets('the minimal style scales corners and swaps shadows for hairlines', (tester) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    const channel = MethodChannel('test.minimalStyle');
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(channel, (call) async => call.method == 'ready' ? {
      'version': 'test', 'coreVersion': 'test', 'lan': {'addresses': <String>[]},
      'dashboard': {'status': 'Stopped', 'modes': []}, 'profiles': [],
      'preferences': {'theme': 'light', 'appearanceStyle': 'standard'},
    } : null);
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
    final events = StreamController<dynamic>.broadcast();
    final box = BoxController(channel: channel, events: events.stream);
    await tester.pumpWidget(BoxApp(controller: box));
    await tester.pumpAndSettle();

    ShapeDecoration panel() => (tester.widget<Container>(find.descendant(of: find.byType(BoxPanel).first,
      matching: find.byType(Container)).first).decoration! as ShapeDecoration);
    expect(PrismStyle.cornerScale, 1);
    expect(panel().shadows, isNotEmpty);
    expect((panel().shape as RoundedSuperellipseBorder).side, BorderSide.none);
    expect(AppShape.all(34).borderRadius, BorderRadius.circular(34));
    expect(find.byWidgetPredicate((widget) => widget is ShapeBadge && widget.shape == MaterialShapes.cookie12Sided), findsOneWidget);

    events.add({'type': 'preferences', 'value': {'theme': 'light', 'appearanceStyle': 'minimal'}});
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(PrismStyle.cornerScale, .5);
    expect(PrismStyle.inkSpread, isFalse);
    expect(PrismStyle.elasticPress, isFalse);
    expect(AppShape.all(34).borderRadius, BorderRadius.circular(17));
    expect(AppShape.all(6).borderRadius, BorderRadius.circular(4), reason: 'small radii keep a 4 dp floor');
    expect(AppShape.all(3).borderRadius, BorderRadius.circular(3));
    expect(panel().shadows ?? const [], isEmpty);
    expect((panel().shape as RoundedSuperellipseBorder).side.style, BorderStyle.solid);
    expect((panel().shape as RoundedSuperellipseBorder).borderRadius, BorderRadius.circular(17));
    expect(find.byWidgetPredicate((widget) => widget is ShapeBadge && widget.shape == MaterialShapes.cookie12Sided), findsNothing, reason: 'the status cookie is decoration');

    events.add({'type': 'preferences', 'value': {'theme': 'light', 'appearanceStyle': 'standard'}});
    await tester.pumpAndSettle();
    expect(PrismStyle.cornerScale, 1);
    expect(PrismStyle.shadows, isTrue);
    expect(panel().shadows, isNotEmpty);
    await tester.pumpWidget(const SizedBox());
    await events.close();
  });
}
