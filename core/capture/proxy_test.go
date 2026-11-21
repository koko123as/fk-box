package capture

import (
	"bufio"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/koko123as/fk-box/core/httpdebug"
	"github.com/stretchr/testify/require"
	"golang.org/x/net/proxy"
)

// connectProxy stands in for a kernel's local inbound: an HTTP CONNECT
// proxy with basic authentication.
func connectProxy(t *testing.T, tunnels *atomic.Int32) string {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	t.Cleanup(func() { listener.Close() })
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				reader := bufio.NewReader(conn)
				request, err := http.ReadRequest(reader)
				if err != nil || request.Method != http.MethodConnect || request.Header.Get("Proxy-Authorization") == "" {
					return
				}
				upstream, err := net.Dial("tcp", request.Host)
				if err != nil {
					conn.Write([]byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
					return
				}
				defer upstream.Close()
				tunnels.Add(1)
				conn.Write([]byte("HTTP/1.1 200 Connection established\r\n\r\n"))
				go io.Copy(upstream, reader)
				io.Copy(conn, upstream)
			}()
		}
	}()
	return "http://fkbox-return:secret@" + listener.Addr().String()
}

func TestProxyCapturesHTTPThroughTheKernel(t *testing.T) {
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		io.WriteString(w, "hello "+r.URL.Path)
	}))
	defer origin.Close()
	_, portText, _ := net.SplitHostPort(strings.TrimPrefix(origin.URL, "http://"))
	port, _ := strconv.Atoi(portText)

	directory := t.TempDir()
	require.NoError(t, httpdebug.Start(directory, `{"ports":[`+portText+`]}`, "", nil))
	defer httpdebug.Stop()

	var tunnels atomic.Int32
	upstream := connectProxy(t, &tunnels)
	server, err := Listen(Options{Username: "u", Password: "p", Upstream: func() string { return upstream }})
	require.NoError(t, err)
	defer server.Close()

	dialer, err := proxy.SOCKS5("tcp", "127.0.0.1:"+strconv.Itoa(server.Port()), &proxy.Auth{User: "u", Password: "p"}, proxy.Direct)
	require.NoError(t, err)
	client := &http.Client{Transport: &http.Transport{Dial: dialer.Dial}, Timeout: 10 * time.Second}
	response, err := client.Get("http://127.0.0.1:" + strconv.Itoa(port) + "/captured")
	require.NoError(t, err)
	body, _ := io.ReadAll(response.Body)
	response.Body.Close()
	require.Equal(t, "hello /captured", string(body))
	require.EqualValues(t, 1, tunnels.Load(), "the upstream left through the kernel's local inbound")

	require.Eventually(t, func() bool {
		store, err := httpdebug.Saved()
		if err != nil {
			return false
		}
		return strings.Contains(mustJSON(store.Page(10, "")), "/captured")
	}, 5*time.Second, 50*time.Millisecond)
}

func TestProxyRelaysWhatItDoesNotCapture(t *testing.T) {
	httpdebug.Stop()
	echo, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	defer echo.Close()
	go func() {
		conn, err := echo.Accept()
		if err == nil {
			io.Copy(conn, conn)
			conn.Close()
		}
	}()
	var tunnels atomic.Int32
	upstream := connectProxy(t, &tunnels)
	server, err := Listen(Options{Username: "u", Password: "p", Upstream: func() string { return upstream }})
	require.NoError(t, err)
	defer server.Close()
	dialer, err := proxy.SOCKS5("tcp", "127.0.0.1:"+strconv.Itoa(server.Port()), &proxy.Auth{User: "u", Password: "p"}, proxy.Direct)
	require.NoError(t, err)
	conn, err := dialer.Dial("tcp", echo.Addr().String())
	require.NoError(t, err)
	defer conn.Close()
	_, err = conn.Write([]byte("ping"))
	require.NoError(t, err)
	reply := make([]byte, 4)
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, err = io.ReadFull(conn, reply)
	require.NoError(t, err)
	require.Equal(t, "ping", string(reply))

	_, err = proxy.SOCKS5("tcp", "127.0.0.1:"+strconv.Itoa(server.Port()), &proxy.Auth{User: "u", Password: "wrong"}, proxy.Direct)
	require.NoError(t, err)
	bad, _ := proxy.SOCKS5("tcp", "127.0.0.1:"+strconv.Itoa(server.Port()), &proxy.Auth{User: "u", Password: "wrong"}, proxy.Direct)
	_, err = bad.Dial("tcp", echo.Addr().String())
	require.Error(t, err)
}

func TestClientHelloServerName(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	defer listener.Close()
	got := make(chan string, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		_, protocol, host := sniff(conn)
		got <- protocol + " " + host
	}()
	conn, err := net.Dial("tcp", listener.Addr().String())
	require.NoError(t, err)
	go func() {
		client := tlsClient(conn, "Example.COM")
		client.Handshake()
	}()
	select {
	case value := <-got:
		require.Equal(t, "tls example.com", value)
	case <-time.After(5 * time.Second):
		t.Fatal("no ClientHello")
	}
	conn.Close()
}
