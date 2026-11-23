//! Input → output snapshots for every protocol, the error paths, subscription
//! DNS being ignored, chained proxies and loop detection. Every snapshot
//! case is checked before the test fails, so one CI run lists all
//! mismatches. With `FKBOX_BRAIN_CONFIG_OUT` set, the generated sing-box
//! configurations are written there for the Go check (probes/stage2), with
//! the actual node objects (`snapshot-actual-*.json`).

use std::path::PathBuf;

use serde_json::{json, Value};

use super::generate::{generate, node_object, GenerateOptions};
use super::*;

const UUID: &str = "b831381d-6324-4d53-ad4f-8cda48b30811";
/// A REALITY public key (32 bytes, base64url without padding).
const PBK: &str = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA";
/// WireGuard keys (32 bytes, standard Base64) and their URL-encoded forms.
const WG_PRIVATE: &str = "SAIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=";
const WG_PRIVATE_URL: &str = "SAIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA%3D";
const WG_PUBLIC: &str = "ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1+f4CBgoM=";
const WG_PUBLIC_URL: &str = "ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1%2Bf4CBgoM%3D";

fn import_text(text: &str) -> Result<ImportResult, ImportError> {
    import(text, &Loaders::none())
}

fn out_dir() -> Option<PathBuf> {
    std::env::var_os("FKBOX_BRAIN_CONFIG_OUT").map(PathBuf::from)
}

fn write_out(name: &str, value: &Value) {
    if let Some(dir) = out_dir() {
        std::fs::create_dir_all(&dir).expect("create the output directory");
        let path = dir.join(format!("{name}.json"));
        std::fs::write(&path, serde_json::to_string_pretty(value).expect("serialize")).expect("write the output file");
    }
}

/// The node objects as the generator writes them.
fn objects(result: &ImportResult) -> Vec<Value> {
    result.nodes.iter().map(|n| Value::Object(node_object(&n.tag, n.detour.as_deref(), &n.spec).0)).collect()
}

fn link_cases() -> Vec<(&'static str, String, Value)> {
    vec![
        (
            "ss",
            "ss://YWVzLTEyOC1nY206cGFzcw@1.2.3.4:8388#ss-a".to_string(),
            json!({"type": "shadowsocks", "tag": "ss-a", "server": "1.2.3.4", "server_port": 8388, "method": "aes-128-gcm", "password": "pass"}),
        ),
        (
            "ss-obfs",
            "ss://YWVzLTEyOC1nY206cGFzcw@1.2.3.4:8388/?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dexample.com#ss-obfs".to_string(),
            json!({
                "type": "shadowsocks", "tag": "ss-obfs", "server": "1.2.3.4", "server_port": 8388, "method": "aes-128-gcm",
                "password": "pass", "plugin": "obfs-local", "plugin_opts": "obfs=http;obfs-host=example.com"
            }),
        ),
        (
            "ss-v2ray-plugin",
            "ss://YWVzLTEyOC1nY206cGFzcw@1.2.3.4:8388/?plugin=v2ray-plugin%3Bmode%3Dwebsocket%3Btls%3Bhost%3Dcdn.example.com%3Bpath%3D%2Fws#ss-v2"
                .to_string(),
            json!({
                "type": "mihomo", "tag": "ss-v2",
                "proxy": {
                    "type": "ss", "server": "1.2.3.4", "port": 8388, "cipher": "aes-128-gcm", "password": "pass", "udp": true,
                    "plugin": "v2ray-plugin",
                    "plugin-opts": {"mode": "websocket", "host": "cdn.example.com", "path": "/ws", "tls": true},
                    "name": "ss-v2"
                }
            }),
        ),
        (
            "ssr",
            "ssr://MS4yLjMuNDo4Mzg4OmF1dGhfYWVzMTI4X21kNTphZXMtMTI4LWNmYjp0bHMxLjJfdGlja2V0X2F1dGg6Y0dGemN3Lz9vYmZzcGFyYW09YjJKbWN5NWxlR0Z0Y0d4bExtTnZiUSZyZW1hcmtzPWMzTnlMV0U".to_string(),
            json!({
                "type": "mihomo", "tag": "ssr-a",
                "proxy": {
                    "type": "ssr", "server": "1.2.3.4", "port": 8388, "protocol": "auth_aes128_md5", "cipher": "aes-128-cfb",
                    "obfs": "tls1.2_ticket_auth", "password": "pass", "udp": true, "obfs-param": "obfs.example.com", "name": "ssr-a"
                }
            }),
        ),
        (
            "vmess-ws-tls",
            "vmess://eyJ2IjoiMiIsInBzIjoidm0td3MiLCJhZGQiOiJ2bS5leGFtcGxlLmNvbSIsInBvcnQiOiI0NDMiLCJpZCI6ImI4MzEzODFkLTYzMjQtNGQ1My1hZDRmLThjZGE0OGIzMDgxMSIsImFpZCI6IjAiLCJzY3kiOiJhdXRvIiwibmV0Ijoid3MiLCJ0eXBlIjoibm9uZSIsImhvc3QiOiJjZG4uZXhhbXBsZS5jb20iLCJwYXRoIjoiL3JheT9lZD0yMDQ4IiwidGxzIjoidGxzIiwic25pIjoidm0uZXhhbXBsZS5jb20iLCJhbHBuIjoiaDIsaHR0cC8xLjEiLCJmcCI6ImNocm9tZSJ9".to_string(),
            json!({
                "type": "vmess", "tag": "vm-ws", "server": "vm.example.com", "server_port": 443, "uuid": UUID, "security": "auto",
                "packet_encoding": "xudp",
                "tls": {"enabled": true, "server_name": "vm.example.com", "alpn": ["h2", "http/1.1"], "utls": {"enabled": true, "fingerprint": "chrome"}},
                "transport": {
                    "type": "ws", "path": "/ray", "headers": {"Host": "cdn.example.com"},
                    "max_early_data": 2048, "early_data_header_name": "Sec-WebSocket-Protocol"
                }
            }),
        ),
        (
            "vless-reality-vision",
            format!("vless://{UUID}@1.2.3.4:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.example.com&fp=chrome&pbk={PBK}&sid=6ba85179e30d4fc2&type=tcp#vl-reality"),
            json!({
                "type": "vless", "tag": "vl-reality", "server": "1.2.3.4", "server_port": 443, "uuid": UUID, "flow": "xtls-rprx-vision",
                "packet_encoding": "xudp",
                "tls": {
                    "enabled": true, "server_name": "www.example.com",
                    "reality": {"enabled": true, "public_key": PBK, "short_id": "6ba85179e30d4fc2"},
                    "utls": {"enabled": true, "fingerprint": "chrome"}
                }
            }),
        ),
        (
            "vless-ws-tls-insecure",
            format!("vless://{UUID}@vl.example.com:443?security=tls&type=ws&host=cdn.example.com&path=%2Fws&sni=vl.example.com&allowInsecure=1#vl-ws"),
            json!({
                "type": "vless", "tag": "vl-ws", "server": "vl.example.com", "server_port": 443, "uuid": UUID, "packet_encoding": "xudp",
                "tls": {"enabled": true, "server_name": "vl.example.com", "insecure": true, "utls": {"enabled": true, "fingerprint": "chrome"}},
                "transport": {"type": "ws", "path": "/ws", "headers": {"Host": "cdn.example.com"}}
            }),
        ),
        (
            "vless-grpc",
            format!("vless://{UUID}@vl.example.com:443?security=tls&type=grpc&serviceName=gun&sni=vl.example.com&fp=firefox#vl-grpc"),
            json!({
                "type": "vless", "tag": "vl-grpc", "server": "vl.example.com", "server_port": 443, "uuid": UUID, "packet_encoding": "xudp",
                "tls": {"enabled": true, "server_name": "vl.example.com", "utls": {"enabled": true, "fingerprint": "firefox"}},
                "transport": {"type": "grpc", "service_name": "gun"}
            }),
        ),
        (
            "vless-httpupgrade",
            format!("vless://{UUID}@vl.example.com:80?type=httpupgrade&host=cdn.example.com&path=%2Fup#vl-up"),
            json!({
                "type": "vless", "tag": "vl-up", "server": "vl.example.com", "server_port": 80, "uuid": UUID, "packet_encoding": "xudp",
                "transport": {"type": "httpupgrade", "host": "cdn.example.com", "path": "/up"}
            }),
        ),
        (
            "vless-xhttp",
            format!("vless://{UUID}@x.example.com:443?encryption=none&security=tls&sni=x.example.com&fp=chrome&type=xhttp&path=%2Fxh&mode=auto&host=x.example.com#vl-xhttp"),
            json!({
                "type": "xray", "tag": "vl-xhttp",
                "outbound": {
                    "protocol": "vless",
                    "settings": {"address": "x.example.com", "port": 443, "id": UUID, "encryption": "none"},
                    "streamSettings": {
                        "network": "xhttp", "security": "tls",
                        "tlsSettings": {"serverName": "x.example.com", "fingerprint": "chrome"},
                        "xhttpSettings": {"path": "/xh", "host": "x.example.com", "mode": "auto"}
                    }
                }
            }),
        ),
        (
            "vless-xhttp-extra-reality",
            format!("vless://{UUID}@x.example.com:443?security=reality&sni=www.example.com&pbk={PBK}&sid=ab&spx=%2F&type=xhttp&path=%2Fxh&extra=%7B%22xPaddingBytes%22%3A%22100-1000%22%7D#vl-xhttp-r"),
            json!({
                "type": "xray", "tag": "vl-xhttp-r",
                "outbound": {
                    "protocol": "vless",
                    "settings": {"address": "x.example.com", "port": 443, "id": UUID, "encryption": "none"},
                    "streamSettings": {
                        "network": "xhttp", "security": "reality",
                        "realitySettings": {"serverName": "www.example.com", "fingerprint": "chrome", "publicKey": PBK, "shortId": "ab", "spiderX": "/"},
                        "xhttpSettings": {"path": "/xh", "extra": {"xPaddingBytes": "100-1000"}}
                    }
                }
            }),
        ),
        (
            "vless-encryption",
            format!("vless://{UUID}@1.2.3.4:443?encryption=mlkem768x25519plus.native.0rtt.AAAA&type=tcp#vl-enc"),
            json!({
                "type": "xray", "tag": "vl-enc",
                "outbound": {
                    "protocol": "vless",
                    "settings": {"address": "1.2.3.4", "port": 443, "id": UUID, "encryption": "mlkem768x25519plus.native.0rtt.AAAA"},
                    "streamSettings": {"network": "raw", "security": "none"}
                }
            }),
        ),
        (
            "trojan-ws",
            "trojan://pass@t.example.com:443?security=tls&sni=t.example.com&type=ws&path=%2Ftj&host=t.example.com#tj-ws".to_string(),
            json!({
                "type": "trojan", "tag": "tj-ws", "server": "t.example.com", "server_port": 443, "password": "pass",
                "tls": {"enabled": true, "server_name": "t.example.com", "utls": {"enabled": true, "fingerprint": "chrome"}},
                "transport": {"type": "ws", "path": "/tj", "headers": {"Host": "t.example.com"}}
            }),
        ),
        (
            "hysteria2-hopping",
            "hysteria2://letmein@h2.example.com:443,20000-30000/?sni=h2.example.com&obfs=salamander&obfs-password=ob&insecure=1#hy2".to_string(),
            json!({
                "type": "hysteria2", "tag": "hy2", "server": "h2.example.com", "server_port": 443, "server_ports": ["443:443", "20000:30000"],
                "password": "letmein", "obfs": {"type": "salamander", "password": "ob"},
                "tls": {"enabled": true, "server_name": "h2.example.com", "insecure": true}
            }),
        ),
        (
            "hysteria2-pin",
            "hy2://pw@h.example.com:8443?sni=h.example.com&pinSHA256=abcdef#hy2-pin".to_string(),
            json!({
                "type": "mihomo", "tag": "hy2-pin",
                "proxy": {"type": "hysteria2", "server": "h.example.com", "port": 8443, "sni": "h.example.com", "fingerprint": "abcdef", "password": "pw", "name": "hy2-pin"}
            }),
        ),
        (
            "hysteria",
            "hysteria://h1.example.com:443?protocol=udp&auth=secret&peer=h1.example.com&insecure=1&upmbps=50&downmbps=100&alpn=h3&obfs=xplus#hy1".to_string(),
            json!({
                "type": "hysteria", "tag": "hy1", "server": "h1.example.com", "server_port": 443, "up_mbps": 50, "down_mbps": 100,
                "auth_str": "secret", "obfs": "xplus",
                "tls": {"enabled": true, "server_name": "h1.example.com", "insecure": true, "alpn": ["h3"]}
            }),
        ),
        (
            "tuic-v5",
            format!("tuic://{UUID}:pw@tu.example.com:443?congestion_control=bbr&alpn=h3&sni=tu.example.com&udp_relay_mode=native#tuic5"),
            json!({
                "type": "tuic", "tag": "tuic5", "server": "tu.example.com", "server_port": 443, "uuid": UUID, "password": "pw",
                "congestion_control": "bbr", "udp_relay_mode": "native",
                "tls": {"enabled": true, "server_name": "tu.example.com", "alpn": ["h3"]}
            }),
        ),
        (
            "tuic-v4",
            "tuic://token123@t4.example.com:443?sni=t4.example.com#tuic4".to_string(),
            json!({
                "type": "mihomo", "tag": "tuic4",
                "proxy": {"type": "tuic", "server": "t4.example.com", "port": 443, "udp": true, "token": "token123", "sni": "t4.example.com", "name": "tuic4"}
            }),
        ),
        (
            "wireguard",
            format!("wireguard://{WG_PRIVATE_URL}@wg.example.com:51820?publickey={WG_PUBLIC_URL}&address=10.0.0.2%2F32%2Cfd00%3A%3A2%2F128&mtu=1420&reserved=1%2C2%2C3#wg"),
            json!({
                "type": "wireguard", "tag": "wg", "address": ["10.0.0.2/32", "fd00::2/128"], "private_key": WG_PRIVATE, "mtu": 1420,
                "peers": [{
                    "address": "wg.example.com", "port": 51820, "public_key": WG_PUBLIC,
                    "allowed_ips": ["0.0.0.0/0", "::/0"], "reserved": [1, 2, 3]
                }]
            }),
        ),
        (
            "anytls",
            "anytls://pw@a.example.com:443?sni=a.example.com&insecure=1#anytls".to_string(),
            json!({
                "type": "anytls", "tag": "anytls", "server": "a.example.com", "server_port": 443, "password": "pw",
                "tls": {"enabled": true, "server_name": "a.example.com", "insecure": true}
            }),
        ),
        (
            "socks5",
            "socks5://dXNlcjpwYXNz@s.example.com:1080#socks".to_string(),
            json!({"type": "socks", "tag": "socks", "server": "s.example.com", "server_port": 1080, "version": "5", "username": "user", "password": "pass"}),
        ),
        (
            "http",
            "http://user:pass@p.example.com:8080#http".to_string(),
            json!({"type": "http", "tag": "http", "server": "p.example.com", "server_port": 8080, "username": "user", "password": "pass"}),
        ),
        (
            "https",
            "https://p.example.com:8443#https".to_string(),
            json!({"type": "http", "tag": "https", "server": "p.example.com", "server_port": 8443, "tls": {"enabled": true}}),
        ),
        (
            "mieru",
            "mierus://u:p@m.example.com?profile=mp&port=6666&protocol=TCP&port=9998-9999&protocol=UDP".to_string(),
            json!([
                {
                    "type": "mihomo", "tag": "mp:6666/TCP",
                    "proxy": {"type": "mieru", "server": "m.example.com", "transport": "TCP", "udp": true, "username": "u", "password": "p", "port": 6666, "name": "mp:6666/TCP"}
                },
                {
                    "type": "mihomo", "tag": "mp:9998-9999/UDP",
                    "proxy": {"type": "mieru", "server": "m.example.com", "transport": "UDP", "udp": true, "username": "u", "password": "p", "port-range": "9998-9999", "name": "mp:9998-9999/UDP"}
                }
            ]),
        ),
    ]
}

#[test]
fn share_link_snapshots() {
    let mut failures = Vec::new();
    let mut actual = serde_json::Map::new();
    for (name, link, expected) in link_cases() {
        let result = match import_text(&link) {
            Ok(r) => r,
            Err(e) => {
                failures.push(format!("{name}: import failed: {e}"));
                continue;
            }
        };
        let got = objects(&result);
        let got = if got.len() == 1 { got[0].clone() } else { Value::Array(got) };
        actual.insert(name.to_string(), got.clone());
        if got != expected {
            failures.push(format!("{name}:\n  expected {expected}\n  actual   {got}"));
        }
        match generate(&result, &GenerateOptions::default()) {
            Ok(g) => write_out(&format!("link-{name}"), &g.config),
            Err(e) => failures.push(format!("{name}: generate failed: {e}")),
        }
    }
    write_out("snapshot-actual-links", &Value::Object(actual));
    assert!(failures.is_empty(), "{} snapshot mismatch(es):\n{}", failures.len(), failures.join("\n"));
}

#[test]
fn downgrades_and_xray_choices_are_reported() {
    let r = import_text(
        "ss://YWVzLTEyOC1nY206cGFzcw@1.2.3.4:8388/?plugin=v2ray-plugin%3Bmode%3Dwebsocket%3Bhost%3Dh#a\nhy2://pw@h.example.com:8443?pinSHA256=ab#b",
    )
    .unwrap();
    let downgraded: Vec<&Notice> = r.notices.iter().filter(|n| n.kind == NoticeKind::Downgraded).collect();
    assert_eq!(downgraded.len(), 2, "{:?}", r.notices);
    assert!(downgraded[0].message.contains("plugin v2ray-plugin"), "{}", downgraded[0].message);
    assert!(downgraded[1].message.contains("fingerprint"), "{}", downgraded[1].message);
    assert_eq!(r.node("a").unwrap().implementation(), Implementation::Mihomo);
    let x = import_text(&format!("vless://{UUID}@x.example.com:443?type=xhttp&security=tls#x")).unwrap();
    assert_eq!(x.nodes[0].implementation(), Implementation::Xray);
    assert_eq!((x.nodes[0].transport.as_str(), x.nodes[0].security), ("xhttp", Security::Tls));
}

#[test]
fn link_lists_and_base64() {
    let list = format!("ss://YWVzLTEyOC1nY206cGFzcw@1.2.3.4:8388#same\nss://YWVzLTEyOC1nY206cGFzcw@1.2.3.5:8388#same\n\nvless://{UUID}@x.example.com:443?type=xhttp&security=tls&allowInsecure=1#bad\n");
    let r = import_text(&list).unwrap();
    assert_eq!(r.format, SourceFormat::Links);
    let tags: Vec<&str> = r.nodes.iter().map(|n| n.tag.as_str()).collect();
    assert_eq!(tags, ["same", "same-01"]);
    let skipped: Vec<&Notice> = r.notices.iter().filter(|n| n.kind == NoticeKind::SkippedLink).collect();
    assert_eq!(skipped.len(), 1);
    assert_eq!(skipped[0].at, "line 4");
    assert!(skipped[0].message.contains("Xray 26 removed"), "{}", skipped[0].message);
    let encoded = "c3M6Ly9ZV1Z6TFRFeU9DMW5ZMjA2Y0dGemN3QDEuMi4zLjQ6ODM4OCNiNjQ=";
    let r = import_text(encoded).unwrap();
    assert_eq!(r.format, SourceFormat::Base64Links);
    assert_eq!(r.nodes[0].tag, "b64");
    assert!(import_text("not a subscription").is_err());
    assert!(import_text("").is_err());
}

const CLASH: &str = r#"
mixed-port: 7890
mode: rule
dns:
  enable: true
  nameserver: ["https://1.1.1.1/dns-query"]
  nameserver-policy:
    "geosite:cn": 223.5.5.5
proxies:
  - {name: hk-ss, type: ss, server: hk.example.com, port: 8388, cipher: aes-128-gcm, password: pw, udp: true}
  - {name: jp-vmess, type: vmess, server: jp.example.com, port: 443, uuid: UUID, alterId: 0, cipher: auto, tls: true, servername: jp.example.com, network: grpc, grpc-opts: {grpc-service-name: gun}}
  - {name: us-trojan, type: trojan, server: us.example.com, port: 443, password: pw, sni: us.example.com, dialer-proxy: hk-ss}
  - {name: sg-mieru, type: mieru, server: sg.example.com, port: 2999, transport: TCP, username: u, password: p}
proxy-groups:
  - {name: Proxy, type: select, proxies: [Auto, DIRECT], include-all: true, exclude-type: Mieru}
  - {name: Auto, type: url-test, include-all-proxies: true, filter: "(?i)jp|us", url: "https://www.gstatic.com/generate_204", interval: 300, tolerance: 50}
rules:
  - DOMAIN-SUFFIX,google.com,Proxy
  - DOMAIN-KEYWORD,ads,REJECT
  - MATCH,DIRECT
"#;

#[test]
fn clash_import_and_generate() {
    let r = import_text(&CLASH.replace("UUID", UUID)).unwrap();
    assert_eq!(r.format, SourceFormat::Clash);
    assert!(r.notices.iter().any(|n| n.kind == NoticeKind::IgnoredDns && n.message == IGNORED_DNS), "{:?}", r.notices);
    assert!(r.notices.iter().any(|n| n.kind == NoticeKind::IgnoredSettings && n.message.contains("mixed-port")), "{:?}", r.notices);
    let tags: Vec<&str> = r.nodes.iter().map(|n| n.tag.as_str()).collect();
    assert_eq!(tags, ["hk-ss", "jp-vmess", "us-trojan", "sg-mieru"]);
    assert_eq!(r.node("us-trojan").unwrap().detour.as_deref(), Some("hk-ss"));
    assert_eq!(r.node("sg-mieru").unwrap().implementation(), Implementation::Mihomo);
    let got = objects(&r);
    let expected = vec![
        json!({"type": "shadowsocks", "tag": "hk-ss", "server": "hk.example.com", "server_port": 8388, "method": "aes-128-gcm", "password": "pw"}),
        json!({
            "type": "vmess", "tag": "jp-vmess", "server": "jp.example.com", "server_port": 443, "uuid": UUID, "security": "auto", "network": "tcp",
            "tls": {"enabled": true, "server_name": "jp.example.com"}, "transport": {"type": "grpc", "service_name": "gun"}
        }),
        json!({
            "type": "trojan", "tag": "us-trojan", "detour": "hk-ss", "server": "us.example.com", "server_port": 443, "password": "pw",
            "network": "tcp", "tls": {"enabled": true, "server_name": "us.example.com"}
        }),
        json!({
            "type": "mihomo", "tag": "sg-mieru",
            "proxy": {"name": "sg-mieru", "type": "mieru", "server": "sg.example.com", "port": 2999, "transport": "TCP", "username": "u", "password": "p"}
        }),
    ];
    let mut failures = Vec::new();
    for (g, e) in got.iter().zip(expected.iter()) {
        if g != e {
            failures.push(format!("expected {e}\n  actual   {g}"));
        }
    }
    write_out("snapshot-actual-clash", &Value::Array(got.clone()));
    assert!(failures.is_empty(), "{}", failures.join("\n"));
    let proxy = r.group("Proxy").unwrap();
    assert_eq!(proxy.kind, GroupKind::Select);
    assert_eq!(proxy.members, ["Auto", "DIRECT", "hk-ss", "jp-vmess", "us-trojan"]);
    let auto = r.group("Auto").unwrap();
    assert_eq!(auto.kind, GroupKind::UrlTest);
    assert_eq!(auto.members, ["jp-vmess", "us-trojan"]);
    assert_eq!((auto.params.interval_secs, auto.params.tolerance_ms), (Some(300), Some(50)));
    assert_eq!(auto.params.url.as_deref(), Some("https://www.gstatic.com/generate_204"));
    assert_eq!(r.rules.as_ref().unwrap().router.rules.len(), 3);

    let g = generate(&r, &GenerateOptions::default()).unwrap();
    assert_eq!(g.outbounds, ["hk-ss", "jp-vmess", "us-trojan", "sg-mieru", "fk-direct", "fk-block"]);
    let config = &g.config;
    assert_eq!(config["outbounds"][0], json!({"type": "fkbrain", "tag": "fkbrain", "outbounds": g.outbounds}));
    assert!(config["route"].get("rules").is_none());
    assert!(config["dns"].get("rules").is_none());
    assert_eq!(config["dns"]["servers"].as_array().unwrap().len(), 1);
    let types: Vec<&str> = config["outbounds"].as_array().unwrap().iter().map(|o| o["type"].as_str().unwrap()).collect();
    assert_eq!(types, ["fkbrain", "shadowsocks", "vmess", "trojan", "mihomo", "direct", "block"]);
    write_out("clash", config);
}

fn singbox_input() -> String {
    json!({
        "log": {"level": "info"},
        "dns": {"servers": [{"type": "udp", "tag": "d", "server": "1.1.1.1"}]},
        "inbounds": [{"type": "mixed", "tag": "in", "listen_port": 2080}],
        "outbounds": [
            {"type": "selector", "tag": "select", "outbounds": ["auto", "vl", "direct"], "default": "auto"},
            {"type": "urltest", "tag": "auto", "outbounds": ["vl", "hy"], "url": "https://www.gstatic.com/generate_204", "interval": "3m", "tolerance": 50},
            {
                "type": "vless", "tag": "vl", "server": "1.2.3.4", "server_port": 443, "uuid": UUID, "flow": "xtls-rprx-vision",
                "tls": {
                    "enabled": true, "server_name": "www.example.com", "utls": {"enabled": true, "fingerprint": "chrome"},
                    "reality": {"enabled": true, "public_key": PBK, "short_id": "6ba85179e30d4fc2"}
                },
                "domain_resolver": "d"
            },
            {"type": "hysteria2", "tag": "hy", "server": "h.example.com", "server_port": 443, "password": "pw", "tls": {"enabled": true, "server_name": "h.example.com"}, "detour": "vl"},
            {"type": "direct", "tag": "direct"}
        ],
        "endpoints": [{
            "type": "wireguard", "tag": "wg", "address": ["10.0.0.2/32"], "private_key": WG_PRIVATE,
            "peers": [{"address": "wg.example.com", "port": 51820, "public_key": WG_PUBLIC, "allowed_ips": ["0.0.0.0/0"]}]
        }],
        "route": {
            "rules": [{"action": "sniff"}, {"protocol": "dns", "action": "hijack-dns"}, {"domain_suffix": ["example.org"], "outbound": "wg"}],
            "final": "select"
        }
    })
    .to_string()
}

#[test]
fn singbox_import_and_generate() {
    let r = import_text(&singbox_input()).unwrap();
    assert_eq!(r.format, SourceFormat::SingBox);
    assert!(r.notices.iter().any(|n| n.kind == NoticeKind::IgnoredDns && n.message == IGNORED_DNS), "{:?}", r.notices);
    assert!(r.notices.iter().any(|n| n.kind == NoticeKind::IgnoredDns && n.at == "vl"), "{:?}", r.notices);
    assert!(r.has_notice(NoticeKind::HostDirective));
    assert!(r.notices.iter().any(|n| n.kind == NoticeKind::IgnoredSettings && n.message.contains("inbounds")), "{:?}", r.notices);
    let tags: Vec<&str> = r.nodes.iter().map(|n| n.tag.as_str()).collect();
    assert_eq!(tags, ["vl", "hy", "direct", "wg"]);
    let vl = r.node("vl").unwrap();
    assert_eq!((vl.security, vl.port), (Security::Reality, 443));
    assert_eq!(r.node("hy").unwrap().detour.as_deref(), Some("vl"));
    assert_eq!(r.node("wg").unwrap().server, "wg.example.com");
    let select = r.group("select").unwrap();
    assert_eq!((select.kind, select.params.default.as_deref()), (GroupKind::Select, Some("auto")));
    let auto = r.group("auto").unwrap();
    assert_eq!((auto.kind, auto.params.interval_secs, auto.params.tolerance_ms), (GroupKind::UrlTest, Some(180), Some(50)));
    let rules = r.rules.as_ref().unwrap();
    assert_eq!(rules.router.rules.len(), 2);
    assert_eq!(rules.host.len(), 1);

    let g = generate(&r, &GenerateOptions::default()).unwrap();
    assert_eq!(g.outbounds, ["vl", "hy", "direct", "wg", "fk-direct", "fk-block"]);
    let config = &g.config;
    let vl_out = &config["outbounds"][1];
    assert_eq!(vl_out["tag"], "vl");
    assert_eq!(vl_out["domain_resolver"], "fkdns");
    assert_eq!(config["outbounds"][2]["detour"], "vl");
    assert_eq!(config["endpoints"][0]["tag"], "wg");
    assert!(config.get("log").is_none());
    let types: Vec<&str> = config["outbounds"].as_array().unwrap().iter().map(|o| o["type"].as_str().unwrap()).collect();
    assert_eq!(types, ["fkbrain", "vless", "hysteria2", "direct", "direct", "block"]);
    write_out("singbox", config);
}

#[test]
fn generated_config_is_minimal() {
    let input = format!(
        "ss://YWVzLTEyOC1nY206cGFzcw@1.2.3.4:8388#ss-a\nwireguard://{WG_PRIVATE_URL}@wg.example.com:51820?publickey={WG_PUBLIC_URL}&address=10.0.0.2%2F32#wg"
    );
    let r = import_text(&input).unwrap();
    let g = generate(&r, &GenerateOptions::default()).unwrap();
    let expected = json!({
        "dns": {"servers": [{"type": "fkdns", "tag": "fkdns"}], "final": "fkdns"},
        "inbounds": [{"type": "tun", "tag": "fk-tun", "address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"], "auto_route": true}],
        "endpoints": [{
            "type": "wireguard", "tag": "wg", "address": ["10.0.0.2/32"], "private_key": WG_PRIVATE,
            "peers": [{"address": "wg.example.com", "port": 51820, "public_key": WG_PUBLIC, "allowed_ips": ["0.0.0.0/0", "::/0"]}]
        }],
        "outbounds": [
            {"type": "fkbrain", "tag": "fkbrain", "outbounds": ["ss-a", "wg", "fk-direct", "fk-block"]},
            {"type": "shadowsocks", "tag": "ss-a", "server": "1.2.3.4", "server_port": 8388, "method": "aes-128-gcm", "password": "pass"},
            {"type": "direct", "tag": "fk-direct"},
            {"type": "block", "tag": "fk-block"}
        ],
        "route": {"final": "fkbrain", "auto_detect_interface": true, "default_domain_resolver": "fkdns"}
    });
    write_out("minimal", &g.config);
    assert_eq!(g.config, expected, "actual {}", g.config);
    let custom = GenerateOptions {
        tun: generate::TunOptions { mtu: Some(1500), strict_route: true, stack: Some("gvisor".into()), ..Default::default() },
        log_level: Some("warn".into()),
    };
    let g = generate(&r, &custom).unwrap();
    assert_eq!(g.config["inbounds"][0]["mtu"], 1500);
    assert_eq!(g.config["inbounds"][0]["strict_route"], true);
    assert_eq!(g.config["inbounds"][0]["stack"], "gvisor");
    assert_eq!(g.config["log"]["level"], "warn");
    write_out("minimal-custom-tun", &g.config);
}

#[test]
fn error_paths() {
    let ss = "{name: a, type: ss, server: s.example.com, port: 1, cipher: aes-128-gcm, password: p}";
    let cases: Vec<(&str, String, &str)> = vec![
        ("clash tailscale node", "proxies:\n  - {name: ts, type: tailscale}".to_string(), "tailscale nodes are not supported"),
        ("clash openvpn node", "proxies:\n  - {name: ov, type: openvpn, server: o.example.com}".to_string(), "openvpn nodes are not supported"),
        ("clash zerotier node", "proxies:\n  - {name: zt, type: zerotier}".to_string(), "zerotier nodes are not supported"),
        ("clash easytier node", "proxies:\n  - {name: et, type: easytier}".to_string(), "easytier nodes are not supported"),
        ("clash dns node", "proxies:\n  - {name: d, type: dns}".to_string(), "type dns"),
        ("clash unknown type", "proxies:\n  - {name: x, type: nope, server: s}".to_string(), "unknown proxy type"),
        ("clash missing server", "proxies:\n  - {name: x, type: vmess, port: 1}".to_string(), "missing server"),
        (
            "proxy-providers",
            format!("proxy-providers:\n  p1: {{type: http, url: 'https://x.example.com/sub'}}\nproxies:\n  - {ss}"),
            "proxy providers are not supported",
        ),
        ("group use", format!("proxies:\n  - {ss}\nproxy-groups:\n  - {{name: g, type: select, use: [p1]}}"), "use / include-all-providers"),
        ("relay group", format!("proxies:\n  - {ss}\nproxy-groups:\n  - {{name: g, type: relay, proxies: [a]}}"), "relay groups were removed"),
        (
            "lookaround filter",
            format!("proxies:\n  - {ss}\nproxy-groups:\n  - {{name: g, type: select, include-all: true, filter: '^(?!jp).*'}}"),
            "does not compile here",
        ),
        (
            "dialer-proxy names a group",
            format!("proxies:\n  - {{name: b, type: ss, server: s, port: 1, cipher: aes-128-gcm, password: p, dialer-proxy: g}}\n  - {ss}\nproxy-groups:\n  - {{name: g, type: select, proxies: [a]}}"),
            "is a group",
        ),
        (
            "dialer-proxy unknown",
            "proxies:\n  - {name: b, type: ss, server: s, port: 1, cipher: aes-128-gcm, password: p, dialer-proxy: nowhere}".to_string(),
            "names no node",
        ),
        (
            "dialer-proxy loop",
            "proxies:\n  - {name: a, type: ss, server: s, port: 1, cipher: aes-128-gcm, password: p, dialer-proxy: b}\n  - {name: b, type: ss, server: s, port: 1, cipher: aes-128-gcm, password: p, dialer-proxy: a}".to_string(),
            "chained proxy loop: a -> b -> a",
        ),
        (
            "group loop",
            format!("proxies:\n  - {ss}\nproxy-groups:\n  - {{name: g1, type: select, proxies: [g2]}}\n  - {{name: g2, type: select, proxies: [g1]}}"),
            "group loop",
        ),
        ("unknown group member", format!("proxies:\n  - {ss}\nproxy-groups:\n  - {{name: g, type: select, proxies: [zz]}}"), "unknown member"),
        ("unknown rule target", format!("proxies:\n  - {ss}\nrules:\n  - MATCH,Nope"), "unknown proxy or group"),
        ("duplicate names", format!("proxies:\n  - {ss}\n  - {ss}"), "duplicate name"),
        ("reserved name", "proxies:\n  - {name: fkbrain, type: ss, server: s, port: 1, cipher: aes-128-gcm, password: p}".to_string(), "reserved"),
        (
            "sing-box tailscale endpoint",
            json!({"endpoints": [{"type": "tailscale", "tag": "ts"}]}).to_string(),
            "tailscale endpoints are not supported",
        ),
        (
            "sing-box openvpn endpoint",
            json!({"endpoints": [{"type": "openvpn", "tag": "ov"}]}).to_string(),
            "openvpn endpoints are not supported",
        ),
        (
            "sing-box openconnect endpoint",
            json!({"endpoints": [{"type": "openconnect", "tag": "oc"}]}).to_string(),
            "openconnect endpoints are not supported",
        ),
        (
            "sing-box wireguard outbound",
            json!({"outbounds": [{"type": "wireguard", "tag": "w"}]}).to_string(),
            "use a WireGuard endpoint",
        ),
        (
            "sing-box detour loop",
            json!({"outbounds": [
                {"type": "socks", "tag": "a", "server": "1.1.1.1", "server_port": 1, "detour": "b"},
                {"type": "socks", "tag": "b", "server": "1.1.1.1", "server_port": 1, "detour": "a"}
            ]})
            .to_string(),
            "chained proxy loop",
        ),
        (
            "sing-box detour to a group",
            json!({"outbounds": [
                {"type": "socks", "tag": "a", "server": "1.1.1.1", "server_port": 1, "detour": "s"},
                {"type": "selector", "tag": "s", "outbounds": ["a"]}
            ]})
            .to_string(),
            "is a group",
        ),
        ("xhttp insecure link", format!("vless://{UUID}@x.example.com:443?type=xhttp&security=tls&allowInsecure=1#x"), "Xray 26 removed"),
        ("mieru profile link", "mieru://AAAA".to_string(), "mierus://"),
        ("kcp link", format!("vless://{UUID}@k.example.com:443?type=kcp#k"), "transport kcp"),
    ];
    let mut failures = Vec::new();
    for (name, input, needle) in cases {
        match import_text(&input) {
            Ok(r) => failures.push(format!("{name}: imported, expected an error containing {needle:?} (nodes {:?})", r.nodes.iter().map(|n| &n.tag).collect::<Vec<_>>())),
            Err(e) => {
                if !e.to_string().contains(needle) {
                    failures.push(format!("{name}: error {:?} does not contain {needle:?}", e.to_string()));
                }
            }
        }
    }
    assert!(failures.is_empty(), "{} error path(s) wrong:\n{}", failures.len(), failures.join("\n"));
}

#[test]
fn chained_proxies_generate_detours() {
    let yaml = "proxies:\n  - {name: entry, type: ss, server: e.example.com, port: 1, cipher: aes-128-gcm, password: p, udp: true}\n  - {name: mid, type: ss, server: m.example.com, port: 2, cipher: aes-128-gcm, password: p, udp: true, dialer-proxy: entry}\n  - {name: exit, type: mieru, server: x.example.com, port: 3, transport: TCP, username: u, password: p, dialer-proxy: mid}";
    let r = import_text(yaml).unwrap();
    let g = generate(&r, &GenerateOptions::default()).unwrap();
    let outbounds = g.config["outbounds"].as_array().unwrap();
    let by_tag = |tag: &str| outbounds.iter().find(|o| o["tag"] == tag).unwrap().clone();
    assert!(by_tag("entry").get("detour").is_none());
    assert_eq!(by_tag("mid")["detour"], "entry");
    let exit = by_tag("exit");
    assert_eq!((exit["type"].as_str(), exit["detour"].as_str()), (Some("mihomo"), Some("mid")));
    assert!(exit["proxy"].get("dialer-proxy").is_none(), "dialer-proxy must move to the plugin's detour");
    write_out("chain", &g.config);
}
