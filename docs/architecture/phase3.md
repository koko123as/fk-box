# 第三阶段：命令订阅调度与收尾

第二阶段（PR #32–#38）把与平台无关的业务逻辑迁入 `crates/client-core`。第三阶段处理第二阶段留下的项目，仍是一个切片一个草稿 PR、逐个堆叠，对现有用户行为不变。

| 项目 | 结论 | 切片 |
| --- | --- | --- |
| client-ui 的 clippy 告警 | 清理：`clippy -p fkbox-client-ui --all-targets -D warnings` 通过；`type_complexity` 在 crate 级放行（页面状态本就是闭包加共享单元） | **3a** |
| 命令订阅调度（`CommandClient` 何时连、连到哪、页面可见性、前后台、远程会话切换） | 规则迁入 client-core `sessions.rs`；Kotlin 只持有 libbox 命令客户端并按目标连/断 | **3b** |
| Rust 直连 libbox 命令服务（gRPC） | 暂不做：调度已在 3b 迁入 Rust，传输继续用 libbox 命令客户端；等 Windows 客户端立项再做（见 3c） | **3c**（评估） |
| `apps/android/core` 独立 `go.mod` | 不做：这些文件就是 gomobile 绑定的 `libbox` 包本身（见 3c） | **3c**（评估） |
| 废弃代码清理 | 删除迁移后不再引用的 Kotlin/Rust 代码 | **3c** |

## 3a 要点

- 修正 7 处 clippy 告警（冗余闭包、`let…else` 改 `?`、可合并的 `if`、空分支、冗余模式匹配、测试模块之后的条目）。
- `pages/routing.rs` 预览的 `tab_core` 参数只用于一个空 `if`，一并删除。
- 本地检查改为两个 crate 都跑 clippy `-D warnings`。

## 3b 要点

- 新增 client-core `sessions.rs`（`sessions.plan`）：输入为前后台、亮屏、本地服务是否已启动、远程服务器及其监视连接、已加载配置的令牌、页面可见性（流量、连接页计数、日志页）；输出每条订阅（`clashMode`、`status`、`groups`、`connections`、`log`、`remoteMonitor`）的目标 `{connect, remoteServerId, localConfig}`。
- 规则与原来各 ViewModel 的 `combine` 一致：都要求前台且服务可达；`status`、`connections` 另需亮屏和对应页面；本地 `log` 需已加载配置且随每次加载/重载续订；`remoteMonitor` 在远程会话且前台时运行。
- Kotlin 新增 `utils/CommandSessions.kt`：收集上述输入、经 JNI 调 `sessions.plan`，每个 `CommandClient` 用 `follow(stream)` 跟随自己的目标——目标变化即重连或断开。删除 Dashboard/Groups/Connections/Log ViewModel 与 `RemoteControlManager` 里的调度代码；`BoxBridge` 的可见性命令改报给 `CommandSessions`。
- 不变：libbox 命令客户端（gomobile）仍是传输；`ServiceNotification` 的通知速率订阅在服务里按亮屏开关，留 Kotlin。
- 风险：可见性计数从各 ViewModel 移到进程级对象，Activity 未走 `BoxBridge.close()` 就被销毁时计数可能残留；远程监视改为异步按目标连接，进入/退出远程会话的时序需实机确认。

## 3c：评估结论与废弃代码清理

**Rust 直连 libbox 命令服务——暂不做。** 命令服务是 sing-box `daemon.StartedService` 的 gRPC（本地 Unix 套接字；远程控制为 TLS 加密钥），并由 `core_api.go` 的 `coreStartedService` 包装成多内核接口。Rust 直连需要在 `libfkui.so`（已约 24 MB）里加入 tonic/prost/rustls，复刻远程连接的地址、证书与密钥处理，并随每次 sing-box 升级同步 proto 与 `core_grpc_passthrough.txt`。第二阶段推迟它的原因——调度规则留在 Kotlin——已由 3b 解决：规则在 `sessions.rs`，宿主只负责按目标连/断。等 Windows 客户端立项、需要不经 gomobile 的传输时，再在 client-core 加 gRPC 客户端，`sessions.rs` 不变。

**`apps/android/core` 独立 `go.mod`——不做。** 这里 50 个 Go 文件全部是 `package libbox`：构建时复制进 sing-box 的 `experimental/libbox`，由 gomobile 绑定成 Kotlin 的 `Libbox.*`，并直接使用该包未导出的类型（`CommandServer`、`StartedService` 等）；`httpdebug`、`dns`、`route`、`option` 也是打补丁进 sing-box 源码树的内部包。独立模块意味着把它们改成导出 API 并在构建时改写 sing-box 的 `go.mod`（`replace`），三个内核共用模块的版本核查（[libbox.md](../../libbox.md)）也要多一层，收益只是目录上的独立。维持现状。

**清理：**
- 宿主已不再发送的界面事件 `connections`、`connectionStats` 从 `client-ui` 删除；截图工具 `shot.rs` 改发 `connectionEvents`（与 `BoxBridge.flushConnections` 相同的批次）。
- 3b 之后无人读取的 `serviceStatus` 流（Connections、Groups、Dashboard ViewModel 的公开属性）删除；`BoxService.cancelNotification`（无调用）删除。
- `.gitignore` 加 `__pycache__/`。
- 检查过：Kotlin 无其他未引用的函数/类（`vendor/` 不动）；Go 导出函数除测试外都有调用；client-ui 在非 Android 目标下被 `allow(dead_code)` 掩盖的只有 Android 专用的 `fonts.rs`。
