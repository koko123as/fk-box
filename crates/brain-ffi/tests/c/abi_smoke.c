/* Links the brain's static library from C (as cgo will) and checks the
 * header against the library at run time: sizes, attach, the entry points'
 * error paths. Built and run by .github/workflows/brain.yml. */
#include <stdio.h>
#include <string.h>

#include "fkbox_brain.h"

static int failures = 0;

#define CHECK(cond)                                                            \
	do {                                                                   \
		if (!(cond)) {                                                 \
			fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
			failures++;                                            \
		}                                                              \
	} while (0)

static int32_t host_start(void *ctx, fkb_str json, uint32_t generation, fkb_buf *err) {
	(void)ctx; (void)json; (void)generation; (void)err;
	return FKB_OK;
}

static int32_t host_stop(void *ctx, fkb_buf *err) {
	(void)ctx; (void)err;
	return FKB_OK;
}

int main(void) {
	CHECK(fkb_brain_abi_version() == FKB_ABI_VERSION);
	CHECK(fkb_struct_size(FKB_STRUCT_CONN_META) == sizeof(fkb_conn_meta));
	CHECK(fkb_struct_size(FKB_STRUCT_DECISION) == sizeof(fkb_decision));
	CHECK(fkb_struct_size(FKB_STRUCT_STR) == sizeof(fkb_str));
	CHECK(fkb_struct_size(FKB_STRUCT_BUF) == sizeof(fkb_buf));
	CHECK(fkb_struct_size(FKB_STRUCT_RESOLVE_QUERY) == sizeof(fkb_resolve_query));
	CHECK(fkb_struct_size(FKB_STRUCT_RESOLVE_RESULT) == sizeof(fkb_resolve_result));
	CHECK(fkb_struct_size(FKB_STRUCT_PROBE_RESULT) == sizeof(fkb_probe_result));
	CHECK(fkb_struct_size(FKB_STRUCT_STATS) == sizeof(fkb_stats));
	CHECK(fkb_struct_size(FKB_STRUCT_BRAIN_API) == sizeof(fkb_brain_api));
	CHECK(fkb_struct_size(FKB_STRUCT_HOST_API) == sizeof(fkb_host_api));
	CHECK(fkb_struct_size(0) == 0);

	fkb_host_api host;
	memset(&host, 0, sizeof host);
	host.abi_version = FKB_ABI_VERSION;
	host.size = sizeof host;
	host.start = host_start;
	host.stop = host_stop;
	fkb_brain_api brain;
	memset(&brain, 0, sizeof brain);
	CHECK(fkb_brain_attach(&host, NULL) == FKB_E_INVALID);
	host.size = 8;
	CHECK(fkb_brain_attach(&host, &brain) == FKB_E_INVALID);
	host.size = sizeof host;
	CHECK(fkb_brain_attach(&host, &brain) == FKB_OK);
	CHECK(brain.abi_version == FKB_ABI_VERSION && brain.size == sizeof brain);
	CHECK(brain.decide != NULL && brain.resolve != NULL);

	fkb_conn_meta meta;
	memset(&meta, 0, sizeof meta);
	meta.abi_version = FKB_ABI_VERSION;
	meta.size = sizeof meta;
	meta.network = FKB_NET_TCP;
	meta.dst_port = 443;
	meta.uid = FKB_UID_UNKNOWN;
	meta.inbound_index = FKB_INBOUND_UNKNOWN;
	const char *domain = "www.example.com";
	meta.domain_len = (uint16_t)strlen(domain);
	memcpy(meta.domain, domain, meta.domain_len);
	meta.domain_source = FKB_DOMAIN_SNIFFED;
	fkb_decision out;
	memset(&out, 0, sizeof out);
	/* No rule snapshot installed from C: the host must fall back. */
	CHECK(brain.decide(&meta, &out) == FKB_E_NOT_READY);
	CHECK(brain.decide(NULL, &out) == FKB_E_INVALID);
	meta.size = 304;
	CHECK(brain.decide(&meta, &out) == FKB_E_INVALID);

	fkb_resolve_query query;
	memset(&query, 0, sizeof query);
	query.abi_version = FKB_ABI_VERSION;
	query.size = sizeof query;
	query.qtype = 1;
	fkb_resolve_result result;
	/* An empty name is malformed; resolve itself is implemented since ABI 2. */
	CHECK(brain.resolve(&query, &result) == FKB_E_INVALID);
	CHECK(brain.dns_message != NULL && brain.fakeip_reverse != NULL);
	uint8_t ip[16] = {8, 8, 8, 8};
	uint8_t name[256];
	fkb_buf domain = {name, sizeof name, 0};
	/* No fake-ip pool configured from C: nothing is a fake address. */
	CHECK(brain.fakeip_reverse(ip, 4, &domain) == FKB_OK && domain.len == 0);

	if (failures) {
		fprintf(stderr, "%d check(s) failed\n", failures);
		return 1;
	}
	printf("abi smoke: ok (fkb_conn_meta %zu bytes, fkb_decision %zu bytes)\n", sizeof meta, sizeof out);
	return 0;
}
