//! Proxy groups as the platform reports them (libbox `OutboundGroup`:
//! `{"tag", "type", "selectable", "selected", "expanded", "items": [{"tag",
//! "type", "delay", "testedAt"}]}`), and the checks a selection, a URL test
//! or a mode switch must pass before the core is asked.

use serde_json::Value;

fn text<'a>(value: &'a Value, key: &str) -> &'a str {
    value.get(key).and_then(Value::as_str).unwrap_or("")
}

fn items(group: &Value) -> &[Value] {
    group.get("items").and_then(Value::as_array).map(Vec::as_slice).unwrap_or(&[])
}

pub fn find<'a>(groups: &'a [Value], tag: &str) -> Option<&'a Value> {
    groups.iter().find(|g| text(g, "tag") == tag)
}

/// Selecting `tag` in `group`: the group must exist, be a selector and hold
/// the node.
pub fn check_select(groups: &[Value], group: &str, tag: &str) -> Result<(), &'static str> {
    let found = find(groups, group).ok_or("Group no longer exists")?;
    if found.get("selectable").and_then(Value::as_bool) != Some(true) {
        return Err("This group is selected automatically by sing-box");
    }
    if !items(found).iter().any(|i| text(i, "tag") == tag) {
        return Err("Node no longer exists");
    }
    Ok(())
}

/// A URL test of a group or of one node in any group.
pub fn check_url_test(groups: &[Value], tag: &str) -> Result<(), &'static str> {
    let known = groups.iter().any(|g| text(g, "tag") == tag || items(g).iter().any(|i| text(i, "tag") == tag));
    if known { Ok(()) } else { Err("Node no longer exists") }
}

/// A routing mode must be one the configuration offers (the dashboard's
/// `modes`, empty while switching is unavailable).
pub fn check_mode(dashboard: &Value, mode: &str) -> Result<(), &'static str> {
    let offered = dashboard.get("modes").and_then(Value::as_array).is_some_and(|m| m.iter().any(|v| v.as_str() == Some(mode)));
    if offered { Ok(()) } else { Err("This routing mode is not provided by the configuration") }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn groups() -> Vec<Value> {
        vec![
            json!({"tag": "Proxy", "selectable": true, "selected": "a", "items": [{"tag": "a"}, {"tag": "b"}]}),
            json!({"tag": "Auto", "selectable": false, "selected": "c", "items": [{"tag": "c"}]}),
        ]
    }

    #[test]
    fn select_checks() {
        let g = groups();
        assert_eq!(check_select(&g, "Proxy", "b"), Ok(()));
        assert_eq!(check_select(&g, "Gone", "b"), Err("Group no longer exists"));
        assert_eq!(check_select(&g, "Auto", "c"), Err("This group is selected automatically by sing-box"));
        assert_eq!(check_select(&g, "Proxy", "z"), Err("Node no longer exists"));
    }

    #[test]
    fn url_test_accepts_groups_and_nodes() {
        let g = groups();
        assert_eq!(check_url_test(&g, "Auto"), Ok(()));
        assert_eq!(check_url_test(&g, "c"), Ok(()));
        assert!(check_url_test(&g, "z").is_err());
        assert!(check_url_test(&[], "Proxy").is_err());
    }

    #[test]
    fn mode_must_be_offered() {
        let d = json!({"modes": ["Rule", "Global"]});
        assert_eq!(check_mode(&d, "Global"), Ok(()));
        assert!(check_mode(&d, "Direct").is_err());
        assert!(check_mode(&json!({"modes": []}), "Rule").is_err());
        assert!(check_mode(&json!({}), "Rule").is_err());
    }
}
