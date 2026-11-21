# 内核隔离：官方内核原样编译、独立进程运行

状态：设计文档（P0）。基线：`main` 3e7be03f（2026-11-21）。内核版本取自 [version.properties](../version.properties)：sing-box `v1.15.0-alpha.13`、Xray `v26.11.9`、mihomo `v1.19.34`、libXray `v1.261109.0`、Go `go1.27.3`。

目标：以后升级内核只改 `version.properties` 的版本号；CI 拉取官方 tag 原样编译，不打补丁、不往内核源码树拷文件，也不改 fk-box 的 Go 代码。

本文的每个接口、参数和构建命令都注明出处（仓库、tag、路径）。出处为当时读到的源码，升级后以 `tools/bump_core.py` 的偏差报告为准。

## 1. 现状与问题

- 三个内核以 Go 库的形式编进同一个 `libbox.aar`，跑在应用服务进程里，同一时间只运行一个（[core/README.md](../core/README.md)）。
- `core_*.go`、`ui_*.go` 拷进 sing-box 源码树的 `experimental/libbox`，`fkhost`、`fkbox`、`fkcore`、`fkbind` 由 `patch_entry.py` 放进同一棵树；Xray、mihomo 的依赖必须迁就 sing-box 的 `go.mod`（`core-module-bumps.txt`，`github.com/sagernet/sing` 不能变）。
- 对上游的补丁：`httpdebug.patch`、`http_rewrite.patch`（改 `option`/`route`）、`sing-tun-android-ingress.patch`、`patch_dns_count.py`、`patch_entry.py`。
- 适配器直接碰内核内部：Xray 用 `reflect`+`unsafe` 改 TUN handler 的私有字段 `dispatcher`；mihomo 用 `hub/executor`、`listener`、`tunnel/statistic`、tun 的 `FileDescriptor` 字段并替换 `resolver.DefaultService`；保护钩子是进程级的 `RegisterDialerController`、`DefaultSocketHook`。
- 静态盘点（3e7be03f）：fk-box Go 代码 102 个非测试文件、22 425 行，其中 48 个文件（10 640 行）直接依赖内核或 libbox 内部符号（67 个 libbox 上游标识符，含 `CommandServer` 的未导出字段）；Kotlin 54 个文件用到 221 个 libbox API（152 个 `Libbox.*` 函数/常量、68 个类型）。

每次升级都要重做补丁、核对 67 个接触点和反射字段，这是本次改造要消除的成本。

## 2. 进程图

```
┌────────────────────────── io.fkbox.app（主进程，Kotlin）───────────────────────────┐
│ Slint 界面（libfkui.so） · BoxService/VPNService（前台服务、VPN 权限、状态确认）   │
│ Root/Shizuku · FkCoreClient（协议客户端） · 官方 libbox 的 CommandClient（读数据流） │
└──────┬───────────────────────────────┬───────────────────────────────┬──────────────┘
       │ LocalSocket（JSON 行协议，    │ Binder（ISingBoxService /      │ command.sock
       │ SCM_RIGHTS 传 TUN fd）         │ ITunProvider）                 │（StartedService gRPC）
┌──────▼────────────────────┐   ┌──────▼──────────────────────┐        │
│ fk-core（libfkcore.so）    │   │ :singbox 进程（Kotlin）       │◄───────┤ sing-box 运行时
│ fk-box 自己的 Go 模块       │   │ 官方 libbox.aar               │        │
│ 配置存储、订阅、geo、分流、 │   │ CommandServer.startOrReload  │        │
│ 生成内核配置、监管子进程、  │   │ PlatformInterface（Kotlin）   │        │
│ 抓包代理（MITM/改写）、     │   └─────────────────────────────┘        │
│ Xray/mihomo 的 StartedService◄──────────────────────────────────────────┘ Xray/mihomo 运行时
└──────┬──────────────┬─────┘
       │ exec + fd 3   │ exec + fd 3
┌──────▼─────┐  ┌──────▼──────┐
│ libxray.so  │  │ libmihomo.so │  官方 main 包，原样编译
│ API: unix   │  │ REST: unix   │
└────────────┘  └─────────────┘
```

| 进程 | 内容 | 由谁启动 | 崩溃后 |
| --- | --- | --- | --- |
| 主进程 | 界面、`BoxService`（`VpnService`）、Root/Shizuku、`FkCoreClient` | 系统 | 系统重启应用；服务状态按现有流程确认 |
| fk-core | `core/` 模块编出的可执行文件 `libfkcore.so`，不 import 任何内核 | `FkCoreClient` 用 `ProcessBuilder` 从 `nativeLibraryDir` 执行 | `FkCoreClient` 见到连接断开或进程退出即按退避重启（第 9 节），正在运行的 Xray/mihomo 随 fk-core 一起退出（`Pdeathsig`），服务转为 FATAL 并按自动重启策略恢复 |
| `:singbox` | `SingBoxService`（绑定式服务，`android:process=":singbox"`），官方 `libbox.aar` | 主进程 `bindService` | `linkToDeath` 通知主进程，主进程按退避重启 |
| Xray / mihomo | 官方可执行文件 | fk-core（`exec.Cmd`） | fk-core 监管：退避重启，有次数上限 |

**决策 D4（P4 实施时调整）：sing-box 留在主进程，不另开 `:singbox` 进程。** 官方 libbox 的 `PlatformInterface`（`openTun`、`autoDetectInterfaceControl` 对每个出站 socket 调 `VpnService.protect`、`findConnectionOwner`、默认网络监视）都是对 `VpnService` 的同步回调；放进另一个进程后每个 socket 都要跨进程 Binder 调用，并要把整个 `PlatformInterfaceWrapper` 拆成 AIDL。上游 SFA 也是在应用进程里运行 libbox。因此 P4 的做法是：主进程加载**官方、未修改的** `libbox.aar`，`BoxService` 用官方 `CommandServer.startOrReloadService` 启动 sing-box；分流规则与本地入站（`fkbox-local`，127.0.0.1 随机端口与凭据，供 fk-core 自己的请求和出口探测走配置的普通规则）由 fk-core 生成（`applyCoreRouting`、`singBoxServiceContent`）。原 fork 里的界面工具（连接流、内存卡片、网络质量、STUN、Tailscale ping、端点状态）改为 Kotlin（`bridge/UiTools.kt`、`bridge/UiStreams.kt`），直接调用官方 `CommandClient`。下文进程图与第 9 节中关于 `:singbox` 的部分以此为准。

为什么 sing-box 不用命令行：官方 `sing-box` 命令行在没有 root 的 `VpnService` 下拿不到 TUN（它的 tun 入站自己打开 `/dev/tun`）；官方 `experimental/libbox` 是上游给 Android 的正式入口，`PlatformInterface.OpenTun` 由应用返回 fd（sing-box `v1.15.0-alpha.13` `experimental/libbox/platform.go`）。Tailscale/Taildrop、OpenConnect、OpenVPN、USB/IP 也都在 libbox 里（`constant/proxy.go:29-45`），原样保留。

gomobile 的 `libgojni.so`（官方 libbox 的库名为 `libbox.so`，`-libname=box`）一个 APK 只能有一份：fk-core 不用 gomobile，是普通 Go 可执行文件；APK 里唯一的 gomobile AAR 是官方 libbox。

主进程也加载官方 libbox（与今天相同，Kotlin 的 `CommandClient`、`Libbox.newRemoteCommandClient` 等官方 API 继续可用），但主进程里不运行任何内核。

## 3. 构建

所有内核在 CI 里从官方仓库按 tag 浅克隆，构建结束后在每个源码目录运行 `git status --porcelain --ignored=no`，必须为空；构建过程不运行补丁脚本，不往源码树拷文件。实现：`.github/actions/build-kernels`（P1），P0 的验证版本是 tools/kernel-probe/build-kernels.sh。

| 产物 | 来源与命令 | 出处 |
| --- | --- | --- |
| `libbox.aar`（官方） | `make lib_install`，然后 `go run ./cmd/internal/build_libbox -target android -platform android/arm64`；gomobile `bind -androidapi 24 -javapkg=io.nekohasekai -libname=box`，标签 `with_quic, with_wireguard, with_utls, with_naive_outbound, with_clash_api, with_usbip, with_openvpn, with_openconnect, badlinkname, tfogo_checklinkname0, with_tailscale, ts_omit_*`；要求 OpenJDK 17；同时产出的 `libbox-legacy.aar`（API 21）丢弃 | SagerNet/sing-box `v1.15.0-alpha.13` `cmd/internal/build_libbox/main.go`、`Makefile` `lib_install` |
| `libxray.so` | `CC=<NDK r28>/aarch64-linux-android24-clang CGO_ENABLED=1 GOOS=android GOARCH=arm64 go build -o libxray.so -trimpath -buildvcs=false -gcflags="all=-l=4" -ldflags="-X github.com/xtls/xray-core/core.build=<git describe> -s -w -buildid= -checklinkname=0 -extldflags=-Wl,--pack-dyn-relocs=android" ./main`，无 `-tags` | XTLS/Xray-core `v26.11.9` `.github/workflows/release.yml:170-186,213-215` |
| `libmihomo.so` | 上游 `linux-arm64` 任务的静态构建：`CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -tags "with_gvisor" -trimpath -ldflags "-extldflags --static -X 'github.com/metacubex/mihomo/constant.Version=v<版本>' -X 'github.com/metacubex/mihomo/constant.BuildTime=…' -w -s -buildid="`（决策 D3） | MetaCubeX/mihomo `v1.19.34` `.github/workflows/build.yml:39,200-203,245` |
| `libfkcore.so` | fk-box 自己的模块 `core/`（`core/go.mod`），`go build ./cmd/fk-core`，同一 NDK | 本仓库 |

与上游工作流的差异（只在工具链，不碰源码与参数）：

- 统一用 `GO_VERSION`（go1.27.3，`GOTOOLCHAIN=local`）。Xray 要求 go 1.27（`go.mod:3`），sing-box 1.25.5，mihomo 1.20；mihomo 上游用 MetaCubeX 自己分发的 Go 1.26 构建，fk-box 用官方 Go。
- Xray 与 fk-core 用 NDK r28（`28.0.13004108`，应用已用）的 `android24` clang，与 Xray 上游相同。
- **决策 D3：mihomo 用上游 `linux-arm64` 的静态构建，不用 `android-arm64`。** P0 在模拟器上实测：官方 android 构建（`android && !cmfa`）在建 TUN 前无条件读取 `/data/system/packages.xml` 来生成分应用规则（`listener/sing_tun/server_android.go:49-55`、`server.go:481`），应用没有权限，报 `Start TUN listening error: build android rules: read packages list: open /data/system/packages.xml: permission denied`，TUN 起不来；`cmfa` 构建则是给 ClashMetaForAndroid 进程内嵌入用的（`hub/route/patch_android.go` 打开嵌入模式，系统 DNS 只能由宿主进程内调用 `UpdateSystemDNS` 设置）。linux 构建走 `server_notandroid.go`，没有包管理器；sing-tun 在给了 `FileDescriptor` 时不碰 netlink（metacubex/sing-tun `v0.4.29` `tun_linux.go:51-75,965,985,1023`）。代价：Go 在 linux 构建下读 `/etc/resolv.conf` 取系统 DNS（Android 没有这个文件，官方 android 非 cmfa 构建同样如此，`dns/system_posix.go`），所以 fk-core 生成配置时把 `system`/`system://`/`dhcp://` 名称服务器换成主进程报来的当前网络 DNS，并在缺省时补 `default-nameserver`，网络变化时经 `PUT /configs` 重载；日志时间为 UTC（linux 构建找不到 Android 的时区数据）；TLS 根证书用 mihomo 内嵌的 CA 与 `/system/etc/security/cacerts`（fk-core 设 `SSL_CERT_DIR`）。
- mihomo 上游在构建前用系统 CA 覆盖 `component/ca/ca-certificates.crt`（`build.yml` “Update CA”），这会修改源码树，fk-box 不做，使用该 tag 提交的 CA 文件。
- Android 10 起应用数据目录不可执行，内核以 `lib*.so` 放进 `jniLibs/arm64-v8a`，应用已设 `useLegacyPackaging = true`（安装时解压到 `nativeLibraryDir`），从那里执行。

CI 检查（P1 加入 `newui.yml`）：

1. 零修改：每个内核源码目录 `git status --porcelain --ignored=no` 为空。
2. 独立模块：`cd core && go list -deps ./...` 的结果不能含 `github.com/sagernet/sing-box`、`github.com/xtls/xray-core`、`github.com/metacubex/mihomo`（`tools/check_kernel_isolation.py`）。
3. 复制的协议定义（第 5 节）与内核 tag 中的原文件逐字节一致（同一脚本）。

## 4. TUN 与 protect

### 4.1 TUN 的传递

- 主进程的 `VPNService` 用 `VpnService.Builder` 打开 TUN（地址、路由、DNS、MTU、分应用与现在相同）。
- Xray/mihomo：主进程通过与 fk-core 的 `LocalSocket`，用 `setFileDescriptorsForSend` 附带 TUN fd 发出 `tun.attach` 消息（SCM_RIGHTS），随即关闭自己的副本；fk-core 用 `exec.Cmd.ExtraFiles` 把它作为子进程的 fd 3。
  - Xray：环境变量 `XRAY_TUN_FD=3`。`xray.tun.fd` 是 `platform.TunFdKey`，`NewEnvFlag` 先查原名，再查规范化名（转大写、`.` 换 `_`），所以 `XRAY_TUN_FD` 生效（Xray-core `v26.11.9` `common/platform/platform.go:27`、`proxy/tun/tun_android.go:25-41`）。未设置时默认读 fd 0 且 Android 版不校验，所以 fk-core 必须设置。Xray 自己 `F_DUPFD_CLOEXEC` 一份再用。
  - mihomo：`tun.file-descriptor: 3`，`tun.stack` 沿用配置（默认 `system`）。该字段在 `listener/sing_tun/server.go:152-163` 读取，文件没有构建标签，官方非 cmfa 构建同样生效（mihomo `v1.19.34` `config/config.go:319,1740`、`listener/config/tun.go:58`）。mihomo 的 TUN IPv4 地址由 `dns.fake-ip-range` 推出（`RawTun.Inet4Address` 已注释掉），因此 fk-core 为 mihomo 打开 TUN 时，把 VpnService 的 IPv4 地址取为 fake-ip 网段的第一个 /30（默认 `198.18.0.1/30`），不再像现在那样在进程内改写 mihomo 的结果。
  - 原 fk-box 的 mihomo TUN fd 泄漏问题（上游问题）随进程退出自然消失：子进程退出时内核关闭它的全部 fd。
- sing-box：`:singbox` 进程的 `PlatformInterface.openTun(options)` 经 Binder 调主进程 `ITunProvider.openTun`，主进程建 TUN 后返回 `ParcelFileDescriptor`；`:singbox` 用 `detachFd()` 交给 libbox（libbox 接管该 fd）。
- 为什么 fk-core 不直接被 Kotlin `ProcessBuilder` 传 fd：Java 的 `ProcessBuilder` 只继承 0–2，不能传额外 fd；`LocalSocket` + SCM_RIGHTS 是 Android SDK 的公开接口，fk-core 是 Go，`syscall.ParseUnixRights` 收取。

P0 在模拟器上验证了这条链（第 12 节）。

### 4.2 socket 保护（决策 D1）

官方可执行文件没有 protect 钩子：Xray 只有 `SO_BINDTODEVICE`（`sockopt.interface` / `autoOutboundsInterface`，`transport/internet/sockopt_linux.go:23-25`），mihomo 的 `dialer.DefaultSocketHook` 只在 CMFA 里赋值（`component/dialer/socket_hook.go`）。

**决定：`VpnService.Builder.addDisallowedApplication(自身包名)`**，本应用 UID 的所有 socket（fk-core、两个内核、`:singbox`、主进程）都不进 VPN。

- 分应用代理为“仅包含”模式时 Builder 不能同时用 disallowed，此时本应用本来就不在包含列表里（保存时去掉自身），效果相同。
- sing-box 的 `usePlatformAutoDetectInterfaceControl` 返回 `false`，不再逐个 socket 跨 Binder 调 `protect`；`auto_detect_interface` 仍由 sing-box 自己处理。
- 代价：本应用自己的流量默认直连。为保持现在“VPN 开着时订阅、geo、WebDAV、更新检查走代理”的行为，**决定：fk-core 在内核运行时经内核的本地回程入站（第 6 节，带随机认证的 SOCKS5/HTTP）发出这些请求**；内核未运行时直连，与现在一致。主进程自己的下载（Kotlin 侧的 geo 资源下载）向 fk-core 取当前代理地址后同样经它走。
- 另一做法（用户可改选）：不加 disallowed，而由 fk-core 给 Xray 配 `sockopt.interface`、给 mihomo 配 `interface-name`（绑定到默认网卡，需在网络变化时重载）。它依赖内核按网卡名绑定的行为，网络切换时有断流窗口，不推荐。

## 5. 控制面

全部用内核自带的官方接口，监听在应用私有目录（`/data/data/io.fkbox.app/` 为 0700，同 UID 之外的应用进不去）的 Unix socket。

### 5.1 应用读数据：同一个 `command.sock`

应用的仪表盘、代理组、连接、日志页今天用官方 libbox 的 `CommandClient` 连 `<basePath>/command.sock`（`experimental/libbox/command_client.go` `dialTarget()`），这部分 Kotlin 不改：

- sing-box 运行时，`:singbox` 进程的官方 `CommandServer.Start()` 监听 `<filesDir>/command.sock`（`Libbox.setup` 的 `basePath = filesDir`，与主进程相同；`commandServerSecret` 两进程一致）。
- Xray/mihomo 运行或没有内核运行时，fk-core 在同一路径提供 StartedService gRPC 服务，自己作答（状态、代理组、模式、连接、出站、日志、选择节点、测速、关闭连接）。gRPC 的服务定义来自 sing-box 的 `daemon/started_service.proto`：fk-core 不 import sing-box，而是把该 tag 生成好的 `started_service.pb.go`、`started_service_grpc.pb.go`（只依赖 protobuf/grpc，不依赖 sing-box 其他包）原样复制到 `core/internal/daemonpb`，CI 校验与内核 tag 中的文件一致；鉴权与 libbox 相同（gRPC metadata，`experimental/libbox/command_server.go:101-131`）。
- 切换内核时 fk-core 先关闭（或启动）自己的服务，`:singbox` 再启动（或关闭）它的，同一时刻只有一方监听；切换期间 `CommandClient` 断开后由应用按现有 `reloadGeneration` 机制重连。

### 5.2 fk-core 读内核

| 内核 | 接口 | 用途 | 出处 |
| --- | --- | --- | --- |
| sing-box | 官方 `CommandClient`（主进程）+ `ISingBoxService`（Binder：启动、重载、停止、OOM 报告、状态） | 状态、代理组、连接、日志、Tailscale/Taildrop/USB/IP/OpenConnect/OpenVPN、网络质量、STUN | sing-box `v1.15.0-alpha.13` `experimental/libbox/command_client.go`、`command_server.go` |
| mihomo | `external-controller-unix: <工作目录>/mihomo.sock`，REST/WebSocket：`/proxies`、`/proxies/{name}`（PUT 选择）、`/proxies/{name}/delay`、`/group/{name}/delay`、`/connections`（GET/WebSocket/DELETE）、`/traffic`、`/memory`、`/logs`、`/providers/proxies`、`/configs`、`/version` | 状态、代理组、测速、连接、日志、内存 | mihomo `v1.19.34` `config/config.go:427`、`hub/route/server.go:123-141,251-291` |
| Xray | `api` 入站监听 `"listen": "<工作目录>/xray-api.sock"`（以 `/` 开头即 Unix socket），服务 `StatsService`、`RoutingService`、`ObservatoryService`、`HandlerService`、`LoggerService` | 流量统计（`QueryStats`、`GetSysStats`）、负载均衡信息与覆盖（`GetBalancerInfo`、`OverrideBalancerTarget`）、观测延迟（`GetOutboundStatus`） | Xray-core `v26.11.9` `app/commander/commander.go:81-82`、`app/stats/command/command.proto:86-92`、`app/router/command/command.proto:120-130`、`app/observatory/command/command.proto:19` |

- mihomo 的 Unix 控制器不做鉴权（`router(cfg.IsDebug, "", …)`，`secret` 被忽略）且把 socket 设为 0666；它放在应用私有目录，其他应用进不去，所以不另加 secret；不开 TCP 控制器（`external-controller` 置空）。
- Xray 的 gRPC 消息定义同样复制生成好的 `.pb.go`（只复制不依赖 xray-core 其他包的文件；依赖的消息手写 protobuf 编码，见 `core/internal/xrayapi`），CI 校验一致。
- 配置检查改为调用内核自己的命令：`libmihomo.so -t -d <dir> -f <file>`、`libxray.so run -test -c <file>`（`main/run.go:57-69`）；sing-box 用官方 `Libbox.checkConfig`（主进程，同一个官方实现，`experimental/libbox/config.go:51`），不再起命令行。fk-core 不链接内核做解析。

### 5.3 Xray 分享链接转换（决策 D2）

**决定：(a) 单独的 Go 模块 `core/tools/xrayshare`**，`require github.com/xtls/libxray v1.261109.0`（不打补丁；它固定的 xray-core 正是 `v26.11.9` 的提交 `676eaf66f8f0`，libXray `go.mod`），只调用 `share.ConvertShareLinksToXrayJson(links, "")`（`share/convert_share.go:13`），编成 `libxrayshare.so`，fk-core 按需执行（stdin 读链接，stdout 写 JSON）。

- 理由：转换规则跟着 Xray 版本走，自己重写（b）会和上游分叉，升级时还要比对；(a) 只多一个约 10 MB 的可执行文件，且只在导入时运行。
- 该模块不是 fk-box 宿主的一部分，`go list -deps` 检查只针对 `core/`（宿主模块），`core/tools/xrayshare` 是单独的 `go.mod`。

## 6. 配置生成与注入

fk-core 保存用户配置原文，启动时生成交给内核的配置（不改磁盘上的配置）。注入内容：

| 注入 | sing-box | Xray | mihomo |
| --- | --- | --- | --- |
| TUN | 配置自己的 `tun` 入站（经 `openTun`） | 入站换成 `tun`（与现在相同），fd 3 | `tun.enable`、`file-descriptor: 3`，关闭 `auto-route`/`auto-redirect`/`auto-detect-interface` |
| 控制接口 | libbox 自带 `command.sock` | `api` 入站 + `stats`、`policy.system.statsOutbound*` | `external-controller-unix`，`external-controller: ""` |
| 回程入站 `fkbox-return` | `mixed`，`127.0.0.1:<随机>`，随机用户名/密码 | `socks`（`udp: true`）+ `http`，`127.0.0.1`，账号认证 | `listeners` 中的 `mixed`，`127.0.0.1`，`users` |
| 抓包出站 `fkbox-capture` | `socks` 出站指向抓包代理 | `socks` 出站 | `proxies` 中的 `socks5` |
| 抓包规则 | 规则列表最前：`inbound != fkbox-return` 且命中（域名、端口、`package_name`）→ `fkbox-capture` | `routing.rules` 最前：`inboundTag` 非回程且命中（`domain`、`port`）→ `fkbox-capture` | `rules` 最前：`AND,((NOT,((IN-NAME,fkbox-return))),(DOMAIN…/DST-PORT…))` → `fkbox-capture` |
| QUIC 拦截（MITM 开启时） | `network: udp, port: 443` 且命中 → `reject` | `network: udp, port: 443` → `blackhole` | `AND,((NETWORK,UDP),(DST-PORT,443),…)` → `REJECT` |

- 回程入站的流量只走普通规则：抓包规则都带“入站不是回程”条件，绝不再进抓包，避免死循环。
- 按应用：sing-box 用 `package_name`，mihomo 的 `PROCESS-NAME` 在非 root 下不可用（第 10 节第 1 项），Xray 没有按应用规则；抓包的应用筛选因此只对 sing-box 精确生效，另两个内核只按域名/端口筛选，记录中的应用名为“尽力而为”（第 7 节）。
- `http_rewrite` 不再写在 sing-box JSON 的 `experimental` 里（官方 sing-box 用 `DisallowUnknownFields` 拒绝未知字段，`option/options.go:40-42`），见第 7.3 节。
- 分流规则编译（`core_routing_{singbox,xray,mihomo}.go`）、数组配置合并、默认日志级别、geo 路径与现在相同，迁入 fk-core。

## 7. 外置抓包代理

取代 `httpdebug.patch`、`http_rewrite.patch`、`core/route/httpdebug.go`。

### 7.1 结构

- `core/httpdebug` 原样迁入 fk-core 模块（它本来不依赖内核，只有 `route/httpdebug.go` 依赖 sing-box 的路由）。新增 `core/capture`：本地代理，监听 `127.0.0.1:<随机端口>`，SOCKS5（CONNECT、UDP ASSOCIATE 只用于拒绝）与 HTTP CONNECT，每次启动生成随机用户名和密码。
- 连接进入后：读目标地址 → 按现有 `httpdebug` 逻辑嗅探、记录、MITM、改写 → 上游连接经回程入站（带认证的 SOCKS5）回到内核，由内核按原规则选出站。原来“保持原路由、原出站”的语义由“回程入站只走普通规则”保证。
- 三个内核共用这一套；sing-box 的抓包从路由钩子改为同一个外部代理。

### 7.2 行为变化

- 原实现在 sing-box 选定出站之后嗅探；外置后抓包在内核选出站之前，嗅探在抓包代理里做（与 `httpdebug` 的 `proxy.go` 相同的嗅探器），对上游 TLS 证书的校验仍用 fk-core 的证书库（与现在 `adapter.RootPoolFromContext` 的系统库 + 用户证书相同来源）。
- `blockQUIC` 改为内核规则（第 6 节），只在 MITM 开启时插入；命中范围按域名/端口（sing-box 另可按应用）。
- 应用归属：抓包代理按回程连接无法得知原应用；fk-core 用内核连接列表按（源地址、源端口）匹配：sing-box 的连接带 `process_info`，mihomo 的 `/connections` 带 `metadata.process`（非 root 时多为空）；匹配不到显示“未知”，不伪造。Xray 没有逐条连接接口，始终未知。
- MITM CA 流程（Android Keystore 中的 P-256 根密钥，Go 只拿签名回调）不变：签名回调从进程内 gomobile 回调改为协议上的反向调用 `host.captureSign`（fk-core → 主进程，主进程用 Keystore 签名后回复），只传 SHA-256 摘要。

**P5 实施时的调整：**

- 抓包代理只做 SOCKS5（用户名 `fkbox-return`，随机密码），不做 HTTP CONNECT 入口；上游经内核本地入站 `fkbox-local` 的 HTTP CONNECT 回到内核（`core/capture/proxy.go`）。
- 内核路由：sing-box 加 socks 出站 `fkbox-capture` 与逻辑规则（来自 `fkbox-local` 的不再送回，避免环路）；Xray、mihomo 加同等出站与规则，但按应用筛选只有 sing-box 支持，选了应用时这两个内核拒绝启动抓包并说明原因（`core/fk/capture_route.go`）。
- 应用归属匹配未做，记录里的应用为空。
- 反向调用名为 `capture.sign`（参数为密钥别名与摘要）。
- 抓包启停后由应用重载服务，使路由生效。

**决策 D5（P5 实施时调整）：不迁移 `http_rewrite` 的存储位置。** 规则继续写在配置内容的 `experimental.http_rewrite`；fk-core 在启动 sing-box 前取出并启用改写，再把去掉该字段（及退役的 `experimental.http_debug`）的内容交给官方解析器。这样不改用户数据格式、备份格式和编辑方式，7.3 的迁移不做。Xray/mihomo 的配置没有该字段，不支持改写。

### 7.3 `http_rewrite` 迁移（用户数据格式变化，未实施，见决策 D5）

- 改写规则成为 fk-box 自己的按配置文件存储的设置（`profiles.store` 中每个配置一项 `httpRewrite`，JSON，与原字段同一结构 `core/option/http_rewrite.go`）。
- 一次性迁移：读取配置时若内容含 `experimental.http_rewrite`（或退役的 `experimental.http_debug`），取出存入该配置的设置，并从内容中删去；保存或格式化配置时同样处理。迁移走现有的修订冲突检查与原子写入；备份导出带上新设置，导入旧备份时同样迁移。
- 下发给内核的配置里不再有这两个字段。

## 8. Kotlin 侧

- `Libbox.*` 中 fk-box 自加的绑定（配置存储、分流、geo、订阅、WebDAV、备份、抓包、出口探测、服务检测、局域网代理、内存、工具类，约 150 个）改为协议方法，由 fk-core 作答；`protocol/methods.json` 增加 `owner: "core"` 的方法（主进程调用 fk-core），`tools/protocol.py` 生成 Go 分发表与 Kotlin 客户端桩（`bridge/CoreProtocolGen.kt`）。
- 官方 libbox 的 API（`CommandClient`、`Libbox.newRemoteCommandClient`、`Libbox.checkConfig`、Tailscale/Taildrop/USB/IP/OpenConnect/OpenVPN 类型、`PlatformInterface` 相关类型）继续直接调用。
- 新增：`bg/SingBoxService.kt`（`:singbox`，持有官方 `CommandServer`，实现 `CommandServerHandler` 与 `PlatformInterface`，原 `PlatformInterfaceWrapper` 迁入）、`bg/ITunProvider.aidl`、`bg/ISingBoxService.aidl`（新 AIDL 文件，不复用、不重排 `IService` 等现有接口的方法编号）、`bridge/FkCoreClient.kt`（启动 fk-core、LocalSocket 协议、fd 传递、监管）。
- 不变：安装身份 `io.fkbox.app`，源码 namespace 与 AIDL 描述符 `io.nekohasekai.sfa`；VPN 权限确认、前台服务、服务状态确认流程；“发出启动请求”不算启动成功：`BoxService` 只在 fk-core 报告内核就绪（第 9 节）或 `:singbox` 的 `startOrReloadService` 返回且状态为 STARTED 后才报告 STARTED。
- `ui/` 的 Slint 界面不动，只改协议对接需要的部分（例如 DNS 计数为空时的显示由 Rust 侧已有的空值分支处理）。

## 9. 监管与资源

- 启动：fk-core `exec` 内核（`SysProcAttr.Pdeathsig = SIGKILL`，fk-core 退出时内核随之退出），**就绪检测**：控制接口能连上且回答（mihomo `GET /version`，Xray `StatsService.GetSysStats`），超时 30 秒（mihomo 首次启动可能要下载 provider）；未就绪即视为启动失败，杀掉子进程并报告错误与最后的日志。
- 停止：先 `SIGTERM`，5 秒未退出再 `SIGKILL`；确认进程已回收（`Wait` 返回）后才算停止。
- 崩溃：进程意外退出时按指数退避重启（1、2、4、8、16 秒），10 分钟内最多 5 次，超过即停止并报告 FATAL；每次重启后重新做就绪检测；VPN 与界面不受影响（TUN fd 由主进程重新发送，fk-core 保留一份 dup 直到服务停止，以便重启时复用）。
- 切换内核：先停旧进程并等待回收（端口与 fd 随进程释放），再启动新进程；sing-box 与另两个内核之间切换时先 `closeService`（`:singbox`）或停子进程，再启动另一方。
- sing-box：主进程 `bindService(:singbox)`，`IBinder.linkToDeath` 收到死亡通知后按同一退避策略重启并重新 `startOrReloadService`。
- 内存与 OOM 报告：fk-core 按子进程读 `/proc/<pid>/status`（`VmRSS`、`VmHWM`）与 `/proc/<pid>/smaps_rollup`（`Pss`），仪表盘内存显示当前内核进程的值（sing-box 为 `:singbox` 进程，主进程用 `Debug.getMemoryInfo` 不可跨进程，由 `:singbox` 自己读 `/proc/self/smaps_rollup` 经 Binder 返回）；内核进程被 OOM 杀死（`Wait` 的信号为 `SIGKILL` 且不是 fk-core 发出的）时，fk-core 写报告：时间、内存曲线最后的读数、崩溃前最后 200 行日志、能取到的连接列表（mihomo 的最后一次 `/connections` 快照；Xray 无）。sing-box 的 OOM 报告沿用官方 libbox 的实现（`:singbox` 进程内）。

## 10. 功能保留与损失（需用户确认的事项，已按推荐做法决定）

| # | 项目 | 决定 | 损失 |
| --- | --- | --- | --- |
| 1 | mihomo `PROCESS-NAME`/按应用规则 | 接受损失：官方非 cmfa 构建在 Android 上读 `/data/system/packages.xml`（需 root，sing-tun `v0.4.29` `packages_android.go:104`），连接归属走 netlink/`/proc`，Android 10 起非 root 拿不到。配置检查时对含 `PROCESS-NAME`、`PROCESS-PATH`、`UID` 规则的 mihomo 配置给出提示；分应用仍可用 VpnService 的分应用代理（整个应用走或不走 VPN） | mihomo 规则内按应用分流不生效 |
| 2 | Xray 连接列表 | 连接页在 Xray 下只显示流量统计（`StatsService`：入站/出站上下行），不列逐条连接，界面标明原因 | 逐条连接、关闭单条连接 |
| 3 | DNS 查询计数 | 去掉：三个内核都没有官方计数接口，日志估算不可靠；仪表盘该项为空 | DNS 计数 |
| 4 | `sing-tun-android-ingress` 修复 | 接受：root 下 `auto-redirect` 的 ingress 过滤问题会重新出现，文档写明；不向上游提 PR（未得用户同意） | root 自动重定向下的该修复 |
| 5 | OOM 报告连接列表、电量报告、Tailscale ping、STUN、网络质量 | sing-box：全部由官方 libbox 在 `:singbox` 内提供（OOM、电量报告）或经 `CommandClient` 调用（Tailscale ping、STUN、网络质量），数据经 `command.sock` / Binder 到主进程。Xray/mihomo：OOM 报告由 fk-core 写（第 9 节，mihomo 附连接快照，Xray 无）；电量报告不提供；网络质量与 STUN 不提供（原实现借用 sing-box 的 `networkquality`/`stun` 包经内核拨号，fk-core 不能 import）；Tailscale 只属于 sing-box | Xray/mihomo 下的电量报告、网络质量、STUN |
| 6 | APK 体积 | 见第 12 节，CI 实测 | — |

另：`core_xray_track.go`（反射改 Xray 私有字段）、mihomo 的进程内 DNS 计数、进程级保护钩子全部删除；Xray 的 `dns` 出站与 53 端口规则保留（配置生成）。

## 11. 分阶段

| 阶段 | 分支 | 内容 |
| --- | --- | --- |
| P0 | `arch/kernel-isolation-p0` | 本文档、`kernel-feasibility.yml` 与 `tools/kernel-probe/`（不合并） |
| P1 | `arch/kernel-isolation-p1` | `build-kernels` 动作：官方 libbox、`libxray.so`、`libmihomo.so`、`libxrayshare.so`、`libfkcore.so`；零修改与模块隔离检查；旧构建保留，`-PkernelIsolation=true` 切换 |
| P2 | `-p2` | `core/go.mod` 独立模块；与内核无关的代码迁入；协议 `owner: core`；Kotlin `Libbox.*` 中 fk-box 的绑定改走 fk-core |
| P3 | `-p3` | Xray/mihomo 子进程、fd 传递、监管、控制接口适配器、StartedService 作答 |
| P4 | `-p4` | 官方 libbox 启动 sing-box（主进程，决策 D4）；fork 中的界面工具改为 Kotlin |
| P5 | `-p5` | 外置抓包代理；`http_rewrite` 留在配置内（决策 D5）。与 P4 一起合并（P4 单独时抓包仍依赖分支 libbox） |
| P6 | `-p6` | 删除 `legacy/`、补丁、旧构建 Action（`build-libbox`、`setup-core`）与脚本；应用只用官方 `libbox.aar`；更新文档 |
| P7 | `main` | `build-release.yml`（`publish_release=false`） |

## 12. P0 验证结果

见 kernel-feasibility.yml 的运行记录（链接与数字在 PR 描述中更新）。
