package rpc

import (
	"encoding/json"
	"errors"
	"testing"
)

type counter struct {
	n      int
	closed bool
}

func (c *counter) Add(delta int) int { c.n += delta; return c.n }
func (c *counter) Fail() error       { return errors.New("failed") }
func (c *counter) Close()            { c.closed = true }

type result struct {
	Content string
	Count   int32 `json:"count"`
}

func TestRegistryFunctionsAndHandles(t *testing.T) {
	registry := New()
	registry.Func("echo", func(s string, n int64, b []byte) (result, error) {
		return result{Content: s + string(b), Count: int32(n)}, nil
	})
	registry.Func("fail", func() error { return errors.New("boom") })
	registry.Func("noResult", func(bool) {})
	var made *counter
	registry.Func("newCounter", func() *counter { made = &counter{}; return made })
	registry.Object("Counter", (*counter)(nil))

	out, err := registry.Call("echo", json.RawMessage(`["a", 3, "Yg=="]`))
	if err != nil || string(out) != `{"Content":"ab","count":3}` {
		t.Fatalf("echo: %s %v", out, err)
	}
	if _, err = registry.Call("fail", nil); err == nil || err.Error() != "boom" {
		t.Fatalf("fail: %v", err)
	}
	if out, err = registry.Call("noResult", json.RawMessage(`[true]`)); err != nil || string(out) != "null" {
		t.Fatalf("noResult: %s %v", out, err)
	}
	if _, err = registry.Call("echo", json.RawMessage(`["a"]`)); err == nil {
		t.Fatal("wrong argument count accepted")
	}
	out, err = registry.Call("newCounter", nil)
	if err != nil {
		t.Fatal(err)
	}
	var handle Handle
	if err = json.Unmarshal(out, &handle); err != nil || handle.ID == 0 {
		t.Fatalf("handle: %s %v", out, err)
	}
	id, _ := json.Marshal(handle.ID)
	if out, err = registry.Call("Counter.Add", json.RawMessage(`[`+string(id)+`, 5]`)); err != nil || string(out) != "5" {
		t.Fatalf("Counter.Add: %s %v", out, err)
	}
	if _, err = registry.Call("Counter.Fail", json.RawMessage(`[`+string(id)+`]`)); err == nil {
		t.Fatal("Counter.Fail: no error")
	}
	if !registry.Has("Counter.Add") || registry.Has("Counter.Nope") {
		t.Fatal("Has")
	}
	if _, err = registry.Call("$release", json.RawMessage(`[`+string(id)+`]`)); err != nil || !made.closed {
		t.Fatalf("release: %v closed=%v", err, made.closed)
	}
	if _, err = registry.Call("Counter.Add", json.RawMessage(`[`+string(id)+`, 1]`)); err == nil {
		t.Fatal("released handle still answers")
	}
}
