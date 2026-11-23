//go:build android && cmfa

package main

/*
#include <stdint.h>
*/
import "C"

import (
	"encoding/json"

	"github.com/metacubex/mihomo/dns"
)

//export fk_network_changed
func fk_network_changed(content *C.char) C.int32_t {
	var update struct {
		DNS []string `json:"dns"`
	}
	if err := json.Unmarshal([]byte(C.GoString(content)), &update); err != nil {
		return fail(err)
	}
	dns.UpdateSystemDNS(update.DNS)
	dns.FlushCacheWithDefaultResolver()
	return 0
}
