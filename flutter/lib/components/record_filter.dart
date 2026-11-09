// FlClash v0.9.3 (be84736) lib/models/record_filter.dart and
// lib/widgets/record_filter.dart, GPL-3.0. A list page owns one
// RecordFilterController: values tapped in its rows (RecordFilterScope) become
// chips under the scaffold bar, offered facets fill the Filter menu (or the
// pinned strip on wide layouts), and a grouper folds the list under headers.
import 'package:collection/collection.dart';
import 'package:flutter/foundation.dart';
import '../widgets.dart';
import 'record.dart';

/// enum.dart's RecordFacetKind, the facets fk-box's records carry.
enum RecordFacetKind {
  network('Network'),
  rule('Rule'),
  mitm('MITM'),
  app('App'),
  proxy('Proxy'),
  level('Level');

  const RecordFacetKind(this.title);
  final String title;
}

/// enum.dart's RecordGrouping.
enum RecordGrouping {
  app('App'),
  host('Host'),
  rule('Rule'),
  exit('Exit');

  const RecordGrouping(this.label);
  final String label;
}

const recordFacetMaxOptions = 12;

@immutable
class RecordFilter {
  final RecordFacetKind kind;
  final String value;

  const RecordFilter(this.kind, this.value);

  @override
  bool operator ==(Object other) =>
      other is RecordFilter && other.kind == kind && other.value == value;

  @override
  int get hashCode => Object.hash(kind, value);

  @override
  String toString() => 'RecordFilter(${kind.name}: $value)';
}

/// Filters on one facet widen each other, on different facets narrow.
class RecordFacet<T> {
  final RecordFacetKind kind;
  final Iterable<String> Function(T item) valuesOf;

  /// Only a facet whose values come from a fixed set is offered in the menu;
  /// one growing with the user's apps or profiles is filtered from a row.
  final bool offered;

  final List<String> order;
  final String Function(String value)? labelOf;

  const RecordFacet(
    this.kind,
    this.valuesOf, {
    this.offered = false,
    this.order = const [],
    this.labelOf,
  });

  String label(String value) => labelOf?.call(value) ?? value;

  List<String> optionsOf(
    Iterable<T> items, {
    Iterable<String> selected = const [],
    int max = recordFacetMaxOptions,
  }) {
    final counts = <String, int>{};
    for (final item in items) {
      for (final value in valuesOf(item)) {
        if (value.isNotEmpty) {
          counts[value] = (counts[value] ?? 0) + 1;
        }
      }
    }
    final frequent = counts.keys.sorted((a, b) => counts[b]! - counts[a]!);
    final options = {...frequent.take(max), ...selected}.toList();
    int rank(String value) {
      final index = order.indexOf(value);
      return index < 0 ? order.length : index;
    }

    return options..sort((a, b) {
      final ranked = rank(a) - rank(b);
      return ranked != 0 ? ranked : a.toLowerCase().compareTo(b.toLowerCase());
    });
  }
}

class RecordGrouper<T> {
  final RecordGrouping grouping;
  final String Function(T item) keyOf;

  const RecordGrouper(this.grouping, this.keyOf);
}

class RecordGroup<T> {
  final String key;

  /// Top first.
  final List<T> items;

  const RecordGroup(this.key, this.items);
}

/// Groups follow their item shown highest; [reverse] marks a list shown
/// bottom up.
List<RecordGroup<T>> groupRecords<T>(
  List<T> items,
  String Function(T item) keyOf, {
  bool reverse = false,
}) {
  final groups = <String, List<T>>{};
  for (final item in reverse ? items.reversed : items) {
    (groups[keyOf(item)] ??= []).add(item);
  }
  return [
    for (final MapEntry(:key, :value) in groups.entries)
      RecordGroup(key, value),
  ];
}

extension RecordFilterIterableExt<T> on Iterable<T> {
  Iterable<T> whereFilters(
    List<RecordFacet<T>> facets,
    List<RecordFilter> filters,
  ) {
    if (filters.isEmpty) {
      return this;
    }
    final selected = <RecordFacet<T>, Set<String>>{};
    for (final filter in filters) {
      final facet = facets.firstWhereOrNull(
        (facet) => facet.kind == filter.kind,
      );
      if (facet != null) {
        (selected[facet] ??= {}).add(filter.value);
      }
    }
    return where(
      (item) => selected.entries.every(
        (entry) => entry.key.valuesOf(item).any(entry.value.contains),
      ),
    );
  }
}


@immutable
class RecordFilterState {
  final List<RecordFilter> filters;
  final RecordGrouping? grouping;

  const RecordFilterState({this.filters = const [], this.grouping});
}

typedef RecordFacetOptions = Map<RecordFacetKind, List<String>>;

const _optionsEquality = MapEquality<RecordFacetKind, List<String>>(
  values: ListEquality(),
);

class RecordFilterController<T> extends ValueNotifier<RecordFilterState> {
  final List<RecordFacet<T>> facets;
  final List<RecordGrouper<T>> groupers;
  final ValueChanged<RecordGrouping?>? onGroupingChanged;

  // Apart from [value] so a list refresh, which only moves [options] when a
  // value comes or goes, leaves the bar and its chips alone.
  final _keywords = ValueNotifier<List<ScaffoldKeyword>>(const []);
  final _pinnedKeywords = ValueNotifier<List<ScaffoldKeyword>>(const []);
  final options = ValueNotifier<RecordFacetOptions>(const {});

  RecordFilterController({
    required this.facets,
    this.groupers = const [],
    RecordGrouping? grouping,
    this.onGroupingChanged,
  }) : super(
         RecordFilterState(
           grouping: groupers.any((grouper) => grouper.grouping == grouping)
               ? grouping
               : null,
         ),
       );

  List<RecordFilter> get filters => value.filters;

  bool get isFiltering => filters.isNotEmpty;

  RecordGrouper<T>? get grouper => groupers.firstWhereOrNull(
    (grouper) => grouper.grouping == value.grouping,
  );

  RecordFacet<T>? facetOf(RecordFacetKind kind) =>
      facets.firstWhereOrNull((facet) => facet.kind == kind);

  void add(RecordFilter filter) {
    if (!filters.contains(filter)) {
      _setFilters([...filters, filter]);
    }
  }

  void remove(RecordFilter filter) {
    if (filters.contains(filter)) {
      _setFilters([...filters]..remove(filter));
    }
  }

  void toggle(RecordFilter filter) {
    filters.contains(filter) ? remove(filter) : add(filter);
  }

  /// [pinned] keeps every offered value in the strip as a toggle.
  ValueListenable<List<ScaffoldKeyword>> keywordsOf({required bool pinned}) =>
      pinned ? _pinnedKeywords : _keywords;

  void _setFilters(List<RecordFilter> filters) {
    value = RecordFilterState(filters: filters, grouping: value.grouping);
    _updateKeywords();
  }

  ScaffoldKeyword _removable(
    RecordFilter filter, {
    bool startsSection = false,
  }) {
    return ScaffoldKeyword(
      key: filter,
      label: facetOf(filter.kind)?.label(filter.value) ?? filter.value,
      onDeleted: () => remove(filter),
      startsSection: startsSection,
    );
  }

  void _updateKeywords() {
    _keywords.value = [for (final filter in filters) _removable(filter)];
    final options = this.options.value;
    final pinned = <ScaffoldKeyword>[];
    for (final facet in facets) {
      for (final (index, option) in (options[facet.kind] ?? []).indexed) {
        final filter = RecordFilter(facet.kind, option);
        pinned.add(
          ScaffoldKeyword(
            key: filter,
            label: facet.label(option),
            selected: filters.contains(filter),
            onPressed: () => toggle(filter),
            startsSection: index == 0 && pinned.isNotEmpty,
          ),
        );
      }
    }
    final unpinned = filters.where(
      (filter) => !(options[filter.kind]?.contains(filter.value) ?? false),
    );
    for (final (index, filter) in unpinned.indexed) {
      pinned.add(
        _removable(filter, startsSection: index == 0 && pinned.isNotEmpty),
      );
    }
    _pinnedKeywords.value = pinned;
  }

  void setGrouping(RecordGrouping? grouping) {
    if (grouping == value.grouping) {
      return;
    }
    value = RecordFilterState(filters: filters, grouping: grouping);
    onGroupingChanged?.call(grouping);
  }

  /// Offers the values [items] take, read before any filter narrows them.
  void offer(Iterable<T> items) {
    final next = <RecordFacetKind, List<String>>{};
    for (final facet in facets) {
      if (!facet.offered) {
        continue;
      }
      final values = facet.optionsOf(
        items,
        selected: [
          for (final filter in filters)
            if (filter.kind == facet.kind) filter.value,
        ],
      );
      if (values.isNotEmpty) {
        next[facet.kind] = values;
      }
    }
    if (!_optionsEquality.equals(next, options.value)) {
      options.value = next;
      _updateKeywords();
    }
  }

  List<T> apply(List<T> items) =>
      isFiltering ? items.whereFilters(facets, filters).toList() : items;

  List<RecordGroup<T>>? groupsOf(List<T> items, {bool reverse = false}) {
    final keyOf = grouper?.keyOf;
    return keyOf == null ? null : groupRecords(items, keyOf, reverse: reverse);
  }

  List<PopupMenuItem> menuItems(BuildContext context, {required bool pinned}) {
    final options = this.options.value;
    final grouping = value.grouping;
    return [
      if (!pinned && options.isNotEmpty)
        PopupMenuItem(
          glyph: AppGlyphs.filter,
          label: context.tr('Filter'),
          subItems: [
            for (final facet in facets)
              for (final (index, option) in (options[facet.kind] ?? []).indexed)
                PopupMenuItem(
                  label: facet.label(option),
                  checked: filters.contains(RecordFilter(facet.kind, option)),
                  keepsOpen: true,
                  section: index == 0
                      ? PopupMenuSection(title: context.tr(facet.kind.title))
                      : null,
                  onPressed: () => toggle(RecordFilter(facet.kind, option)),
                ),
          ],
        ),
      if (groupers.isNotEmpty)
        PopupMenuItem(
          glyph: AppGlyphs.layers,
          label: context.tr('Group by'),
          subItems: [
            PopupMenuItem(
              label: context.tr('None'),
              checked: grouping == null,
              onPressed: () => setGrouping(null),
            ),
            for (final grouper in groupers)
              PopupMenuItem(
                label: context.tr(grouper.grouping.label),
                checked: grouping == grouper.grouping,
                onPressed: () => setGrouping(grouper.grouping),
              ),
          ],
        ),
    ];
  }

  @override
  void dispose() {
    _keywords.dispose();
    _pinnedKeywords.dispose();
    options.dispose();
    super.dispose();
  }
}

/// The grouping a page saved in the `recordGroupings` interface preference.
RecordGrouping? savedRecordGrouping(Json preferences, String pageId) {
  final saved = preferences['recordGroupings'];
  return saved is Map ? RecordGrouping.values.asNameMap()[saved[pageId]] : null;
}

/// Saves [grouping] for [pageId] in the `recordGroupings` interface preference.
void saveRecordGrouping(BoxController box, String pageId, RecordGrouping? grouping) {
  final saved = box.preferences['recordGroupings'];
  final groupings = <String, String>{
    if (saved is Map) for (final MapEntry(:key, :value) in saved.entries) if (key is String && value is String && key != pageId) key: value,
    if (grouping != null) pageId: grouping.name,
  };
  box.setPreference('recordGroupings', groupings);
}

/// Lets a value tapped in a row narrow the list it sits in.
class RecordFilterScope extends InheritedWidget {
  final ValueChanged<RecordFilter> onFilter;

  const RecordFilterScope({
    super.key,
    required this.onFilter,
    required super.child,
  });

  static void add(BuildContext context, RecordFilter filter) {
    context.getInheritedWidgetOfExactType<RecordFilterScope>()?.onFilter(
      filter,
    );
  }

  @override
  bool updateShouldNotify(RecordFilterScope oldWidget) => false;
}

/// Lays [groups] out folded under their headers, newest first whatever way
/// the list reads ungrouped.
class RecordGroupedList<T> extends StatelessWidget {
  final List<RecordGroup<T>> groups;
  final RecordGrouping grouping;
  final ScrollController? controller;
  final EdgeInsetsGeometry? padding;
  final double estimatedItemExtent;
  final Widget Function(BuildContext context, T item, int index) itemBuilder;
  final Widget? Function(RecordGroup<T> group)? leadingOf;
  final Widget? Function(RecordGroup<T> group)? trailingOf;

  const RecordGroupedList({
    super.key,
    required this.groups,
    required this.grouping,
    this.controller,
    this.padding,
    this.estimatedItemExtent = recordListEstimatedExtent,
    required this.itemBuilder,
    this.leadingOf,
    this.trailingOf,
  });

  @override
  Widget build(BuildContext context) {
    final other = context.tr('Other');
    return ArrivalCacheExtentBuilder(
      builder: (cacheExtent) => CustomScrollView(
        controller: controller,
        scrollCacheExtent: cacheExtent,
        slivers: [
          SliverPadding(
            padding: padding ?? EdgeInsets.zero,
            sliver: SliverCollapsibleGroups(
              key: ValueKey(grouping),
              estimatedItemExtent: estimatedItemExtent,
              groups: [
                for (final group in groups)
                  CollapsibleGroup(
                    key: group.key,
                    label: group.key.isEmpty ? other : group.key,
                    itemCount: group.items.length,
                    leading: leadingOf?.call(group),
                    trailing: trailingOf?.call(group),
                  ),
              ],
              itemBuilder: (context, group, index) =>
                  itemBuilder(context, groups[group].items[index], index),
            ),
          ),
        ],
      ),
    );
  }
}
