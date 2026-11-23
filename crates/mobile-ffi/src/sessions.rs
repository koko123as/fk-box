//! Which libbox command subscriptions should run (phase 3).
//!
//! The host (Android: `utils/CommandSessions.kt`) reports what it knows about
//! the app and the service; [`plan`] answers, for every subscription the UI
//! uses, whether it should be connected and to which target. The host keeps
//! the libbox command clients and reconnects a subscription whenever its
//! [`Target`] changes, so a new remote server, a reloaded local configuration
//! or a page shown again renews the stream.
//!
//! The rules were the per-ViewModel `combine` blocks in Kotlin:
//! - every stream needs the app in the foreground and the service reachable
//!   (a remote session: connected; locally: started);
//! - the per-second status and the connection list also need the screen on
//!   and a page that shows them;
//! - the log stream needs the Logs page and, locally, a loaded configuration
//!   (libbox reports the default level when subscribing, so a reload renews it);
//! - the remote monitor runs for a remote session in the foreground, whether or
//!   not the remote service is reachable yet: it is what finds out.

use serde::{Deserialize, Serialize};

/// What the host reports. Missing fields read as false / none.
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Inputs {
    pub foreground: bool,
    pub screen_on: bool,
    /// The local service has started.
    pub service_started: bool,
    /// The remote server being controlled, if any.
    pub remote_server_id: Option<i64>,
    /// The remote session's monitor is connected.
    pub remote_connected: bool,
    /// A token for the loaded local configuration (changes on every load or
    /// reload); none until one is loaded.
    pub local_config: Option<i64>,
    /// A page shows the per-second status (rates, totals, memory).
    pub traffic_visible: bool,
    /// How many pages show the connection list or its counts.
    pub connections_visible: u32,
    /// The Logs page is shown.
    pub logs_visible: bool,
}

/// One subscription's wanted state. Two equal targets mean "leave it".
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Target {
    pub connect: bool,
    pub remote_server_id: Option<i64>,
    /// Only the local log stream follows the configuration.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub local_config: Option<i64>,
}

/// Every subscription's target.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Plan {
    pub clash_mode: Target,
    pub status: Target,
    pub groups: Target,
    pub connections: Target,
    pub log: Target,
    pub remote_monitor: Target,
    /// The tool status subscriptions (Tailscale, OpenConnect, OpenVPN,
    /// USBIP) and the outbound list the tool pages offer.
    pub tool_status: Target,
}

fn target(connect: bool, remote: Option<i64>) -> Target {
    Target { connect, remote_server_id: remote, local_config: None }
}

/// The subscriptions `inputs` call for.
pub fn plan(inputs: &Inputs) -> Plan {
    let remote = inputs.remote_server_id;
    let ready = if remote.is_some() { inputs.remote_connected } else { inputs.service_started };
    let live = inputs.foreground && ready;
    let awake = live && inputs.screen_on;
    let log_ready = if remote.is_some() { inputs.remote_connected } else { inputs.service_started && inputs.local_config.is_some() };
    Plan {
        clash_mode: target(live, remote),
        status: target(awake && inputs.traffic_visible, remote),
        groups: target(live, remote),
        connections: target(awake && inputs.connections_visible > 0, remote),
        log: Target {
            connect: inputs.foreground && log_ready && inputs.logs_visible,
            remote_server_id: remote,
            local_config: if remote.is_none() { inputs.local_config } else { None },
        },
        remote_monitor: target(remote.is_some() && inputs.foreground, remote),
        tool_status: target(live, remote),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn local() -> Inputs {
        Inputs { foreground: true, screen_on: true, service_started: true, local_config: Some(1), ..Default::default() }
    }

    #[test]
    fn nothing_runs_while_stopped_or_in_the_background() {
        let p = plan(&Inputs { traffic_visible: true, connections_visible: 1, logs_visible: true, ..Default::default() });
        assert_eq!(p, Plan::default());
        let p = plan(&Inputs { foreground: false, traffic_visible: true, logs_visible: true, ..local() });
        assert!(!p.clash_mode.connect && !p.status.connect && !p.groups.connect && !p.log.connect);
    }

    #[test]
    fn mode_and_groups_follow_every_page_status_and_lists_follow_their_pages() {
        let p = plan(&local());
        assert!(p.clash_mode.connect && p.groups.connect && p.tool_status.connect);
        assert!(!p.status.connect && !p.connections.connect && !p.log.connect && !p.remote_monitor.connect);
        let p = plan(&Inputs { traffic_visible: true, connections_visible: 2, logs_visible: true, ..local() });
        assert!(p.status.connect && p.connections.connect && p.log.connect);
    }

    #[test]
    fn screen_off_stops_the_per_second_streams_only() {
        let p = plan(&Inputs { screen_on: false, traffic_visible: true, connections_visible: 1, logs_visible: true, ..local() });
        assert!(!p.status.connect && !p.connections.connect);
        assert!(p.clash_mode.connect && p.groups.connect && p.log.connect);
    }

    #[test]
    fn the_local_log_stream_waits_for_and_follows_the_configuration() {
        let shown = Inputs { logs_visible: true, ..local() };
        assert!(!plan(&Inputs { local_config: None, ..shown.clone() }).log.connect);
        let first = plan(&shown).log;
        let reloaded = plan(&Inputs { local_config: Some(2), ..shown }).log;
        assert!(first.connect && reloaded.connect && first != reloaded);
    }

    #[test]
    fn a_remote_session_needs_its_monitor_and_renews_streams_on_a_new_server() {
        let remote = Inputs { remote_server_id: Some(7), service_started: false, local_config: Some(3), logs_visible: true, ..local() };
        let p = plan(&remote);
        assert!(p.remote_monitor.connect && !p.clash_mode.connect && !p.log.connect);
        let p = plan(&Inputs { remote_connected: true, ..remote.clone() });
        assert!(p.clash_mode.connect && p.log.connect);
        assert_eq!(p.log.local_config, None);
        let other = plan(&Inputs { remote_connected: true, remote_server_id: Some(8), ..remote.clone() });
        assert_ne!(p.groups, other.groups);
        assert!(!plan(&Inputs { foreground: false, ..remote }).remote_monitor.connect);
    }

    #[test]
    fn inputs_read_from_partial_json() {
        let inputs: Inputs = serde_json::from_str(r#"{"foreground":true,"remoteServerId":5}"#).unwrap();
        assert_eq!(inputs.remote_server_id, Some(5));
        assert!(!inputs.screen_on);
        let json = serde_json::to_value(plan(&inputs)).unwrap();
        assert_eq!(json["remoteMonitor"], serde_json::json!({"connect": true, "remoteServerId": 5}));
    }
}
