// Command singbox is the fk-box sing-box shim: unpatched sing-box behind the
// split-kernel C ABI (../include/fk_kernel.h), built with -buildmode=c-shared.
package main

/*
#cgo CFLAGS: -I${SRCDIR}/../include
#define FK_KERNEL_TYPES_ONLY
#include <stdlib.h>
#include "fk_kernel.h"
*/
import "C"

import (
	"context"
	"encoding/json"
	"os"
	"sync"
	"unsafe"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	C2 "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	sjson "github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/filemanager"
)

var (
	access   sync.Mutex
	host     *C.fk_host
	homeDir  string
	instance *box.Box
	cancel   context.CancelFunc
	platform *hostPlatform
	errMsg   string
)

func fail(err error) C.int32_t {
	errMsg = err.Error()
	return -1
}

func baseContext() context.Context {
	ctx := filemanager.WithDefault(context.Background(), homeDir, homeDir, os.Getuid(), os.Getgid())
	return include.Context(ctx)
}

func newBox(config []byte) (*box.Box, context.CancelFunc, error) {
	ctx := baseContext()
	options, err := sjson.UnmarshalExtendedContext[option.Options](ctx, config)
	if err != nil {
		return nil, nil, err
	}
	ctx, cancel := context.WithCancel(ctx)
	if host != nil && host.open_tun != nil {
		platform = &hostPlatform{host: host}
		ctx = service.ContextWith[adapter.PlatformInterface](ctx, platform)
	}
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		cancel()
		return nil, nil, err
	}
	return instance, cancel, nil
}

//export fk_init
func fk_init(h *C.fk_host, home *C.char) C.int32_t {
	access.Lock()
	defer access.Unlock()
	host = h
	homeDir = C.GoString(home)
	if err := os.MkdirAll(homeDir, 0o700); err != nil {
		return fail(err)
	}
	return 0
}

//export fk_check
func fk_check(config *C.char, length C.int32_t) C.int32_t {
	access.Lock()
	defer access.Unlock()
	checked, stop, err := newBox(C.GoBytes(unsafe.Pointer(config), C.int(length)))
	if err != nil {
		return fail(err)
	}
	checked.Close()
	stop()
	return 0
}

//export fk_start
func fk_start(config *C.char, length C.int32_t, _ C.int32_t) C.int32_t {
	access.Lock()
	defer access.Unlock()
	stopLocked()
	started, stop, err := newBox(C.GoBytes(unsafe.Pointer(config), C.int(length)))
	if err != nil {
		return fail(err)
	}
	if err = started.Start(); err != nil {
		started.Close()
		stop()
		return fail(err)
	}
	instance, cancel = started, stop
	return 0
}

func stopLocked() error {
	if instance == nil {
		return nil
	}
	err := instance.Close()
	cancel()
	instance, cancel = nil, nil
	return err
}

//export fk_stop
func fk_stop() C.int32_t {
	access.Lock()
	defer access.Unlock()
	if err := stopLocked(); err != nil {
		return fail(err)
	}
	return 0
}

//export fk_network_changed
func fk_network_changed(content *C.char) C.int32_t {
	var update struct {
		Index int32  `json:"index"`
		Name  string `json:"name"`
	}
	if err := json.Unmarshal([]byte(C.GoString(content)), &update); err != nil {
		return fail(err)
	}
	access.Lock()
	current := platform
	access.Unlock()
	if current != nil {
		current.defaultInterfaceChanged(update.Name, int(update.Index))
	}
	return 0
}

//export fk_version
func fk_version() *C.char { return C.CString("sing-box " + C2.Version) }

//export fk_last_error
func fk_last_error() *C.char { return C.CString(errMsg) }

//export fk_free
func fk_free(p *C.char) { C.free(unsafe.Pointer(p)) }

func main() {}
