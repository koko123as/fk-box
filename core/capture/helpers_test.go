package capture

import (
	"crypto/tls"
	"encoding/json"
	"net"
)

func tlsClient(conn net.Conn, name string) *tls.Conn {
	return tls.Client(conn, &tls.Config{ServerName: name, InsecureSkipVerify: true})
}

func mustJSON(value any) string {
	data, _ := json.Marshal(value)
	return string(data)
}
