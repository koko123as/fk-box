//go:build !linux && !android

package kernel

import "syscall"

func sysProcAttr() *syscall.SysProcAttr { return nil }
