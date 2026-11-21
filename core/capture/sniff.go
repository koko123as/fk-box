package capture

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/koko123as/fk-box/core/httpdebug"
)

const sniffTimeout = time.Second

// peekedConn replays the bytes read while sniffing.
type peekedConn struct {
	net.Conn
	reader *bufio.Reader
}

func (c *peekedConn) Read(b []byte) (int, error) { return c.reader.Read(b) }

// sniff reads the client's first bytes without consuming them and answers
// the protocol ("tls" with the ClientHello's server name, "http" with the
// Host header, or "" when neither is recognized in time).
func sniff(conn net.Conn) (net.Conn, string, string) {
	reader := bufio.NewReaderSize(conn, httpdebug.MaxHeaderBytes)
	wrapped := &peekedConn{Conn: conn, reader: reader}
	_ = conn.SetReadDeadline(time.Now().Add(sniffTimeout))
	defer conn.SetReadDeadline(time.Time{})
	first, err := reader.Peek(1)
	if err != nil {
		return wrapped, "", ""
	}
	if first[0] == 0x16 {
		header, err := reader.Peek(5)
		if err != nil {
			return wrapped, "", ""
		}
		length := int(binary.BigEndian.Uint16(header[3:5]))
		record, err := reader.Peek(min(5+length, httpdebug.MaxHeaderBytes))
		if err != nil {
			return wrapped, "", ""
		}
		if name, ok := clientHelloServerName(record[5:]); ok {
			return wrapped, "tls", name
		}
		return wrapped, "", ""
	}
	// An HTTP request: read the header block from a copy of the buffer.
	for size := 512; ; size *= 2 {
		size = min(size, httpdebug.MaxHeaderBytes)
		peeked, err := reader.Peek(size)
		if index := bytes.Index(peeked, []byte("\r\n\r\n")); index >= 0 {
			request, parseErr := http.ReadRequest(bufio.NewReader(bytes.NewReader(peeked[:index+4])))
			if parseErr != nil {
				return wrapped, "", ""
			}
			return wrapped, "http", strings.ToLower(request.Host)
		}
		if err != nil || size == httpdebug.MaxHeaderBytes {
			// Peek answers what arrived before the deadline: not a whole header.
			return wrapped, "", ""
		}
	}
}

// clientHelloServerName parses a TLS handshake message for its SNI.
func clientHelloServerName(message []byte) (string, bool) {
	// handshake type (1) + length (3) + version (2) + random (32)
	if len(message) < 38 || message[0] != 1 {
		return "", false
	}
	data := message[38:]
	skip := func(lengthBytes int) bool {
		if len(data) < lengthBytes {
			return false
		}
		var length int
		for _, b := range data[:lengthBytes] {
			length = length<<8 | int(b)
		}
		if len(data) < lengthBytes+length {
			return false
		}
		data = data[lengthBytes+length:]
		return true
	}
	// session id, cipher suites, compression methods
	if !skip(1) || !skip(2) || !skip(1) {
		return "", false
	}
	if len(data) < 2 {
		return "", false
	}
	extensions := data[2:]
	if total := int(binary.BigEndian.Uint16(data[:2])); total < len(extensions) {
		extensions = extensions[:total]
	}
	for len(extensions) >= 4 {
		kind := binary.BigEndian.Uint16(extensions[:2])
		length := int(binary.BigEndian.Uint16(extensions[2:4]))
		if len(extensions) < 4+length {
			return "", false
		}
		body := extensions[4 : 4+length]
		extensions = extensions[4+length:]
		if kind != 0 || len(body) < 5 {
			continue
		}
		list := body[2:]
		for len(list) >= 3 {
			nameType := list[0]
			nameLength := int(binary.BigEndian.Uint16(list[1:3]))
			if len(list) < 3+nameLength {
				return "", false
			}
			if nameType == 0 {
				return strings.ToLower(string(list[3 : 3+nameLength])), true
			}
			list = list[3+nameLength:]
		}
	}
	// A ClientHello without SNI is still TLS.
	return "", true
}
