// mihomo symbols used by the mihomo plugin (plugin/mihomo*.go).
package upstream

import (
	mihomoadapter "github.com/metacubex/mihomo/adapter"
	mihomocomponentresolver "github.com/metacubex/mihomo/component/resolver"
	mihomoconstant "github.com/metacubex/mihomo/constant"
)

// github.com/metacubex/mihomo/adapter

var MihomoParseProxy = mihomoadapter.ParseProxy

var MihomoWithDialerForAPI = mihomoadapter.WithDialerForAPI

// github.com/metacubex/mihomo/component/resolver

// SetMihomoDefaultResolver sets mihomo's resolver.DefaultResolver (a package variable mihomo reads at dial time).
func SetMihomoDefaultResolver(r mihomocomponentresolver.Resolver) {
	mihomocomponentresolver.DefaultResolver = r
}

// SetMihomoDirectHostResolver sets mihomo's resolver.DirectHostResolver (a package variable mihomo reads at dial time).
func SetMihomoDirectHostResolver(r mihomocomponentresolver.Resolver) {
	mihomocomponentresolver.DirectHostResolver = r
}

// SetMihomoProxyServerHostResolver sets mihomo's resolver.ProxyServerHostResolver (a package variable mihomo reads at dial time).
func SetMihomoProxyServerHostResolver(r mihomocomponentresolver.Resolver) {
	mihomocomponentresolver.ProxyServerHostResolver = r
}

type MihomoResolver = mihomocomponentresolver.Resolver

// github.com/metacubex/mihomo/constant

type MihomoDialer = mihomoconstant.Dialer

type MihomoMetadata = mihomoconstant.Metadata

type MihomoNetWork = mihomoconstant.NetWork

type MihomoProxy = mihomoconstant.Proxy

const MihomoTCP = mihomoconstant.TCP

const MihomoUDP = mihomoconstant.UDP
