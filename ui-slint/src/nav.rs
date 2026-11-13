//! Navigation, menus, dialogs and the snackbar (ui/Navigator.kt, Popup.kt,
//! Dialog.kt, BoxApp's SnackbarHost). Routes are a flat list: a form's root
//! has `form` and `sheet_root`; pages pushed while a form is on top join it.

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;
use std::time::Duration;

use slint::{ComponentHandle, Model, ModelRc, SharedString, Timer, VecModel};

use crate::app::{ss, with_window};
use crate::i18n::tr;
use crate::{AppWindow, Glyph, MenuItem, Nav, Route};

// ---------------------------------------------------------------------------
// Icons (icons.slint, read once so Rust can name glyphs)
// ---------------------------------------------------------------------------

fn icon_table() -> &'static HashMap<String, (String, bool)> {
    static TABLE: std::sync::OnceLock<HashMap<String, (String, bool)>> = std::sync::OnceLock::new();
    TABLE.get_or_init(|| {
        let mut map = HashMap::new();
        for line in include_str!("../ui/icons.slint").lines() {
            let line = line.trim();
            let Some(rest) = line.strip_prefix("out property <Glyph> ") else { continue };
            let Some((name, value)) = rest.split_once(':') else { continue };
            let code = value.split("\\u{").nth(1).and_then(|v| v.split('}').next()).and_then(|h| u32::from_str_radix(h, 16).ok());
            let outlined = value.contains("outlined: true");
            if let Some(ch) = code.and_then(char::from_u32) {
                map.insert(name.trim().to_string(), (ch.to_string(), outlined));
            }
        }
        map
    })
}

/// A glyph of icons.slint by its name ("bolt", "check-circle").
pub fn glyph(name: &str) -> Glyph {
    match icon_table().get(name) {
        Some((code, outlined)) => Glyph { code: code.as_str().into(), outlined: *outlined },
        None => Glyph::default(),
    }
}

// ---------------------------------------------------------------------------
// Routes
// ---------------------------------------------------------------------------

pub type Guard = Rc<dyn Fn() -> bool>;

#[derive(Default)]
struct NavState {
    routes: Option<Rc<VecModel<Route>>>,
    next_id: i32,
    /// BoxEditGuard: true while the route has unsaved changes.
    guards: HashMap<i32, Guard>,
    /// Busy routes refuse to close.
    busy: HashMap<i32, Guard>,
    on_closed: HashMap<i32, Box<dyn FnOnce()>>,
    menu: Vec<MenuEntry>,
    menu_stack: Vec<Vec<MenuEntry>>,
    dialogs: Vec<Dialog>,
    toast_serial: u64,
}

thread_local! {
    static NAV: RefCell<NavState> = RefCell::new(NavState::default());
}

fn routes() -> Rc<VecModel<Route>> {
    NAV.with(|n| n.borrow().routes.clone().expect("navigation not installed"))
}

fn all_routes() -> Vec<Route> {
    routes().iter().collect()
}

pub fn top() -> Option<Route> {
    all_routes().last().cloned()
}

pub fn is_open(kind: &str) -> bool {
    all_routes().iter().any(|r| r.kind == kind)
}

pub fn open_kinds() -> Vec<(String, String)> {
    all_routes().iter().map(|r| (r.kind.to_string(), r.arg.to_string())).collect()
}

pub fn mobile() -> bool {
    crate::app::window().map(|w| w.global::<Nav>().get_mobile()).unwrap_or(true)
}

pub fn tab() -> String {
    crate::app::window().map(|w| w.global::<Nav>().get_tab().to_string()).unwrap_or_default()
}

fn tab_index() -> i32 {
    let Some(w) = crate::app::window() else { return -1 };
    let nav = w.global::<Nav>();
    let tab = nav.get_tab();
    nav.get_destinations().iter().position(|d| d == tab).map(|i| i as i32).unwrap_or(-1)
}

fn add(kind: &str, arg: &str, form: bool) -> i32 {
    let model = routes();
    let id = NAV.with(|n| {
        let mut n = n.borrow_mut();
        n.next_id += 1;
        n.next_id
    });
    let top_form = model.iter().last().map(|r| r.form).unwrap_or(false);
    let route = if form {
        Route { id, kind: kind.into(), arg: arg.into(), form: true, pane: -1, sheet_root: true }
    } else if top_form {
        Route { id, kind: kind.into(), arg: arg.into(), form: true, pane: -1, sheet_root: false }
    } else {
        Route { id, kind: kind.into(), arg: arg.into(), form: false, pane: if mobile() { -1 } else { tab_index() }, sheet_root: false }
    };
    model.push(route);
    crate::pages::opened(kind, arg, id);
    crate::pages::mark(crate::pages::Mark::ALL);
    id
}

/// Opens a page, inside the form on top when there is one.
pub fn push(kind: &str, arg: &str) -> i32 {
    add(kind, arg, false)
}

/// Opens a form; on a phone it fills the screen, elsewhere a card.
pub fn form(kind: &str, arg: &str) -> i32 {
    add(kind, arg, true)
}

pub fn on_closed(id: i32, f: impl FnOnce() + 'static) {
    NAV.with(|n| n.borrow_mut().on_closed.insert(id, Box::new(f)));
}

/// BoxEditGuard for the route `id`: `dirty` answers whether to ask first.
pub fn guard(id: i32, dirty: impl Fn() -> bool + 'static) {
    NAV.with(|n| n.borrow_mut().guards.insert(id, Rc::new(dirty)));
}

pub fn busy_guard(id: i32, busy: impl Fn() -> bool + 'static) {
    NAV.with(|n| n.borrow_mut().busy.insert(id, Rc::new(busy)));
}

fn remove_at(index: usize) {
    let model = routes();
    if index >= model.row_count() {
        return;
    }
    let route = model.remove(index);
    let closed = NAV.with(|n| {
        let mut n = n.borrow_mut();
        n.guards.remove(&route.id);
        n.busy.remove(&route.id);
        n.on_closed.remove(&route.id)
    });
    crate::pages::closed(&route.kind, &route.arg, route.id);
    if let Some(closed) = closed {
        closed();
    }
}

/// Closes the innermost route without asking its guard.
pub fn pop() {
    let count = routes().row_count();
    if count > 0 {
        remove_at(count - 1);
        refocus();
        crate::pages::mark(crate::pages::Mark::ALL);
    }
}

/// Closes the route `id` (and anything above it).
pub fn close(id: i32) {
    let list = all_routes();
    if let Some(index) = list.iter().position(|r| r.id == id) {
        for i in (index..list.len()).rev() {
            remove_at(i);
        }
        refocus();
        crate::pages::mark(crate::pages::Mark::ALL);
    }
}

/// Closes every route of a kind.
pub fn close_kind(kind: &str) {
    for r in all_routes().iter().rev() {
        if r.kind == kind {
            close(r.id);
        }
    }
}

fn ask_then(id: i32, then: impl FnOnce() + 'static) {
    let (dirty, busy) = NAV.with(|n| {
        let n = n.borrow();
        (n.guards.get(&id).cloned(), n.busy.get(&id).cloned())
    });
    if busy.map(|b| b()).unwrap_or(false) {
        return;
    }
    if dirty.map(|d| d()).unwrap_or(false) {
        confirm("Discard changes?", None, "Discard", move |ok| {
            if ok {
                then()
            }
        });
    } else {
        then();
    }
}

/// maybePop: asks the innermost route's guard, then closes it.
pub fn maybe_pop() {
    if let Some(top) = top() {
        ask_then(top.id, move || close(top.id));
    }
}

/// Closes the form `id` belongs to, asking the guard of its visible route.
pub fn dismiss_form(id: i32) {
    let list = all_routes();
    let Some(index) = list.iter().position(|r| r.id == id) else { return };
    let visible = list.last().map(|r| r.id).unwrap_or(id);
    let _ = index;
    ask_then(visible, move || close(id));
}

/// System back: closes the menu, the dialog, then the top route.
fn back() -> bool {
    let Some(w) = crate::app::window() else { return false };
    let nav = w.global::<Nav>();
    if nav.get_menu_open() {
        close_menu();
        return true;
    }
    if nav.get_dialog_open() {
        dismiss_dialog();
        return true;
    }
    if crate::pages::back() {
        return true;
    }
    if routes().row_count() > 0 {
        maybe_pop();
        return true;
    }
    false
}

pub fn refocus() {
    with_window(|w| {
        let nav = w.global::<Nav>();
        nav.set_focus_tick(nav.get_focus_tick() + 1);
    });
}

pub fn select_tab(label: &str) {
    with_window(|w| w.global::<Nav>().set_tab(label.into()));
    crate::pages::mark(crate::pages::Mark::ALL);
}

// ---------------------------------------------------------------------------
// Menus (PopupMenuItem)
// ---------------------------------------------------------------------------

#[derive(Clone, Default)]
pub struct MenuEntry {
    pub label: String,
    pub icon: Option<&'static str>,
    pub checked: bool,
    pub danger: bool,
    pub section: Option<String>,
    pub sub: Vec<MenuEntry>,
    pub action: Option<Rc<dyn Fn()>>,
}

impl MenuEntry {
    pub fn new(label: &str, icon: Option<&'static str>, action: Option<Rc<dyn Fn()>>) -> Self {
        MenuEntry { label: tr(label), icon, action, ..Default::default() }
    }

    pub fn act(label: &str, icon: &'static str, f: impl Fn() + 'static) -> Self {
        Self::new(label, Some(icon), Some(Rc::new(f)))
    }

    pub fn plain(label: &str, f: impl Fn() + 'static) -> Self {
        Self::new(label, None, Some(Rc::new(f)))
    }

    pub fn disabled(label: &str, icon: &'static str) -> Self {
        Self::new(label, Some(icon), None)
    }

    pub fn when(self, enabled: bool) -> Self {
        if enabled {
            self
        } else {
            MenuEntry { action: None, ..self }
        }
    }

    pub fn checked(mut self, checked: bool) -> Self {
        self.checked = checked;
        self
    }

    pub fn danger(mut self) -> Self {
        self.danger = true;
        self
    }

    /// Starts a new group; `title` empty for a plain divider.
    pub fn section(mut self, title: &str) -> Self {
        self.section = Some(tr(title));
        self
    }

    pub fn submenu(label: &str, icon: &'static str, sub: Vec<MenuEntry>) -> Self {
        MenuEntry { label: tr(label), icon: Some(icon), sub, ..Default::default() }
    }
}

fn show_menu(entries: &[MenuEntry], parent: Option<&str>) {
    let mut items: Vec<MenuItem> = Vec::new();
    if let Some(parent) = parent {
        items.push(MenuItem { label: parent.into(), glyph: glyph("back"), has_glyph: true, enabled: true, ..Default::default() });
    }
    for (i, e) in entries.iter().enumerate() {
        let section = e.section.clone().unwrap_or_default();
        let divider = (parent.is_some() && i == 0) || (e.section.is_some() && section.is_empty() && i > 0);
        if !section.is_empty() {
            items.push(MenuItem { section: section.as_str().into(), divider: i > 0 || parent.is_some(), ..Default::default() });
        }
        items.push(MenuItem {
            label: e.label.as_str().into(),
            glyph: e.icon.map(glyph).unwrap_or_default(),
            has_glyph: e.icon.is_some(),
            checked: e.checked,
            enabled: e.action.is_some() || !e.sub.is_empty(),
            danger: e.danger,
            section: SharedString::new(),
            divider: divider && section.is_empty(),
            submenu: !e.sub.is_empty(),
        });
    }
    with_window(|w| {
        let nav = w.global::<Nav>();
        nav.set_menu_items(ModelRc::new(VecModel::from(items)));
        nav.set_menu_open(true);
    });
}

/// Opens a menu at (x, y).
pub fn open_menu_at(entries: Vec<MenuEntry>, x: f32, y: f32) {
    if entries.is_empty() {
        return;
    }
    show_menu(&entries, None);
    NAV.with(|n| {
        let mut n = n.borrow_mut();
        n.menu = entries;
        n.menu_stack.clear();
    });
    with_window(|w| {
        let nav = w.global::<Nav>();
        nav.set_menu_x(x);
        nav.set_menu_y(y);
    });
}

fn close_menu() {
    NAV.with(|n| {
        let mut n = n.borrow_mut();
        n.menu.clear();
        n.menu_stack.clear();
    });
    with_window(|w| w.global::<Nav>().set_menu_open(false));
    refocus();
}

fn menu_pick(index: i32) {
    let (shown, has_parent) = NAV.with(|n| {
        let n = n.borrow();
        (n.menu_stack.last().cloned().unwrap_or_else(|| n.menu.clone()), !n.menu_stack.is_empty())
    });
    let mut index = index as usize;
    // Item rows only: the section headers sit between them.
    let mut rows: Vec<Option<usize>> = Vec::new();
    if has_parent {
        rows.push(None);
    }
    for (i, e) in shown.iter().enumerate() {
        if e.section.as_ref().map(|s| !s.is_empty()).unwrap_or(false) {
            rows.push(Some(usize::MAX));
        }
        rows.push(Some(i));
    }
    let Some(slot) = rows.get(index).copied() else { return };
    match slot {
        None => {
            // The parent row: back to the outer menu.
            NAV.with(|n| n.borrow_mut().menu_stack.pop());
            let (shown, parent) = NAV.with(|n| {
                let n = n.borrow();
                (n.menu_stack.last().cloned().unwrap_or_else(|| n.menu.clone()), n.menu_stack.len())
            });
            show_menu(&shown, if parent > 0 { Some("") } else { None });
        }
        Some(usize::MAX) => {}
        Some(i) => {
            index = i;
            let entry = shown[index].clone();
            if !entry.sub.is_empty() {
                NAV.with(|n| n.borrow_mut().menu_stack.push(entry.sub.clone()));
                show_menu(&entry.sub, Some(&entry.label));
            } else if let Some(action) = entry.action {
                close_menu();
                action();
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Dialogs (DialogHost)
// ---------------------------------------------------------------------------

pub type Validator = Box<dyn Fn(&str) -> Option<String>>;

enum DialogKind {
    Confirm(Box<dyn FnOnce(bool)>),
    Choice(Vec<Option<String>>, Box<dyn FnOnce(Option<String>)>),
    Input { reset: Option<String>, validator: Option<Validator>, done: Box<dyn FnOnce(Option<String>)>, touched: bool },
    Options(Box<dyn FnOnce(Option<usize>)>),
}

struct Dialog {
    title: String,
    message: String,
    mono: bool,
    actions: Vec<String>,
    danger: bool,
    input: Option<(String, bool, String, bool)>,
    options: Vec<String>,
    option: i32,
    kind: DialogKind,
}

fn show_dialog() {
    NAV.with(|n| {
        let n = n.borrow();
        with_window(|w| {
            let nav = w.global::<Nav>();
            match n.dialogs.last() {
                None => nav.set_dialog_open(false),
                Some(d) => {
                    nav.set_dialog_title(d.title.as_str().into());
                    nav.set_dialog_message(d.message.as_str().into());
                    nav.set_dialog_mono(d.mono);
                    nav.set_dialog_actions(ModelRc::new(VecModel::from(d.actions.iter().map(ss).collect::<Vec<_>>())));
                    nav.set_dialog_danger(d.danger);
                    nav.set_dialog_options(ModelRc::new(VecModel::from(d.options.iter().map(ss).collect::<Vec<_>>())));
                    nav.set_dialog_option(d.option);
                    nav.set_dialog_error(SharedString::new());
                    match &d.input {
                        Some((value, kind, suffix, multiline)) => {
                            nav.set_dialog_input(true);
                            nav.set_dialog_text(value.as_str().into());
                            nav.set_dialog_number(*kind);
                            nav.set_dialog_suffix(suffix.as_str().into());
                            nav.set_dialog_multiline(*multiline);
                        }
                        None => {
                            nav.set_dialog_input(false);
                            nav.set_dialog_text(SharedString::new());
                            nav.set_dialog_suffix(SharedString::new());
                            nav.set_dialog_multiline(false);
                        }
                    }
                    nav.set_dialog_open(true);
                }
            }
        });
    });
}

fn push_dialog(d: Dialog) {
    NAV.with(|n| n.borrow_mut().dialogs.push(d));
    show_dialog();
}

fn take_dialog() -> Option<Dialog> {
    let d = NAV.with(|n| n.borrow_mut().dialogs.pop());
    show_dialog();
    refocus();
    d
}

/// Cancel and `action`; `done(true)` only for `action`.
pub fn confirm(title: &str, message: Option<&str>, action: &str, done: impl FnOnce(bool) + 'static) {
    confirm_styled(title, message, action, true, done)
}

pub fn confirm_styled(title: &str, message: Option<&str>, action: &str, danger: bool, done: impl FnOnce(bool) + 'static) {
    push_dialog(Dialog {
        title: tr(title),
        message: message.map(tr).unwrap_or_default(),
        mono: false,
        actions: vec![tr("Cancel"), tr(action)],
        danger: danger && matches!(action, "Delete" | "Discard" | "Remove" | "Clear" | "Reset" | "Log out"),
        input: None,
        options: Vec::new(),
        option: -1,
        kind: DialogKind::Confirm(Box::new(done)),
    });
}

/// A dialog with its own actions: the picked action's value, or None when dismissed.
pub fn choose(title: &str, message: Option<&str>, actions: Vec<(&str, Option<&str>)>, done: impl FnOnce(Option<String>) + 'static) {
    push_dialog(Dialog {
        title: tr(title),
        message: message.unwrap_or_default().to_string(),
        mono: false,
        actions: actions.iter().map(|(l, _)| tr(l)).collect(),
        danger: false,
        input: None,
        options: Vec::new(),
        option: -1,
        kind: DialogKind::Choice(actions.iter().map(|(_, v)| v.map(str::to_string)).collect(), Box::new(done)),
    });
}

/// A message with a single close button (SelectionContainer text).
pub fn show_text(title: &str, message: &str, mono: bool) {
    push_dialog(Dialog {
        title: title.to_string(),
        message: message.to_string(),
        mono,
        actions: vec![tr("Close")],
        danger: false,
        input: None,
        options: Vec::new(),
        option: -1,
        kind: DialogKind::Choice(vec![None], Box::new(|_| {})),
    });
}

pub struct InputSpec {
    pub title: String,
    pub value: String,
    pub number: bool,
    pub suffix: String,
    pub reset: Option<String>,
    pub multiline: bool,
    pub validator: Option<Validator>,
}

impl InputSpec {
    pub fn new(title: &str, value: &str) -> Self {
        InputSpec { title: tr(title), value: value.to_string(), number: false, suffix: String::new(), reset: None, multiline: false, validator: None }
    }
}

/// A text field: the text, or None when cancelled.
pub fn input(spec: InputSpec, done: impl FnOnce(Option<String>) + 'static) {
    let first = match &spec.reset {
        Some(reset) if *reset != spec.value => tr("Reset"),
        _ => tr("Cancel"),
    };
    push_dialog(Dialog {
        title: spec.title,
        message: String::new(),
        mono: false,
        actions: vec![first, tr("Confirm")],
        danger: false,
        input: Some((spec.value, spec.number, spec.suffix, spec.multiline)),
        options: Vec::new(),
        option: -1,
        kind: DialogKind::Input { reset: spec.reset, validator: spec.validator, done: Box::new(done), touched: false },
    });
}

/// A choice among `options` (a radio list); the picked index, or None.
pub fn pick(title: &str, options: Vec<String>, selected: Option<usize>, done: impl FnOnce(Option<usize>) + 'static) {
    push_dialog(Dialog {
        title: tr(title),
        message: String::new(),
        mono: false,
        actions: vec![tr("Cancel")],
        danger: false,
        input: None,
        options,
        option: selected.map(|s| s as i32).unwrap_or(-1),
        kind: DialogKind::Options(Box::new(done)),
    });
}

fn dismiss_dialog() {
    if let Some(d) = take_dialog() {
        match d.kind {
            DialogKind::Confirm(done) => done(false),
            DialogKind::Choice(_, done) => done(None),
            DialogKind::Input { done, .. } => done(None),
            DialogKind::Options(done) => done(None),
        }
    }
}

fn dialog_action(index: i32, text: SharedString) {
    let index = index as usize;
    let is_input = NAV.with(|n| matches!(n.borrow().dialogs.last().map(|d| &d.kind), Some(DialogKind::Input { .. })));
    if is_input {
        // The last action submits after validation; the first resets or cancels.
        let error = NAV.with(|n| {
            let mut n = n.borrow_mut();
            let Some(d) = n.dialogs.last_mut() else { return None };
            if let DialogKind::Input { validator, touched, .. } = &mut d.kind {
                *touched = true;
                if index == d.actions.len() - 1 {
                    return validator.as_ref().and_then(|v| v(text.as_str()));
                }
            }
            None
        });
        if let Some(error) = error {
            with_window(|w| w.global::<Nav>().set_dialog_error(error.into()));
            return;
        }
        if let Some(d) = take_dialog() {
            let actions = d.actions.len();
            if let DialogKind::Input { reset, done, .. } = d.kind {
                if index == actions - 1 {
                    done(Some(text.to_string()))
                } else {
                    match reset {
                        Some(reset) if *d.actions.first().unwrap_or(&String::new()) == tr("Reset") => done(Some(reset)),
                        _ => done(None),
                    }
                }
            }
        }
        return;
    }
    if let Some(d) = take_dialog() {
        match d.kind {
            DialogKind::Confirm(done) => done(index == 1),
            DialogKind::Choice(values, done) => done(values.get(index).cloned().flatten()),
            DialogKind::Input { .. } => {}
            DialogKind::Options(done) => done(None),
        }
    }
}

fn dialog_edited(text: SharedString) {
    let error = NAV.with(|n| {
        let n = n.borrow();
        match n.dialogs.last().map(|d| &d.kind) {
            Some(DialogKind::Input { validator: Some(v), .. }) => v(text.as_str()),
            _ => None,
        }
    });
    with_window(|w| w.global::<Nav>().set_dialog_error(error.unwrap_or_default().into()));
}

fn dialog_pick(index: i32) {
    if let Some(d) = take_dialog() {
        if let DialogKind::Options(done) = d.kind {
            done(Some(index as usize));
        }
    }
}

// ---------------------------------------------------------------------------
// The snackbar: errors stay longer; a new message replaces the current one.
// ---------------------------------------------------------------------------

pub fn toast(text: &str, error: bool) {
    let text = text.trim().to_string();
    if text.is_empty() {
        return;
    }
    let serial = NAV.with(|n| {
        let mut n = n.borrow_mut();
        n.toast_serial += 1;
        n.toast_serial
    });
    with_window(|w| {
        let nav = w.global::<Nav>();
        nav.set_toast(text.as_str().into());
        nav.set_toast_error(error);
    });
    Timer::single_shot(Duration::from_millis(if error { 10_000 } else { 4_000 }), move || {
        if NAV.with(|n| n.borrow().toast_serial) == serial {
            with_window(|w| w.global::<Nav>().set_toast(SharedString::new()));
        }
    });
}

pub fn install(window: &AppWindow) {
    let model = Rc::new(VecModel::<Route>::default());
    NAV.with(|n| n.borrow_mut().routes = Some(model.clone()));
    let nav = window.global::<Nav>();
    nav.set_routes(ModelRc::from(model));
    nav.on_select_tab(|label| select_tab(&label));
    nav.on_push(|kind, arg| {
        push(&kind, &arg);
    });
    nav.on_form(|kind, arg| {
        form(&kind, &arg);
    });
    nav.on_pop(maybe_pop);
    nav.on_back(back);
    nav.on_dismiss_form(dismiss_form);
    nav.on_menu_dismiss(close_menu);
    nav.on_menu_pick(menu_pick);
    nav.on_dialog_action(dialog_action);
    nav.on_dialog_edited(dialog_edited);
    nav.on_dialog_pick(dialog_pick);
    nav.on_dialog_dismiss(dismiss_dialog);
    nav.on_dismiss_toast(|| with_window(|w| w.global::<Nav>().set_toast(SharedString::new())));
    nav.on_open_menu(|key, x, y| {
        let entries = crate::pages::menu(&key);
        open_menu_at(entries, x, y);
    });
}
