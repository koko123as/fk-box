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
| 远程控制服务器列表 | `database/RemoteServer.kt`、`RemoteServerManager.kt`（`profiles.db` 表 `remote_servers`） | 已迁：client-core `remote_servers.rs`（地址规范化、校验、连接地址、显示名、表单保存）；界面直接读列表与保存，删除与连接仍经宿主（需要当前远程会话） | **2a**（存储）/**2e-2**（地址与表单） |
| 服务设置键值（已选配置、服务模式、分应用代理、内核开关、OOM、Geo、路由规则原文、Tailscale SSH 等） | `database/Settings.kt`、`preference/KeyValueEntity.kt`、`RoomPreferenceDataStore.kt`（Room `settings.db` 表 `KeyValueEntity`） | Rust client-core `storage`（键值、类型保持） | **2a**（存储） |
| 界面偏好（主题、配色、仪表盘卡片、代理页布局……）及校验 | `bridge/BoxPreferences.kt`、`BoxBridge.setPreferences`（SharedPreferences `flclash_ui` 的 `value`） | Rust client-core `preferences` | **2a** |
| 备份身份 `backupOrigin` | SharedPreferences `flclash_ui` | Rust client-core `storage` | **2a**（存储） |
| Room 数据库、SharedPreferences 到新存储的迁移 | — | Rust client-core `legacy` | **2a** |
| 服务设置的校验与组装（`BoxSettings.validate`、`BoxBridge.settings()/patchSettings`） | 已迁：client-core `service_settings.rs`（快照、校验、`plan`、原子写入）；`bridge/BoxSettings.kt` 已删；`BoxBridge.patchSettings` 只按 `plan` 做副作用（切换查询方式、扫描应用）再调 Rust 写入，之后重读启动参数、重排 geo 任务；电池优化豁免由 Kotlin 附加 | Rust client-core（副作用——扫描应用、切换查询方式、重启服务——留 Kotlin） | **2c**（原计划 2e） |
| 订阅下载、请求头（UA）、`subscription-userinfo`/`profile-update-interval`/`profile-web-page-url` 解析 | 已迁：client-core `subscription.rs`（解析、元数据、拉取流程）；Kotlin 留 `utils/HTTPClient.kt` 的 UA 规则与 `database/CoreKernel.kt` 的 `fetch`（调 Go `ui_subscription.go`） | 头解析与元数据计算 → Rust；HTTP 请求本身仍经 libbox（Android 证书库） | **2b** |
| 订阅自动更新调度、亮屏补更新 | 已迁：client-core `subscription::schedule`/`due`/`is_due`/`update_all`；`bg/UpdateProfileWork.kt` 只剩 WorkManager 入队、亮屏广播与熄屏推迟（Android 电源状态） | 调度策略 → Rust；WorkManager 入口留 Kotlin | **2b** |
| JSON 数组配置拆分后的命名与跟随（`ProfileArray`） | 已迁：client-core `profile_array.rs`；`database/ProfileArray.kt` 只剩数据类与调用；拆分本身仍是 Go `SplitCoreConfigArray` | Rust | **2b** |
| 配置保存流程（修订号检查、原子写入、失败回滚，`ProfileChange`/`ProfileContent`/`BoxProfiles`） | 内容替换（编辑保存、手动/自动订阅更新）已迁 client-core `content.rs`；服务应用与恢复（`ProfileChange`、`replaceLocked` 外壳）留 Kotlin；新建/编辑表单保存 `BoxProfiles.saveLocked`、`saveAll`、删除仍在 Kotlin | 流程与文件写入 → Rust；内核校验/格式化调用 Go | **2b**（替换）/2e（表单保存） |
| 路由规则数据模型、校验、序列化（`CoreRouting`） | 已迁：client-core `routing.rs`（界面路由页的重复副本一并改用它）；`bridge/CoreRouting.kt` 已删 | Rust；按内核编译仍在 Go `core_routing*.go` | **2c** |
| Geo 资源 URL 策略与更新间隔 | 已迁：client-core `geo.rs`（`needed`、`should_extract`、`due`/`schedule`、地址读写与校验）；`bg/GeoResources.kt` 的 `GeoPolicy` 只剩清单与记录文件的数据类和对 Rust 的调用，`UpdateGeoWork.kt` 只剩 WorkManager 入口 | 策略 → Rust；下载与文件放置仍经 Go `core_geo.go` | **2c** |
| 备份打包/校验/恢复（`bbox-1`） | 已迁：client-core `backup.rs`（打包、校验、单事务恢复、文件原件副本与回滚、`ProfileWireFields` 的恢复与网站地址校验）；`bridge/BoxBackups.kt` 只剩 Android 步骤（服务设置副作用、设置缓存、应用到服务与失败恢复） | Rust | **2d** |
| WebDAV 账号与上传/下载 | 已迁：client-core `webdav.rs`（账号存 `preferences` 表 `webdav` 键，校验、地址解析、上传/下载流程）；`bridge/BoxWebDAV.kt` 已删；请求仍经 Go `ui_http.go`，密码加密仍是 Kotlin `utils/SecretBox.kt`（Keystore），二者都经 `CoreKernel` 回调 | 账号与流程 → Rust；密码加密留 Kotlin（`utils/SecretBox.kt`，Android Keystore） | **2d** |
| 更新检查（GitHub Release 元数据、版本比较） | 已迁：client-core `updates.rs`；`bridge/BoxUpdates.kt` 已删；请求经 Go `UIHTTPGet` | Rust | **2d** |
| 终端（Ghostty）设置、字体导入 | 已迁：client-core `terminal.rs`（取值、校验、写入、备份部分）；`bridge/BoxTerminalSettings.kt` 只剩主题目录/预览、字体文件选择导入与删除 | 设置值 → Rust；字体文件选择留 Kotlin | **2d** |
| 抓包设置 | 已迁：client-core `capture.rs`（读取、一次性升级、修订检查、原子写入，校验经内核方法 `capture.validate`）；`bridge/CaptureSettings.kt` 只剩退役 Keystore 密钥清理与调用封装 | 设置模型 → Rust；证书生成留 Kotlin（Keystore）+ Go | **2e-1** |
| 界面状态与命令分发（仪表盘/代理组/连接/日志的状态模型、命令总线） | 已迁：连接列表 client-core `connections.rs`（2e-2）；日志记录 `logs.rs`、仪表盘显示推导 `dashboard.rs`、分组/节点/模式检查 `groups.rs`（2e-3），`LogStream.kt`、`LogBuffer`、`backend/model/Groups.kt` 已删。命令客户端连接调度（`CommandClient`、可见性与前后台）仍在 `backend/*ViewModel.kt`、`bridge/BoxBridge.kt`、`SlintHost.kt`、`DashboardConfigCache.kt`、`VisiblePolling.kt` | Rust client-core；数据源仍为 libbox 命令客户端，调度留 Kotlin 到第三阶段 | **2e-2**/**2e-3** |
| 仪表盘 IP 质量缓存 | 已迁：client-core `ip_quality.rs`（`preferences` 键 `ip_quality`，一次性导入旧 `dashboard_ip_quality.xml`）；界面直接读写，宿主方法已删 | Rust client-core `storage` | **2e-1** |
| 应用列表缓存（`AppListCache`）、应用标签 | `bridge/AppListCache.kt` | 留 Kotlin（PackageManager） | — |
| VpnService、前台服务、通知、磁贴、开机启动、网络监听、Root/Shizuku、USB/IP、Taildrop 发送 | `bg/*`、`vendor/*`、`usbip/*` | 留 Kotlin | — |
| 扫码、文件选择、浏览器、终端宿主 | `compose/qr/*`、`bridge/BoxQrScanner.kt`、`NativeEditor.kt`、`Box*Factory.kt` | 留 Kotlin | — |
| 崩溃/OOM/耗电报告收集 | `bg/*ReportManager.kt` | 留 Kotlin（报告列表展示可在 2e 迁移） | — |

### Go `apps/android/core`（非内核专属部分）

| 项目 | 现在位置 | 目标 | 切片 |
| --- | --- | --- | --- |
| 订阅请求（带响应头）、订阅 UA | `ui_subscription.go`、`core_profile.go`（`CoreSubscriptionUserAgent`） | 留 Go（复用 libbox 的 TLS/证书库与 UA 规则）；原始响应头交 Rust 解析（Go 接口未改） | 2b |
| 订阅内容中 `http_rewrite` 的处理 | `ui_subscription_rewrite.go` | 留 Go（sing-box 专属） | — |
| WebDAV/HTTP 请求 | `ui_http.go` | 留 Go（证书库）；流程迁 Rust（Go 接口未改，Rust 经 `CoreKernel` 的 `webdav.request`/`http.get` 调用） | 2d |
| Geo 资源下载与默认 URL | `core_geo.go` | 留 Go（第三阶段再评估） | — |
| JSON 数组拆分、Xray 数组合并、格式化、校验、分享链接转换、路由编译、日志级别、DNS、出口探测、服务状态检测、LAN 代理、抓包 | `core_*.go`、`ui_*.go` | 留 Go（内核专属） | — |
| 更新检查 | 无（2d 起在 Rust，请求经 `UIHTTPGet`） | — | — |

## 切片顺序

1. **2a 存储层**（本 PR）：`client-core` 的 SQLite 存储（设置键值、配置元数据、远程服务器、界面偏好），首次启动从 Room `profiles.db`/`settings.db` 与 SharedPreferences `flclash_ui` 一次性迁移；Kotlin 的 `Settings`/`ProfileManager`/`RemoteServerManager` 改为经 JNI 读写同一存储，Room 依赖移除；Rust 界面直接从 `client-core` 读配置列表与界面偏好、写偏好与排序。
2. **2b 订阅**（`arch/cor-core-p2b`）：头解析、元数据计算、数组拆分命名、自动更新策略迁 Rust；保存/更新流程（修订号、原子写入、回滚）迁 Rust，内核校验仍调 Go。
3. **2c 路由规则模型、Geo 策略与服务设置**（`arch/cor-core-p2c`）：`CoreRouting` 模型与校验、Geo 策略迁 Rust；服务设置快照、校验与写入迁 Rust（2a 收尾），界面直接从 client-core 读服务设置和路由规则，不需要 Android 先动手的设置直接写入。
4. **2d 备份/WebDAV、更新检查、终端设置**（`arch/cor-core-p2d`）：`bbox-1` 备份的打包、校验与恢复（含回滚）、WebDAV 账号与流程（一次性从 `bbox_webdav` 导入）、更新检查、终端设置值迁 Rust；HTTP 请求、Keystore 加密、Ghostty 主题与字体检查经内核桥回调平台。
5. **2e 界面控制器**：命令分发、状态模型迁 Rust，Kotlin `BoxBridge` 收缩为平台能力的薄层。

2a 之后界面的“服务设置”快照仍由 Kotlin `BoxBridge.settings()` 组装；2c 起改由 client-core 组装（见下文 2c 设计要点），Kotlin 只附加电池优化豁免并执行副作用。

## 2a 设计要点

- **存储**：`client-core::storage::Store`，rusqlite（内置 SQLite），文件 `<filesDir>/fkbox/client.db`，WAL；表 `meta`（含 `schema_version`、迁移标记）、`settings`（键、类型码、JSON 值，类型码沿用 `KeyValueEntity`）、`profiles`（Room 字段展开为列，另有内核解析缓存）、`remote_servers`、`preferences`（界面偏好 JSON 与备份身份）。路径由平台外壳以构造参数传入，不在 `client-core` 中写死。
- **进程内唯一实例**：`libfkui.so` 中只有一个 `Store`（单连接加锁），由 Kotlin 首次访问时经 JNI 打开（`Application` 进程即使没有界面也能用），Rust 界面在 `android_main` 中取用同一实例，避免同一进程内两份 SQLite 对同一文件加锁。（4e3c 起存储改为 Rust 管理的 JSON 文件，`client.db` 只作一次性导入来源，见 [phase4.md](phase4.md)。）
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

## 2c 设计要点

- **模块**：`client-core::routing`（规则模型、校验、存储文本与线格式、`core_json`、预设、条目拆分、端口与包名校验）、`geo`（`needed`、`should_extract`、`is_due`/`due`、`schedule`、`valid_url` 及用户地址的读写）、`service_settings`（快照、`validate`、`plan`、`patch`、`backup`）。`Store::put_settings` 一次写多个设置：独立事务，或在备份恢复已开的事务里一并提交/回滚。
- **存储不变**：仍是 `settings` 表原来的键和类型码（`routing_rules` 字符串、`geo_urls` 字符串、`geo_*`、`per_app_*` 等），`user_version` 仍为 1，无迁移。`routing_rules` 的文本与 Kotlin（kotlinx，`encodeDefaults`）写出的逐字节相同（`routing::tests::stored_text_matches_what_kotlin_wrote`），`geo_urls` 同样按名称排序、紧凑 JSON，清除地址时写 `""`（与旧版一致）。读取规则同旧 Kotlin 缓存：缺失或类型不符取默认值，整数取 long 的低 32 位，读不出的路由规则视为无规则。因此可直接降级到 2b/2a。
- **写入路径**：
  - `service_settings::plan` 校验（与原 `BoxSettings.validate` 相同的规则，外加“至少保留一个内核”，在任何副作用之前检查）并返回 Android 要做的事：`queryMode`（先切换包查询方式，失败时切回）、`scan`（受管模式扫描应用，扫描成功才写入，受管列表仍由 Kotlin 写）、`reloadSetup`、`rescheduleGeo`。
  - Kotlin `BoxBridge.patchSettings`：刷出缓存的待写入 → `plan` → 副作用 → `patch`（全部或全不）→ 把写入项 `adopt` 进 `StorePreferenceDataStore` 缓存（不再重复写）→ 重读启动参数、按需重排 `UpdateGeoWork` → 发 `settings` 事件。备份恢复、`saveRouting`（含服务重载失败时恢复旧规则的 `ServiceSettingsChange`）、需要自动重启服务的开关都走这里。
  - 界面（`client-ui` `app::set_setting/set_settings`）：有存储且 `plan.store_only()`（不切换查询方式、不扫描、不需要立即重启服务）时直接调 `service_settings::patch` 写入（恢复事务期间拒绝），再调宿主 `serviceSettingsWritten {written}` 让 Kotlin 收进缓存并重读启动参数/重排 geo 任务；其余情况仍调宿主 `setSetting`/`updateSettings`。
- **读取路径**：界面 `d.settings` = client-core 快照 + 宿主给的平台状态（目前只有 `batteryExempt`）。宿主的 `ready`、`settings` 事件和设置类调用的返回值都只带平台状态，且发出前先刷出缓存待写入。没有存储（截图工具）时沿用宿主载荷。路由页从快照的 `routingRules` 读（经模型补全默认值），校验、预设、新规则 id、条目拆分都用 `client-core::routing`；Xray 不能按应用分流的检查（`validate_rule_for_core`）也移入模型。服务启动时编译用的规则 JSON 由 `routing.coreJson` 直接读存储（`BoxService`）。
- **Geo**：`GeoResources` 只做文件：读取 APK 清单、解压、调 libbox 下载、读写 `geo-resources.json`；是否解压、哪些文件到期、内核需要哪些文件、下载地址由 Rust 决定。`UpdateGeoWork` 只按 `geo.schedule` 入队，按 `geo.due` 逐个调下载。`valid_url` 按 `java.net.URI` 的规则重写（方案大小写敏感、服务器型主机、非法字符），测试覆盖了原有用例和若干边界。
- **删除**：`bridge/CoreRouting.kt`、`bridge/BoxSettings.kt` 及其 JVM 测试（`CoreRoutingTest`、`BoxSettingsTest` 中设置相关部分；偏好校验测试改名为 `BoxPreferencesTest`），`Settings.routingRules/geoAutoUpdate/geoUpdateInterval/geoUrls` 缓存属性。`GeoPolicyTest` 只剩记录文件与清单解析。
- **推迟**：geo 地址编辑仍经宿主 `geoSetUrl`（需要 libbox 的默认地址，写入本身已在 Rust）；路由预览与可选节点仍经宿主（需要 libbox 编译与读取配置）；`saveRouting` 仍经宿主（运行中需重载服务）。命令分发与状态模型留 2e。
- **已知差异**：多个设置一次写入现在是原子的（旧 Kotlin 逐个写，中途失败会留下部分写入）；“至少保留一个内核”在切换查询方式/扫描之前就检查。界面直接写入与 Kotlin 收进缓存之间有一次桥调用的间隔，期间服务进程读到的仍是旧值（与旧版异步写入的窗口相当）。

## 2d 设计要点

- **模块**：`client-core::backup`（`create`、`validate`、`read` 暂存、`restore`/`finish`/`rollback`、`restore_wire_fields`、`remote_server_url`、`profile_key`）、`webdav`（账号读写、`validate`、`resolve`、`check`/`upload`/`download`）、`updates`（两个来源、候选与 APK 选择、`check`）、`terminal`（快照、`set`、备份部分的校验与字体写回）、`codec`（Base64、Java `nameUUIDFromBytes` 的 MD5 UUID、随机 UUID）。新增依赖 `md-5`。
- **内核桥新增方法**（`database/CoreKernel.kt`，Rust 在同一线程回调）：`validate`（按声明内核解析并用该内核校验内容，返回内核）、`http.get`（Go `UIHTTPGet`）、`webdav.request`（Go `UIWebDAVRequest`）、`secret.encrypt`/`secret.decrypt`（`SecretBox`，Keystore AES-GCM，`enc1:` 前缀）、`terminal.themes`/`terminal.fonts`/`terminal.fontValid`（Ghostty 主题目录、已导入字体、字体能否加载）。Go 接口没有改动。
- **存储**：表结构不变，`user_version` 仍为 1，无迁移；可直接降级到 2c/2b/2a。WebDAV 账号存 `preferences` 表的 `webdav` 键（JSON，密码为 `enc1:` 密文；从旧版导入的明文密码在首次读取时加密）。终端设置仍是 `settings` 表原来的 `tailscale_ssh_*` 键和类型码，默认值同 `Settings.kt`。
- **WebDAV 一次性导入**：`legacy::migrate_webdav`，以 `meta.legacy_webdav_import` 为标记，与 2a 的导入相互独立（已升级到 2a–2c 的用户库非空，也会导入账号）；先把 `shared_prefs/bbox_webdav.xml`（有 `.bak` 时取 `.bak`）复制到缓存目录再读，原文件不动；库里已有账号时不覆盖；导入和标记在一个事务里。`CoreStore.nativeOpen` 多了这个文件路径参数。
- **备份恢复流程**：Kotlin 在 `ProfileContent.mutex` 内先刷出设置缓存，调 `backup.restore`（Rust 在一个存储事务里写配置文件与行、远程服务器、已选配置、终端设置与字体、WebDAV 账号、界面偏好；被替换的文件先复制到 `cacheDir/restore-*`；中途失败则回滚事务并放回文件），然后 Kotlin 把 `written` 收进设置缓存、经 `patchSettings` 应用备份里的服务设置（查询方式、扫描等副作用）、应用已选配置到服务；成功后 `backup.finish`（删除 Override 移除的配置文件和副本），失败则 `backup.rollback`（再开一个事务放回所有行、偏好、账号与设置，放回文件，删除新字体）、重新应用旧服务设置、恢复运行中的服务。修订冲突检查（内容替换的修订号）不受影响，配置文件写入一律经 `content::write_atomic`。
- **恢复期间拒绝界面写入**（已知风险，保留）：恢复事务期间 `Store::in_transaction()` 为真，界面直接写入（偏好、排序、服务设置、终端设置）和 `webdav.save` 都返回“Wait for the restore to finish”。事务只覆盖 Rust 写存储的那一步，服务设置与应用到服务在事务之后。
- **更新检查**：Kotlin 只传 `applicationId`、`versionCode`、`Build.SUPPORTED_ABIS`；两个来源、重试（断连一次、5xx 一次）、新旧资源前缀、REST 列表在版本码相同时优先，都与原 `BoxUpdates` 相同（`updates::tests`）。`BoxUpdatesTest` 改为 Rust 测试。
- **界面直接调用**：终端设置中只存于存储的键（`lightConfig`、`darkConfig`、`fontFollowTheme`、`fontSize`、`fontFamily`）由界面直接调 `client-core::terminal::set` 写入，再调宿主 `terminalSettingsWritten {written}` 让 Kotlin 收进缓存；主题和已导入字体（要查 Ghostty 主题目录、字体文件）仍经宿主。备份、WebDAV、更新检查需要文件选择器、HTTP 或 Keystore，界面仍调宿主，宿主只做一次 Rust 调用。
- **删除**：`bridge/BoxWebDAV.kt`、`bridge/BoxUpdates.kt`、`BoxUpdatesTest`；`ProfileWireFields` 只剩导出（`validate`/`declaredCore`/`restore`/网站地址校验迁入 `backup.rs`，`ProfileWireFieldsTest` 只测导出）；`BoxTerminalSettings` 的备份与设置写入部分。
- **已知差异**：备份文本由 serde 输出，`/` 不再转义为 `\/`（两者互相可读）。Override 恢复对已有配置写入备份里的顺序（与 2a 前一致；2a–2c 的 Kotlin 写法实际也会写，因为顺序有变化）。`remote_server_url` 与 `geo::valid_url` 按 `java.net.URI` 的规则重写，个别边界（如 `ftp://` 被读成主机 `ftp`）与 Kotlin 一致。备份恢复不再在 Kotlin 侧把旧界面偏好“校验后写回”，而是原样放回旧的存储文本。暂存的备份最多保留两份（旧版只保留最后一份），未完成的恢复最多保留四个。
- **推迟**：命令分发与状态模型（2e）；备份导出/导入的文件选择器和 WebDAV 页面仍走宿主（2e 评估是否改为界面直接调用 + 平台能力回调）。

## 2e-1 设计要点

- **配置表单保存**：新建/编辑保存、`saveAll`、删除、失败回滚迁入 client-core `profiles.rs`（`profiles.save`/`saveAll`/`delete`）；修订号检查和文件写入复用 2b 的 `content.rs`（`Undo` trait + 泛型 `Pending`）。Kotlin `BoxProfiles.kt` 只做服务应用（重载/重启）与平台回调；`SubscriptionInfo.kt` 的 `fetch`/`metadataAfter` 已删。界面“全部更新”改走 `subscription::update_all`。
- **抓包设置**：`<dir>/settings.json` 读写在 Rust。读取时 `.bak` 优先、残留 `.new` 丢弃；首次升级上限 4 MiB，之后 64 KiB；删除退役 `ca.json`；旧 format 1 字段删除并强制关闭 HTTPS。内容由内核方法 `capture.validate`（`Libbox.validateUICaptureConfig`）校验。超限错误从 JNI 抛出为 `RuntimeException`，`ProfileRecoveryTest` 已随之调整。
- **IP 质量缓存**：存 `preferences` 键 `ip_quality`（JSON 对象）。写入校验 IP、`quality.ip` 一致、`checkedAt` 不晚于现在、不超过 16 KiB，并顺带清掉 3 天前或时间在未来的其他 IP；恢复事务中拒绝写入（只丢缓存，不报错）。`open` 时一次性导入旧 SharedPreferences（`.bak` 优先，先复制再读，元数据键 `legacy_ip_quality_import`）。
- **schema**：未改，`user_version` 不变。
- **推迟（2e-2）**：界面控制器其余部分与 `BoxBridge` 收缩；备份/WebDAV/更新检查/geo 地址入口仍经宿主——它们需要文件选择器或 libbox 默认值，改成界面直调要先有 Rust→Kotlin 平台回调，放到 2e-2 与回调一起做。

## 2e-2 设计要点

- **连接列表**：client-core `connections.rs` 按 libbox `Connections.ApplyEvents` 维护 ID → 行映射（NEW/UPDATE/CLOSED、5 分钟淘汰、DNS 行只计数不显示、仪表盘的活动/最近/DNS 计数）。Kotlin `ConnectionsViewModel` 不再持有连接表，只在单线程通道里把 libbox 事件转为增量放进缓冲；`BoxBridge.flushConnections` 最多每秒发一次 `connectionEvents`（`reset`、`events`、本地服务的 `dnsQueries`），页面或统计卡片可见时立即补发一次。原来每秒整表发送行（含未变化的行），现在只发增量；界面收到后重建行列表。`connections`、`connectionStats` 事件仍保留给没有 client-core 的宿主（截图工具）。
- **远程控制服务器**：地址规范化、校验、连接地址、显示名迁 client-core `remote_servers.rs`（原 `backup.rs` 里的校验移入）；界面直接从存储读列表并保存表单（恢复事务中拒绝），宿主的 `remoteServers`/`saveRemoteServer` 已删。Kotlin `RemoteServer.connectURL`/`displayName` 调 Rust。删除与连接仍经宿主（要先看当前远程会话）。
- **schema**：未改，`user_version` 不变。
- **推迟（2e-3）**：日志流（`LogStream`/`LogViewModel`）、代理组与仪表盘状态模型；命令客户端连接调度（`CommandClient`、可见性与前后台）留 Kotlin——它们依赖 libbox 命令客户端与 Android 生命周期，迁 Rust 要先让 Rust 直接连 libbox 命令服务（第三阶段评估）。

## 2e-3 设计要点

- **日志**：client-core `logs.rs` 持有日志记录（去 ANSI、递增 ID、3000 条环形、默认等级标签）。Kotlin `LogViewModel` 只在单线程通道里暂存新行、清空与等级变化（`LogEventBuffer`，同样以 3000 行为上限）；`BoxBridge.flushLogs` 在日志页可见时最多每秒发一次 `logEvents`（`reset`、`defaultLogLevel`、`entries`）。原来每次恢复可见要重新编码整份快照，现在界面自己保留记录，只发新增。`LogStream.kt`、`LogBuffer` 及其 JVM 测试已删，用例改为 Rust 测试；`LogLevel` 枚举保留给仪器测试比较默认等级。
- **仪表盘**：client-core `dashboard.rs` 从 Kotlin 上报的原值推导显示（`trafficAvailable`、仅可切换时的 `modes`、仅本地服务的 `systemProxyAvailable`、远程会话优先的 `startedAt`）；已是显示形态的值（截图工具）原样通过。
- **代理组**：`backend/model/Groups.kt` 已删，`GroupsViewModel` 直接把 libbox `OutboundGroup` 转为界面读取的映射。选择节点、测速、切换模式的检查移到 client-core `groups.rs`，界面在调用宿主前检查（选择失败仍静默忽略，测速与模式报错）；`BoxBridge.selectOutbound`/`urlTest`/`setMode` 不再重复检查。
- **schema**：未改，`user_version` 不变。
- **第二阶段到此结束。留到第三阶段**（已在第三阶段 3b 完成，见 [phase3.md](phase3.md)）：命令客户端连接调度（`CommandClient`、页面可见性、前后台、远程会话切换）——需要 Rust 直接连 libbox 命令服务后再迁。

