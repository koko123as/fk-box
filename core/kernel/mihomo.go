package kernel

// mihomo's REST controller on a Unix socket (external-controller-unix;
// mihomo hub/route/server.go). The socket lives in the app's private
// directory, so it carries no secret.

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"time"
)

// Mihomo is a client of one mihomo controller.
type Mihomo struct {
	client *http.Client
}

// NewMihomo returns a client of the controller listening at socket.
func NewMihomo(socket string) *Mihomo {
	transport := &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			var dialer net.Dialer
			return dialer.DialContext(ctx, "unix", socket)
		},
		MaxIdleConns:    4,
		IdleConnTimeout: 30 * time.Second,
	}
	return &Mihomo{client: &http.Client{Transport: transport}}
}

// Close drops idle connections.
func (m *Mihomo) Close() {
	m.client.CloseIdleConnections()
}

func (m *Mihomo) do(ctx context.Context, method, path string, body any, out any) error {
	var reader io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			return err
		}
		reader = bytes.NewReader(encoded)
	}
	request, err := http.NewRequestWithContext(ctx, method, "http://mihomo"+path, reader)
	if err != nil {
		return err
	}
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := m.client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, 64<<20))
	if err != nil {
		return err
	}
	if response.StatusCode >= 300 {
		var message struct {
			Message string `json:"message"`
		}
		if json.Unmarshal(data, &message) == nil && message.Message != "" {
			return errors.New(message.Message)
		}
		return fmt.Errorf("mihomo: %s %s: %s", method, path, response.Status)
	}
	if out == nil || len(data) == 0 {
		return nil
	}
	return json.Unmarshal(data, out)
}

// Version is GET /version.
func (m *Mihomo) Version(ctx context.Context) (string, error) {
	var version struct {
		Version string `json:"version"`
	}
	err := m.do(ctx, http.MethodGet, "/version", nil, &version)
	return version.Version, err
}

// MihomoDelay is one entry of a proxy's delay history.
type MihomoDelay struct {
	Time  time.Time `json:"time"`
	Delay int       `json:"delay"`
}

// MihomoProxy is one entry of GET /proxies.
type MihomoProxy struct {
	Name    string        `json:"name"`
	Type    string        `json:"type"`
	Now     string        `json:"now"`
	All     []string      `json:"all"`
	History []MihomoDelay `json:"history"`
	Hidden  bool          `json:"hidden"`
}

// LastDelay is the newest delay of the history (0: failed or none) and when
// it was measured.
func (p MihomoProxy) LastDelay() (int, time.Time) {
	if len(p.History) == 0 {
		return 0, time.Time{}
	}
	last := p.History[len(p.History)-1]
	return last.Delay, last.Time
}

// Proxies is GET /proxies.
func (m *Mihomo) Proxies(ctx context.Context) (map[string]MihomoProxy, error) {
	var answer struct {
		Proxies map[string]MihomoProxy `json:"proxies"`
	}
	err := m.do(ctx, http.MethodGet, "/proxies", nil, &answer)
	return answer.Proxies, err
}

// Select is PUT /proxies/{group}.
func (m *Mihomo) Select(ctx context.Context, group, name string) error {
	return m.do(ctx, http.MethodPut, "/proxies/"+url.PathEscape(group), map[string]string{"name": name}, nil)
}

// Delay is GET /proxies/{name}/delay.
func (m *Mihomo) Delay(ctx context.Context, name, testURL string, timeout time.Duration) (int, error) {
	query := url.Values{"url": {testURL}, "timeout": {strconv.Itoa(int(timeout / time.Millisecond))}}
	var answer struct {
		Delay int `json:"delay"`
	}
	err := m.do(ctx, http.MethodGet, "/proxies/"+url.PathEscape(name)+"/delay?"+query.Encode(), nil, &answer)
	return answer.Delay, err
}

// GroupDelay is GET /group/{name}/delay: the delay of every member.
func (m *Mihomo) GroupDelay(ctx context.Context, group, testURL string, timeout time.Duration) (map[string]int, error) {
	query := url.Values{"url": {testURL}, "timeout": {strconv.Itoa(int(timeout / time.Millisecond))}}
	answer := map[string]int{}
	err := m.do(ctx, http.MethodGet, "/group/"+url.PathEscape(group)+"/delay?"+query.Encode(), nil, &answer)
	return answer, err
}

// MihomoMetadata is a connection's metadata.
type MihomoMetadata struct {
	Network         string `json:"network"`
	Type            string `json:"type"`
	SourceIP        string `json:"sourceIP"`
	DestinationIP   string `json:"destinationIP"`
	SourcePort      string `json:"sourcePort"`
	DestinationPort string `json:"destinationPort"`
	InboundName     string `json:"inboundName"`
	InboundUser     string `json:"inboundUser"`
	Host            string `json:"host"`
	SniffHost       string `json:"sniffHost"`
	Process         string `json:"process"`
	ProcessPath     string `json:"processPath"`
	UID             *int32 `json:"uid"`
}

// MihomoConnection is one entry of GET /connections.
type MihomoConnection struct {
	ID          string         `json:"id"`
	Metadata    MihomoMetadata `json:"metadata"`
	Upload      int64          `json:"upload"`
	Download    int64          `json:"download"`
	Start       time.Time      `json:"start"`
	Chains      []string       `json:"chains"`
	Rule        string         `json:"rule"`
	RulePayload string         `json:"rulePayload"`
}

// MihomoConnections is GET /connections.
type MihomoConnections struct {
	DownloadTotal int64              `json:"downloadTotal"`
	UploadTotal   int64              `json:"uploadTotal"`
	Connections   []MihomoConnection `json:"connections"`
}

// Connections is GET /connections.
func (m *Mihomo) Connections(ctx context.Context) (*MihomoConnections, error) {
	var answer MihomoConnections
	err := m.do(ctx, http.MethodGet, "/connections", nil, &answer)
	return &answer, err
}

// CloseConnection is DELETE /connections/{id}.
func (m *Mihomo) CloseConnection(ctx context.Context, id string) error {
	return m.do(ctx, http.MethodDelete, "/connections/"+url.PathEscape(id), nil, nil)
}

// CloseAllConnections is DELETE /connections.
func (m *Mihomo) CloseAllConnections(ctx context.Context) error {
	return m.do(ctx, http.MethodDelete, "/connections", nil, nil)
}

// Mode is the mode of GET /configs.
func (m *Mihomo) Mode(ctx context.Context) (string, error) {
	var answer struct {
		Mode string `json:"mode"`
	}
	err := m.do(ctx, http.MethodGet, "/configs", nil, &answer)
	return answer.Mode, err
}

// SetMode is PATCH /configs {"mode": mode}.
func (m *Mihomo) SetMode(ctx context.Context, mode string) error {
	return m.do(ctx, http.MethodPatch, "/configs", map[string]string{"mode": mode}, nil)
}

// Reload is PUT /configs?force=true with the configuration file at path.
func (m *Mihomo) Reload(ctx context.Context, path string) error {
	return m.do(ctx, http.MethodPut, "/configs?force=true", map[string]string{"path": path}, nil)
}
