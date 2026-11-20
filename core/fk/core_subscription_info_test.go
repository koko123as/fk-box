package fk

import (
	"math"
	"net/http"
	"net/http/httptest"
	"testing"
)

// Ported from the app's SubscriptionInfoTest (JVM) when the header parsing
// moved here; the extra cases pin Kotlin's toLongOrNull/toDoubleOrNull rules.

func TestUISubscriptionUserInfoSpacesCaseAndMissingKeys(t *testing.T) {
	for _, test := range []struct {
		header string
		want   uiSubscriptionInfo
	}{
		{" upload=1024; Download = 2048 ;TOTAL=10737418240; expire=1790000000 ", uiSubscriptionInfo{1024, 2048, 10_737_418_240, 1_790_000_000}},
		{"download=5; total=100", uiSubscriptionInfo{Download: 5, Total: 100}},
		{"upload=1;;total=3;", uiSubscriptionInfo{Upload: 1, Total: 3}},
		// A later valid value replaces an earlier one; an invalid one does not.
		{"total=1; total=2; total=x", uiSubscriptionInfo{Total: 2}},
		{"=5; upload=7", uiSubscriptionInfo{Upload: 7}},
		{"upload=+8", uiSubscriptionInfo{Upload: 8}},
		// Kotlin trims Unicode spaces and reads any BMP decimal digit.
		{"\u00a0upload\u3000=\u0661\u0662", uiSubscriptionInfo{Upload: 12}},
		{"\x1cupload=3\x1f", uiSubscriptionInfo{Upload: 3}},
	} {
		got, ok := uiParseSubscriptionUserInfo(test.header)
		if !ok || got != test.want {
			t.Fatalf("%q: got %+v ok=%v, want %+v", test.header, got, ok, test.want)
		}
	}
}

func TestUISubscriptionUserInfoFloatsAndBadValues(t *testing.T) {
	got, ok := uiParseSubscriptionUserInfo("upload=12.9; download=-4; total=1e9; expire=never")
	if !ok || got != (uiSubscriptionInfo{Upload: 12, Total: 1_000_000_000}) {
		t.Fatalf("got %+v ok=%v", got, ok)
	}
	for header, want := range map[string]int64{
		"total=1e30":                 math.MaxInt64,
		"total=99999999999999999999": math.MaxInt64, // beyond Long: read as a decimal
		"total=0x10p0":               16,            // Double.valueOf's hexadecimal form
		"total=5d":                   5,             // and its type suffix
		"total=2.5F":                 2,
		"total=1.":                   1,
		"total=.9":                   0,
		"total=-0":                   0,
	} {
		got, ok := uiParseSubscriptionUserInfo(header)
		if !ok || got.Total != want {
			t.Fatalf("%q: got %+v ok=%v, want total %d", header, got, ok, want)
		}
	}
	for _, header := range []string{"total=Infinity", "total=NaN", "total=-1.5", "total=1_000", "total=0x10", "total=1e", "total=\U0001D7CF"} {
		if got, ok := uiParseSubscriptionUserInfo(header); ok {
			t.Fatalf("%q: got %+v, want nothing usable", header, got)
		}
	}
}

func TestUISubscriptionUserInfoNothingUsable(t *testing.T) {
	for _, header := range []string{"", "   ", "\u2003", "foo=1; bar", "upload=abc", "upload=-1"} {
		if got, ok := uiParseSubscriptionUserInfo(header); ok {
			t.Fatalf("%q: got %+v", header, got)
		}
	}
	// All zero is still a report (the app shows it as empty).
	if got, ok := uiParseSubscriptionUserInfo("upload=0; download=0; total=0; expire=0"); !ok || got != (uiSubscriptionInfo{}) {
		t.Fatalf("zeros: %+v %v", got, ok)
	}
}

func TestUISubscriptionIntervalHoursClamped(t *testing.T) {
	for header, want := range map[string]int32{
		"12": 720, " 1.5 ": 90, "0.1": 15, "1000000": uiSubscriptionMaxInterval, "1e308": uiSubscriptionMaxInterval,
		"0.2575": 15, "0.2583": 15, "0.2584": 16, "0.75": 45, "0.0125": 15, "24d": 1440, "0x1p1": 120,
		"0.025": 15, "0.275": 17, // 16.5 minutes rounds half up
	} {
		got, ok := uiParseSubscriptionInterval(header)
		if !ok || got != want {
			t.Fatalf("%q: got %d ok=%v, want %d", header, got, ok, want)
		}
	}
	for _, header := range []string{"0", "-3", "daily", "", "NaN", "Infinity", "-0.0"} {
		if got, ok := uiParseSubscriptionInterval(header); ok {
			t.Fatalf("%q: got %d", header, got)
		}
	}
}

func TestUISubscriptionResponseCarriesParsedHeaders(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/plain" {
			_, _ = w.Write([]byte("{}"))
			return
		}
		w.Header()["subscription-userinfo"] = []string{"upload=1; download=2.7; total=10; expire=1790000000"}
		w.Header()["profile-update-interval"] = []string{"24"}
		w.Header().Set("Profile-Web-Page-Url", "javascript:alert(1)")
		_, _ = w.Write([]byte("{}"))
	}))
	defer server.Close()
	response, err := UISubscriptionGet(server.URL, "sing-box")
	if err != nil {
		t.Fatal(err)
	}
	if !response.HasInfo || response.Upload != 1 || response.Download != 2 || response.Total != 10 || response.Expire != 1_790_000_000 ||
		response.IntervalMinutes != 1440 || response.WebPage != "javascript:alert(1)" {
		t.Fatalf("unexpected response: %+v", response)
	}
	response, err = UISubscriptionGet(server.URL+"/plain", "sing-box")
	if err != nil || response.HasInfo || response.Upload != 0 || response.IntervalMinutes != 0 {
		t.Fatalf("response=%+v err=%v", response, err)
	}
}

func TestUIJVMNumberRules(t *testing.T) {
	for text, want := range map[string]int64{"0": 0, "-9223372036854775808": math.MinInt64, "+9223372036854775807": math.MaxInt64, "\u0663": 3} {
		if got, ok := kotlinToLongOrNull(text); !ok || got != want {
			t.Fatalf("toLongOrNull(%q) = %d %v", text, got, ok)
		}
	}
	for _, text := range []string{"", "-", "+", "9223372036854775808", "1 ", "0x10", "1.0", "\U0001D7CF"} {
		if got, ok := kotlinToLongOrNull(text); ok {
			t.Fatalf("toLongOrNull(%q) = %d", text, got)
		}
	}
	for text, want := range map[string]float64{" 1.5 ": 1.5, "\x01-2e1\x00": -20, ".5": 0.5, "1.f": 1, "0X.8P1": 1, "0x1.8p1D": 3} {
		if got, ok := kotlinToDoubleOrNull(text); !ok || got != want {
			t.Fatalf("toDoubleOrNull(%q) = %v %v", text, got, ok)
		}
	}
	for _, text := range []string{"", ".", "e5", "1e", "0x1", "infinity", "inf", "1,5", "1_0", "\u00a01", "1f2", "0x1p1.5"} {
		if got, ok := kotlinToDoubleOrNull(text); ok {
			t.Fatalf("toDoubleOrNull(%q) = %v", text, got)
		}
	}
	if javaD2L(math.NaN()) != 0 || javaD2L(1e300) != math.MaxInt64 || javaD2L(-1e300) != math.MinInt64 || javaD2I(4294967311) != math.MaxInt32 {
		t.Fatal("Java double casts")
	}
	for value, want := range map[float64]string{1: "1.0", 1.15: "1.15", 1e7: "1.0E7", 1.5e-5: "1.5E-5", 123456.789: "123456.789", -0.001: "-0.001"} {
		if got := javaDoubleToString(value); got != want {
			t.Fatalf("Double.toString(%v) = %q, want %q", value, got, want)
		}
	}
}
