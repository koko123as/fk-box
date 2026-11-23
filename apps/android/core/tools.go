//go:build tools

// The sources in this directory are compiled as part of sing-box's
// experimental/libbox package (setup-core copies them into the matching core
// checkout, .core/). This file only records, for go mod tidy, the packages
// the Android build binds: the patched libbox and the gomobile runtime that
// gobind links into it. It is never built.
package libbox

import (
	_ "github.com/sagernet/gomobile/bind"
	_ "github.com/sagernet/gomobile/bind/java"
	_ "github.com/sagernet/sing-box/experimental/libbox"
)
