/*
 * fk-box brain C ABI (version 2: stage 4 adds dns_message and fakeip_reverse
 * to fkb_brain_api and implements resolve).
 *
 * The host is the Go kernel process: one sing-box instance (TUN, forwarding,
 * protocol outbounds, protected dialing) whose fkbrain plugin outbound
 * receives every new connection. The brain is the Rust library (crate
 * fkbox-brain-ffi, static library libfkbox_brain_ffi.a) that owns the rules.
 *
 *   Go -> Rust   decide() once per connection (synchronous, pure computation),
 *                fakeip_reverse() for a fake-ip destination (memory only),
 *                dns_message() / resolve() for the fkdns transport (may
 *                block up to timeout_ms; the brain owns hosts, cache,
 *                fake-ip and the choice of upstream and outbound).
 *   Rust -> Go   start/stop/reload with a sing-box JSON configuration,
 *                probe() an outbound, exchange() a DNS message through an
 *                outbound, stats().
 *
 * Bootstrap: the host calls fkb_brain_attach() once, before anything else,
 * handing over its function table and receiving the brain's.
 *
 * Every struct here has a fixed layout, checked below with static asserts
 * and on the Rust side with const asserts; both sides also exchange sizes at
 * run time (abi_version + size fields, fkb_struct_size()). No JSON crosses
 * the decide path. 64-bit little-endian targets only (fk-box ships arm64).
 *
 * The full contract (memory ownership, threading, errors and timeouts, DNS
 * dependent rules) is docs/architecture/brain-ffi.md.
 */
#ifndef FKBOX_BRAIN_H
#define FKBOX_BRAIN_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define FKB_ABI_VERSION 2u
#define FKB_DOMAIN_MAX 256
#define FKB_NAME_MAX 128
#define FKB_RESOLVE_MAX 16

/* Return codes of every function in this ABI. */
enum {
	FKB_OK = 0,
	FKB_E_INVALID = -1,       /* NULL pointer, wrong abi_version/size, malformed field */
	FKB_E_NOT_READY = -2,     /* decide: no rule snapshot installed yet */
	FKB_E_PANIC = -3,         /* the callee failed internally (Rust panic caught) */
	FKB_E_UNIMPLEMENTED = -4, /* the function exists in the ABI but not in this build */
	FKB_E_TIMEOUT = -5,       /* the host gave up after timeout_ms */
	FKB_E_BUFFER = -6,        /* output buffer too small; buf->len holds the size needed */
	FKB_E_HOST = -7,          /* the host failed; err holds its message */
	FKB_E_NOT_FOUND = -8      /* unknown outbound tag */
};

/* fkb_conn_meta.network */
enum { FKB_NET_UNKNOWN = 0, FKB_NET_TCP = 1, FKB_NET_UDP = 2 };

/* fkb_conn_meta.dst_ip_version / src_ip_version: 0 = no address. */
enum { FKB_IP_NONE = 0, FKB_IP_V4 = 4, FKB_IP_V6 = 6 };

/* fkb_conn_meta.protocol: what the host's sniffer found (sing-box names). */
enum {
	FKB_PROTO_NONE = 0,
	FKB_PROTO_HTTP = 1,
	FKB_PROTO_TLS = 2,
	FKB_PROTO_QUIC = 3,
	FKB_PROTO_DNS = 4,
	FKB_PROTO_BITTORRENT = 5,
	FKB_PROTO_STUN = 6,
	FKB_PROTO_DTLS = 7,
	FKB_PROTO_SSH = 8,
	FKB_PROTO_RDP = 9,
	FKB_PROTO_NTP = 10
};

/* fkb_conn_meta.domain_source */
enum { FKB_DOMAIN_NONE = 0, FKB_DOMAIN_FQDN = 1, FKB_DOMAIN_SNIFFED = 2 };

/* fkb_conn_meta.flags */
#define FKB_META_PACKAGE_TRUNCATED 0x01u
#define FKB_META_PROCESS_TRUNCATED 0x02u

#define FKB_UID_UNKNOWN (-1)
#define FKB_INBOUND_UNKNOWN UINT32_MAX

/*
 * One new connection. Filled by the host for every decide() call, in memory
 * the host owns (a Go struct mirroring this layout, no Go pointers inside);
 * the brain reads it during the call only. Strings are UTF-8, not
 * NUL-terminated, with their length in the *_len field.
 */
typedef struct fkb_conn_meta {
	uint32_t abi_version;  /*   0  FKB_ABI_VERSION */
	uint32_t size;         /*   4  sizeof(fkb_conn_meta) */
	uint8_t network;       /*   8  FKB_NET_* */
	uint8_t dst_ip_version;/*   9  FKB_IP_*; NONE for a domain-only destination */
	uint8_t src_ip_version;/*  10  FKB_IP_* */
	uint8_t protocol;      /*  11  FKB_PROTO_* */
	uint16_t src_port;     /*  12 */
	uint16_t dst_port;     /*  14 */
	uint8_t src_ip[16];    /*  16  IPv4 in the first 4 bytes */
	uint8_t dst_ip[16];    /*  32  IPv4 in the first 4 bytes; IPv4-mapped IPv6 is read as IPv4 */
	int32_t uid;           /*  48  Android app uid, FKB_UID_UNKNOWN */
	uint32_t inbound_index;/*  52  index into the inbound table of the running configuration */
	uint16_t domain_len;   /*  56  bytes used in domain[] */
	uint8_t domain_source; /*  58  FKB_DOMAIN_* */
	uint8_t flags;         /*  59  FKB_META_* */
	uint8_t package_len;   /*  60  bytes used in package_name[] */
	uint8_t process_len;   /*  61  bytes used in process_name[] */
	uint16_t reserved;     /*  62  0 */
	uint8_t domain[FKB_DOMAIN_MAX];     /*  64  FQDN destination, or sniffed host / SNI */
	uint8_t package_name[FKB_NAME_MAX]; /* 320  Android package name */
	uint8_t process_name[FKB_NAME_MAX]; /* 448  process name (desktop) */
} fkb_conn_meta;                        /* 576 */

/* fkb_decision.action */
enum {
	FKB_ACTION_ROUTE = 0,       /* use outbound[decision.outbound] */
	FKB_ACTION_REJECT = 1,      /* reject (TCP RST / ICMP unreachable) */
	FKB_ACTION_REJECT_DROP = 2, /* drop without a reply */
	FKB_ACTION_HIJACK_DNS = 3   /* hand the connection to the DNS handler */
};

/* fkb_decision.flags */
#define FKB_DECISION_DNS_MISS 0x0001u /* a rule needed the DNS cache and it missed (prefetch queued) */

#define FKB_RULE_FINAL UINT32_MAX

/* dns_message() flags: the query came from an app (fkbrain's port-53
 * hijack), so a fake-ip answer is allowed. Without it (sing-box resolving a
 * domain destination or a server name for an outbound) the brain answers
 * with real addresses only. */
#define FKB_DNS_CLIENT 0x0001u

/* What decide() returns, written by the brain into host memory. */
typedef struct fkb_decision {
	uint16_t action;     /*  0  FKB_ACTION_* */
	uint16_t flags;      /*  2  FKB_DECISION_* */
	uint32_t outbound;   /*  4  index into the outbound table of `generation` */
	uint32_t rule_index; /*  8  top-level rule that matched, FKB_RULE_FINAL */
	uint32_t generation; /* 12  configuration generation the decision was made with */
} fkb_decision;          /* 16 */

/* A borrowed byte string: valid for the duration of the call only. */
typedef struct fkb_str {
	const uint8_t *ptr;
	size_t len;
} fkb_str; /* 16 */

/*
 * An output buffer owned by the caller. The callee writes at most cap bytes
 * at ptr and sets len; when the output does not fit it sets len to the size
 * needed and returns FKB_E_BUFFER (error buffers are truncated instead).
 */
typedef struct fkb_buf {
	uint8_t *ptr;
	size_t cap;
	size_t len;
} fkb_buf; /* 24 */

/* resolve(): one name, one query type, real addresses only (hosts, cache, then
 * an upstream through the outbound the brain picks). */
typedef struct fkb_resolve_query {
	uint32_t abi_version; /*  0 */
	uint32_t size;        /*  4 */
	uint16_t qtype;       /*  8  1 = A, 28 = AAAA, 0 = both */
	uint16_t domain_len;  /* 10 */
	uint32_t timeout_ms;  /* 12  how long the caller waits at most */
	uint8_t domain[FKB_DOMAIN_MAX]; /* 16 */
} fkb_resolve_query;      /* 272 */

typedef struct fkb_resolve_result {
	uint32_t count;      /*  0  addresses written */
	uint32_t ttl_secs;   /*  4 */
	int32_t rcode;       /*  8  DNS RCODE (0 NOERROR, 3 NXDOMAIN, ...) */
	uint32_t reserved;   /* 12 */
	uint8_t family[FKB_RESOLVE_MAX];    /* 16  4 or 6 per address */
	uint8_t addrs[FKB_RESOLVE_MAX][16]; /* 32 */
} fkb_resolve_result;    /* 288 */

/* probe() result. */
typedef struct fkb_probe_result {
	int32_t http_status; /*  0  HTTP status, or -1 when no response */
	uint32_t latency_ms; /*  4  time to the response headers */
	uint32_t flags;      /*  8  0 */
	uint32_t reserved;   /* 12 */
} fkb_probe_result;      /* 16 */

/* stats() result. */
typedef struct fkb_stats {
	uint64_t uplink_bytes;     /*  0  totals since start */
	uint64_t downlink_bytes;   /*  8 */
	uint64_t uplink_rate;      /* 16  bytes/s */
	uint64_t downlink_rate;    /* 24 */
	uint32_t connections_tcp;  /* 32 */
	uint32_t connections_udp;  /* 36 */
	uint64_t memory_inuse;     /* 40 */
	uint32_t goroutines;       /* 48 */
	uint32_t reserved;         /* 52 */
} fkb_stats;                   /* 56 */

/* Go -> Rust. Thread-safe; decide must not block (see the contract). */
typedef int32_t (*fkb_decide_fn)(const fkb_conn_meta *meta, fkb_decision *out);
typedef int32_t (*fkb_resolve_fn)(const fkb_resolve_query *query, fkb_resolve_result *out);
/* One DNS message (wire format, any query type) answered by the brain; the
 * reply (same ID) goes to `reply` (FKB_E_BUFFER with len = size needed when
 * it does not fit). Blocks at most timeout_ms. */
typedef int32_t (*fkb_dns_message_fn)(fkb_str query, uint32_t flags, uint32_t timeout_ms, fkb_buf *reply);
/* Is ip (16 bytes, IPv4 in the first 4; ip_version 4 or 6) a fake-ip
 * address? FKB_OK with domain->len 0: not a fake address; FKB_OK with the
 * domain written: the name it stands for; FKB_E_NOT_FOUND: inside the
 * fake-ip range but unknown (stale). Memory only, never blocks: the host
 * calls it per connection before decide. */
typedef int32_t (*fkb_fakeip_reverse_fn)(const uint8_t *ip, uint8_t ip_version, fkb_buf *domain);

typedef struct fkb_brain_api {
	uint32_t abi_version;                 /*  0 */
	uint32_t size;                        /*  4 */
	fkb_decide_fn decide;                 /*  8 */
	fkb_resolve_fn resolve;               /* 16 */
	fkb_dns_message_fn dns_message;       /* 24 */
	fkb_fakeip_reverse_fn fakeip_reverse; /* 32 */
} fkb_brain_api;                          /* 40 */

/*
 * Rust -> Go. `ctx` is the host's opaque handle (a runtime/cgo.Handle value),
 * passed back unchanged; the brain never dereferences it. Every function may
 * block (the brain calls them from its own threads, never from decide) and
 * must be safe to call from any thread. A NULL entry means not provided
 * (the brain reports FKB_E_UNIMPLEMENTED).
 */
typedef struct fkb_host_api {
	uint32_t abi_version; /*  0 */
	uint32_t size;        /*  4 */
	void *ctx;            /*  8 */
	/* Start sing-box with this configuration; its outbound/inbound order is the
	 * index table of `generation`. */
	int32_t (*start)(void *ctx, fkb_str config_json, uint32_t generation, fkb_buf *err); /* 16 */
	int32_t (*stop)(void *ctx, fkb_buf *err);                                             /* 24 */
	/* Replace the running configuration; keep the previous generation's
	 * tables until decisions stop naming it. */
	int32_t (*reload)(void *ctx, fkb_str config_json, uint32_t generation, fkb_buf *err); /* 32 */
	/* One HTTP(S) request to url through the outbound (a delay test). */
	int32_t (*probe)(void *ctx, fkb_str outbound_tag, fkb_str url, uint32_t timeout_ms, fkb_probe_result *out,
			 fkb_buf *err); /* 40 */
	/* One DNS message to upstream (udp://, tcp://, tls://, https:// address)
	 * through the outbound; the reply goes to response. */
	int32_t (*exchange)(void *ctx, fkb_str outbound_tag, fkb_str upstream, fkb_str message, uint32_t timeout_ms,
			    fkb_buf *response, fkb_buf *err); /* 48 */
	int32_t (*stats)(void *ctx, fkb_stats *out); /* 56 */
} fkb_host_api;                                  /* 64 */

/* fkb_struct_size() selectors. */
enum {
	FKB_STRUCT_CONN_META = 1,
	FKB_STRUCT_DECISION = 2,
	FKB_STRUCT_STR = 3,
	FKB_STRUCT_BUF = 4,
	FKB_STRUCT_RESOLVE_QUERY = 5,
	FKB_STRUCT_RESOLVE_RESULT = 6,
	FKB_STRUCT_PROBE_RESULT = 7,
	FKB_STRUCT_STATS = 8,
	FKB_STRUCT_BRAIN_API = 9,
	FKB_STRUCT_HOST_API = 10
};

/*
 * Exported by the brain.
 *
 * fkb_brain_attach: called once by the host at startup, before any other
 * call and before any connection. The brain copies *host (may be NULL for a
 * host without callbacks) and writes its own table into *brain (host
 * memory). Returns FKB_E_INVALID on a version or size mismatch.
 */
int32_t fkb_brain_attach(const fkb_host_api *host, fkb_brain_api *brain);
uint32_t fkb_brain_abi_version(void);
/* The brain's size of a struct (FKB_STRUCT_*), 0 for an unknown selector. */
uint32_t fkb_struct_size(uint32_t which);

/*
 * Control path (stage 3): once per configuration change, never per
 * connection. The brain imports `profile` (Clash/mihomo YAML, sing-box JSON,
 * share links or Base64 of them), generates the sing-box configuration,
 * compiles its rules against fkbrain's outbound table and calls the host's
 * start (or reload) with it. `options` is empty or a JSON object with the
 * optional keys tun_address (list), tun_auto_route, tun_stack, tun_mtu,
 * log_level, dns (the brain's DNS settings, docs/architecture/brain-dns.md).
 * On failure `err` (NULL or a caller buffer, truncated) holds the
 * reason and a reload keeps the previous configuration.
 */
int32_t fkb_brain_start_profile(fkb_str profile, fkb_str options, fkb_buf *err);
int32_t fkb_brain_reload_profile(fkb_str profile, fkb_str options, fkb_buf *err);
int32_t fkb_brain_stop(fkb_buf *err);

/* Layout checks (C11): the Rust side asserts the same numbers. */
#ifndef __cplusplus
_Static_assert(sizeof(void *) == 8, "fkb ABI: 64-bit targets only");
_Static_assert(sizeof(fkb_conn_meta) == 576, "fkb_conn_meta size");
_Static_assert(offsetof(fkb_conn_meta, network) == 8, "fkb_conn_meta.network");
_Static_assert(offsetof(fkb_conn_meta, src_port) == 12, "fkb_conn_meta.src_port");
_Static_assert(offsetof(fkb_conn_meta, dst_port) == 14, "fkb_conn_meta.dst_port");
_Static_assert(offsetof(fkb_conn_meta, src_ip) == 16, "fkb_conn_meta.src_ip");
_Static_assert(offsetof(fkb_conn_meta, dst_ip) == 32, "fkb_conn_meta.dst_ip");
_Static_assert(offsetof(fkb_conn_meta, uid) == 48, "fkb_conn_meta.uid");
_Static_assert(offsetof(fkb_conn_meta, inbound_index) == 52, "fkb_conn_meta.inbound_index");
_Static_assert(offsetof(fkb_conn_meta, domain_len) == 56, "fkb_conn_meta.domain_len");
_Static_assert(offsetof(fkb_conn_meta, domain_source) == 58, "fkb_conn_meta.domain_source");
_Static_assert(offsetof(fkb_conn_meta, package_len) == 60, "fkb_conn_meta.package_len");
_Static_assert(offsetof(fkb_conn_meta, domain) == 64, "fkb_conn_meta.domain");
_Static_assert(offsetof(fkb_conn_meta, package_name) == 320, "fkb_conn_meta.package_name");
_Static_assert(offsetof(fkb_conn_meta, process_name) == 448, "fkb_conn_meta.process_name");
_Static_assert(sizeof(fkb_decision) == 16, "fkb_decision size");
_Static_assert(offsetof(fkb_decision, outbound) == 4, "fkb_decision.outbound");
_Static_assert(offsetof(fkb_decision, rule_index) == 8, "fkb_decision.rule_index");
_Static_assert(offsetof(fkb_decision, generation) == 12, "fkb_decision.generation");
_Static_assert(sizeof(fkb_str) == 16, "fkb_str size");
_Static_assert(sizeof(fkb_buf) == 24, "fkb_buf size");
_Static_assert(sizeof(fkb_resolve_query) == 272, "fkb_resolve_query size");
_Static_assert(offsetof(fkb_resolve_query, domain) == 16, "fkb_resolve_query.domain");
_Static_assert(sizeof(fkb_resolve_result) == 288, "fkb_resolve_result size");
_Static_assert(offsetof(fkb_resolve_result, addrs) == 32, "fkb_resolve_result.addrs");
_Static_assert(sizeof(fkb_probe_result) == 16, "fkb_probe_result size");
_Static_assert(sizeof(fkb_stats) == 56, "fkb_stats size");
_Static_assert(offsetof(fkb_stats, memory_inuse) == 40, "fkb_stats.memory_inuse");
_Static_assert(sizeof(fkb_brain_api) == 40, "fkb_brain_api size");
_Static_assert(offsetof(fkb_brain_api, dns_message) == 24, "fkb_brain_api.dns_message");
_Static_assert(offsetof(fkb_brain_api, fakeip_reverse) == 32, "fkb_brain_api.fakeip_reverse");
_Static_assert(sizeof(fkb_host_api) == 64, "fkb_host_api size");
_Static_assert(offsetof(fkb_host_api, start) == 16, "fkb_host_api.start");
_Static_assert(offsetof(fkb_host_api, stats) == 56, "fkb_host_api.stats");
#endif

#ifdef __cplusplus
}
#endif

#endif /* FKBOX_BRAIN_H */
