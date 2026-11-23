# 内核拆分：三个上游原版二进制 + Rust 主机

状态：第 1 阶段设计（只有文档，不含代码）。本文是之后第 2～8 阶段的依据；每个阶段一个 PR，合并前由维护者确认。

目标：sing-box、mihomo、Xray 各自是一个**未打补丁的上游可执行文件**，按配置类型由 Rust 启动其中一个；内核只运行配置，其余功能（配置识别与生成、订阅、规则、Geo、进程守护、控制面、探测、抓包、备份、报告）在 Rust。Kotlin 只保留 Android 平台能力。

相关文档：[libbox.md](../libbox.md)（现有补丁与扩展）、[app.md](../app.md)（应用行为）、[phase2.md](phase2.md)～[phase4.md](phase4.md)（已完成的 Rust 迁移）、[AGENTS.md](../../AGENTS.md)（约束）。

## 1. 现状（基线 `37e80bc1`）

| 项目 | 现状 |
| --- | --- |
| 内核 | sing-box `v1.15.0-alpha.13`、Xray `v26.11.9`（libXray `v1.261109.0`）、mihomo `v1.19.34`，Go `go1.27.3`（[version.properties](../../version.properties)），经 gomobile 编进同一个 `libbox.aar` |
| 自写 Go | `apps/android/core/` 非测试代码 7,087 行（`*.go` 共 12,913 行，含测试 5,826 行），另 4 个补丁 364 行（`httpdebug.patch` 86、`http_rewrite.patch` 66、`sing-tun-android-ingress.patch` 170、`libbox-no-legacy.patch` 42），以及 `scripts/patch_dns_count.py`、`patch_httpdebug.py`、`patch_multicore.py`、`patch_sing_tun.py` |
| Kotlin 调 libbox | 43 个文件、约 9,600 行引用 `io.nekohasekai.libbox`；其中纯转换层是 `bg/PlatformInterfaceWrapper.kt`、`bridge/BoxStreams.kt`、`bridge/BoxToolStatus.kt`、`bridge/BoxWire.kt`、`utils/CommandClient.kt`、`backend/tools/*Status.kt`、`backend/usbip/USBIPStatus.kt` 等 |
| Rust | `crates/` 与 `apps/android/native/` 共约 32,400 行：存储、订阅流程、规则模型、Geo 策略、备份、命令订阅调度（`sessions.rs`）、流规则（`runtime.rs`）、服务状态机等已在 Rust；内核相关的拉取、拆分、转换、校验、编译仍经 `CoreKernel` 回调 libbox |

## 2. 上游核实结果

按 `version.properties` 的版本检出上游标签核对（2026-11-23）。“与提示词不一致或需补充”的项目放在 2.4，按约束先报告，不打补丁绕过。

### 2.1 sing-box `v1.15.0-alpha.13`

- 纯内核入口是 `cmd/sing-box`（`run`、`check`、`format` 子命令）；`experimental/libbox`、`daemon/`、`clients/` 是客户端层。
- `option/tun.go` 的 `TunInboundOptions` 没有传入外部 fd 的字段（只有 `interface_name`、`auto_route`、`auto_redirect`、`include_package` 等）。非 root 的 Android 进程不能自己创建 TUN，所以命令行版不能直接使用 VpnService 的 TUN。
- 官方 Android 命令行构建（`.github/workflows/build.yml` 的 `Build Android`）：`CGO_ENABLED=1`，NDK r28，`aarch64-linux-android23-clang`，标签为 `release/DEFAULT_BUILD_TAGS_OTHERS`：`with_quic,with_dhcp,with_wireguard,with_utls,with_acme,with_clash_api,with_tailscale,with_ccm,with_ocm,with_cloudflared,with_usbip,with_openvpn,with_openconnect,badlinkname,tfogo_checklinkname0`。**不含 `with_naive_outbound`**（只有 `DEFAULT_BUILD_TAGS` 带，且需要 cronet 工具链，仅用于 Linux）。
- 状态与控制：`service/api/server.go` 注册服务类型 `api`（`C.TypeAPI`），在 `StartStateStarted` 时创建 `daemon.NewAttachedService(ctx)` 与 `daemon.NewServer(startedService, secret)`，经 h2c 在 TCP 监听上提供 **与 libbox 命令客户端相同的 `daemon.StartedService` gRPC**（`daemon/started_service.proto`：状态、日志、分组、模式、URL 测试、选节点、连接订阅与关闭、出站列表、网络质量、STUN、Tailscale 状态/Ping/出口节点/证书/SSH、Taildrop、USBIP（`ProvideUSBDevices` 双向流）、OpenConnect、OpenVPN、通知）。鉴权为 `authorization: Bearer <secret>`（`daemon/server.go` `authenticate`）。监听只支持 TCP（`ListenOptions`），没有 Unix 套接字选项。Clash API（`with_clash_api`）另行存在。
- Android 平台行为（无 libbox `PlatformInterface` 时）：
  - `route/network.go`：没有平台接口时调用 sing-tun `NewNetworkUpdateMonitor`；Android 上 netlink 被禁（sing-tun `monitor_linux.go` 返回 `ErrNetlinkBanned`），`auto_detect_interface` 为 false 时该错误被忽略，为 true 时启动失败。因此**命令行版在 Android 上不能用 `auto_detect_interface`，也就收不到默认网络变化**。
  - 同文件 `StartStateStart`：`C.IsAndroid && platformInterface == nil` 时创建 sing-tun 包管理器，读 `/data/system/packages.xml`（sing-tun `packages_android.go`），普通应用无权限，`Start` 失败只记警告。`package_name` 规则因此匹配不到。
  - `wifi_ssid`/`wifi_bssid` 规则依赖平台的 Wi-Fi 监视，命令行版没有。

### 2.2 mihomo `v1.19.34`

- `config/config.go` 的 `tun.file-descriptor`（`RawTun.FileDescriptor`，`listener/config/tun.go`）可直接接收 VpnService 的 fd。
- 界面数据全在 external-controller（REST + WebSocket）。另有 `external-controller-unix`（`config/config.go` 第 427 行）可监听 Unix 套接字。
- `PROCESS-NAME` 只在 `cmfa` 标签下经回调取包名（`constant/features/cmfa.go`、`dns/patch_android.go`、`hub/route/patch_android.go`）。
- 官方构建（`.github/workflows/build.yml`）：
  - `goos: android`：`CGO_ENABLED=1`，NDK r29-beta1，**`aarch64-linux-android34`**（API 34）。
  - `goos: linux, goarch: arm64`：`CGO_ENABLED=0`，`-extldflags --static`，标签 `with_gvisor`。
- `listener/sing_tun/server_android.go`（`android && !cmfa`）在建 TUN 时调用 `tun.NewPackageManager` 并 `Start`（读 `/data/system/packages.xml`），失败则 TUN 启动失败。

### 2.3 Xray `v26.11.9`

- `proxy/tun/tun_android.go`：从环境变量读取 fd（`platform.TunFdKey = "xray.tun.fd"`，`EnvFlag` 也接受规范化名 `XRAY_TUN_FD`），设为非阻塞后 `F_DUPFD_CLOEXEC` 复制一份自己用，原 fd 留给调用方。
- 控制面：`app/commander`（gRPC）加 `app/stats/command`（`GetStats`、`QueryStats`、`GetSysStats` 等）、`app/observatory/command`（`GetOutboundStatus`）、`app/router/command`（`GetBalancerInfo`、`OverrideBalancerTarget`、`TestRoute`、`SubscribeRoutingStats`、规则增删）、`app/proxyman/command`。没有实时连接列表与关闭；`SubscribeRoutingStats` 只推送每个新路由决定（无关闭、无流量）。
- `app/commander/commander.go`：`listen` 以 `/` 或 `@` 开头时监听 Unix 套接字。gRPC 本身没有鉴权。
- 官方 Android 构建（`.github/workflows/release.yml`）：`CGO_ENABLED=1`，NDK r28b，`aarch64-linux-android24-clang`，`-checklinkname=0`、`--pack-dyn-relocs=android`。最低 API 24，与应用一致。

### 2.4 与提示词不一致、需确认的事实

| # | 事实 | 影响 | 本文的处理（待确认） |
| --- | --- | --- | --- |
| F1 | mihomo 官方 Android 构建目标是 API 34，而本应用 minSdk 24；且 `android && !cmfa` 构建在建 TUN 时必须读 `packages.xml`。分支 `arch/kernel-isolation-p0` 的模拟器探针（API 34）已复现后者：android 构建的 TUN 起不来 | 官方 Android 构建在非 root 设备上不能用 TUN，在 Android 7～13 上可能无法运行 | 用 mihomo **官方 linux/arm64 静态构建**（`CGO_ENABLED=0`、`with_gvisor`，与其 Release 资产同参数）。代价：不读 `/etc/resolv.conf`（Android 没有）→ Rust 必须把 `system`/`dhcp://` 名称服务器替换为 Kotlin 给出的系统 DNS；时区为 UTC（日志时间由 Rust 换算）；证书由 Go 的 `root_linux.go` 读 `/system/etc/security/cacerts`。同一探针中 gvisor 栈收发正常；`stack: system` 在本应用自身被排除出 VPN 时收不到流量，因此固定 `stack: gvisor` |
| F2 | sing-box 官方 Android 命令行标签不含 `with_naive_outbound` | NaiveProxy 出站不可用（现 libbox 有） | 按官方标签构建，NaiveProxy 列为去掉；配置识别时含 `naive` 出站的配置给出明确错误 |
| F3 | sing-box 的完整控制面是 `api` 服务（`daemon.StartedService` gRPC），不是只有 Clash API | Tailscale/OpenConnect/OpenVPN/USBIP 与分组、连接、日志可走同一套 gRPC | sing-box 全部走 `api` gRPC，不开 Clash API（见 4.6） |
| F4 | sing-box 命令行版在 Android 上没有 netlink、包管理器与 Wi-Fi 状态 | 不能 `auto_detect_interface`；`package_name`、`wifi_ssid` 规则无效；网络切换不会自动重置连接 | 生成配置时去掉这些字段并在界面提示；默认网络变化时由 Rust 调 `CloseAllConnections`（4.9） |
| F5 | sing-box 没有传入 TUN fd 的字段 | 必须由 Rust 的 tun2socks 接管 TUN | 第 5 阶段验证，未达标时的替代方案见 5.4，须维护者确认 |
| F6 | Xray 与 mihomo 的控制接口没有鉴权，同机其他应用可访问 127.0.0.1 | 本地端口必须防止被其他应用利用 | Xray API、mihomo 控制器都走应用私有目录的 Unix 套接字；sing-box gRPC 用随机 secret；所有代理入站带随机用户名密码（4.5） |

## 3. 目标架构

```
┌──────────────────────────── 应用进程 io.fkbox.app（uid U）────────────────────────────┐
│ Kotlin：Activity/GameActivity、VpnService（建站、交 fd）、前台服务与通知、磁贴、        │
│         应用列表、相机、WebView、USB 设备、文件选择、Keystore、Root/Shizuku、JNI 宿主  │
│   │ JNI（现有桥：CoreStore / SlintHost / CoreKernel 回调）                              │
│ Rust libfkui.so：client-ui（Slint）· mobile-ffi · client-core · 新增 client-runtime：  │
│   config 生成 · 订阅 · 规则编译 · Geo · 分享链接 · 校验                                  │
│   supervisor（fork/exec、守护、日志、内存）· tun2socks（仅 sing-box）· 抓包代理（P7）   │
│   CoreController：SingBoxApi(gRPC) / MihomoApi(REST over UDS) / XrayApi(gRPC over UDS)│
└───────┬──────────────────────────────────────────────────────────────────────────────┘
        │ fork/exec（同 uid U，被 VpnService 排除，流量直连底层网络）
        ▼
  libsingbox.so run -D <dir> -c config.json        （mixed 入站 + api 服务）
  libmihomo.so  -d <dir> -f config.yaml            （tun.file-descriptor: 3）
  libxray.so    run -c config.json                 （XRAY_TUN_FD=3）
```

- 三个内核以 `lib<名>.so` 名义放进 `jniLibs/arm64-v8a`，安装后位于 `nativeLibraryDir`，可执行（`useLegacyPackaging = true` 已开，库会解压到磁盘；`targetSdk 37` 下应用数据目录禁止 exec，`nativeLibraryDir` 允许，分支 `arch/kernel-isolation-p0` 已在 API 34 模拟器上执行成功，需真机复核）。
- 同一时间只运行一个内核；切换内核 = 停旧进程、确认退出与端口/TUN 释放、起新进程。
- 新增 crate `crates/client-runtime`（[phase4.md](phase4.md) 已预留此名）：进程守护、控制 API 客户端、tun2socks、抓包代理；只依赖 `client-core`，不含 JNI。`mobile-ffi` 编排，`apps/android/native` 接 JNI。

## 4. 关键设计

### 4.1 构建与打包

- 新的复合 Action `.github/actions/build-cores`：按 `version.properties` 检出三个上游标签（浅克隆），**原样**构建，构建后对每棵源码树断言 `git status --porcelain` 为空；不加 `replace`、不复制文件进源码树。
  - sing-box：`go build -trimpath -tags "$(cat release/DEFAULT_BUILD_TAGS_OTHERS)" -ldflags "-X 'github.com/sagernet/sing-box/constant.Version=…' $(cat release/LDFLAGS) -s -w -buildid=" ./cmd/sing-box`，`GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC=aarch64-linux-android23-clang`（与其 `build.yml` 相同）。
  - Xray：`go build -trimpath -buildvcs=false -gcflags="all=-l=4" -ldflags="-X github.com/xtls/xray-core/core.build=<commit> -s -w -buildid= -checklinkname=0 -extldflags=-Wl,--pack-dyn-relocs=android" ./main`，`GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC=aarch64-linux-android24-clang`。
  - mihomo：`go build -tags with_gvisor -trimpath -ldflags "-extldflags --static -X 'github.com/metacubex/mihomo/constant.Version=…' -w -s -buildid="`，`GOOS=linux GOARCH=arm64 CGO_ENABLED=0`（F1）。
  - Go 版本：各用上游 `go.mod` 要求的版本，不再共用一个模块图；`GO_VERSION` 只作为不低于三者要求的统一工具链。
- 输出 `libsingbox.so`、`libxray.so`、`libmihomo.so`，Gradle 从 `build/coreJniLibs/arm64-v8a` 打包；只构建 arm64-v8a。CI 模拟器（x86_64）另构建 amd64 版本只用于测试 APK。
- `scripts/verify_release.py` 增加：三个 ELF 为 aarch64、可执行段存在、版本字符串与 `version.properties` 一致；APK 中不再有 `libgojni.so`/`libbox`（第 8 阶段）。
- 缓存键：平台 + 三个版本 + Action 文件哈希（不再含 `apps/android/core/**`）。
- `scripts/bump_core.py` 改为只改 `version.properties`；升级内核不再需要核对补丁和 `ui_*.go`。

### 4.2 进程管理（`client-runtime::supervisor`）

- 启动：`std::process::Command` + `CommandExt::pre_exec`（`nix`），工作目录 `<filesDir>/cores/<内核>`，配置写在其中（权限 0600）。`pre_exec` 中：
  - `prctl(PR_SET_PDEATHSIG, SIGKILL)`，并在其后检查 `getppid()` 是否仍是父进程（父进程已死则立即 `_exit`）；
  - 对 fd 3 清除 `FD_CLOEXEC`（TUN，见 4.3）；其余 fd 由 Rust 统一以 `O_CLOEXEC` 打开，`Command` 不继承；
  - `PR_SET_THP_DISABLE` 按现有 `core_memory.go` 的条件设置（该标志跨 `fork`/`execve` 继承，见 `prctl(2)`），替代 libbox 里对整个应用进程的设置。
- `PR_SET_PDEATHSIG` 绑定的是**创建子进程的线程**：线程退出也会触发信号。所以所有子进程由 supervisor 的一个常驻线程创建，该线程在进程生命期内不退出。
- 残留进程：启动前读 `<filesDir>/cores/pid`，若该 pid 仍存在且 `/proc/<pid>/exe` 指向本应用 `nativeLibraryDir` 下的内核，先 `SIGTERM` 再 `SIGKILL`。应用被 LMK 杀掉时 PDEATHSIG 会杀子进程，这一步覆盖意外情况。
- 日志：stdout/stderr 各一条管道，按行读入环形缓冲（复用 client-core `logs.rs` 的 3000 行规则），解析各内核的级别前缀；sing-box 另经 gRPC `SubscribeLog`，以 gRPC 为准、管道作为启动阶段与崩溃前的补充。
- 就绪判断：子进程存活且控制接口可用（sing-box `GetStartedAt`，mihomo `GET /version`，Xray `GetSysStats`），超时 30 秒（mihomo 首次下载 provider 时延长到 90 秒，与现在的提示一致）。“发出启动请求”不等于“已启动”，Started 回调只在就绪后发出（保留 [AGENTS.md](../../AGENTS.md) 约束 4）。
- 停止：`SIGTERM`，3 秒后 `SIGKILL`；`waitpid` 收尸后确认 TUN fd 已在子进程侧关闭（子进程退出即关闭）、代理端口可再次绑定，才报告 Stopped。
- 崩溃：子进程非预期退出 → 记录退出码/信号与最后 200 行日志到报告目录（复用 `CrashReportManager` 的列表与导出）→ 断网保护开启时保持阻断 TUN（现有 `openBlockingTun`），否则按退避（1、2、4、8、16 秒，60 秒内 5 次后放弃）重启；重启成功前服务状态为 Starting，不报告 Started。
- 重载：sing-box 与 Xray 不支持热重载新配置 → 重启进程；mihomo 用 `PUT /configs?force=true`。TUN fd 在重启期间由 Rust 持有，不重新建站（见 4.3），避免 VPN 图标闪断与“始终开启 VPN”重连。
- OOM：`GOMEMLIMIT` 环境变量替代 `debug.SetMemoryLimit`；sing-box 另可在配置中启用其 `oom-killer` 服务。子进程在未请求停止时被 `SIGKILL` 结束、且最近一次采样的 `VmRSS` 达到上限的 90% 时，记为疑似 OOM 报告。
- 内存展示：子进程 `VmRSS` 读 `/proc/<pid>/status`（同 uid 可读），Go 运行时细分来自各控制接口（sing-box `Status.memory`、mihomo `/memory`、Xray `GetSysStats`）。
- 已知风险：Android 12+ 的 phantom process 管理（`PhantomProcessList`）会在全系统子进程超过 32 个或应用处于后台且子进程占用 CPU 过高时杀子进程。前台服务期间应用不是后台，但部分厂商 ROM 更激进。缓解：崩溃重启 + 断网保护；文档中说明 Android 14 开发者选项“停用子进程限制”。

### 4.3 TUN

1. Kotlin `VPNService` 照旧建站（地址、路由、DNS、MTU、分应用列表来自 mobile-ffi `vpn_routing.rs`），`establish()` 后把 `ParcelFileDescriptor.detachFd()` 经 JNI 交给 Rust；之后 Kotlin 不再持有。
2. Rust 持有该 fd（`OwnedFd`，CLOEXEC）。启动内核时在 `pre_exec` 里 `dup2(fd, 3)` 并清 CLOEXEC。
   - mihomo：配置生成器写 `tun: {enable: true, stack: gvisor, file-descriptor: 3, auto-route: false, auto-redirect: false, auto-detect-interface: false}`，`inet4-address`/`inet6-address` 与 VpnService 一致。
   - Xray：环境变量 `XRAY_TUN_FD=3`，入站换成 `tun` 入站。
   - sing-box：fd 不交给内核，由 Rust tun2socks 读写（4.4、5）。
3. 内核退出后 fd 3 随子进程关闭；Rust 手里的原 fd 不受影响，下一次启动重用。停止服务时 Rust 关闭原 fd，VPN 随之撤销。
4. 断网保护：内核未就绪或崩溃时 TUN 无人读取，流量自然被阻断，与现有 `openBlockingTun` 语义一致；开关关闭时崩溃后按设置决定是否撤销 VPN。

### 4.4 防回环与按应用分流

- `VpnService.Builder.addDisallowedApplication(本应用包名)` 始终加入（在用户的分应用设置之后合并），内核子进程与应用同 uid，其流量直接走底层网络；不再需要 `protect()` 回调（`PlatformInterfaceWrapper.autoDetectInterfaceControl` 删除）。
  - 用户把分应用设为“仅允许列表”时，`addAllowedApplication` 与 `addDisallowedApplication` 不能混用：此时只要不把本应用列入允许列表即可。由 `vpn_routing.rs` 统一处理并加测试。
- 后果：本应用自己的 HTTP（订阅、Geo、更新检查、WebDAV）默认直连。需要经代理时，Rust HTTP 客户端经内核的本地代理入站（4.5）发出；订阅设置增加“经当前代理更新”（默认沿用现在“服务运行时可能经过代理”的行为，即运行时经代理）。
- 分流规则中的应用条件：
  - 目标为“直连”的应用规则 → 三个内核都合并进 VpnService 的排除列表；
  - sing-box 的其他目标（代理/拦截/指定节点）：tun2socks 拿到新连接的五元组后经 JNI 调 `ConnectivityManager.getConnectionOwnerUid`（API 29+，与现在 `findConnectionOwner` 的实现相同）得到 uid 和包名，以对应的用户名连 mixed 入站（入站 `users` 为规则中出现的每个应用各生成一个随机凭据，另有一个默认凭据）；规则编译为 sing-box 的 `auth_user` 条件。只在规则用到应用时查询，Android 7–9 不支持（规则跳过并警告）；
  - mihomo 与 Xray 的其他目标：内核直接读 TUN，拿不到来源应用 → 生成配置时去掉，路由页与预览提示“此内核的应用规则只能直连，改用分应用代理”；
  - 用户配置文件自带的 `package_name`（sing-box，可同样改写为 `auth_user`）/`PROCESS-NAME`（mihomo，去掉并警告）。

### 4.5 内核的本地入站与鉴权

| 内核 | 控制 | 代理入站（探测、抓包、局域网、经代理下载） | DNS |
| --- | --- | --- | --- |
| sing-box | `services: [{type: api, listen: 127.0.0.1, listen_port: 随机, secret: 32 字节随机}]` | `mixed`（tag `fk-in`），`127.0.0.1:随机`，随机用户名密码；tun2socks 也用它（5），按应用的凭据见 4.4 | `dns.servers` 中 `local`/未指定 → Kotlin 给出的系统 DNS（`udp://<ip>`）；`hijack-dns` 规则处理 tun2socks 送来的 53 端口 |
| mihomo | `external-controller-unix: <dir>/ctl.sock`，`external-controller` 置空，`secret` 随机 | `mixed-port` 不用；`listeners: [{type: mixed, listen: 127.0.0.1, port: 随机, users: [随机]}]` | `nameserver`/`default-nameserver` 中的 `system`、`dhcp://` 换成系统 DNS（F1）；`dns-hijack` 保持 |
| Xray | `api: {tag: fk-api, listen: <dir>/api.sock, services: [StatsService, RoutingService, ObservatoryService]}`，`stats`/`policy` 开启入站与出站计数 | `socks` 入站 `127.0.0.1:随机`，`auth: password` 随机 | 配置自带 `dns`；53 端口规则同现在（`dns` 出站） |

- 端口随机选在 20000–60000、启动前探测可绑定；用户配置里已有同端口的入站时换一个。
- 用户配置中原有的 `clash_api`、`external-controller`、`api` 段：保留用户自己的监听（他们可能用外部面板），但若是 `0.0.0.0` 且无 secret，启动结果给出警告；不强行修改用户语义。

### 4.6 控制：`CoreController`

```rust
#[async_trait]
pub trait CoreController: Send + Sync {
    fn kind(&self) -> CoreKind;
    fn capabilities(&self) -> Capabilities;            // 界面据此隐藏不支持的能力
    async fn ready(&self) -> Result<()>;               // 就绪探测
    async fn reload(&self, config: &GeneratedConfig) -> Result<ReloadOutcome>; // 热重载或要求重启
    async fn groups(&self) -> Result<Vec<Group>>;
    fn watch_groups(&self) -> BoxStream<Result<Vec<Group>>>;
    async fn select(&self, group: &str, node: &str) -> Result<()>;
    async fn url_test(&self, group: &str) -> Result<()>;
    async fn mode(&self) -> Result<Option<ModeState>>;
    async fn set_mode(&self, mode: &str) -> Result<()>;
    fn watch_connections(&self) -> BoxStream<Result<ConnectionEvents>>;
    async fn close_connection(&self, id: &str) -> Result<()>;
    async fn close_all(&self) -> Result<()>;
    fn watch_traffic(&self) -> BoxStream<Result<Traffic>>;
    fn watch_logs(&self) -> BoxStream<Result<LogEntry>>;
    async fn memory(&self) -> Result<RuntimeMemory>;
    fn tools(&self) -> Option<&dyn SingBoxTools>;      // Tailscale/OpenConnect/OpenVPN/USBIP/Taildrop/诊断
}
```

| 能力 | sing-box（`api` gRPC） | mihomo（REST over UDS） | Xray（gRPC over UDS） |
| --- | --- | --- | --- |
| 分组、选节点 | `SubscribeGroups`、`SelectOutbound`、`SetGroupExpand` | `GET /proxies`、`PUT /proxies/{name}` | balancer → 组（`GetBalancerInfo`、`OverrideBalancerTarget`）；与现在“Xray 合并数组 → balancer 组”的展示一致 |
| 延迟测试 | `URLTest` | `GET /group/{name}/delay`、`/proxies/{name}/delay` | `GetOutboundStatus`（observatory 周期结果）；不支持按需测速 → 隐藏“测速”按钮，显示最近一次结果 |
| 模式 | `GetClashModeStatus`/`SetClashMode` | `PATCH /configs {mode}` | 不支持（隐藏） |
| 连接 | `SubscribeConnections`、`CloseConnection`、`CloseAllConnections` | `GET /connections`（WebSocket 或 1 秒轮询）、`DELETE /connections/{id}` | 无连接列表（隐藏连接页的列表与关闭，仅显示流量）；可选：`SubscribeRoutingStats` 做“最近路由决定”只读列表 |
| 流量 | `SubscribeStatus` | `GET /traffic`（流） | `QueryStats` 每秒轮询入站计数，按差值算速率 |
| 日志 | `SubscribeLog`、`GetDefaultLogLevel`、`ClearLogs` | `GET /logs?level=`（流） | 子进程 stdout/stderr |
| 内存 | `Status.memory` | `GET /memory` | `GetSysStats` |
| Tailscale/Taildrop/OpenConnect/OpenVPN/USBIP/网络质量/STUN/通知 | 对应 RPC | 不支持（隐藏） | 不支持（隐藏） |

- 实现：sing-box 与 Xray 用 `tonic` + `prost`，proto 由构建脚本从**同版本上游**取 `daemon/started_service.proto` 与 Xray 的 `command.proto`，生成代码不入库；CI 检查版本一致。mihomo 用 `hyper` + Unix 套接字。
- 现有的流规则（`mobile-ffi/runtime.rs`）、连接表（`client-core/connections.rs`）、调度（`sessions.rs`）不变，输入从 Kotlin 转发的 libbox 回调改为 `CoreController` 的流；sing-box 的 gRPC 消息与 libbox 命令客户端的回调本就同源，字段一一对应。
- 远程控制（连接另一台设备的 sing-box daemon）也改用同一 gRPC 客户端（TLS + secret，`rustls`），替代 `Libbox.newRemoteCommandClient`。
- 测试：每个实现配一个假服务端（tonic 服务端 / hyper UDS 服务端）跑在测试进程里，覆盖正常流、断流重连、鉴权失败、超时、版本不匹配。

### 4.7 配置管线（`client-core::config`）

1. 识别（现 `DetectCoreConfig`）→ 2. 数组拆分/合并（`SplitCoreConfigArray`、`core_array_merge.go`）→ 3. 分享链接转换（第 4 阶段，替代 mihomo `common/convert` 与 libXray `share`）→ 4. 规则编译（`core_routing*.go`）→ 5. 运行时改写（本节下表）→ 6. 内核校验（子进程 check 子命令）→ 7. 写入工作目录并启动。

- 第 6 步不在 Rust 里重写各内核的解析器：调用同一个二进制的检查模式，以内核自己的结果为准。
  - sing-box：`check -c`；`format -c` 替代 `FormatConfig`。
  - Xray：`run -test -c`。
  - mihomo：`-t -d <dir> -f`。
  - 导入时的轻量检查（现 `CheckCoreConfig` 对 Xray/mihomo 只逐个解析出站）改为同样调用检查模式；Xray 的检查需要 geo 文件时给出“缺少地理数据”的明确错误（与现在“geo 文件不存在时不构建路由”不同，属于行为变化，记入第 2 阶段 PR）。
- 第 5 步运行时改写（不写回磁盘上的用户配置）：

| 内核 | 改写 |
| --- | --- |
| sing-box | 删除所有 `tun` 入站，加 mixed 入站（4.5）与 `api` 服务；`route.rules` 最前加 `{"inbound":"fk-in","port":53,"action":"hijack-dns"}` 与 `sniff`；`route.auto_detect_interface`、`default_interface`、`default_network_strategy`、`package_name`/`wifi_*` 条件移除（F4，警告）；`local` DNS 服务器替换为系统 DNS；默认日志级别规则沿用 `core_loglevel.go`；`experimental.cache_file.path` 指向工作目录；`experimental.http_rewrite` 交给抓包层（第 7 阶段）并从内核配置删除 |
| mihomo | 强制 `tun` 段（4.3）；`external-controller-unix`、`secret`；去掉 `external-controller` 的 0.0.0.0 无 secret 监听以外的不动；`find-process-mode: off`；`system`/`dhcp://` DNS 替换（F1）；`log-level` 规则沿用；`geodata`/`geox-url` 指向共享 Geo 目录 |
| Xray | 入站换成 `tun` + `fk-api` + 代理 socks 入站；53 端口 → `dns` 出站规则放最前；`api`/`stats`/`policy`；`log.access` 规则沿用；`XRAY_LOCATION_ASSET` 指向共享 Geo 目录 |

- 黄金样本：第 2 阶段把 `core_*_test.go` 的输入/期望输出提取为 `crates/client-core/tests/golden/` 下的 JSON/YAML 文件（脚本从 Go 测试生成，生成脚本与样本一起入库），Rust 输出必须逐字节一致（JSON 比较规范化后的值，YAML 比较解析后的值并另比文本）。

### 4.8 Geo 资源

- 下载、条件请求、SHA-256 校验、格式校验（protowire `.dat` 帧、MMDB 头）从 `core_geo.go` 迁到 Rust（`geodata/fetch.rs`），HTTP 用 `reqwest` + `rustls` + `rustls-platform-verifier`（经 JNI 用 Android 证书库，需要 Kotlin 在启动时调用一次其初始化）。
- 内核读取：mihomo `-d` 指向共享目录（`<filesDir>/cores/geo`）；Xray `XRAY_LOCATION_ASSET`；sing-box 仍用远程规则集。
- “替换后让 mihomo 重读”（现 `mmdb.ReloadIP`）改为：运行中更新后对 mihomo 调 `PUT /configs?force=true` 重新载入当前配置（不用 `POST /configs/geo`，它会按 `geox-url` 再下载一次），Xray 下次启动生效。

### 4.9 网络变化、DNS、系统代理

- `DefaultNetworkMonitor` 的回调经 mobile-ffi `network_recovery.rs` 判断后：
  - sing-box：`CloseAllConnections`（替代 libbox 的接口监视重置）；
  - mihomo：`DELETE /connections`，DNS 有变化时 `PATCH /configs` 更新 `nameserver`（或重载）；
  - Xray：无连接关闭接口 → 不处理（与 Xray 本身行为一致，记入能力差异）。
- 系统 DNS 来自 `LinkProperties.dnsServers`，私有 DNS（DoT）开启时仍用其解析出的服务器地址。
- VpnService 的 HTTP 代理（`setHttpProxy`）指向内核代理入站时，必须用无认证的单独入站且只监听 127.0.0.1——这会被同机其他应用使用。默认关闭，界面说明风险。

### 4.10 服务检测、出口探测、局域网、DNS 测试

- 出口 IP、IP 质量、服务检测（`ui_egress.go`、`ui_service_check.go` 的 13 项规则）迁到 Rust（`client-runtime::probe`），HTTP 客户端两种传输：直连（服务停止时）和经内核代理入站（运行时，SOCKS5/HTTP CONNECT 带认证）。运行中失败不回退直连（保留现规则）。
- 局域网代理：`ui_lan.go` 的监听解析与开关迁到 Rust 配置层；设备接口地址由 Kotlin（`LinkProperties`/`NetworkInterface`）经 JNI 提供，Rust 不读 netlink。
- DNS 查询计数：sing-box 从连接流中 DNS 类连接计数（tun2socks 送来的 53 端口经 `hijack-dns`，会作为连接出现）；mihomo 与 Xray 无数据 → 仪表盘隐藏该项（DNS 计数补丁去掉）。
- 网络质量、STUN、Tailscale Ping：sing-box 运行时走 gRPC；服务停止或其他内核时：STUN 用 Rust 实现（RFC 5389/5780，代码量小），网络质量测试隐藏。

### 4.11 抓包、MITM、HTTP 改写（第 7 阶段，先设计）

- 形态：Rust 本地 HTTP(S) 代理层 `client-runtime::capture`，位于“流量入口”和内核代理入站之间：
  - sing-box：tun2socks 把命中筛选的 TCP 流交给抓包层，抓包层再连内核 mixed 入站；
  - mihomo/Xray：内核直接读 TUN，抓包层无法插在前面 → 用内核规则把命中端口/域名的流量路由到抓包层监听的 `socks`/`http` 上游出站：mihomo `proxies` 加一个 `socks5` 节点指向抓包层，规则前置；Xray 加 `socks` 出站。抓包层处理后再经内核的另一个代理入站出去，入站打标签避免回环。
- 能力：HTTP 记录、HTTPS MITM（rustls 服务端 + 上游 rustls 客户端，先连上游、验证证书后再签发，沿用 mitmproxy 的上游优先握手）、`http_rewrite` 全部规则（Map Remote/Local、Header/URL/Body Rewrite）、JavaScript 脚本（候选 `boa_engine` 或 `rquickjs`，第 7 阶段评估体积与沙箱限制后决定）、HAR/cURL 导出、域名提取、QUIC 阻断（sing-box 经 tun2socks 丢弃 UDP 443；mihomo/Xray 用规则 `REJECT` UDP 443 命中的域名）。
- 签发：根私钥仍在 Android Keystore，Rust 经 JNI 调签名（与现在 `ui_capture_signer.go` 的回调相同语义）。
- 应用筛选：sing-box 路径由 tun2socks 按 4.4 查询归属后决定是否交给抓包层（API 29+）；mihomo/Xray 路径拿不到来源应用，**应用筛选不可用**，只保留端口与域名筛选（记入能力差异）。
- `experimental.http_rewrite` 字段：从 sing-box 配置迁出到配置元数据的独立字段（迁移一次，原文件保留备份），因为原版 sing-box 拒绝未知字段。

### 4.12 Kotlin 剩余职责

VpnService 建站与交 fd、前台服务与通知、磁贴、开机启动、默认网络回调与 `LinkProperties`、应用列表与图标、相机扫码、OpenConnect WebView、USB 设备枚举与 URB 读写（数据经 Rust gRPC 流给 sing-box）、文件选择、Keystore（WebDAV 密码、MITM 签名）、Root/Shizuku（应用查询、终端）、WorkManager 入口、JNI 宿主。其余 Kotlin 业务删除（第 8 阶段清单见第 7 节）。

## 5. sing-box 的 TUN 方案

### 5.1 首选：Rust tun2socks + mixed 入站

```
TUN fd ─► Rust 用户态栈 ──TCP──► 127.0.0.1:mixed（CONNECT/SOCKS5，带认证）─► sing-box 路由/嗅探/出站
                         └─UDP──► SOCKS5 UDP ASSOCIATE ─────────────────────► （53 端口 → hijack-dns）
```

- 候选：`tun2proxy`（基于 `ipstack`，支持 SOCKS5 UDP、虚拟 DNS、Android fd，MIT）、直接用 `ipstack`、`smoltcp`（自管缓冲，CPU 较高）。第 5 阶段先做基准再定。
- DNS：VpnService 的 DNS 地址（如 `172.19.0.2`）的 53 端口查询经 UDP ASSOCIATE 送入，由 `hijack-dns` 交给 sing-box DNS；fake-ip 照常可用。tun2proxy 自带的虚拟 DNS 不用。
- 嗅探：sing-box 在 mixed 入站上照常 `sniff`（TLS SNI、HTTP Host、QUIC）。
- 已知损失：ICMP（ping）不经代理（SOCKS 不支持；现在 sing-box TUN 可代理 ICMP echo）；`auto_redirect`（Root）与 `sing-tun-android-ingress.patch` 失去作用；连接页的“来源应用”只在规则或抓包需要查询归属时才有（4.4）。

### 5.2 验收指标（与当前 libbox TUN 同机对比）

| 项目 | 方法 | 达标线 |
| --- | --- | --- |
| TCP 吞吐 | iperf3 经 direct 出站到局域网服务器，上下行各 30 秒 ×3 | ≥ 当前的 80% |
| UDP | iperf3 `-u` 100 Mbps；QUIC 下载（Chrome） | 丢包 ≤ 当前 +1 个百分点；QUIC 可用 |
| 延迟 | 局域网 TCP RTT p50/p99 | p50 增加 ≤ 1 ms |
| CPU | `/proc/<pid>/stat` 每 100 Mbps 的 CPU 秒 | ≤ 当前的 130% |
| 内存 | 空载与 1,000 并发连接时两个进程 RSS 之和 | ≤ 当前 +30 MiB |
| 嗅探 | 按域名规则分流 HTTPS/QUIC | 全部命中 |
| fake-ip | fake-ip 配置浏览、应用连接 | 正常 |

### 5.3 不达标时

写明哪一项未达标和测量数据，然后在下列替代方案中提出建议，**等维护者确认后再做**：

- A. **官方原版 `libbox.aar`**（不打补丁、不加自写 Go），运行在独立 `:singbox` 进程的 Kotlin 服务里，`PlatformInterface.openTun` 交 fd、`protect` 不需要（自身已排除）。优点：完整 TUN、ICMP、接口监视、`package_name`、NaiveProxy（libbox 标签含 `with_naive_outbound`）；缺点：保留 gomobile 与少量 Kotlin 胶水（与第 8 阶段“删除 gomobile”冲突，只删自写部分）。
- B. Root 设备上用命令行版的 `tun` 入站（`auto_route`），非 Root 设备用 5.1。两套路径，测试成本高。
- C. 换用另一个 Rust 栈（如 `smoltcp` 自行优化）继续 5.1。

## 6. 功能去向表

“阶段”为承担迁移或删除的阶段。“去掉”项在界面上隐藏或给出明确提示，不静默失效。

### 6.1 Go `apps/android/core`

| 文件 | 功能 | 去向 | 阶段 |
| --- | --- | --- | --- |
| `core_profile.go`（识别、校验、格式化、链接转换、订阅 UA） | `DetectCoreConfig`、`ResolveProfileCore` | 迁 Rust（`config/detect.rs`） | 2 |
| 同上 | `CheckCoreConfig`、`FormatCoreConfig` | 改为内核 check/format 子命令（4.7） | 3–5 |
| 同上 | `ConvertCoreShareLinksFor`（mihomo `common/convert` + libXray `share`） | 迁 Rust（`config/share/`），字段映射沿用 serenity/现实现 | 4 |
| 同上 | `CoreSubscriptionUserAgent` | 迁 Rust | 2 |
| `core_array.go`、`core_array_merge.go` | 数组拆分、Xray 合并 | 迁 Rust | 2 |
| `core_routing.go`、`core_routing_{singbox,xray,mihomo}.go` | 规则编译、列表下载与解析、目标列表 | 迁 Rust（`rules/compile/`）；应用条件按 4.4 处理 | 2 |
| `core_routing_owner.go` | mihomo 包名回调 | 去掉（F1、4.4） | 3 |
| `core_geo.go` | Geo 下载与校验 | 迁 Rust（4.8） | 2 |
| `core_loglevel.go` | 默认日志级别 | 迁 Rust 配置生成 | 2 |
| `core_service.go`、`core_api.go`、`core_extra.go` | 多内核服务、StartedService 包装 | 去掉，由 supervisor + `CoreController` 取代 | 3–5 |
| `core_xray_track.go` | Xray 连接跟踪 | 去掉（Xray 无连接列表，4.6） | 4 |
| `core_tunstack.go` | TUN 栈选择 | 迁 Rust 配置生成（mihomo 固定 gvisor） | 3 |
| `core_memory.go` | THP、FreeOSMemory | THP 由 `pre_exec` 设置；FreeOSMemory 去掉（子进程退出即释放） | 3 |
| `core_dns_cmfa.go`、`core_dns_other.go`、`dns/`、`ui_dns.go`、`scripts/patch_dns_count.py` | DNS 计数补丁 | 去掉；sing-box 用连接流计数（4.10） | 8 |
| `core_link.go`、`core_version.go` | 链接内核、版本 | 去掉；版本取各二进制 `version` 子命令 | 8 |
| `ui_dashboard.go` | Go 运行时内存 | 改由控制接口（4.6） | 3–5 |
| `ui_http.go`、`ui_subscription.go` | HTTP/WebDAV/订阅请求 | 迁 Rust（`reqwest`） | 2 |
| `ui_subscription_rewrite.go` | 订阅中的 `http_rewrite` | 迁 Rust（与 7 阶段字段迁移一起） | 7 |
| `ui_egress.go`、`ui_service_check.go` | 出口与服务检测 | 迁 Rust（4.10） | 6 |
| `ui_lan.go` | 局域网入站 | 迁 Rust（4.10） | 6 |
| `httpdebug/`、`route/httpdebug.go`、`option/http_rewrite.go`、`ui_capture*.go`、`ui_http_rewrite.go`、`httpdebug.patch`、`http_rewrite.patch`、`scripts/patch_httpdebug.py`、`verify_httpdebug.py` | 抓包、MITM、改写、JS | 迁 Rust（4.11）；应用筛选只在 sing-box 路径保留 | 7 |
| `sing-tun/`、`sing-tun-android-ingress.patch`、`scripts/patch_sing_tun.py` | Root 自动重定向兼容 | 去掉（命令行版不跑 TUN 入站） | 8 |
| `libbox-no-legacy.patch`、`scripts/patch_multicore.py`、`verify_multicore.py`、`core_grpc_passthrough.txt` | 构建与多内核补丁 | 去掉 | 8 |

### 6.2 libbox 客户端层能力（Kotlin 经 `Libbox.*` 使用，非自写 Go）

| 能力 | 去向 | 阶段 |
| --- | --- | --- |
| 命令客户端（本地/远程，`CommandClient`、`newStandalone*CommandClient`） | Rust gRPC 客户端（4.6） | 3–5 |
| `PlatformInterface`：`openTun`、`autoDetectInterfaceControl`、`findConnectionOwner`、默认接口监视、`getInterfaces`、`localDNSTransport`、`readWIFIState` | 去掉（4.3、4.4、4.9；`wifi_ssid` 规则去掉） | 5 |
| Tailscale 状态/Ping/出口节点/SSH 会话/Taildrop | sing-box gRPC | 5 |
| 平台 Shell（Tailscale SSH 服务端用）、`readSystemSSHHostKey`、`lookupSFTPServer` | 去掉：命令行版没有平台接口，设备作为 Tailscale SSH 服务端的能力不可用（客户端 SSH 会话仍走 gRPC） | 5 |
| `openNativeShellSession`/`openNativePipeSession`（终端 PTY） | Rust（`nix::pty`），Root/Shizuku 会话仍由 Kotlin 提供进程 | 6 |
| OpenConnect 认证表单、OpenVPN 挑战 | sing-box gRPC | 5 |
| USB/IP（`newUSBDeviceDescriptor`、`newUSBURBResponse`） | Kotlin USB 读写 + Rust gRPC 双向流 | 5 |
| 网络质量、STUN | 4.10 | 6 |
| `newAutoRedirectService`（Root 自动重定向）、`newBridgeService`（Root 桥接）、`subscribeNeighborTable` | 去掉（依赖 libbox 平台层与 sing-box 进程内接口） | 5 |
| `createZipArchive`（报告打包，可加密给 sing-box 维护者） | Rust `zip`；加密选项去掉（其收件人只对上游 libbox 有意义） | 6 |
| `parseRemoteProfileImportLink`、`decodeProfileContent`（`.bpf`） | 迁 Rust（格式在 `experimental/libbox/profile_import.go`、`remote_profile.go`，按同版本源码实现并以样本测试） | 6 |
| `formatBytes`、`formatNATMapping`、`proxyDisplayType`、`version` | 迁 Rust | 6 |
| `setup`、`reloadSetupOptions`、`setLocale` | 去掉 | 8 |

### 6.3 Kotlin

| 文件/区域 | 去向 | 阶段 |
| --- | --- | --- |
| `bg/PlatformInterfaceWrapper.kt`、`bg/LocalResolver.kt`、`bg/IntArrayIterator.kt` | 删除 | 8 |
| `bridge/BoxStreams.kt`、`bridge/BoxToolStatus.kt`、`bridge/BoxWire.kt`、`bridge/BoxDiagnostics.kt`、`utils/CommandClient.kt`、`utils/CommandTarget.kt`、`utils/RemoteControlManager.kt`、`backend/tools/*Status.kt`、`backend/tools/Taildrop*.kt`、`backend/usbip/USBIPStatus.kt`、`ktx/Wrappers.kt` | 删除（数据由 Rust 控制器直接给界面） | 8 |
| `bg/BoxService.kt` | 只剩前台服务、通知、建站请求与 JNI 调用；启动/重载/停止交给 Rust supervisor | 3–5 |
| `bg/VPNService.kt`、`bg/VpnRouting.kt` | 保留；加入自身排除与 fd 交给 Rust | 3 |
| `bg/GeoResources.kt`、`database/CoreKernel.kt`、`database/ProfileContent.kt`、`bridge/BoxProfiles.kt`、`bridge/DashboardConfigCache.kt` | 内核调用改 Rust 后删除或缩到平台部分 | 2–6 |
| `bridge/BoxCapture.kt`、`bridge/MitmCertificate*.kt`、`bridge/CaptureSettings.kt` | Keystore 与文件选择留 Kotlin，其余迁 Rust | 7 |
| `bg/RootServer.kt` | 保留 Root 应用查询与终端；自动重定向、桥接、邻居表删除 | 5、8 |
| `terminal/*`、`usbip/*` | 保留平台部分 | — |

### 6.4 去掉的能力汇总（需维护者确认）

NaiveProxy 出站（F2）；mihomo 与 Xray 的应用包名路由（只能“直连 = 排除出 VPN”），sing-box 的应用路由需 Android 10+；`wifi_ssid`/`wifi_bssid` 规则；Root 自动重定向与 Root 桥接；mihomo 与 Xray 的 DNS 计数；Xray 的连接列表、按需测速与模式；mihomo 与 Xray 路径的抓包应用筛选；sing-box 经代理的 ICMP；设备作为 Tailscale SSH 服务端；sing-box 网络切换时的自动重连（改为关闭全部连接）；报告“为 sing-box 维护者加密”。

## 7. 第 8 阶段删除清单

`apps/android/core/` 整个目录；`.github/actions/setup-core`、`build-libbox`；`scripts/patch_*.py`、`verify_multicore.py`、`verify_httpdebug.py`；`apps/android/app/libs/libbox.aar` 的消费（Gradle 依赖与 R8 规则）；6.3 中标为删除的 Kotlin 文件；`docs/libbox.md` 改写为“内核构建与配置生成”。AGENTS.md、app.md、newui.yml、build-release.yml、`bump_core.py` 同步更新。每个删除在 PR 描述中给出行数（Go、Kotlin 删除行，Rust 新增行）。

## 8. 风险

| 风险 | 影响 | 缓解 / 验证 |
| --- | --- | --- |
| Android phantom process 管理或厂商 ROM 杀子进程 | 断网 | 崩溃重启 + 断网保护；实机覆盖原生 Android 14/15、MIUI/HyperOS、ColorOS、魅族 Flyme |
| `nativeLibraryDir` exec 在某些 ROM/SELinux 策略下被拒 | 内核起不来 | 启动前 `access(X_OK)` 检查，失败给出明确错误；实机矩阵 |
| tun2socks 性能（5.2） | sing-box 体验下降 | 基准 + 5.3 替代方案 |
| 两个进程内存总和上升 | 低内存设备 | 第 3 阶段实测 RSS；`GOMEMLIMIT`；UI 进程不再含 Go 运行时 |
| APK 体积：三个独立二进制不共享 Go 运行时与公共依赖 | 下载变大 | `arch/kernel-isolation-p0` 的 CI 实测（当时基线）：68,202,058 B → 80,941,574 B（官方三件套，未签名）；第 3 阶段起每个 PR 记录实测值。可选 UPX 不采用（影响 exec 与安全扫描） |
| Rust 依赖体积（tonic、prost、hyper、rustls、reqwest、JS 引擎） | `libfkui.so` 增大 | 共用一套 hyper/rustls；`opt-level = "s"` 与 LTO 已开；每阶段记录 `libfkui.so` 大小 |
| 本地端口被其他应用访问 | 绕过分应用设置、读到连接信息 | 4.5（UDS、随机 secret 与认证） |
| mihomo linux 构建在 Android 上的差异（DNS、时区、`/proc` 访问） | 解析失败、日志时间错 | F1 的替换；第 3 阶段测试 `system` DNS、IPv6、Wi-Fi/移动切换 |
| 上游 proto 变化 | gRPC 客户端失配 | proto 从同版本标签取并在 CI 生成；版本一致性检查 |
| 黄金样本覆盖不足 | Rust 与 Go 输出偏差 | 第 2 阶段以全部现有 Go 用例为样本，另加订阅样本集；差异逐条列出 |
| 子进程崩溃丢失最后日志 | 难排查 | 管道按行实时读入环形缓冲，崩溃时落盘 |
| 旧版数据（`http_rewrite` 字段、抓包设置） | 升级后配置被内核拒绝 | 一次性迁移并保留原文件；第 7 阶段测试降级 |

## 9. 验证方法

- **每个 PR**：`cargo test`（新 Rust 代码都有单元测试；进程管理与控制客户端用假服务端/假二进制测试：假二进制是测试内编译的小程序，模拟就绪、崩溃、忽略 SIGTERM、大量输出）、`cargo clippy -D warnings`、`newui.yml`、`build-debug-apk.yml`；CI 基础设施失败重跑一次，同一失败两次即停止并报告。
- **内核构建**：每个内核源码树 `git status --porcelain` 为空；ELF 架构与版本检查。
- **模拟器探针**（沿用 `arch/kernel-isolation-p0` 的 `kernel-feasibility.yml` 思路，x86_64 模拟器 + amd64 内核）：exec、fd 传递、启停与崩溃重启、端口/fd 释放、控制接口可用。
- **实机清单**（arm64，每阶段列出适用项）：三内核启动/重载/切换/停止；崩溃自动重启；20 次启停后 fd 数（`/proc/<pid>/fd`）与 RSS 不增长；Wi-Fi/移动切换；分组、测速、连接、日志、仪表盘；分应用与规则分流；Tailscale、Taildrop、USB/IP、OpenConnect、OpenVPN；抓包/MITM/改写与 QUIC 回退；旧配置与备份迁移；OOM 报告；断网保护。
- **体积与内存**：每阶段 PR 记录 APK 大小、`libfkui.so` 大小、空载与负载时各进程 RSS（`dumpsys meminfo` + `/proc/<pid>/status`），与上一阶段比较。

## 10. 阶段计划

| 阶段 | 内容 | 运行时 | 主要验证 |
| --- | --- | --- | --- |
| 1 | 本文 | — | 维护者审阅 |
| 2 | 配置识别/数组/规则编译/订阅/Geo/日志级别迁 `client-core`，Go 测试为黄金样本 | 仍是 libbox | 黄金样本逐字节一致；现有 CI |
| 3 | mihomo 独立进程：构建 Action、supervisor、fd 传递、Clash API 客户端、仪表盘与节点页；设置开关与旧路径并存（默认旧路径） | mihomo 可选新路径 | 假服务端测试；模拟器探针；实机 mihomo 清单 |
| 4 | Xray 独立进程；分享链接转换迁 Rust（替代 libXray） | Xray 可选新路径 | 分享链接黄金样本；Xray 清单 |
| 5 | sing-box 独立进程 + tun2socks 基准；gRPC 接入 Tailscale/OpenConnect/OpenVPN/USBIP；未达标按 5.3 报告 | sing-box 可选新路径 | 5.2 指标；工具类实机 |
| 6 | 出口/服务检测、局域网、STUN、报告打包、`.bpf`/导入链接、终端 PTY 迁 Rust | 新路径为默认 | 现有用例迁移为 Rust 测试 |
| 7 | Rust 抓包/MITM/改写层（4.11） | — | 现 `httpdebug`/`mitm` 用例改写为 Rust 测试；实机抓包 |
| 8 | 删除 gomobile、libbox.aar、`apps/android/core`、补丁、Kotlin 转换层；文档更新 | 只有新路径 | 全部清单；删除行数统计 |

## 11. 需要维护者确认的事项

1. F1：mihomo 采用官方 **linux/arm64 静态构建**（而非其 Android 构建），并固定 `stack: gvisor`。
2. F2 与 6.4：去掉的能力清单，尤其 NaiveProxy、mihomo/Xray 的应用包名路由（只保留“直连 = 排除出 VPN”）与 Root 自动重定向；sing-box 的应用路由改用 tun2socks 归属查询 + `auth_user`（4.4）。
3. F3：sing-box 控制全走 `api` gRPC，不开 Clash API；`libfkui.so` 因此加入 `tonic`/`prost`（推翻 [phase3.md](phase3.md) 3c“暂不做 Rust gRPC”的结论）。
4. 4.4：应用自身排除出 VPN 后，订阅等下载默认经内核代理入站（运行时），停止时直连。
5. 4.11：抓包的应用筛选只在 sing-box（Android 10+）保留；`http_rewrite` 从 sing-box 配置迁出为独立字段。
6. 第 3 阶段的设置开关默认走旧路径，第 6 阶段切换默认，第 8 阶段删除旧路径。
