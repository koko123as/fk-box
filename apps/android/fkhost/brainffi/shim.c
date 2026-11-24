#include "shim.h"

#include "_cgo_export.h"

int32_t fkhost_call_decide(fkb_decide_fn fn, const fkb_conn_meta *meta, fkb_decision *out) {
	if (fn == NULL) {
		return FKB_E_NOT_READY;
	}
	return fn(meta, out);
}

int32_t fkhost_call_dns_message(fkb_dns_message_fn fn, const uint8_t *message, size_t len, uint32_t flags, uint32_t timeout_ms,
				uint8_t *reply, size_t cap, size_t *reply_len) {
	if (fn == NULL) {
		return FKB_E_NOT_READY;
	}
	fkb_str query = {message, len};
	fkb_buf out = {reply, cap, 0};
	int32_t code = fn(query, flags, timeout_ms, &out);
	*reply_len = out.len;
	return code;
}

int32_t fkhost_call_fakeip_reverse(fkb_fakeip_reverse_fn fn, const uint8_t *ip, uint8_t version, uint8_t *name, size_t cap,
				   size_t *name_len) {
	if (fn == NULL) {
		*name_len = 0;
		return FKB_OK;
	}
	fkb_buf out = {name, cap, 0};
	int32_t code = fn(ip, version, &out);
	*name_len = out.len;
	return code;
}

void fkhost_fill_host_api(fkb_host_api *api, uintptr_t handle) {
	api->abi_version = FKB_ABI_VERSION;
	api->size = (uint32_t)sizeof(fkb_host_api);
	api->ctx = (void *)handle;
	api->start = fkhostStart;
	api->stop = fkhostStop;
	api->reload = fkhostReload;
	api->probe = fkhostProbe;
	api->exchange = fkhostExchange;
	api->stats = fkhostStats;
}
