//! Remote control server addresses and the server form's save.
//!
//! An address is kept scheme-less for http (the default) and keeps an
//! explicit `https://`; the form passed to libbox always has a scheme.
//! Validation follows `java.net.URI` as the Kotlin `RemoteServer` used it
//! (through [`crate::geo::valid_url`]), so addresses stored before keep
//! their meaning.

use crate::storage::{RemoteServer, Store};

fn trimmed(value: &str) -> &str {
    value.trim().trim_end_matches('/')
}

fn has_scheme(value: &str) -> bool {
    let lower = value.get(..8).unwrap_or(value).to_ascii_lowercase();
    lower.starts_with("http://") || lower.starts_with("https://")
}

/// The stored form: trimmed, no trailing slash, no `http://`.
pub fn normalize_url(value: &str) -> String {
    let value = trimmed(value);
    if value.len() >= 7 && value.is_char_boundary(7) && value[..7].eq_ignore_ascii_case("http://") {
        value[7..].to_string()
    } else {
        value.to_string()
    }
}

/// The form passed to libbox: a scheme is required, defaulting to http.
/// Empty for an empty address.
pub fn connect_url(value: &str) -> String {
    let value = trimmed(value);
    if value.is_empty() || has_scheme(value) {
        value.to_string()
    } else {
        format!("http://{value}")
    }
}

/// The stored form of a valid http(s) address with a host, None otherwise.
pub fn validate_url(value: &str) -> Option<String> {
    let connect = connect_url(value);
    if connect.is_empty() {
        return None;
    }
    // java.net.URI compares the scheme case-insensitively.
    let (scheme, rest) = connect.split_once("://")?;
    if !crate::geo::valid_url(&format!("{}://{rest}", scheme.to_ascii_lowercase())) {
        return None;
    }
    Some(normalize_url(value))
}

/// `host` or `host:port` of an address, the address itself when it has no
/// valid host.
fn host_port(value: &str) -> String {
    if validate_url(value).is_none() {
        return value.to_string();
    }
    let connect = connect_url(value);
    let rest = connect.split_once("://").map_or(connect.as_str(), |(_, rest)| rest);
    let authority = &rest[..rest.find(['/', '?', '#']).unwrap_or(rest.len())];
    let host_port = authority.rsplit_once('@').map_or(authority, |(_, h)| h);
    let (host, port) = if host_port.starts_with('[') {
        match host_port.find(']') {
            Some(close) => (&host_port[..=close], host_port[close + 1..].strip_prefix(':')),
            None => (host_port, None),
        }
    } else {
        match host_port.split_once(':') {
            Some((host, port)) => (host, Some(port)),
            None => (host_port, None),
        }
    };
    match port.filter(|p| !p.is_empty()) {
        Some(port) => format!("{host}:{port}"),
        None => host.to_string(),
    }
}

/// The name a server is shown by: its name, else its host and port.
pub fn display_name(name: &str, url: &str) -> String {
    if name.is_empty() {
        host_port(url)
    } else {
        name.to_string()
    }
}

/// Saves the server form: creates the server when `id` is 0 (last in the
/// list), otherwise edits it. Returns its id.
pub fn save(store: &Store, id: i64, name: &str, url: &str, secret: &str) -> Result<i64, String> {
    let e = |error: crate::storage::Error| error.0;
    let url = validate_url(url).ok_or("Invalid server address")?;
    if id == 0 {
        let server = RemoteServer {
            id: 0,
            user_order: store.next_remote_server_order().map_err(e)?,
            name: name.to_string(),
            url,
            secret: secret.to_string(),
        };
        return store.insert_remote_server(&server).map_err(e);
    }
    let mut server = store.remote_server(id).map_err(e)?.ok_or("Server no longer exists")?;
    server.name = name.to_string();
    server.url = url;
    server.secret = secret.to_string();
    store.update_remote_server(&server).map_err(e)?;
    Ok(id)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn addresses_are_normalized_and_connected_with_a_scheme() {
        assert_eq!(normalize_url(" HTTP://h:9090/ "), "h:9090");
        assert_eq!(normalize_url("https://h/"), "https://h");
        assert_eq!(connect_url("h:9090"), "http://h:9090");
        assert_eq!(connect_url("HTTPS://h"), "HTTPS://h");
        assert_eq!(connect_url("  "), "");
        assert_eq!(validate_url("10.0.0.1:9090").as_deref(), Some("10.0.0.1:9090"));
        assert_eq!(validate_url("HTTPS://h/").as_deref(), Some("HTTPS://h"));
        assert_eq!(validate_url("a b"), None);
        assert_eq!(validate_url(""), None);
    }

    #[test]
    fn servers_are_shown_by_name_or_host_and_port() {
        assert_eq!(display_name("Home", "h:1"), "Home");
        assert_eq!(display_name("", "10.0.0.1:9090"), "10.0.0.1:9090");
        assert_eq!(display_name("", "https://user@h/path"), "h");
        assert_eq!(display_name("", "[::1]:9090"), "[::1]:9090");
        assert_eq!(display_name("", "a b"), "a b");
    }

    #[test]
    fn the_form_creates_and_edits() {
        let store = Store::open_in_memory().unwrap();
        let first = save(&store, 0, "a", "http://h:1/", "s").unwrap();
        let second = save(&store, 0, "b", "h:2", "").unwrap();
        let servers = store.remote_servers().unwrap();
        assert_eq!(servers.iter().map(|s| (s.id, s.url.as_str(), s.user_order)).collect::<Vec<_>>(), [(first, "h:1", 0), (second, "h:2", 1)]);
        assert_eq!(save(&store, first, "a2", "https://h", "t").unwrap(), first);
        let edited = store.remote_server(first).unwrap().unwrap();
        assert_eq!((edited.name.as_str(), edited.url.as_str(), edited.secret.as_str(), edited.user_order), ("a2", "https://h", "t", 0));
        assert_eq!(save(&store, first, "a", "a b", "").unwrap_err(), "Invalid server address");
        assert_eq!(save(&store, 999, "a", "h", "").unwrap_err(), "Server no longer exists");
    }
}
