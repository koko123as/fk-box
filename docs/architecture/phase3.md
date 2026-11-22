# 第三阶段：命令订阅调度与收尾

第二阶段（PR #32–#38）把与平台无关的业务逻辑迁入 `crates/client-core`。第三阶段处理第二阶段留下的项目，仍是一个切片一个草稿 PR、逐个堆叠，对现有用户行为不变。

| 项目 | 结论 | 切片 |
| --- | --- | --- |
| client-ui 的 clippy 告警 | 清理：`clippy -p fkbox-client-ui --all-targets -D warnings` 通过；`type_complexity` 在 crate 级放行（页面状态本就是闭包加共享单元） | **3a** |
| 命令订阅调度（`CommandClient` 何时连、连到哪、页面可见性、前后台、远程会话切换） | 规则迁入 client-core `sessions.rs`；Kotlin 只持有 libbox 命令客户端并按目标连/断 | **3b** |
| Rust 直连 libbox 命令服务（gRPC） | 见 3c 评估 | **3c** |
| `apps/android/core` 独立 `go.mod` | 见 3c 评估 | **3c** |
| 废弃代码清理 | 删除迁移后不再引用的 Kotlin/Rust 代码 | **3d** |

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
