// Package ffi is the Go half of the stage-0 Go -> Rust decide ABI: Rust
// registers a C function pointer at startup (fkrust_init ->
// fkgo_register_decider), Go calls it through cgo with a fixed-layout C
// struct. Build ../rustcb first (cargo build --release).
package ffi

/*
#cgo CFLAGS: -I${SRCDIR} -O2
#cgo LDFLAGS: -L${SRCDIR}/../rustcb/target/release -lfkrustcb -lgcc_s -lutil -lrt -lpthread -lm -ldl -lc
#include "fk_ffi.h"
*/
import "C"

import (
	"net/netip"
	"sync"
	"unsafe"

	"github.com/sagernet/sing-box/adapter"
	E "github.com/sagernet/sing/common/exceptions"
	N "github.com/sagernet/sing/common/network"

	"github.com/koko123as/fk-box/probes/stage0/plugin"
)

// DomainMax must equal FK_DOMAIN_MAX (checked in Init).
const DomainMax = 256

// Meta mirrors fk_conn_meta field for field (checked in Init). It holds no
// Go pointers, so a *Meta may be handed to C directly.
type Meta struct {
	Network      uint8
	IPVersion    uint8
	SrcPort      uint16
	DstPort      uint16
	DomainLen    uint16
	SrcIP        [16]byte
	DstIP        [16]byte
	UID          int32
	InboundIndex uint32
	Domain       [DomainMax]byte
}

// Decision mirrors fk_decision.
type Decision struct {
	Action   int32
	Outbound uint32
}

// Values of the FK_ACTION_* / FK_NET_* enums in fk_ffi.h.
const (
	ActionRoute  int32 = 0
	ActionReject int32 = 1
	NetTCP       uint8 = 1
	NetUDP       uint8 = 2
)

var initOnce sync.Once
var initErr error

// Init runs the Rust side's startup registration once and checks the ABI.
func Init() error {
	initOnce.Do(func() {
		if got, want := uint32(C.fkrust_meta_size()), uint32(unsafe.Sizeof(Meta{})); got != want || want != uint32(C.sizeof_fk_conn_meta) {
			initErr = E.New("fk_conn_meta size mismatch: rust ", got, ", go ", want, ", c ", uint32(C.sizeof_fk_conn_meta))
			return
		}
		if got, want := uint32(C.fkrust_decision_size()), uint32(unsafe.Sizeof(Decision{})); got != want || want != uint32(C.sizeof_fk_decision) {
			initErr = E.New("fk_decision size mismatch")
			return
		}
		if DomainMax != int(C.FK_DOMAIN_MAX) {
			initErr = E.New("FK_DOMAIN_MAX mismatch")
			return
		}
		var cMeta C.fk_conn_meta
		var goMeta Meta
		offsets := [][2]uintptr{
			{unsafe.Offsetof(cMeta.dst_port), unsafe.Offsetof(goMeta.DstPort)},
			{unsafe.Offsetof(cMeta.src_ip), unsafe.Offsetof(goMeta.SrcIP)},
			{unsafe.Offsetof(cMeta.dst_ip), unsafe.Offsetof(goMeta.DstIP)},
			{unsafe.Offsetof(cMeta.uid), unsafe.Offsetof(goMeta.UID)},
			{unsafe.Offsetof(cMeta.inbound_index), unsafe.Offsetof(goMeta.InboundIndex)},
			{unsafe.Offsetof(cMeta.domain), unsafe.Offsetof(goMeta.Domain)},
		}
		for _, pair := range offsets {
			if pair[0] != pair[1] {
				initErr = E.New("fk_conn_meta field offset mismatch")
				return
			}
		}
		C.fkrust_init(C.fk_register_fn(C.fkgo_register_decider))
		if C.fkgo_has_decider() != 1 {
			initErr = E.New("rust did not register a decider")
		}
	})
	return initErr
}

// Decide calls the registered Rust decider: one cgo crossing plus one
// indirect C call.
func Decide(meta *Meta) (Decision, error) {
	var decision Decision
	if C.fkgo_decide((*C.fk_conn_meta)(unsafe.Pointer(meta)), (*C.fk_decision)(unsafe.Pointer(&decision))) != 0 {
		return decision, E.New("decider failed")
	}
	return decision, nil
}

// Noop crosses cgo the same way but does no Rust work (overhead baseline).
func Noop(meta *Meta) Decision {
	var decision Decision
	C.fkgo_noop((*C.fk_conn_meta)(unsafe.Pointer(meta)), (*C.fk_decision)(unsafe.Pointer(&decision)))
	return decision
}

// FillMeta converts sing-box connection metadata into the fixed C layout.
func FillMeta(meta *Meta, metadata *adapter.InboundContext, inboundIndex uint32) {
	*meta = Meta{UID: -1, InboundIndex: inboundIndex}
	switch N.NetworkName(metadata.Network) {
	case N.NetworkUDP:
		meta.Network = NetUDP
	default:
		meta.Network = NetTCP
	}
	meta.SrcPort = metadata.Source.Port
	meta.DstPort = metadata.Destination.Port
	putAddr(&meta.SrcIP, metadata.Source.Addr)
	if metadata.Destination.IsFqdn() {
		meta.DomainLen = uint16(copy(meta.Domain[:], metadata.Destination.Fqdn))
	} else {
		meta.IPVersion = putAddr(&meta.DstIP, metadata.Destination.Addr)
		if metadata.Domain != "" {
			meta.DomainLen = uint16(copy(meta.Domain[:], metadata.Domain))
		}
	}
	if metadata.ProcessInfo != nil {
		meta.UID = int32(metadata.ProcessInfo.UserId)
	}
}

func putAddr(dst *[16]byte, addr netip.Addr) uint8 {
	if !addr.IsValid() {
		return 0
	}
	addr = addr.Unmap()
	if addr.Is4() {
		v4 := addr.As4()
		copy(dst[:], v4[:])
		return 4
	}
	*dst = addr.As16()
	return 6
}

// Decider adapts the Rust callback to the probe's fkbrain outbound.
func Decider() plugin.Decider {
	return func(metadata *adapter.InboundContext) plugin.Decision {
		var meta Meta
		FillMeta(&meta, metadata, 0)
		decision, err := Decide(&meta)
		if err != nil || decision.Action == ActionReject {
			return plugin.Decision{Reject: true}
		}
		return plugin.Decision{Index: int(decision.Outbound)}
	}
}
