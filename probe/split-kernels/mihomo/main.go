// Command mihomo is the fk-box mihomo shim: unpatched mihomo (cmfa build tag,
// as ClashMetaForAndroid embeds it) behind ../include/fk_kernel.h.
package main

/*
#cgo CFLAGS: -I${SRCDIR}/../include
#define FK_KERNEL_TYPES_ONLY
#include <stdlib.h>
#include "fk_kernel.h"

static int32_t fk_call_protect(const fk_host *h, int32_t fd) {
    return h->protect ? h->protect(h->ctx, fd) : 0;
}
static int32_t fk_call_find_owner(const fk_host *h, int32_t proto, const char *src, int32_t sport,
                                  const char *dst, int32_t dport, int32_t *uid, char *pkg, int32_t cap) {
    return h->find_owner ? h->find_owner(h->ctx, proto, src, sport, dst, dport, uid, pkg, cap) : -1;
}
*/
import "C"

import (
	"errors"
	"os"
	"sync"
	"syscall"
	"unsafe"

	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/component/mmdb"
	"github.com/metacubex/mihomo/component/process"
	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

var (
	access  sync.Mutex
	host    *C.fk_host
	running bool
	errMsg  string
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
	constant.SetHomeDir(dir)
	if h != nil && h.protect != nil {
		dialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
			var protectErr error
			err := conn.Control(func(fd uintptr) {
				if C.fk_call_protect(host, C.int32_t(fd)) != 0 {
					protectErr = errors.New("protect socket")
				}
			})
			if err != nil {
				return err
			}
			return protectErr
		}
	}
	if h != nil && h.find_owner != nil {
		process.DefaultPackageNameResolver = findPackageName
	}
	return 0
}

func findPackageName(metadata *constant.Metadata) (string, error) {
	protocol := C.int32_t(syscall.IPPROTO_TCP)
	if metadata.NetWork == constant.UDP {
		protocol = syscall.IPPROTO_UDP
	}
	src, dst := C.CString(metadata.SrcIP.String()), C.CString(metadata.DstIP.String())
	defer C.free(unsafe.Pointer(src))
	defer C.free(unsafe.Pointer(dst))
	var uid C.int32_t
	var pkg [256]C.char
	if C.fk_call_find_owner(host, protocol, src, C.int32_t(metadata.SrcPort), dst, C.int32_t(metadata.DstPort), &uid, &pkg[0], C.int32_t(len(pkg))) != 0 {
		return "", process.ErrNotFound
	}
	return C.GoString(&pkg[0]), nil
}

// verifyGeo turns mihomo's log.Fatalln on an unreadable MMDB/ASN
// (component/mmdb) into an error: a present but broken file is refused
// before mihomo opens it. Rust keeps the files valid; this is the backstop.
func verifyGeo() error {
	for _, path := range []string{constant.Path.MMDB(), constant.Path.ASN()} {
		if _, err := os.Stat(path); err == nil && !mmdb.Verify(path) {
			return errors.New("invalid geo database: " + path)
		}
	}
	return nil
}

func parse(content []byte) (*config.Config, error) {
	if err := verifyGeo(); err != nil {
		return nil, err
	}
	return executor.ParseWithBytes(content)
}

//export fk_check
func fk_check(content *C.char, length C.int32_t) C.int32_t {
	access.Lock()
	defer access.Unlock()
	if _, err := parse(C.GoBytes(unsafe.Pointer(content), C.int(length))); err != nil {
		return fail(err)
	}
	return 0
}

//export fk_start
func fk_start(content *C.char, length C.int32_t, tunFd C.int32_t) C.int32_t {
	access.Lock()
	defer access.Unlock()
	cfg, err := parse(C.GoBytes(unsafe.Pointer(content), C.int(length)))
	if err != nil {
		return fail(err)
	}
	if tunFd >= 0 {
		cfg.General.Tun.Enable = true
		cfg.General.Tun.FileDescriptor = int(tunFd)
	}
	hub.ApplyConfig(cfg)
	running = true
	return 0
}

//export fk_stop
func fk_stop() C.int32_t {
	access.Lock()
	defer access.Unlock()
	if !running {
		return 0
	}
	// An empty configuration has no listeners, controller or TUN:
	// applying it closes every port the previous one opened.
	if empty, err := executor.ParseWithBytes([]byte("{}")); err == nil {
		hub.ApplyConfig(empty)
	} else {
		return fail(err)
	}
	executor.Shutdown()
	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		_ = c.Close()
		return true
	})
	running = false
	return 0
}

//export fk_version
func fk_version() *C.char { return C.CString("mihomo " + constant.Version) }

//export fk_last_error
func fk_last_error() *C.char { return C.CString(errMsg) }

//export fk_free
func fk_free(p *C.char) { C.free(unsafe.Pointer(p)) }

func main() {}
