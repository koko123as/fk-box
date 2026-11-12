# FK-box

Kotlin+Jetpack Compose三内核客户端

FK-box（安装身份 `io.fkbox.app`）是独立维护的开源 Android 客户端，源码位于 [koko123as/fk-box](https://github.com/koko123as/fk-box)。它派生自 [SagerNet/sing-box-for-android](https://github.com/SagerNet/sing-box-for-android)，直接前身是 [giturass/B-box](https://github.com/giturass/B-box)（经 [koko123as/B-box](https://github.com/koko123as/B-box) 的 `fk-box` 分支迁入，完整 Git 历史保留在本仓库）。界面为 Kotlin + Jetpack Compose 的朴素 Material 3 风格（卡片、无阴影/玻璃/特效/转场动画、Material 图标，观感接近 sing-box-for-android 与 v2rayNG）；页面布局参考 [FlClash v0.9.3](https://github.com/chen08209/FlClash/tree/be84736605527034689c95bf27828ff27d912c5e)，仓库中没有 Flutter/Dart 代码。`libbox.aar` 同时编入 sing-box、Xray、mihomo 三个内核，同一时间只运行一个。本项目不是上述任何上游的官方发布。

## 致谢 / 来源

FK-box 使用或参考了下列项目（许可证以各项目为准；本仓库保留的许可文本见 [licenses/](licenses)）：

| 项目 | 许可证 | 取用内容 |
| --- | --- | --- |
| [SagerNet/sing-box-for-android](https://github.com/SagerNet/sing-box-for-android) | GPL-3.0-or-later（含附加条款，见下文 License） | 应用基础：VPN/前台服务、Room 数据库、配置与订阅、Root/Shizuku、Tailscale/终端等 Android 后端；比较基线见 [app.md](app.md) |
| [giturass/B-box](https://github.com/giturass/B-box)（及其分叉 [koko123as/B-box](https://github.com/koko123as/B-box)） | GPL-3.0（沿用上游） | 直接前身：libbox 扩展绑定、HTTP/HTTPS 请求调试与 MITM、配置改写、多内核接入、界面适配、备份格式 `bbox-1` 等，见 [libbox.md](libbox.md) |
| [SagerNet/sing-box](https://github.com/SagerNet/sing-box) | GPL-3.0-or-later | 主代理内核（libbox，含 Tailscale 等协议），版本见 [version.properties](version.properties) |
| [XTLS/Xray-core](https://github.com/XTLS/Xray-core) | MPL-2.0 | 第二内核，编入同一 `libbox.aar` |
| [MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo) | GPL-3.0（所用 v1.19.34） | 第三内核（Clash YAML），编入同一 `libbox.aar` |
| [chen08209/FlClash v0.9.3](https://github.com/chen08209/FlClash/tree/be84736605527034689c95bf27828ff27d912c5e) | GPL-3.0 | 页面布局参考（控件、图标、动效不再取用）；另参考 FlClash `dev` [`63aa150`](https://github.com/chen08209/FlClash/tree/63aa150e23288558074d877f745a6d3b5f6418f6) 的 MITM 证书模块和 IP 质量查询。对应清单见 [flclash-manifest.json](flclash-manifest.json) 与 [flclash.md](flclash.md) |
| [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android) | GPL-3.0 | `DefaultNetworkListener.kt`（经 sing-box-for-android 沿用） |
| [Android Open Source Project](https://source.android.com/) / [androidx](https://github.com/androidx/androidx) | Apache-2.0 | `ParceledListSlice.java` |
| [Jetpack Compose、AndroidX](https://developer.android.com/jetpack)（含 Material 3、Material Icons、CameraX、Room、WorkManager）、[Material Components](https://github.com/material-components/material-components-android) | Apache-2.0 | 界面框架与 Android 组件；CameraX 用于扫码取景 |
| [ZXing](https://github.com/zxing/zxing) | Apache-2.0 | 二维码识别与生成 |
| [ghostty-org/ghostty](https://github.com/ghostty-org/ghostty)（经 [SagerNet/libghostty-android](https://github.com/SagerNet/libghostty-android)） | MIT | 终端渲染 |
| [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API) | MIT | Shizuku 权限调用 |
| [topjohnwu/libsu](https://github.com/topjohnwu/libsu) | Apache-2.0 | Root shell 与 Root 服务 |
| [google/smali](https://github.com/google/smali)（dexlib2）、[Guava](https://github.com/google/guava) | BSD-3-Clause、Apache-2.0 | 分应用代理的应用扫描 |
| [Kotlin coroutines / serialization](https://github.com/Kotlin) | Apache-2.0 | 协程与 JSON |
| [dop251/goja](https://github.com/dop251/goja) 及其依赖 [dlclark/regexp2](https://github.com/dlclark/regexp2)、[go-sourcemap/sourcemap](https://github.com/go-sourcemap/sourcemap)、[google/pprof](https://github.com/google/pprof) | MIT、MIT、BSD-2-Clause、Apache-2.0 | libbox 中 HTTP 改写脚本的 JavaScript 引擎 |
| [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) | OFL-1.1 | 内置等宽字体 |

[licenses/](licenses) 另保留自 FlClash 许可目录迁入的 [Twemoji](licenses/Twemoji-LICENSE.md)（字体文件已随 Flutter 模块移除）、[cilium/ebpf](licenses/cilium-ebpf-LICENSE)、[golang.org/x/arch](licenses/golang-x-arch-LICENSE) 与 [ulikunitz/xz](licenses/xz-LICENSE) 许可文本。

下载 APK：[GitHub Releases](https://github.com/koko123as/fk-box/releases)（Android 7.0+，ARM64）。已有 Release 不等同于当前源码。

- Compose 负责仪表盘、代理、配置、工具、设置及所有子页面；启动器和 Android 分享入口均进入新界面。应用不再包含 Flutter。
- 导入时按内容识别内核：sing-box JSON、Xray JSON（含 MASQUE 出站）、Clash YAML（mihomo）及分享链接/Base64 节点列表，也可手动指定内核；v2rayNG 式“多个完整配置的 JSON 数组”按元素拆成多个配置，以 `remarks` 命名；分享链接转换为对应内核的配置后再校验。Xray/mihomo 仅支持 VPN 模式。代理组、可选节点、测速和路由模式来自当前内核，自动组由内核选择。
- 保留 VPN、分应用代理、Root/Shizuku 应用查询、远程控制、连接管理和日志。
- 工具中的“请求”支持 HTTP 抓取、HTTPS MITM、域名筛选、请求详情及 HAR/cURL 导出；[支持范围和限制](libbox.md#httphttps-请求调试)。
- JSON 配置支持 MITM、Map Remote、Header/URL/Body Rewrite、Map Local/API Mock 和 JavaScript 请求/响应改写；[配置说明与示例](libbox.md#json-配置中的-http-改写)。
- Tailscale、Taildrop、SSH、OpenConnect/OpenVPN 认证、USB/IP、网络诊断和报告通过新页面使用现有后端。
- 支持配置和设置备份（`bbox-1` 格式，含每个配置的内核与订阅信息）。
- 旧 Compose 页面、导航、编辑器及更新下载/静默安装链路已移除。关于页检查 [koko123as/fk-box](https://github.com/koko123as/fk-box/releases) 的 `FK-box-*` 发布资产；配置订阅更新继续保留。
- Xposed/LSPosed 和 Android 5/6 支持已移除。

项目约束：[AGENTS.md](AGENTS.md)。核心差异：[libbox.md](libbox.md)。界面实现与 FlClash 布局参考：[flclash.md](flclash.md)。Android 应用差异与构建发布：[app.md](app.md)。组件许可证：[licenses](licenses)。

本项目沿用 sing-box-for-android 的 GPL-3.0-or-later 许可（含下文附加条款），FlClash 布局参考保留其 GPL-3.0 来源与许可，各组件许可见 [licenses](licenses) 与 [flclash-manifest.json](flclash-manifest.json)。发布 APK 时须同时提供对应源码。

## Documentation

https://sing-box.sagernet.org/installation/clients/sfa/

## License

```
Copyright (C) 2022 by nekohasekai <contact-sagernet@sekai.icu>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.

In addition, no derivative work may use the name or imply association
with this application without prior consent.
```

Under the license, that forks of the app are not allowed to be listed on F-Droid or other app stores
under the original name.
