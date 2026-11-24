// C side of package brainffi: the trampolines Go cannot express (calling a
// C function pointer) and the fkb_host_api table pointing at the Go
// functions exported in export.go.
#ifndef FKHOST_BRAINFFI_SHIM_H
#define FKHOST_BRAINFFI_SHIM_H

#include <stdint.h>

#include "fkbox_brain.h"

int32_t fkhost_call_decide(fkb_decide_fn fn, const fkb_conn_meta *meta, fkb_decision *out);
void fkhost_fill_host_api(fkb_host_api *api, uintptr_t handle);

#endif
