#include "shim.h"

#include "_cgo_export.h"

int32_t fkhost_call_decide(fkb_decide_fn fn, const fkb_conn_meta *meta, fkb_decision *out) {
	if (fn == NULL) {
		return FKB_E_NOT_READY;
	}
	return fn(meta, out);
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
