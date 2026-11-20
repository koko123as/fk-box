//! Record filters (ui/RecordFilter.kt, after FlClash's record filter): values
//! tapped in a record become keyword chips, offered facets fill the Filter
//! menu (or the pinned strip on wide layouts), and a grouper folds the list
//! under collapsible headers.

use std::collections::{HashMap, HashSet};

use serde_json::{json, Value};

use crate::app::{self, data};
use crate::i18n::tr;
use crate::nav::MenuEntry;
use crate::pages::{mark, Mark};
use crate::rows::Row;

pub const MAX_OPTIONS: usize = 12;

pub struct Facet {
    pub kind: &'static str,
    pub values: fn(&Value) -> Vec<String>,
    pub offered: bool,
    pub order: &'static [&'static str],
    pub label: fn(&str) -> String,
}

pub fn same(v: &str) -> String {
    v.to_string()
}

pub struct Grouper {
    pub id: &'static str,
    pub label: &'static str,
    pub key: fn(&Value) -> String,
}

#[derive(Default)]
pub struct Filters {
    pub filters: Vec<(&'static str, String)>,
    pub grouping: Option<&'static str>,
    pub open: HashSet<String>,
    pub options: Vec<(&'static str, Vec<String>)>,
    /// The preference key under recordGroupings ("connections", "captured", …).
    pub page_id: &'static str,
}

impl Filters {
    pub fn new(page_id: &'static str, groupers: &[Grouper]) -> Filters {
        let saved = data(|d| d.preferences.get("recordGroupings").and_then(|g| g.get(page_id)).and_then(Value::as_str).map(str::to_string));
        let grouping = saved.and_then(|s| groupers.iter().find(|g| g.id == s).map(|g| g.id));
        Filters { grouping, page_id, ..Default::default() }
    }

    pub fn is_filtering(&self) -> bool {
        !self.filters.is_empty()
    }

    fn facet<'a>(facets: &'a [Facet], kind: &str) -> Option<&'a Facet> {
        facets.iter().find(|f| f.kind == kind)
    }

    pub fn add(&mut self, facets: &[Facet], kind: &'static str, value: String) {
        if !value.is_empty() && Self::facet(facets, kind).is_some() && !self.filters.iter().any(|(k, v)| *k == kind && *v == value) {
            self.filters.push((kind, value));
        }
    }

    pub fn toggle(&mut self, facets: &[Facet], kind: &'static str, value: String) {
        if let Some(i) = self.filters.iter().position(|(k, v)| *k == kind && *v == value) {
            self.filters.remove(i);
        } else {
            self.add(facets, kind, value);
        }
    }

    /// Offers the values `items` take; call with the list before filtering.
    pub fn offer(&mut self, facets: &[Facet], items: &[Value]) {
        let mut next = Vec::new();
        for facet in facets.iter().filter(|f| f.offered) {
            let mut counts: HashMap<String, usize> = HashMap::new();
            for item in items {
                for v in (facet.values)(item) {
                    if !v.is_empty() {
                        *counts.entry(v).or_default() += 1;
                    }
                }
            }
            let mut frequent: Vec<String> = counts.keys().cloned().collect();
            frequent.sort_by(|a, b| counts[b].cmp(&counts[a]).then(a.cmp(b)));
            let mut options: Vec<String> = frequent.into_iter().take(MAX_OPTIONS).collect();
            for (k, v) in &self.filters {
                if *k == facet.kind && !options.contains(v) {
                    options.push(v.clone());
                }
            }
            let rank = |v: &str| facet.order.iter().position(|o| *o == v).unwrap_or(facet.order.len());
            options.sort_by(|a, b| rank(a).cmp(&rank(b)).then(a.to_lowercase().cmp(&b.to_lowercase())));
            if !options.is_empty() {
                next.push((facet.kind, options));
            }
        }
        self.options = next;
    }

    /// Filters on one facet widen each other, on different facets narrow.
    pub fn apply(&self, facets: &[Facet], items: Vec<Value>) -> Vec<Value> {
        if self.filters.is_empty() {
            return items;
        }
        items
            .into_iter()
            .filter(|item| {
                facets.iter().all(|facet| {
                    let wanted: Vec<&String> = self.filters.iter().filter(|(k, _)| *k == facet.kind).map(|(_, v)| v).collect();
                    wanted.is_empty() || (facet.values)(item).iter().any(|v| wanted.contains(&v))
                })
            })
            .collect()
    }

    /// Groups follow their item shown highest.
    pub fn groups(&self, groupers: &[Grouper], items: &[Value]) -> Option<Vec<(String, Vec<Value>)>> {
        let g = groupers.iter().find(|g| Some(g.id) == self.grouping)?;
        let mut order: Vec<String> = Vec::new();
        let mut map: HashMap<String, Vec<Value>> = HashMap::new();
        for item in items {
            let key = (g.key)(item);
            if !map.contains_key(&key) {
                order.push(key.clone());
            }
            map.entry(key).or_default().push(item.clone());
        }
        Some(order.into_iter().map(|k| { let v = map.remove(&k).unwrap_or_default(); (k, v) }).collect())
    }

    /// The keyword strip: [pinned] keeps every offered value as a toggle.
    pub fn keywords(&self, facets: &[Facet], pinned: bool) -> Vec<(String, String, bool, bool, bool)> {
        let key = |k: &str, v: &str| format!("{k}\u{1f}{v}");
        let label = |k: &str, v: &str| Self::facet(facets, k).map(|f| (f.label)(v)).unwrap_or_else(|| v.to_string());
        if !pinned {
            return self.filters.iter().map(|(k, v)| (key(k, v), label(k, v), true, false, false)).collect();
        }
        let mut out = Vec::new();
        for facet in facets {
            if let Some((_, options)) = self.options.iter().find(|(k, _)| *k == facet.kind) {
                for (i, o) in options.iter().enumerate() {
                    let selected = self.filters.iter().any(|(k, v)| *k == facet.kind && v == o);
                    let start = i == 0 && !out.is_empty();
                    out.push((key(facet.kind, o), (facet.label)(o), false, selected, start));
                }
            }
        }
        let extra: Vec<_> = self.filters.iter().filter(|(k, v)| !self.options.iter().any(|(ok, ov)| ok == k && ov.contains(v))).collect();
        for (i, (k, v)) in extra.into_iter().enumerate() {
            let start = i == 0 && !out.is_empty();
            out.push((key(k, v), label(k, v), true, false, start));
        }
        out
    }

    /// A keyword chip was tapped: toggle (pinned) or remove it.
    pub fn keyword(&mut self, facets: &[Facet], key: &str) {
        let Some((k, v)) = key.split_once('\u{1f}') else { return };
        if let Some(facet) = Self::facet(facets, k) {
            self.toggle(facets, facet.kind, v.to_string());
        }
    }

    pub fn change_grouping(&mut self, next: Option<&'static str>) {
        if next == self.grouping {
            return;
        }
        self.grouping = next;
        self.open.clear();
        let mut groupings = data(|d| d.preferences.get("recordGroupings").cloned()).unwrap_or(json!({}));
        if !groupings.is_object() {
            groupings = json!({});
        }
        match next {
            Some(g) => groupings[self.page_id] = json!(g),
            None => {
                if let Some(o) = groupings.as_object_mut() {
                    o.remove(self.page_id);
                }
            }
        }
        app::set_preference("recordGroupings", groupings);
    }

    /// The Filter and Group by menus. `with` applies a change to this page's filters.
    pub fn menu_items(&self, facets: &'static [Facet], groupers: &'static [Grouper], pinned: bool, with: std::rc::Rc<dyn Fn(&dyn Fn(&mut Filters))>) -> Vec<MenuEntry> {
        let mut items = Vec::new();
        if !pinned && !self.options.is_empty() {
            let mut sub = Vec::new();
            for facet in facets {
                if let Some((_, options)) = self.options.iter().find(|(k, _)| *k == facet.kind) {
                    for (i, o) in options.iter().enumerate() {
                        let selected = self.filters.iter().any(|(k, v)| *k == facet.kind && v == o);
                        let (w, kind, value) = (with.clone(), facet.kind, o.clone());
                        let mut m = MenuEntry::plain(&(facet.label)(o), move || {
                            let value = value.clone();
                            w(&move |f: &mut Filters| f.toggle(facets, kind, value.clone()));
                            mark(Mark::ROWS);
                        })
                        .checked(selected);
                        if i == 0 {
                            m = m.section(&tr(facet.kind));
                        }
                        sub.push(m);
                    }
                }
            }
            items.push(MenuEntry::submenu("Filter", "filter", sub));
        }
        if !groupers.is_empty() {
            let w = with.clone();
            let mut sub = vec![MenuEntry::plain(&tr("None"), move || {
                w(&|f: &mut Filters| f.change_grouping(None));
                mark(Mark::ROWS);
            })
            .checked(self.grouping.is_none())];
            for g in groupers {
                let (w, id) = (with.clone(), g.id);
                sub.push(
                    MenuEntry::plain(&tr(g.label), move || {
                        w(&move |f: &mut Filters| f.change_grouping(Some(id)));
                        mark(Mark::ROWS);
                    })
                    .checked(self.grouping == Some(g.id)),
                );
            }
            items.push(MenuEntry::submenu("Group by", "layers", sub));
        }
        items
    }

    /// Rows for `items`, under folding headers when grouped.
    pub fn rows(&self, groupers: &[Grouper], items: &[Value], toggle: std::rc::Rc<dyn Fn(String)>, row: &dyn Fn(&Value) -> Row) -> Vec<Row> {
        match self.groups(groupers, items) {
            None => items.iter().map(row).collect(),
            Some(groups) => {
                let mut out = Vec::new();
                for (key, list) in groups {
                    let open = self.open.contains(&key);
                    let t = toggle.clone();
                    let k = key.clone();
                    let label = if key.is_empty() { tr("Other") } else { key.clone() };
                    out.push(Row::group(&label, list.len(), open, move || t(k.clone())).key(&format!("group:{key}")));
                    if open {
                        for item in &list {
                            let mut r = row(item);
                            r.data.key = format!("group:{key}:{}", r.data.key).into();
                            out.push(r);
                        }
                    }
                }
                out
            }
        }
    }
}
