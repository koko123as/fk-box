package libbox

import (
	"context"

	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
)

// CoreServiceOptions are the app's options for one start or reload of the
// local service (Kotlin io.nekohasekai.libbox.CoreServiceOptions): libbox's
// OverrideOptions fields plus the HTTP rewrite storage and signer.
//
// IncludePackage and ExcludePackage are read once per start: by sing-box's
// tun inbound, or by the TUN fk-box opens for Xray and mihomo.
type CoreServiceOptions struct {
	AutoRedirect       bool
	IncludePackage     StringIterator
	ExcludePackage     StringIterator
	RewriteDirectory   string
	RewriteCertificate string
	RewriteSigner      UICaptureSigner
}

// parseCoreConfig parses a sing-box profile the way fk-box runs and checks
// it: the retired capture options (experimental.http_debug) are dropped
// first, then libbox's parser decodes it with the registries of ctx.
func parseCoreConfig(ctx context.Context, content string) (option.Options, error) {
	content, err := RemoveUIRetiredCaptureOptions(content)
	if err != nil {
		return option.Options{}, E.Cause(err, "remove retired capture options")
	}
	return parseConfig(ctx, content)
}

// HasCoreTunInbound is libbox's HasTunInbound for a sing-box profile as
// fk-box runs it: the retired capture options are dropped first (libbox's
// parser rejects them).
func HasCoreTunInbound(content string) (bool, error) {
	content, err := RemoveUIRetiredCaptureOptions(content)
	if err != nil {
		return false, E.Cause(err, "remove retired capture options")
	}
	return HasTunInbound(content)
}
