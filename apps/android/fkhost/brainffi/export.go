package brainffi

/*
#include <stdint.h>
#include "fkbox_brain.h"
*/
import "C"

import (
	"context"
	"errors"
	"runtime/cgo"
	"time"
	"unsafe"

	"github.com/koko123as/fk-box/apps/android/fkhost/host"
)

// The fkb_host_api functions (Rust -> Go). The brain calls them from its own
// threads, never from decide; ctx is the runtime/cgo.Handle of the Host.

func hostOf(ctx unsafe.Pointer) *host.Host {
	return cgo.Handle(uintptr(ctx)).Value().(*host.Host)
}

func goString(s C.fkb_str) string {
	if s.ptr == nil || s.len == 0 {
		return ""
	}
	return C.GoStringN((*C.char)(unsafe.Pointer(s.ptr)), C.int(s.len))
}

func goBytes(s C.fkb_str) []byte {
	if s.ptr == nil || s.len == 0 {
		return nil
	}
	return C.GoBytes(unsafe.Pointer(s.ptr), C.int(s.len))
}

// writeBuf copies data into a caller buffer; FKB_E_BUFFER with len = size
// needed when it does not fit (truncate for error buffers).
func writeBuf(buf *C.fkb_buf, data []byte, truncate bool) C.int32_t {
	if buf == nil {
		return C.FKB_E_INVALID
	}
	capacity := int(buf.cap)
	if len(data) > capacity && !truncate {
		buf.len = C.size_t(len(data))
		return C.FKB_E_BUFFER
	}
	n := min(len(data), capacity)
	if n > 0 && buf.ptr != nil {
		copy(unsafe.Slice((*byte)(unsafe.Pointer(buf.ptr)), capacity), data[:n])
	}
	buf.len = C.size_t(n)
	return C.FKB_OK
}

func fail(err *C.fkb_buf, code C.int32_t, cause error) C.int32_t {
	if err != nil && cause != nil {
		writeBuf(err, []byte(cause.Error()), true)
	}
	return code
}

func errorCode(err error) C.int32_t {
	switch {
	case errors.Is(err, context.DeadlineExceeded):
		return C.FKB_E_TIMEOUT
	case host.IsNotFound(err):
		return C.FKB_E_NOT_FOUND
	default:
		return C.FKB_E_HOST
	}
}

func withTimeout(timeoutMs C.uint32_t) (context.Context, context.CancelFunc) {
	timeout := time.Duration(timeoutMs) * time.Millisecond
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	return context.WithTimeout(context.Background(), timeout)
}

//export fkhostStart
func fkhostStart(ctx unsafe.Pointer, config C.fkb_str, generation C.uint32_t, err *C.fkb_buf) C.int32_t {
	if cause := hostOf(ctx).Start(goString(config), uint32(generation)); cause != nil {
		return fail(err, C.FKB_E_HOST, cause)
	}
	return C.FKB_OK
}

//export fkhostReload
func fkhostReload(ctx unsafe.Pointer, config C.fkb_str, generation C.uint32_t, err *C.fkb_buf) C.int32_t {
	if cause := hostOf(ctx).Reload(goString(config), uint32(generation)); cause != nil {
		return fail(err, C.FKB_E_HOST, cause)
	}
	return C.FKB_OK
}

//export fkhostStop
func fkhostStop(ctx unsafe.Pointer, err *C.fkb_buf) C.int32_t {
	if cause := hostOf(ctx).Stop(); cause != nil {
		return fail(err, C.FKB_E_HOST, cause)
	}
	return C.FKB_OK
}

//export fkhostProbe
func fkhostProbe(ctx unsafe.Pointer, tag C.fkb_str, url C.fkb_str, timeoutMs C.uint32_t, out *C.fkb_probe_result, err *C.fkb_buf) C.int32_t {
	if out == nil {
		return C.FKB_E_INVALID
	}
	requestCtx, cancel := withTimeout(timeoutMs)
	defer cancel()
	status, latency, cause := hostOf(ctx).Probe(requestCtx, goString(tag), goString(url))
	out.http_status = C.int32_t(status)
	out.latency_ms = C.uint32_t(latency / time.Millisecond)
	out.flags = 0
	out.reserved = 0
	if cause != nil {
		out.http_status = -1
		return fail(err, errorCode(cause), cause)
	}
	return C.FKB_OK
}

//export fkhostExchange
func fkhostExchange(ctx unsafe.Pointer, tag C.fkb_str, upstream C.fkb_str, message C.fkb_str, timeoutMs C.uint32_t, response *C.fkb_buf, err *C.fkb_buf) C.int32_t {
	requestCtx, cancel := withTimeout(timeoutMs)
	defer cancel()
	reply, cause := hostOf(ctx).Exchange(requestCtx, goString(tag), goString(upstream), goBytes(message))
	if cause != nil {
		return fail(err, errorCode(cause), cause)
	}
	return writeBuf(response, reply, false)
}

//export fkhostStats
func fkhostStats(ctx unsafe.Pointer, out *C.fkb_stats) C.int32_t {
	if out == nil {
		return C.FKB_E_INVALID
	}
	stats := hostOf(ctx).Stats()
	out.uplink_bytes = C.uint64_t(stats.UplinkBytes)
	out.downlink_bytes = C.uint64_t(stats.DownlinkBytes)
	out.uplink_rate = C.uint64_t(stats.UplinkRate)
	out.downlink_rate = C.uint64_t(stats.DownlinkRate)
	out.connections_tcp = C.uint32_t(stats.ConnectionsTCP)
	out.connections_udp = C.uint32_t(stats.ConnectionsUDP)
	out.memory_inuse = C.uint64_t(stats.MemoryInuse)
	out.goroutines = C.uint32_t(stats.Goroutines)
	out.reserved = 0
	return C.FKB_OK
}
