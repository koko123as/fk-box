package fk

// Number, string and JSON rules of the Kotlin/Java code the core_* ports
// replace, so a value read here means what it meant there.

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf16"
)

// kotlinToLongOrNull is Kotlin's String.toLongOrNull(): an optional leading
// '+' or '-', then BMP decimal digits (Character.digit), null on overflow.
func kotlinToLongOrNull(text string) (int64, bool) {
	units := utf16.Encode([]rune(text))
	if len(units) == 0 {
		return 0, false
	}
	start, negative := 0, false
	if units[0] < '0' {
		if len(units) == 1 {
			return 0, false
		}
		switch units[0] {
		case '-':
			negative = true
		case '+':
		default:
			return 0, false
		}
		start = 1
	}
	// Accumulate negatively, as Kotlin does, so Long.MIN_VALUE fits.
	var value int64
	for _, unit := range units[start:] {
		digit, ok := javaDecimalDigit(rune(unit))
		if !ok {
			return 0, false
		}
		if value < (math.MinInt64+int64(digit))/10 {
			return 0, false
		}
		value = value*10 - int64(digit)
	}
	if negative {
		return value, true
	}
	if value == math.MinInt64 {
		return 0, false
	}
	return -value, true
}

// javaDecimalDigit is Character.digit(c, 10) for one UTF-16 unit: any
// decimal digit (Nd); surrogates are no digit.
func javaDecimalDigit(r rune) (int, bool) {
	if r >= 0xD800 && r <= 0xDFFF || !unicode.Is(unicode.Nd, r) {
		return 0, false
	}
	// Decimal digits come in runs of ten starting at zero.
	zero := r
	for zero > 0 && unicode.Is(unicode.Nd, zero-1) {
		zero--
	}
	return int((r - zero) % 10), true
}

// kotlinToDoubleOrNull is Kotlin's String.toDoubleOrNull() on the JVM:
// Double.valueOf's grammar (ASCII digits, an optional f/F/d/D suffix,
// hexadecimal with a binary exponent, NaN and Infinity, surrounding
// characters up to U+0020 ignored), null for anything else.
func kotlinToDoubleOrNull(text string) (float64, bool) {
	trimmed := strings.TrimFunc(text, func(r rune) bool { return r <= 0x20 })
	s := trimmed
	sign := ""
	if s != "" && (s[0] == '+' || s[0] == '-') {
		sign, s = s[:1], s[1:]
	}
	switch s {
	case "NaN":
		return math.NaN(), true
	case "Infinity":
		if sign == "-" {
			return math.Inf(-1), true
		}
		return math.Inf(1), true
	}
	if s != "" && strings.ContainsRune("fFdD", rune(s[len(s)-1])) {
		s = s[:len(s)-1]
	}
	if !javaFloatLiteral(s) {
		return 0, false
	}
	value, err := strconv.ParseFloat(sign+s, 64)
	if err != nil {
		var numErr *strconv.NumError
		if !errors.As(err, &numErr) || numErr.Err != strconv.ErrRange {
			return 0, false
		}
	}
	return value, true
}

// javaFloatLiteral checks the unsigned, suffix-free part of Double.valueOf's
// grammar: Digits[.Digits][Exp], .Digits[Exp], or 0x Hex[.Hex] p Digits.
func javaFloatLiteral(s string) bool {
	digits := func(s string, hex bool) int {
		n := 0
		for n < len(s) && (s[n] >= '0' && s[n] <= '9' || hex && (s[n] >= 'a' && s[n] <= 'f' || s[n] >= 'A' && s[n] <= 'F')) {
			n++
		}
		return n
	}
	exponent := func(s string, marks string) bool {
		if s == "" {
			return true
		}
		if !strings.ContainsRune(marks, rune(s[0])) {
			return false
		}
		s = s[1:]
		if s != "" && (s[0] == '+' || s[0] == '-') {
			s = s[1:]
		}
		n := digits(s, false)
		return n > 0 && n == len(s)
	}
	if len(s) > 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X') {
		s = s[2:]
		whole := digits(s, true)
		s = s[whole:]
		fraction := 0
		if s != "" && s[0] == '.' {
			s = s[1:]
			fraction = digits(s, true)
			s = s[fraction:]
		}
		// The binary exponent is required.
		return whole+fraction > 0 && s != "" && exponent(s, "pP")
	}
	whole := digits(s, false)
	s = s[whole:]
	fraction := 0
	if s != "" && s[0] == '.' {
		s = s[1:]
		fraction = digits(s, false)
		s = s[fraction:]
	}
	return whole+fraction > 0 && exponent(s, "eE")
}

// javaD2L is Java's (long) cast of a double: NaN is 0, out of range saturates.
func javaD2L(value float64) int64 {
	switch {
	case math.IsNaN(value):
		return 0
	case value >= 9.223372036854775807e18:
		return math.MaxInt64
	case value <= -9.223372036854775808e18:
		return math.MinInt64
	}
	return int64(value)
}

// javaD2I is Java's (int) cast of a double.
func javaD2I(value float64) int32 {
	switch {
	case math.IsNaN(value):
		return 0
	case value >= math.MaxInt32:
		return math.MaxInt32
	case value <= math.MinInt32:
		return math.MinInt32
	}
	return int32(value)
}

// utf16Length is a Java String's length().
func utf16Length(text string) int {
	n := 0
	for _, r := range text {
		if r >= 0x10000 {
			n += 2
		} else {
			n++
		}
	}
	return n
}

// kotlinIsBlank is Kotlin's CharSequence.isBlank().
func kotlinIsBlank(text string) bool {
	return strings.TrimFunc(text, kotlinIsWhitespace) == ""
}

// jvmNumber is a number as org.json reads it: a literal without '.', 'e'
// or 'E' that fits a long is an Integer/Long, anything else a Double.
type jvmNumber struct {
	isLong  bool
	long    int64
	double  float64
	literal string
}

func newJVMNumber(literal json.Number) jvmNumber {
	text := string(literal)
	if !strings.ContainsAny(text, ".eE") {
		if value, err := strconv.ParseInt(text, 10, 64); err == nil {
			return jvmNumber{isLong: true, long: value, literal: text}
		}
	}
	value, _ := strconv.ParseFloat(text, 64)
	return jvmNumber{double: value, literal: text}
}

// toDouble is Number.doubleValue().
func (n jvmNumber) toDouble() float64 {
	if n.isLong {
		return float64(n.long)
	}
	return n.double
}

// toLong is Number.longValue().
func (n jvmNumber) toLong() int64 {
	if n.isLong {
		return n.long
	}
	return javaD2L(n.double)
}

// toInt is Number.intValue(): a long keeps its low 32 bits.
func (n jvmNumber) toInt() int32 {
	if n.isLong {
		return int32(n.long)
	}
	return javaD2I(n.double)
}

// String is String.valueOf(Integer|Long|Double).
func (n jvmNumber) String() string {
	if n.isLong {
		return strconv.FormatInt(n.long, 10)
	}
	return javaDoubleToString(n.double)
}

// javaDoubleToString is Double.toString with the shortest digits that
// identify the value: plain between 10^-3 and 10^7, else d.dddE±n.
func javaDoubleToString(value float64) string {
	switch {
	case math.IsNaN(value):
		return "NaN"
	case math.IsInf(value, 1):
		return "Infinity"
	case math.IsInf(value, -1):
		return "-Infinity"
	case value == 0:
		if math.Signbit(value) {
			return "-0.0"
		}
		return "0.0"
	}
	abs := math.Abs(value)
	if abs >= 1e-3 && abs < 1e7 {
		text := strconv.FormatFloat(value, 'f', -1, 64)
		if !strings.Contains(text, ".") {
			text += ".0"
		}
		return text
	}
	text := strconv.FormatFloat(value, 'e', -1, 64)
	mantissa, exp, _ := strings.Cut(text, "e")
	if !strings.Contains(mantissa, ".") {
		mantissa += ".0"
	}
	exponent, _ := strconv.Atoi(exp)
	return mantissa + "E" + strconv.Itoa(exponent)
}

// decodeJVMJSON reads the first JSON value of data as org.json's
// JSONTokener.nextValue does (trailing text is ignored); numbers stay
// json.Number for jvmNumber. A document that does not open with an object,
// array or string is read as org.json's unquoted literal (a captive portal's
// HTML reads as the string up to its first space), so the caller's type
// error names what org.json named.
func decodeJVMJSON(data []byte) (any, error) {
	text := string(data)
	rest := strings.TrimLeft(text, " \t\n\r")
	if rest == "" {
		return nil, fmt.Errorf("End of input at character %d of %s", utf16Length(text), text)
	}
	if !strings.ContainsRune("{[\"'", rune(rest[0])) {
		end := strings.IndexFunc(rest, func(r rune) bool { return strings.ContainsRune("{}[]/\\:,=;# \t\f\r\n", r) })
		if end < 0 {
			end = len(rest)
		}
		if end == 0 {
			return nil, fmt.Errorf("Expected literal value at character %d of %s", utf16Length(text)-utf16Length(rest), text)
		}
		return orgJSONLiteral(rest[:end]), nil
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	var value any
	if err := decoder.Decode(&value); err != nil {
		return nil, err
	}
	return value, nil
}

// orgJSONLiteral is JSONTokener.readLiteral: null/true/false in any case, a
// whole number (0x hexadecimal, leading-zero octal) as Integer/Long, a
// Double.valueOf number, else the text as a string.
func orgJSONLiteral(literal string) any {
	switch strings.ToLower(literal) {
	case "null":
		return nil
	case "true":
		return true
	case "false":
		return false
	}
	if !strings.Contains(literal, ".") {
		number, base := literal, 10
		if strings.HasPrefix(number, "0x") || strings.HasPrefix(number, "0X") {
			number, base = number[2:], 16
		} else if strings.HasPrefix(number, "0") && len(number) > 1 {
			number, base = number[1:], 8
		}
		if value, err := strconv.ParseInt(number, base, 64); err == nil {
			return json.Number(strconv.FormatInt(value, 10))
		}
	}
	if value, ok := kotlinToDoubleOrNull(literal); ok {
		return jvmDouble(value)
	}
	return literal
}

// jvmDouble is a Double org.json read from an unquoted literal.
type jvmDouble float64

// orgJSONString is JSON.toString(value) as org.json's optString/getString
// read a present value: a string itself, null as "null", anything else
// String.valueOf.
func orgJSONString(value any) string {
	switch v := value.(type) {
	case string:
		return v
	case nil:
		return "null"
	case bool:
		return strconv.FormatBool(v)
	case json.Number:
		return newJVMNumber(v).String()
	case jvmDouble:
		return javaDoubleToString(float64(v))
	default:
		return orgJSONText(v)
	}
}

// orgJSONText is a nested object's or array's toString(): org.json escapes
// '/' and does not escape <, > and &.
func orgJSONText(value any) string {
	var buffer bytes.Buffer
	encoder := json.NewEncoder(&buffer)
	encoder.SetEscapeHTML(false)
	_ = encoder.Encode(value)
	return strings.ReplaceAll(strings.TrimSuffix(buffer.String(), "\n"), "/", `\/`)
}

// orgJSONOptString is JSONObject.optString(name): "" when absent.
func orgJSONOptString(object map[string]any, name string) string {
	value, present := object[name]
	if !present {
		return ""
	}
	return orgJSONString(value)
}

// orgJSONOptLong is JSONObject.optLong(name, fallback).
func orgJSONOptLong(object map[string]any, name string, fallback int64) int64 {
	switch v := object[name].(type) {
	case json.Number:
		return newJVMNumber(v).toLong()
	case string:
		if value, ok := kotlinToDoubleOrNull(v); ok {
			return javaD2L(value)
		}
	}
	return fallback
}

// orgJSONOptBoolean is JSONObject.optBoolean(name): a boolean, or the
// string "true"/"false" in any case; false otherwise.
func orgJSONOptBoolean(object map[string]any, name string) bool {
	switch v := object[name].(type) {
	case bool:
		return v
	case string:
		return strings.EqualFold(v, "true")
	}
	return false
}
