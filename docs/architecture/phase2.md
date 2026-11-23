# 第二阶段：业务逻辑迁入 `crates/client-core`

第一阶段（PR #31，`arch/cor-layout-p1`）只调整目录结构。第二阶段把 Android 宿主（Kotlin）里与平台无关的业务逻辑迁入 Rust `fkbox-client-core`，让将来的 Windows 客户端能复用；分若干切片（2a、2b……）逐个堆叠提交，每个切片对现有用户保持行为不变。

分工原则（已定架构）：

- **Rust `client-core`**：共享业务（存储、设置、配置元数据、订阅、路由规则模型、备份/WebDAV、更新检查、界面状态控制）。
- **Go `apps/android/core`（libbox）**：各内核的配置转换、校验、分享链接转换、核心生命周期，以及只能在内核里做的探测（出口、服务状态、DNS 计数、抓包）。这些依赖各内核自己的 Go 解析器，用 Rust 重写会和内核行为产生偏差。
- **Kotlin `apps/android/app`**：只保留 Android 平台能力——`VpnService`、前台服务与通知、权限、应用列表、快捷设置磁贴、Shizuku/Root、Keystore、文件选择器、WorkManager 调度入口。

## 盘点：业务逻辑现在的位置与目标

“切片”一栏为计划迁移的切片；“—”表示不迁移。

### Kotlin `apps/android/app`

| 项目 | 现在位置 | 目标 | 切片 |
| --- | --- | --- | --- |
| 配置列表元数据（名称、顺序、图标、类型、订阅 URL、自动更新、间隔、内核、流量/到期、网站、`intervalUserSet`） | `database/Profile.kt`、`TypedProfile.kt`（Room `profiles.db` 表 `profiles`，`typed` 列为 Parcel 二进制） | Rust client-core `storage` | **2a** |
| 远程控制服务器列表 | `database/RemoteServer.kt`、`RemoteServerManager.kt`（`profiles.db` 表 `remote_servers`） | Rust client-core `storage`（URL 规范化/校验留 Kotlin 至 2e） | **2a**（存储） |
| 服务设置键值（已选配置、服务模式、分应用代理、内核开关、OOM、Geo、路由规则原文、Tailscale SSH 等） | `database/Settings.kt`、`preference/KeyValueEntity.kt`、`RoomPreferenceDataStore.kt`（Room `settings.db` 表 `KeyValueEntity`） | Rust client-core `storage`（键值、类型保持） | **2a**（存储） |
| 界面偏好（主题、配色、仪表盘卡片、代理页布局……）及校验 | `bridge/BoxPreferences.kt`、`BoxBridge.setPreferences`（SharedPreferences `flclash_ui` 的 `value`） | Rust client-core `preferences` | **2a** |
| 备份身份 `backupOrigin` | SharedPreferences `flclash_ui` | Rust client-core `storage` | **2a**（存储） |
| Room 数据库、SharedPreferences 到新存储的迁移 | — | Rust client-core `legacy` | **2a** |
| 服务设置的校验与组装（`BoxSettings.validate`、`BoxBridge.settings()/patchSettings`） | `bridge/BoxSettings.kt`、`BoxBridge.kt` | Rust client-core（副作用——扫描应用、切换查询方式、重启服务——留 Kotlin） | 2e |
| 订阅下载、请求头（UA）、`subscription-userinfo`/`profile-update-interval`/`profile-web-page-url` 解析 | 已迁：client-core `subscription.rs`（解析、元数据、拉取流程）；Kotlin 留 `utils/HTTPClient.kt` 的 UA 规则与 `database/CoreKernel.kt` 的 `fetch`（调 Go `ui_subscription.go`） | 头解析与元数据计算 → Rust；HTTP 请求本身仍经 libbox（Android 证书库） | **2b** |
| 订阅自动更新调度、亮屏补更新 | 已迁：client-core `subscription::schedule`/`due`/`is_due`/`update_all`；`bg/UpdateProfileWork.kt` 只剩 WorkManager 入队、亮屏广播与熄屏推迟（Android 电源状态） | 调度策略 → Rust；WorkManager 入口留 Kotlin | **2b** |
| JSON 数组配置拆分后的命名与跟随（`ProfileArray`） | 已迁：client-core `profile_array.rs`；`database/ProfileArray.kt` 只剩数据类与调用；拆分本身仍是 Go `SplitCoreConfigArray` | Rust | **2b** |
| 配置保存流程（修订号检查、原子写入、失败回滚，`ProfileChange`/`ProfileContent`/`BoxProfiles`） | 内容替换（编辑保存、手动/自动订阅更新）已迁 client-core `content.rs`；服务应用与恢复（`ProfileChange`、`replaceLocked` 外壳）留 Kotlin；新建/编辑表单保存 `BoxProfiles.saveLocked`、`saveAll`、删除仍在 Kotlin | 流程与文件写入 → Rust；内核校验/格式化调用 Go | **2b**（替换）/2e（表单保存） |
| 路由规则数据模型、校验、序列化（`CoreRouting`） | `bridge/CoreRouting.kt` | Rust；按内核编译仍在 Go `core_routing*.go` | 2c |
| Geo 资源 URL 策略与更新间隔 | `bg/GeoResources.kt`（`GeoPolicy`）、`bg/UpdateGeoWork.kt` | 策略 → Rust；下载与文件放置仍经 Go `core_geo.go` | 2c |
| 备份打包/校验/恢复（`bbox-1`） | `bridge/BoxBackups.kt` | Rust | 2d |
| WebDAV 账号与上传/下载 | `bridge/BoxWebDAV.kt`（SharedPreferences `bbox_webdav`）；Go `ui_http.go`（请求） | 账号与流程 → Rust；密码加密留 Kotlin（`utils/SecretBox.kt`，Android Keystore） | 2d |
| 更新检查（GitHub Release 元数据、版本比较） | `bridge/BoxUpdates.kt` | Rust | 2d |
| 终端（Ghostty）设置、字体导入 | `bridge/BoxTerminalSettings.kt` | 设置值 → Rust；字体文件选择留 Kotlin | 2d |
| 抓包设置 | `bridge/CaptureSettings.kt`、`BoxCapture.kt` | 设置模型 → Rust；证书生成留 Kotlin（Keystore）+ Go | 2e |
| 界面状态与命令分发（仪表盘/代理组/连接/日志的状态模型、命令总线） | `backend/*ViewModel.kt`、`bridge/BoxBridge.kt`、`SlintHost.kt`、`LogStream.kt`、`ConnectionWire`、`DashboardConfigCache.kt`、`VisiblePolling.kt` | Rust client-core 控制器；数据源仍为 libbox 命令客户端 | 2e |
| 仪表盘 IP 质量缓存 | SharedPreferences `dashboard_ip_quality` | Rust client-core `storage` | 2e |
| 应用列表缓存（`AppListCache`）、应用标签 | `bridge/AppListCache.kt` | 留 Kotlin（PackageManager） | — |
| VpnService、前台服务、通知、磁贴、开机启动、网络监听、Root/Shizuku、USB/IP、Taildrop 发送 | `bg/*`、`vendor/*`、`usbip/*` | 留 Kotlin | — |
| 扫码、文件选择、浏览器、终端宿主 | `compose/qr/*`、`bridge/BoxQrScanner.kt`、`NativeEditor.kt`、`Box*Factory.kt` | 留 Kotlin | — |
| 崩溃/OOM/耗电报告收集 | `bg/*ReportManager.kt` | 留 Kotlin（报告列表展示可在 2e 迁移） | — |

### Go `apps/android/core`（非内核专属部分）

| 项目 | 现在位置 | 目标 | 切片 |
| --- | --- | --- | --- |
| 订阅请求（带响应头）、订阅 UA | `ui_subscription.go`、`core_profile.go`（`CoreSubscriptionUserAgent`） | 留 Go（复用 libbox 的 TLS/证书库与 UA 规则）；原始响应头交 Rust 解析（Go 接口未改） | 2b |
| 订阅内容中 `http_rewrite` 的处理 | `ui_subscription_rewrite.go` | 留 Go（sing-box 专属） | — |
| WebDAV/HTTP 请求 | `ui_http.go` | 留 Go（证书库）；流程迁 Rust | 2d（接口调整） |
| Geo 资源下载与默认 URL | `core_geo.go` | 留 Go（第三阶段再评估） | — |
| JSON 数组拆分、Xray 数组合并、格式化、校验、分享链接转换、路由编译、日志级别、DNS、出口探测、服务状态检测、LAN 代理、抓包 | `core_*.go`、`ui_*.go` | 留 Go（内核专属） | — |
| 更新检查 | 无（在 Kotlin） | — | — |

## 切片顺序

1. **2a 存储层**（本 PR）：`client-core` 的 SQLite 存储（设置键值、配置元数据、远程服务器、界面偏好），首次启动从 Room `profiles.db`/`settings.db` 与 SharedPreferences `flclash_ui` 一次性迁移；Kotlin 的 `Settings`/`ProfileManager`/`RemoteServerManager` 改为经 JNI 读写同一存储，Room 依赖移除；Rust 界面直接从 `client-core` 读配置列表与界面偏好、写偏好与排序。
2. **2b 订阅**（`arch/cor-core-p2b`）：头解析、元数据计算、数组拆分命名、自动更新策略迁 Rust；保存/更新流程（修订号、原子写入、回滚）迁 Rust，内核校验仍调 Go。
3. **2c 路由规则模型与 Geo 策略**：`CoreRouting` 模型与校验、Geo URL 策略迁 Rust；界面直接读写路由规则。
4. **2d 备份/WebDAV、更新检查、终端设置**。
5. **2e 界面控制器**：设置组装/校验、命令分发、状态模型迁 Rust，Kotlin `BoxBridge` 收缩为平台能力的薄层。

2a 之后界面的“服务设置”快照仍由 Kotlin `BoxBridge.settings()` 组装（数据已在 `client-core` 存储中）：它混有电池优化豁免等 Android 状态，以及路由规则（2c）和 Geo URL（2c）模型的解码，写入还带有应用扫描、包查询方式切换、服务重启等副作用。这部分在 2c/2e 随对应模型一起迁移。

## 2a 设计要点

- **存储**：`client-core::storage::Store`，rusqlite（内置 SQLite），文件 `<filesDir>/fkbox/client.db`，WAL；表 `meta`（含 `schema_version`、迁移标记）、`settings`（键、类型码、JSON 值，类型码沿用 `KeyValueEntity`）、`profiles`（Room 字段展开为列，另有内核解析缓存）、`remote_servers`、`preferences`（界面偏好 JSON 与备份身份）。路径由平台外壳以构造参数传入，不在 `client-core` 中写死。
- **进程内唯一实例**：`libfkui.so` 中只有一个 `Store`（单连接加锁），由 Kotlin 首次访问时经 JNI 打开（`Application` 进程即使没有界面也能用），Rust 界面在 `android_main` 中取用同一实例，避免同一进程内两份 SQLite 对同一文件加锁。
- **迁移**：只在新存储尚无数据时执行；先把旧库（含 `-wal`/`-shm`）复制到临时目录再读，原文件不修改；整个导入在一个事务内完成并写入迁移标记，重复执行无副作用。旧库保留不删，作为本版本的回退（降级到旧版本时旧版仍读取原数据）。
- **Kotlin 面**：`Settings`、`ProfileManager`、`RemoteServerManager` 的接口不变，内部改为 `CoreStore`（JNI）；VPN 服务启动时读取已选配置和设置的路径不变。

## 2b 设计要点

- **模块**：`client-core::subscription`（头解析、`Metadata::after`、`schedule`/`due`/`is_due`/`update_all`）、`profile_array`（命名与元素跟随）、`content`（内容替换流程）。存储表结构不变（`user_version` 仍为 1），无数据迁移，可直接降级到 2a。
- **内核桥**：`content::Kernel` 由平台实现，方法 `fetch`/`split`/`remarks`/`prepare`。Android 上 Kotlin 调 `CoreStore.callKernel`（JNI `nativeCallKernel`，把 `CoreKernel` 对象一并传入），Rust 在同一线程回调 `CoreKernel.call(method, argsJson)`，后者调 libbox；Kotlin 不抛异常，错误以 `{"error": ...}` 返回。不需要内核的调用仍走 `CoreStore.call`。
- **保存流程**（`content::replace`）：读文件 → 修订号检查 → 订阅时经内核拉取并按 `profile_array::select` 跟随元素 → 内核 `prepare`（转换 + 各内核校验）→ 内容变化才原子写入（同目录临时文件 + fsync + rename）→ 只更新该行的 `last_updated` 与订阅列（`ContentState`）。中途失败自行撤回已写的文件。需要应用到服务时返回 `token`，Kotlin 应用成功后 `content.finish`，失败则 `content.rollback`（文件与这些列放回）再按原流程恢复服务；服务模式的回滚仍在 Kotlin。未完成的 token 最多保留 16 个。
- **排序竞态**：后台/手动订阅更新与编辑保存不再整行写回 `profiles`。`Store::record_content_save` 在持有连接锁的一步内读出该行的 `interval_user_set` 与旧值、只写 `ContentState` 列；回滚同样只写这些列。因此更新期间界面直接写入的 `user_order`（以及同期的改名等）都不会被旧副本覆盖（`content::tests::background_updates_never_touch_the_order_or_other_edits`）。表单保存（`saveLocked`）仍整行写但不写排序（2a 的规则），且在 `ProfileContent.mutex` 内读取最新行。
- **WorkManager**：`UpdateProfileWork` 取 `subscription.schedule`（周期 ≥15 分钟、首次延迟）与 `subscription.due`，锁内用 `subscription.isDue` 复查，熄屏推迟仍在 Kotlin。
- **推迟**：新建/编辑表单保存、`saveAll`、删除配置的流程（2e，与界面控制器一起）；备份恢复里的网站地址校验仍在 `ProfileWireFields`（2d）；界面的“全部更新”已是 Rust 页面逻辑，逐个调用 `updateProfile`，筛选规则另有 `subscription::update_all` 供 2e 使用。
- **已知差异**：无效 UTF-8 的配置文件，Rust 与 Java 的替换字符数量可能不同，导致修订号与 Kotlin 读出的不一致（编辑保存会提示“配置已更改”，重新载入即可）。
