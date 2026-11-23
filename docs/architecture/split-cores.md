# 内核拆分：三个上游原版内核进程内嵌入，一个 C ABI + Rust 主机

状态：第 1 阶段设计（只有文档，不含代码）。本文是之后第 2～8 阶段的依据；每个阶段一个 PR，合并前由维护者确认。

目标：**没有子进程**，Rust 在应用进程内直接调用内核。sing-box、mihomo、Xray 的**上游源码不打补丁**，以 Go 模块的形式被一个新的小 Go 胶水模块导入，编成**唯一一个** `-buildmode=c-shared` 库 `libfkcore.so`，对外只暴露一个精简的 C ABI；Rust（`libfkui.so`）经 FFI 调用它。内核只运行配置，其余功能（配置识别与生成、订阅、规则、Geo、生命周期、控制、探测、抓包、备份、报告）在 Rust。Kotlin 只保留 Android 平台能力。只构建 arm64-v8a。最终删除 gomobile、`libbox.aar`、`apps/android/core/` 与其补丁、Kotlin 转换层。

相关文档：[libbox.md](../libbox.md)（现有补丁与扩展）、[app.md](../app.md)（应用行为）、[phase2.md](phase2.md)～[phase4.md](phase4.md)（已完成的 Rust 迁移）、[AGENTS.md](../../AGENTS.md)（约束）。

## 1. 现状（基线 `37e80bc1`）

| 项目 | 现状 |
| --- | --- |
| 内核 | sing-box `v1.15.0-alpha.13`、Xray `v26.11.9`（模块 `v1.260327.1-0.20261109052709-676eaf66f8f0`，libXray `v1.261109.0`）、mihomo `v1.19.34`，Go `go1.27.3`（[version.properties](../../version.properties)），经 gomobile 编进同一个 `libbox.aar`（`libgojni.so`，已是**一个进程一个 Go 运行时**） |
| 自写 Go | `apps/android/core/` 非测试代码 7,087 行（`*.go` 共 12,913 行，含测试 5,826 行），另 4 个补丁 364 行（`httpdebug.patch` 86、`http_rewrite.patch` 66、`sing-tun-android-ingress.patch` 170、`libbox-no-legacy.patch` 42），以及 `scripts/patch_dns_count.py`、`patch_httpdebug.py`、`patch_multicore.py`、`patch_sing_tun.py` |
| Kotlin 调 libbox | 43 个文件、约 9,600 行引用 `io.nekohasekai.libbox`；纯转换层是 `bg/PlatformInterfaceWrapper.kt`、`bridge/BoxStreams.kt`、`bridge/BoxToolStatus.kt`、`bridge/BoxWire.kt`、`utils/CommandClient.kt`、`backend/tools/*Status.kt`、`backend/usbip/USBIPStatus.kt` 等 |
| Rust | `crates/` 与 `apps/android/native/` 共约 32,400 行：存储、订阅流程、规则模型、Geo 策略、备份、命令订阅调度（`sessions.rs`）、流规则（`runtime.rs`）、服务状态机等已在 Rust；内核相关的拉取、拆分、转换、校验、编译仍经 `CoreKernel` 回调 libbox |
| 进程 | 单进程（清单无 `android:process`）；`Application.onCreate` 调 `Libbox.setup`，Go 运行时随应用启动加载 |

## 2. 上游核实结果

按 `version.properties` 的版本检出上游标签核对（2026-11-23）。模块图用 `go list -m all`（Go 1.27.4）对三个固定版本求解。与需求不一致或需补充的事实放在 2.7，按约束先报告，不打补丁绕过。

### 2.1 一个进程只能有一个 Go 运行时

- Go 不支持同一进程里有多个 Go 运行时：Ian Lance Taylor 在 [golang/go#18976](https://github.com/golang/go/issues/18976#issuecomment-308505600)：“We do not support multiple Go runtimes in a single process … there are no plans to ever make it work”；[golang/go#65050](https://github.com/golang/go/issues/65050)（多个 c-shared 库同进程崩溃，Not planned）中 cherrymui 说明 c-shared 假定自己是进程里唯一的 Go 运行时，Ian Lance Taylor 补充运行时还独占信号处理与固定偏移的 TLS。
- 因此“三个内核各编一个 c-shared 库”不可行；也意味着迁移期间 `libgojni.so`（libbox）与 `libfkcore.so` **不能在同一进程共存**（见 10）。
- 同一议题中的另一事实：c-shared 的运行时初始化（全部包的 `init`）在加载时于后台线程进行，第一次调用导出函数会等它完成（#18976 同一评论）。第一次调用不能放在 UI 线程。

### 2.2 sing-box `v1.15.0-alpha.13`

- 嵌入入口：`box.New(box.Options{Context, Options})`（`box.go`），注册表 `include.Context(ctx)`（`include/registry.go`）。
- `daemon` 包与 libbox 无关：`daemon.NewStartedService(daemon.ServiceOptions{Context, Handler, …})` 提供 `StartOrReloadService`、`CloseService`、`CheckConfig`、`FormatConfig`（`daemon/instance.go`），以及 `started_service.proto` 的全部方法（状态、日志、分组、模式、URL 测试、选节点、连接订阅与关闭、出站、网络质量、STUN、Tailscale、Taildrop、USBIP、OpenConnect、OpenVPN、通知）。流式方法的参数是 `grpc.ServerStreamingServer[T]` / `BidiStreamingServer` **接口**，可以用进程内实现直接调用，不需要 gRPC 传输。libbox 的 `CommandServer` 本身就是这样包它的（`experimental/libbox/command_server.go`）。
- **`adapter.PlatformInterface` 不依赖 libbox**：定义在 `adapter/platform.go`，由 `box.New` 从 `service.FromContext[adapter.PlatformInterface](ctx)` 取得（`box.go` 第 169 行）。上游已有 libbox 以外的实现：`experimental/boxdd`（`server.go` `service.MustRegister[adapter.PlatformInterface]`）。接口覆盖 `OpenInterface`（TUN）、`AutoDetectInterfaceControl`（protect）、`CreateDefaultInterfaceMonitor`、`NetworkInterfaces`、`FindConnectionOwner`、`ReadWIFIState`、通知、邻居表、平台 Shell、Bridge、AutoRedirect。
- TUN：libbox 的 `OpenInterface` 只是向平台要 fd、`dup` 后设 `tun.Options.FileDescriptor` 并调 `tun.New`（`experimental/libbox/service.go` 第 58～87 行），胶水层照做即可。**不需要 tun2socks，ICMP 照常可代理。**
- `local` DNS：Android 上没有平台传输时走 `dns/transport/local` 的 `resolv.conf` 来源，Android 没有该文件。libbox 是在 `baseContext` 里为 `C.DNSTypeLocal` 注册自己的平台传输（`experimental/libbox/config.go`，类型未导出）。胶水层须用公开的 `dns.RegisterTransport` 注册一个等价传输（约 120 行）。
- 构建标签：libbox Android 变体（API 24，`cmd/internal/build_libbox/main.go` 第 66～69、166～176 行）为 `with_quic,with_wireguard,with_utls,with_naive_outbound,with_clash_api,with_usbip,with_openvpn,with_openconnect,badlinkname,tfogo_checklinkname0,with_tailscale,ts_omit_*`，链接参数 `-checklinkname=0`、`-X runtime.godebugDefault=multipathtcp=0,tlssha1=1`（`cmd/internal/build_shared/flags.go`）。gomobile bind 产物就是 c-shared 库，所以 **`with_naive_outbound`（cronet）在 Android c-shared 下已经可用**，原 F2 不成立。
- 进程退出：库路径中没有 `os.Exit`/`signal.Notify`；仅 `cmd/`、`experimental/boxdd/` 与 `common/netns.Hold`（命令行用）有，胶水层不引用。重复 `New`/`Close` 是 libbox 的日常用法。

### 2.3 mihomo `v1.19.34`

- 嵌入入口：`hub/executor.ParseWithBytes` → `executor.ApplyConfig(cfg, force)`，停止 `executor.Shutdown()`（只清监听、TProxy、存 fake-ip 状态）。**没有实例对象，全部是进程级单例**（`tunnel`、`statistic.DefaultManager`、`resolver`、`listener`、`log` 通道）。现在的 `core_extra.go` `mihomoCloseListeners` 与 `core_memory.go` `mihomoReleaseState` 用公开函数补齐清理，只用公开 API。
- TUN：`tun.file-descriptor`（`listener/config/tun.go`）。
- `cmfa` 是 mihomo 自己的构建标签（ClashMetaForAndroid 用法，现 libbox 构建已加）：
  - `listener/sing_tun/server_notandroid.go`（`!android || cmfa`）：不读 `/data/system/packages.xml`；
  - `dns/patch_android.go`：系统 DNS 由 `dns.UpdateSystemDNS([]string)` 推入；
  - `component/process.DefaultPackageNameResolver`：公开变量，宿主提供包名查询，`PROCESS-NAME` 可用；
  - 另关闭回环检测（`component/loopback`）、放开安全路径检查（`constant/path.go`）、`hub/route` 嵌入模式。
  原 F1（官方 Android 构建 API 34 + 读 `packages.xml`）因此不成立：用我们自己的 NDK（API 24）以 `cmfa` 标签编译，与现状相同。
- 会结束进程的路径：`component/mmdb` 加载失败调用 `log.Fatalln`（→ `log.Fatalf` → `os.Exit(1)`）；`hub/route/restart.go`（只经外部控制器触发，不启用）；`executor.updateIPTables` 的 `os.Exit(2)` 只在 `runtime.GOOS == "linux"` 时可达，Android 上 `GOOS` 是 `android`，不可达。
- `go.mod` 有 `replace google.golang.org/protobuf => github.com/metacubex/protobuf-go`，注释指向 [golang/protobuf#1704](https://github.com/golang/protobuf/issues/1704)（Go 1.26 起方法死代码消除失效、体积变大）。作为依赖时 `replace` 不生效；模块图选中的 `protobuf v1.36.12` 已不在 `internal/descfmt/stringer.go` 调 `MethodByName("Methods")`，修复已包含，无需替换。

### 2.4 Xray `v26.11.9`

- 嵌入入口：`infra/conf/serial.LoadJSONConfig` → `core.New(config)` → `Start()`/`Close()`（现 `core_service.go` 即如此）。
- TUN fd **只能经环境变量**传入：`proxy/tun/tun_android.go` 在 `NewTun` 时读 `platform.TunFdKey`（`xray.tun.fd`），`F_DUPFD_CLOEXEC` 复制后自用。进程内用 `os.Setenv` 即可（现状），无需补丁，但它是进程级状态。
- 平台钩子都是公开的全局注册：`transport/internet.RegisterDialerController`（protect）、`common/net.RegisterAndroidProcessFinder`（路由 `process` 条件，应用分流可用）、`app/log.RegisterHandlerCreator`（日志）。都不能注销，胶水层在 `init` 注册一次、经间接层指向当前会话。
- 状态不经 gRPC：`instance.GetFeature(stats.ManagerType())`（计数器）、`extension.ObservatoryType()`（`GetObservation`）、`app/router` 的 `SetOverrideTarget`（balancer 选节点）。连接列表由现有 `core_xray_track.go` 包装 TUN 入站的 dispatcher 得到——它用 `reflect` + `unsafe` 改写未导出字段 `dispatcher`，不是源码补丁，但依赖私有布局（2.7 N5）。
- 库路径中没有 `os.Exit`（只在 `infra/vformat` 等工具与 `main/`）。

### 2.5 一个模块图里的依赖

`go list -m all` 对三个固定版本求解成功，**不需要 `replace`**（现 `setup-core` 也是把 Xray、mihomo 加进 sing-box 的 `go.mod` 后 `tidy`，并断言 `sing` 版本不变）。MVS 会把共享依赖提到三者中的最高版本，即每个内核都不是用它发布时的依赖集编译的。主要上调：

| 内核 | 被上调的共享依赖（自身版本 → 选中版本） |
| --- | --- |
| sing-box | `google.golang.org/grpc` 1.79.1 → 1.85.0；`golang.org/x/net` 0.57.0 → 0.62.0、`x/crypto` 0.54.0 → 0.58.0、`x/sys` 0.47.0 → 0.49.0；**`metacubex/utls` 1.8.7 → 1.9.0-alpha.1**（mihomo）；`pires/go-proxyproto` 0.8.1 → 0.16.0；`klauspost/compress` 1.19.1 → 1.20.1；`miekg/dns` 1.1.72 → 1.1.73；`protobuf` 1.36.11 → 1.36.12 |
| mihomo | `golang.org/x/*` 全部大幅上调（如 `x/net` 0.35.0 → 0.62.0、`x/crypto` 0.33.0 → 0.58.0）；`mdlayher/netlink` 1.7.2 → 1.11.2；`klauspost/compress` 1.17.9 → 1.20.1；`miekg/dns` 1.1.63 → 1.1.73；`go-proxyproto` 0.8.0 → 0.16.0；`sagernet/netlink`、`insomniacslk/dhcp` 随 sing-box |
| Xray | 仅 `huin/goupnp`、`go4.org/netipx`、`golang.org/x/exp` |

quic-go、gvisor、sing 系列各自是不同模块路径（如 `sagernet/quic-go` 与 `metacubex/quic-go`），并存不冲突。现 libbox 已在同一模块图下运行三内核，protobuf 注册名也无冲突。

### 2.6 现有补丁与脚本去掉的代价

| 补丁/脚本 | 作用 | 去掉后 |
| --- | --- | --- |
| `libbox-no-legacy.patch` | 只改 `build_libbox`：不再构建 SDK 21 的 legacy AAR | 无代价（不再用 `build_libbox`） |
| `patch_multicore.py` | 把 libbox 的 gRPC 注册换成多内核包装 | 无代价（没有 `CommandServer`，多内核由胶水层分派） |
| `http_rewrite.patch` | 给 sing-box `ExperimentalOptions` 加 `http_rewrite` 字段 | 原版 sing-box 拒绝未知字段 → 字段迁出到配置元数据（4.10） |
| `httpdebug.patch` + `patch_httpdebug.py` | 在 sing-box `route/route.go` 中插入抓包/MITM 与 QUIC 拒绝钩子 | sing-box 内的抓包钩子没有了 → 抓包移到 Rust，三内核同一做法（4.10） |
| `patch_dns_count.py` | 在 sing-box `dns/router.go` 计 DNS 应答数 | sing-box 改按连接流中 53 端口计（缓存命中与 DNS 入站不计）；mihomo 的计数是包装 `resolver` 的胶水，保留 |
| `sing-tun-android-ingress.patch` + `patch_sing_tun.py` | Root 自动重定向：兼容 ingress discard BPF map 的 4 字节 value（一加 10 Pro / Android 15 实例） | 这类设备上 Root 自动重定向会被上游布局检查拒绝（2.7 N3） |

### 2.7 待确认的事实（原 F1～F6 重评 + 新事实）

| # | 事实 | 进程内结论 |
| --- | --- | --- |
| F1 | mihomo 官方 Android 构建 API 34、`!cmfa` 读 `packages.xml` | **不再成立**：以 `cmfa` 标签、API 24 编进 `libfkcore.so`（2.3） |
| F2 | sing-box 官方命令行 Android 标签无 `with_naive_outbound` | **不再成立**：沿用 libbox Android 标签（2.2）；cronet 在本胶水库中的链接须第 5 阶段实测 |
| F3 | sing-box 控制面是 `api` gRPC | **改变**：进程内直接调 `daemon.StartedService` 方法，无监听、无 secret；Rust 只用 `prost` 解码同版本 proto 消息，不引入 `tonic`（远程控制除外，11.7） |
| F4 | 命令行版无 netlink/包管理器/Wi-Fi | **不再成立**：`PlatformInterface` 提供接口监视、连接归属、Wi-Fi 状态，`auto_detect_interface`、`package_name`、`wifi_*` 照常 |
| F5 | sing-box 无传入 TUN fd 的字段，需 tun2socks | **不再成立**：`OpenInterface` 返回带 `FileDescriptor` 的 `tun.New` |
| F6 | 本地控制端口无鉴权 | **不再成立**：控制不走套接字。用户配置自带的 `clash_api`/`external-controller` 照旧只警告 |
| N1 | MVS 上调共享依赖（2.5），含 sing-box 用到 alpha 版 `metacubex/utls` | 源码未改，但依赖集与各内核发布时不同；一个运行时就只能有一个模块图，无法避免 |
| N2 | Xray 只接受环境变量传 TUN fd；mihomo 无实例对象 | 可用（不打补丁），但都是进程级状态，切换内核要靠胶水层清理（4.4） |
| N3 | Root 自动重定向在 4 字节 ingress map 设备上需要 sing-tun 补丁 | 不打补丁则这类设备失去该功能；可向上游提交修复 |
| N4 | mihomo 加载坏的 MMDB 会 `os.Exit(1)`，进程内即整个应用退出 | Rust 在交给内核前校验 MMDB（4.8）；无法在不改源码的前提下拦截 |
| N5 | Xray 连接列表靠 `reflect`/`unsafe` 改未导出字段 | 不是补丁，但升级 Xray 时可能静默失效；需单测守护或放弃该功能 |

## 3. 目标架构

```
┌──────────────────────── 应用进程 io.fkbox.app（单进程、单 Go 运行时）────────────────────────┐
│ Kotlin：Activity、VpnService（Builder.establish、protect）、前台服务/通知、磁贴、            │
│         ConnectivityManager 回调、getConnectionOwnerUid、Wi-Fi、相机、WebView、USB、Keystore │
│   │ JNI（CoreStore / SlintHost / 新增 PlatformHost）                                         │
│ Rust libfkui.so：client-ui · mobile-ffi · client-core（配置/订阅/规则/Geo/校验）             │
│   client-runtime：CoreHost（加载 libfkcore、会话、状态机）· 控制流 · 探测 · 抓包（P7）      │
│   │ dlopen + C ABI（fk_*，4.2）            ▲ 宿主回调（日志、open_tun、protect、归属…）      │
│   ▼                                         │                                                │
│ Go libfkcore.so（新胶水，唯一的 Go）：fkcore 包 → sing-box / mihomo / Xray 上游模块（原版） │
└───────────────────────────────────────────────────────────────────────────────────────────────┘
```

- 同一时间只运行一个内核；切换 = 胶水层停旧内核并清理（4.4）、起新内核，TUN fd 由 Rust 持有不重建。
- 数据面完全在 Go 内（内核直接读写 TUN fd）；跨 FFI 的只有控制调用、事件流与平台回调。
- 新 crate `crates/client-runtime`（[phase4.md](phase4.md) 已预留）：`CoreHost` 加载与调用 ABI、`CoreController`（4.6）、探测、抓包；只依赖 `client-core`，不含 JNI。`mobile-ffi` 编排，`apps/android/native` 接 JNI。

## 4. 关键设计

### 4.1 `libfkcore`：新的 Go 胶水（不是内核补丁）

- 位置 `apps/android/fkcore/`（独立 `go.mod`，`package main` + `//export`）。`require` 三个内核的固定版本（取自 `version.properties`），不加 `replace`，不复制任何上游文件；构建后断言模块缓存中三棵源码与校验和一致（`go mod verify`）。
- 它是迁移后**唯一的 Go 代码**，只做三件事：把三个内核的公开 Go API 接到 C ABI；实现平台接口并转成宿主回调；把 mihomo/Xray 的状态映射成 `started_service.proto` 消息（现 `core_api.go` 已在做同样的映射）。不含配置生成、规则、订阅、HTTP、抓包。
- 组成与估算（非测试代码，多数从 `apps/android/core/` 现有胶水搬来）：

| 部分 | 来源 | 估算 |
| --- | --- | --- |
| C ABI 导出、缓冲区、流表、`recover` 包装、版本握手 | 新写 | ~400 |
| sing-box：`adapter.PlatformInterface` 实现、默认接口监视、`local` DNS 平台传输、`StartedService` 方法分派与进程内流适配器 | 新写（参照 libbox `service.go`/`monitor.go`/`dns.go`，共约 900 行） | ~900 |
| mihomo：启动/重载/停止、清理、日志、DNS 计数包装、状态映射 | `core_service.go`、`core_extra.go`、`core_memory.go`、`core_api.go` 相应部分 | ~650 |
| Xray：启动/停止、连接跟踪、stats/observatory/balancer 映射 | `core_service.go`、`core_xray_track.go`、`core_api.go` 相应部分 | ~600 |
| 内存（`debug.SetMemoryLimit`、THP、`FreeOSMemory`）、`debug.SetCrashOutput` | `core_memory.go` | ~100 |
| 合计 | | **约 2,500～2,800 行**（现 7,087 行 + 364 行补丁 + 4 个脚本） |

- 测试：胶水层只有 ABI 往返与切换内核的 Go 测试（linux/amd64 宿主构建），业务测试都在 Rust。

### 4.2 C ABI

```c
typedef struct { uint8_t *ptr; size_t len; } FkBuf;   // Go 以 C.malloc 分配，Rust 调 fk_free
typedef struct {
  void    *ctx;
  void    (*log)(void *ctx, int32_t kernel, int32_t level, const uint8_t *msg, size_t len);
  void    (*event)(void *ctx, uint64_t stream, const uint8_t *pb, size_t len, int32_t done); // 流推送
  int32_t (*open_tun)(void *ctx, const uint8_t *opts_pb, size_t len, FkBuf *err);           // 返回 fd
  int32_t (*protect)(void *ctx, int32_t fd);
  int32_t (*find_owner)(void *ctx, const uint8_t *req_pb, size_t len, FkBuf *resp);
  int32_t (*platform)(void *ctx, const char *method, const uint8_t *req, size_t len, FkBuf *resp); // Wi-Fi、接口列表、通知等低频调用
} FkHost;

uint32_t fk_abi_version(void);
int32_t  fk_init(const FkHost *host, const uint8_t *init_pb, size_t len, FkBuf *err); // 目录、内存上限、语言
int32_t  fk_version(int32_t kernel, FkBuf *out);
int32_t  fk_check(int32_t kernel, const uint8_t *cfg, size_t len, FkBuf *err);
int32_t  fk_format(int32_t kernel, const uint8_t *cfg, size_t len, FkBuf *out, FkBuf *err);
int32_t  fk_start(int32_t kernel, const uint8_t *cfg, size_t len, const uint8_t *opts_pb, size_t olen, FkBuf *err);
int32_t  fk_reload(int32_t kernel, const uint8_t *cfg, size_t len, const uint8_t *opts_pb, size_t olen, FkBuf *err);
int32_t  fk_stop(FkBuf *err);
int32_t  fk_call(const char *method, const uint8_t *req, size_t len, FkBuf *resp, FkBuf *err); // 一元
uint64_t fk_stream_open(const char *method, const uint8_t *req, size_t len);                  // 服务端流/双向流
int32_t  fk_stream_send(uint64_t stream, const uint8_t *pb, size_t len);                      // 仅双向流（USBIP）
void     fk_stream_close(uint64_t stream);
void     fk_network_update(const uint8_t *pb, size_t len);   // 默认网络、接口、系统 DNS（Rust → Go）
void     fk_free(FkBuf buf);
```

- 方法名与消息沿用 sing-box `daemon/started_service.proto`（如 `SubscribeGroups`、`SelectOutbound`、`CloseConnection`），三个内核共用一套消息，Rust 用 `prost` 从同版本标签的 proto 生成解码代码（不入库，CI 检查版本一致）。ABI 自有的小消息（`init_pb`、`opts_pb`、`find_owner`）放在 `fkcore.proto`，与胶水同库维护。
- 规则：每个导出函数 `defer recover()` 转成错误返回；Go 不保存 Rust 传入的指针（`C.GoBytes` 复制）；Rust 回调都是 `extern "C"` 且 `catch_unwind`，不让 panic 跨边界；回调可能在任何 Go 线程上执行，Rust 侧需要 JNI 时 `AttachCurrentThread` 并用线程键在线程退出时 `Detach`；回调不得反向同步调用 `fk_*`（避免持锁重入）。
- 日志与流事件在 Go 侧批量（≤ 50 ms）推送，减少 cgo 往返。

### 4.3 Rust 侧加载：`dlopen`（建议）而非链接期依赖

- 建议 `libloading` 在第一次需要内核时（服务启动、导入校验）于后台线程 `dlopen("libfkcore.so")`，进程生命期内不 `dlclose`（Go 运行时不能卸载）。理由：
  1. `libfkui.so` 的 cargo 构建与主机单元测试不需要 Go 工具链与 Go 产物，CI 的 Rust 任务不依赖 `build-fkcore`；
  2. 只打开界面、不连 VPN 时不付 Go 运行时与三内核 `init` 的内存（现状是 `Libbox.setup` 在启动即加载）；
  3. 首次调用会等 Go 初始化（2.1），显式加载点便于放到后台线程并给出“内核加载失败”的明确错误；
  4. `fk_abi_version()` 握手，不匹配即拒绝。
- 代价：手写一张函数指针表（由 cgo 生成的 `libfkcore.h` 经 `bindgen` 在 CI 中对照，防止漂移）。链接期依赖（`DT_NEEDED`）更简单，但把两个工具链的构建绑在一起，且失去 2、3。
- `CoreHost` trait 抽象 ABI，生产实现是 FFI，测试实现是纯 Rust 假后端（模拟就绪、失败、流断开、慢回调）；所有控制路径用假后端做单元测试。

### 4.4 生命周期

- `fk_start(kernel, config, opts)`：胶水层按内核分派。
  - sing-box：`daemon.StartedService.StartOrReloadService`，上下文含 `include` 注册表、`local` DNS 平台传输与胶水的 `PlatformInterface`；
  - mihomo：`ParseWithBytes` → `ApplyConfig`（先不启 TUN）→ `listener.ReCreateTun`（现有顺序，避免 DNS 劫持读到被替换的 resolver）；`tun.file-descriptor` 用 `dup` 的 fd；
  - Xray：`os.Setenv(xray.tun.fd)`（持锁、每次启动前设置）→ `core.New` → 安装连接跟踪 → `Start`。
- “已启动”只在内核返回成功且 TUN 已建立后报告（保留 [AGENTS.md](../../AGENTS.md) 约束 4）。
- 重载：sing-box `StartOrReloadService`；mihomo `ApplyConfig(force)`；Xray 停止再启动。TUN fd 由 Rust 持有，重载与切换内核期间不重建 VPN。
- 停止与切换：sing-box `CloseService`；mihomo `Shutdown` + 关闭全部监听 + 释放规则/代理/provider/连接/Geo 缓存（保留 resolver 与 fake-ip，与现状相同）；Xray `Close`。之后 `FreeOSMemory`。切换前确认旧内核的 fd 已关闭（`/proc/self/fd` 计数）。
- 崩溃：Go 的 `fatal error` 或未恢复的 panic 会结束整个应用进程（与现状相同）。`debug.SetCrashOutput` 把崩溃输出写到报告目录，下次启动由 Rust 读入报告列表（替代 `CrashReportManager` 的 Go 部分）。
- 内存：`debug.SetMemoryLimit` 由 `fk_init` 设置；THP 按 `core_memory.go` 的条件设置；内存展示用 Go `runtime/metrics` 加 `/proc/self/status`。

### 4.5 TUN 与平台能力

1. `fk_start` 时 sing-box 的 `OpenInterface`，或 mihomo/Xray 启动前胶水层，调 `host.open_tun(选项)`；Rust 经 JNI 让 Kotlin `VpnService.Builder` 建站（地址、路由、DNS、MTU、分应用列表来自 `vpn_routing.rs`），`detachFd()` 交给 Rust，Rust 返回给 Go 的是 `dup`。
2. 出站套接字经 `protect` 回调调 `VpnService.protect`（sing-box `AutoDetectInterfaceControl`、mihomo `dialer.DefaultSocketHook`、Xray `RegisterDialerController`），与现状一致；应用**不需要**把自己排除出 VPN，订阅等下载行为不变。
3. 默认网络、接口列表、系统 DNS 由 Kotlin `ConnectivityManager` 回调经 Rust `fk_network_update` 推入：sing-box 接口监视、mihomo `dns.UpdateSystemDNS`、`local` DNS 传输。
4. 应用归属：`find_owner` → `getConnectionOwnerUid`（API 29+）→ 包名；sing-box `FindConnectionOwner`、mihomo `DefaultPackageNameResolver`、Xray `RegisterAndroidProcessFinder` 共用。
5. Wi-Fi、通知、Tailscale SSH 宿主（平台 Shell、主机密钥）、Root 的 Bridge/AutoRedirect 经 `host.platform` 调用，Kotlin/Root 服务实现保留。

### 4.6 控制：`CoreController`

trait 与旧版本相同（就绪、重载、分组、选节点、URL 测试、模式、连接流、关闭连接、流量、日志、内存、sing-box 工具），实现只有一个 `FfiController`，消息都是 `started_service.proto`：

| 能力 | sing-box | mihomo | Xray |
| --- | --- | --- | --- |
| 分组、选节点 | `StartedService` 原方法 | `tunnel.Proxies()`、`outboundgroup.Selector.Set` | balancer → 组，`SetOverrideTarget` |
| 延迟测试 | `URLTest` | 代理 `URLTest` | observatory `GetObservation`（按需测速不支持，显示最近结果） |
| 模式 | `Get/SetClashMode` | `tunnel.SetMode` | 不支持（隐藏） |
| 连接与关闭 | `SubscribeConnections`、`CloseConnection` | `statistic.DefaultManager` | 现有连接跟踪（N5） |
| 流量、内存、日志 | `SubscribeStatus`、`SubscribeLog` | `statistic` 计数、`log.Subscribe` | `stats.Manager`、日志处理器 |
| Tailscale/Taildrop/OpenConnect/OpenVPN/USBIP/网络质量/STUN | `StartedService` 原方法（USBIP 用双向流） | 不支持 | 不支持 |

- 现有 `runtime.rs`（流规则）、`connections.rs`（连接表）、`sessions.rs`（调度）不变，输入由 Kotlin 转发的 libbox 回调改为 `CoreController` 的流；消息与 libbox 命令客户端同源，字段一一对应。
- Kotlin 的 `CommandClient`、`BoxStreams`、`BoxWire` 等转换层随之删除。

### 4.7 配置管线（`client-core::config`）

识别 → 数组拆分/合并 → 分享链接转换（第 4 阶段，替代 mihomo `common/convert` 与 libXray `share`）→ 规则编译 → 运行时改写 → `fk_check` 校验 → `fk_start`。

- 校验以内核自己的解析为准：sing-box `CheckConfig`/`FormatConfig`、mihomo `ParseRawConfig`、Xray `LoadJSONConfig` + `core.New` 不启动；Rust 不重写内核解析器。
- 运行时改写沿用现有规则（只在内存中，不写回用户配置）：sing-box 的 TUN 入站保留（交给平台接口），`http_rewrite` 字段剥离；mihomo 强制 `tun`（fd、栈按 `core_tunstack.go`），关外部控制器与 redir/tproxy；Xray 入站换为 `tun` + 53 端口 → `dns` 出站。
- 黄金样本：第 2 阶段把 `core_*_test.go` 的输入/期望输出提取为 `crates/client-core/tests/golden/`（生成脚本一并入库），Rust 输出必须一致（JSON 比规范化值，YAML 比解析值并另比文本）。

### 4.8 Geo

下载、条件请求、SHA-256 与格式校验（protowire `.dat` 帧、MMDB 头与元数据可解析）迁到 Rust（`reqwest` + `rustls`）。只有校验通过的文件才放进共享 Geo 目录（N4）。mihomo 运行中替换后调 `fk_call("ReloadGeo")`（胶水层 `mmdb.ReloadIP/ReloadASN` 与 geodata 缓存清理，现状同款）；Xray 下次启动生效。

### 4.9 探测、局域网、DNS 计数

- 出口 IP、IP 质量、服务检测（`ui_egress.go`、`ui_service_check.go` 的 13 项规则）迁到 Rust。运行中经内核的本地代理入站（随机端口、随机凭据）发出，失败不回退直连；停止时直连。
- 局域网代理的监听解析迁 Rust 配置层；接口地址由 Kotlin 提供。
- STUN、网络质量：sing-box 运行时用 `StartedService`；其他情况 STUN 由 Rust 实现，网络质量隐藏。
- DNS 计数见 2.6。

### 4.10 抓包、MITM、HTTP 改写（第 7 阶段，先设计）

- 三内核同一做法：生成配置时把命中抓包筛选（应用、端口、域名）的流量路由到一个 `socks5` 出站（带随机凭据），指向 Rust `client-runtime::capture` 监听的 `127.0.0.1` 端口；抓包层处理后经内核的另一个带标签的入站发出，规则避免回环。应用筛选用内核自己的应用规则（sing-box `package_name`、mihomo `PROCESS-NAME`、Xray `process`，均经 4.5 的归属回调，API 29+）。
- 能力：HTTP 记录、HTTPS MITM（上游优先握手）、`http_rewrite` 全部规则、脚本（候选 `boa_engine`/`rquickjs`，第 7 阶段评估）、HAR/cURL 导出、QUIC 阻断（内核规则拒绝命中域名的 UDP 443）。根私钥仍在 Keystore，Rust 经 JNI 签名。
- `experimental.http_rewrite` 从 sing-box 配置迁到配置元数据的独立字段（一次性迁移，保留原文件备份）。

### 4.11 Kotlin 剩余职责

VpnService 建站、`protect`、前台服务与通知、磁贴、开机启动、默认网络与 `LinkProperties`、连接归属查询、Wi-Fi 状态、应用列表与图标、相机扫码、OpenConnect WebView、USB 设备枚举与 URB 读写、文件选择、Keystore、Root/Shizuku（应用查询、终端、Root 桥接/自动重定向）、WorkManager 入口、JNI 宿主。其余 Kotlin 业务删除（第 7 节）。

### 4.12 构建与打包

- 新复合 Action `.github/actions/build-fkcore`：`setup-go`（`GO_VERSION`）→ `go mod download` + `go mod verify` → `GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC=aarch64-linux-android24-clang go build -buildmode=c-shared -trimpath -buildvcs=false -tags "<sing-box libbox Android 标签>,cmfa" -ldflags "-checklinkname=0 -s -w -buildid= -X …Version=… -extldflags=-Wl,-soname,libfkcore.so"`，与 gomobile 对 Android 的 c-shared 构建等价。输出 `libfkcore.so` 与 `libfkcore.h`，Gradle 从 `build/fkcoreJniLibs/arm64-v8a` 打包。
- 模块上调报告沿用现 `setup-core` 的做法（2.5 表格每次构建写入 Step Summary）；`sing` 版本断言保留。
- `scripts/verify_release.py` 增加：`libfkcore.so` 为 aarch64、`PT_LOAD` 对齐 ≥ 16 KiB、导出 `fk_abi_version`、版本字符串与 `version.properties` 一致；第 8 阶段起 APK 中不得有 `libgojni.so`。
- 缓存键：Go 版本 + 三个内核版本 + `apps/android/fkcore/**` + Action 哈希。
- Android 加载：与 `libgojni.so` 相同的约束——Go 自己处理 bionic TLS（Android 10+ 用 `TLS_SLOT_APP`，更早版本扫描 pthread key，见 Go `runtime/cgo/gcc_android.c`），minSdk 24 不变；信号处理与 ART 共存已由现 libbox 验证。
- `scripts/bump_core.py` 只改 `version.properties`，再跑胶水层测试与 2.5 报告；不再核对补丁。

## 5. 体积与内存预期

- 一个 Go 运行时、三内核共享依赖，结构与现 `libgojni.so` 相同。去掉的 Go：gomobile/libbox `CommandServer` 与绑定代码、`httpdebug`（含 goja JS 运行时）、libXray `share`；新增约 2,500～2,800 行胶水。预期 `libfkcore.so` ≤ 现 `libgojni.so`，第 3 阶段起每个 PR 记录实测值。
- 对比：旧方案（三个独立二进制）在 `arch/kernel-isolation-p0` 实测 APK 68,202,058 B → 80,941,574 B（+12.7 MB，未签名）；本方案不产生这部分增量。
- `libfkui.so` 增量来自抓包层、分享链接转换与 `prost`，每阶段记录；不再需要 `tonic`/`hyper` 客户端（远程控制除外）。
- 内存：仍是一个进程一个运行时，没有第二个进程；只打开界面时不加载 Go（4.3）。

## 6. 功能去向表

“阶段”为承担迁移或删除的阶段。

### 6.1 Go `apps/android/core`

| 文件 | 去向 | 阶段 |
| --- | --- | --- |
| `core_profile.go`：识别、订阅 UA | 迁 Rust（`config/detect.rs`） | 2 |
| 同上：`CheckCoreConfig`、`FormatCoreConfig` | 改为 `fk_check`/`fk_format` | 3–5 |
| 同上：`ConvertCoreShareLinksFor` | 迁 Rust（`config/share/`） | 4 |
| `core_array*.go`、`core_routing*.go`、`core_loglevel.go`、`core_geo.go` | 迁 Rust | 2 |
| `core_routing_owner.go` | 并入胶水层归属回调（4.5） | 3 |
| `core_service.go`、`core_api.go`、`core_extra.go`、`core_xray_track.go`、`core_memory.go`、`core_tunstack.go`、`core_dns_cmfa.go` | 搬进 `libfkcore`（4.1） | 3–5 |
| `core_dns_other.go`、`dns/`、`ui_dns.go`、`patch_dns_count.py` | 去掉（2.6） | 8 |
| `core_link.go`、`core_version.go` | `fk_version` | 3 |
| `ui_dashboard.go` | 控制流（4.6） | 3–5 |
| `ui_http.go`、`ui_subscription.go` | 迁 Rust（`reqwest`） | 2 |
| `ui_subscription_rewrite.go`、`httpdebug/`、`route/httpdebug.go`、`option/http_rewrite.go`、`ui_capture*.go`、`ui_http_rewrite.go`、两个补丁及脚本 | 迁 Rust（4.10） | 7 |
| `ui_egress.go`、`ui_service_check.go`、`ui_lan.go` | 迁 Rust（4.9） | 6 |
| `sing-tun/`、`sing-tun-android-ingress.patch`、`patch_sing_tun.py` | 去掉（N3） | 8 |
| `libbox-no-legacy.patch`、`patch_multicore.py`、`verify_multicore.py`、`core_grpc_passthrough.txt` | 去掉 | 8 |

### 6.2 libbox 客户端层能力（Kotlin 经 `Libbox.*` 使用）

| 能力 | 去向 | 阶段 |
| --- | --- | --- |
| 命令客户端（本地） | `CoreController`（4.6） | 3–5 |
| 命令客户端（远程设备） | Rust `tonic` 客户端或去掉（11.7） | 6 |
| `PlatformInterface` 全部回调 | 胶水层 `PlatformInterface` + 宿主回调（4.5） | 5 |
| Tailscale、OpenConnect、OpenVPN、USB/IP、网络质量、STUN | `StartedService` 经 ABI | 5 |
| 平台 Shell / SSH 主机密钥（设备作 Tailscale SSH 服务端） | `host.platform` 回调，保留 | 5 |
| `newAutoRedirectService`、`newBridgeService`、邻居表 | `host.platform` 回调 + Root 服务，保留（N3 设备除外） | 5 |
| `openNativeShellSession`/`openNativePipeSession` | Rust（`nix::pty`） | 6 |
| `createZipArchive`、`parseRemoteProfileImportLink`、`decodeProfileContent`、`formatBytes`、`formatNATMapping`、`proxyDisplayType` | 迁 Rust（按同版本 libbox 源码实现、样本测试） | 2 |
| `setup`、`reloadSetupOptions`、`setLocale` | `fk_init` | 3 |

### 6.3 Kotlin

| 文件/区域 | 去向 | 阶段 |
| --- | --- | --- |
| `bg/PlatformInterfaceWrapper.kt`、`bg/LocalResolver.kt`、`bg/IntArrayIterator.kt` | 删除；平台能力改为 `PlatformHost` JNI 方法 | 5、8 |
| `bridge/BoxStreams.kt`、`BoxToolStatus.kt`、`BoxWire.kt`、`BoxDiagnostics.kt`、`utils/CommandClient.kt`、`CommandTarget.kt`、`RemoteControlManager.kt`、`backend/tools/*Status.kt`、`Taildrop*.kt`、`backend/usbip/USBIPStatus.kt`、`ktx/Wrappers.kt` | 删除 | 8 |
| `bg/BoxService.kt` | 只剩前台服务、通知与 JNI 调用 | 3–5 |
| `bg/VPNService.kt`、`bg/VpnRouting.kt` | 保留；建站由 Rust 经 JNI 触发 | 3 |
| `bg/GeoResources.kt`、`database/CoreKernel.kt`、`ProfileContent.kt`、`bridge/BoxProfiles.kt`、`DashboardConfigCache.kt` | 删除或缩到平台部分 | 2–6 |
| `bridge/BoxCapture.kt`、`MitmCertificate*.kt`、`CaptureSettings.kt` | Keystore 与文件选择留 Kotlin，其余迁 Rust | 7 |
| `bg/RootServer.kt`、`terminal/*`、`usbip/*` | 保留平台部分 | — |

### 6.4 能力变化汇总（需维护者确认）

与旧的子进程方案相比，NaiveProxy、mihomo/Xray 的应用包名路由、`wifi_*` 规则、ICMP、网络切换自动重连、Xray 连接列表、设备作 Tailscale SSH 服务端、Root 桥接、抓包应用筛选**全部保留**。仍有的变化：

1. Root 自动重定向在 4 字节 ingress map 设备（如一加 10 Pro / Android 15）上不可用（N3）。
2. sing-box DNS 计数只计连接流中的 53 端口（2.6）。
3. 抓包从 sing-box 路由内钩子改为“内核路由 → Rust 抓包层”，三内核行为一致；`http_rewrite` 字段迁出 sing-box 配置（4.10）。
4. Xray 仍无按需测速与模式（与现在相同）。
5. 报告“为 sing-box 维护者加密”去掉（只对上游 libbox 有意义）。
6. 远程控制另一台设备视 11.7 决定。

## 7. 第 8 阶段删除清单

`apps/android/core/` 整个目录；`.github/actions/setup-core`、`build-libbox`；`scripts/patch_*.py`、`verify_multicore.py`、`verify_httpdebug.py`；`libbox.aar` 的 Gradle 依赖与 R8 规则、gomobile 工具链安装；6.3 中标为删除的 Kotlin 文件；`docs/libbox.md` 改写为“libfkcore 与内核构建”。AGENTS.md、app.md、newui.yml、build-release.yml、`bump_core.py` 同步更新。每个删除在 PR 描述中给出行数（Go、Kotlin 删除行，Go 胶水与 Rust 新增行）。

## 8. 风险

| 风险 | 影响 | 缓解 / 验证 |
| --- | --- | --- |
| Go 致命错误（未恢复 panic、并发 map 写、mihomo `log.Fatalln`）结束整个应用 | 界面与 VPN 一起退出（与现状同） | 每个导出 `recover`；Geo 先校验（N4）；`SetCrashOutput` 落盘并在下次启动报告 |
| Go 运行时不能卸载，内核残留状态留到进程结束 | 内存与 goroutine 泄漏累积 | 切换内核 20 次后比较 goroutine 数、fd 数、RSS（模拟器探针与实机） |
| 进程级全局状态：mihomo 单例、Xray 全局钩子与 `os.Setenv`、sing-box `log.SetStdLogger` | 切换后残留旧配置；`setenv` 与其他原生线程 `getenv` 竞争 | 胶水层清理函数 + 切换测试；`Setenv` 只在启动前持锁设置一次 |
| cgo 回调线程不是 JVM 线程 | JNI 崩溃 | Rust 统一 `AttachCurrentThread` + 线程键 `Detach`；假后端测试多线程回调 |
| FFI 内存与 panic 规则被违反 | 崩溃、泄漏 | 4.2 规则；Rust 端 `FkBuf` 封装为 RAII；ASan 构建的主机测试 |
| MVS 上调共享依赖（N1） | 内核行为与其发布版不同 | 每次构建报告上调列表；升级内核时人工审阅；三内核冒烟测试 |
| Xray 连接跟踪依赖私有字段（N5） | 升级后静默失效 | 胶水层测试断言字段存在与类型；失败即构建失败 |
| cronet（NaiveProxy）在新 c-shared 库中的链接 | 构建失败或运行时缺符号 | 第 5 阶段实测；libbox 已证明 Android c-shared 可用 |
| 迁移期两个 Go 运行时不能共存（2.1） | 新旧路径不能运行时切换 | 构建开关（10），CI 两种 APK 都构建 |
| 首次调用阻塞在 Go 初始化 | 界面卡顿 | 只在后台线程加载与首次调用（4.3） |
| 旧版数据（`http_rewrite` 字段、抓包设置） | 升级后配置被内核拒绝 | 一次性迁移并保留原文件；第 7 阶段测试降级 |

## 9. 验证方法

- **每个 PR**：`cargo test`（控制路径、状态机、流处理用假 `CoreHost`）、`cargo clippy -D warnings`、`newui.yml`、`build-debug-apk.yml`；胶水层 `go test`（ABI 往返、三内核启停与切换，linux/amd64）；CI 基础设施失败重跑一次，同一失败两次即停止并报告。
- **构建**：`go mod verify`；ELF 架构、16 KiB 对齐、导出符号、版本检查；模块上调报告。
- **模拟器探针**（x86_64 + `android/amd64` 构建）：加载、TUN、protect、三内核启停与切换、20 次循环后 goroutine/fd/RSS 不增长。
- **实机清单**（arm64）：三内核启动/重载/切换/停止；Wi-Fi/移动切换；分组、测速、连接、日志、仪表盘；分应用与应用规则；Tailscale、Taildrop、USB/IP、OpenConnect、OpenVPN；NaiveProxy；抓包/MITM/改写与 QUIC 回退；旧配置与备份迁移；OOM 与崩溃报告；断网保护；Android 7/10/14/15。
- **体积与内存**：每阶段记录 APK、`libfkcore.so`、`libfkui.so` 大小与空载/负载 RSS，与上一阶段比较。

## 10. 阶段计划

`libgojni.so` 与 `libfkcore.so` 不能同进程共存，第 3～7 阶段用 Gradle 构建开关 `fkCore`（默认关）：关时仍是 libbox，开时只用 `libfkcore`，CI 两种 APK 都构建，开时尚未迁移的内核显示“未就绪”。

| 阶段 | 内容 | 默认运行时 | 主要验证 |
| --- | --- | --- | --- |
| 1 | 本文 | libbox | 维护者审阅 |
| 2 | 配置识别/数组/规则编译/订阅/Geo/日志级别/HTTP 迁 `client-core`（Go 测试为黄金样本）；6.2 中与运行无关的工具函数迁 Rust | libbox | 黄金样本一致；现有 CI |
| 3 | `apps/android/fkcore` 骨架、`build-fkcore`、C ABI 与 `fkcore.proto`、Rust `CoreHost`（dlopen + 假后端）、`CoreController`；mihomo 经 ABI；构建开关 | libbox | 假后端测试；ABI 往返；模拟器探针；mihomo 实机清单 |
| 4 | Xray 经 ABI（含连接跟踪）；分享链接转换迁 Rust（替代 libXray） | libbox | 分享链接黄金样本；Xray 清单 |
| 5 | sing-box 经 ABI：`PlatformInterface`、接口监视、`local` DNS、`StartedService` 全部方法（含 USBIP 双向流）；NaiveProxy 实测 | libbox | sing-box 与工具类实机清单 |
| 6 | 出口/服务检测、局域网、STUN、终端 PTY、远程控制（按 11.7）迁 Rust | libbox | 现有用例迁为 Rust 测试 |
| 7 | Rust 抓包/MITM/改写层（4.10），`http_rewrite` 字段迁移 | libbox | 现 `httpdebug`/`mitm` 用例改写为 Rust 测试；实机抓包 |
| 8 | 默认切到 `libfkcore`；删除 gomobile、`libbox.aar`、`apps/android/core`、补丁与脚本、Kotlin 转换层；文档更新 | libfkcore | 全部清单；删除行数统计 |

## 11. 需要维护者确认的事项

1. 4.3：Rust 用 `dlopen`（延迟加载、不卸载）调用 `libfkcore.so`，而不是链接期依赖。
2. 4.1：sing-box 平台能力由胶水层**直接实现 `adapter.PlatformInterface`**（参照 `experimental/boxdd`）；备选是导入上游 `experimental/libbox` Go 包（不经 gomobile，可复用其监视器与 DNS 传输，但带进 `CommandServer` 等不用的代码）。
3. N1：接受 MVS 上调的共享依赖（含 sing-box 用 `metacubex/utls v1.9.0-alpha.1`），不加 `replace`。
4. N3：不打 sing-tun 补丁后，Root 自动重定向在 4 字节 ingress map 设备上不可用——接受，或先向上游 sing-tun 提交修复。
5. N5：Xray 连接列表继续用 `reflect`/`unsafe` 跟踪器（有测试守护），或放弃该功能。
6. 4.10：抓包改为“内核路由 → Rust 抓包层（`127.0.0.1` + 随机凭据）”，`http_rewrite` 迁出为独立字段。
7. 远程控制另一台设备的 sing-box：保留（Rust 引入 `tonic`，仅此一处 gRPC）或去掉。
8. 10：迁移期用构建开关、两种 APK 并行，第 8 阶段一次切换默认并删除旧路径。
9. 6.4 的其余变化（sing-box DNS 计数口径、报告加密选项去掉）。
