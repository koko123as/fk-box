// Package fk is the kernel-independent part of fk-box's Go code, ported from
// the former libbox overlay (package libbox) into fk-core
// (docs/kernel-isolation.md, phase P2). fk-core answers it over its protocol
// by name; the names are the ones gomobile gave Kotlin (the Go name with its
// first letter lowercased), so the app's Libbox.* calls switch to fk-core
// mechanically.
//
// Register exposes, as functions:
//
//   - Profile store: uiProfileStoreOpen, uiProfileStoreBegin,
//     uiProfileStoreCommit, uiProfileStoreRollback, uiProfileStoreList,
//     uiProfileStoreGet, uiProfileStoreNextOrder, uiProfileStoreInsert,
//     uiProfileStoreUpdate, uiProfileStoreDelete, uiProfileStoreStageImport,
//     uiProfileStoreCommitImport
//   - Backup: newUIBackupCheck (object UIBackupCheck), uiBackupProfileKey
//   - Profiles: detectCoreConfig, resolveProfileCore, checkCoreConfig,
//     formatCoreConfig, hasCoreTunInbound, coreConfigRemarks,
//     coreProfileStats, splitCoreConfigArray (object CoreConfigEntries),
//     profileArraySelect, prepareCoreProfile, prepareCoreProfileArray
//     (object UIProfileArrayEntries)
//   - Subscriptions: uiSubscriptionGet, coreSubscriptionUserAgent
//   - Routing: applyCoreRouting, coreRoutingTargets
//   - Geo: geoDataDir, geoResourceFileName, geoResourceDefaultURL,
//     geoResourcesNeeded, geoShouldExtract, geoUpdateDue, newGeoUpdate
//     (object GeoUpdate)
//   - WebDAV: uiWebDAVCheck, uiWebDAVDownload, uiWebDAVUpload
//   - UI protocol: uiInvoke, setUIAppInfo
//   - Log stream: uiLogStreamAppend, uiLogStreamCommit, uiLogStreamClear,
//     uiLogStreamSetDefaultLevel, uiLogStreamSetVisible
//   - LAN proxy: readUILANProxyListeners, setUILANProxyEnabled
//   - Egress probe: newUIDirectEgressProbe, newUIServiceEgressProbe
//     (object UIEgressProbe)
//   - Service: serviceStart, serviceStop,
//     serviceDefaultNetworkChanged,
//     closeCommandServer
//   - Versions: xrayVersion, mihomoVersion
//
// and the methods of the objects GeoUpdate (Fetch, FetchExpected, Cancel),
// UIBackupCheck (EntryCount, CheckEntry, CheckEntryIdentity, Finish),
// UIEgressProbe (LookupOutboundIP, Get, ServiceCheck, Close),
// CoreConfigEntries and UIProfileArrayEntries (Len and the per-index
// getters).
//
// Not registered: setUISettingsStore and setUIEventSink (the app is the Host,
// host.go), createZipArchive and formatBytes (official libbox, called by the
// app directly), and everything that needs a running kernel (service,
// dashboard and connection streams, quality/STUN/Tailscale tools, endpoint
// status, capture, CommandServer.NewUIEgressProbe), which stays in legacy
// until P3-P5.
package fk

import "github.com/koko123as/fk-box/core/rpc"

// Register adds every function and object type the app calls through
// fk-core to r.
func Register(r *rpc.Registry) {
	// Profile store (core_profile_store.go).
	r.Func("uiProfileStoreOpen", UIProfileStoreOpen)
	r.Func("uiProfileStoreBegin", UIProfileStoreBegin)
	r.Func("uiProfileStoreCommit", UIProfileStoreCommit)
	r.Func("uiProfileStoreRollback", UIProfileStoreRollback)
	r.Func("uiProfileStoreList", UIProfileStoreList)
	r.Func("uiProfileStoreGet", UIProfileStoreGet)
	r.Func("uiProfileStoreNextOrder", UIProfileStoreNextOrder)
	r.Func("uiProfileStoreInsert", UIProfileStoreInsert)
	r.Func("uiProfileStoreUpdate", UIProfileStoreUpdate)
	r.Func("uiProfileStoreDelete", UIProfileStoreDelete)
	r.Func("uiProfileStoreStageImport", UIProfileStoreStageImport)
	r.Func("uiProfileStoreCommitImport", UIProfileStoreCommitImport)

	// Backup (core_backup.go).
	r.Func("newUIBackupCheck", NewUIBackupCheck)
	r.Func("uiBackupProfileKey", UIBackupProfileKey)
	r.Object("UIBackupCheck", (*UIBackupCheck)(nil))

	// Profiles (core_profile.go, core_array.go, core_profile_array.go,
	// core_prepare.go, core_profile_stats.go, host.go).
	r.Func("detectCoreConfig", DetectCoreConfig)
	r.Func("resolveProfileCore", ResolveProfileCore)
	r.Func("checkCoreConfig", CheckCoreConfig)
	r.Func("formatCoreConfig", FormatCoreConfig)
	r.Func("hasCoreTunInbound", HasCoreTunInbound)
	r.Func("coreConfigRemarks", CoreConfigRemarks)
	r.Func("coreProfileStats", CoreProfileStats)
	r.Func("splitCoreConfigArray", SplitCoreConfigArray)
	r.Object("CoreConfigEntries", (*CoreConfigEntries)(nil))
	r.Func("profileArraySelect", ProfileArraySelect)
	r.Func("prepareCoreProfile", PrepareCoreProfile)
	r.Func("prepareCoreProfileArray", PrepareCoreProfileArray)
	r.Object("UIProfileArrayEntries", (*UIProfileArrayEntries)(nil))

	// Subscriptions (ui_subscription.go, core_profile.go).
	r.Func("uiSubscriptionGet", UISubscriptionGet)
	r.Func("coreSubscriptionUserAgent", CoreSubscriptionUserAgent)

	// Routing (core_routing.go).
	r.Func("applyCoreRouting", ApplyCoreRouting)
	r.Func("coreRoutingTargets", CoreRoutingTargets)

	// Geo (core_geo.go, core_geo_policy.go).
	r.Func("geoDataDir", GeoDataDir)
	r.Func("geoResourceFileName", GeoResourceFileName)
	r.Func("geoResourceDefaultURL", GeoResourceDefaultURL)
	r.Func("geoResourcesNeeded", GeoResourcesNeeded)
	r.Func("geoShouldExtract", GeoShouldExtract)
	r.Func("geoUpdateDue", GeoUpdateDue)
	r.Func("newGeoUpdate", NewGeoUpdate)
	r.Object("GeoUpdate", (*GeoUpdate)(nil))

	// WebDAV (core_webdav.go).
	r.Func("uiWebDAVCheck", UIWebDAVCheck)
	r.Func("uiWebDAVDownload", UIWebDAVDownload)
	r.Func("uiWebDAVUpload", UIWebDAVUpload)

	// UI protocol (core_method.go, core_updates.go).
	r.Func("uiInvoke", UIInvoke)
	r.Func("setUIAppInfo", SetUIAppInfo)

	// Log stream (core_log_stream.go).
	r.Func("uiLogStreamAppend", UILogStreamAppend)
	r.Func("uiLogStreamCommit", UILogStreamCommit)
	r.Func("uiLogStreamClear", UILogStreamClear)
	r.Func("uiLogStreamSetDefaultLevel", UILogStreamSetDefaultLevel)
	r.Func("uiLogStreamSetVisible", UILogStreamSetVisible)

	// LAN proxy (ui_lan.go).
	r.Func("readUILANProxyListeners", ReadUILANProxyListeners)
	r.Func("setUILANProxyEnabled", SetUILANProxyEnabled)

	// Direct egress probe and service check (ui_egress.go, ui_service_check.go).
	r.Func("newUIDirectEgressProbe", NewUIDirectEgressProbe)
	r.Func("newUIServiceEgressProbe", NewUIServiceEgressProbe)
	r.Object("UIEgressProbe", (*UIEgressProbe)(nil))

	// Xray and mihomo as child processes (core_service.go).
	r.Func("serviceStart", ServiceStart)
	r.Func("serviceStop", ServiceStop)
	r.Func("serviceDefaultNetworkChanged", ServiceDefaultNetworkChanged)
	r.Func("singBoxServiceContent", SingBoxServiceContent)
	r.Func("singBoxServiceStarted", SingBoxServiceStarted)
	r.Func("singBoxServiceStopped", SingBoxServiceStopped)
	r.Func("closeCommandServer", CloseCommandServer)

	// HTTP capture and profile rewriting (capture_service.go).
	r.Func("captureGenerateCA", CaptureGenerateCA)
	r.Func("captureSetMaterial", CaptureSetMaterial)
	r.Func("captureOpen", CaptureOpen)
	r.Func("captureValidate", CaptureValidate)
	r.Func("captureStart", CaptureStart)
	r.Func("captureStop", CaptureStop)
	r.Func("captureStatus", CaptureStatus)
	r.Func("captureLogs", CaptureLogs)
	r.Func("captureAppendLog", CaptureAppendLog)
	r.Func("captureClearLogs", CaptureClearLogs)
	r.Func("captureList", CaptureList)
	r.Func("captureHosts", CaptureHosts)
	r.Func("captureRead", CaptureRead)
	r.Func("captureClear", CaptureClear)
	r.Func("captureExportHAR", CaptureExportHAR)
	r.Func("captureExportCurl", CaptureExportCurl)
	r.Func("removeUIRetiredCaptureOptions", RemoveUIRetiredCaptureOptions)

	// Kernel versions (core_kernel_exec.go).
	r.Func("xrayVersion", XrayVersion)
	r.Func("mihomoVersion", MihomoVersion)
}
