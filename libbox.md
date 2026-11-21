# sing-box 与 libbox

fk-box 不再维护 libbox 分支：应用使用 sing-box 官方、未修改的 `libbox.aar`，Xray 与 mihomo 以官方可执行文件运行，由 fk-box 自己的 Go 模块 [core/](core/)（fk-core）管理。架构、进程划分和各阶段决策见 [docs/kernel-isolation.md](docs/kernel-isolation.md)；Android 调用及数据持久化见 [app.md](app.md)。

## 基线

| 组件 | 版本（[version.properties](version.properties)） | 构建 |
| --- | --- | --- |
| sing-box | `CORE_VERSION` | 官方 `cmd/internal/build_libbox`，[build-kernels](.github/actions/build-kernels/action.yml) / [build_kernels.sh](tools/build_kernels.sh) 生成 `libs/libbox-official.aar` |
| Xray | `XRAY_VERSION` | 上游标签源码编译的可执行文件，打包为 `libxray.so` |
| mihomo | `MIHOMO_VERSION` | 同上，`libmihomo.so`（gvisor 栈，见 kernel-isolation 决策 D3） |
| libXray | `LIBXRAY_MODULE_VERSION` | 仅用于分享链接转换（`core/tools/xrayshare`） |
| fk-core | 本仓库 | `core/cmd/fk-core`，不链接任何内核（[check_kernel_isolation.py](tools/check_kernel_isolation.py)） |

## 原分支功能的去向

| 功能 | 现在的位置 |
| --- | --- |
| sing-box 启动、重载、关闭 | 官方 `CommandServer`（`startOrReloadService`/`closeService`，`OverrideOptions`），见 `BoxService.kt`；sing-box 留在应用进程（决策 D4） |
| Xray/mihomo 运行、仪表盘、连接与日志 | fk-core（[core/fk](core/fk/)、[core/kernel](core/kernel/)），以 sing-box 的 StartedService gRPC 接口作答 |
| 分流规则 | fk-core 生成三种内核的规则；sing-box 的规则由 `BoxService.kt` 写入 `OverrideOptions` |
| HTTP/HTTPS 抓包 | [core/capture](core/capture/)：127.0.0.1 上的 SOCKS5 代理（随机密码），按 SNI/Host 嗅探后交给 [core/httpdebug](core/httpdebug/)，其余连接原样经内核的本地入站 `fkbox-local` 转发；内核在抓包或改写时把选中流量路由到它 |
| HTTP 改写 | 仍写在 sing-box 配置的 `experimental.http_rewrite`，fk-core 启动前取出并启用，再把去掉该字段的配置交给官方解析器 |
| MITM 签名 | 根私钥留在 Android Keystore；fk-core 通过反向调用 `capture.sign` 请应用签名 |
| 网络质量、STUN、Tailscale/OpenConnect/OpenVPN 工具 | Kotlin（`bridge/UiTools.kt`、`bridge/UiStreams.kt`）直接调用官方 libbox 命令客户端 |
| 订阅、配置存储、出口探测、服务检测、局域网代理 | fk-core（[core/fk](core/fk/)） |

已撤销、不再提供的分支差异：sing-tun Android ingress discard 兼容补丁（官方 sing-tun 行为为准）、仪表盘 DNS 查询计数、释放内存操作、`experimental.http_debug` 配置（启动前由 fk-core 删除）。

## 分流规则

应用为每个内核保存一套规则（数据模型与界面见 [app.md](app.md#分流规则)），[core_routing.go](core/fk/core_routing.go) 在启动时把实际运行内核的那一套编译成该内核自己的路由语法：

| 接口 | 作用 |
| --- | --- |
| `ApplyCoreRouting(core, content, rules)` | 返回 `CoreRoutingResult{Content, Applied, Warnings, Active}`；`rules` 为空、`mode: off`，或没有启用的规则且兜底为 `config`、模式为 `prepend` 时原样返回内容。Xray 结果另做 `checkXrayRuleTags`（ruleTag 不得重复），不通过时整体返回错误 |
| `CommandServer.StartOrReloadCoreServiceWithRouting(core, content, rules, options)` | 返回 `(*CoreRoutingResult, error)`。先编译再走 `StartOrReloadCoreService`；成功后写日志“已应用 <内核> 分流规则 N 条”和逐条警告。规则不会让配置起不来：整体编译失败时按原配置启动，`Error` 为原因；带规则启动失败（如 sing-box 远程规则集首次下载失败，上游 `rule_set_remote.go` 视为启动错误）时用原配置再启动一次，`Fallback=true`、`Error` 为原因（规则集下载失败会注明“下次启动或重载时重新应用”）。只有原配置本身也起不来时才返回 error |
| `CoreRoutingTargets(core, content)` | 按行返回“指定节点或组”可选的名字：sing-box 出站/端点 tag（不含 dns/block）、Xray balancer 与出站 tag（不含 dns）、mihomo 的 proxies 与 proxy-groups（不含 proxy-provider 里的节点，mihomo 规则不能直接引用它们） |

单条规则的问题（无效条目、找不到节点、Xray 不支持应用匹配、列表下载失败且无缓存）只跳过该条或该项并给出警告，不阻止启动。没有目的地条件、只有应用/网络/端口的规则（内容 `all` 加条件，或条目为空的 `match` 加条件）只按这些条件匹配：sing-box 为仅含 `package_name`（及 `network`/端口）的路由规则，mihomo 为 `PROCESS-NAME,<包名>,目标`（有网络/端口时为 `AND`），Xray 跳过并警告；应用全部无效时该条跳过，不会变成匹配全部流量（`TestCoreRoutingAppOnly`）。

字段映射（“目标 proxy”= 配置的主出站：sing-box 为 `route.final`（若是代理类）、否则第一个 selector/urltest/代理出站；Xray 为配置最后一条兜底规则的 `balancerTag`/`outboundTag`、唯一的 balancer、默认（第一个）出站、第一个代理出站；mihomo 为 `MATCH` 的目标（非 DIRECT/REJECT）、否则第一个策略组或节点）：

| 用户字段 | sing-box | Xray | mihomo |
| --- | --- | --- | --- |
| 位置 | `route.rules`，放在开头连续的 sniff/hijack-dns/resolve 动作之后；有域名条件而开头这段里没有无条件的 sniff（只有 sniffer/timeout 选项）时，在最前加 `{"action":"sniff"}`（规则动作按顺序执行，排在后面的 sniff 对用户规则来不及） | `routing.rules` 最前（启动时 libbox 再把 53 端口 DNS 规则放到所有规则之前） | `rules` 最前；配置的 rule-providers、sub-rules、策略组不动 |
| 域名后缀 / 完整 / 关键字 / 正则 | `domain_suffix` / `domain` / `domain_keyword` / `domain_regex` | `domain:` / `full:` / `keyword:` / `regexp:` | `DOMAIN-SUFFIX` / `DOMAIN` / `DOMAIN-KEYWORD` / `DOMAIN-REGEX` |
| IP / CIDR | `ip_cidr` | `ip` | `IP-CIDR,…,no-resolve`（在 `AND` 内为 `(IP-CIDR,…,no-resolve)`） |
| `geosite:x` | 远程 binary 规则集 `fk-geosite-x`：`https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/sing/geo/geosite/x.srs`，`http_client.detour` 为主代理出站，`update_interval: 1d`；新增规则集且配置未设 `experimental.cache_file` 时加 `{"enabled": true}`，首次下载后存入缓存文件。不使用 1.12 起已移除的旧 geosite 数据库 | `geosite:x`，读共享地理数据目录的 `geosite.dat` | `GEOSITE,x` |
| `geoip:x` | 同上 `fk-geoip-x`（`…/geo/geoip/x.srs`）；`geoip:private` 用 `ip_is_private: true` | `geoip:x` | `GEOIP,x,no-resolve`；`geoip:private` 写作 mihomo 的 `GEOIP,lan,no-resolve` |
| 应用包名 | `package_name` | 不支持（Xray 在 Android 无应用归属），跳过并警告 | `PROCESS-NAME,<包名>`，多个用 `OR`；见下 |
| 网络 / 端口 | `network` / `port` + `port_range`（`8000:9000`） | `network` / `port`（`443,8000-9000`） | `NETWORK,TCP` / `DST-PORT,443/8000-9000` |
| 组合 | 同一条规则内目的地条件为“或”，与应用/网络/端口为“与”（sing-box 默认规则语义） | 同一条规则内条件全部为“与”，所以域名和 IP 拆成两条规则，`ruleTag` 分别为 `fk #N 名称 (domain)` / `(ip)`（Xray 的 `router.ReloadRules` 拒绝重复 ruleTag；与配置已有 ruleTag 重名时再加 ` [2]` 等） | 有网络/端口/应用条件时写成 `AND,((匹配),(条件)…),目标`；mihomo 逻辑规则按括号切分，条目含 `()`（`DOMAIN-REGEX`）或 `,()`（其他类型）时不能放进逻辑规则，跳过并警告。顶层 `DOMAIN-REGEX` 的逗号由 mihomo `ParseRulePayload` 保留 |
| 全部流量 | 无匹配条件的规则 | `network: "tcp,udp"` | `MATCH,目标`（有条件时为条件本身或 `AND`） |
| 代理 / 直连 / 拦截 / 指定 | `action: route` + 主出站 / 第一个 `direct` 出站（没有则新增 `fk-direct`）/ `action: reject` / 该 tag（出站或端点） | 主出站或 balancer / 第一个 `freedom`（没有则新增 `fk-direct`）/ 第一个 `blackhole`（没有则新增 `fk-block`）/ 该出站或 balancer | 主组 / `DIRECT` / `REJECT` / 该节点或组 |
| 兜底 `final` | `route.final`；拦截时末尾加 `{"action":"reject"}` | 去掉配置的兜底规则（无其他匹配字段，网络缺省或同时含 tcp 与 udp（字符串或数组），端口缺省或覆盖 1–65535，如 v2rayN 的 `"port": "0-65535"`），末尾加 `network: tcp,udp` 规则（ruleTag `fk fallback`）。这一判定也用于找“主出站”和 override 保留配置兜底 | 去掉配置的 `MATCH`，末尾加 `MATCH,目标`。mihomo 无 MATCH 时未匹配流量默认直连 |
| `override` | 只保留开头的 sniff/hijack-dns/resolve | 兜底为 `config` 时保留配置的兜底规则，balancers/observatory 保留 | 兜底为 `config` 时保留配置的 `MATCH` |

- DNS 与 IP 规则：三个内核都不为 IP 规则去解析域名。sing-box 的 `ip_cidr`/geoip 规则集本就只看连接的目标 IP；mihomo 的 `IP-CIDR`、`GEOIP` 和 ipcidr 规则列表都带 `no-resolve`；Xray 保留配置的 `routing.domainStrategy`（不再强制改成 `IPIfNonMatch`）。因此 IP 规则匹配目标为 IP 的连接（未嗅探出域名的 TCP/UDP、直接按 IP 连接的应用、mihomo 已解析或 fake-ip 映射过的主机），被嗅探出域名的连接按域名/geosite 规则匹配，不会为每个未命中的域名发一次本地 DNS 查询（时延与泄漏）。Xray 的 TUN 嗅探会把目标改写为域名，`AsIs` 下这类连接的 IP 规则匹配不到；需要时由用户在配置中设置 `IPIfNonMatch`/`IPOnDemand`（注意：有兜底规则时第一轮即命中，`IPIfNonMatch` 实际不会再解析）。界面在 Xray 分页底部提示这一点。
- sing-box 远程规则集：上游在首次下载失败且无缓存、无 `initial_path` 时让整个启动失败。libbox 没有内置 .srs（避免 APK 增大和规则过期），而是按上面的回退用原配置启动并报告；规则集下载走主代理出站，代理起来后的下一次启动或重载会再下载并存入 cache_file。
- mihomo 的 `find-process-mode: off`：mihomo 不会查应用归属，带应用的规则匹配不到，编译时给出警告。指定节点为 proxy-provider 里的节点时，警告会说明需选包含它的策略组；界面的候选列表来自 `CoreRoutingTargets`，只列配置自身的节点与组，且仅当所选配置正运行在该分页的内核上时显示。
- mihomo 应用匹配：cmfa 构建下 mihomo 不读 `/proc`，而是调用 `process.DefaultPackageNameResolver`。[core_routing_owner.go](core/fk/core_routing.go) 参照 ClashMetaForAndroid，经 `PlatformInterface.FindConnectionOwner`（应用端 `getConnectionOwnerUid` + 包管理器，与 sing-box `package_name` 用的同一实现）返回包名，地址优先用 mihomo 改写前的 `RawSrcAddr`/`RawDstAddr`。因此此前“cmfa 模式下 PROCESS-NAME 取不到进程名”的限制对包名规则已解除；共享 UID 的应用返回第一个包名，fake-ip 下取不到原始目标地址的连接仍无法匹配，需实机确认。
- 规则列表 URL：libbox 下载（单个 15 秒超时；一次启动的多个列表并行下载，最多 4 个同时进行，合计不超过 20 秒，超时的列表用缓存或跳过；最大 16 MiB，最多 30 万项）到 `<工作目录>/routing/lists/<URL 哈希>.txt`，24 小时内直接用缓存，过期后尝试刷新，失败继续用旧副本并警告，从未下载成功则跳过该规则。解析 AdBlock（`||example.com^`；带 `$` 选项、例外 `@@`、元素隐藏规则忽略）、hosts（`0.0.0.0 host`，按完整域名）、Clash 经典规则（`DOMAIN-SUFFIX`/`DOMAIN`/`DOMAIN-KEYWORD`/`DOMAIN-REGEX`/`IP-CIDR(6)`，含 YAML `payload`）、Clash 域名文本（`+.example.com`）、v2fly domain-list-community 行（`full:`/`domain:`/`keyword:`/`regexp:`，`@cn` 等属性忽略，`include:` 跳过）和纯 IP/CIDR/域名行。`DOMAIN-REGEX` 取逗号后的整段，正则里的逗号保留。`.srs`/`.mrs`/`.dat`/`.mmdb` 等二进制格式不支持。sing-box 与 Xray 把条目内联进规则；mihomo 写成 mihomo 主目录 `fk-routing/` 下的本地 rule-provider 文件（`domain`、`ipcidr`、`classical` 三种 behavior，`format: text`），规则写作 `RULE-SET,fk-list-<哈希>-<behavior>,目标`，以使用 mihomo 自己的集合匹配。
- 测试：[core_routing_test.go](core/fk/core_routing_test.go) 与 [core_routing_start_test.go](core/fk/core_routing_test.go) 的 `TestCoreRouting*`（newui.yml 多内核步骤）：列表解析（含 v2fly 与带逗号的正则）、条目分类与端口、`off`/空规则不改内容；sing-box 对链接转换配置编译后 `CheckConfig` 通过并核对规则顺序、规则集、cache_file、兜底与 override，以及 sniff 不在开头时补插；Xray 编译后 `CheckCoreConfig` 通过并核对拆分、ruleTag 唯一、balancer、兜底替换和 domainStrategy 不变；`TestCoreRoutingXrayStarts` 写入含所需代码的测试用 geosite.dat/geoip.dat，按 `startXrayCore` 的同一构造（`xrayStartRoot`，去掉需要 fd 的 TUN 入站）对每个预设（prepend/override）、全部预设同名、混合规则与已占用 tag 的配置调用 `xrayCore.New`（会初始化路由并执行 `ReloadRules` 的检查），并确认重复 ruleTag 被 Xray 和 `checkXrayConfig` 拒绝；兜底识别覆盖 `0-65535`/`1-65535`/分段端口与 tcp,udp 写法；mihomo 编译后 `CheckCoreConfig` 通过，非 GEO/RULE-SET 规则逐条交给 mihomo 自己的 `rules.ParseRule`，IP 规则对只有域名的连接不触发解析，目标必须是已有节点/组，并检查 provider 文件、find-process-mode 与 proxy-provider 警告；列表并行下载与总超时用本地 HTTP 服务测。CI 测不到：实际下载规则集、GEO 规则命中、`PROCESS-NAME` 在真机上的归属查询、带规则启动失败后的回退重启。

## JSON 配置中的 HTTP 改写

在原 sing-box JSON 的 `experimental` 中添加 `http_rewrite`，保留原入站、DNS、出站和路由。此字段是 B-box 的真实核心扩展，出现在生成的配置 schema 中；`Libbox.checkConfig` 同时校验字段、正则、静态响应和 JavaScript 语法，格式化、分享及备份保留它。它不是 Surge INI 导入器，也不是 Mihomo 配置覆写。完整示例见 [examples/http-rewrite.json](examples/http-rewrite.json)。该示例使用测试域名，HTTPS 默认关闭；移植时替换匹配域名和映射地址，将 `experimental.http_rewrite` 合并到自己的配置即可。

最小本地 Mock 示例：

```json
{
  "experimental": {
    "http_rewrite": {
      "enabled": true,
      "mitm": {"enabled": true, "hosts": ["api.example.test"]},
      "map_local": [{
        "pattern": "^https://api\\.example\\.test/mock$",
        "status": 200,
        "data_type": "text",
        "data": "{\"ok\":true}",
        "headers": [{"name": "Content-Type", "value": "application/json"}]
      }]
    }
  }
}
```

启用 HTTPS 前，通过“工具 → 请求 → MITM 证书”生成并安装本机 CA；证书与私钥不写入 JSON。`enabled` 默认 false，启用后的规则随本地服务启动/重载生效，服务停止或移除该字段后结束。请求记录仍由抓包开关控制；停止记录会关闭旧拦截连接，但不会停用配置中的规则。应用失败恢复及证书生命周期见 [app.md](app.md#配置驱动的-http-改写)。

顶层选项：

| 字段 | 含义 |
| --- | --- |
| `enabled` | 启用此配置的 HTTP 处理，默认 false |
| `ports` / `packages` | 原始目标端口及应用范围；端口默认 80、443、8080、8443，最多 32 个；应用为空表示不限 |
| `mitm.enabled` | 是否解密 HTTPS，默认 false |
| `mitm.hosts` / `mitm.excluded_hosts` | 精确域名、IP 或 `*.example.com`；启用 MITM 必须填写包含列表，排除优先；不接受 Surge 的 `*`、`?`、端口内嵌或负号列表语法 |
| `mitm.block_quic` | 按已观测的名称及配置范围拒绝 QUIC，默认 false，要求 MITM 已开启 |
| `max_body_bytes` | 改写正文的缓冲与输出上限，1～1048576 字节，默认 1 MiB；与抓包保存前缀的限额独立 |
| `resources` | JSON 内嵌资源字典，值为 `{ "data_type": "text"或"base64", "data": "..." }`；静态响应与 JS 可按名称引用 |

规则总数最多 256，扩展 JSON 最多 2 MiB。每条规则都有必填 `pattern`、可选 `name` 和 `disabled`（默认 false）。`pattern` 对进入引擎时的完整 URL（含查询参数）匹配；请求和响应阶段使用相同的原始 URL，避免映射后响应规则失配。声明式正则使用 Go RE2，最多 4096 字节、32 个捕获组，不支持前后查找或反向引用；替换模板支持 `$1`、`${name}` 和 `$$`。正文正则中的 `^`、`$` 按行匹配。

| 能力 / 字段 | 规则内容及行为 |
| --- | --- |
| Header Rewrite / `header_rewrite` | `phase`: `request`或`response`；`operation`: `add`、`delete`、`replace`、`replace_regex`；指定 `header`，普通操作使用 `value`，正则操作使用 `regex`/`replacement`。`add` 保留重复字段；`replace` 只替换已存在字段，新增或覆盖可依次 delete/add。请求 Host 仅允许 replace/replace_regex |
| URL Rewrite / `url_rewrite` | `action`: `header` 透明改写并更新 Host，`302`/`307` 返回重定向，`reject` 返回 403。前两类使用绝对 HTTP(S) `replacement` 模板；首条命中生效 |
| Map Remote / `map_remote` | `replacement` 指定绝对 HTTP(S) URL 模板，默认更新 Host，`preserve_host: true` 保留已有 Host；首条命中生效。与 URL header 规则同时匹配时，Map Remote 决定最终目标 |
| Body Rewrite / `body_rewrite` | `phase`、URL `pattern`、正文 `regex` 和 `replacement`；所有命中规则依次执行，可以从空正文生成内容 |
| Map Local / `map_local` | 首条命中返回本地响应，不请求原 HTTP 服务器。`status` 为 200～599，默认 200；`headers` 为保留重复字段的 `{name,value}` 数组；`data_type` 为 `text`（默认）、`base64` 或 `tiny-gif`，`data` 为内容；也可用 `resource` 引用内嵌资源，与 data/data_type 互斥 |
| API Mock | 静态使用 Map Local；动态使用请求脚本的 `$done({response:{...}})` |
| JavaScript / `scripts` | `phase`、URL `pattern`，以及 `source` 或 `resource`；每个方向只执行第一条启用且匹配的脚本 |

处理顺序为请求 Header → URL → Map Remote → Body → 请求脚本 → Map Local → 上游 → 响应 Header → Body → 响应脚本。重定向、拒绝或脚本 Mock 会提前结束请求路径，生成的响应仍经过响应阶段。首条命中脚本失败不会改用下一条脚本。错误写入诊断；脚本异常、超时、非法结果或上游失败返回 502，超限请求正文返回 413，不把失败改写默认为成功。

脚本接口由 [rewrite_script.go](core/httpdebug/rewrite_script.go) 提供：

- `$request`: `url`、`method`、`headers`、稳定的 `id`；请求脚本设置 `requires_body: true` 时提供 `body`。响应脚本中的 request 不携带正文。URL 是前序规则处理后的值。
- `$response`: 响应脚本可读取 `status`、`headers`，`requires_body: true` 时提供 `body`。先执行静态正文改写，脚本读取其结果。
- `$done()`/`$done({})` 原样继续并结束脚本；请求结果可含 `url`、`headers`、`body`、`response`、`abort`，另支持 B-box 的 `method`。脚本修改 URL 默认保留 Host，需要时一起返回 headers；响应结果可含 `status`、`headers`、`body`、`abort`。动态 response 支持 status/headers/body。
- 默认正文为 UTF-8 字符串；`binary_body: true` 使用 `Uint8Array`。替换当前报文 body 要求 `requires_body: true`；动态 Mock 的 response.body 不要求读取原请求正文。
- `full_headers: true` 将头部作为 `{field,value}` 数组传入，保留重复 Cookie 等字段；结果同时接受对象或数组。普通对象输入每个字段取首值，需完整保留重复字段的脚本应使用 full_headers。
- `argument` 通过 `$argument` 提供；`$script` 包含 name/type/binaryBodyMode；`console.log/info/warn/error/debug` 写入有界诊断日志（每次最多 10 条、每条最多 2 KiB）。只有脚本显式打印的内容进入这些日志。

脚本正文限额可用 `max_body_bytes` 降低，不能超过配置限额；`timeout_ms` 默认 200，范围 1～2000。每次创建独立 Goja runtime，同时最多执行 4 个，调用栈最多 256 层；原生 JS 正则另设 200 毫秒的匹配截止时间。超时是执行限制，不是独立进程的硬内存隔离。未实现网络请求、持久存储、定时器、Node.js 模块、通知、cron、Surge 的完整 JS API 或 jq；不自动执行外链脚本，不把资源名称解释为本机文件路径。配置及备份可以完整携带内嵌 JS 和静态资源。

正文与协议边界：只缓冲命中的报文，支持 identity/gzip/deflate，压缩前后分别检查限额；不支持的编码和非 UTF-8 文本改写会保留原报文并记录原因。响应超限会回放已读前缀并继续原样转发，不截断数据；SSE、WebSocket 升级、HEAD 和 206 响应跳过正文处理。改写后移除旧压缩、摘要、ETag、范围及 trailer 信息，重新计算 Content-Length；禁止规则/脚本手动改变消息分帧、升级头或 101 升级状态，HEAD/204/205/304 不发送不允许的正文。

最初连接仍须通过 sing-box 路由选择，改写不会绕过原路由的拒绝。未改变 origin 的请求沿用原出站、解析结果及网络选项；跨 origin 的目标经 [route/httpdebug.go](core/capture/proxy.go) 重新进入同一核心，保留入站、来源和应用归属，清除旧目的地/规则缓存，并阻止递归改写。没有直连回退。记录保存改写后的请求/响应、原 URL 与命中说明；映射条目的连接路由字段仍表示原连接，界面明确标为原连接出站/规则。

配置 MITM 允许 Map Local 和脚本在原服务器离线时响应，因此 [rewrite_transport.go](core/httpdebug/rewrite_transport.go) 先完成客户端握手，实际需要上游时再连接；每次真实 HTTPS 上游（含映射目标）仍验证证书链、名称和有效期。本地 Mock 不伪造上游 TLS 信息，实际转发后才记录上游 TLS 和证书。仅手动抓包的连接继续使用前文的上游优先握手。CA 信任、证书固定校验、ECH、HTTP/3 及连接/HTTP2 并发边界仍见前文。

参考依据为 2026-11-03 读取的 Surge [HTTP Processing](https://manual.nssurge.com/http/overview.html)、[MITM](https://manual.nssurge.com/http/mitm.html)、[URL Rewrite](https://manual.nssurge.com/http/url-rewrite.html)、[Header Rewrite](https://manual.nssurge.com/http/header-rewrite.html)、[Body Rewrite](https://manual.nssurge.com/http/body-rewrite.html)、[Map Local](https://manual.nssurge.com/http/map-local.html)、[请求脚本](https://manual.nssurge.com/scripting/http-request.html)和[响应脚本](https://manual.nssurge.com/scripting/http-response.html)。Surge 的 URL header 模式提供透明远端映射；B-box 另提供 map_remote 列表。上表明确 B-box 的字段、顺序与支持范围，不宣称配置或脚本完全兼容 Surge。

核心集成固定 [Goja `a177d211bf606f6275674a35bc0d31d8ee0ee396`](https://github.com/dop251/goja/tree/a177d211bf606f6275674a35bc0d31d8ee0ee396)，模块版本 `v0.0.0-20261102223426-a177d211bf60`。共享 setup-core 先将核心模块最低 Go 版本调整为 1.26.0，加入 Xray/libXray 后 `go mod tidy` 再抬到它们要求的 Go 1.27.x，实际构建用 version.properties 的 go1.27.3；Goja 依赖将 x/text 提升到 v0.43.0，并引入 regexp2/v2、sourcemap 和 pprof。Goja 列在应用内开源许可页（`SettingsPages.kt` 的许可列表）；三个上游基线不变。Release、Flutter 核心验证和 Android smoke 都使用同一个安装入口。

## 升级内核

一次只升一个内核，在单独分支上做，CI 通过并实机检查后再合并。

1. 在 Actions 中手动运行 [bump-core.yml](.github/workflows/bump-core.yml)，输入 `kernel`（sing-box、xray、mihomo、libxray）和 `version`。它用 [bump_core.py](tools/bump_core.py) 改写 version.properties，并在 DRAFT PR 中附偏差报告（上游改动了哪些 core/ 导入的包；sing-box 另含 `experimental/libbox/`、daemon 的 gRPC 服务与 `go.mod`）。
2. 在该分支手动运行 `newui.yml` 与 `build-apk.yml`。[verify_multicore.py](tools/verify_multicore.py) 列出 fk-core 尚未实现的 StartedService 方法，并拦下应用对官方解析器的直接调用；Kotlin 编译会拦下官方 libbox API 的改名或改签名。
3. 实机检查：三个内核分别启动、切换、停止；连接页、流量、分流规则、抓包与改写、Tailscale/OpenConnect/OpenVPN 工具。
4. 全部通过后再把 DRAFT PR 标为可审查并合并。

