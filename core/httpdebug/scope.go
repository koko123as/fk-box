package httpdebug

import "slices"

// RouteScope is what a kernel's route sends to the capture proxy: TCP to
// these ports from these apps (any app when Packages is empty). BlockQUIC
// asks the kernel to reject QUIC (UDP 443) from the same apps, so they fall
// back to TCP, which the proxy can see.
type RouteScope struct {
	Ports     []uint16
	Packages  []string
	BlockQUIC bool
}

// Scope is the union of the capture and rewrite scopes; ok is false when
// the engine takes no connection.
func (e *Engine) Scope() (scope RouteScope, ok bool) {
	add := func(c Config) {
		for _, port := range c.Ports {
			if !slices.Contains(scope.Ports, port) {
				scope.Ports = append(scope.Ports, port)
			}
		}
		if len(c.Packages) == 0 {
			scope.Packages = nil
		} else if ok == false || scope.Packages != nil {
			for _, name := range c.Packages {
				if !slices.Contains(scope.Packages, name) {
					scope.Packages = append(scope.Packages, name)
				}
			}
		}
		scope.BlockQUIC = scope.BlockQUIC || c.HTTPS && c.BlockQUIC
		ok = true
	}
	if e.captureEnabled {
		add(e.config)
	}
	if e.rewrite != nil {
		add(e.rewrite.scope)
	}
	slices.Sort(scope.Ports)
	return scope, ok
}
