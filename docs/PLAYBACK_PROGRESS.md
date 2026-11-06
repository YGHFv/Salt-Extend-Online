# 播放底层进度

更新：2026-11-06。承接会话 `01a1b0e4-4f95-7842-94e7-f9970438ac57` 的未完成流适配工作。
后续已增加真实引擎接线、显式本机试听和B站匿名解析的独立实验包，见 [实验说明](EXPERIMENTAL_PLAYBACK.md)。

## 已落地

| 组件 | 文件 | 行为 |
| --- | --- | --- |
| 平台媒体租约 | `plugin/.../transport/MediaLease.java` | 固定 HTTPS 平台 CDN、长度、账号作用域、合法请求头；诊断隐藏签名和凭据 |
| Range 门禁 | `plugin/.../transport/ByteRange.java` | 单范围、闭区间／开放区间／尾部范围、整数溢出和 4 MiB 单请求上限 |
| 私有传输 | `plugin/.../transport/LoopbackRangeServer.java` | `127.0.0.1` 随机端口、256-bit 随机标识、限流／超时、GET／HEAD／206／416、撤销和过期 |
| 可逆事务 | `plugin/.../host/ReversibleInterceptorLists.java` | 前置本插件拦截器、保留委托列表、逐字段回滚、停用只恢复仍属本插件的覆盖层 |
| 原生流适配 | `plugin/.../host/Salt1193StreamAdapter.java` | 真实 suspend nullable `Result<Int>` ABI、取消异常、flag 透传、只匹配本插件路径、引擎级事务 |
| 播放会话 | `plugin/.../host/Salt1193PlaybackSession.java` | 元数据／租约／私有路径注册、显式 attach／enqueue、账号撤销、权限监视和统一清理 |
| 引擎接线／诊断 | `plugin/.../host/Salt1193EngineLink.java`、`Salt1193DiagnosticRuntime.java` | 真实服务／播放器读取，选定服务的普通方法 Hook，显式本机测试音和撤销清理 |
| DNS固定／解析范围 | `plugin/.../net/PinnedHttpsConnection.java`、`RequestScope.java` | 隔离TLS、固定IP与域名验证、HTTP framing／JSON界限、连接取消 |
| B站匿名解析 | `plugin/.../bilibili/BiliClient.java`、`BiliResolutionController.java` | WBI／分P／DASH选择／单字节探测／后台防旧发布；不入队播放 |
| 双端二维码账号 | `plugin/.../auth/AccountLoginController.java`、`BiliQrClient.java`、`NeteaseQrClient.java` | 明确扫码、账号校验、取消/退出、DPAPI保存与明确恢复；B站账号revision绑定API解析 |

此处 `plugin/...` 均指 `plugin/src/main/java/io/github/yghfv/saltneri/plugin/`。

### 传输规则

- 租约最长 30 分钟、最多 64 个，资源长度在 1 byte 至 2 GiB；不做整曲预下载。
- 上游仅允许 B 站 `bilivideo.com`／`bilivideo.cn` 或网易云 `music.126.net` 域族的 HTTPS；禁非 443 端口、userinfo 和 fragment。
- 只允许注册的 User-Agent／Referer／Cookie；本机请求的 Cookie 等不会转发到上游。
- 禁浏览器 Origin、其他 Host、任意 URL 参数、请求体、重复头和过长请求；不给出 CORS 授权。
- 逐次验证上游 206、精确 Content-Range／Content-Length、identity 编码；失败不回退到全文件 200。
- 最多 3 次重定向；只允许同 host HTTPS 跳转。跨 CDN 备用 URL 由平台解析器显式探测并取得租约，不盲目带凭据跨 host；运行中的租约不自动换址。
- 检查 DNS 解析结果，拒绝私网／回环／链路本地等地址；生产连接不使用系统代理。
- 生产传输改用隔离的 HTTP/1.1 TLS 连接，不调用全局 CookieHandler／Authenticator／ProxySelector，不读取或修改宿主全局账号状态。
- 4 个 worker、16 个排队请求、总请求 12 秒期限；丢弃请求会取消计时任务，避免超时任务无界堆积。
- 退出账号、权限撤销、过期和停用会取消本插件连接，不触碰其他插件的 HTTP 请求。

新增 `PinnedHttpsConnection`：DNS 全部结果先验证，以已验证 InetAddress 连接，最多尝试三个地址；
使用 Proxy.NO_PROXY 原始 Socket，再以原域名分层 TLS、HTTPS 端点校验和 SNI，不重新解析域名。
私网／混合 DNS、特殊用途 IP、重复 framing header、CL/TE 歧义、截断和非法 chunk 均拒绝。
请求 header 名遵循 RFC token，支持 B站实际使用的下划线，不因该合法字段放松边界检查。
B站匿名解析器已实现并有一次真实 API/CDN 单字节探测证据；未验证 Salt 内实际 HTTPS 请求或 BASS 播放。
细节与对等差异见 `BILIBILI_PROGRESS.md`。

### 原生适配和会话规则

- 实际三个列表来自主播放器 Properties、Morvanium 自动混音分析器和 audio hash 分析器。
- 每步修改前查询 `CLASS_TRANSFORM`；中途失败或撤销会回滚已经修改的槽位。
- 保留其他插件条目及原列表之后的修改；如果其他插件之后包装或替换字段，停用时不会覆盖它的字段。
- 停用使保留的覆盖层失活；实际适配器自己也关闭，从旧 iterator 中取得的残留代理不能新开本插件流。
- 健康检查发现列表已被替换掉时不再报告已安装；重复 attach 不叠加，显式对新引擎 attach 支持新事务。
- 仅 `saltneri://` 路径被接管；普通本地歌曲／WebDAV 路径返回 null，交给原拦截链。
- 成功返回 boxed Kotlin Result；失败不给宿主暴露带凭据的上游异常，CancellationException 不被吞成普通错误。
- 不支持子文件 offset／length；传入非零值会失败，不把整曲当子文件播放。
- 成功 stream handle 归宿主所有，不在返回前二次检查取消并遗失 handle；宿主负责取消后释放。
- 不调用 `abortActiveStreams(Unspecified)`，避免清掉其他 Mod 的流。
- 每次准备媒体使用新私有路径，逻辑TrackKey保留平台／BV／CID身份，原生队列ID每次附加独立随机标识；旧队列项不会被重新绑定到新账号。
- 会话最多保留 64 个 prepared tracks，调用者需 close 不再使用的项；账号切换应关闭旧会话、建立新会话。
- 权限每 250ms 检查，且每次操作再次校验；停用清理监视线程、映射、事务、租约及本机监听。
- `nativeQueue()` 只创建延迟调用端口；不读控制器 `INSTANCE`，直到显式入队动作真正调用该端口。

## 测试证据

全部插件检查由 `scripts/build-local.ps1` 自动执行，任何失败都阻止打包：

| 测试 | 结果 | 实际覆盖 |
| --- | --- | --- |
| 核心策略 | 70 项 | 身份、歌词门禁／选择、缩略图、过期发布 |
| classfile inspector | 6 组 | 指纹／结构解析及截断输入 |
| HostProfileSmokeTest | 通过 | 17 类指纹、真实六字段媒体往返、版本／渠道／指纹拒绝 |
| TransportContractTest | 123 项 | 真实回环 HTTP；模拟上游、边界／协议／重定向／截断／连接取消和账号撤销 |
| InterceptorContractTest | 46 项 | 事务回滚／共存、真实 Kotlin ABI、取消／权限／路径／handle 校验 |
| HostRangeIntegrationTest | 通过 | 真实 Salt HttpRangeSource 的 0-0 探测、随机 seek、512 KiB 稀疏缓存、尾部／EOF、撤销后的重开失败 |
| PlaybackSessionContractTest | 26 项 | 真实媒体与 nullable Result／HTTP reader；模拟引擎和 recording queue；准备、显式入队、重建、撤销、停用 |
| EngineLinkContractTest | 33 项 | 真实 Hook API；模拟服务／引擎，检查接线、过滤、回滚和撤销，不安装实际 Hook |
| DiagnosticContractTest | 23 项 | 生成 WAV、真实 reader／ABI、模拟引擎／队列；arm 不入队、play 入队、取消不清队列 |
| PinnedHttpsContractTest | 144 项 | 假 Socket；地址固定、全局隔离、SNI/HTTPS 参数、HTTP framing／取消／请求期限；不是实际 TLS 服务器 fixture |
| BoundedJsonContractTest | 40 项 | UTF-8、重复 key、数值精度、深度／节点／字节／字符串上限和 surrogate |
| BiliClientContractTest | 152 项 | 假 API/CDN；WBI、分P、音质／别名、匿名/付费/预览拒绝、备用URL、长度／过期／重定向 |
| BiliResolutionContractTest | 15 项 | 后台、旧请求不合作仍不能发布、配置失效、停用、权限、过期、脱敏错误 |
| NeteaseClientContractTest | 237 项 | 假API/CDN；EAPI固定向量、精确身份、免费/非试听/完整时长/长度门禁、账号校验/撤销与Cookie不发往CDN |
| NeteaseResolutionContractTest | 32 项 | 双平台共用后台生命周期：有限队列、匿名转登录、账号对象替换、锁内发布检查、撤销/过期/停用 |
| BiliResolvedIntegrationTest | 通过 | B站 fixture → 实际匿名租约 → 真实回环 → Salt HTTP reader、尾部seek、EOF、撤销 |
| NeteaseResolvedIntegrationTest | 通过 | 网易云fixture → 实际租约 → 真实回环 → Salt HTTP reader、尾部seek、EOF、撤销；不访问网易云实网 |
| AuthProtocolContractTest | 48 项 | 假HTTP，双端QR状态、多域/路径Cookie、WEAPI编码、确认后账号校验，兼容新旧官方B站扫码页 |
| AuthLifecycleContractTest | 23 项 | 旧登录不合作、重新登录/权限撤销/退出、合成凭据密文、篡改与退出标记拒绝恢复 |
| LoginQrPageContractTest | 13 项 | 真实回环页面/CSP/capability/异源拒绝、确认后QR撤销、status不含凭据；不打开浏览器 |
| WindowsDpapiContractTest | 通过 | 系统DPAPI的纯测试文本真实往返与篡改拒绝，不是保存真实账号 |
| 最小模块复测 | 123 项重复通过 | `--limit-modules=java.base` 下运行传输检查；不用 java.net.http／jdk.httpserver |

真实宿主 reader 的临时稀疏文件放在当前构建目录 `tmp/`，close 后删除。
默认构建测试从未构造 BASS 音频引擎、提交真实播放队列、访问平台 CDN 或读取账号。
另一次显式公网 CLI 探测访问了匿名 API 和 CDN 的 0-0 Range，输出仅身份／音轨／长度，没有宿主 classpath 或账号。
模拟引擎的事务测试不能替代实际播放器 private final 字段写入／JIT 可见性／并发联调。

## 当前产物

`plugin/build/libs/plugin-io.github.yghfv.saltneri.online-0.1.0-hostprobe.spmod`

仍是被动检查包，虽然已经包含新底层类，`MainPlugin.start()` 不调用它们。
被动包不申请 class-transform，不自动安装、不打开端口、不改变播放队列、不收集 Cookie。
`SaltHostBridge` 仍返回零项可用能力。没有把测试通过等同于“能播放 B 站／网易云”。
当前另有 `0.6.0-library-playback-preview.spmod` 实验包，声明 class-transform，用户明确点击时才接线／提交测试音或后台解析 B站／网易云；
解析不接线、不入队；0.6另有听歌桌与独立确认播放按钮，启用依旧不自动联网；仍未安装或测试实际发声。旧 `0.2.0` 构建只是历史产物，不应同时加载。

## 接线前剩余项

1. 已固定实际 `getPlayer()` 与父类指纹，编写普通 Hook 接线。`onPlayerSet` 只在构造时触发，不能当所有设备重建回调；真实重建仍需验收。
2. 实验入口、权限模式及本机测试音已实现。先验证实际加载／发声，再将接线接入账号级在线会话。
3. 再用合法音频实测 AAC／FLAC／DASH 音轨、BASS handle 释放、拖动、暂停、切歌和停用。
4. 权限撤销已拒绝新连接／新流，但宿主已缓冲的音频／稀疏缓存可能继续供现有流读取；需验证只停止本插件当前流的机制，不能宣称即时静音。
5. 双端二维码登录、B站DASH与网易云EAPI音频取址／长度探测已实现，并已接0.6明确单曲提交入口；实际平台加载/发声、网易云实网、搜索与租约刷新待验收／实现。当前过期路径明确失败，不静默换账号或重新取流。
6. 实现封面下载／解码／两类原生入口及完整歌词模型、转换和发布；当前只有核心策略，未实现词图 UI。

继续开发不需要再次提供 Salt 安装目录。公开 API 的普通 Hook 不支持构造器或 suspend 方法；
不要把“读到了签名／完成了模拟队列”再次误写成实播闭环。

0.6听歌桌/账号共享资源/一用播放确认与新增检查详见 [列表与单曲播放](LIBRARY_PLAYBACK_PROGRESS.md)。
