# 内核拆分：三个独立的进程内库，每个进程只加载一个

状态：设计草案（2026-11-23，分支 `docs/split-kernels`），未实施，未合并。证据见 [§11](#11-证据)。

## 1. 目标与边界

目标是**隔离内核**：sing-box、mihomo、Xray 各自编成一个独立的共享库 `libsingbox.so`、`libmihomo.so`、`libxray.so`（`-buildmode=c-shared`），源码是未打补丁的上游发布版本，依赖版本就是该内核自己 `go.mod` 的版本；唯一新增的 Go 是每个内核一个很小的 cgo 导出层（shim）。

- **不建子进程**：任何地方都不 `fork`/`exec`，内核以库的形式运行在应用进程内，由 Rust 直接调用。
- **每个进程最多一个内核库**：Go 不支持同一进程内多个 Go 运行时（[golang/go#18976](https://github.com/golang/go/issues/18976)、[#65050](https://github.com/golang/go/issues/65050)，见 §11.4），c-shared 库也不能卸载。切换内核 = 重启应用进程（§4）。
- **内核只做最基本的代理**：TUN 入站、路由（分流规则匹配）、出站协议、隧道内 DNS 解析与 FakeIP。其余全部归 Rust；Kotlin 只保留 Android 平台代码。
- 只支持 arm64-v8a。

| 能力 | 归属 | 说明 |
| --- | --- | --- |
| TUN 读写、协议栈（gVisor/system） | 内核 | TUN fd 由 Kotlin `VpnService.Builder` 建立 |
| 路由规则匹配、出站协议、代理组选择/自动测速 | 内核 | 规则内容由 Rust 生成 |
| 隧道内 DNS（远程/直连服务器、FakeIP、hijack） | 内核 | 配置段由 Rust 按统一 DNS 设置生成（§6） |
| 本地代理入站（mixed/socks）与只读控制接口 | 内核 | 各内核自带的控制接口，监听本地 socket（§3.2） |
| 配置识别、生成、格式化、分享链接转换、订阅下载与解析 | Rust | 取代 libbox 的 `DetectCoreConfig`、`ConvertCoreShareLinksFor` 等 |
| 分流规则编译（三种语法）、规则列表下载与缓存 | Rust | 取代 `core_routing*.go` |
| Geo 资源下载、校验、更新 | Rust | 取代 `core_geo.go`；MMDB/ASN/dat 校验在 Rust 做 |
| 生命周期、监督、失败恢复、内核切换 | Rust | §4 |
| 流量/连接统计聚合、日志收集、仪表盘 | Rust | 从内核控制接口拉取，聚合在 Rust |
| 出口 IP、网络质量、服务检测、STUN、延迟测试（非内核内建的） | Rust | 经内核的本地代理入站发出，走内核路由 |
| HTTP 抓包、MITM、HTTP 改写 | Rust 或删除 | 见开放决策 O5 |
| 备份/恢复、WebDAV、更新检查、报告（崩溃/OOM/功耗） | Rust | 功耗/OOM 报告是 libbox 服务，删除（§7） |
| VpnService、前台服务与通知、权限、Keystore、包管理器查询、默认网络回调 | Kotlin | 经 Rust 回调给内核 |

## 2. 进程布局

**现状（源码核实，main `37e80bc1`）**：`AndroidManifest.xml` 里没有任何 `android:process`，界面 `compose.MainActivity`（`android.app.lib_name = fkui`）、`.bg.VPNService`、`.bg.ProxyService` 都在同一个主进程。`Application.onCreate` 在 IO 线程加载 libbox 并 `Libbox.setup`（`Application.kt:69-71,139-143`），`libfkui.so` 由 `CoreStore.kt:41` 的 `System.loadLibrary("fkui")` 加载。Kotlin 有 31 个文件、约 300 处直接调用 `Libbox.*`。

**主方案：单进程。**

```
应用进程（zygote 启动，唯一进程）
├── Kotlin：Activity、VPNService/ProxyService、通知、权限、回调实现
├── libfkui.so（Rust）：UI、client-core、mobile-ffi、内核宿主（kernel-host）
└── 按需 dlopen 的一个内核库：libsingbox.so | libmihomo.so | libxray.so
```

- 启动时不加载任何 Go 库；Rust 在第一次需要内核（启动 VPN 或校验当前内核的配置）时 `dlopen` 当前配置对应的那一个，此后本进程只能使用它。
- 想用另一个内核（启动时选中的配置属于另一内核，或用户切换）时，Rust 走 §4 的进程重启。
- 不在同一进程内同时校验另一个内核的配置：非当前内核的配置只做 Rust 侧结构检查，真正的内核校验在下次以该内核启动时进行（开放决策 O3）。

**开放决策 O1：单进程 vs 独立服务进程。** 现在的应用就是单进程，独立服务进程（`android:process=":core"`）是新增结构，用户可能视为“子进程”，因此不是主方案。

| | 单进程（主方案） | 独立服务进程 `:core`（manifest 声明，由系统 zygote 启动，非 fork/exec） |
| --- | --- | --- |
| 切换内核 | 重启整个应用，界面冷启动（约 1–3 s，待实机测） | 只重启 `:core`，界面不断开 |
| 内核崩溃（如 mihomo `log.Fatalln`） | 界面一起退出 | 界面存活并显示错误 |
| 界面与内核通信 | 进程内直接调用 | 需要 AIDL/Binder 或本地 socket，状态要跨进程同步 |
| 内存 | 一份 Rust 运行时 | 两份 Rust 运行时与 SQLite 连接，需处理多进程写库 |
| 改动量 | 小：保持现有布局 | 大：拆分 Kotlin 服务与 Rust 状态 |

## 3. 每个内核的 C ABI

### 3.1 导出函数（三个库完全相同）

完整定义见 [probe/split-kernels/include/fk_kernel.h](../../probe/split-kernels/include/fk_kernel.h)：

```c
int32_t fk_init(const fk_host *host, const char *home_dir);  // 一次：回调表与工作目录
int32_t fk_check(const char *config, int32_t len);           // 用内核自己的解析器构建一遍再释放
int32_t fk_start(const char *config, int32_t len, int32_t tun_fd); // 运行中再调用即重载
int32_t fk_stop(void);                                       // 关闭所有监听、连接与 TUN
int32_t fk_network_changed(const char *json);                // 默认网络变化 {index,name,dns}
char   *fk_version(void);  char *fk_last_error(void);  void fk_free(char *);
```

`fk_host` 是 Rust 提供的回调表：`protect(fd)`（VpnService.protect）、`open_tun(options_json, *fd)`（仅 sing-box：按内核算出的地址/路由/分应用建 TUN）、`find_owner(...)`（连接归属 uid 与包名）、`interfaces(buf)`（仅 sing-box：网卡列表 JSON）。

| 内核 | 用到的回调 | 内核内接入点（上游公开 API，无补丁） |
| --- | --- | --- |
| sing-box | protect、open_tun、find_owner、interfaces | 自实现 `adapter.PlatformInterface` 放进 `box.New` 的 context；默认网络监视器为 `tun.DefaultInterfaceMonitor`，由 `fk_network_changed` 驱动 |
| mihomo | protect、find_owner | `cmfa` 构建标签；`dialer.DefaultSocketHook`；`process.DefaultPackageNameResolver`；`tun.file-descriptor`；`dns.UpdateSystemDNS` |
| Xray | protect | `internet.RegisterDialerController`；`xray.tun.fd` 环境变量；`xray.location.asset` |

shim 规模（probe 实测，含注释）：sing-box `main.go` 159 行 + `platform.go` 310 行（其中约一半是 PlatformInterface 的“不支持”桩与默认网络监视器）；mihomo 214 行；Xray 150 行。

### 3.2 查询走内核自带的控制接口，不加 C 函数

Rust 需要的运行时数据都已有上游接口，C ABI 不再增加查询函数：

| 数据 | sing-box | mihomo | Xray |
| --- | --- | --- | --- |
| 接口 | Clash API（`experimental.clash_api`），只能监听 TCP，用 `127.0.0.1:随机端口` + secret | 外部控制器 `external-controller-unix`（unix socket） | `api` gRPC，`listen` 为 unix socket 路径 |
| 流量 | `/traffic` | `/traffic` | `StatsService`（Rust 生成 `stats` + `policy` 计数配置） |
| 连接列表/关闭 | `/connections` | `/connections` | 无，删除（§7） |
| 代理组、选择节点 | `/proxies`、`PUT /proxies/{组}` | 同左 | `RoutingService.GetBalancerInfo/OverrideBalancerTarget` |
| 延迟测试 | `/proxies/{名}/delay` | 同左 | `ObservatoryService.GetOutboundStatus` |
| 日志 | `/logs` | `/logs` | 配置 `log.error` 写文件，Rust 读取 |
| 模式切换 | `/configs` | `/configs` | 无 |

## 4. 启动、切换与重启

启动（任一内核）：Rust 读 `client.db` → 生成该内核配置（含内部 mixed 入站与控制接口，§5）→ `dlopen` → `fk_init` → Kotlin 建 TUN（sing-box 由 `open_tun` 回调触发；mihomo/Xray 由 Rust 按生成的地址先请求 Kotlin 建立再把 fd 传给 `fk_start`）→ `fk_start` → Rust 连接控制接口。启动前 Rust 设置 `GOMEMLIMIT`（Go 运行时在初始化时读取）并执行 `prctl(PR_SET_THP_DISABLE)`，取代 libbox 的 `ReloadSetupOptions` 与 `core_memory.go`。

同一内核内重载：`fk_start` 新配置（probe 已验证同进程内 stop→start 两轮，端口释放）。

切换到另一内核（只能重启进程，**没有 fork/exec**）：

1. Rust 把“下一个内核 + 启动后恢复 VPN”写入 `client.db`（原子写入，现有修订检查不变）。
2. Rust 调 `fk_stop`，Kotlin 关闭 TUN、`stopForeground`。
3. Kotlin 用 `AlarmManager.set(ELAPSED_REALTIME, now+300ms, PendingIntent.getActivity(启动 Activity))` 登记重新启动，再 `Process.killProcess(myPid())`。
4. 系统按 PendingIntent 由 zygote 新建应用进程，Rust 读到恢复标记，`dlopen` 新内核并启动 VPN（此时有可见 Activity，可以 `startForegroundService`）。

预计断网时间 = 停止（probe 中 `fk_stop` ≤2 ms）+ 进程冷启动与 Rust 初始化 + 新内核启动（mihomo 远程 provider 最多数十秒，Rust 在切换前预下载）。冷启动部分**未经实机测量**，阶段 1 的验收项之一就是在设备上测出这一时间。从磁贴或通知切换时没有可见 Activity：依赖已申请的“忽略电池优化”豁免启动前台服务，需实机验证（风险 R4）。

## 5. Rust 归属

- `crates/kernel-host`（新）：`KernelHost` trait（`load/init/check/start/stop/network_changed/version`），真实实现用 `libloading` 加载 `nativeLibraryDir` 下的库，测试用 `FakeKernel`；单元测试覆盖“已加载 A 时请求 B 必须走重启”“启动失败回退到上次成功配置”“控制接口断开后的监督”。
- `crates/kernel-api`（新）：Clash API 客户端（sing-box、mihomo 共用）与 Xray gRPC 客户端（tonic，unix socket），产出统一的流量/连接/分组/日志模型给现有 `mobile-ffi/runtime.rs`。
- `client-core/config`：三种内核的配置生成器（统一 DNS、入站、控制接口、日志、分流规则）；订阅解析与分享链接转换（取代 mihomo `common/convert` 与 libXray `share`，开放决策 O4）。
- 每份生成的配置都含一个仅 Rust 使用的内部入站（`127.0.0.1` 随机端口 + 随机用户名密码），出口 IP、服务检测、网络质量、订阅（运行中时）都经它进入内核路由。
- Kotlin：保留 `VPNService`/`VpnRouting.kt`（`vpnRouting.plan` 已在 Rust）、`DefaultNetworkMonitor`（改为调用 Rust → `fk_network_changed`）、连接归属查询（`getConnectionOwnerUid` + 包管理器）、通知、Keystore、权限。

## 6. DNS 统一

Rust 保存一份 `DnsSettings`：远程服务器列表、直连服务器列表（可为“系统 DNS”）、FakeIP 开关与 IPv4/IPv6 网段、IPv6 策略（prefer_ipv4/ipv4_only/prefer_ipv6/ipv6_only）、劫持开关（TUN 内 53 端口）。

| 设置 | sing-box | mihomo | Xray |
| --- | --- | --- | --- |
| 远程/直连服务器 | `dns.servers`（`https`/`udp`/`local`）+ 规则按出站选择 | `dns.nameserver` / `dns.direct-nameserver`（`system` 用 `UpdateSystemDNS` 推送） | `dns.servers`，直连服务器带 `domains` 或走 `freedom` 出站 |
| FakeIP | `fakeip` 服务器 + `inet4_range`/`inet6_range` | `enhanced-mode: fake-ip` + `fake-ip-range` | `fakedns` + `"servers":["fakedns"]` |
| IPv6 策略 | `strategy` | `ipv6` + `dns.ipv6` | `queryStrategy` |
| 劫持 | 路由规则 `hijack-dns` | `dns-hijack` | 53 端口路由到 `dns` 出站 |
| 系统 DNS 变化 | `fk_network_changed` → 平台网卡 DNS | `fk_network_changed` → `dns.UpdateSystemDNS` | 配置内写死，变化时 Rust 重载 |

不能映射的高级规则（sing-box `dns.rules` 的复杂匹配、mihomo `nameserver-policy`、Xray `domains`/`expectIPs`）按内核单独保存，作为“附加 DNS 规则”原样并入。订阅自带 DNS 段如何处理（覆盖为统一设置 / 保留订阅 / 合并）是开放决策 O2。

## 7. 与现在相比，每个内核保留与失去的能力

共同失去（libbox/补丁的扩展，不再进入任何内核）：HTTP 抓包/MITM/HTTP 改写（`httpdebug.patch`、`http_rewrite.patch`，见 O5）；仪表盘 DNS 查询计数（`patch_dns_count.py`，删除）；Go 运行时内存绑定（改为 Rust 读 `/proc/self/status` 的 RSS）；OOM killer、功耗报告、崩溃报告草稿（libbox 服务，删除；Go 崩溃输出由 Rust 重定向 stderr 到文件保留）；不重启进程的内核切换。

| 内核 | 保留 | 失去或改变 |
| --- | --- | --- |
| sing-box | 全部协议出站/端点（含 naive/cronet、WireGuard、Tailscale 端点、OpenVPN/OpenConnect 端点，按构建标签）、`package_name` 分流（平台连接归属）、分应用、默认网络跟随、Clash API | libbox `CommandServer`/StartedService gRPC 及其上的命令：Tailscale 状态/Ping/Taildrop、终端与平台 Shell（`UsePlatformShell=false`）、USBIP、OpenConnect/OpenVPN 状态订阅、网络质量与 STUN 命令（由 Rust 重写或删除，O6）；Root 自动重定向（`UsePlatformAutoRedirect=false`，且 `sing-tun-android-ingress.patch` 不能再用，O7）；Wi-Fi SSID 规则（`ReadWIFIState` 返回空，可补回调）；系统通知 |
| mihomo | 全部协议、策略组/provider、`PROCESS-NAME`（cmfa 下经 `find_owner`）、FakeIP、外部控制器 REST | 内核内的 geo 自动更新（`geo-auto-update` 关闭，Rust 负责）；停止时对 provider 的逐个关闭（进程切换后自然释放）；坏 MMDB 仍会 `log.Fatalln` 退出进程（R2） |
| Xray | 全部协议（含 MASQUE、xhttp、REALITY）、balancer/observatory、gRPC API | 连接列表（现在靠反射读私有字段，上游无 API，删除）；应用分流（本来就不支持）；直连 DNS 自动跟随网络（改为重载） |

## 8. 风险

- **R1 两个运行时**：本方案靠“每进程一个内核库”规避。§11.4 中 linux/arm64 probe 两个运行时同进程并跑未崩溃，但上游明确不支持（信号处理与 TLS 冲突、Go 1.21 起已有崩溃报告），Android（bionic + ART 信号链）更不可依赖。Rust 侧在 `KernelHost` 中强制单例。
- **R2 进程级退出**：mihomo `component/mmdb/mmdb.go:32,60,82` 在 MMDB/ASN 打不开时 `log.Fatalln`（`log/log.go:61-62` → `log.Fatalf` → `os.Exit(1)`）。单进程下会连同界面退出。缓解：Rust 负责下载并校验 geo 文件；shim 在解析前对已存在的文件调 `mmdb.Verify`（probe 已实现）；`geo-auto-update: false`；外部控制器的 `/restart`（`hub/route/restart.go:56,65`）不开放。`netns.go:19` 的 `os.Exit(0)` 只在 sing-box 网络命名空间子进程中出现，Android 不用。
- **R3 Go 全局状态**：`dialer.DefaultSocketHook`、`internet.RegisterDialerController`（只能追加不能移除）、`xray.tun.fd` 环境变量、mihomo 全局 tunnel 都是进程级。单进程单内核下可接受，且进程重启会清空。
- **R4 进程重启的可靠性**：`AlarmManager` + `killProcess` 的重启路径、后台（磁贴/通知）切换时前台服务的启动豁免、冷启动耗时，都只有文档依据，需在阶段 1 实机验证；失败时退回“提示用户重新打开应用”。
- **R5 体积**：三个库各自带一份 Go 运行时与共用依赖，APK 比单一 libbox 大（§11.5）。
- **R6 Rust 重写量**：分享链接转换、订阅识别、三种配置生成、Clash API/gRPC 客户端都是新代码；Kotlin 约 300 处 `Libbox.*` 调用要逐个迁走。
- **R7 sing-box Clash API 只监听 TCP**（`experimental/clashapi/server.go:162`）：本机其他应用可连 `127.0.0.1`，靠随机端口 + secret 保护。

## 9. 分阶段迁移（每阶段一个 PR，CI 通过 + 用户确认后再合并）

| 阶段 | 内容 | 交付与验收 |
| --- | --- | --- |
| 0 | 本文档 + probe | 本 PR；CI 证据见 §11 |
| 1 | `apps/android/kernels/{singbox,mihomo,xray}` 三个 Go 模块（shim 从 probe 转正）与构建工作流；`crates/kernel-host`（trait + FakeKernel + libloading 实现）；APK 同时打包三个库与旧 libbox，默认仍走旧路径 | 三个库在 CI 构建且依赖核对为 0 变更；Rust 单元测试；实机测重启切换时间（R4） |
| 2 | Rust 配置生成：统一 DNS、入站、控制接口、日志、分流规则编译（迁出 `core_routing*.go`），golden 测试对照旧输出 | `client-core` 测试；生成的三种配置经 `fk_check`（CI 上 linux 库） |
| 3 | `crates/kernel-api`：Clash API 与 Xray gRPC 客户端接入 `mobile-ffi/runtime.rs`（仪表盘、分组、连接、日志、延迟测试） | 单元测试 + linux 集成测试（harness 风格） |
| 4 | 启动/停止/切换改走 kernel-host；`VPNService` 只建 TUN 与回调；删除 Xray/mihomo 经 libbox 启动的路径 | 实机：三内核分别启动、切换、规则命中、连接页有数据 |
| 5 | 订阅、分享链接转换、Geo、HTTP/WebDAV、探测迁到 Rust；Kotlin 去掉 `Libbox.*` | `rg Libbox` 为空；回归测试 |
| 6 | 删除 libbox、`apps/android/core/`、`setup-core`/`build-libbox`、`scripts/patch_*.py`；更新 AGENTS.md、libbox.md、app.md | APK 体积与功能核对表；Release 构建（不发布） |

## 10. 开放决策

- **O1** 单进程（主方案）还是 manifest 声明的独立服务进程（§2 表格）。
- **O2** 订阅自带 DNS：统一设置覆盖（建议默认）/保留订阅/逐项合并。
- **O3** 非当前内核的配置校验：只做 Rust 结构检查（建议），还是导入时重启到该内核校验。
- **O4** 分享链接转换：Rust 重写（建议，覆盖 v2rayN/Clash 常见格式），还是只支持完整配置订阅。
- **O5** 抓包/MITM/HTTP 改写：删除，或在 Rust 内实现为内核路由前方的本地 HTTP 代理（大量新代码）。
- **O6** Tailscale：只保留 sing-box 的 Tailscale 端点（状态/Taildrop 删除），还是连端点一起去掉换取体积（§11.5 有无 Tailscale 两种体积）；终端、USBIP、OpenConnect/OpenVPN 状态页同问。这些与 AGENTS.md “保留 Tailscale/Taildrop、终端”的现行约束冲突，需用户确认后改约束。
- **O7** Root 自动重定向：删除，或在 shim 中实现 `CreateAutoRedirect`（经 Root 服务），但不再带 sing-tun 4 字节 BPF value 兼容补丁（部分 Android 15 设备会失败）。
- **O8** sing-box 构建标签：沿用 libbox 的 Android 标签（去掉 `with_usbip`）还是进一步裁剪。

## 11. 证据

### 11.1 嵌入 API（上游源码，无补丁；行号对应 sing-box v1.15.0-alpha.13、mihomo v1.19.34、Xray v26.11.9）

- sing-box：`box.go:110` `New`、`:169` 从 context 取 `adapter.PlatformInterface`、`:501` `Start`、`:656` `Close`；`include/registry.go:49` 注册表 context；`cmd/sing-box/cmd_check.go:35` 以 `box.New`+`Close` 做检查（`fk_check` 同法）；`adapter/platform.go:12` 平台接口全集；`route/router.go:183` 有平台接口时用平台查连接归属，`route/network.go:185-187` 无平台接口才在 Android 上用读 `packages.xml` 的包管理器——所以 shim 提供平台接口即可在不依赖 libbox、不需 Root 的情况下按包名分流；`experimental/libbox/service.go:58-87` 是 TUN fd 接入的参考实现（shim 同法：取名 `TUNGETIFF`、dup、`tun.New`）。gomobile 本身就以 `-buildmode=c-shared` 构建 libbox（`golang.org/x/mobile/cmd/gomobile/bind_androidapp.go:393`），所以 c-shared 在 Android 上与现有 libbox 同类；naive（cronet-go 静态库，`go.mod:38-39,146`）在 Android c-shared 中的构建结果见 §11.3。
- mihomo：`hub/hub.go:55` `ApplyConfig`；`hub/executor/executor.go:79` `ParseWithBytes`、`:532` `Shutdown`；`constant/features/cmfa.go`（`cmfa` 标签）；`tunnel/tunnel.go:354,370` cmfa 下进程查找改用 `process.FindPackageName`；`component/process/process.go:31` `DefaultPackageNameResolver`；`listener/sing_tun/server_android.go:1`（`!cmfa` 才用包管理器）；`component/dialer/dialer.go:103,160` `DefaultSocketHook`；`config/config.go:319` `tun.file-descriptor`；`dns/patch_android.go:16` `UpdateSystemDNS`；`config/config.go:427` `external-controller-unix`。干净停止：`Shutdown` 只关 TUN（`listener/listener.go:727-729`），shim 先应用空配置关闭全部监听与控制器再 `Shutdown` 并关闭已跟踪连接（probe 验证端口释放）。
- Xray：`common/platform/platform.go:27` `xray.tun.fd`；`proxy/tun/tun_android.go:25,35` 读取并 dup fd；`transport/internet/system_dialer.go:217` `RegisterDialerController`（只追加）；`core/xray.go:169` `New`、`:263` `Close`、`:389` `Start`；`infra/conf/serial/loader.go:92` `LoadJSONConfig`；`app/commander/commander.go:82` API 监听 unix socket；`app/stats/command`、`app/router/command`（`OverrideBalancerTarget`）、`app/observatory/command`。
- 进程退出调用：见 R2；Xray `core/xray.go:62,287` 的 `panic` 仅为编程错误。

### 11.2 依赖版本（`check_deps.py` + `go mod verify`）

三个 shim 模块各自 `go mod tidy` 后，内核 `go.mod` 中的要求**变化数均为 0**：sing-box 191 项要求、构建列表 402 个模块；mihomo 135 项、177 个模块；Xray 56 项、110 个模块（其余模块是 MVS 从依赖里解析出的、内核 go.mod 未列出的间接依赖）。唯一的 `replace` 是从 mihomo v1.19.34 自己的 `go.mod:151` 原样复制的 `google.golang.org/protobuf => metacubex/protobuf-go`（作为依赖时 replace 不继承），不替换任何内核源码。shim 目录只有自己的 `*.go`，无 vendor、无补丁。

### 11.3 probe 结果

工作流 [.github/workflows/probe-split-kernels.yml](../../.github/workflows/probe-split-kernels.yml)（`workflow_dispatch`；因 GitHub 只能调度默认分支上已有的工作流，另加了只对本分支、只对 probe 路径生效的 `push` 触发）。最终运行：[run 45211065726](https://github.com/koko123as/fk-box/actions/runs/45211065726)（`146ec75b`，两个任务均成功）；同样结果的上一轮 [run 45210017066](https://github.com/koko123as/fk-box/actions/runs/45210017066)。最早的 [run 45208872769](https://github.com/koko123as/fk-box/actions/runs/45208872769) 中 harness 已全部通过，失败的是负测试步骤本身的脚本错误（`bash -e` 下非 0 退出码中止了步骤），已修正，不是 CI 基础设施故障。

**android/arm64**（`ubuntu-24.04`，NDK 28.0.13004108，`aarch64-linux-android24-clang`，Go 1.27.3，`-s -w`，再 `llvm-strip --strip-all`）：三个库全部构建成功，各导出 8 个 `fk_*` 符号；sing-box 使用 libbox 的 Android 标签（含 `with_naive_outbound`，cronet 静态链接进 c-shared 成功）去掉 `with_usbip`。

| 库 | 字节 | deflate -6 | deflate -9 | NEEDED |
| --- | --- | --- | --- | --- |
| libsingbox.so（含 Tailscale） | 72,412,184 | 25,373,708 | 25,251,190 | liblog libdl libm libandroid libc |
| libsingbox.so（无 Tailscale） | 60,201,144 | 21,526,569 | 21,435,799 | 同上 |
| libmihomo.so（`cmfa,with_gvisor`） | 62,512,880 | 21,393,837 | 21,282,202 | liblog libdl libc |
| libxray.so | 33,720,568 | 11,609,168 | 11,548,090 | liblog libdl libc |

**linux/arm64 harness**（`ubuntu-24.04-arm` 原生 arm64 runner；本地 Debian arm64 结果相同）：每个内核在**单独的 harness 进程**中 `dlopen` → `fk_init` → `fk_check`（并确认坏配置被拒绝）→ 两轮 `fk_start` → 经 SOCKS5 入站取回本机 `python3 -m http.server` 的页面（HTTP 200 + 标记）→ 查询内核自带控制接口 → `fk_stop` → 确认代理端口已释放，最后退出码 0。

| 内核 | 结果 | 启动/停止（第 1 轮、第 2 轮） | 控制接口 |
| --- | --- | --- | --- |
| sing-box 1.15.0-alpha.13 | PASS | 6/0 ms、4/0 ms | Clash API `GET /version` → 200 |
| mihomo v1.19.34 | PASS | 23/2 ms、2/1 ms | `external-controller-unix` `GET /version` → 200 |
| Xray 26.11.9 | PASS | 0/0 ms、0/0 ms | `api` gRPC unix socket 可连接 |

linux 构建不含 naive/Tailscale 标签（只为验证 ABI 与运行时行为）；在 Android 设备上运行（TUN、protect、包名查询回调）不在 CI 覆盖范围内，属于阶段 1 实机验收。

### 11.4 两个内核库同进程（负测试）

harness `two`：在同一进程中先启动内核 A，再 `dlopen` 内核 B 并 `fk_init`/`fk_start`，然后两边交替经各自入站取页面 20 秒。run 45211065726 中三组各跑 3 次：

| A + B | 结果（3 次） |
| --- | --- |
| sing-box + mihomo | 19,120 / 23,455 / 24,357 次请求，0 失败，未崩溃 |
| mihomo + Xray | 40 / 40 / 40 次，0 失败，未崩溃 |
| Xray + sing-box | 39 / 39 / 39 次，0 失败，未崩溃 |

含 Xray 的组合请求数少，是 Xray 每个连接约 0.5–1 s 才关闭所致（Xray 单独运行时同样如此），与双运行时无关。

**结论**：与预期“崩溃/未定义”不同，在 linux/arm64 glibc 上两个 Go 运行时同进程跑通了这一简单负载。这**不改变设计**：Go 团队在 #18976 明确“不支持多个 Go 运行时”，在 #65050 说明 c-shared 假定自己是进程内唯一的运行时，符号去重取决于平台动态链接器，信号处理与 TLS 冲突会导致崩溃（Go 1.21 起已有 `bad sweepgen` 等报告）；Android 的 bionic 与 ART 信号链未测。因此仍坚持每进程一个内核库，不把这一结果当作可用能力。

### 11.5 APK 体积估算

基线：main `37e80bc1` 的 `build-apk.yml` 产物（[run 45170857771](https://github.com/koko123as/fk-box/actions/runs/45170857771)）APK 69,224,666 B，其中 `lib/arm64-v8a/libbox.so` 原始 140,246,088 B、APK 内压缩后 47,451,122 B（`useLegacyPackaging = true`，库在 APK 内 deflate 压缩、安装时解压）。

| 方案 | 三个库原始合计 | APK 内（deflate -6 估算） | 估算 APK | 相对现在 |
| --- | --- | --- | --- | --- |
| 现在（单一 libbox） | 140,246,088 | 47,451,122 | 69,224,666 | — |
| 三个库，sing-box 含 Tailscale | 168,645,632 | 58,376,713 | 约 80,150,257 | +10.9 MB（+15.8%） |
| 三个库，sing-box 不含 Tailscale | 156,434,592 | 54,529,574 | 约 76,303,118 | +7.1 MB（+10.2%） |

估算 = 现 APK − 现 libbox 压缩大小 + 三个库 deflate -6 大小；未计入 Rust 新增代码与删除的 Kotlin/Go 扩展（抓包、goja 等不再编入）。安装后解压占用从 140 MB 增至 156–169 MB。此前（三个独立可执行文件方案的第 0 阶段）测得 68,202,058 → 80,941,574 B，与本估算同量级。
