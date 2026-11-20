// Command xrayshare converts Xray share links (vless://, vmess://, ss://,
// trojan://, socks://, hysteria2://, a base64 subscription) to an Xray JSON
// configuration with the official libXray converter, unmodified
// (share.ConvertShareLinksToXrayJson, XTLS/libXray share/convert_share.go).
// fk-core runs it on demand as libxrayshare.so: the links on stdin, the JSON
// on stdout, an error on stderr with exit status 1.
//
// It is its own module so fk-core (../..) never depends on xray-core
// (docs/kernel-isolation.md, decision D2).
package main

import (
	"fmt"
	"io"
	"os"

	"github.com/xtls/libxray/share"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "-version" {
		fmt.Println("xrayshare (libXray share converter)")
		return
	}
	input, err := io.ReadAll(io.LimitReader(os.Stdin, 32<<20))
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	output, err := share.ConvertShareLinksToXrayJson(string(input), "")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if _, err = os.Stdout.Write(output); err != nil {
		os.Exit(1)
	}
}
