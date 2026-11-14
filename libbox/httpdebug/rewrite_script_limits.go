package httpdebug

import (
	"context"
	"errors"
	"runtime"
	"runtime/metrics"
	"time"

	"github.com/dop251/goja"
)

// goja has no allocation limit, and native built-ins (String.prototype.repeat,
// Array.prototype.join, typed array constructors, ...) allocate in one Go call
// that Interrupt cannot stop, so "x".repeat(2**29) would take the whole VPN
// process down before the script timeout fires. Two layers bound a script:
//
//   - scriptGuardSource wraps the built-ins that allocate a caller-chosen size
//     in one step and throws a RangeError above the limits below;
//   - watchScriptMemory interrupts the runtime when the Go heap grows by more
//     than scriptHeapBudget while the script runs, which catches growth built
//     up by bytecode (s += s, pushing in a loop).
const (
	// Bodies are at most MaxBodyLimit (1 MiB); a UTF-16 copy plus edits stays
	// well inside these bounds.
	scriptMaxStringLength = 8 * 1024 * 1024
	scriptMaxArrayLength  = 1024 * 1024
	scriptMaxBufferBytes  = 8 * 1024 * 1024
	scriptHeapBudget      = 64 * 1024 * 1024
	scriptHeapSample      = 2 * time.Millisecond
	scriptHeapCollectGap  = 100 * time.Millisecond
)

var errScriptMemory = errors.New("script memory limit exceeded")

const scriptGuardSource = `(function (maxString, maxArray, maxBuffer) {
	"use strict";
	var tooLarge = function () { throw new RangeError("script memory limit exceeded"); };
	var toNumber = function (value) { var n = Number(value); return n !== n ? 0 : n; };
	var replace = function (target, name, wrap) {
		var original = target[name];
		if (typeof original !== "function") return;
		Object.defineProperty(target, name, { value: wrap(original), writable: true, configurable: true, enumerable: false });
	};
	// goja concatenates long strings lazily (s += s builds a rope without
	// copying) and flattens them inside the next native call, so every
	// string built-in refuses an over-long receiver or argument first.
	var checkStrings = function (self, args) {
		if (typeof self === "string" && self.length > maxString) tooLarge();
		for (var i = 0; i < args.length; i++) {
			if (typeof args[i] === "string" && args[i].length > maxString) tooLarge();
		}
	};
	var guardAll = function (target, names) {
		(names || Object.getOwnPropertyNames(target)).forEach(function (name) {
			if (name === "constructor") return;
			var descriptor = Object.getOwnPropertyDescriptor(target, name);
			if (!descriptor || typeof descriptor.value !== "function" || !descriptor.configurable) return;
			var original = descriptor.value;
			Object.defineProperty(target, name, {
				value: function () { checkStrings(this, arguments); return original.apply(this, arguments); },
				writable: true, configurable: true, enumerable: false
			});
		});
	};
	guardAll(String.prototype);
	guardAll(String);
	guardAll(RegExp.prototype);
	guardAll(JSON);
	guardAll(globalThis, ["encodeURI", "encodeURIComponent", "decodeURI", "decodeURIComponent", "escape", "unescape", "parseInt", "parseFloat"]);
	var S = String.prototype;
	replace(S, "repeat", function (original) {
		return function repeat(count) {
			if (this != null) {
				var length = String(this).length, n = toNumber(count);
				if (length > 0 && n > 0 && length * n > maxString) tooLarge();
			}
			return original.apply(this, arguments);
		};
	});
	["padStart", "padEnd"].forEach(function (name) {
		replace(S, name, function (original) {
			return function (maxLength) {
				if (toNumber(maxLength) > maxString) tooLarge();
				return original.apply(this, arguments);
			};
		});
	});
	replace(S, "concat", function (original) {
		return function concat() {
			var total = this != null ? String(this).length : 0;
			for (var i = 0; i < arguments.length; i++) {
				var item = arguments[i];
				if (typeof item === "string") total += item.length;
				if (total > maxString) tooLarge();
			}
			return original.apply(this, arguments);
		};
	});
	var A = Array.prototype;
	replace(A, "join", function (original) {
		return function join(separator) {
			if (this != null) {
				var length = toNumber(this.length);
				if (length > maxArray) tooLarge();
				var total = (length > 0 ? length - 1 : 0) * (separator === undefined ? 1 : String(separator).length);
				for (var i = 0; i < length && total <= maxString; i++) {
					var item = this[i];
					if (typeof item === "string") total += item.length;
				}
				if (total > maxString) tooLarge();
			}
			return original.apply(this, arguments);
		};
	});
	replace(A, "fill", function (original) {
		return function fill() {
			if (this != null && toNumber(this.length) > maxArray) tooLarge();
			return original.apply(this, arguments);
		};
	});
	replace(Array, "from", function (original) {
		return function from(items) {
			if (items != null && typeof items === "object" && toNumber(items.length) > maxArray) tooLarge();
			return original.apply(this, arguments);
		};
	});
	// A plain function that shares the original prototype keeps instanceof,
	// Array.isArray and the static methods working (goja's Proxy does not
	// support instanceof).
	var guardConstructor = function (name, check) {
		var original = globalThis[name];
		if (typeof original !== "function") return;
		var guarded = function () {
			check(arguments, original);
			if (new.target === undefined) return Reflect.apply(original, this, arguments);
			return Reflect.construct(original, arguments, new.target === guarded ? original : new.target);
		};
		Object.getOwnPropertyNames(original).forEach(function (key) {
			if (key === "length" || key === "name" || key === "prototype" || key === "arguments" || key === "caller") return;
			Object.defineProperty(guarded, key, Object.getOwnPropertyDescriptor(original, key));
		});
		Object.defineProperty(guarded, "prototype", { value: original.prototype, writable: false });
		Object.defineProperty(guarded, "name", { value: name });
		Object.setPrototypeOf(guarded, Object.getPrototypeOf(original));
		Object.defineProperty(globalThis, name, { value: guarded, writable: true, configurable: true, enumerable: false });
	};
	var isArrayBuffer = function (value) { return Object.prototype.toString.call(value) === "[object ArrayBuffer]"; };
	guardConstructor("Array", function (args) {
		if (args.length === 1 && typeof args[0] === "number" && args[0] > maxArray) tooLarge();
	});
	guardConstructor("ArrayBuffer", function (args) {
		if (toNumber(args[0]) > maxBuffer) tooLarge();
	});
	["Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array", "Uint16Array", "Int32Array", "Uint32Array",
		"Float32Array", "Float64Array", "BigInt64Array", "BigUint64Array"].forEach(function (name) {
		guardConstructor(name, function (args, target) {
			var first = args[0];
			var length = typeof first === "number" ? first : (first != null && typeof first === "object" && !isArrayBuffer(first) ? toNumber(first.length) : 0);
			if (length * (target.BYTES_PER_ELEMENT || 1) > maxBuffer) tooLarge();
		});
	});
})`

var scriptGuardProgram = goja.MustCompile("script-limits.js", scriptGuardSource, true)

// installScriptGuards wraps the allocating built-ins of a fresh runtime before
// any profile code runs.
func installScriptGuards(vm *goja.Runtime) error {
	value, err := vm.RunProgram(scriptGuardProgram)
	if err != nil {
		return err
	}
	install, ok := goja.AssertFunction(value)
	if !ok {
		return errors.New("script limits did not compile to a function")
	}
	_, err = install(goja.Undefined(), vm.ToValue(scriptMaxStringLength), vm.ToValue(scriptMaxArrayLength), vm.ToValue(scriptMaxBufferBytes))
	return err
}

// watchScriptMemory interrupts vm with errScriptMemory when the Go heap grows
// by more than scriptHeapBudget until the returned stop is called. The heap is
// process-wide, so live growth elsewhere can stop a script early; that fails
// only the script, like any other script error.
func watchScriptMemory(ctx context.Context, vm *goja.Runtime) (stop func()) {
	baseline := scriptHeapBytes()
	if baseline == 0 {
		return func() {}
	}
	ctx, cancel := context.WithCancel(ctx)
	done := make(chan struct{})
	go func() {
		defer close(done)
		ticker := time.NewTicker(scriptHeapSample)
		defer ticker.Stop()
		var lastCollect time.Time
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if !scriptHeapOver(baseline) {
					continue
				}
				// The sample also counts garbage not yet collected; only live
				// growth stops the script. Collections are spaced out so a
				// busy process is not collected continuously.
				if time.Since(lastCollect) < scriptHeapCollectGap {
					continue
				}
				runtime.GC()
				lastCollect = time.Now()
				if scriptHeapOver(baseline) {
					vm.Interrupt(errScriptMemory)
					return
				}
			}
		}
	}()
	return func() {
		cancel()
		<-done
	}
}

// scriptStringTooLong reports whether value is a string longer than limit
// UTF-16 units, without flattening a lazy concatenation the way Export or
// String would.
func scriptStringTooLong(value goja.Value, limit int) bool {
	text, ok := value.(goja.String)
	return ok && text.Length() > limit
}

func scriptHeapOver(baseline uint64) bool {
	current := scriptHeapBytes()
	return current > baseline && current-baseline > scriptHeapBudget
}

func scriptHeapBytes() uint64 {
	sample := []metrics.Sample{{Name: "/memory/classes/heap/objects:bytes"}}
	metrics.Read(sample)
	if sample[0].Value.Kind() != metrics.KindUint64 {
		return 0
	}
	return sample[0].Value.Uint64()
}
