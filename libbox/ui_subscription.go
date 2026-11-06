package libbox

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const (
	uiSubscriptionLimit   = 32 * 1024 * 1024
	uiSubscriptionTimeout = 60 * time.Second
	uiSubscriptionHeader  = 4096
)

// UISubscriptionResponse carries a subscription body together with the raw
// provider headers the app reads for traffic, expiry and update hints. Empty
// strings mean the provider did not send that header.
type UISubscriptionResponse struct {
	Content        string
	UserInfo       string
	UpdateInterval string
	WebPage        string
}

// UISubscriptionGet fetches a subscription the way HTTPRequest.Execute does
// (upstream certificate store, Modern TLS, URL credentials as Basic auth,
// non-200 is an error) and also returns the subscription-userinfo,
// profile-update-interval and profile-web-page-url headers, which the upstream
// HTTPResponse has no accessor for.
func UISubscriptionGet(link string, userAgent string) (*UISubscriptionResponse, error) {
	target, err := url.Parse(link)
	if err != nil || target == nil || target.Hostname() == "" || (target.Scheme != "http" && target.Scheme != "https") {
		return nil, fmt.Errorf("invalid HTTP or HTTPS subscription address")
	}
	ctx, cancel := context.WithTimeout(context.Background(), uiSubscriptionTimeout)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, target.String(), nil)
	if err != nil {
		return nil, fmt.Errorf("invalid HTTP request")
	}
	if target.User != nil {
		password, _ := target.User.Password()
		request.SetBasicAuth(target.User.Username(), password)
	}
	if userAgent != "" {
		request.Header.Set("User-Agent", userAgent)
	}
	client := NewHTTPClient().(*httpClient)
	defer client.Close()
	client.ModernTLS()
	client.client.Timeout = uiSubscriptionTimeout
	response, err := client.client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		detail, _ := io.ReadAll(io.LimitReader(response.Body, uiSubscriptionHeader))
		if text := strings.TrimSpace(string(detail)); text != "" {
			return nil, fmt.Errorf("HTTP %s: %s", response.Status, text)
		}
		return nil, fmt.Errorf("HTTP %s", response.Status)
	}
	if response.ContentLength > uiSubscriptionLimit {
		return nil, fmt.Errorf("subscription exceeds size limit")
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, uiSubscriptionLimit+1))
	if err != nil {
		return nil, err
	}
	if len(body) > uiSubscriptionLimit {
		return nil, fmt.Errorf("subscription exceeds size limit")
	}
	return &UISubscriptionResponse{
		Content:        string(body),
		UserInfo:       uiSubscriptionHeaderValue(response.Header, "subscription-userinfo"),
		UpdateInterval: uiSubscriptionHeaderValue(response.Header, "profile-update-interval"),
		WebPage:        uiSubscriptionHeaderValue(response.Header, "profile-web-page-url"),
	}, nil
}

// uiSubscriptionHeaderValue matches the name case-insensitively, including
// headers stored under a non-canonical key, and bounds the value.
func uiSubscriptionHeaderValue(header http.Header, name string) string {
	value := header.Get(name)
	if value == "" {
		for key, values := range header {
			if strings.EqualFold(key, name) && len(values) > 0 {
				value = values[0]
				break
			}
		}
	}
	value = strings.TrimSpace(value)
	if len(value) > uiSubscriptionHeader {
		return ""
	}
	return value
}
