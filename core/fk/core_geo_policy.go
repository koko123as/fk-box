package fk

// The decisions behind the host's geo resources (moved from the Android app's
// GeoPolicy): which files a core needs, whether the APK's bundled copy should
// replace the installed one, and when an update is due. Reading assets,
// downloading and the state file stay with the host.

import (
	"regexp"
	"strings"
)

// geoDataMode is the app's Regex("""geodata-mode["']?\s*:\s*true""") as
// Android's ICU engine runs it (\s is White_Space).
var geoDataMode = regexp.MustCompile(`geodata-mode["']?[` + androidWhiteSpace + `]*:[` + androidWhiteSpace + `]*true`)

// GeoResourcesNeeded returns the geo resources core reads for text (its
// profile content plus the routing rules compiled for it), comma-separated in
// a fixed order. sing-box uses rule sets, none of these. Xray loads
// geoip.dat / geosite.dat only for the geoip:/geosite: (or ext:geoip.dat:…)
// entries it is given. mihomo may read GeoSite and the MMDB lazily
// (GEOSITE/GEOIP rules, DNS fallback-filter), so both bundled files are
// always prepared; ASN.mmdb only for IP-ASN rules and geoip.dat only in
// geodata-mode.
func GeoResourcesNeeded(core string, text string) string {
	lower := kotlinLowercase(text)
	var needed []string
	switch core {
	case CoreXray:
		if strings.Contains(lower, "geosite:") || strings.Contains(lower, "geosite.dat") {
			needed = append(needed, GeoResourceGeoSite)
		}
		if strings.Contains(lower, "geoip:") || strings.Contains(lower, "geoip.dat") {
			needed = append(needed, GeoResourceGeoIP)
		}
	case CoreMihomo:
		needed = append(needed, GeoResourceGeoSite, GeoResourceMMDB)
		if strings.Contains(lower, "ip-asn") {
			needed = append(needed, GeoResourceASN)
		}
		if geoDataMode.MatchString(lower) {
			needed = append(needed, GeoResourceGeoIP)
		}
	}
	return strings.Join(needed, ",")
}

// kotlinLowercase is Kotlin's String.lowercase() (Locale.ROOT) where it can
// change a match on the ASCII words above: Go maps İ (U+0130) to a lone "i",
// Java to "i" plus a combining dot.
func kotlinLowercase(text string) string {
	return strings.ToLower(strings.ReplaceAll(text, "\u0130", "i\u0307"))
}

// GeoShouldExtract reports whether the APK's bundled copy should replace what
// is installed. A missing file is always restored; otherwise the bundle only
// replaces data older than itself, so a newer download (or a file mihomo
// fetched) is never overwritten, while an app upgrade with a newer bundle
// replaces an older bundled or downloaded file. hasState is false when the
// app has no record of the file; installedSha256 and installedTime are that
// record's.
func GeoShouldExtract(exists bool, hasState bool, installedSha256 string, installedTime int64, bundledSha256 string, bundleTime int64, fileModified int64) bool {
	if !exists {
		return true
	}
	if !hasState {
		return fileModified >= 1 && fileModified < bundleTime
	}
	if installedSha256 == bundledSha256 {
		return false
	}
	return installedTime < bundleTime
}

// GeoUpdateDue reports whether a resource last checked at lastChecked (ms,
// 0 for never) is due at now with an interval of intervalHours (at least 1).
func GeoUpdateDue(lastChecked int64, intervalHours int32, now int64) bool {
	if intervalHours < 1 {
		intervalHours = 1
	}
	return lastChecked <= 0 || now-lastChecked >= int64(intervalHours)*3_600_000
}
