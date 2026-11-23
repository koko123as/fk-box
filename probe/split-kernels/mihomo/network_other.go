//go:build !(android && cmfa)

package main

/*
#include <stdint.h>
*/
import "C"

// fk_network_changed: off Android mihomo reads the system resolver itself.
//
//export fk_network_changed
func fk_network_changed(*C.char) C.int32_t { return 0 }
