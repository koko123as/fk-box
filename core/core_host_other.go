//go:build !android

package libbox

import "github.com/sagernet/sing-box/experimental/fkhost"

// Off Android the operating system's own fkhost platform runs (fkhost's
// platform_<os>.go); the CommandServer's PlatformInterface is not used.
func (s *CommandServer) hostPlatform() fkhost.Platform { return fkhost.NewPlatform() }
