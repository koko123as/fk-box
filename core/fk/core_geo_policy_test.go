package fk

import (
	"strings"
	"testing"
)

const (
	testGeoBundleTime = int64(1_762_396_920_000)
	testGeoHour       = int64(3_600_000)
)

var testGeoBundleSha = strings.Repeat("a", 64)

func TestUIGeoMissingFileIsAlwaysExtracted(t *testing.T) {
	if !GeoShouldExtract(false, false, "", 0, testGeoBundleSha, testGeoBundleTime, 0) {
		t.Fatal("missing file without state")
	}
	if !GeoShouldExtract(false, true, strings.Repeat("b", 64), testGeoBundleTime+1, testGeoBundleSha, testGeoBundleTime, 0) {
		t.Fatal("missing file with a newer state")
	}
}

func TestUIGeoNewerDownloadIsNeverOverwritten(t *testing.T) {
	download := strings.Repeat("b", 64)
	if GeoShouldExtract(true, true, download, testGeoBundleTime+60_000, testGeoBundleSha, testGeoBundleTime, testGeoBundleTime+60_000) {
		t.Fatal("newer download overwritten")
	}
	if GeoShouldExtract(true, true, download, testGeoBundleTime, testGeoBundleSha, testGeoBundleTime, testGeoBundleTime) {
		t.Fatal("download as old as the bundle overwritten")
	}
}

func TestUIGeoNewerBundleReplacesOlderData(t *testing.T) {
	if !GeoShouldExtract(true, true, strings.Repeat("c", 64), testGeoBundleTime-86_400_000, testGeoBundleSha, testGeoBundleTime, 1) {
		t.Fatal("older bundled file kept")
	}
	if !GeoShouldExtract(true, true, strings.Repeat("d", 64), testGeoBundleTime-1, testGeoBundleSha, testGeoBundleTime, 1) {
		t.Fatal("older download kept")
	}
	if GeoShouldExtract(true, true, testGeoBundleSha, testGeoBundleTime, testGeoBundleSha, testGeoBundleTime, 1) {
		t.Fatal("current bundled file extracted again")
	}
}

func TestUIGeoUntrackedFileFollowsItsModificationTime(t *testing.T) {
	// A file mihomo downloaded by itself has no state entry.
	if GeoShouldExtract(true, false, "", 0, testGeoBundleSha, testGeoBundleTime, testGeoBundleTime+1) {
		t.Fatal("newer untracked file overwritten")
	}
	if !GeoShouldExtract(true, false, "", 0, testGeoBundleSha, testGeoBundleTime, testGeoBundleTime-1) {
		t.Fatal("older untracked file kept")
	}
	if GeoShouldExtract(true, false, "", 0, testGeoBundleSha, testGeoBundleTime, 0) {
		t.Fatal("untracked file without a time overwritten")
	}
}

func TestUIGeoUpdateIsDueAfterTheInterval(t *testing.T) {
	cases := []struct {
		checked  int64
		interval int32
		now      int64
		want     bool
	}{
		{0, 24, 10 * testGeoHour, true},
		{10 * testGeoHour, 24, 33 * testGeoHour, false},
		{10 * testGeoHour, 24, 34 * testGeoHour, true},
		{10 * testGeoHour, 0, 11 * testGeoHour, true},
		{-5, 24, 0, true},
	}
	for _, c := range cases {
		if got := GeoUpdateDue(c.checked, c.interval, c.now); got != c.want {
			t.Errorf("GeoUpdateDue(%d, %d, %d) = %v", c.checked, c.interval, c.now, got)
		}
	}
}

func TestUIGeoCoresNeedOnlyTheResourcesTheyRead(t *testing.T) {
	cases := []struct {
		core, text, want string
	}{
		{CoreSingBox, `{"route":{"rule_set":[{"tag":"geoip-cn"}]}}`, ""},
		{CoreXray, `{"outbounds":[{"protocol":"freedom"}]}`, ""},
		{CoreXray, `{"routing":{"rules":[{"domain":["geosite:cn"]},{"ip":["GeoIP:CN"]}]}}`, "geosite,geoip"},
		{CoreXray, `{"ip":["ext:geoip.dat:private"]}`, "geoip"},
		{CoreMihomo, "rules:\n  - MATCH,DIRECT\n", "geosite,mmdb"},
		{CoreMihomo, "rules:\n  - IP-ASN,13335,DIRECT\n", "geosite,mmdb,asn"},
		{CoreMihomo, "geodata-mode: true\nrules:\n  - GEOIP,CN,DIRECT\n", "geosite,mmdb,geoip"},
		{CoreMihomo, "geodata-mode: false\n", "geosite,mmdb"},
		{CoreMihomo, `{"geodata-mode" :` + "\u00a0" + `TRUE}`, "geosite,mmdb,geoip"},
		// Java lowercases İ to "i" plus a combining dot, which is not "geoip:".
		{CoreXray, "GEO\u0130P:CN", ""},
		{"auto", "geoip:cn", ""},
	}
	for _, c := range cases {
		if got := GeoResourcesNeeded(c.core, c.text); got != c.want {
			t.Errorf("GeoResourcesNeeded(%s, %q) = %q, want %q", c.core, c.text, got, c.want)
		}
	}
}
