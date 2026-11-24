// Package brainffi links the Rust brain (crates/brain-ffi, static library
// libfkbox_brain_ffi.a; set CGO_LDFLAGS=-L<dir>) into the Go host:
// fkb_brain_attach hands the brain the host's fkb_host_api table
// (export.go) and returns decide, which becomes fkbrain's Decider, and
// dns_message / fakeip_reverse, which become the plugins' DNSBrain. decide
// takes the fixed C structs straight from Go memory (plugin.Meta /
// plugin.Decision have the C layout); no JSON, no copy, no allocation.
package brainffi

/*
#cgo CFLAGS: -I${SRCDIR}/../../../../crates/brain-ffi/include
#cgo LDFLAGS: -lfkbox_brain_ffi -lm -ldl
#cgo !android LDFLAGS: -lpthread
#cgo linux,!android LDFLAGS: -lgcc_s -lutil -lrt
#cgo android LDFLAGS: -llog
#include <stdlib.h>
#include "shim.h"
*/
import "C"

import (
	"context"
	"net/netip"
	"runtime/cgo"
	"sync"
	"time"
	"unsafe"

	"github.com/koko123as/fk-box/apps/android/fkhost/host"
	"github.com/koko123as/fk-box/apps/android/fkhost/plugin"
	"github.com/koko123as/fk-box/apps/android/fkhost/upstream"
)

type rustDecider struct {
	decide C.fkb_decide_fn
}

func (d rustDecider) Decide(meta *plugin.Meta, out *plugin.Decision) int32 {
	return int32(C.fkhost_call_decide(d.decide, (*C.fkb_conn_meta)(unsafe.Pointer(meta)), (*C.fkb_decision)(unsafe.Pointer(out))))
}

// rustDNS is the brain's DNS behind fkdns and fkbrain's fake-ip reverse
// lookup: dns_message (may block up to the query's deadline) and
// fakeip_reverse (memory only).
type rustDNS struct {
	message C.fkb_dns_message_fn
	reverse C.fkb_fakeip_reverse_fn
}

const defaultDNSTimeout = 5 * time.Second

func (d rustDNS) Exchange(ctx context.Context, message []byte, client bool) ([]byte, error) {
	if len(message) == 0 {
		return nil, upstream.NewError("brain dns_message: empty message")
	}
	timeout := defaultDNSTimeout
	if deadline, ok := ctx.Deadline(); ok {
		timeout = time.Until(deadline)
		if timeout <= 0 {
			return nil, context.DeadlineExceeded
		}
	}
	var flags C.uint32_t
	if client {
		flags = C.FKB_DNS_CLIENT
	}
	reply := make([]byte, 4096)
	for attempt := 0; attempt < 2; attempt++ {
		var length C.size_t
		code := C.fkhost_call_dns_message(d.message, (*C.uint8_t)(unsafe.Pointer(&message[0])), C.size_t(len(message)), flags,
			C.uint32_t(max(timeout.Milliseconds(), 1)), (*C.uint8_t)(unsafe.Pointer(&reply[0])), C.size_t(len(reply)), &length)
		switch code {
		case C.FKB_OK:
			return reply[:int(length)], nil
		case C.FKB_E_BUFFER:
			if int(length) > len(reply) && int(length) <= 65535 {
				reply = make([]byte, int(length))
				continue
			}
		}
		return nil, upstream.NewError("brain dns_message: error ", int32(code))
	}
	return nil, upstream.NewError("brain dns_message: reply does not fit")
}

func (d rustDNS) Reverse(addr netip.Addr) (string, bool) {
	var ip [16]byte
	var version C.uint8_t
	addr = addr.Unmap()
	switch {
	case addr.Is4():
		raw := addr.As4()
		copy(ip[:], raw[:])
		version = C.FKB_IP_V4
	case addr.Is6():
		ip = addr.As16()
		version = C.FKB_IP_V6
	default:
		return "", false
	}
	var name [256]byte
	var length C.size_t
	code := C.fkhost_call_fakeip_reverse(d.reverse, (*C.uint8_t)(unsafe.Pointer(&ip[0])), version, (*C.uint8_t)(unsafe.Pointer(&name[0])), C.size_t(len(name)), &length)
	switch code {
	case C.FKB_OK:
		if length == 0 || int(length) > len(name) {
			return "", false
		}
		return string(name[:int(length)]), true
	case C.FKB_E_NOT_FOUND:
		return "", true
	default:
		return "", false
	}
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
			return upstream.NewError("brain ABI: ", check.name, " is ", got, " bytes in the brain, ", check.size, " here")
		}
	}
	if version := uint32(C.fkb_brain_abi_version()); version != plugin.ABIVersion {
		return upstream.NewError("brain ABI version ", version, ", host ", plugin.ABIVersion)
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
		return upstream.NewError("fkb_brain_attach: ", int32(code))
	}
	if brainAPI.abi_version != C.FKB_ABI_VERSION || uintptr(brainAPI.size) != unsafe.Sizeof(C.fkb_brain_api{}) || brainAPI.decide == nil {
		handle.Delete()
		return upstream.NewError("fkb_brain_attach: unexpected brain table")
	}
	if attached != 0 {
		attached.Delete()
	}
	attached = handle
	plugin.SetDecider(rustDecider{decide: brainAPI.decide})
	if brainAPI.dns_message != nil && brainAPI.fakeip_reverse != nil {
		plugin.SetDNSBrain(rustDNS{message: brainAPI.dns_message, reverse: brainAPI.fakeip_reverse})
	}
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
		return upstream.NewError("brain error ", int32(code), ": ", message)
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
		return upstream.NewError("brain error ", int32(code), ": ", C.GoStringN((*C.char)(unsafe.Pointer(errBytes)), C.int(errBuf.len)))
	}
	return nil
}

func newErrBuf(capacity int) (*C.fkb_buf, func() string, func()) {
	bytes := (*C.uint8_t)(C.malloc(C.size_t(capacity)))
	buf := (*C.fkb_buf)(C.calloc(1, C.size_t(unsafe.Sizeof(C.fkb_buf{}))))
	buf.ptr = bytes
	buf.cap = C.size_t(capacity)
	message := func() string { return C.GoStringN((*C.char)(unsafe.Pointer(bytes)), C.int(buf.len)) }
	release := func() {
		C.free(unsafe.Pointer(bytes))
		C.free(unsafe.Pointer(buf))
	}
	return buf, message, release
}

func cString(value string) (C.fkb_str, func()) {
	text := C.CString(value)
	return C.fkb_str{ptr: (*C.uint8_t)(unsafe.Pointer(text)), len: C.size_t(len(value))}, func() { C.free(unsafe.Pointer(text)) }
}

// Groups returns the brain's proxy groups as the JSON document
// docs/architecture/brain-groups.md describes (fkb_brain_groups): the
// members, their delays and each group's current choice.
func Groups() (string, error) {
	capacity := 16 * 1024
	for range 3 {
		out, _, release := newErrBuf(capacity)
		code := C.fkb_brain_groups(out)
		if code == C.FKB_OK {
			text := C.GoStringN((*C.char)(unsafe.Pointer(out.ptr)), C.int(out.len))
			release()
			return text, nil
		}
		needed := int(out.len)
		release()
		if code != C.FKB_E_BUFFER || needed <= capacity {
			return "", upstream.NewError("fkb_brain_groups: ", int32(code))
		}
		capacity = needed
	}
	return "", upstream.NewError("fkb_brain_groups: the list keeps growing")
}

// SelectGroup selects member in a select group (fkb_brain_group_select);
// automatic groups refuse.
func SelectGroup(group string, member string) error {
	groupC, freeGroup := cString(group)
	defer freeGroup()
	memberC, freeMember := cString(member)
	defer freeMember()
	errBuf, message, release := newErrBuf(1024)
	defer release()
	if code := C.fkb_brain_group_select(groupC, memberC, errBuf); code != C.FKB_OK {
		return upstream.NewError("brain error ", int32(code), ": ", message())
	}
	return nil
}

// TestGroup runs a delay test of a group now (empty: every group) and
// waits for it (fkb_brain_group_test).
func TestGroup(group string) error {
	groupC, freeGroup := cString(group)
	defer freeGroup()
	errBuf, message, release := newErrBuf(1024)
	defer release()
	if code := C.fkb_brain_group_test(groupC, errBuf); code != C.FKB_OK {
		return upstream.NewError("brain error ", int32(code), ": ", message())
	}
	return nil
}
