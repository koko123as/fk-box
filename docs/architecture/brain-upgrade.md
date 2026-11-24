# Stage 7: upgrading sing-box, mihomo and Xray

Stage 7 of the single-kernel design. Goal: moving one of the three cores (or
gomobile) to a new version is a change of `version.properties` only; CI then
builds and tests that version, and when the upstream API moved, the failure
names the moved symbol in one wrapper package instead of somewhere in the host
or a plugin.

## Pins

`version.properties` is the only place a person edits:

| Key | Module in `apps/android/fkhost/go.mod` | Note |
| --- | --- | --- |
| `CORE_VERSION` | `github.com/sagernet/sing-box` (`v` + value) | also `constant.Version` of the kernel (ldflags in build-fkhost) |
| `MIHOMO_VERSION` | `github.com/metacubex/mihomo` (`v` + value) | |
| `XRAY_MODULE_VERSION` | `github.com/xtls/xray-core` (as is) | `XRAY_VERSION` is its tag; Xray tags are not module versions, `bump_core.py` turns the tag into the pseudo-version |
| `GOMOBILE_VERSION` | `github.com/sagernet/gomobile` (as is) | a go.mod `tool` (`gomobile`, `gobind`); build-fkhost runs `go install tool`, no `go get` |
| `GO_VERSION` | — | must be at least go.mod's `go` directive |

`sing`, `sing-tun` and every other module follow from those modules' own go.mod
files by MVS. `LIBXRAY_MODULE_VERSION` was dropped in stage 7 (libXray left with
libbox in stage 6b).

`scripts/sync_core_versions.py --check` (first step of fkhost.yml) fails when
go.mod and `version.properties` disagree, when go.mod replaces a pinned module,
or when the gomobile tools are missing from go.mod. `--apply` (needs Go) runs
one `go get` for all pins, `go get -tool` for gomobile and `go mod tidy`.

## Core upgrade workflow

`.github/workflows/core-upgrade.yml`, dispatched with `core` (sing-box, mihomo,
xray, gomobile), `version`, `base` (default `arch/stage7-ci`) and `dry_run`:

1. refuses a `base` outside `arch/*` (never `main`) and a version with
   characters a version cannot have;
2. checks out `base`, writes the pin (`scripts/bump_core.py`, which also refuses
   a version whose go.mod needs a newer Go than `GO_VERSION`; gomobile is a
   plain write);
3. `sync_core_versions.py --apply` → go.mod/go.sum;
4. commits as fk-box bot and pushes `arch/core-upgrade/<core>-<version>-<run id>`
   (a same-version run commits with `--allow-empty`, so the simulation still
   has a branch);
5. calls `brain.yml`, `fkhost.yml` and `newui.yml` (`workflow_call`) with
   `ref` = that branch: pushes made with `GITHUB_TOKEN` start no push runs, so
   the CI runs inside this workflow;
6. opens a **draft** PR `branch → base` with the CI results in its body, then
   fails if any of the three failed. Opening it needs "Allow GitHub Actions to
   create and approve pull requests"; without it the step says so, the branch
   and its CI stay, and `dry_run` skips the PR.

The workflow also runs on pushes of `arch/stage7-ci` that touch it or the two
scripts (a script check only): a workflow GitHub has never seen run cannot be
dispatched from a branch other than the default one.

`brain.yml`, `fkhost.yml` and `newui.yml` take a `ref` input on
`workflow_dispatch` and `workflow_call`; checkout and the concurrency group use
it, so stacked `arch/*` branches can be tested by dispatch as before.

## The upstream wrapper

`apps/android/fkhost/upstream` (package `upstream`, imported as `up`) is the
only package of fkhost product code that imports sing-box, sing, sing-tun,
mihomo or Xray: `singbox.go` (sing-box, sing, sing-tun), `mihomo.go`,
`xray.go` (also the blank import of `xray-core/main/distro/all`). 164 symbols:

- types are aliases (`type InboundContext = adapter.InboundContext`; embedded
  fields keep their names: `Adapter`, `TransportAdapter`, `DialerOptions`);
- constants are constants, functions are variables;
- generic functions are thin generic wrappers with the upstream signature
  (`FromContext`, `MustRegister`, `Register`, `RegisterTransport`), generic
  types generic aliases (`List`, `Element`);
- package variables the plugins write are setters
  (`SetMihomoDefaultResolver`, `SetMihomoProxyServerHostResolver`,
  `SetMihomoDirectHostResolver`); `constant.Version` is `SingBoxVersion()`
  (set by ldflags, read at call time);
- names: sing-box/sing/sing-tun keep theirs except collisions (`NewError` =
  `exceptions.New`, `NewTun`, `TunOptions`, `DialerConfig` =
  `common/dialer.Options`); mihomo and Xray symbols carry a `Mihomo` / `Xray`
  prefix.

`scripts/check_upstream_imports.py` (fkhost.yml) fails when a product file
imports an upstream module directly. Exempt: the wrapper, `*_test.go`,
`internal/fixture`, `e2e` (test servers may use upstream APIs directly).
fkhost.yml builds `./upstream` alone before the rest, so after an upgrade the
first red step is "Build the upstream wrapper" with the moved symbol's line;
fix it there (or, when the meaning changed, in the one plugin that uses it).

Not wrapped: the Go standard library, `miekg/dns`, `golang.org/x/*` (not one
of the three cores).

## Protocol tests

`apps/android/fkhost/host/protocols_test.go` (fkhost.yml, log lines `STAGE7`):

| Test | Servers | Client |
| --- | --- | --- |
| `TestProtocols/native/*` | sing-box inbounds (`fixture.StartSingBoxServer`, self-signed TLS from `fixture.TLSFiles`) | native sing-box outbound |
| `TestProtocols/mihomo/*` | the same sing-box inbounds | mihomo plugin |
| `TestXrayProtocols/*` | Xray inbounds in a child process (`fixture.StartXrayInbound`) | Xray plugin |
| `TestProtocolsConstructOnly/*` | none | mihomo plugin, start only |

Protocols with TCP+UDP echo: shadowsocks, vmess, vless, trojan, socks5,
hysteria2, tuic, anytls (native and mihomo), hysteria (native; mihomo TCP
only: mihomo's hysteria v1 UDP to sing-box's hysteria inbound timed out in CI
run 45354148270, unverified whether the two v1 UDP implementations
interoperate), http (TCP only); Xray
VLESS over tcp/ws/grpc/httpupgrade, VMess, Trojan, Shadowsocks. Stage 3's
tests keep mieru (mihomo), the detour chain, Xray XHTTP and mKCP. Every case
asserts a protected socket (and for mihomo, dials through the sing-box dialer;
for Xray, no refused dial).

Construct-only (no local server in this module): SSR, snell, gost-relay,
MASQUE, sudoku, shadowquic, trusttunnel. Not covered at all: WireGuard and
MASQUE traffic (endpoints need a peer), ssh (sing-box has no SSH server),
REALITY.

## Dependency report

`scripts/deps_report.py` (from the stage 0 probe) runs as an informational
fkhost.yml step: for the three cores' go.mod files at the pinned versions and
fkhost's `go list -m all`, which shared modules they ask for in different
versions (MVS raised them) and which parallel forks (quic-go, TLS forks, gVisor,
WireGuard) are linked side by side. It never fails the job.

## Upgrading, step by step

1. Run "Core upgrade" with the core and version (base: the newest `arch/*`
   stage branch, or the branch the next upgrade should stack on).
2. Read the three CI results in the draft PR. Red "Build the upstream wrapper":
   adapt `apps/android/fkhost/upstream`. Red protocol test: the named protocol
   broke at runtime. `go.mod`/`go.sum` never get edited by hand.
3. The sing-tun patch (`scripts/patch_sing_tun.py`) is checked with
   `git apply --check` against whatever sing-tun the new go.mod selects; when
   it no longer applies, newui.yml fails with a request to rebase the patch.
