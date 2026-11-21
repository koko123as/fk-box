package fk

// Capture and HTTP rewriting outside the kernel (docs/kernel-isolation.md §7).
// The httpdebug engine runs in fk-core; the capture proxy (core/capture) takes
// the connections a kernel's route sends it and leaves through the kernel's
// local inbound. The MITM CA key stays in the Android Keystore: certificates
// are signed through the reverse call capture.sign, with SHA-256 digests only.

import (
	"crypto"
	"crypto/sha256"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"sync"

	"github.com/koko123as/fk-box/core/capture"
	"github.com/koko123as/fk-box/core/httpdebug"
)

const captureOutboundTag = "fkbox-capture"

var (
	captureAccess   sync.Mutex
	captureProxy    *capture.Proxy
	capturePassword string

	// The CA the app holds for profile rewriting (CaptureSetMaterial).
	captureDirectory   string
	captureCertificate string
	captureSignerID    string
)

// hostCaptureSigner signs with a key the app keeps (capture.sign).
type hostCaptureSigner struct {
	id     string
	public crypto.PublicKey
}

func (s *hostCaptureSigner) Public() crypto.PublicKey { return s.public }

func (s *hostCaptureSigner) Sign(_ io.Reader, digest []byte, opts crypto.SignerOpts) ([]byte, error) {
	if opts.HashFunc() != crypto.SHA256 || len(digest) != sha256.Size {
		return nil, errors.New("MITM signing supports SHA-256 digests only")
	}
	var signature []byte
	if err := hostCall(&signature, "capture.sign", s.id, digest); err != nil {
		return nil, err
	}
	return signature, nil
}

func captureSigner(certificate, signerID string) (crypto.Signer, error) {
	ca, err := httpdebug.ParseCA(certificate)
	if err != nil {
		return nil, err
	}
	return &hostCaptureSigner{id: signerID, public: ca.PublicKey}, nil
}

// CaptureGenerateCA creates the MITM CA for a key the app generated:
// publicKey is its PKIX DER, signerID names it for capture.sign.
func CaptureGenerateCA(publicKey []byte, signerID string) (string, error) {
	public, err := x509.ParsePKIXPublicKey(publicKey)
	if err != nil {
		return "", err
	}
	return httpdebug.GenerateCA(&hostCaptureSigner{id: signerID, public: public})
}

// CaptureSetMaterial records the CA profile rewriting uses when a service
// starts (an empty certificate: none generated yet).
func CaptureSetMaterial(directory, certificate, signerID string) {
	captureAccess.Lock()
	captureDirectory, captureCertificate, captureSignerID = directory, certificate, signerID
	captureAccess.Unlock()
}

// CaptureOpen opens the capture store, also while no service runs.
func CaptureOpen(directory string) error { return httpdebug.Open(directory) }

// CaptureValidate normalizes capture settings.
func CaptureValidate(config string) (string, error) { return httpdebug.Validate(config) }

// CaptureStart starts recording. The running service must reload afterwards
// so its kernel routes the matching connections to the capture proxy.
func CaptureStart(directory, config, certificate, signerID string) error {
	if ServiceLocalProxy() == "" {
		return os.ErrClosed
	}
	var signer crypto.Signer
	if certificate != "" {
		var err error
		if signer, err = captureSigner(certificate, signerID); err != nil {
			return err
		}
	}
	if err := httpdebug.Start(directory, config, certificate, signer); err != nil {
		return err
	}
	return ensureCaptureProxy()
}

// CaptureStop stops recording; profile rewriting stays. The service reloads
// afterwards, as after CaptureStart.
func CaptureStop() { httpdebug.Stop() }

func captureJSON(value any) (string, error) {
	data, err := json.Marshal(value)
	return string(data), err
}

// CaptureStatus is the capture state as JSON.
func CaptureStatus() (string, error) { return captureJSON(httpdebug.Status()) }

// CaptureLogs is the state and the capture log as JSON.
func CaptureLogs() (string, error) {
	return captureJSON(struct {
		State httpdebug.State        `json:"state"`
		Logs  []httpdebug.CaptureLog `json:"logs"`
	}{httpdebug.Status(), httpdebug.CaptureLogs()})
}

// CaptureAppendLog adds a line to the capture log.
func CaptureAppendLog(level, message string) { httpdebug.AppendCaptureLog(level, message) }

// CaptureClearLogs empties the capture log.
func CaptureClearLogs() { httpdebug.ClearCaptureLogs() }

// CaptureList is a page of saved transactions as JSON.
func CaptureList(limit int32, query string) (string, error) {
	store, err := httpdebug.Saved()
	if err != nil {
		return "", err
	}
	return captureJSON(store.Page(int(limit), query))
}

// CaptureHosts is the observed host names as JSON.
func CaptureHosts(query string) (string, error) {
	store, err := httpdebug.Saved()
	if err != nil {
		return "", err
	}
	return captureJSON(store.Domains(query))
}

// CaptureRead is one transaction as JSON, without its bodies (file exports carry them).
func CaptureRead(id string) (string, error) {
	store, err := httpdebug.Saved()
	if err != nil {
		return "", err
	}
	detail, err := store.Detail(id)
	if err != nil {
		return "", err
	}
	detail.RequestBody, detail.ResponseBody = nil, nil
	return captureJSON(detail)
}

// CaptureClear deletes the saved transactions.
func CaptureClear() error {
	store, err := httpdebug.Saved()
	if err != nil {
		return err
	}
	store.Clear()
	return nil
}

// CaptureExportHAR writes the transactions matching query to path as HAR.
func CaptureExportHAR(path, query string) error {
	store, err := httpdebug.Saved()
	if err != nil {
		return err
	}
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	err = store.WriteHAR(file, query)
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	return err
}

// CaptureExportCurl is one transaction as a curl command.
func CaptureExportCurl(id string) (string, error) {
	store, err := httpdebug.Saved()
	if err != nil {
		return "", err
	}
	return store.Curl(id)
}

// ---- the service side ----

func ensureCaptureProxy() error {
	captureAccess.Lock()
	defer captureAccess.Unlock()
	if captureProxy != nil {
		return nil
	}
	capturePassword = randomHex(16)
	roots, _ := x509.SystemCertPool()
	proxy, err := capture.Listen(capture.Options{
		Username: localReturnUser,
		Password: capturePassword,
		Upstream: ServiceLocalProxy,
		Packages: captureScopePackages,
		Roots:    roots,
	})
	if err != nil {
		return err
	}
	captureProxy = proxy
	return nil
}

func captureScopePackages() []string {
	engine := httpdebug.Current()
	if engine == nil {
		return nil
	}
	scope, _ := engine.Scope()
	return scope.Packages
}

// prepareServiceRewrite activates the profile's HTTP rewriting
// (experimental.http_rewrite of a sing-box profile) for the service about to
// start, or turns it off; it keeps a running capture.
func prepareServiceRewrite(core, content string) error {
	var config *httpdebug.RewriteConfig
	if core == CoreSingBox {
		if raw := profileHTTPRewrite(content); raw != nil {
			config = new(httpdebug.RewriteConfig)
			if err := json.Unmarshal(raw, config); err != nil {
				return fmt.Errorf("http_rewrite: %w", err)
			}
		}
	}
	captureAccess.Lock()
	directory, certificate, signerID := captureDirectory, captureCertificate, captureSignerID
	captureAccess.Unlock()
	var signer crypto.Signer
	if config != nil && config.Enabled && config.MITM.Enabled {
		if certificate == "" {
			return errors.New("HTTP rewrite needs a generated MITM CA")
		}
		var err error
		if signer, err = captureSigner(certificate, signerID); err != nil {
			return err
		}
	}
	if directory == "" && config != nil && config.Enabled {
		return errors.New("HTTP rewrite: the capture directory is not set")
	}
	prepared, err := httpdebug.PrepareRewrite(directory, config, certificate, signer)
	if err != nil {
		return err
	}
	httpdebug.ActivateRewrite(prepared)
	return nil
}

// captureRoute is what the kernel about to start routes to the capture
// proxy; nil when nothing is captured or rewritten.
type captureRoute struct {
	port     int
	password string
	scope    httpdebug.RouteScope
}

func currentCaptureRoute() (*captureRoute, error) {
	engine := httpdebug.Current()
	if engine == nil {
		return nil, nil
	}
	scope, ok := engine.Scope()
	if !ok {
		return nil, nil
	}
	if err := ensureCaptureProxy(); err != nil {
		return nil, err
	}
	captureAccess.Lock()
	defer captureAccess.Unlock()
	return &captureRoute{port: captureProxy.Port(), password: capturePassword, scope: scope}, nil
}

// stopServiceCapture ends rewriting and recording with the service.
func stopServiceCapture() {
	httpdebug.StopService()
	captureAccess.Lock()
	proxy := captureProxy
	captureProxy = nil
	captureAccess.Unlock()
	if proxy != nil {
		_ = proxy.Close()
	}
}
