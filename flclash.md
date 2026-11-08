# FlClash UI 使用情况

本文记录 FK-box 界面的来源、与 FlClash 源码的逐文件对应关系、Android 桥接范围和验证方式。Android 后端差异见 [app.md](app.md)，核心扩展见 [libbox.md](libbox.md)，维护约束见 [AGENTS.md](AGENTS.md)。

> 当前状态说明：`flclash-ui` 分支用 FlClash 的 Flutter 源码本身作为界面（源码级复制），取代 `main` 上的 Kotlin + Jetpack Compose 翻译版。合并前 `main` 仍是 Compose 界面。

## 参考版本

| 项目 | 固定版本 |
| --- | --- |
| FlClash | [chen08209/FlClash v0.9.3](https://github.com/chen08209/FlClash/tree/be84736605527034689c95bf27828ff27d912c5e)，提交 `be84736605527034689c95bf27828ff27d912c5e` |
| 界面套件 | [chen08209/prism_ui](https://github.com/chen08209/prism_ui/tree/63450c0ac0e8e1b2c0f3ed4ad7926dc94e083185) `63450c0ac0e8e1b2c0f3ed4ad7926dc94e083185`（FlClash v0.9.3 的 `plugins/prism_ui` 子模块） |
| Flutter SDK | 3.47.6（FlClash v0.9.3 `.github/workflows/build.yaml` 的 `FLUTTER_VERSION`） |
| 许可 | 均为 GPL-3.0；源文件的版权头、`LICENSE`、`NOTICE` 原样保留 |

[flclash-manifest.json](flclash-manifest.json) 记录上述固定版本、改动文件及原因、FK-box 新增文件，以及从 FlClash `android/` 移植到 `app/` 的 Kotlin 宿主代码。

## 目录与逐文件对应

`flutter/` 就是 FlClash 的 Flutter 工程：`lib/`（365 个 Dart 文件）、`arb/`、`assets/`、`plugins/`（含 vendored 的 `prism_ui`）、`pubspec.yaml`/`pubspec.lock` 与分析配置。Dart 包名仍为 `fl_clash`，平台通道前缀仍为 `com.follow.clash`，因此没有 Dart 文件被改名或改写。

- **界面文件逐字节一致**：`flutter/lib/` 下全部文件与 FlClash `be84736` 一致，包括交互逻辑、动画、阴影、导航、平板布局（`lib/common/layout.dart` 的 `ViewMode` mobile/laptop/desktop 断点、`lib/views/navigation.dart`、仪表盘网格/编辑/拖动）。`plugins/prism_ui` 与 prism_ui `63450c0` 一致。
- **核心桥接层**：FlClash 在 Android 上把 `lib/core/` 的 Core 调用（`CoreMethodCall` JSON）经 `com.follow.clash/core` 通道交给进程内 Go 核心（libclash）。FK-box 不改 Dart 侧 `lib/core/interface.dart` 的任何方法签名，而是在 Kotlin 侧以同样的方法名和 JSON 结构回答（`app/src/main/java/com/follow/clash/core/`），见下文“Core 方法”。
- **改动的 FlClash 文件**（由清单 `adaptedFiles` 列出原因）：`pubspec.yaml` 去掉构建 libclash 的 `setup` 依赖及其 `hooks.user_defines` 项；`assets/data/` 的四个地理数据文件不打进 Flutter 资源，由 APK 的 `assets/geo` 提供。
- **FK-box 新增文件**（清单 `fkboxFiles`）：`assets/data/README.md`、空的 `core/Clash.Meta/component/ca/ca-certificates.crt`（FlClash 列为资源，子模块提交中本为空），以及让 Flutter 工具识别 v2 嵌入的 `android/app/src/main/AndroidManifest.xml`。

CI（[flclash-ui.yml](.github/workflows/flclash-ui.yml)）检出两个固定参考，运行 [verify_flclash_ui.py](tools/verify_flclash_ui.py) 逐文件比较 SHA-256，把报告写入运行摘要并上传 `flclash-ui-diff` Artifact。未在清单中说明原因的改动、缺失或新增文件会使检查失败。

## Android 宿主

FlClash 的 `android/` 宿主（插件、服务、快捷方式）移植为 Kotlin，位于 `app/src/main/java/com/follow/clash/`，逐文件来源见清单 `androidHost`：

- `plugins/AppPlugin.kt`、`BatteryPlugin.kt`、`TilePlugin.kt`、`ServicePlugin.kt` 与 FlClash 通道和方法一致；`ServicePlugin` 的 start/stop/getRunTime 交给 `FkHost`（FK-box 的 `BoxService`：VPN 授权、前台服务、通知、分应用代理），`invokeMethod` 交给 `FkCore`。
- `BoxActivity` 是 `FlutterFragmentActivity`，承载 FlClash 的 Flutter 界面；启动器和分享入口保持原组件名。
- 桌面专用部分（托盘、窗口、热键、桌面 helper）在 Android 上本就不运行，Dart 侧按平台判断跳过，未改动界面文件。

## Core 方法

`FkCore` 按 `lib/core/method.dart` 的线路名分派，结果结构与 FlClash Go 核心相同：

| 方法 | FK-box 实现 |
| --- | --- |
| `core.init` / `core.initialized` / `core.gc` / `core.memory` | 记录 home 目录并解压/同步地理数据；Go 运行时内存与进程 PSS |
| `config.validate` / `config.read` / `profile.base` / `profile.outbounds` / `profile.build` / `config.setup` / `config.update` | Clash YAML 按 FlClash `core/profile_base.go` 合并覆写层（`FkYaml`）后由 mihomo 运行；sing-box JSON、Xray JSON 原样保留并由对应内核运行；模式经 libbox 切换 |
| `proxy.convert` | 分享链接/Base64 节点列表经 libbox（mihomo `common/convert`）转为 mihomo 代理；仅 Xray 支持的链接（xhttp）在校验时直接改写为 Xray 配置 |
| `proxy.list` / `proxy.select` / `proxy.testDelay` | 运行时取当前内核分组（sing-box selector/urltest、mihomo 组、Xray balancer）；停止时按配置静态列出 |
| `traffic.*` / `connection.*` / `log.start` / `log.stop` | libbox 状态、连接和日志流；`traffic.reset` 以基线清零累计值 |
| `provider.list` / `provider.get` / `provider.update` | mihomo 运行时的代理/规则提供者（libbox `MihomoExternalProviders` 等，移植自 FlClash `core/provider.go`） |
| `geo.update` | libbox `GeoUpdate` 下载到 `Libbox.geoDataDir()`（mihomo 的 home、Xray 的 `xray.location.asset`），再镜像到 FlClash home 的 `GEOIP.dat`/`GEOSITE.dat`/`GEOIP.metadb`/`ASN.mmdb`；以 `geoUpdate` 事件回报 |
| `route.watch` / `route.check` / `network.changed` | 内核每次启停递增 epoch，配置、选择、模式、提供者和网络变化递增 route-version，并发 `routeChanged` 事件 |
| `probe.outboundIp` | 应用进程按 URL 列表并发请求，取第一个成功的回答（服务运行时经 VPN） |

尚未提供、回答 `unsupported` 或空结果的方法：`config.decrypt`（age 加密配置）、`proxy.definition`、`proxy.validate`、`shareLink.encode`/`decode`、`filter.validate`、`provider.sideLoad`、`ruleSet.*`、`probe.services`（返回空列表）、`dns.clearCache`（无操作）及 `mitm.*`。对应页面会显示 Core 错误，而不是虚构结果。

## 构建

`settings.gradle.kts` 引入 Flutter SDK 的 Gradle 插件，按 `flutter/.flutter-plugins-dependencies` 包含插件工程；`:app` 应用 `dev.flutter.flutter-gradle-plugin`，`flutter.source` 指向 `flutter/`。只构建 `arm64-v8a`（`-Parm64Only=true -Ptarget-platform=android-arm64`）。CI 产出 debug（JIT）与 profile（AOT，真实动画速度）两个 APK。

CI 的网络加固：Gradle init 脚本 [.github/ci/maven-mirror.init.gradle](.github/ci/maven-mirror.init.gradle) 把所有构建（含 Flutter SDK 自己的 Gradle 构建）中的 Maven Central 指向 JetBrains cache-redirector，并加大超时与重试；[.github/ci/sqlite3-assets.sh](.github/ci/sqlite3-assets.sh) 按 `package:sqlite3` 自带的 SHA-256 带重试下载其 Android 库，经本机 HTTP 提供给构建钩子（只在 CI 检出中加入 `url_pattern`）。

## 验证范围

- CI：`flutter analyze`、源码差异报告、arm64 debug/profile APK 构建。
- 未覆盖：真机与平板上的视觉、动画和交互验收；各内核在 FlClash 界面下的实际启停、测速、提供者更新和地理数据更新。编译通过与源码一致不等于实机验收。
