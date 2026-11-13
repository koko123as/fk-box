package dns

import "sync/atomic"

// FK-box: every DNS message the router answers for a client (hijack-dns,
// the dns outbound and DNS inbounds all go through Exchange/ExchangeAsync),
// cached answers included. The router's own lookups for outbound dialing use
// Lookup and are not counted. Read by libbox ReadUIDNSQueryCount.
var uiQueryCount atomic.Int64

func UIQueryCount() int64 { return uiQueryCount.Load() }
