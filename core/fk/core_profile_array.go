package fk

// Naming the profiles a JSON array of configs becomes, and the element a
// subscription profile keeps following when its URL returns an array (moved
// from the Android app's ProfileArray). gomobile cannot pass slices, so lists
// travel as JSON arrays of strings. Trimming follows Kotlin's String.trim so
// the names are the ones the app produced before.

import (
	"encoding/json"
	"regexp"
	"strconv"
	"strings"
	"unicode"
)

// profileArrayFallback is the app's Regex("""\s#(\d+)$""") as Android's ICU
// engine runs it: \s is White_Space, \d any decimal digit (Nd), and $ also
// matches before one final line terminator.
var profileArrayFallback = regexp.MustCompile(`[` + androidWhiteSpace + `]#(\p{Nd}+)(?:\r\n|[\n\r\x{0085}\x{2028}\x{2029}])?$`)

// androidWhiteSpace is the Unicode White_Space property, Android's \s.
const androidWhiteSpace = `\t\n\x0B\f\r \x{0085}\x{00A0}\x{1680}\x{2000}-\x{200A}\x{2028}\x{2029}\x{202F}\x{205F}\x{3000}`

// kotlinIsWhitespace is Kotlin's Char.isWhitespace on the JVM:
// Character.isWhitespace or Character.isSpaceChar.
func kotlinIsWhitespace(r rune) bool {
	if (r >= 0x09 && r <= 0x0D) || (r >= 0x1C && r <= 0x1F) {
		return true
	}
	return unicode.In(r, unicode.Zs, unicode.Zl, unicode.Zp)
}

func kotlinTrim(value string) string {
	return strings.TrimFunc(value, kotlinIsWhitespace)
}

// profileArrayName is an element's profile name: its remarks, or
// "<source> #<n>" (n is index+1, source "Profile" when blank) without them.
func profileArrayName(source string, remarks string, index int32) string {
	if trimmed := kotlinTrim(remarks); trimmed != "" {
		return trimmed
	}
	base := kotlinTrim(source)
	if base == "" {
		base = "Profile"
	}
	return base + " #" + strconv.FormatInt(int64(index)+1, 10)
}

// ProfileArrayNames names the profiles for the elements whose remarks are
// remarksJSON (a JSON array of strings), returned as a JSON array in the same
// order. A lone element without remarks (an array of one-node Xray configs
// merged into one profile) takes the source's own name.
func ProfileArrayNames(source string, remarksJSON string) (string, error) {
	remarks, err := decodeStringList(remarksJSON)
	if err != nil {
		return "", err
	}
	data, err := json.Marshal(profileArrayNames(source, remarks))
	return string(data), err
}

func profileArrayNames(source string, remarks []string) []string {
	names := make([]string, len(remarks))
	if len(remarks) == 1 && kotlinTrim(remarks[0]) == "" {
		names[0] = kotlinTrim(source)
		if names[0] == "" {
			names[0] = "Profile"
		}
	} else {
		for index, element := range remarks {
			names[index] = profileArrayName(source, element, int32(index))
		}
	}
	return names
}

// ProfileArraySelect returns the element a subscription profile follows, or
// -1: the element whose remarks equal the saved content's remarks, then the
// profile's name; a profile named by the "<source> #n" fallback follows
// element n when that element has no remarks; a one-element array always
// matches.
func ProfileArraySelect(remarksJSON string, savedRemarks string, profileName string) (int32, error) {
	remarks, err := decodeStringList(remarksJSON)
	if err != nil {
		return -1, err
	}
	return profileArraySelect(remarks, savedRemarks, profileName), nil
}

func profileArraySelect(remarks []string, savedRemarks string, profileName string) int32 {
	trimmed := make([]string, len(remarks))
	for index, element := range remarks {
		trimmed[index] = kotlinTrim(element)
	}
	indexOf := func(value string) int {
		for index, element := range trimmed {
			if element == value {
				return index
			}
		}
		return -1
	}
	if saved := kotlinTrim(savedRemarks); saved != "" {
		if index := indexOf(saved); index >= 0 {
			return int32(index)
		}
	}
	if name := kotlinTrim(profileName); name != "" {
		if index := indexOf(name); index >= 0 {
			return int32(index)
		}
		if match := profileArrayFallback.FindStringSubmatch(name); match != nil {
			if number, ok := kotlinToIntOrNull(match[1]); ok {
				index := number - 1
				if index >= 0 && index < int64(len(trimmed)) && trimmed[index] == "" {
					return int32(index)
				}
			}
		}
	}
	if len(remarks) == 1 {
		return 0
	}
	return -1
}

// kotlinToIntOrNull is Kotlin's String.toIntOrNull for a run of decimal
// digits: any BMP decimal digit counts (Character.digit), a digit outside the
// BMP is two UTF-16 chars that are no digit, and a value beyond Int is null.
func kotlinToIntOrNull(digits string) (int64, bool) {
	var value int64
	for _, r := range digits {
		if r > 0xFFFF || !unicode.Is(unicode.Nd, r) {
			return 0, false
		}
		// Decimal digits come in runs of ten starting at zero.
		zero := r
		for zero > 0 && unicode.Is(unicode.Nd, zero-1) {
			zero--
		}
		value = value*10 + int64((r-zero)%10)
		if value > 0x7FFFFFFF {
			return 0, false
		}
	}
	return value, digits != ""
}

func decodeStringList(text string) ([]string, error) {
	var values []string
	if err := json.Unmarshal([]byte(text), &values); err != nil {
		return nil, err
	}
	return values, nil
}
