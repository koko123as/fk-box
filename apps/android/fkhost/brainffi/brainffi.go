// Package brainffi links the Rust brain (crates/brain-ffi, static library
// libfkbox_brain_ffi.a; set CGO_LDFLAGS=-L<dir>) into the Go host:
// fkb_brain_attach hands the brain the host's fkb_host_api table
// (export.go) and returns decide, which becomes fkbrain's Decider. decide
// takes the fixed C structs straight from Go memory (plugin.Meta /
// plugin.Decision have the C layout); no JSON, no copy, no allocation.
package brainffi

/*
#cgo CFLAGS: -I${SRCDIR}/../../../../crates/brain-ffi/include
#cgo LDFLAGS: -lfkbox_brain_ffi -lm -ldl -lpthread
#cgo linux,!android LDFLAGS: -lgcc_s -lutil -lrt
#include <stdlib.h>
#include "shim.h"
*/
import "C"

import (
	"runtime/cgo"
	"sync"
	"unsafe"

	"github.com/koko123as/fk-box/apps/android/fkhost/host"
	"github.com/koko123as/fk-box/apps/android/fkhost/plugin"

	E "github.com/sagernet/sing/common/exceptions"
)

type rustDecider struct {
	decide C.fkb_decide_fn
}

func (d rustDecider) Decide(meta *plugin.Meta, out *plugin.Decision) int32 {
	return int32(C.fkhost_call_decide(d.decide, (*C.fkb_conn_meta)(unsafe.Pointer(meta)), (*C.fkb_decision)(unsafe.Pointer(out))))
}

var (
	attachAccess sync.Mutex
	attached     cgo.Handle
)

// CheckLayout compares the Go mirror with the brain's struct sizes.
func CheckLayout() error {
	checks := []struct {
		which C.uint32_t
		size  uintptr
		name  string
	}{
		{C.FKB_STRUCT_CONN_META, unsafe.Sizeof(plugin.Meta{}), "fkb_conn_meta"},
		{C.FKB_STRUCT_DECISION, unsafe.Sizeof(plugin.Decision{}), "fkb_decision"},
		{C.FKB_STRUCT_HOST_API, unsafe.Sizeof(C.fkb_host_api{}), "fkb_host_api"},
		{C.FKB_STRUCT_BRAIN_API, unsafe.Sizeof(C.fkb_brain_api{}), "fkb_brain_api"},
		{C.FKB_STRUCT_STATS, unsafe.Sizeof(C.fkb_stats{}), "fkb_stats"},
	}
	for _, check := range checks {
		if got := uintptr(C.fkb_struct_size(check.which)); got != check.size {
			return E.New("brain ABI: ", check.name, " is ", got, " bytes in the brain, ", check.size, " here")
		}
	}
	if version := uint32(C.fkb_brain_abi_version()); version != plugin.ABIVersion {
		return E.New("brain ABI version ", version, ", host ", plugin.ABIVersion)
	}
	return nil
}

// Attach registers h with the brain (fkb_brain_attach) and installs the
// brain's decide as fkbrain's decider. Once per process; a later call
// replaces the host table.
func Attach(h *host.Host) error {
	if err := CheckLayout(); err != nil {
		return err
	}
	attachAccess.Lock()
	defer attachAccess.Unlock()
	handle := cgo.NewHandle(h)
	hostAPI := (*C.fkb_host_api)(C.calloc(1, C.size_t(unsafe.Sizeof(C.fkb_host_api{}))))
	defer C.free(unsafe.Pointer(hostAPI))
	brainAPI := (*C.fkb_brain_api)(C.calloc(1, C.size_t(unsafe.Sizeof(C.fkb_brain_api{}))))
	defer C.free(unsafe.Pointer(brainAPI))
	C.fkhost_fill_host_api(hostAPI, C.uintptr_t(handle))
	if code := C.fkb_brain_attach(hostAPI, brainAPI); code != C.FKB_OK {
		handle.Delete()
		return E.New("fkb_brain_attach: ", int32(code))
	}
	if brainAPI.abi_version != C.FKB_ABI_VERSION || uintptr(brainAPI.size) != unsafe.Sizeof(C.fkb_brain_api{}) || brainAPI.decide == nil {
		handle.Delete()
		return E.New("fkb_brain_attach: unexpected brain table")
	}
	if attached != 0 {
		attached.Delete()
	}
	attached = handle
	plugin.SetDecider(rustDecider{decide: brainAPI.decide})
	return nil
}

func control(call func(profile, options C.fkb_str, err *C.fkb_buf) C.int32_t, profile string, options string) error {
	profileC := C.CString(profile)
	defer C.free(unsafe.Pointer(profileC))
	optionsC := C.CString(options)
	defer C.free(unsafe.Pointer(optionsC))
	const errCap = 4096
	errBytes := (*C.uint8_t)(C.malloc(errCap))
	defer C.free(unsafe.Pointer(errBytes))
	errBuf := (*C.fkb_buf)(C.calloc(1, C.size_t(unsafe.Sizeof(C.fkb_buf{}))))
	defer C.free(unsafe.Pointer(errBuf))
	errBuf.ptr = errBytes
	errBuf.cap = errCap
	code := call(
		C.fkb_str{ptr: (*C.uint8_t)(unsafe.Pointer(profileC)), len: C.size_t(len(profile))},
		C.fkb_str{ptr: (*C.uint8_t)(unsafe.Pointer(optionsC)), len: C.size_t(len(options))},
		errBuf,
	)
	if code != C.FKB_OK {
		message := C.GoStringN((*C.char)(unsafe.Pointer(errBytes)), C.int(errBuf.len))
		return E.New("brain error ", int32(code), ": ", message)
	}
	return nil
}

// StartProfile asks the brain to import a subscription and start the host
// with the generated configuration (fkb_brain_start_profile). options is
// empty or the JSON object the header documents.
func StartProfile(profile string, options string) error {
	return control(func(p, o C.fkb_str, err *C.fkb_buf) C.int32_t { return C.fkb_brain_start_profile(p, o, err) }, profile, options)
}

// ReloadProfile replaces the running configuration (fkb_brain_reload_profile).
func ReloadProfile(profile string, options string) error {
	return control(func(p, o C.fkb_str, err *C.fkb_buf) C.int32_t { return C.fkb_brain_reload_profile(p, o, err) }, profile, options)
}

// Stop stops the host through the brain (fkb_brain_stop).
func Stop() error {
	const errCap = 1024
	errBytes := (*C.uint8_t)(C.malloc(errCap))
	defer C.free(unsafe.Pointer(errBytes))
	errBuf := (*C.fkb_buf)(C.calloc(1, C.size_t(unsafe.Sizeof(C.fkb_buf{}))))
	defer C.free(unsafe.Pointer(errBuf))
	errBuf.ptr = errBytes
	errBuf.cap = errCap
	if code := C.fkb_brain_stop(errBuf); code != C.FKB_OK {
		return E.New("brain error ", int32(code), ": ", C.GoStringN((*C.char)(unsafe.Pointer(errBytes)), C.int(errBuf.len)))
	}
	return nil
}
