//go:build linux || android

package kernel

import "syscall"

// sysProcAttr ends the kernel with fk-core: a kernel never outlives the host
// that supervises it.
func sysProcAttr() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL}
}
