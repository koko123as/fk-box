//! Rust-built list pages (ui/pages/rows.slint): a page is a builder closure
//! that returns its bar and rows from the current state; it is rebuilt on
//! every projection and its rows are diffed into a persistent model, so
//! fields keep their focus and text while the user types.

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use slint::{ComponentHandle, Model, ModelRc, SharedString, VecModel};

use crate::app::ss;
use crate::i18n::tr;
use crate::nav::{self, glyph, MenuEntry};
use crate::{AppWindow, BarAction, Nav, PageData, Pages, SettingRow};

pub type Act = Rc<dyn Fn(String)>;

#[derive(Clone, Default)]
pub struct Bar {
    pub id: String,
    pub icon: &'static str,
    pub tooltip: String,
    pub enabled: bool,
    pub selected: bool,
    pub loading: bool,
    pub action: Option<Rc<dyn Fn()>>,
}

impl Bar {
    pub fn new(icon: &'static str, tooltip: &str, action: impl Fn() + 'static) -> Bar {
        Bar { id: format!("{icon}:{tooltip}"), icon, tooltip: tr(tooltip), enabled: true, selected: false, loading: false, action: Some(Rc::new(action)) }
    }

    pub fn when(mut self, enabled: bool) -> Bar {
        self.enabled = enabled;
        self
    }

    pub fn loading(mut self, loading: bool) -> Bar {
        self.loading = loading;
        self
    }

    pub fn selected(mut self, selected: bool) -> Bar {
        self.selected = selected;
        self
    }
}

#[derive(Clone, Default)]
pub struct Row {
    pub data: SettingRow,
    pub action: Option<Act>,
    pub edit: Option<Act>,
    pub done: Option<Act>,
    pub trail: Option<Rc<dyn Fn()>>,
    pub menu: Option<Rc<dyn Fn() -> Vec<MenuEntry>>>,
}

fn row(kind: &str, title: &str) -> Row {
    Row { data: SettingRow { kind: kind.into(), title: title.into(), enabled: true, ..Default::default() }, ..Default::default() }
}

impl Row {
    /// Section title (already translated by the caller when needed).
    pub fn section(title: &str) -> Row {
        row("section", title)
    }

    pub fn first_section(title: &str) -> Row {
        let mut r = row("section", title);
        r.data.checked = true;
        r
    }

    pub fn gap() -> Row {
        row("gap", "")
    }

    pub fn spacer(px: i32) -> Row {
        let mut r = row("spacer", "");
        r.data.lines = px;
        r
    }

    pub fn footer(text: &str) -> Row {
        row("footer", text)
    }

    pub fn title(text: &str, subtitle: &str) -> Row {
        let mut r = row("title", text);
        r.data.subtitle = subtitle.into();
        r
    }

    pub fn item(title: &str) -> Row {
        row("item", title)
    }

    /// ListItemOpen: a chevron row that runs `f`.
    pub fn open(title: &str, icon: Option<&'static str>, f: impl Fn() + 'static) -> Row {
        let r = row("item", title).chevron().on(move |_| f());
        match icon {
            Some(i) => r.icon(i),
            None => r,
        }
    }

    /// ListItemToggle
    pub fn switch(title: &str, checked: bool, f: impl Fn(bool) + 'static) -> Row {
        let mut r = row("switch", title);
        r.data.checked = checked;
        r.data.clickable = true;
        r.action = Some(Rc::new(move |v: String| f(v == "1")));
        r
    }

    pub fn check(title: &str, checked: bool, f: impl Fn(bool) + 'static) -> Row {
        let mut r = row("check", title);
        r.data.checked = checked;
        r.data.clickable = true;
        r.action = Some(Rc::new(move |v: String| f(v == "1")));
        r
    }

    /// FormRow with FormRowTextField.
    pub fn field(title: &str, value: &str, hint: &str, f: impl Fn(String) + 'static) -> Row {
        let mut r = row("field", title);
        r.data.value = value.into();
        r.data.hint = hint.into();
        r.edit = Some(Rc::new(f));
        r
    }

    /// An outlined single-line field.
    pub fn input(label: &str, value: &str, hint: &str, f: impl Fn(String) + 'static) -> Row {
        let mut r = row("input", label);
        r.data.value = value.into();
        r.data.hint = hint.into();
        r.edit = Some(Rc::new(f));
        r
    }

    /// An outlined multi-line field.
    pub fn editor(label: &str, value: &str, lines: i32, f: impl Fn(String) + 'static) -> Row {
        let mut r = row("editor", label);
        r.data.value = value.into();
        r.data.lines = lines;
        r.data.mono = true;
        r.edit = Some(Rc::new(f));
        r
    }

    pub fn text(text: &str) -> Row {
        row("text", text)
    }

    pub fn error(text: &str) -> Row {
        row("error", text)
    }

    pub fn button(label: &str, kind: i32, f: impl Fn() + 'static) -> Row {
        let mut r = row("button", label);
        r.data.lines = kind;
        r.action = Some(Rc::new(move |_| f()));
        r
    }

    pub fn progress(label: &str, value: f32) -> Row {
        let mut r = row("progress", label);
        r.data.progress = value;
        r
    }

    pub fn card(title: &str, subtitle: &str, f: impl Fn() + 'static) -> Row {
        let mut r = row("card", title);
        r.data.subtitle = subtitle.into();
        r.action = Some(Rc::new(move |_| f()));
        r
    }

    /// ReorderableColumn row with a SortHandle: `f(offset)` moves it by `offset` rows.
    pub fn sort(title: &str, f: impl Fn(i32) + 'static) -> Row {
        let mut r = row("sort", title);
        r.action = Some(Rc::new(move |v: String| f(v.parse().unwrap_or(0))));
        r
    }

    /// SegmentedButton row: `f(index)`.
    pub fn segments(options: Vec<String>, selected: i32, f: impl Fn(usize) + 'static) -> Row {
        let mut r = row("segments", "");
        r.data.options = slint::ModelRc::new(slint::VecModel::from(options.into_iter().map(slint::SharedString::from).collect::<Vec<_>>()));
        r.data.lines = selected;
        r.action = Some(Rc::new(move |v: String| f(v.parse().unwrap_or(0))));
        r
    }

    /// TonalChips: `f(label)`.
    pub fn chips(options: Vec<String>, f: impl Fn(String) + 'static) -> Row {
        let mut r = row("chips", "");
        r.data.options = slint::ModelRc::new(slint::VecModel::from(options.into_iter().map(slint::SharedString::from).collect::<Vec<_>>()));
        r.action = Some(Rc::new(f));
        r
    }

    /// ProfileStats: three figures (groups, outbounds, rules); loading shows skeletons.
    pub fn stats(groups: &str, outbounds: &str, rules: &str, loading: bool) -> Row {
        let mut r = row("stats", groups);
        r.data.subtitle = outbounds.into();
        r.data.value = rules.into();
        r.data.loading = loading;
        r
    }

    /// SubscriptionInfoView as a list item: the traffic line, the expiry
    /// (tinted by `tone` 0/1/2) and the quota bar when `ratio` is set.
    pub fn usage(line: &str, expire: &str, ratio: Option<f32>, tone: i32) -> Row {
        let mut r = row("usage", line);
        r.data.value = expire.into();
        r.data.checked = ratio.is_some();
        r.data.progress = ratio.unwrap_or(0.0);
        r.data.lines = tone;
        r
    }

    pub fn empty(title: &str, message: &str, icon: &'static str) -> Row {
        let mut r = row("empty", title);
        r.data.subtitle = message.into();
        r.data.glyph = glyph(icon);
        r
    }

    // Modifiers

    pub fn key(mut self, key: &str) -> Row {
        self.data.key = key.into();
        self
    }

    pub fn subtitle(mut self, s: &str) -> Row {
        self.data.subtitle = s.into();
        self
    }

    pub fn value(mut self, v: &str) -> Row {
        self.data.value = v.into();
        self
    }

    pub fn icon(mut self, icon: &'static str) -> Row {
        self.data.glyph = glyph(icon);
        self.data.has_glyph = true;
        self
    }

    pub fn chevron(mut self) -> Row {
        self.data.chevron = true;
        self
    }

    pub fn on(mut self, f: impl Fn(String) + 'static) -> Row {
        self.action = Some(Rc::new(f));
        self.data.clickable = true;
        self
    }

    pub fn on_done(mut self, f: impl Fn(String) + 'static) -> Row {
        self.done = Some(Rc::new(f));
        self
    }

    pub fn trailing(mut self, icon: &'static str, f: impl Fn() + 'static) -> Row {
        self.data.trailing_glyph = glyph(icon);
        self.data.has_trailing = true;
        self.trail = Some(Rc::new(f));
        self
    }

    pub fn menu(mut self, f: impl Fn() -> Vec<MenuEntry> + 'static) -> Row {
        self.data.menu = true;
        if self.data.kind != "card" {
            self.data.trailing_glyph = glyph("more");
            self.data.has_trailing = true;
        }
        self.menu = Some(Rc::new(f));
        self
    }

    pub fn enabled(mut self, enabled: bool) -> Row {
        self.data.enabled = enabled;
        if !enabled && self.data.kind == "item" {
            self.data.clickable = false;
        }
        self
    }

    pub fn danger(mut self) -> Row {
        self.data.danger = true;
        self
    }

    pub fn mono(mut self) -> Row {
        self.data.mono = true;
        self
    }

    /// RecordListItem: `labels` tap as "l<i>", `chain` chips as "c<i>", the rule (`detail`) as "r".
    pub fn record(time: &str, labels: Vec<String>, title: &str, f: impl Fn() + 'static) -> Row {
        let mut r = row("record", title);
        r.data.value = time.into();
        r.data.options = ModelRc::new(VecModel::from(labels.into_iter().map(SharedString::from).collect::<Vec<_>>()));
        r.action = Some(Rc::new(move |_| f()));
        r
    }

    pub fn chain(mut self, rule: &str, chain: Vec<String>) -> Row {
        self.data.detail = rule.into();
        self.data.chain = ModelRc::new(VecModel::from(chain.into_iter().map(SharedString::from).collect::<Vec<_>>()));
        self
    }

    /// The record's label taps ("l0", "c1", "r").
    pub fn on_tap(mut self, f: impl Fn(String) + 'static) -> Row {
        self.edit = Some(Rc::new(f));
        self
    }

    /// CollapsibleHeader
    pub fn group(label: &str, count: usize, open: bool, f: impl Fn() + 'static) -> Row {
        let mut r = row("group", label);
        r.data.value = count.to_string().into();
        r.data.checked = open;
        r.action = Some(Rc::new(move |_| f()));
        r
    }

    /// An installed app's icon: shown once loaded; until then the row asks
    /// for it when it scrolls near the viewport (settings::request_icon).
    pub fn app_icon(mut self, package: &str) -> Row {
        self.data.lazy = true;
        match crate::pages::settings::app_icon(package) {
            Some(i) => {
                self.data.image = i;
                self.data.has_image = true;
            }
            None => {
                // An empty 48 dp slot until it loads, so a row has the same
                // height with or without its icon (lazy rows share one height).
                self.data.has_image = true;
                self.data.image_key = package.into();
            }
        }
        self
    }

    pub fn secret(mut self, secret: bool) -> Row {
        self.data.secret = secret;
        self
    }

    pub fn number(mut self) -> Row {
        self.data.number = true;
        self
    }

    pub fn invalid(mut self, invalid: bool) -> Row {
        self.data.invalid = invalid;
        self
    }

    pub fn loading(mut self, loading: bool) -> Row {
        self.data.loading = loading;
        self
    }

    pub fn checked(mut self, checked: bool) -> Row {
        self.data.checked = checked;
        self
    }

    /// The subtitle on at most this many lines (one line is elided).
    pub fn subtitle_lines(mut self, lines: i32) -> Row {
        self.data.subtitle_lines = lines;
        self
    }

    pub fn lines(mut self, lines: i32) -> Row {
        self.data.lines = lines;
        self
    }

    pub fn hint(mut self, hint: &str) -> Row {
        self.data.hint = hint.into();
        self
    }
}

#[derive(Clone, Default)]
pub struct Page {
    pub title: String,
    pub bar: Vec<Bar>,
    /// The confirmation action, always shown last (CommonScaffold confirmationAction).
    pub confirm: Option<Bar>,
    /// Bar actions folded into the overflow menu (foldBarActions), filled by build().
    pub folded: Vec<Bar>,
    pub menu: Option<Rc<dyn Fn() -> Vec<MenuEntry>>>,
    pub loading: bool,
    pub search: Option<Act>,
    pub rows: Vec<Row>,
    pub empty: Option<(String, String, &'static str, String)>,
    pub empty_action: Option<Rc<dyn Fn()>>,
    /// FloatingFootBar: text, icon, chip label, button label.
    pub foot: Option<(String, &'static str, String, String)>,
    pub foot_action: Option<Rc<dyn Fn()>>,
    /// titleMenuItems: the title opens this menu.
    pub title_menu: Option<Rc<dyn Fn() -> Vec<MenuEntry>>>,
    /// The keyword strip: (key, label, removable, selected, section start), and its tap.
    pub keywords: Vec<(String, String, bool, bool, bool)>,
    pub on_keyword: Option<Act>,
    /// A list read bottom up (LogsPage's reverseLayout).
    pub reverse: bool,
}

impl Page {
    pub fn new(title: &str) -> Page {
        Page { title: title.to_string(), ..Default::default() }
    }

    pub fn rows(mut self, rows: Vec<Row>) -> Page {
        self.rows = rows;
        self
    }

    pub fn bar(mut self, bar: Vec<Bar>) -> Page {
        self.bar = bar;
        self
    }

    pub fn loading(mut self, loading: bool) -> Page {
        self.loading = loading;
        self
    }

    pub fn confirm(mut self, bar: Bar) -> Page {
        self.confirm = Some(bar);
        self
    }

    pub fn menu(mut self, f: impl Fn() -> Vec<MenuEntry> + 'static) -> Page {
        self.menu = Some(Rc::new(f));
        self
    }

    pub fn search(mut self, f: impl Fn(String) + 'static) -> Page {
        self.search = Some(Rc::new(f));
        self
    }

    pub fn empty(mut self, title: &str, message: &str, icon: &'static str) -> Page {
        self.empty = Some((title.into(), message.into(), icon, String::new()));
        self
    }

    pub fn title_menu(mut self, f: impl Fn() -> Vec<MenuEntry> + 'static) -> Page {
        self.title_menu = Some(Rc::new(f));
        self
    }

    pub fn keywords(mut self, keywords: Vec<(String, String, bool, bool, bool)>, f: impl Fn(String) + 'static) -> Page {
        self.keywords = keywords;
        self.on_keyword = Some(Rc::new(f));
        self
    }

    pub fn reverse(mut self, reverse: bool) -> Page {
        self.reverse = reverse;
        self
    }

    pub fn foot(mut self, text: &str, icon: &'static str, chip: &str, button: &str, f: impl Fn() + 'static) -> Page {
        self.foot = Some((text.into(), icon, chip.into(), button.into()));
        self.foot_action = Some(Rc::new(f));
        self
    }

    pub fn empty_action(mut self, label: &str, f: impl Fn() + 'static) -> Page {
        if let Some(e) = self.empty.as_mut() {
            e.3 = label.into();
        }
        self.empty_action = Some(Rc::new(f));
        self
    }
}

pub type Builder = Rc<dyn Fn() -> Page>;

struct Entry {
    builder: Builder,
    rows: Rc<VecModel<SettingRow>>,
    actions: Rc<VecModel<BarAction>>,
    // Kept per page like rows and actions: a fresh ModelRc on every build
    // never compares equal, so PageData would look changed (and the page be
    // set again) on each projection.
    keywords: Rc<VecModel<crate::Keyword>>,
    last: Page,
}

thread_local! {
    static ENTRIES: RefCell<HashMap<i32, Entry>> = RefCell::new(HashMap::new());
    static DATA: RefCell<Option<Rc<VecModel<PageData>>>> = const { RefCell::new(None) };
}

/// The home Tools page is built here too, under this id.
pub const TOOLS_ID: i32 = -100;
/// The home Requests and Logs destinations (sidebar layouts).
pub const REQUESTS_ID: i32 = -101;
pub const LOGS_ID: i32 = -102;

pub fn set_home(id: i32, builder: impl Fn() -> Page + 'static) {
    register(id, Rc::new(builder));
}

fn register(id: i32, builder: Builder) {
    ENTRIES.with(|e| {
        e.borrow_mut().insert(
            id,
            Entry {
                builder,
                rows: Rc::new(VecModel::default()),
                actions: Rc::new(VecModel::default()),
                keywords: Rc::new(VecModel::default()),
                last: Page::default(),
            },
        )
    });
}

/// Pushes a Rust-built page.
pub fn push(builder: impl Fn() -> Page + 'static) -> i32 {
    let builder: Builder = Rc::new(builder);
    // Registered before the route so the first projection finds it.
    let id = peek_id();
    register(id, builder);
    let pushed = nav::push("rows", "");
    debug_assert_eq!(pushed, id);
    pushed
}

/// Opens a Rust-built page as a form.
pub fn form(builder: impl Fn() -> Page + 'static) -> i32 {
    let builder: Builder = Rc::new(builder);
    let id = peek_id();
    register(id, builder);
    nav::form("rows", "")
}

fn peek_id() -> i32 {
    nav::next_id()
}

/// Builds the route `id` (opened from Slint with its own kind) as a Rust-built page.
pub fn adopt(id: i32, builder: impl Fn() -> Page + 'static) {
    register(id, Rc::new(builder));
}

pub fn set_tools(builder: impl Fn() -> Page + 'static) {
    register(TOOLS_ID, Rc::new(builder));
}

pub fn closed(id: i32) {
    ENTRIES.with(|e| e.borrow_mut().remove(&id));
}

fn positions(rows: &mut [SettingRow]) {
    let groupable = |k: &str| matches!(k, "item" | "switch" | "check" | "radio" | "field" | "sort" | "usage");
    let mut i = 0;
    while i < rows.len() {
        if !groupable(rows[i].kind.as_str()) {
            rows[i].position = 0;
            i += 1;
            continue;
        }
        let start = i;
        while i < rows.len() && groupable(rows[i].kind.as_str()) {
            i += 1;
        }
        let n = i - start;
        for (j, r) in rows[start..i].iter_mut().enumerate() {
            r.position = if n == 1 {
                4
            } else if j == 0 {
                1
            } else if j == n - 1 {
                3
            } else {
                2
            };
        }
    }
}

/// Diffs `next` into `model`, keeping row instances where the length matches.
pub fn sync_model<T: Clone + PartialEq + 'static>(model: &VecModel<T>, next: Vec<T>) {
    let count = model.row_count();
    for (i, item) in next.iter().enumerate() {
        if i < count {
            if model.row_data(i).as_ref() != Some(item) {
                model.set_row_data(i, item.clone());
            }
        } else {
            model.push(item.clone());
        }
    }
    for i in (next.len()..count).rev() {
        model.remove(i);
    }
}

fn same_strings(a: &ModelRc<SharedString>, b: &ModelRc<SharedString>) -> bool {
    a.row_count() == b.row_count() && a.iter().zip(b.iter()).all(|(x, y)| x == y)
}

/// How far ahead a shifted list is searched for the row it lines up with.
const ALIGN_WINDOW: usize = 256;

/// [sync_model] for a page's rows, which rebuild from scratch on every
/// projection. A record row's labels and chain are new models each build and
/// never compare equal, so the models of the row with the same key are kept
/// when their contents match; and rows that left or joined the list at the
/// first difference (the log ring dropping its oldest lines, a new connection
/// sorted to the top) are removed or inserted there instead of every later
/// row being set again. The final [sync_model] makes the model exactly `next`
/// whatever the alignment did.
fn sync_rows(model: &VecModel<SettingRow>, mut next: Vec<SettingRow>) {
    let count = model.row_count();
    if count > 0 {
        let old: HashMap<SharedString, SettingRow> = model.iter().map(|r| (r.key.clone(), r)).collect();
        for r in next.iter_mut() {
            if let Some(o) = old.get(&r.key) {
                if same_strings(&o.options, &r.options) {
                    r.options = o.options.clone();
                }
                if same_strings(&o.chain, &r.chain) {
                    r.chain = o.chain.clone();
                }
            }
        }
        let key = |i: usize| model.row_data(i).map(|r| r.key).unwrap_or_default();
        if let Some(at) = (0..count.min(next.len())).find(|&i| key(i) != next[i].key) {
            let first_old = key(at);
            let window = |len: usize| (at + 1)..len.min(at + ALIGN_WINDOW);
            if let Some(k) = window(count).find(|&k| key(k) == next[at].key) {
                for _ in at..k {
                    model.remove(at);
                }
            } else if let Some(j) = window(next.len()).find(|&j| next[j].key == first_old) {
                for (i, r) in next[at..j].iter().enumerate() {
                    model.insert(at + i, r.clone());
                }
            }
        }
    }
    sync_model(model, next);
}

/// Updates a property's model in place when it is already a VecModel.
pub fn update<T: Clone + PartialEq + 'static>(current: ModelRc<T>, next: Vec<T>) -> Option<ModelRc<T>> {
    if let Some(model) = current.as_any().downcast_ref::<VecModel<T>>() {
        sync_model(model, next);
        None
    } else {
        Some(ModelRc::new(VecModel::from(next)))
    }
}

fn build(id: i32) -> Option<PageData> {
    let entry = ENTRIES.with(|e| e.borrow().get(&id).map(|en| (en.builder.clone(), en.rows.clone(), en.actions.clone(), en.keywords.clone())));
    let (builder, rows_model, actions_model, keywords_model) = entry?;
    let mut page = builder();
    for (i, r) in page.rows.iter_mut().enumerate() {
        if r.data.key.is_empty() {
            r.data.key = format!("#{i}").into();
        }
        if r.action.is_some() && r.data.kind == "item" && !r.data.clickable && r.data.enabled {
            r.data.clickable = true;
        }
    }
    // foldBarActions: at most three buttons, the rest in the overflow menu.
    {
        const MAX_BAR_BUTTONS: usize = 3;
        let fixed = usize::from(page.search.is_some()) + usize::from(page.confirm.is_some());
        let menu = usize::from(page.menu.is_some());
        let mut shown = page.bar.clone();
        if fixed + shown.len() + menu > MAX_BAR_BUTTONS {
            let slots = MAX_BAR_BUTTONS.saturating_sub(1 + fixed);
            page.folded = shown.split_off(slots.min(shown.len()));
        }
        if let Some(c) = page.confirm.clone() {
            shown.push(c);
        }
        page.bar = shown;
    }
    let mut rows: Vec<SettingRow> = page.rows.iter().map(|r| r.data.clone()).collect();
    positions(&mut rows);
    sync_rows(&rows_model, rows);
    let actions: Vec<BarAction> = page
        .bar
        .iter()
        .map(|b| BarAction {
            id: ss(&b.id),
            glyph: glyph(b.icon),
            tooltip: ss(&b.tooltip),
            enabled: b.enabled && b.action.is_some(),
            selected: b.selected,
            loading: b.loading,
        })
        .collect();
    sync_model(&actions_model, actions);
    let keywords: Vec<crate::Keyword> = page
        .keywords
        .iter()
        .map(|(key, label, removable, selected, section)| crate::Keyword { key: ss(key), label: ss(label), removable: *removable, selected: *selected, section_start: *section })
        .collect();
    let has_keywords = !keywords.is_empty();
    sync_model(&keywords_model, keywords);
    let data = PageData {
        id,
        generic: true,
        title: ss(&page.title),
        actions: ModelRc::from(actions_model),
        menu_key: if page.menu.is_some() || !page.folded.is_empty() { ss(format!("page:{id}")) } else { SharedString::new() },
        loading: page.loading,
        searchable: page.search.is_some(),
        rows: ModelRc::from(rows_model),
        empty_title: page.empty.as_ref().map(|e| ss(&e.0)).unwrap_or_default(),
        empty_message: page.empty.as_ref().map(|e| ss(&e.1)).unwrap_or_default(),
        empty_glyph: page.empty.as_ref().map(|e| glyph(e.2)).unwrap_or_default(),
        empty_action: page.empty.as_ref().map(|e| ss(&e.3)).unwrap_or_default(),
        foot_text: page.foot.as_ref().map(|f| ss(&f.0)).unwrap_or_default(),
        foot_glyph: page.foot.as_ref().map(|f| glyph(f.1)).unwrap_or_default(),
        foot_chip: page.foot.as_ref().map(|f| ss(&f.2)).unwrap_or_default(),
        foot_button: page.foot.as_ref().map(|f| ss(&f.3)).unwrap_or_default(),
        title_menu_key: if page.title_menu.is_some() { ss(format!("title:{id}")) } else { SharedString::new() },
        keywords: if has_keywords { ModelRc::from(keywords_model) } else { ModelRc::default() },
        reverse: page.reverse,
    };
    ENTRIES.with(|e| {
        if let Some(en) = e.borrow_mut().get_mut(&id) {
            en.last = page;
        }
    });
    Some(data)
}

/// Rebuilds the Rust-built pages, aligned with the routes. `m` is what
/// changed: a projection that only carries the per-second marks (the clock,
/// the rates, streamed records) rebuilds just what is on screen (the top
/// route, or every route in the wide layout, and the shown home destination)
/// instead of every page each second. Covered pages are rebuilt by the next
/// full projection; opening, closing and switching pages all mark everything.
pub fn project(w: &AppWindow, m: crate::pages::Mark) {
    use crate::pages::Mark;
    let model = DATA.with(|d| {
        d.borrow_mut()
            .get_or_insert_with(|| {
                let m = Rc::new(VecModel::<PageData>::default());
                w.global::<Pages>().set_data(ModelRc::from(m.clone()));
                m
            })
            .clone()
    });
    let light = m.0 & !(Mark::CLOCK.0 | Mark::RATES.0 | Mark::RECORDS.0) == 0;
    let nav = w.global::<Nav>();
    let mobile = nav.get_mobile();
    let routes: Vec<i32> = nav.get_routes().iter().map(|r| r.id).collect();
    let covered = mobile && !routes.is_empty();
    let top = routes.len().saturating_sub(1);
    let next: Vec<PageData> = routes
        .iter()
        .enumerate()
        .map(|(i, id)| {
            if light && mobile && i != top {
                if let Some(kept) = model.row_data(i).filter(|p| p.id == *id) {
                    return kept;
                }
            }
            build(*id).unwrap_or(PageData { id: *id, ..Default::default() })
        })
        .collect();
    sync_model(&model, next);
    let tab = nav.get_tab();
    if !light || (tab == "Tools" && !covered && m.has(Mark::CLOCK)) {
        if let Some(tools) = build(TOOLS_ID) {
            if w.global::<Pages>().get_tools() != tools {
                w.global::<Pages>().set_tools(tools);
            }
        }
    }
    // Requests and Logs on the home pane only while they are the destination
    // shown, and in a light projection only when records arrived (they show
    // nothing the clock or the rates change). Their rows are updated in place
    // in the page's own models (build); the page itself is only replaced when
    // something else on it changed.
    let records = !light || (m.has(Mark::RECORDS) && !covered);
    if tab == "Requests" && records {
        if let Some(p) = build(REQUESTS_ID) {
            if w.global::<Pages>().get_requests() != p {
                w.global::<Pages>().set_requests(p);
            }
        }
    }
    if tab == "Logs" && records {
        if let Some(p) = build(LOGS_ID) {
            if w.global::<Pages>().get_logs() != p {
                w.global::<Pages>().set_logs(p);
            }
        }
    }
}
