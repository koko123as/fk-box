//! Shared editors (ui/QuickEdit.kt, pages/Configuration.kt ConfigValueEditor,
//! ui/Forms.kt ListItemInput / ListItemOptions): Rust-built pages with a
//! guard against losing changes.

use std::cell::RefCell;
use std::rc::Rc;

use serde_json::Value;

use crate::i18n::tr;
use crate::nav;
use crate::pages::{mark, Mark};
use crate::rows::{self, Bar, Page, Row};

/// The result an apply step reports: Ok closes the editor, Err(None) keeps
/// it open silently (QuickEditCancelled), Err(Some) shows the message.
pub type Done = Box<dyn FnOnce(Result<(), Option<String>>)>;
pub type Apply = Rc<dyn Fn(String, Done)>;

const LINE_SEPARATORS: [&str; 2] = ["\r\n", "\n"];

fn lines(text: &str) -> Vec<&str> {
    let normalized: Vec<&str> = text.split('\n').map(|l| l.strip_suffix('\r').unwrap_or(l)).collect();
    let _ = LINE_SEPARATORS;
    normalized
}

fn strip_markup(line: &str) -> String {
    let mut v = line.trim().to_string();
    if let Some(rest) = v.strip_prefix("- ") {
        v = rest.trim().to_string();
    }
    if let Some(rest) = v.strip_suffix(',') {
        v = rest.trim().to_string();
    }
    v
}

fn unquote(v: &str) -> String {
    let mut chars = v.chars();
    match (chars.next(), v.chars().last()) {
        (Some(f), Some(l)) if v.len() >= 2 && (f == '"' || f == '\'') && f == l => v[1..v.len() - 1].trim().to_string(),
        _ => v.to_string(),
    }
}

pub struct Issue {
    pub line: usize,
    pub raw: String,
    pub too_long: bool,
}

/// parseListInput: one item a line (commas also split), blanks and repeats dropped.
pub fn parse_list(text: &str, max_length: Option<usize>, valid: Option<&dyn Fn(&str) -> bool>) -> (Vec<String>, Vec<Issue>) {
    let mut entries = Vec::new();
    let mut issues = Vec::new();
    for (i, raw_line) in lines(text).into_iter().enumerate() {
        for raw in strip_markup(raw_line).split([',', '，']) {
            let value = unquote(raw.trim());
            if value.is_empty() {
                continue;
            }
            if max_length.map(|m| value.chars().count() > m).unwrap_or(false) {
                issues.push(Issue { line: i + 1, raw: value, too_long: true });
                continue;
            }
            if let Some(v) = valid {
                if !v(&value) {
                    issues.push(Issue { line: i + 1, raw: value, too_long: false });
                    continue;
                }
            }
            if !entries.contains(&value) {
                entries.push(value);
            }
        }
    }
    (entries, issues)
}

/// parseLineInput: each non-blank line whole, with its 1-based number.
pub fn parse_lines(text: &str) -> Vec<(usize, String)> {
    lines(text).into_iter().enumerate().filter_map(|(i, l)| {
        let t = l.trim();
        if t.is_empty() {
            None
        } else {
            Some((i + 1, t.to_string()))
        }
    }).collect()
}

pub fn line_issue(line: usize, message: &str) -> String {
    tr("Line %1$d: %2$s").replace("%1$d", &line.to_string()).replace("%2$s", message)
}

struct EditorState {
    text: String,
    original: String,
    saving: bool,
    error: Option<String>,
}

/// QuickEditPage: a list edited as text; an unchanged text just closes.
pub fn quick_edit(title: &str, content: &str, hint: Option<&str>, apply: Apply) {
    let state = Rc::new(RefCell::new(EditorState { text: content.into(), original: content.into(), saving: false, error: None }));
    let title = title.to_string();
    let hint = hint.unwrap_or_default().to_string();
    let st = state.clone();
    let id_cell = Rc::new(RefCell::new(0));
    let idc = id_cell.clone();
    let id = rows::form(move || {
        let s = st.borrow();
        let save = {
            let st = st.clone();
            let apply = apply.clone();
            let idc = idc.clone();
            move || {
                let (text, original, saving) = {
                    let s = st.borrow();
                    (s.text.clone(), s.original.clone(), s.saving)
                };
                if saving {
                    return;
                }
                let id = *idc.borrow();
                if text == original {
                    nav::close(id);
                    return;
                }
                st.borrow_mut().saving = true;
                mark(Mark::ROWS);
                let st2 = st.clone();
                apply(
                    text,
                    Box::new(move |result| {
                        st2.borrow_mut().saving = false;
                        mark(Mark::ROWS);
                        match result {
                            Ok(()) => nav::close(id),
                            Err(None) => {}
                            Err(Some(message)) => {
                                let body = format!("{message}\n\n{}", tr("Discard changes?"));
                                nav::confirm("Quick edit", Some(&body), "Discard", move |ok| {
                                    if ok {
                                        nav::close(id)
                                    }
                                });
                            }
                        }
                    }),
                );
            }
        };
        let st_edit = st.clone();
        Page::new(&title)
            .loading(s.saving)
            .confirm(Bar::new("check", "Save", save).when(!s.saving))
            .rows(vec![
                Row::editor("", &s.text, 12, move |t| st_edit.borrow_mut().text = t).hint(&hint).enabled(!s.saving),
            ])
    });
    *id_cell.borrow_mut() = id;
    let st = state.clone();
    nav::guard(id, move || {
        let s = st.borrow();
        s.text != s.original
    });
    let st = state;
    nav::busy_guard(id, move || st.borrow().saving);
    let _ = (); // the error lives in the dialog
}

/// ListQuickEditPage: one item a line, validated.
pub fn list_quick_edit(
    title: &str,
    items: Vec<String>,
    hint: Option<&str>,
    max_length: Option<usize>,
    validator: Option<Rc<dyn Fn(&str) -> Option<String>>>,
    on_changed: Rc<dyn Fn(Vec<String>, Done)>,
) {
    let apply: Apply = Rc::new(move |content: String, done: Done| {
        let v = validator.clone();
        let check = v.as_ref().map(|v| {
            let v = v.clone();
            move |s: &str| v(s).is_none()
        });
        let (entries, issues) = match &check {
            Some(c) => parse_list(&content, max_length, Some(c)),
            None => parse_list(&content, max_length, None),
        };
        if !issues.is_empty() {
            let message = issues
                .iter()
                .take(3)
                .map(|i| {
                    let m = if i.too_long {
                        tr("Value must be at most %d characters").replace("%d", &max_length.unwrap_or(0).to_string())
                    } else {
                        v.as_ref().and_then(|v| v(&i.raw)).unwrap_or_else(|| tr("Invalid value"))
                    };
                    line_issue(i.line, &m)
                })
                .collect::<Vec<_>>()
                .join("\n");
            done(Err(Some(message)));
            return;
        }
        on_changed(entries, done);
    });
    quick_edit(title, &items.join("\n"), hint, apply);
}

pub fn encode(value: &Value) -> String {
    serde_json::to_string_pretty(value).unwrap_or_default()
}

pub fn encode_line(value: &Value) -> String {
    serde_json::to_string(value).unwrap_or_default()
}

pub fn decode(text: &str) -> Result<Value, String> {
    serde_json::from_str::<Value>(text).map_err(|e| e.to_string())
}

/// ConfigValueEditor: a JSON value in an outlined monospace field.
pub fn value_editor(title: &str, initial: &Value, on_save: Rc<dyn Fn(Value, Done)>) {
    let original = encode(initial);
    let state = Rc::new(RefCell::new(EditorState { text: original.clone(), original, saving: false, error: None }));
    let title = tr(title);
    let st = state.clone();
    let idc = Rc::new(RefCell::new(0));
    let idc2 = idc.clone();
    let id = rows::form(move || {
        let s = st.borrow();
        let save = {
            let st = st.clone();
            let on_save = on_save.clone();
            let idc = idc2.clone();
            move || {
                if st.borrow().saving {
                    return;
                }
                let text = st.borrow().text.clone();
                match decode(&text) {
                    Err(e) => {
                        st.borrow_mut().error = Some(e);
                        mark(Mark::ROWS);
                    }
                    Ok(value) => {
                        {
                            let mut s = st.borrow_mut();
                            s.saving = true;
                            s.error = None;
                        }
                        mark(Mark::ROWS);
                        let st2 = st.clone();
                        let id = *idc.borrow();
                        on_save(
                            value,
                            Box::new(move |r| {
                                st2.borrow_mut().saving = false;
                                match r {
                                    Ok(()) => nav::close(id),
                                    Err(None) => {}
                                    Err(Some(e)) => st2.borrow_mut().error = Some(e),
                                }
                                mark(Mark::ROWS);
                            }),
                        );
                    }
                }
            }
        };
        let mut rows_out = Vec::new();
        if let Some(e) = &s.error {
            rows_out.push(Row::error(e));
            rows_out.push(Row::spacer(12));
        }
        let st_edit = st.clone();
        rows_out.push(Row::editor(&tr("Configuration"), &s.text, 12, move |t| st_edit.borrow_mut().text = t).enabled(!s.saving));
        Page::new(&title).loading(s.saving).confirm(Bar::new("check", "Save", save).when(!s.saving)).rows(rows_out)
    });
    *idc.borrow_mut() = id;
    let st = state.clone();
    nav::guard(id, move || {
        let s = st.borrow();
        s.text != s.original
    });
    let st = state;
    nav::busy_guard(id, move || st.borrow().saving);
}

/// ListItemOptions: a row whose value opens a choice dialog.
pub fn options_row(title: &str, options: Vec<String>, current: &str, label: Rc<dyn Fn(&str) -> String>, on_changed: Rc<dyn Fn(String)>) -> Row {
    let shown = label(current);
    let title_owned = title.to_string();
    let current = current.to_string();
    Row::item(title).value(&shown).on(move |_| {
        let labels: Vec<String> = options.iter().map(|o| label(o)).collect();
        let selected = options.iter().position(|o| *o == current);
        let options = options.clone();
        let f = on_changed.clone();
        nav::pick(&title_owned, labels, selected, move |picked| {
            if let Some(i) = picked {
                f(options[i].clone())
            }
        });
    })
}

/// ListItemInput: a row whose value opens an input dialog.
pub fn input_row(title: &str, value: &str, number: bool, suffix: &str, validator: Option<Rc<dyn Fn(&str) -> Option<String>>>, on_changed: Rc<dyn Fn(String)>) -> Row {
    let title_owned = title.to_string();
    let value_owned = value.to_string();
    let suffix = suffix.to_string();
    Row::item(title).value(&format!("{value}{}", if suffix.is_empty() { String::new() } else { format!(" {suffix}") })).on(move |_| {
        let mut spec = nav::InputSpec::new(&title_owned, &value_owned);
        spec.title = title_owned.clone();
        spec.number = number;
        spec.suffix = suffix.clone();
        if let Some(v) = validator.clone() {
            spec.validator = Some(Box::new(move |s: &str| v(s)));
        }
        let f = on_changed.clone();
        nav::input(spec, move |r| {
            if let Some(v) = r {
                f(v)
            }
        });
    })
}
