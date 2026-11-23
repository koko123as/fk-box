/* Stage-0 Go -> Rust decide ABI. Fixed-layout C structs, no JSON, no Go
 * pointers inside (so cgo may pass a Go-allocated struct directly). The Rust
 * side (../rustcb) mirrors these with #[repr(C)]. */
#ifndef FK_FFI_H
#define FK_FFI_H

#include <stdint.h>

#define FK_DOMAIN_MAX 256

enum {
	FK_NET_TCP = 1,
	FK_NET_UDP = 2,
};

enum {
	FK_ACTION_ROUTE = 0,
	FK_ACTION_REJECT = 1,
};

typedef struct fk_conn_meta {
	uint8_t network;    /* FK_NET_* */
	uint8_t ip_version; /* 4, 6, or 0 when the destination is a domain only */
	uint16_t src_port;
	uint16_t dst_port;
	uint16_t domain_len; /* bytes used in domain[] (sniffed or FQDN destination) */
	uint8_t src_ip[16];  /* IPv4 in the first 4 bytes */
	uint8_t dst_ip[16];
	int32_t uid;            /* Android app uid, -1 if unknown */
	uint32_t inbound_index; /* index into the host's inbound table */
	uint8_t domain[FK_DOMAIN_MAX];
} fk_conn_meta;

typedef struct fk_decision {
	int32_t action;    /* FK_ACTION_* */
	uint32_t outbound; /* index into the host's outbound table when ROUTE */
} fk_decision;

/* Pure, synchronous, thread-safe; returns 0 on success. */
typedef int32_t (*fk_decide_fn)(const fk_conn_meta *meta, fk_decision *out);
typedef void (*fk_register_fn)(fk_decide_fn decide);

/* Go side (fk_ffi.c) */
void fkgo_register_decider(fk_decide_fn decide);
int32_t fkgo_has_decider(void);
int32_t fkgo_decide(const fk_conn_meta *meta, fk_decision *out);
int32_t fkgo_noop(const fk_conn_meta *meta, fk_decision *out);

/* Rust side (rustcb, staticlib libfkrustcb.a) */
void fkrust_init(fk_register_fn register_decider);
uint32_t fkrust_meta_size(void);
uint32_t fkrust_decision_size(void);

#endif
