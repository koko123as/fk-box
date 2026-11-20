package fk

// Provider headers of a subscription fetch, parsed as the app's
// SubscriptionHeaders.kt did. profile-web-page-url stays in the app: it is
// checked with java.net.URI there.

import (
	"math"
	"strings"
)

const (
	// uiSubscriptionMinInterval is Android WorkManager's minimum periodic interval.
	uiSubscriptionMinInterval = 15
	// uiSubscriptionMaxInterval is one year; longer is a provider error.
	uiSubscriptionMaxInterval = 365 * 24 * 60
)

// uiSubscriptionInfo is subscription-userinfo: bytes and Unix seconds, 0 when
// not reported (an expire of 0 means no expiry).
type uiSubscriptionInfo struct {
	Upload   int64
	Download int64
	Total    int64
	Expire   int64
}

// uiParseSubscriptionUserInfo parses `upload=..; download=..; total=..;
// expire=..`. Keys are case-insensitive, spaces are tolerated, missing keys
// read as 0, decimals are truncated and negative or non-numeric values are
// ignored. ok is false when nothing usable is present.
func uiParseSubscriptionUserInfo(header string) (info uiSubscriptionInfo, ok bool) {
	if kotlinIsBlank(header) {
		return info, false
	}
	for _, field := range strings.Split(header, ";") {
		separator := strings.Index(field, "=")
		if separator <= 0 {
			continue
		}
		var target *int64
		switch kotlinLowercase(kotlinTrim(field[:separator])) {
		case "upload":
			target = &info.Upload
		case "download":
			target = &info.Download
		case "total":
			target = &info.Total
		case "expire":
			target = &info.Expire
		default:
			continue
		}
		value, valid := uiSubscriptionCount(field[separator+1:])
		if !valid {
			continue
		}
		*target = value
		ok = true
	}
	return info, ok
}

// uiSubscriptionCount is one userinfo value: a whole number (negative is
// ignored, not read as a decimal), else a finite non-negative decimal,
// truncated and capped at Long.MAX_VALUE.
func uiSubscriptionCount(raw string) (int64, bool) {
	value := kotlinTrim(raw)
	if value == "" {
		return 0, false
	}
	if count, ok := kotlinToLongOrNull(value); ok {
		return count, count >= 0
	}
	decimal, ok := kotlinToDoubleOrNull(value)
	if !ok || math.IsNaN(decimal) || math.IsInf(decimal, 0) || decimal < 0 {
		return 0, false
	}
	return javaD2L(decimal), true
}

// uiParseSubscriptionInterval reads profile-update-interval (hours) as
// minutes, rounded and kept within 15 minutes and one year; ok is false for
// a missing, non-numeric, zero or negative value.
func uiParseSubscriptionInterval(header string) (int32, bool) {
	hours, ok := kotlinToDoubleOrNull(kotlinTrim(header))
	if !ok || math.IsNaN(hours) || math.IsInf(hours, 0) || hours <= 0 {
		return 0, false
	}
	minutes := math.Min(hours*60, uiSubscriptionMaxInterval)
	// Math.round of a positive value: ties up, as math.Round.
	rounded := int32(math.Round(minutes))
	if rounded < uiSubscriptionMinInterval {
		rounded = uiSubscriptionMinInterval
	}
	return rounded, true
}
