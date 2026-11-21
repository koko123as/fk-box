// Local replacements for the mihomo-internal packages the vendored
// common/convert sources import (github.com/metacubex/mihomo v1.19.34):
//   - log.Warnln                  -> logWarnln (Warn hook, silent by default)
//   - common/utils.NewUUIDV4      -> newUUIDV4Bytes (crypto/rand)
//   - metacubex/randv2            -> math/rand/v2
//   - metacubex/http              -> net/http
//   - sing-shadowsocks/shadowimpl -> fetchShadowsocksMethod (the method and
//     key checks of shadowimpl.FetchMethod, sing-shadowsocks v0.2.13, without
//     building the cipher)
//
// This file is fk-box's own (GPL-3.0 like the files it supports).

package clashconvert

import (
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"slices"
	"strings"
)

// Warn receives the converter's warnings (a share link line it skipped).
// nil drops them.
var Warn func(format string, args ...any)

func logWarnln(format string, args ...any) {
	if Warn != nil {
		Warn(format, args...)
	}
}

func newUUIDV4Bytes() []byte {
	var uuid [16]byte
	_, _ = rand.Read(uuid[:])
	uuid[6] = (uuid[6] & 0x0f) | 0x40
	uuid[8] = (uuid[8] & 0x3f) | 0x80
	return uuid[:]
}

var (
	errBadKey          = errors.New("bad key")
	errMissingPassword = errors.New("missing password")
	errMissingPSK      = errors.New("missing psk")
)

// sing-shadowsocks v0.2.13 shadowstream.List, shadowaead.List,
// shadowaead_2022.List.
var (
	shadowStreamMethods = []string{
		"aes-128-ctr", "aes-192-ctr", "aes-256-ctr",
		"aes-128-cfb", "aes-192-cfb", "aes-256-cfb",
		"rc4-md5", "chacha20-ietf", "xchacha20", "chacha20",
	}
	shadowAEADMethods = []string{
		"aes-128-gcm", "aes-192-gcm", "aes-256-gcm",
		"chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
		"chacha8-ietf-poly1305", "xchacha8-ietf-poly1305", "rabbit128-poly1305",
		"aes-128-ccm", "aes-192-ccm", "aes-256-ccm",
	}
	shadow2022KeyLength = map[string]int{
		"2022-blake3-aes-128-gcm":       16,
		"2022-blake3-aes-256-gcm":       32,
		"2022-blake3-chacha20-poly1305": 32,
		"2022-blake3-chacha8-poly1305":  32,
		"2022-blake3-aes-128-ccm":       16,
		"2022-blake3-aes-256-ccm":       32,
	}
)

// fetchShadowsocksMethod reports the error shadowimpl.FetchMethod would
// return for method and password.
func fetchShadowsocksMethod(method string, password string) error {
	switch {
	case method == "none" || method == "plain" || method == "dummy":
		return nil
	case slices.Contains(shadowStreamMethods, method), slices.Contains(shadowAEADMethods, method):
		if password == "" {
			return errMissingPassword
		}
		return nil
	}
	keyLength, ok := shadow2022KeyLength[method]
	if !ok {
		return fmt.Errorf("shadowsocks: unsupported method %s", method)
	}
	if password == "" {
		return errMissingPSK
	}
	keys := strings.Split(password, ":")
	if len(keys) > 1 && strings.Contains(method, "chacha") {
		return errors.New("invalid argument")
	}
	for _, key := range keys {
		decoded, err := base64.StdEncoding.DecodeString(key)
		if err != nil {
			return fmt.Errorf("decode key: %w", err)
		}
		if len(decoded) < keyLength {
			return errBadKey
		}
	}
	return nil
}
