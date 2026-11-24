// C side of package brainffi: the trampolines Go cannot express (calling a
// C function pointer) and the fkb_host_api table pointing at the Go
// functions exported in export.go.
#ifndef FKHOST_BRAINFFI_SHIM_H
#define FKHOST_BRAINFFI_SHIM_H

#include <stddef.h>
#include <stdint.h>

#include "fkbox_brain.h"

int32_t fkhost_call_decide(fkb_decide_fn fn, const fkb_conn_meta *meta, fkb_decision *out);
int32_t fkhost_call_dns_message(fkb_dns_message_fn fn, const uint8_t *message, size_t len, uint32_t flags, uint32_t timeout_ms,
				uint8_t *reply, size_t cap, size_t *reply_len);
int32_t fkhost_call_fakeip_reverse(fkb_fakeip_reverse_fn fn, const uint8_t *ip, uint8_t version, uint8_t *name, size_t cap,
				   size_t *name_len);
void fkhost_fill_host_api(fkb_host_api *api, uintptr_t handle);

#endif
