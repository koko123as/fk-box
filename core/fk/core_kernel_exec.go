package fk

// The official kernel executables answer what libbox answered in-process
// before (docs/kernel-isolation.md section 5.2 and 5.3): config checks
// (libxray.so run -test, libmihomo.so -t), versions, and the Xray share-link
// conversion (libxrayshare.so, core/tools/xrayshare). They live in the app's
// native library directory (SetPaths).

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"sync"
	"time"
)

const (
	kernelXray      = "libxray.so"
	kernelMihomo    = "libmihomo.so"
	kernelXrayShare = "libxrayshare.so"

	kernelCheckTimeout   = 60 * time.Second
	kernelVersionTimeout = 15 * time.Second
	kernelOutputLimit    = 64 * 1024
)

// limitedBuffer keeps the first limit bytes written to it.
type limitedBuffer struct {
	bytes.Buffer
	limit int
}

func (b *limitedBuffer) Write(p []byte) (int, error) {
	if room := b.limit - b.Len(); room > 0 {
		if len(p) > room {
			b.Buffer.Write(p[:room])
		} else {
			b.Buffer.Write(p)
		}
	}
	return len(p), nil
}

// runKernel runs an executable from the native library directory and returns
// its standard output, its combined output and its error.
func runKernel(timeout time.Duration, name string, stdin []byte, env []string, args ...string) (stdout []byte, combined string, err error) {
	path, err := kernelExecutable(name)
	if err != nil {
		return nil, "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	command := exec.CommandContext(ctx, path, args...)
	command.Dir = coreTempPath()
	command.Env = append(os.Environ(), env...)
	if stdin != nil {
		command.Stdin = bytes.NewReader(stdin)
	}
	var out bytes.Buffer
	all := &limitedBuffer{limit: kernelOutputLimit}
	command.Stdout = &multiWriter{&out, all}
	command.Stderr = all
	err = command.Run()
	if ctx.Err() == context.DeadlineExceeded {
		err = fmt.Errorf("%s did not finish within %s", name, timeout)
	}
	return out.Bytes(), strings.TrimSpace(all.String()), err
}

type multiWriter struct {
	first  *bytes.Buffer
	second *limitedBuffer
}

func (w *multiWriter) Write(p []byte) (int, error) {
	w.first.Write(p)
	return w.second.Write(p)
}

// writeKernelTemp writes content to a new temporary file whose name ends in
// suffix and returns its path.
func writeKernelTemp(pattern string, content string) (string, error) {
	file, err := os.CreateTemp(coreTempPath(), pattern)
	if err != nil {
		return "", err
	}
	_, err = file.WriteString(content)
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		_ = os.Remove(file.Name())
		return "", err
	}
	return file.Name(), nil
}

// ---- Xray ----

// xrayConfigJSON holds the parts of an Xray profile fk-box reads, under the
// field names of Xray's infra/conf.Config.
type xrayConfigJSON struct {
	Outbounds    []json.RawMessage `json:"outbounds"`
	RouterConfig *xrayRouterJSON   `json:"routing"`
}

type xrayRouterJSON struct {
	Balancers []*xrayBalancerJSON `json:"balancers"`
	RuleList  []json.RawMessage   `json:"rules"`
}

type xrayBalancerJSON struct {
	Tag string `json:"tag"`
}

func decodeXrayConfig(content string) (*xrayConfigJSON, error) {
	var config xrayConfigJSON
	decoder := json.NewDecoder(strings.NewReader(normalizeCoreContent(content)))
	if err := decoder.Decode(&config); err != nil {
		return nil, fmt.Errorf("xray: %w", err)
	}
	return &config, nil
}

// checkXrayRuleTags repeats the checks router.ReloadRules makes only when the
// core starts (app/router/router.go): Xray refuses to start with a repeated
// ruleTag or balancer tag.
func checkXrayRuleTags(config *xrayRouterJSON) error {
	balancers := map[string]bool{}
	for _, balancer := range config.Balancers {
		if balancer == nil {
			continue
		}
		if balancers[balancer.Tag] {
			return fmt.Errorf("duplicate balancer tag %s", balancer.Tag)
		}
		balancers[balancer.Tag] = true
	}
	ruleTags := map[string]bool{}
	for index, raw := range config.RuleList {
		var rule struct {
			RuleTag string `json:"ruleTag"`
		}
		if err := json.Unmarshal(raw, &rule); err != nil {
			return fmt.Errorf("rule %d: %w", index, err)
		}
		if rule.RuleTag == "" {
			continue
		}
		if ruleTags[rule.RuleTag] {
			return fmt.Errorf("duplicate ruleTag %s", rule.RuleTag)
		}
		ruleTags[rule.RuleTag] = true
	}
	return nil
}

// checkXrayConfig runs `libxray.so run -test -c <file>` with the shared geo
// directory as Xray's asset location; a failure is Xray's own output.
func checkXrayConfig(content string) error {
	config, err := decodeXrayConfig(content)
	if err != nil {
		return err
	}
	if len(config.Outbounds) == 0 {
		return fmt.Errorf("xray: no outbound")
	}
	if config.RouterConfig != nil {
		if err := checkXrayRuleTags(config.RouterConfig); err != nil {
			return fmt.Errorf("xray routing: %w", err)
		}
	}
	path, err := writeKernelTemp("xray-check-*.json", normalizeCoreContent(content))
	if err != nil {
		return err
	}
	defer os.Remove(path)
	_, output, err := runKernel(kernelCheckTimeout, kernelXray, nil,
		[]string{"XRAY_LOCATION_ASSET=" + GeoDataDir()}, "run", "-test", "-c", path)
	if err != nil {
		return kernelError("xray", output, err)
	}
	return nil
}

// ---- mihomo ----

// checkMihomoConfig runs `libmihomo.so -t -d <geo dir> -f <file>`; a failure
// is mihomo's error lines.
func checkMihomoConfig(content string) error {
	path, err := writeKernelTemp("mihomo-check-*.yaml", normalizeCoreContent(content))
	if err != nil {
		return err
	}
	defer os.Remove(path)
	_, output, err := runKernel(kernelCheckTimeout, kernelMihomo, nil, nil, "-t", "-d", GeoDataDir(), "-f", path)
	if err != nil {
		return kernelError("mihomo", mihomoErrorText(output), err)
	}
	return nil
}

var (
	ansiEscape     = regexp.MustCompile(`\x1b\[[0-9;?]*[ -/]*[@-~]`)
	logrusMessage  = regexp.MustCompile(`level=(error|fatal|panic) msg="((?:[^"\\]|\\.)*)"`)
	mihomoTestLine = regexp.MustCompile(`(?m)^configuration file .* test (failed|is successful)\s*$`)
)

// mihomoErrorText keeps the messages of mihomo's error lines (logrus text:
// time="..." level=error msg="..."), or the output without colors and the
// closing "test failed" line when it has none.
func mihomoErrorText(output string) string {
	output = ansiEscape.ReplaceAllString(output, "")
	var messages []string
	for _, match := range logrusMessage.FindAllStringSubmatch(output, -1) {
		message := match[2]
		var unquoted string
		if json.Unmarshal([]byte(`"`+message+`"`), &unquoted) == nil {
			message = unquoted
		}
		messages = append(messages, message)
	}
	if len(messages) > 0 {
		return strings.Join(messages, "\n")
	}
	return strings.TrimSpace(mihomoTestLine.ReplaceAllString(output, ""))
}

func kernelError(kernel string, output string, err error) error {
	if output == "" {
		return fmt.Errorf("%s: %w", kernel, err)
	}
	return fmt.Errorf("%s: %s", kernel, output)
}

// ---- share links -> Xray JSON ----

// convertShareLinksToXrayJSON is libXray's share.ConvertShareLinksToXrayJson,
// run as libxrayshare.so: the links on stdin, the JSON on stdout.
func convertShareLinksToXrayJSON(links string) ([]byte, error) {
	stdout, output, err := runKernel(kernelCheckTimeout, kernelXrayShare, []byte(links), nil)
	if err != nil {
		if output == "" {
			return nil, err
		}
		return nil, errors.New(output)
	}
	return stdout, nil
}

// ---- versions ----

var (
	xrayVersionOnce   sync.Once
	xrayVersionValue  string
	mihomoVersionOnce sync.Once
	mihomoVersionText string
)

// XrayVersion is the version the shipped Xray reports ("Xray 26.11.9 (...)"
// -> "26.11.9"), read once; "unknown" when it cannot be run.
func XrayVersion() string {
	xrayVersionOnce.Do(func() {
		stdout, _, err := runKernel(kernelVersionTimeout, kernelXray, nil, nil, "version")
		xrayVersionValue = parseXrayVersion(string(stdout), err)
	})
	return xrayVersionValue
}

func parseXrayVersion(output string, err error) string {
	if err == nil {
		for _, line := range strings.Split(output, "\n") {
			fields := strings.Fields(line)
			if len(fields) >= 2 && fields[0] == "Xray" {
				return strings.TrimPrefix(fields[1], "v")
			}
		}
	}
	return "unknown"
}

// MihomoVersion is the version the shipped mihomo reports ("Mihomo Meta
// v1.19.34 linux arm64 ..." -> "1.19.34", without the "v" as legacy
// reported it), read once; "unknown" when it cannot be run or was built
// without a version.
func MihomoVersion() string {
	mihomoVersionOnce.Do(func() {
		stdout, _, err := runKernel(kernelVersionTimeout, kernelMihomo, nil, nil, "-v")
		mihomoVersionText = parseMihomoVersion(string(stdout), err)
	})
	return mihomoVersionText
}

func parseMihomoVersion(output string, err error) string {
	if err == nil {
		for _, line := range strings.Split(output, "\n") {
			fields := strings.Fields(line)
			if len(fields) >= 3 && fields[0] == "Mihomo" && fields[1] == "Meta" {
				version := fields[2]
				if version == "unknown" || strings.HasPrefix(version, "1.10") {
					break
				}
				return strings.TrimPrefix(version, "v")
			}
		}
	}
	return "unknown"
}
