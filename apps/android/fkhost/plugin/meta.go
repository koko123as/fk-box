package plugin

import (
	"net/netip"
	"path/filepath"
	"sync"
	"sync/atomic"
	"unsafe"

	up "github.com/koko123as/fk-box/apps/android/fkhost/upstream"
)

// The Go mirror of crates/brain-ffi/include/fkbox_brain.h (ABI 2). Meta and
// Decision have exactly the C layout (checked in meta_test.go and, against
// the Rust library, in brainffi), hold no Go pointers and can be handed to C
// as they are. Nothing here allocates per decision except through metaPool.

// metaSize / decisionSize are the C sizes (576 / 16).
const (
	metaSize     = unsafe.Sizeof(Meta{})
	decisionSize = unsafe.Sizeof(Decision{})
)

const (
	ABIVersion = 2
	domainMax  = 256
	nameMax    = 128
)

// fkb_conn_meta.network
const (
	NetUnknown = 0
	NetTCP     = 1
	NetUDP     = 2
)

// fkb_conn_meta.protocol
const (
	ProtoNone       = 0
	ProtoHTTP       = 1
	ProtoTLS        = 2
	ProtoQUIC       = 3
	ProtoDNS        = 4
	ProtoBitTorrent = 5
	ProtoSTUN       = 6
	ProtoDTLS       = 7
	ProtoSSH        = 8
	ProtoRDP        = 9
	ProtoNTP        = 10
)

// fkb_conn_meta.domain_source / flags
const (
	DomainNone    = 0
	DomainFQDN    = 1
	DomainSniffed = 2

	MetaPackageTruncated = 0x01
	MetaProcessTruncated = 0x02

	UIDUnknown     = -1
	InboundUnknown = ^uint32(0)
)

// fkb_decision.action / flags and the return codes.
const (
	ActionRoute      = 0
	ActionReject     = 1
	ActionRejectDrop = 2
	ActionHijackDNS  = 3

	DecisionDNSMiss = 0x0001

	CodeOK            = 0
	CodeInvalid       = -1
	CodeNotReady      = -2
	CodePanic         = -3
	CodeUnimplemented = -4
	CodeTimeout       = -5
	CodeBuffer        = -6
	CodeHost          = -7
	CodeNotFound      = -8
)

// Meta is fkb_conn_meta (576 bytes).
type Meta struct {
	ABIVersion   uint32
	Size         uint32
	Network      uint8
	DstIPVersion uint8
	SrcIPVersion uint8
	Protocol     uint8
	SrcPort      uint16
	DstPort      uint16
	SrcIP        [16]byte
	DstIP        [16]byte
	UID          int32
	InboundIndex uint32
	DomainLen    uint16
	DomainSource uint8
	Flags        uint8
	PackageLen   uint8
	ProcessLen   uint8
	Reserved     uint16
	Domain       [domainMax]byte
	PackageName  [nameMax]byte
	ProcessName  [nameMax]byte
}

// Decision is fkb_decision (16 bytes).
type Decision struct {
	Action     uint16
	Flags      uint16
	Outbound   uint32
	RuleIndex  uint32
	Generation uint32
}

// Decider is the brain's decide(): synchronous, no I/O. brainffi wires it
// to the Rust library; tests use Go deciders.
type Decider interface {
	Decide(meta *Meta, out *Decision) int32
}

// DeciderFunc adapts a function.
type DeciderFunc func(meta *Meta, out *Decision) int32

func (f DeciderFunc) Decide(meta *Meta, out *Decision) int32 { return f(meta, out) }

type deciderHolder struct{ Decider }

var currentDecider atomic.Pointer[deciderHolder]

// SetDecider installs the process-wide decide function (nil removes it).
func SetDecider(decider Decider) {
	if decider == nil {
		currentDecider.Store(nil)
		return
	}
	currentDecider.Store(&deciderHolder{decider})
}

var metaPool = sync.Pool{New: func() any { return new(Meta) }}

func protocolCode(protocol string) uint8 {
	switch protocol {
	case up.ProtocolHTTP:
		return ProtoHTTP
	case up.ProtocolTLS:
		return ProtoTLS
	case up.ProtocolQUIC:
		return ProtoQUIC
	case up.ProtocolDNS:
		return ProtoDNS
	case up.ProtocolBitTorrent:
		return ProtoBitTorrent
	case up.ProtocolSTUN:
		return ProtoSTUN
	case up.ProtocolDTLS:
		return ProtoDTLS
	case up.ProtocolSSH:
		return ProtoSSH
	case up.ProtocolRDP:
		return ProtoRDP
	case up.ProtocolNTP:
		return ProtoNTP
	default:
		return ProtoNone
	}
}

func putAddr(dst *[16]byte, addr netip.Addr) uint8 {
	if !addr.IsValid() {
		return 0
	}
	addr = addr.Unmap()
	if addr.Is4() {
		raw := addr.As4()
		copy(dst[:4], raw[:])
		return 4
	}
	raw := addr.As16()
	copy(dst[:], raw[:])
	return 6
}

// fill writes the record for one connection. Strings longer than their
// array are cut (package/process, flagged) or left out (a domain: a cut
// name would match the wrong rules).
func (m *Meta) fill(metadata *up.InboundContext, inboundIndex uint32) {
	*m = Meta{}
	m.ABIVersion = ABIVersion
	m.Size = uint32(metaSize)
	switch metadata.Network {
	case up.NetworkTCP:
		m.Network = NetTCP
	case up.NetworkUDP:
		m.Network = NetUDP
	}
	m.Protocol = protocolCode(metadata.Protocol)
	m.SrcPort = metadata.Source.Port
	m.DstPort = metadata.Destination.Port
	m.SrcIPVersion = putAddr(&m.SrcIP, metadata.Source.Addr)
	domain, source := metadata.Domain, uint8(DomainSniffed)
	if metadata.Destination.IsFqdn() {
		domain, source = metadata.Destination.Fqdn, DomainFQDN
	} else {
		m.DstIPVersion = putAddr(&m.DstIP, metadata.Destination.Addr)
	}
	if domain != "" && len(domain) <= domainMax {
		m.DomainLen = uint16(copy(m.Domain[:], domain))
		m.DomainSource = source
	}
	m.UID = UIDUnknown
	if owner := metadata.ProcessInfo; owner != nil {
		m.UID = owner.UserId
		if len(owner.PackageNames) > 0 {
			name := owner.PackageNames[0]
			if len(name) > nameMax {
				m.Flags |= MetaPackageTruncated
			}
			m.PackageLen = uint8(copy(m.PackageName[:], name))
		}
		if len(owner.ProcessPaths) > 0 {
			name := filepath.Base(owner.ProcessPaths[0])
			if len(name) > nameMax {
				m.Flags |= MetaProcessTruncated
			}
			m.ProcessLen = uint8(copy(m.ProcessName[:], name))
		}
	}
	m.InboundIndex = inboundIndex
}

// ---- generations: fkbrain's outbound tables, by configuration generation ----

const keptGenerations = 3

type generationTable struct {
	generation uint32
	tags       []string
}

var (
	generationAccess  sync.Mutex
	generationTables  []generationTable
	pendingGeneration atomic.Uint32
)

// SetPendingGeneration names the generation of the configuration the host is
// about to create; the fkbrain outbound of that configuration registers its
// table under it.
func SetPendingGeneration(generation uint32) { pendingGeneration.Store(generation) }

func registerGeneration(generation uint32, tags []string) {
	generationAccess.Lock()
	defer generationAccess.Unlock()
	kept := generationTables[:0]
	for _, table := range generationTables {
		if table.generation != generation {
			kept = append(kept, table)
		}
	}
	kept = append(kept, generationTable{generation: generation, tags: append([]string(nil), tags...)})
	if len(kept) > keptGenerations {
		kept = kept[len(kept)-keptGenerations:]
	}
	generationTables = kept
}

// outboundTag resolves a decision: the tag at index in the table of its
// generation (the previous ones stay while decisions may still name them).
func outboundTag(generation uint32, index uint32) (string, bool) {
	generationAccess.Lock()
	defer generationAccess.Unlock()
	for i := len(generationTables) - 1; i >= 0; i-- {
		table := generationTables[i]
		if table.generation != generation {
			continue
		}
		if int(index) >= len(table.tags) {
			return "", false
		}
		return table.tags[index], true
	}
	return "", false
}
