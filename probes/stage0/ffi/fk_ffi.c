#include "fk_ffi.h"

#include <stddef.h>

/* Written once at startup, before any decide call (see ffi.Init). */
static fk_decide_fn fk_decider = NULL;

void fkgo_register_decider(fk_decide_fn decide) {
	fk_decider = decide;
}

int32_t fkgo_has_decider(void) {
	return fk_decider != NULL;
}

int32_t fkgo_decide(const fk_conn_meta *meta, fk_decision *out) {
	if (fk_decider == NULL) {
		return -1;
	}
	return fk_decider(meta, out);
}

/* Same cgo crossing, no Rust work: isolates the cgo overhead. */
int32_t fkgo_noop(const fk_conn_meta *meta, fk_decision *out) {
	out->action = FK_ACTION_ROUTE;
	out->outbound = meta->dst_port & 1;
	return 0;
}
