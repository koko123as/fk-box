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
