package fk

// Delay tests for Xray (it has no delay API): an HTTPS request through the
// local inbound with the user whose routing rule sends it to the outbound or
// balancer under test (core_service_config.go).

import (
	"context"
	"net/http"
	"net/url"
	"strconv"
	"sync"
	"time"
)

// xrayURLTest measures tag (an outbound, or every member of a balancer), at
// most 8 at a time.
func xrayURLTest(ctx context.Context, running *serviceKernel, tag string) {
	layout := running.layout
	if layout == nil {
		return
	}
	tags := []string{tag}
	for _, balancer := range layout.balancers {
		if balancer.tag == tag {
			tags = balancer.members
		}
	}
	if tag == "outbounds" {
		tags = layout.outbounds
	}
	limit := make(chan struct{}, 8)
	var wait sync.WaitGroup
	for _, member := range tags {
		user, ok := layout.testUsers[member]
		if !ok {
			continue
		}
		wait.Add(1)
		limit <- struct{}{}
		go func(member, user string) {
			defer wait.Done()
			defer func() { <-limit }()
			delay := measureThroughLocal(ctx, running, user)
			running.delayAccess.Lock()
			running.delays[member] = serviceDelay{delay: delay, at: time.Now()}
			running.delayAccess.Unlock()
			notifyServiceChange()
		}(member, user)
	}
	wait.Wait()
}

// measureThroughLocal is the time of one request through the local inbound
// as user, in milliseconds (0 when it failed). The connection is opened
// first, so the time is that of the request alone, as sing-box measures.
func measureThroughLocal(ctx context.Context, running *serviceKernel, user string) int32 {
	proxy := &url.URL{
		Scheme: "http",
		User:   url.UserPassword(user, running.localPassword),
		Host:   "127.0.0.1:" + strconv.Itoa(running.localPort),
	}
	client := &http.Client{
		Transport: &http.Transport{Proxy: http.ProxyURL(proxy), DisableKeepAlives: false},
		Timeout:   10 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	defer client.CloseIdleConnections()
	request := func() (time.Duration, bool) {
		req, err := http.NewRequestWithContext(ctx, http.MethodHead, serviceTestURL, nil)
		if err != nil {
			return 0, false
		}
		start := time.Now()
		response, err := client.Do(req)
		if err != nil {
			return 0, false
		}
		response.Body.Close()
		return time.Since(start), true
	}
	if _, ok := request(); !ok {
		return 0
	}
	elapsed, ok := request()
	if !ok {
		return 0
	}
	delay := int32(elapsed / time.Millisecond)
	if delay <= 0 {
		delay = 1
	}
	return delay
}
