package libbox

import (
	"bufio"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"hash"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime/debug"
	"strings"
	"time"

	mihomoGeodata "github.com/metacubex/mihomo/component/geodata"
	mihomoRouter "github.com/metacubex/mihomo/component/geodata/router"
	mihomoMMDB "github.com/metacubex/mihomo/component/mmdb"
	"github.com/oschwald/maxminddb-golang"
	"google.golang.org/protobuf/encoding/protowire"
	"google.golang.org/protobuf/proto"
)

// Geo data (GeoIP, GeoSite, MMDB, ASN) shared by Xray and mihomo, modelled on
// FlClash: the app ships a copy in its assets, extracts it on first use and
// can replace each file from its own URL. All four live in mihomo's home
// directory, which mihomo searches by case-insensitive name; Xray is pointed
// at the same directory through xray.location.asset and reads the lower-case
// geoip.dat / geosite.dat written here.

const (
	GeoResourceGeoIP   = "geoip"
	GeoResourceGeoSite = "geosite"
	GeoResourceMMDB    = "mmdb"
	GeoResourceASN     = "asn"

	geoDownloadLimit   = 96 * 1024 * 1024
	geoDownloadTimeout = 5 * time.Minute
)

type geoResourceSpec struct {
	file       string
	defaultURL string
	// verify checks a downloaded file on disk without loading it whole.
	verify func(path string) error
}

// Default URLs are FlClash's defaultGeoXUrl (MetaCubeX/meta-rules-dat).
var geoResourceSpecs = map[string]geoResourceSpec{
	GeoResourceGeoIP: {
		file:       "geoip.dat",
		defaultURL: "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.dat",
		verify:     verifyGeoIPFile,
	},
	GeoResourceGeoSite: {
		file:       "geosite.dat",
		defaultURL: "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat",
		verify:     verifyGeoSiteFile,
	},
	GeoResourceMMDB: {
		file:       "geoip.metadb",
		defaultURL: "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb",
		verify:     verifyMMDBFile,
	},
	GeoResourceASN: {
		file:       "ASN.mmdb",
		defaultURL: "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb",
		verify:     verifyMMDBFile,
	},
}

// GeoDataDir is the shared geo directory under the working directory.
func GeoDataDir() string {
	return coreAssetDir(sWorkingPath, CoreMihomo)
}

// GeoResourceFileName returns the file a resource is stored as, or "".
func GeoResourceFileName(name string) string {
	return geoResourceSpecs[name].file
}

// GeoResourceDefaultURL returns the URL a resource updates from by default.
func GeoResourceDefaultURL(name string) string {
	return geoResourceSpecs[name].defaultURL
}

// GeoUpdateResult describes the file after an update. Skipped means the
// installed file was already current (same content, or the server answered
// 304 Not Modified) and was left untouched. Etag and LastModified are the
// validators the server sent, for the next conditional request.
type GeoUpdateResult struct {
	Size         int64
	Sha256       string
	Skipped      bool
	Etag         string
	LastModified string
}

// GeoUpdate is one cancellable resource update. Cancel aborts a download in
// progress (the installed file is kept); it is safe to call at any time.
type GeoUpdate struct {
	ctx    context.Context
	cancel context.CancelFunc
}

func NewGeoUpdate() *GeoUpdate {
	ctx, cancel := context.WithTimeout(context.Background(), geoDownloadTimeout)
	return &GeoUpdate{ctx: ctx, cancel: cancel}
}

func (u *GeoUpdate) Cancel() { u.cancel() }

// Fetch downloads one resource into dir, checks that it is that kind of
// database and replaces the installed file atomically. Any failure leaves the
// previous file as it was.
//
// installedSha256, etag and lastModified describe what the app last
// installed. When installedSha256 still matches the file on disk, the request
// is conditional (If-None-Match / If-Modified-Since), so an unchanged
// resource costs no download. The body is streamed to a temporary file and
// hashed on the way; nothing holds the whole file in memory.
//
// Callers serialize updates (GeoResources' mutex in the app); Fetch does not
// lock by itself. One GeoUpdate is good for one Fetch.
func (u *GeoUpdate) Fetch(dir string, name string, link string, installedSha256 string, etag string, lastModified string) (*GeoUpdateResult, error) {
	defer u.cancel()
	spec, ok := geoResourceSpecs[name]
	if !ok {
		return nil, fmt.Errorf("unknown geo resource: %s", name)
	}
	if strings.TrimSpace(link) == "" {
		link = spec.defaultURL
	}
	// Download buffers, TLS state and parser garbage go back to the OS right
	// away instead of lingering as idle heap beside the running core.
	defer debug.FreeOSMemory()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	target := filepath.Join(dir, spec.file)
	var conditional *geoValidators
	installedSha256 = strings.ToLower(strings.TrimSpace(installedSha256))
	if installedSha256 != "" && (etag != "" || lastModified != "") {
		if sum, size, err := hashGeoFile(target); err == nil && sum == installedSha256 {
			conditional = &geoValidators{etag: etag, lastModified: lastModified, size: size}
		}
	}
	download, err := downloadGeoResource(u.ctx, link, dir, spec.file, conditional)
	if err != nil {
		return nil, fmt.Errorf("download %s: %w", spec.file, err)
	}
	if download.notModified {
		return &GeoUpdateResult{
			Size: conditional.size, Sha256: installedSha256, Skipped: true,
			Etag: firstNonEmpty(download.etag, etag), LastModified: firstNonEmpty(download.lastModified, lastModified),
		}, nil
	}
	defer os.Remove(download.path)
	return installGeoResource(dir, name, download)
}

type geoValidators struct {
	etag         string
	lastModified string
	size         int64
}

type geoDownload struct {
	path         string
	size         int64
	sha256       string
	etag         string
	lastModified string
	notModified  bool
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}

func installGeoResource(dir string, name string, download *geoDownload) (*GeoUpdateResult, error) {
	spec := geoResourceSpecs[name]
	if download.size == 0 {
		return nil, fmt.Errorf("%s: empty file", spec.file)
	}
	if err := spec.verify(download.path); err != nil {
		return nil, fmt.Errorf("invalid %s: %w", spec.file, err)
	}
	result := &GeoUpdateResult{Size: download.size, Sha256: download.sha256, Etag: download.etag, LastModified: download.lastModified}
	target := filepath.Join(dir, spec.file)
	// Same size first, so a changed file is never read back.
	if info, err := os.Stat(target); err == nil && info.Size() == download.size {
		if current, _, err := hashGeoFile(target); err == nil && current == download.sha256 {
			result.Skipped = true
			return result, nil
		}
	}
	if err := os.Rename(download.path, target); err != nil {
		return nil, err
	}
	removeGeoNameVariants(dir, spec.file)
	reloadGeoResource(name)
	return result, nil
}

// reloadGeoResource makes mihomo pick up a replaced file. Rules already built
// keep their matchers until the core restarts; the MMDB/ASN readers are
// sync.Once singletons that would otherwise keep the old (unlinked) file
// mapped until the process exits.
func reloadGeoResource(name string) {
	switch name {
	case GeoResourceGeoIP:
		mihomoGeodata.ClearGeoIPCache()
	case GeoResourceGeoSite:
		mihomoGeodata.ClearGeoSiteCache()
	case GeoResourceMMDB:
		mihomoMMDB.ReloadIP()
	case GeoResourceASN:
		mihomoMMDB.ReloadASN()
	}
}

func hashGeoFile(path string) (string, int64, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", 0, err
	}
	defer file.Close()
	digest := sha256.New()
	size, err := io.CopyBuffer(digest, file, make([]byte, 64*1024))
	if err != nil {
		return "", 0, err
	}
	return hex.EncodeToString(digest.Sum(nil)), size, nil
}

func downloadGeoResource(ctx context.Context, link string, dir string, file string, conditional *geoValidators) (*geoDownload, error) {
	target, err := url.Parse(link)
	if err != nil || target == nil || target.Hostname() == "" || (target.Scheme != "http" && target.Scheme != "https") {
		return nil, fmt.Errorf("invalid HTTP or HTTPS address")
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, target.String(), nil)
	if err != nil {
		return nil, fmt.Errorf("invalid HTTP request")
	}
	if target.User != nil {
		password, _ := target.User.Password()
		request.SetBasicAuth(target.User.Username(), password)
	}
	if conditional != nil {
		if conditional.etag != "" {
			request.Header.Set("If-None-Match", conditional.etag)
		}
		if conditional.lastModified != "" {
			request.Header.Set("If-Modified-Since", conditional.lastModified)
		}
	}
	client := NewHTTPClient().(*httpClient)
	defer client.Close()
	client.ModernTLS()
	client.client.Timeout = geoDownloadTimeout
	response, err := client.client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	result := &geoDownload{etag: response.Header.Get("ETag"), lastModified: response.Header.Get("Last-Modified")}
	if response.StatusCode == http.StatusNotModified && conditional != nil {
		result.notModified = true
		return result, nil
	}
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %s", response.Status)
	}
	if response.ContentLength > geoDownloadLimit {
		return nil, fmt.Errorf("file exceeds size limit")
	}
	temp, err := os.CreateTemp(dir, file+".tmp-*")
	if err != nil {
		return nil, err
	}
	var digest hash.Hash = sha256.New()
	size, err := io.CopyBuffer(io.MultiWriter(temp, digest), io.LimitReader(response.Body, geoDownloadLimit+1), make([]byte, 64*1024))
	if err == nil && size > geoDownloadLimit {
		err = fmt.Errorf("file exceeds size limit")
	}
	if err == nil && response.ContentLength >= 0 && size != response.ContentLength {
		err = io.ErrUnexpectedEOF
	}
	if err == nil {
		err = temp.Sync()
	}
	if closeErr := temp.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		_ = os.Remove(temp.Name())
		return nil, err
	}
	result.path = temp.Name()
	result.size = size
	result.sha256 = hex.EncodeToString(digest.Sum(nil))
	return result, nil
}

// mihomo may have downloaded GeoIP.dat / GeoSite.dat under its own spelling;
// on a case-sensitive file system that copy would shadow ours for mihomo.
func removeGeoNameVariants(dir string, file string) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	target, err := os.Stat(filepath.Join(dir, file))
	if err != nil {
		return
	}
	for _, entry := range entries {
		if entry.IsDir() || entry.Name() == file || !strings.EqualFold(entry.Name(), file) {
			continue
		}
		path := filepath.Join(dir, entry.Name())
		if info, err := os.Stat(path); err == nil && !os.SameFile(info, target) {
			_ = os.Remove(path)
		}
	}
}

// geoEntryLimit bounds the one list entry that is decoded to check a .dat
// file; the largest entries of the default lists are a few MiB.
const geoEntryLimit = 32 * 1024 * 1024

var errGeoTruncated = errors.New("truncated protobuf")

// scanGeoList walks a GeoIPList / GeoSiteList file (a protobuf message whose
// field 1 is the repeated entry) without decoding it whole. Every top-level
// field is framed and skipped, so a truncated or non-protobuf file fails; the
// entries are decoded one at a time only until accept reports a usable one.
// Memory stays at one entry instead of the millions of objects a full
// proto.Unmarshal builds.
func scanGeoList(path string, accept func([]byte) (bool, error)) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return err
	}
	reader := bufio.NewReaderSize(file, 64*1024)
	remaining := info.Size()
	readVarint := func() (uint64, error) { return binary.ReadUvarint(reader) }
	found := false
	var offset int64
	for {
		start := offset
		tag, err := readVarint()
		if err == io.EOF {
			break
		}
		if err != nil {
			return errGeoTruncated
		}
		offset += int64(protowire.SizeVarint(tag))
		number, wireType := protowire.DecodeTag(tag)
		if !number.IsValid() {
			return fmt.Errorf("invalid protobuf field at %d", start)
		}
		var skip int64
		switch wireType {
		case protowire.VarintType:
			value, err := readVarint()
			if err != nil {
				return errGeoTruncated
			}
			offset += int64(protowire.SizeVarint(value))
			continue
		case protowire.Fixed32Type:
			skip = 4
		case protowire.Fixed64Type:
			skip = 8
		case protowire.BytesType:
			length, err := readVarint()
			if err != nil {
				return errGeoTruncated
			}
			offset += int64(protowire.SizeVarint(length))
			if length > uint64(remaining-offset) {
				return errGeoTruncated
			}
			if number == 1 && !found {
				if length > geoEntryLimit {
					return fmt.Errorf("entry of %d bytes exceeds limit", length)
				}
				entry := make([]byte, length)
				if _, err := io.ReadFull(reader, entry); err != nil {
					return errGeoTruncated
				}
				offset += int64(length)
				ok, err := accept(entry)
				if err != nil {
					return err
				}
				found = ok
				continue
			}
			skip = int64(length)
		default:
			return fmt.Errorf("invalid protobuf wire type %d at %d", wireType, start)
		}
		if skip > remaining-offset {
			return errGeoTruncated
		}
		if _, err := reader.Discard(int(skip)); err != nil {
			return errGeoTruncated
		}
		offset += skip
	}
	if !found {
		return errors.New("no usable entries")
	}
	return nil
}

func verifyGeoIPFile(path string) error {
	err := scanGeoList(path, func(data []byte) (bool, error) {
		var entry mihomoRouter.GeoIP
		if err := proto.Unmarshal(data, &entry); err != nil {
			return false, err
		}
		if entry.CountryCode == "" {
			return false, nil
		}
		for _, cidr := range entry.Cidr {
			if len(cidr.Ip) == 4 || len(cidr.Ip) == 16 {
				return true, nil
			}
		}
		return false, nil
	})
	if err != nil {
		return fmt.Errorf("GeoIP: %w", err)
	}
	return nil
}

func verifyGeoSiteFile(path string) error {
	err := scanGeoList(path, func(data []byte) (bool, error) {
		var entry mihomoRouter.GeoSite
		if err := proto.Unmarshal(data, &entry); err != nil {
			return false, err
		}
		if entry.CountryCode == "" {
			return false, nil
		}
		for _, domain := range entry.Domain {
			if domain.Value != "" {
				return true, nil
			}
		}
		return false, nil
	})
	if err != nil {
		return fmt.Errorf("GeoSite: %w", err)
	}
	return nil
}

// verifyMMDBFile opens the file the way mihomo does (memory-mapped), so the
// database is not copied into the Go heap.
func verifyMMDBFile(path string) error {
	reader, err := maxminddb.Open(path)
	if err != nil {
		return err
	}
	return reader.Close()
}
