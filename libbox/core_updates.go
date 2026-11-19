package libbox

// The About page's update check (was the app's BoxUpdates.kt), on fk-box's
// own release metadata, prereleases included, rather than SFA's updater.
//
// Two sources are read and the higher version code wins:
//   - the latest release's metadata asset through
//     github.com/…/releases/latest/download, a plain redirect to the asset
//     origin that is not subject to the anonymous REST API limit (60 requests
//     an hour per IP, so a shared proxy exit answers 403 quickly);
//   - the REST release list, which also sees prereleases and release notes.
//
// A check fails only when both sources fail; a dropped connection is retried
// once. Release assets are named fk-box-*; releases published before the
// rename use FK-box-*. Both prefixes are accepted, the current one first, and
// the APK offered is the one the release actually lists. Downloading and
// installing stay with the user (the browser): nothing here fetches an APK.
//
// The JSON is read with org.json's rules (core_jvm.go) so a field of an
// unexpected type means what it meant in the app.

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
)

const (
	uiUpdateRepo              = "koko123as/fk-box"
	uiUpdateAssetPrefix       = "fk-box"
	uiUpdateLegacyAssetPrefix = "FK-box"
)

var uiUpdateAssetPrefixes = []string{uiUpdateAssetPrefix, uiUpdateLegacyAssetPrefix}

// Sources; tests point them at a local server.
var (
	uiUpdateReleasesPage = "https://github.com/" + uiUpdateRepo + "/releases"
	uiUpdateReleasesAPI  = "https://api.github.com/repos/" + uiUpdateRepo + "/releases?per_page=20"
)

var (
	uiAppInfoAccess sync.RWMutex
	uiAppInfo       *uiInstalledApp
)

type uiInstalledApp struct {
	applicationID string
	versionCode   int64
	abis          []string
}

// SetUIAppInfo tells Go the installed app's identity, version code and the
// device's supported ABIs, preferred first, separated by newlines (on
// Android: BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE and
// Build.SUPPORTED_ABIS).
func SetUIAppInfo(applicationID string, versionCode int64, abis string) {
	info := &uiInstalledApp{applicationID: applicationID, versionCode: versionCode}
	for _, abi := range strings.Split(abis, "\n") {
		if abi != "" {
			info.abis = append(info.abis, abi)
		}
	}
	uiAppInfoAccess.Lock()
	uiAppInfo = info
	uiAppInfoAccess.Unlock()
}

func uiInstalledAppInfo() (*uiInstalledApp, error) {
	uiAppInfoAccess.RLock()
	info := uiAppInfo
	uiAppInfoAccess.RUnlock()
	if info == nil {
		return nil, errors.New("app information is not available")
	}
	return info, nil
}

// uiUpdateCandidate is one release that could be offered.
type uiUpdateCandidate struct {
	version string
	code    int64
	url     string
	notes   string
	// assetNames is the release's asset list; nil for the latest/download
	// source, which lists none.
	assetNames map[string]struct{}
	// assetPrefix is the prefix of the metadata this candidate was read from.
	assetPrefix string
}

// uiUpdateStatusError is an HTTP answer other than 200.
type uiUpdateStatusError struct {
	status  int32
	message string
}

func (e *uiUpdateStatusError) Error() string { return e.message }

func uiUpdateStatusMessage(link string, status int32) string {
	if status == 403 && strings.Contains(link, "api.github.com") {
		return "Release request failed: GitHub API rate limit (HTTP 403)"
	}
	return fmt.Sprintf("Release request failed: HTTP %d", status)
}

// uiUpdateGet answers a 200 body. A transport error (EOF, reset, timeout)
// and a server error are tried once more; another status is final.
func uiUpdateGet(link string) ([]byte, error) {
	var failure error
	for attempt := 0; attempt < 2; attempt++ {
		response, err := UIHTTPGet(link)
		if err != nil {
			failure = err
			continue
		}
		if response.Status == 200 {
			return response.Content, nil
		}
		failure = &uiUpdateStatusError{status: response.Status, message: uiUpdateStatusMessage(link, response.Status)}
		// 403/404 would answer the same again; only a server-side error is repeated.
		if response.Status < 500 {
			break
		}
	}
	if failure == nil {
		failure = errors.New("Release request failed")
	}
	return nil, failure
}

// uiUpdateCandidateFrom is a metadata asset's candidate, or nil when it is
// for another app or malformed.
func uiUpdateCandidateFrom(metadata map[string]any, applicationID string, url *string, notes string, assetNames map[string]struct{}, assetPrefix string) *uiUpdateCandidate {
	return uiUpdateCandidateOf(orgJSONOptString(metadata, "application_id"), orgJSONOptLong(metadata, "version_code", -1),
		orgJSONOptString(metadata, "version_name"), applicationID, url, notes, assetNames, assetPrefix)
}

func uiUpdateCandidateOf(metadataID string, code int64, version string, applicationID string, url *string, notes string, assetNames map[string]struct{}, assetPrefix string) *uiUpdateCandidate {
	if metadataID != applicationID || code <= 0 || kotlinIsBlank(version) {
		return nil
	}
	link := uiUpdateReleasesPage + "/tag/" + version
	if url != nil {
		link = *url
	}
	return &uiUpdateCandidate{version: version, code: code, url: link, notes: notes, assetNames: assetNames, assetPrefix: assetPrefix}
}

// uiUpdateNewest is the newest candidate; on a tie the REST one (with notes
// and the asset list) wins, else the first.
func uiUpdateNewest(candidates []*uiUpdateCandidate) *uiUpdateCandidate {
	var best *uiUpdateCandidate
	for _, candidate := range candidates {
		if best == nil || best.code < candidate.code ||
			best.code == candidate.code && best.assetNames == nil && candidate.assetNames != nil {
			best = candidate
		}
	}
	return best
}

func uiUpdateMetadataName(prefix string) string { return prefix + "-version-metadata.json" }

func uiUpdateAPKName(version, abi, prefix string) string {
	return prefix + "-" + version + "-" + abi + ".apk"
}

// uiUpdateAPKAsset is the release APK for abi: with an asset list, the
// listed one under either prefix (current first); without one (the
// latest/download source), the ARM64 APK under the metadata's prefix.
func uiUpdateAPKAsset(candidate *uiUpdateCandidate, abi string) (string, bool) {
	if candidate.assetNames == nil {
		if abi == "arm64-v8a" {
			return uiUpdateAPKName(candidate.version, abi, candidate.assetPrefix), true
		}
		return "", false
	}
	for _, prefix := range uiUpdateAssetPrefixes {
		name := uiUpdateAPKName(candidate.version, abi, prefix)
		if _, ok := candidate.assetNames[name]; ok {
			return name, true
		}
	}
	return "", false
}

// uiUpdateMetadataAsset is the metadata asset among a release's asset names,
// current prefix first, with its prefix.
func uiUpdateMetadataAsset(names []string) (name string, prefix string, ok bool) {
	for _, prefix := range uiUpdateAssetPrefixes {
		name := uiUpdateMetadataName(prefix)
		for _, candidate := range names {
			if candidate == name {
				return name, prefix, true
			}
		}
	}
	return "", "", false
}

// uiUpdateJSONObject reads a body as JSONObject(text) does.
func uiUpdateJSONObject(body []byte) (map[string]any, error) {
	value, err := decodeJVMJSON(body)
	if err != nil {
		return nil, err
	}
	object, ok := value.(map[string]any)
	if !ok {
		return nil, orgJSONTypeMismatch(value, "JSONObject")
	}
	return object, nil
}

// orgJSONTypeMismatch is org.json's "Value … of type … cannot be converted" error.
func orgJSONTypeMismatch(value any, want string) error {
	kind := "java.lang.String"
	switch v := value.(type) {
	case nil:
		kind = "org.json.JSONObject$1"
	case bool:
		kind = "java.lang.Boolean"
	case jvmDouble:
		kind = "java.lang.Double"
	case json.Number:
		number := newJVMNumber(v)
		switch {
		case !number.isLong:
			kind = "java.lang.Double"
		case number.long >= -1<<31 && number.long < 1<<31:
			kind = "java.lang.Integer"
		default:
			kind = "java.lang.Long"
		}
	case []any:
		kind = "org.json.JSONArray"
	case map[string]any:
		kind = "org.json.JSONObject"
	}
	return fmt.Errorf("Value %s of type %s cannot be converted to %s", orgJSONString(value), kind, want)
}

func uiUpdateFromLatest(applicationID string) (*uiUpdateCandidate, error) {
	var notFound error
	for _, prefix := range uiUpdateAssetPrefixes {
		body, err := uiUpdateGet(uiUpdateReleasesPage + "/latest/download/" + uiUpdateMetadataName(prefix))
		if err != nil {
			// Only a missing asset tries the other name; other failures are reported as they are.
			var status *uiUpdateStatusError
			if !errors.As(err, &status) || status.status != 404 {
				return nil, err
			}
			if notFound == nil {
				notFound = err
			}
			continue
		}
		metadata, err := uiUpdateJSONObject(body)
		if err != nil {
			return nil, err
		}
		return uiUpdateCandidateFrom(metadata, applicationID, nil, "", nil, prefix), nil
	}
	if notFound == nil {
		notFound = errors.New("Release request failed")
	}
	return nil, notFound
}

func uiUpdateFromAPI(applicationID string) (*uiUpdateCandidate, error) {
	body, err := uiUpdateGet(uiUpdateReleasesAPI)
	if err != nil {
		return nil, err
	}
	value, err := decodeJVMJSON(body)
	if err != nil {
		return nil, err
	}
	releases, ok := value.([]any)
	if !ok {
		return nil, orgJSONTypeMismatch(value, "JSONArray")
	}
	var found []*uiUpdateCandidate
	for _, item := range releases {
		release, ok := item.(map[string]any)
		if !ok {
			return nil, orgJSONTypeMismatch(item, "JSONObject")
		}
		if orgJSONOptBoolean(release, "draft") {
			continue
		}
		assets, ok := release["assets"].([]any)
		if !ok {
			continue
		}
		assetList := make([]map[string]any, 0, len(assets))
		names := make([]string, 0, len(assets))
		for _, asset := range assets {
			entry, ok := asset.(map[string]any)
			if !ok {
				return nil, orgJSONTypeMismatch(asset, "JSONObject")
			}
			assetList = append(assetList, entry)
			names = append(names, orgJSONOptString(entry, "name"))
		}
		metadataFile, prefix, ok := uiUpdateMetadataAsset(names)
		if !ok {
			continue
		}
		var metadataEntry map[string]any
		for index, name := range names {
			if name == metadataFile {
				metadataEntry = assetList[index]
				break
			}
		}
		downloadURL, present := metadataEntry["browser_download_url"]
		if !present {
			continue
		}
		metadataBody, err := uiUpdateGet(orgJSONString(downloadURL))
		if err != nil {
			continue
		}
		metadata, err := uiUpdateJSONObject(metadataBody)
		if err != nil {
			continue
		}
		htmlURL, present := release["html_url"]
		if !present {
			return nil, errors.New("No value for html_url")
		}
		link := orgJSONString(htmlURL)
		notes := ""
		if body, present := release["body"]; present && body != nil {
			notes = orgJSONString(body)
		}
		assetNames := make(map[string]struct{}, len(names))
		for _, name := range names {
			assetNames[name] = struct{}{}
		}
		if candidate := uiUpdateCandidateFrom(metadata, applicationID, &link, notes, assetNames, prefix); candidate != nil {
			found = append(found, candidate)
		}
	}
	return uiUpdateNewest(found), nil
}

// uiUpdateResult is checkUpdates' answer to the About page.
type uiUpdateResult struct {
	Available   bool    `json:"available"`
	HasMetadata bool    `json:"hasMetadata"`
	URL         string  `json:"url"`
	Version     *string `json:"version,omitempty"`
	VersionCode *int64  `json:"versionCode,omitempty"`
	Notes       *string `json:"notes,omitempty"`
	// DownloadURL is null (not absent) when the release has no APK for this device.
	DownloadURL **string `json:"downloadUrl,omitempty"`
}

func uiCheckUpdatesFor(app *uiInstalledApp) (*uiUpdateResult, error) {
	var errs []error
	var candidates []*uiUpdateCandidate
	for _, source := range []func(string) (*uiUpdateCandidate, error){uiUpdateFromLatest, uiUpdateFromAPI} {
		candidate, err := source(app.applicationID)
		if err != nil {
			errs = append(errs, err)
			continue
		}
		if candidate != nil {
			candidates = append(candidates, candidate)
		}
	}
	// Both sources failed: surface the first (non-API) failure rather than claiming no update.
	if len(candidates) == 0 && len(errs) == 2 {
		return nil, errs[0]
	}
	latest := uiUpdateNewest(candidates)
	if latest == nil {
		return &uiUpdateResult{URL: uiUpdateReleasesPage}, nil
	}
	var download *string
	for _, abi := range app.abis {
		if apk, ok := uiUpdateAPKAsset(latest, abi); ok {
			link := uiUpdateReleasesPage + "/download/" + latest.version + "/" + apk
			download = &link
			break
		}
	}
	version, code, notes := latest.version, latest.code, latest.notes
	return &uiUpdateResult{
		Available: latest.code > app.versionCode, HasMetadata: true,
		Version: &version, VersionCode: &code, URL: latest.url, Notes: &notes,
		DownloadURL: &download,
	}, nil
}

// checkUpdates {}: the newest fk-box release for this install. Was
// BoxBridge.checkUpdates / BoxUpdates.check.
func uiCheckUpdates(json.RawMessage) (any, error) {
	app, err := uiInstalledAppInfo()
	if err != nil {
		return nil, err
	}
	return uiCheckUpdatesFor(app)
}
