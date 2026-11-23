// Command xray is the fk-box Xray shim: unpatched Xray-core behind
// ../include/fk_kernel.h. The TUN fd reaches proxy/tun through Xray's own
// xray.tun.fd environment flag (common/platform).
package main

/*
#cgo CFLAGS: -I${SRCDIR}/../include
#define FK_KERNEL_TYPES_ONLY
#include <stdlib.h>
#include "fk_kernel.h"

static int32_t fk_call_protect(const fk_host *h, int32_t fd) {
    return h->protect ? h->protect(h->ctx, fd) : 0;
}
*/
import "C"

import (
	"bytes"
	"errors"
	"os"
	"strconv"
	"sync"
	"syscall"
	"unsafe"

	"github.com/xtls/xray-core/common/platform"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all"
	"github.com/xtls/xray-core/transport/internet"
)

var (
	access   sync.Mutex
	host     *C.fk_host
	instance *core.Instance
	errMsg   string
)

func fail(err error) C.int32_t {
	errMsg = err.Error()
	return -1
}

//export fk_init
func fk_init(h *C.fk_host, home *C.char) C.int32_t {
	access.Lock()
	defer access.Unlock()
	host = h
	dir := C.GoString(home)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fail(err)
	}
	// geoip.dat / geosite.dat are read from here (xray.location.asset).
	os.Setenv("xray.location.asset", dir)
	if h != nil && h.protect != nil {
		// Registered once: Xray has no way to remove a dialer controller.
		err := internet.RegisterDialerController(func(network, address string, conn syscall.RawConn) error {
			var protectErr error
			if err := conn.Control(func(fd uintptr) {
				if C.fk_call_protect(host, C.int32_t(fd)) != 0 {
					protectErr = errors.New("protect socket")
				}
			}); err != nil {
				return err
			}
			return protectErr
		})
		if err != nil {
			return fail(err)
		}
	}
	return 0
}

func build(content []byte) (*core.Instance, error) {
	config, err := serial.LoadJSONConfig(bytes.NewReader(content))
	if err != nil {
		return nil, err
	}
	return core.New(config)
}

//export fk_check
func fk_check(content *C.char, length C.int32_t) C.int32_t {
	access.Lock()
	defer access.Unlock()
	checked, err := build(C.GoBytes(unsafe.Pointer(content), C.int(length)))
	if err != nil {
		return fail(err)
	}
	checked.Close()
	return 0
}

//export fk_start
func fk_start(content *C.char, length C.int32_t, tunFd C.int32_t) C.int32_t {
	access.Lock()
	defer access.Unlock()
	if instance != nil {
		instance.Close()
		instance = nil
	}
	if tunFd >= 0 {
		os.Setenv(platform.TunFdKey, strconv.Itoa(int(tunFd)))
	}
	started, err := build(C.GoBytes(unsafe.Pointer(content), C.int(length)))
	if err != nil {
		return fail(err)
	}
	if err = started.Start(); err != nil {
		started.Close()
		return fail(err)
	}
	instance = started
	return 0
}

//export fk_stop
func fk_stop() C.int32_t {
	access.Lock()
	defer access.Unlock()
	if instance == nil {
		return 0
	}
	err := instance.Close()
	instance = nil
	if err != nil {
		return fail(err)
	}
	return 0
}

// fk_network_changed: Xray reads DNS servers from its config; Rust reloads
// (fk_start) when the direct DNS servers it wrote there change.
//
//export fk_network_changed
func fk_network_changed(*C.char) C.int32_t { return 0 }

//export fk_version
func fk_version() *C.char { return C.CString("Xray " + core.Version()) }

//export fk_last_error
func fk_last_error() *C.char { return C.CString(errMsg) }

//export fk_free
func fk_free(p *C.char) { C.free(unsafe.Pointer(p)) }

func main() {}
