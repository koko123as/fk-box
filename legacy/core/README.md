# core/

fk-box's own Go code, overlaid into sing-box's `experimental/libbox` package at build
time (`.github/actions/setup-core` copies `core_*.go` and `ui_*.go`). Upstream kernel sources and `go.mod` are not
edited here.

fk-box's own packages sit next to it in the same sing-box tree and module
(`tools/patch_entry.py`, no Go module of their own):

| Source | In the sing-box tree | What it is |
| --- | --- | --- |
| `core/fkhost/` | `experimental/fkhost` | fk-box's host services (log, HTTP, platform per OS, working directory, sing-box's start and stop `StartSingBox`, the protocol server); never imports libbox |
| `core/fkbox/` | `experimental/fkbox` | the gomobile entry package, bound into `libbox.aar` next to libbox (Java `io.nekohasekai.fkbox.Fkbox`); forwards to libbox so the Kotlin calls to `Libbox` keep their names |
| `core/fkcore/` | `cmd/fk-core` | the desktop core process |
| `core/fkbind/` | `cmd/fk-bind` | Android 构建命令：把 libbox 与 fkbox 绑定成一个 `libbox.aar`（API 24，build_libbox 的参数与标签加 `cmfa`），不产出 legacy AAR |

## Clients and the `Core` interface

Every client talks to the core through the UI protocol in `protocol/methods.json`:
methods (answered by Go through `UIInvoke`, or by the client's host), events (delivered to
a `UIEventSink`), a protocol version and the platform capabilities a client declares.
`core_interface.go` names the client-facing surface as the Go interface `Core`: `Hello`,
`Start`, `Reload`, `Stop`, `SelectOutbound`, `SubscribeConnections`, `SubscribeLogs`,
`SubscribeDashboard` and `SetEventSink`.

- **Android** 在进程内经 gomobile 运行核心。`InProcessCore` 包装 `CommandServer`
  与 Kotlin 宿主已调用的 Go 侧数据流；应用经 fk-box 自己的命令服务
  （`core_command_server.go`，见下文“sing-box 作为普通内核”）读取核心，不在进程外运行核心。
- **Desktop clients** run the core as the child process `fk-core` (`core/fkcore`,
  built from the same packages; CI builds it on Linux and checks the
  handshake). The client starts it, sends `hello`, then speaks the protocol
  as JSON lines over stdio or a Unix socket (`-socket PATH`); the framing is
  `fkhost.ProtocolServer` (`{"id","method","args"}` in, `{"id","result"|"error"}`
  and `{"event","payload"}` out; anything before `hello` is refused). It
  answers the Go-owned methods, `startService`/`reloadService`
  `{content, core?}` (a desktop client passes the profile; Android's host
  reads it from its own store), `stopService`, `setLogsVisible`, `clearLogs`,
  and raises the core's events; host-owned methods are refused by name.
  No desktop client exists yet.

Every client starts with `hello`; see `core_hello.go` and `tools/protocol.py`.

## Kernels and host services

The three kernels (sing-box, Xray, mihomo) implement one internal interface,
`coreKernel` (`core_kernel.go`), one file per kernel:
`core_kernel_singbox.go`, `core_kernel_xray.go`, `core_kernel_mihomo.go`
(tests `TestUIKernel<Name>*`). A kernel gets everything it needs from the
program hosting it through fk-box's own host interfaces (`core_host.go`), never
from libbox globals or libbox types:

| Interface | What it provides |
| --- | --- |
| `Platform` | create the TUN, protect a socket (dial control), the owner of a connection, default-network changes |
| `CommandService` | the control channel the UI reads the core through (moving open streams to the running core, group refreshes) |
| `Lifecycle` | starting and stopping sing-box inside the service, the working directory |
| `HTTP` | HTTP clients that trust the host's certificate store |
| `Log` | the service log the app shows, and the process log |

Each is implemented by fk-box's own package `fkhost` (Lifecycle on Android:
`core_singbox_host.go` over `fkhost.SingBox`; CommandService:
`core_command_server.go`). Android 上只有平台适配仍读取应用交给 libbox 的
`PlatformInterface` 对象（`core_host_android.go`）。 `core_touch_singbox.go`
lists every sing-box, sing-tun and libbox symbol the code here uses, so an
upgrade that changes one fails to compile there by name; the upgrade checklist
is in [libbox.md](../../libbox.md#升级内核).

The core switch (`startOrReloadCore`, `closeCore` in `core_service.go`) runs
against any kernel host: the `CommandServer` host on Android (sing-box via
`fkhost.SingBox`), or the standalone host (`core_standalone.go`, not built for
Android) that the desktop core uses.

## sing-box 作为普通内核（Android）

Android 上 sing-box 不运行在 libbox 的 `daemon.StartedService` 里；fk-box 不修改
`experimental/libbox` 与 `cmd/internal/build_libbox` 的上游文件（`httpdebug.patch`、
`http_rewrite.patch` 只改 option/route，见 [libbox.md](../../libbox.md)）。

- **启动与停止**：`core_singbox_host.go` 的 `coreSingBoxLifecycle` 调用
  `fkhost.StartSingBox`（`core/fkhost/singbox.go`）自己创建、启动、关闭
  `box.Box`，并完成启动前后要做的事：配置快照（崩溃报告）、HTTP 抓包/改写的停止、
  准备与激活、应用的自动重定向与分应用列表写入第一个 tun 入站、按设置追加 OOM killer
  服务、重载期间 OOM 记录器的 `BeginReload`/`EndReload`、电量报告的启动与暂停记录、
  全新启动时清空服务日志。上下文是 `NewCommandServer` 建立的平台上下文（libbox 的
  平台包装、电量与 OOM 记录器）。正在进行的启动（规则集下载可能让 `box.Start` 很久）
  会被停止或下一次启动中断：取消它的上下文，不留下运行的 box，停止不必等它结束。
- **启动选项**：`CoreServiceOptions`（`core_service_options.go`）是 libbox
  `OverrideOptions` 的字段加 HTTP 改写的存储目录、证书与签名回调。sing-box 配置统一经
  `parseCoreConfig` 解析：先去掉退役的 `experimental.http_debug`，再交给 libbox 的
  `parseConfig`；`CheckCoreConfig`、`FormatCoreConfig`、`HasCoreTunInbound` 对
  sing-box 同样先清理（libbox 自己的 `CheckConfig`、`FormatConfig`、`HasTunInbound`
  不清理，应用的 main 源码不直接调用它们）。
- **命令服务**：`CommandServer.StartCoreCommandServer`（`core_command_server.go`）
  在 libbox `CommandServer.Start` 用的同一个 `command.sock`（或配置的本地端口）上
  提供 StartedService gRPC 服务、ManagedService 和健康检查，沿用 libbox 的鉴权与语言
  拦截器；`CloseCoreCommandServer` 先停当前内核再关闭服务。服务实现是 fk-box 的
  `coreStartedService`：Xray/mihomo 运行时由 fk-box 自己作答（`core_api.go`、
  `core_extra.go`），sing-box 运行时转给该 box 的 attached service
  （`daemon.NewAttachedService`，即 sing-box 自己的 `api` 服务为非自己创建的 box
  提供的同一实现，覆盖状态、代理组、模式、连接、出站、网络质量、STUN 以及
  Tailscale/Taildrop、USB/IP、OpenConnect、OpenVPN 端点服务，
  `core_command_forward.go`），都不运行时转给一个空闲的服务。版本、服务日志
  （`core_service_log.go`：sing-box 自己的日志行与 fkhost.Log 的每一行进同一个环，
  SubscribeLog/ClearLogs 由它提供）、服务状态（`core_service_status.go`）与弃用提示
  （`fkhost.DeprecatedNotes` 收集）由 fk-box 自己提供。`tools/verify_multicore.py`
  要求 StartedServiceServer 的每个方法都由 `coreStartedService` 实现。
- **服务状态**：`SubscribeServiceStatus` 对所有内核报告同一套状态：切换开始
  （`beginCoreSwitch`）为 STARTING；最外层切换结束时有内核在运行为 STARTED，否则为
  最后一次启动的错误（FATAL，带错误信息），被停止中断的启动为 IDLE；停止时
  STOPPING → IDLE，启动失败后的停止保留 FATAL；`setCoreError` 置为 FATAL；新启动的命令服务
  从 IDLE 开始。
- **OOM 报告**：`StartCoreCommandServer` 把 `NewCommandServer` 创建的 OOM 记录器换成
  fk-box 的：报告内容（内存 profile、运行时信息、配置快照、日志）仍由 libbox 生成，
  连接列表改从 fk-box 运行的 sing-box 的 `trafficcontrol.Manager` 读取；ManagedService
  随之重建，应用手动写 OOM 报告用的是同一个记录器。
- **应用调用**：应用、远程会话与 Go 侧工具用 libbox 的 `CommandClient`（gRPC 客户端）
  访问本机或远端，本机调用落到 fk-box 的服务。读取 libbox 自身服务的 `CommandServer`
  方法都有 fk-box 版本：`startCoreCommandServer`/`closeCoreCommandServer`（取代
  `start`/`close`）、`needWIFIStateCore`、`needFindProcessCore`、`pauseCore`、
  `wakeCore`、`wakeNowCore`、`resetNetworkCore`、`updateWIFIStateCore`、
  `setCoreError`；`recordScreenState` 只写电量报告，照旧可用。设备与网络事件作用于运行
  中的 sing-box；Xray/mihomo 没有暂停管理、Wi-Fi 状态和进程查找，只有
  `resetNetworkCore` 对它们生效（关闭绑在旧网络上的连接，同默认网络变化）。应用当前只
  调用 `needWIFIStateCore`（设备空闲时保持核心运行，不调用暂停/唤醒）。
  `tools/verify_multicore.py` 拦下 Kotlin 对 libbox 原方法（`start`、`close`、
  `needWIFIState`、`pause` 等）以及 main 源码对 `Libbox.hasTunInbound`/`formatConfig`/
  `checkConfig` 的调用。
- **仍由 libbox 提供**：`CommandServer` 对象本身（平台上下文、`PlatformInterface`
  包装、电量记录器）、`CommandClient`、`Libbox.*` 的既有绑定和 libbox 包内被 fk-box
  文件调用的辅助函数（`core_touch_singbox.go` 列出）。`NewCommandServer` 仍会创建一个
  libbox `StartedService`，它不承载 sing-box，只接收服务日志的副本：OOM 与电量报告附带
  它的日志，调试构建经它把日志交给应用。
- **Android 构建**：`cmd/fk-bind`（`core/fkbind`）用 gomobile 绑定
  `./experimental/libbox` 与 `./experimental/fkbox`，参数与标签取自 sing-box 的
  `cmd/internal/build_libbox`（加 mihomo 的 `cmfa`），只产出 API 24 的 `libbox.aar`；
  `bump_core.py` 的偏差报告会列出 build_libbox 的改动以便同步。
- **桌面**：`core_standalone.go` 同样用 `fkhost.StartSingBox`；`fk-core` 的协议服务
  （`fkhost.ProtocolServer`）不变。Windows 上 Xray/mihomo 的会话式 TUN 适配仍未做
  （sing-box 自己打开 wintun）。

需要实机验证的范围见 [libbox.md](../../libbox.md#sing-box-作为普通内核)。
