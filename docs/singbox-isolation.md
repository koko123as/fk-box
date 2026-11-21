# sing-box 进程隔离方案（P8，供实现用）

目标：三个内核全部由 fk-core 拉起、全部是独立子进程；app 进程不加载任何内核，APK 里不再有 gomobile / `libbox.aar`。替代 `docs/kernel-isolation.md` 决策 D4。

## 0. 结论先行

- 新增可执行文件 **`fk-singbox`**：本仓库 `core/cmd/fk-singbox/` 下的一个 `main` 包，按 tag 引用**未修改的**官方 sing-box（与现在官方 libbox 同一 tag、同一组 build tags），作为 `libfksingbox.so` 放进 `jniLibs`，fk-core 像拉 Xray/mihomo 一样拉它。
- `fk-singbox` 内部使用官方 `daemon.NewStartedService`（`daemon/started_service.go`），并自己实现 `adapter.PlatformInterface`（`adapter/platform.go`）。libbox 里 Kotlin 实现的那些平台回调，改为 Go 实现或经 fk-core 反向调用 app。
- 控制面：`fk-singbox` 在 fk-core 私有目录的 unix socket 上提供官方 `daemon.StartedService` gRPC；fk-core 的 `core_service_grpc.go` 在 core = sing-box 时**透明转发**整个服务到这个 socket。app 只连 fk-core 的 `command.sock`，三内核一个接口。
- 官方 StartedService 已包含 Tailscale（状态、ping、exit node、SSH、Taildrop）、NetworkQuality、STUN、OpenConnect、OpenVPN、USB/IP、连接流、分组、clash mode、日志，所以 `UiTools.kt`/`UiStreams.kt` 不再需要 libbox `CommandClient`。
- 关键前提：沿用 D3 的做法，`VpnService.Builder.addDisallowedApplication(自己的包名)`。app UID（含 fk-core 及其子进程）的流量天然绕过 VPN，因此**不需要逐 socket 的 `protect`**，这正是 D4 里"每个 socket 跨进程"的问题不存在的原因。代价同 D3：sing-box 的 tun `stack` 必须是 `gvisor`（`system`/`mixed` 在自排除时看不到流量）。

## 1. 进程与数据流

```
app 进程 (Kotlin, VpnService)
  │  Binder 无；unix socket: fk-core RPC (FkLib) + command.sock (gRPC)
  ▼
fk-core (Go 可执行)
  ├─ xray        (子进程, TUN = fd 3)
  ├─ mihomo      (子进程, TUN = fd 3)
  └─ fk-singbox  (子进程, TUN = fd 3, 控制 = unix socket gRPC daemon.StartedService
                  + 平台回调 socket：fk-singbox -> fk-core -> app)
```

## 2. fk-singbox 实现

文件：`core/cmd/fk-singbox/main.go`、`platform.go`、`tun.go`、`monitor.go`、`owner.go`。

1. **构建**：独立 `go.mod`（`module github.com/koko123as/fk-box/core/cmd/fk-singbox`），`require github.com/sagernet/sing-box <tag>`，CI 里用 `replace => ../../../.core`（已检出的官方 tag，`git status --porcelain` 必须为空）。build tags 与当前构建官方 libbox 的列表完全一致（从 `.github/actions/build-kernels` 里读，不要手抄），`CGO_ENABLED=0`，`GOOS=android`，`-trimpath -ldflags "-s -w -buildid="`，输出 `jni/<abi>/libfksingbox.so`。在 `tools/build_kernels.sh` 加一段，与 mihomo 那段同形。
2. **启动参数**：`fk-singbox --control <sock> --platform <sock> --workdir <dir> --tun-fd 3`。配置不走命令行，由 fk-core 通过 `StartOrReloadService(profileContent, OverrideOptions)` 下发（与 P4 在 app 里做的完全一样，只是挪到 fk-core）。
3. **StartedService**：`daemon.NewStartedService(daemon.ServiceOptions{...})`，注册到 `grpc.NewServer()`，监听 `--control`。`ServiceOptions` 里需要的 `Handler`/平台对象由下面的 platform 提供。参考官方 `experimental/boxdd/server.go` 与 `daemon/server.go` 的组装方式，照抄结构，不 fork。
4. **PlatformInterface**（`adapter/platform.go` 的每个方法都要有明确取舍）：

| 方法 | 实现 |
|---|---|
| `UsePlatformAutoDetectInterfaceControl` | `false`（app 自排除，不需要 protect） |
| `UsePlatformInterface` / `OpenInterface` | `true`。把 sing-box 算好的 `tun.Options`（地址、路由、MTU、include/exclude package、http proxy）序列化，经 `--platform` socket 发给 fk-core；fk-core 反向调用 app 的 `vpn.openTun`（已有，P3），fd 经 SCM_RIGHTS 回到 fk-core，再 SCM_RIGHTS 给 fk-singbox；用 `tun.Options.FileDescriptor` 打开。**映射逻辑**从 app 里现在的 `PlatformInterfaceWrapper.openTun` 逐项移植，保证与 libbox 行为一致。`--tun-fd 3` 只用于首启加速，reload 时走上面的请求。 |
| `UsePlatformDefaultInterfaceMonitor` / `CreateDefaultInterfaceMonitor` | `true`。app 已有 `DefaultNetworkMonitor`，把推送内容扩成 `{interfaceName, interfaceIndex, dns[]}`，经 `serviceDefaultNetworkChanged` 送 fk-core，再转给 fk-singbox 的 monitor 回调。`serviceNetworkSwitched` 对 sing-box 改为触发 monitor 回调（sing-box 自己会关旧连接）。 |
| `UsePlatformNetworkInterfaces` / `NetworkInterfaces` | `true`。Android 11+ 普通 app 不能用 netlink 枚举网卡，子进程同 UID 同样受限；经 fk-core 反向调用 app `net.interfaces`（Kotlin 用 `NetworkInterface`/`ConnectivityManager`，从 libbox 包装里移植）。 |
| `UsePlatformConnectionOwnerFinder` / `FindConnectionOwner` | `true`。反向调用 app `vpn.findConnectionOwner`（`ConnectivityManager.getConnectionOwnerUid` + `PackageManager`），fk-singbox 侧 LRU 缓存（key 五元组，TTL 30 s）。配置里没有 package/process 规则时返回 `false` 以免开销。 |
| `ReadWIFIState` / `UsePlatformWIFIMonitor` | 反向调用 app；无权限时返回空，不报错。 |
| `UsePlatformNotification` / `SendNotification` | 反向调用 app `notify.send`（复用现有通知通道）。 |
| `ClearDNSCache` | no-op。 |
| `UsePlatformNeighborResolver` | `false`（初版）。 |
| `UsePlatformShell` | `false`（Tailscale SSH 服务端不支持；客户端 `StartTailscaleSSHSession` 不受影响）。 |
| `UnderNetworkExtension` 等 Apple 专用 | `false`。 |

5. **反向调用通道**：`--platform` 是 fk-singbox→fk-core 的 unix socket，复用 `core/rpc` 的现有编解码；fk-core 对需要 app 的方法再走已有的 host 反向调用（`vpn.openTun` 已存在，新增 `vpn.findConnectionOwner`、`net.interfaces`、`wifi.state`、`notify.send`）。在 `protocol/methods.json` 登记，`python3 tools/protocol.py gen` 生成。
6. **退出**：收到 SIGTERM 或 control socket 上 `CloseService` 后关闭实例、关 TUN fd 再退出；fk-core 现有的崩溃重启/退避/FATAL 逻辑直接复用。

## 3. fk-core 改动

- `core_service.go`：`ServiceStart` 增加 `CoreSingBox` 分支，与 Xray/mihomo 同路径（准备配置 → 拉进程 → 等控制口就绪 → 标记 running）。`SingBoxServiceContent/Started/Stopped`、`closeCommandServer` 这些 P4 为 app 内 libbox 加的入口删除。
- `core_service_grpc.go`：core = sing-box 时，`startedService` 的每个方法转发到 fk-singbox 的 gRPC client（同一 proto，用 `grpc.ClientConn` 直接调用；流式方法逐条转发）。`internal/daemonpb` 从**同一 sing-box tag** 的 `daemon/started_service.proto` 重新生成，CI 加一条检查：仓库内 proto 与 tag 内一致。
- `core_routing_singbox.go`：P4 写在 Kotlin `BoxService.kt` 里的分流规则全部移入这里（Windows 也要用）。tun `stack` 统一改写为 `gvisor`，用户写了别的值时日志 warn 一次。
- 抓包（P5）：`fkbox-capture` socks 出站与 `fkbox-local` 本地入站逻辑不变，只是注入点从"给 app 的内容"变为"给 fk-singbox 的内容"。
- 内核更新：`fk-singbox` 与 Xray/mihomo 一样支持运行时替换（下载到 `filesDir/kernels/`，校验 sha256 + 我们发布时的签名，失败回退到 APK 自带的 `libfksingbox.so`）。CI 的 release 产出 `fk-singbox-<tag>-android-<abi>` 资产。

## 4. app（Kotlin）改动

- 删除 `libs/libbox-official.aar` 依赖与所有 `io.nekohasekai.libbox.*` import；APK 中不再有 gomobile 库。
- `BoxService.kt`：sing-box 与另两个内核走同一条 `FkLib.serviceStart` 路径；删除 `CommandServer`、`OverrideOptions`、`PlatformInterfaceWrapper` 中 libbox 专用的部分（`openTun` 映射逻辑移到 Go 后删除；`vpn.openTun` 反向调用保留）。
- `UiTools.kt` / `UiStreams.kt`：改用已有的 fk-core gRPC 客户端（Xray/mihomo 已在用）调用 Tailscale/NQ/STUN/OpenConnect/OpenVPN/连接流。`Libbox.*` 常量换成 `ProtocolGen.kt` 或本地常量。
- 新增 host 方法：`vpn.findConnectionOwner`、`net.interfaces`、`wifi.state`、`notify.send`（`FkCoreHost.kt`）。
- `DefaultNetworkMonitor`：推送接口名与 index。

## 5. 守卫（CI 必须拦住回退）

- `tools/verify_multicore.py`：`clients/` 下出现 `io.nekohasekai.libbox` 即报错；`build.gradle.kts` 出现 `libbox` 即报错。
- `tools/check_kernel_isolation.py`：`core/fk` 不得 import `github.com/sagernet/sing-box`（只有 `core/cmd/fk-singbox` 可以）；fk-core 二进制里不得出现 sing-box 符号。
- 构建后检查：`.core` 工作区干净；`libfksingbox.so` 的 `go version -m` 显示的 sing-box 版本等于 tag。

## 6. 分阶段

| 阶段 | 内容 | 验收 |
|---|---|---|
| S1 | `fk-singbox` 可执行 + PlatformInterface（OpenInterface、默认网络、网卡、owner）+ 构建进 jniLibs | 单元测试；`go vet` GOOS=android；CI 产出 so |
| S2 | fk-core 拉起 fk-singbox、gRPC 透明转发、routing 从 Kotlin 移入 Go | `core_service_integration_test.go` 增加 sing-box 用例（用 fake tun fd，参考现有 Xray/mihomo 测试） |
| S3 | app 切换：BoxService、UiTools/UiStreams 改 fk-core gRPC；删 libbox.aar | build-apk 绿；守卫生效 |
| S4 | 运行时更新 fk-singbox；release 资产 | build-release 绿 |
| S5 | 文档：`kernel-isolation.md` 删除 D4，写 D6 = 本方案；`libbox.md` 改为"历史" | — |

每阶段一个分支 `arch/singbox-isolation-sN`，每个 PR CI 全绿再合并；S3 前 app 仍可用（S1/S2 只加不删）。

## 7. 真机验收清单

1. sing-box 配置连通；规则分流、默认出口。
2. Wi-Fi ↔ 4G 切换后 10 s 内恢复，无残留旧连接。
3. 按应用分流（include/exclude package）与 package 规则生效。
4. Tailscale 登录、状态、ping、exit node、Taildrop；OpenConnect、OpenVPN 认证挑战；STUN、网络质量。
5. MITM 抓包与 http_rewrite（sing-box 下）。
6. 连接列表、关闭连接、分组切换、clash mode、日志。
7. 杀掉 fk-singbox 进程：自动重启；连续崩溃进入 FATAL 并停止 VPN。
8. 内存：app 进程常驻内存应比 P7 明显下降；记录 fk-singbox RSS。
9. 运行时更新 fk-singbox 与回退。

## 8. 风险

- **gvisor 性能**：自排除强制 gvisor，吞吐低于 system 栈。若实测不可接受，备选：不自排除，改为 fk-singbox 的 `AutoDetectInterfaceControl` 经反向调用 app `protect(fd)`——需要把 fd 用 SCM_RIGHTS 送到 app，每连接一次跨进程，仅作为后备。
- **owner 查询延迟**：`getConnectionOwnerUid` 每次约 1 ms 级，必须缓存。
- **Tailscale 平台依赖**：它在 libbox 中用到的平台能力全部在上表里；逐项对照 `experimental/libbox` 中 Tailscale 相关调用确认无遗漏。
- **体积**：去掉 libbox.aar（约同量级），净变化应接近 0；以 CI 产物为准。
- **Windows**：同一个 `fk-singbox` 以 `GOOS=windows`、platform 置空（原生 wintun）构建，或直接用官方 `sing-box.exe`；fk-core 侧代码不分平台。
