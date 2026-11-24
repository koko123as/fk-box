//! From a subscription to a running kernel: import it (`config::brain`),
//! generate the minimal sing-box configuration, compile the rules against
//! fkbrain's outbound table and hand both to the host (`start` / `reload`).
//!
//! This is the control path (once per configuration change), not the
//! per-connection path: its C entry points take the options as a small JSON
//! object. `decide` stays fixed structs only.
//!
//! Groups never become sing-box `selector`/`urltest` outbounds: the rules
//! are compiled against fkbrain's outbound table followed by the group
//! names, and the brain follows a group to its current member per decision
//! (`crate::groups`, stage 5).

use std::collections::HashSet;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::Arc;

use fkbox_client_core::config::brain::generate::{self, GenerateOptions, TunOptions};
use fkbox_client_core::config::brain::{import, ImportResult, Loaders};
use fkbox_client_core::rules::engine::{compile, Cond, Env, Router, RouterIr, Rule, Target};
use serde_json::Value;

use crate::abi::*;
use crate::brain::Routing;
use crate::dns::settings::DnsSettings;
use crate::global;
use crate::groups::GroupSet;

/// Options of one start/reload; every field has the Android default.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct ProfileOptions {
    /// TUN addresses (`172.19.0.1/30`, `fdfe:dcba:9876::1/126` when empty).
    pub tun_address: Vec<String>,
    /// `false` leaves routing to the platform (tests on Linux without root routes).
    pub tun_auto_route: Option<bool>,
    pub tun_stack: Option<String>,
    pub tun_mtu: Option<u32>,
    pub log_level: Option<String>,
    /// The brain's DNS settings (`dns` object; defaults when absent).
    pub dns: DnsSettings,
    /// Where select groups' manual choices are kept (a file the brain
    /// rewrites); absent: in memory only.
    pub group_store: Option<PathBuf>,
}

impl ProfileOptions {
    /// Reads `{"tun_address": [...], "tun_auto_route": bool, "tun_stack": "...", "tun_mtu": n, "log_level": "..."}`
    /// (every key optional; unknown keys are refused).
    pub fn from_json(text: &str) -> Result<ProfileOptions, String> {
        let mut options = ProfileOptions::default();
        if text.trim().is_empty() {
            return Ok(options);
        }
        let value: Value = serde_json::from_str(text).map_err(|e| format!("profile options: {e}"))?;
        let object = value.as_object().ok_or("profile options: not an object")?;
        for (key, item) in object {
            match key.as_str() {
                "tun_address" => {
                    let list = item.as_array().ok_or("profile options: tun_address is not a list")?;
                    for entry in list {
                        options.tun_address.push(entry.as_str().ok_or("profile options: tun_address entry is not a string")?.to_string());
                    }
                }
                "tun_auto_route" => options.tun_auto_route = Some(item.as_bool().ok_or("profile options: tun_auto_route is not a bool")?),
                "tun_stack" => options.tun_stack = Some(item.as_str().ok_or("profile options: tun_stack is not a string")?.to_string()),
                "tun_mtu" => {
                    let mtu = item.as_u64().filter(|m| *m > 0 && *m <= 65535).ok_or("profile options: tun_mtu out of range")?;
                    options.tun_mtu = Some(mtu as u32);
                }
                "log_level" => options.log_level = Some(item.as_str().ok_or("profile options: log_level is not a string")?.to_string()),
                "dns" => options.dns = DnsSettings::from_value(item)?,
                "group_store" => match item {
                    Value::Null => options.group_store = None,
                    Value::String(path) if !path.is_empty() => options.group_store = Some(PathBuf::from(path)),
                    _ => return Err("profile options: group_store is not a path".into()),
                },
                other => return Err(format!("profile options: unknown key {other:?}")),
            }
        }
        Ok(options)
    }

    fn generate_options(&self, find_process: bool) -> GenerateOptions {
        let mut tun = TunOptions::default();
        if !self.tun_address.is_empty() {
            tun.address = self.tun_address.clone();
        }
        if let Some(auto_route) = self.tun_auto_route {
            tun.auto_route = auto_route;
        }
        tun.stack = self.tun_stack.clone();
        tun.mtu = self.tun_mtu;
        GenerateOptions { tun, log_level: self.log_level.clone(), find_process }
    }
}

/// What the host receives and what decide runs.
pub struct Plan {
    pub config_json: String,
    /// fkbrain's outbound table (`fkb_decision.outbound` indexes it).
    pub outbounds: Vec<String>,
    pub router: Router,
    /// The nodes' server names (resolved through the direct upstreams).
    pub node_servers: HashSet<String>,
    /// The proxy groups (the router's indices from `outbounds.len()` on).
    pub groups: Arc<GroupSet>,
}

impl Plan {
    fn routing(&self) -> Routing {
        Routing { outbounds: self.outbounds.clone(), node_servers: self.node_servers.clone(), groups: self.groups.clone() }
    }
}

fn reads_process(cond: &Cond) -> bool {
    match cond {
        Cond::ProcessName(_) | Cond::PackageName(_) | Cond::PackageNameRegex(_) | Cond::Uid(_) => true,
        Cond::And(children) | Cond::Or(children) => children.iter().any(reads_process),
        Cond::Not(child) => reads_process(child),
        _ => false,
    }
}

fn ir_reads_process(ir: &RouterIr) -> bool {
    ir.rules.iter().chain(ir.sub_rules.values().flatten()).any(|r| reads_process(&r.cond))
        || ir.rule_sets.values().any(|s| s.rules.iter().any(reads_process))
}

/// The outbound a rule target name stands for: a node, a group (run by
/// the brain), or a built-in.
fn resolve_name(result: &ImportResult, name: &str) -> Result<Target, String> {
    match name {
        "DIRECT" => return Ok(Target::Outbound(generate::TAG_DIRECT.to_string())),
        "REJECT" => return Ok(Target::Reject),
        "REJECT-DROP" => return Ok(Target::RejectDrop),
        "PASS" => return Ok(Target::Pass),
        // mihomo's COMPATIBLE is what an empty group falls back to: direct.
        "COMPATIBLE" => return Ok(Target::Outbound(generate::TAG_DIRECT.to_string())),
        _ => {}
    }
    if result.node(name).is_some() || result.group(name).is_some() {
        return Ok(Target::Outbound(name.to_string()));
    }
    Err(format!("rule target {name:?}: unknown outbound"))
}

fn map_target(result: &ImportResult, target: &Target) -> Result<Target, String> {
    match target {
        Target::Outbound(name) => resolve_name(result, name),
        other => Ok(other.clone()),
    }
}

fn map_rules(result: &ImportResult, rules: &[Rule]) -> Result<Vec<Rule>, String> {
    rules
        .iter()
        .map(|rule| Ok(Rule { cond: rule.cond.clone(), target: map_target(result, &rule.target)?, source: rule.source.clone() }))
        .collect()
}

/// The rules of the import with every target named by fkbrain's table.
/// A source without rules (a link list) sends everything to its first node.
fn router_ir(result: &ImportResult) -> Result<RouterIr, String> {
    let Some(rules) = &result.rules else {
        let first = result.nodes.first().map(|n| n.tag.clone()).unwrap_or_else(|| generate::TAG_DIRECT.to_string());
        return Ok(RouterIr { final_target: Some(Target::Outbound(first)), ..RouterIr::default() });
    };
    let ir = &rules.router;
    let mut mapped = RouterIr { rules: map_rules(result, &ir.rules)?, rule_sets: ir.rule_sets.clone(), ..RouterIr::default() };
    for (name, list) in &ir.sub_rules {
        mapped.sub_rules.insert(name.clone(), map_rules(result, list)?);
    }
    mapped.final_target = match &ir.final_target {
        Some(target) => Some(map_target(result, target)?),
        // sing-box takes the first outbound, mihomo has no default; the first node, else direct.
        None => Some(Target::Outbound(result.nodes.first().map(|n| n.tag.clone()).unwrap_or_else(|| generate::TAG_DIRECT.to_string()))),
    };
    Ok(mapped)
}

/// Imports a subscription and prepares what the host and decide need.
pub fn plan(content: &str, options: &ProfileOptions) -> Result<Plan, String> {
    let result = import(content, &Loaders::none()).map_err(|e| e.to_string())?;
    let ir = router_ir(&result)?;
    let generated = generate::generate(&result, &options.generate_options(ir_reads_process(&ir)))?;
    let groups = GroupSet::build(&result.groups, &generated.outbounds, generate::TAG_DIRECT)?;
    let inbounds = vec![generate::TAG_TUN.to_string()];
    // Rule targets: the outbounds, then the groups (virtual indices the brain follows).
    let targets = groups.target_names();
    let router =
        compile(&ir, &Env { outbounds: &targets, inbounds: &inbounds, geosite: None, geoip: None, mmdb: None, default_final: None })?;
    let config_json = serde_json::to_string(&generated.config).map_err(|e| e.to_string())?;
    let node_servers = result
        .nodes
        .iter()
        .filter(|n| !n.server.is_empty() && n.server.trim_matches(['[', ']']).parse::<std::net::IpAddr>().is_err())
        .filter_map(|n| fkbox_client_core::rules::engine::domain::normalize(&n.server).map(|d| d.into_owned()))
        .collect();
    Ok(Plan { config_json, outbounds: generated.outbounds, router, node_servers, groups: Arc::new(groups) })
}

fn write_error(err: *mut FkbBuf, message: &str) {
    if err.is_null() {
        return;
    }
    // SAFETY: the caller passes a writable buffer of `cap` bytes at `ptr` (or a NULL ptr with cap 0).
    unsafe {
        let buf = &mut *err;
        let n = message.len().min(buf.cap);
        if n > 0 && !buf.ptr.is_null() {
            std::ptr::copy_nonoverlapping(message.as_ptr(), buf.ptr, n);
        }
        buf.len = n;
    }
}

fn read_str(s: &FkbStr) -> Result<&str, String> {
    if s.ptr.is_null() && s.len != 0 {
        return Err("NULL string".into());
    }
    if s.len == 0 {
        return Ok("");
    }
    // SAFETY: the caller keeps `len` bytes at `ptr` alive for the call.
    std::str::from_utf8(unsafe { s.as_bytes() }).map_err(|_| "not UTF-8".to_string())
}

fn run(profile: FkbStr, options: FkbStr, err: *mut FkbBuf, reload: bool) -> i32 {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<u32, (i32, String)> {
        let content = read_str(&profile).map_err(|e| (FKB_E_INVALID, format!("profile: {e}")))?;
        let options = read_str(&options).map_err(|e| (FKB_E_INVALID, format!("options: {e}")))?;
        let options = ProfileOptions::from_json(options).map_err(|e| (FKB_E_INVALID, e))?;
        let plan = plan(content, &options).map_err(|e| (FKB_E_INVALID, e))?;
        let brain = global();
        brain.groups().set_store(options.group_store.clone());
        // The settings go in first (the host may resolve while it starts); a
        // failed start or reload puts the previous ones back.
        let previous = brain.dns_state().settings();
        brain.dns_state().apply(options.dns.clone()).map_err(|e| (FKB_E_INVALID, e))?;
        let routing = plan.routing();
        let started = if reload {
            brain.reload_with(&plan.config_json, plan.router, routing)
        } else {
            brain.start_with(&plan.config_json, plan.router, routing)
        };
        if started.is_err() {
            let _ = brain.dns_state().apply((*previous).clone());
        }
        started.map_err(|e| (e.code, e.message))
    }));
    match outcome {
        Ok(Ok(_generation)) => FKB_OK,
        Ok(Err((code, message))) => {
            write_error(err, &message);
            code
        }
        Err(_) => {
            write_error(err, "panic in the brain");
            FKB_E_PANIC
        }
    }
}

/// Imports `profile` (a subscription: Clash/mihomo YAML, sing-box JSON,
/// share links or Base64 of them), generates the configuration and starts
/// the host with it. `options` is a JSON object ([`ProfileOptions`]) or empty.
/// On failure `err` holds the reason.
///
/// # Safety
/// The strings are readable for the call; `err` is NULL or a writable buffer.
#[no_mangle]
pub unsafe extern "C" fn fkb_brain_start_profile(profile: FkbStr, options: FkbStr, err: *mut FkbBuf) -> i32 {
    run(profile, options, err, false)
}

/// As [`fkb_brain_start_profile`], replacing the running configuration; on
/// failure the previous configuration keeps running and deciding.
///
/// # Safety
/// As [`fkb_brain_start_profile`].
#[no_mangle]
pub unsafe extern "C" fn fkb_brain_reload_profile(profile: FkbStr, options: FkbStr, err: *mut FkbBuf) -> i32 {
    run(profile, options, err, true)
}

/// Stops the host; decide answers `FKB_E_NOT_READY` afterwards.
///
/// # Safety
/// `err` is NULL or a writable buffer.
#[no_mangle]
pub unsafe extern "C" fn fkb_brain_stop(err: *mut FkbBuf) -> i32 {
    match catch_unwind(AssertUnwindSafe(|| global().stop())) {
        Ok(Ok(())) => FKB_OK,
        Ok(Err(e)) => {
            write_error(err, &e.message);
            e.code
        }
        Err(_) => {
            write_error(err, "panic in the brain");
            FKB_E_PANIC
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use fkbox_client_core::rules::engine::{Action, Conn, Network, NoDns};

    const YAML: &str = r#"
proxies:
  - {name: ss-a, type: ss, server: 127.0.0.1, port: 8388, cipher: aes-128-gcm, password: pw}
  - {name: mieru-b, type: mieru, server: 127.0.0.1, port: 8964, transport: TCP, username: u, password: p}
proxy-groups:
  - {name: Proxy, type: select, proxies: [mieru-b, ss-a]}
  - {name: Outer, type: select, proxies: [Proxy]}
rules:
  - DOMAIN-SUFFIX,ss.test,ss-a
  - DOMAIN-SUFFIX,group.test,Outer
  - DOMAIN-SUFFIX,block.test,REJECT
  - PROCESS-NAME,com.example.app,DIRECT
  - MATCH,DIRECT
"#;

    fn conn(domain: &'static str) -> Conn<'static> {
        Conn {
            network: Some(Network::Tcp),
            src: Some("172.19.0.1".parse().unwrap()),
            src_port: 40000,
            dst: Some("198.18.99.1".parse().unwrap()),
            dst_port: 443,
            domain: Some(domain),
            protocol: Some("tls"),
            uid: None,
            package: None,
            process: None,
            inbound: Some(0),
        }
    }

    fn outbound(plan: &Plan, domain: &'static str) -> Action {
        let table = crate::groups::Groups::default().install(plan.groups.clone());
        table.resolve(plan.router.decide(&conn(domain), &NoDns).action, &conn(domain))
    }

    fn index(plan: &Plan, tag: &str) -> u32 {
        plan.outbounds.iter().position(|t| t == tag).unwrap() as u32
    }

    #[test]
    fn plan_maps_targets_to_the_fkbrain_table() {
        let options = ProfileOptions::from_json(r#"{"tun_auto_route": false, "tun_stack": "gvisor", "log_level": "debug"}"#).unwrap();
        let plan = plan(YAML, &options).unwrap();
        assert_eq!(plan.outbounds, ["ss-a", "mieru-b", "fk-direct", "fk-block"]);
        assert!(plan.node_servers.is_empty(), "both servers are addresses");
        assert_eq!(outbound(&plan, "a.ss.test"), Action::Outbound(index(&plan, "ss-a")));
        // Outer -> Proxy -> first member mieru-b (the select groups' starting members).
        assert_eq!(outbound(&plan, "x.group.test"), Action::Outbound(index(&plan, "mieru-b")));
        assert_eq!(outbound(&plan, "block.test"), Action::Reject);
        assert_eq!(outbound(&plan, "other.example"), Action::Outbound(index(&plan, "fk-direct")));
        let config: Value = serde_json::from_str(&plan.config_json).unwrap();
        assert_eq!(config["outbounds"][0]["type"], "fkbrain");
        assert_eq!(config["outbounds"][0]["outbounds"], serde_json::json!(plan.outbounds));
        assert_eq!(config["inbounds"][0]["stack"], "gvisor");
        assert!(config["inbounds"][0].get("auto_route").is_none());
        assert_eq!(config["route"]["find_process"], true, "PROCESS-NAME needs the connection owner");
        assert_eq!(config["log"]["level"], "debug");
    }

    #[test]
    fn link_lists_go_to_the_first_node_and_options_are_strict() {
        let links = "ss://YWVzLTEyOC1nY206cHc@127.0.0.1:8388#first\nss://YWVzLTEyOC1nY206cHc@127.0.0.1:8389#second\n";
        let plan = plan(links, &ProfileOptions::default()).unwrap();
        assert_eq!(outbound(&plan, "any.example"), Action::Outbound(index(&plan, "first")));
        let config: Value = serde_json::from_str(&plan.config_json).unwrap();
        assert!(config["route"].get("find_process").is_none());
        assert!(ProfileOptions::from_json(r#"{"tun": 1}"#).is_err());
        assert!(ProfileOptions::from_json(r#"{"tun_mtu": 0}"#).is_err());
        assert_eq!(ProfileOptions::from_json("").unwrap(), ProfileOptions::default());
        let with_dns = ProfileOptions::from_json(r#"{"dns": {"fake_ip": false, "direct_upstreams": ["tls://223.5.5.5"]}}"#).unwrap();
        assert!(!with_dns.dns.fake_ip);
        assert!(ProfileOptions::from_json(r#"{"dns": {"direct_upstreams": ["tls://dns.alidns.com"]}}"#).is_err());
    }

    #[test]
    fn unknown_targets_and_group_loops_fail() {
        let mut result = import(YAML, &Loaders::none()).unwrap();
        assert!(resolve_name(&result, "nope").is_err());
        assert_eq!(resolve_name(&result, "Outer").unwrap(), Target::Outbound("Outer".into()));
        result.groups[0].members = vec!["Outer".into()];
        let outbounds: Vec<String> = ["ss-a", "mieru-b", "fk-direct", "fk-block"].iter().map(|s| s.to_string()).collect();
        assert!(GroupSet::build(&result.groups, &outbounds, "fk-direct").unwrap_err().contains("loop"));
        let with_group = ProfileOptions::from_json(r#"{"group_store": "/data/fk/groups.json"}"#).unwrap();
        assert_eq!(with_group.group_store, Some(PathBuf::from("/data/fk/groups.json")));
        assert!(ProfileOptions::from_json(r#"{"group_store": 1}"#).is_err());
    }

    #[test]
    fn c_entry_points_report_errors() {
        let mut bytes = vec![0u8; 256];
        let mut err = FkbBuf { ptr: bytes.as_mut_ptr(), cap: bytes.len(), len: 0 };
        // SAFETY: the strings and the buffer live for the calls.
        let code = unsafe { fkb_brain_start_profile(FkbStr::of("not a subscription ::"), FkbStr::of(""), &mut err) };
        assert_eq!(code, FKB_E_INVALID);
        assert!(err.len > 0, "no message");
        let code = unsafe { fkb_brain_start_profile(FkbStr::of(YAML), FkbStr::of("[]"), &mut err) };
        assert_eq!(code, FKB_E_INVALID);
        assert!(String::from_utf8_lossy(&bytes[..err.len]).contains("not an object"));
    }
}
