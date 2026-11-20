// Command fk-bind builds fk-box's Android core: libbox.aar, the gomobile
// bind of experimental/libbox and fk-box's entry package experimental/fkbox
// (Java io.nekohasekai.libbox.Libbox and io.nekohasekai.fkbox.Fkbox, native
// library libbox.so). It is copied to cmd/fk-bind of the sing-box checkout by
// tools/patch_entry.py and replaces the Android branch of sing-box's
// cmd/internal/build_libbox, which also builds an API 21 legacy AAR that
// fk-box does not ship.
//
// The flags, Android API level and build tags are build_libbox's (sing-box
// v1.15.0-alpha.13) plus mihomo's cmfa tag; tools/bump_core.py reports
// changes of cmd/internal/build_libbox/main.go on every sing-box upgrade so
// the two stay in step.
//
//	go run ./cmd/fk-bind -platform android/arm64 [-debug]
package main

import (
	"flag"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	_ "github.com/sagernet/gomobile"
	"github.com/sagernet/sing-box/cmd/internal/build_shared"
	"github.com/sagernet/sing-box/log"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/shell"
)

// androidAPI is the minimum Android API level of the bound library.
const androidAPI = "24"

// buildTags are build_libbox's Android tags, with mihomo embedded the way
// ClashMetaForAndroid embeds it (cmfa: the app supplies system DNS).
var buildTags = []string{
	"with_quic", "with_wireguard", "with_utls", "with_naive_outbound", "with_clash_api",
	"with_usbip", "with_openvpn", "with_openconnect", "badlinkname", "tfogo_checklinkname0",
	"with_tailscale", "ts_omit_logtail", "ts_omit_ssh", "ts_omit_drive", "ts_omit_taildrop",
	"ts_omit_webclient", "ts_omit_doctor", "ts_omit_capture", "ts_omit_kube", "ts_omit_aws",
	"ts_omit_synology", "ts_omit_bird",
	"cmfa",
}

func main() {
	var (
		debugEnabled bool
		platform     string
		output       string
	)
	flag.BoolVar(&debugEnabled, "debug", false, "debug build (debug tag and linker flags)")
	flag.StringVar(&platform, "platform", "android/arm64", "gomobile Android target")
	flag.StringVar(&output, "o", "libbox.aar", "output AAR")
	flag.Parse()
	if !strings.HasPrefix(platform, "android") {
		log.Fatal("fk-bind builds Android targets only: ", platform)
	}

	build_shared.FindMobile()
	build_shared.FindSDK()
	checkJavaVersion()

	currentTag, err := build_shared.ReadTag()
	if err != nil {
		currentTag = "unknown"
	}
	tags := append([]string{}, buildTags...)
	if debugEnabled {
		tags = append(tags, "debug")
	}
	args := []string{
		"bind",
		"-v",
		"-o", output,
		"-target", platform,
		"-androidapi", androidAPI,
		"-javapkg=io.nekohasekai",
		"-libname=box",
	}
	if !debugEnabled {
		args = append(args, "-trimpath", "-buildvcs=false")
	}
	args = append(args,
		"-ldflags", build_shared.LinkerFlags(currentTag, debugEnabled),
		"-tags", strings.Join(tags, ","),
		"./experimental/libbox", "./experimental/fkbox",
	)
	command := exec.Command(filepath.Join(build_shared.GoBinPath, "gomobile"), args...)
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	if err = command.Run(); err != nil {
		log.Fatal(err)
	}
}

// checkJavaVersion requires OpenJDK 17, as build_libbox does.
func checkJavaVersion() {
	javaPath := "java"
	if javaHome := os.Getenv("JAVA_HOME"); javaHome != "" {
		javaPath = filepath.Join(javaHome, "bin", "java")
	}
	javaVersion, err := shell.Exec(javaPath, "--version").ReadOutput()
	if err != nil {
		log.Fatal(E.Cause(err, "check java version"))
	}
	if !strings.Contains(javaVersion, "openjdk 17") {
		log.Fatal("java version should be openjdk 17")
	}
}
