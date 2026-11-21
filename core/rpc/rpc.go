// Package rpc exposes Go functions and objects to fk-core's client by name,
// the way gomobile bound them into libbox.aar before (docs/kernel-isolation.md
// section 8): the client calls "name" with its arguments as a JSON array, in
// order; the result is the function's first return value as JSON (null when
// it returns none), and a returned error is the call's error.
//
// A function that returns a pointer to a registered object type answers
// {"$handle": n}; the client calls the object's methods as "Type.Method"
// with the handle as the first argument and releases it with "$release".
//
// Encoding: Go's encoding/json, so []byte travels as base64 and struct fields
// under their Go names (or their json tags).
package rpc

import (
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
	"sort"
	"sync"
)

// Handle is how an object travels to the client.
type Handle struct {
	ID int64 `json:"$handle"`
}

// Registry maps names to functions and object types.
type Registry struct {
	access    sync.RWMutex
	functions map[string]reflect.Value
	objects   map[reflect.Type]string

	handleAccess sync.Mutex
	nextHandle   int64
	handles      map[int64]any
}

// New returns an empty registry.
func New() *Registry {
	return &Registry{
		functions: make(map[string]reflect.Value),
		objects:   make(map[reflect.Type]string),
		handles:   make(map[int64]any),
	}
}

var errorType = reflect.TypeOf((*error)(nil)).Elem()

// Func registers function under name. It panics when function is not a func
// or returns more than a value and an error.
func (r *Registry) Func(name string, function any) {
	value := reflect.ValueOf(function)
	if value.Kind() != reflect.Func {
		panic("rpc: " + name + " is not a function")
	}
	if err := checkResults(value.Type()); err != nil {
		panic("rpc: " + name + ": " + err.Error())
	}
	r.access.Lock()
	defer r.access.Unlock()
	if _, exists := r.functions[name]; exists {
		panic("rpc: " + name + " registered twice")
	}
	r.functions[name] = value
}

// Object registers the methods of the pointer type of sample under the type
// name typeName ("GeoUpdate" -> "GeoUpdate.Fetch").
func (r *Registry) Object(typeName string, sample any) {
	t := reflect.TypeOf(sample)
	if t.Kind() != reflect.Pointer {
		panic("rpc: object " + typeName + " must be a pointer type")
	}
	for i := 0; i < t.NumMethod(); i++ {
		if err := checkResults(t.Method(i).Type); err != nil {
			panic("rpc: " + typeName + "." + t.Method(i).Name + ": " + err.Error())
		}
	}
	r.access.Lock()
	defer r.access.Unlock()
	r.objects[t] = typeName
}

func checkResults(t reflect.Type) error {
	switch t.NumOut() {
	case 0:
	case 1:
	case 2:
		if !t.Out(1).Implements(errorType) {
			return errors.New("second result must be an error")
		}
	default:
		return errors.New("too many results")
	}
	return nil
}

// Names lists every callable name, sorted.
func (r *Registry) Names() []string {
	r.access.RLock()
	defer r.access.RUnlock()
	var names []string
	for name := range r.functions {
		names = append(names, name)
	}
	for t, typeName := range r.objects {
		for i := 0; i < t.NumMethod(); i++ {
			names = append(names, typeName+"."+t.Method(i).Name)
		}
	}
	sort.Strings(names)
	return names
}

// Has reports whether name is a registered function or object method.
func (r *Registry) Has(name string) bool {
	if name == "$release" {
		return true
	}
	r.access.RLock()
	defer r.access.RUnlock()
	if _, ok := r.functions[name]; ok {
		return true
	}
	_, _, ok := r.methodLocked(name)
	return ok
}

func (r *Registry) methodLocked(name string) (reflect.Type, string, bool) {
	for t, typeName := range r.objects {
		if len(name) > len(typeName)+1 && name[:len(typeName)] == typeName && name[len(typeName)] == '.' {
			method := name[len(typeName)+1:]
			if _, ok := t.MethodByName(method); ok {
				return t, method, true
			}
		}
	}
	return nil, "", false
}

// Call runs name with the JSON array args.
func (r *Registry) Call(name string, args json.RawMessage) (json.RawMessage, error) {
	var raw []json.RawMessage
	if len(args) > 0 && string(args) != "null" {
		if err := json.Unmarshal(args, &raw); err != nil {
			return nil, fmt.Errorf("%s: arguments must be a JSON array: %w", name, err)
		}
	}
	if name == "$release" {
		var id int64
		if len(raw) != 1 || json.Unmarshal(raw[0], &id) != nil {
			return nil, errors.New("$release: expected a handle")
		}
		r.release(id)
		return json.RawMessage("null"), nil
	}
	r.access.RLock()
	function, isFunction := r.functions[name]
	var receiverType reflect.Type
	var methodName string
	isMethod := false
	if !isFunction {
		receiverType, methodName, isMethod = r.methodLocked(name)
	}
	r.access.RUnlock()
	switch {
	case isFunction:
		return r.invoke(name, function, raw)
	case isMethod:
		if len(raw) == 0 {
			return nil, fmt.Errorf("%s: missing handle", name)
		}
		var id int64
		if err := json.Unmarshal(raw[0], &id); err != nil {
			return nil, fmt.Errorf("%s: invalid handle: %w", name, err)
		}
		object := r.lookup(id)
		if object == nil || reflect.TypeOf(object) != receiverType {
			return nil, fmt.Errorf("%s: unknown or released handle %d", name, id)
		}
		return r.invoke(name, reflect.ValueOf(object).MethodByName(methodName), raw[1:])
	}
	return nil, fmt.Errorf("%s: unknown method", name)
}

func (r *Registry) invoke(name string, function reflect.Value, raw []json.RawMessage) (result json.RawMessage, err error) {
	t := function.Type()
	if t.IsVariadic() || len(raw) != t.NumIn() {
		return nil, fmt.Errorf("%s: expected %d arguments, got %d", name, t.NumIn(), len(raw))
	}
	in := make([]reflect.Value, t.NumIn())
	for i := range in {
		target := reflect.New(t.In(i))
		if err := json.Unmarshal(raw[i], target.Interface()); err != nil {
			return nil, fmt.Errorf("%s: argument %d: %w", name, i, err)
		}
		in[i] = target.Elem()
	}
	defer func() {
		if recovered := recover(); recovered != nil {
			err = fmt.Errorf("%s: panic: %v", name, recovered)
		}
	}()
	out := function.Call(in)
	var value any
	switch len(out) {
	case 2:
		if !out[1].IsNil() {
			return nil, out[1].Interface().(error)
		}
		fallthrough
	case 1:
		if function.Type().Out(0) == errorType {
			if !out[0].IsNil() {
				return nil, out[0].Interface().(error)
			}
			break
		}
		value = r.wrap(out[0])
	}
	encoded, err := json.Marshal(value)
	if err != nil {
		return nil, fmt.Errorf("%s: encode result: %w", name, err)
	}
	return encoded, nil
}

func (r *Registry) wrap(value reflect.Value) any {
	if value.Kind() == reflect.Pointer {
		r.access.RLock()
		_, isObject := r.objects[value.Type()]
		r.access.RUnlock()
		if isObject {
			if value.IsNil() {
				return nil
			}
			r.handleAccess.Lock()
			r.nextHandle++
			id := r.nextHandle
			r.handles[id] = value.Interface()
			r.handleAccess.Unlock()
			return Handle{ID: id}
		}
	}
	if !value.IsValid() {
		return nil
	}
	return value.Interface()
}

func (r *Registry) lookup(id int64) any {
	r.handleAccess.Lock()
	defer r.handleAccess.Unlock()
	return r.handles[id]
}

// release forgets a handle; an object with a Close() method is closed.
func (r *Registry) release(id int64) {
	r.handleAccess.Lock()
	object := r.handles[id]
	delete(r.handles, id)
	r.handleAccess.Unlock()
	if closer, ok := object.(interface{ Close() }); ok {
		closer.Close()
	} else if closer, ok := object.(interface{ Close() error }); ok {
		_ = closer.Close()
	}
}

// ReleaseAll forgets every handle (the client went away).
func (r *Registry) ReleaseAll() {
	r.handleAccess.Lock()
	ids := make([]int64, 0, len(r.handles))
	for id := range r.handles {
		ids = append(ids, id)
	}
	r.handleAccess.Unlock()
	for _, id := range ids {
		r.release(id)
	}
}
