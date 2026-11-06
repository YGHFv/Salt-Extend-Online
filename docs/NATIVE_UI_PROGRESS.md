# Salt 原生侧栏与平台页面

更新：2026-11-07。当前实验包：`0.7.6-flow-cover`。
**用户最新要求取消页面“播放适配”和来回确认，改为双击曲目直接播放。已移除这些控件：单击曲目选中，双击当前可见合法曲目自动解析/内部接流/一用提交。列表封面保留。最新实机截图只证明账号校验、视频列表和音轨准备；新版真实提交后的解码/发声仍需验收。**

## 0.7.6 流程修正

默认加密记住/后台自动恢复登录，页面移除恢复按钮；双击按同一宿主FIFO发送addToPlay和play；新增实际标签文件接通底部封面；标题与作者共用左边缘；移除整页/未选中行不透明底色，直接透出宿主背景效果。用户截图已确认0.7.5提交到底部但未自动开始。实现、存储变化与验收见[0.7.6流程说明](FLOW_POLISH_0.7.6.md)。

## 0.7.5 双击直接播放（历史）

- 不再显示“播放适配”区域、arm/stop和确认按钮；单击收藏夹/歌单打开，单击曲目只选中，双击曲目直接发起播放。整行（含封面和字幕）使用同一个`combinedClickable`，子标题不另装clickable抢走双击。
- 双击就是明确播放意图，不再要求第二次确认。仅当前可见且enabled的TRACK能发起；伪造/过期行token、禁用曲目、收藏夹ID不能作为双击播放请求。
- 控制器仅在双击后创建一用播放意图；解析仍在后台，待当前音轨通过既有门禁后内部设置流适配并提交一次。单击、渲染、加载封面、普通prepare都不自动播放。
- “移除适配”指移除用户操作/页面控件，不是删除必需的底层流映射。双击才自动接入内部适配器，仍需已有class-transform授权；不自动授权、不绕过免费/权限/账号门禁。模组设置保留低层诊断按钮，正常列表播放不再需要访问它们。
- 后续双击或普通prepare替换旧意图；导航/翻页/账号变化/权限撤销/停用取消未完成请求，最多等待30秒。内部设置前与提交前均重新校验能力；失败不自动入队重试。已成功提交的歌曲不会因离页被清空队列。
- 播放状态只作为提示显示，不含播放控制按钮；`ADAPTER_SETUP`、`PLAY_STATE_CHANGED`和`QUEUE_SUBMIT`仅输出安全类别，`SUBMITTED`仍不代表宿主已接受/发声。
- 离屏`ImageComposeScene`测试发送真实mouse press/release：单击不提交；双击触发假适配设置和假队列各一次，同时断言合成封面画出彩色像素。真实运行中宿主队列/音频未操作。

## 0.7.4 封面与确认（历史交互，已被双击替代）

- B站收藏夹/视频`cover`、网易云歌单`picUrl/coverImgUrl`与歌曲专辑`al/album.picUrl`进入内部模型。原生页面只使用当前可见行token请求图片，不在状态快照中输出图片URL。
- 封面只接受固定公开图片域名：B站`i0/i1/i2.hdslb.com`与网易云`p1/p2/p3/p4.music.126.net`。合法HTTP/协议相对地址升级到HTTPS；拒绝端口、userinfo、fragment、query与其他主机。图片使用隔离DNS固定TLS，不读取全局代理/账号，不发送Cookie或Authorization，不跳转、不自动重试。
- 只加载可见行；单worker、20个队列槽位/缓存项，响应最多1MiB，仅JPEG/PNG，解码前检查尺寸不超过4096与1600万像素，并降采样/裁切为96×96 PNG。显示为56dp缩略图；原图与PNG不落盘，未提供/失败图片显示占位，不阻止曲目准备。离页/停用清理缓存和请求，账号/页面代次失效不能发布旧封面。
- 原生位图由宿主Skia创建，Compose组件退出时释放，构建核验新增Compose/Skia ABI；不捆绑第二套图片库。当前只为列表显示封面，未接原生播放栏/队列封面及歌词。
- 点击曲目仍仅准备音轨。准备后显示中文“尚未播放”；未arm时明确显示“启用适配并确认播放：曲名”，点击即显式同意先接入适配器再提交当前单曲。已有授权才可执行，不自动授予class-transform，不在浏览/准备时arm或播放。
- 合并动作先校验当前一用token与账号/权限，再做适配器设置，最后重新校验后提交。旧token/撤销权限不arm，arm后账号/页面改变不提交。设置失败显示`ADAPTER_SETUP`且没有队列尝试；提交失败显示`QUEUE_SUBMIT/类别`并消费token，不自动重试。
- `SUBMITTED`仅表示原生异步addToPlay调用已返回，不证明音频引擎接受、解码或发声。用户本次截图的Prepared状态不能作为“提交后播放失败”的证据；若下一次出现SUBMITTED而底部仍停留旧歌曲，需要继续审计真实引擎/队列接收链路。
- 保留独立“启用实验适配/停止”入口与配置的prepare/play入口；已有一用提交、账号捕获、权限、免费完整音轨与私有Range门禁不变。没有整单自动播放或播放完成观察器。

## 0.7.3 列表修复

- 原代码只把`type=11`当普通收藏夹，其他所有类型都标成“合集”。创建列表现在接受普通/默认收藏夹的`type=0/1`，保留`11`与缺省类型兼容；订阅列表仍仅接受普通收藏夹`11`。不把两种端点的type字段视为同一种枚举。
- 明确`type=21`或存在正数`season_id/series_id`的条目继续禁用，不能向普通收藏夹内容接口提交合集ID；其他未知类型显示“未支持的列表类型(type=…)”，不再冒充合集。同ID不同普通类型也按一个收藏夹身份拒绝重复响应。
- 错误提示只保留固定接口阶段、固定原因和HTTP/API数字码；不转发上游异常消息、cause、响应正文、UID、Cookie、二维码或音频链接。例如`BILI_CONTENTS/HTTP=412`表示收藏夹内容HTTP拒绝，`NET_DISCOVER/API=301`表示推荐接口API拒绝。
- `BILI_CREATED/BILI_SAVED/BILI_CONTENTS`分别为创建/订阅/内容；`NET_DISCOVER/NET_PLAYLISTS/NET_CONTENTS/NET_SONGS`为推荐/个人/歌单内容/歌曲详情；`BILI_VERIFY/NET_VERIFY`为账号复验，`NET_CSRF`为有界会话预热。
- `HTTP/API`是平台拒绝，`RESPONSE`是响应格式/字段不符合门禁，`AUTH`是复验失败，`LOGIN_REQUIRED`提示先扫码登录；`DNS/TLS/TIMEOUT/NETWORK`是网络类别，`CSRF`表示预热后缺少平台cookie。复验失败不解析旧登录异常文本为数字码。停止后不自动重试或绕过风控。
- 网易云“喜欢”位于“我的歌单/喜欢”，收藏栏目改名“收藏/订阅”；B站匿名发现明确显示“暂未接入”且不可点击，不再由一个未实现按钮触发异常。
- 回归夹具包含默认/普通/旧类型收藏夹、合集与冲突类型、正常文件夹→视频列表→仅准备、HTTP/API拒绝与脱敏。全部使用合成数据，不读取用户真实账户，也没有取得截图请求的原始type/API码，不能据此声称实网报错已全部解决。

## 0.7.2 修复依据

- 0.7.0 的返回值替换方案受实际宿主 Advice 的返回值丢弃问题影响。0.7.1 改为修改 provider，但仍没有运行中 Salt 的普通 Hook 回调触发证据；用户截图证明它没有解决页面问题。
- 之前的 `NativeHookTransformTest` 使用实际 transformer，却使用人工桥接入口直接调用回调。它只能证明该夹具中的对象副作用，不能证明正式桥接会派发回调，更不能证明界面显示。保留这项测试作为旧机制审计，不再让页面依赖它。
- 实际 `ComposableSingletons$AppNavigation_desktopKt.getLambda$1605156558$Voxzen_composeApp()` 返回模组页面持有的同一个 `ComposableLambdaImpl` 对象。现通过其 `update()` 可逆替换 `_block` 中的内容函数体；已有 `NavEntry` 缓存该对象时也会读到新内容。仅本插件两个精确 route 标识进入平台页，其余 route 直接委托原函数体。
- 实际调用测试抓到 Compose 多参数容器的 arity 陷阱：`ComposableLambdaImpl` 同时实现许多 Function 接口，不能直接作为 arity-3 函数体进行 Kotlin 运行时强转。接入时解开编译器生成容器，取原始函数体，保留宿主外层对象。
- 真实离屏图又发现 `Color(Long)` 将宿主已打包的64位 Compose颜色当成普通32位 ARGB，低32位为零使标题/状态/背景透明。现在用 `Color(packed.toULong())`，并断言标题真的画出了像素，而非只生成语义节点。
- 窄内容区采用纵向登录/栏目/分页按钮，避免复用侧栏布局时文字挤成省略号。宽内容区保留横向布局；颜色、字体、圆角仍来自Salt主题。
- 登录按钮只记录明确请求并导航；平台页完成组合后才申请二维码。无可见平台页时不启动新扫码轮询；取消、退出、切到另一平台、停用清理待执行请求。
- 状态提示现在带 `Native UI 0.7.5`：`bound` 表示实际内容对象仍绑定，`dispatch` 为页面函数体调用次数，`platform` 为本插件键命中次数，`rendered` 为页面组合生命周期次数，`visible` 为仍挂载的平台，`error` 为安全错误类别。不输出账号、Cookie、二维码链接或音轨地址。

## 页面位置

按用户截图，在左侧“音乐库”下方依次增加：

```text
音乐库
Bilibili
网易云
设置
```

保留原有音乐库、设置、歌单与其他模组入口。点击两个平台行，在 Salt 内容区展示对应平台页，不启动浏览器或独立窗口；最新截图已确认平台页显示，不等于全功能通过。

- 侧栏使用实际宿主 `SidebarItemLayout`；字体、文本颜色、背景和圆角取自 `SaltTheme`。
- 平台页显式继承侧栏专有 `SidebarItemStyle` CompositionLocal；该宿主 Local 默认会抛出“未提供样式”，不能在页面作用域直接假定它存在。
- 页面提供扫码登录、自动恢复、退出、发现、我的歌单/创建的收藏夹、喜欢/收藏、封面、曲目单击选择/双击播放和分页；无适配/播放确认控件。
- 二维码本机生成，在原生 Canvas 绘制，黑白高对比、四格静区、整数像素格；扫码成功、过期或取消后不再显示旧二维码。
- 未登录不会导入其他软件账号。DPAPI“记住登录”开关在原生模组设置中，默认开启；启动仅恢复本插件保存，仍先校验账号。

原浏览器 `LibraryPage`、`LoginQrPage`、HTML 资源及其回环页面服务器已经删除。播放传输的私有 Range 服务是另一条链路，双击曲目后由内部适配器按需打开；诊断设置也可显式启用，不作为页面服务器。

## 原生接入方式

只适配已经核验的 Windows / Steam Salt 1.19.3：

1. 使用 `afterComposable`，在唯一侧栏 RouteItem 调用之后追加平台行；只有 MusicLibrary 项追加内容，不替换宿主其他行。
2. `SaltPageContentBinding` 获取上述宿主页面对象，在 Swing UI 线程调用 `update()`。不替换NavEntry/provider/contentKey/metadata，不安装普通页面方法Hook，不修改宿主文件，不绕过带默认ViewModel的Composable replacement限制。
3. 页面键继续复用宿主可序列化 `ScreenRoute.ModConfigContent`，只匹配本插件命名空间下的 `/native/BILIBILI`、`/native/NETEASE`。其他模组原函数体不变；宿主原内容键和metadata保持不变。
4. 接入、导航和恢复在UI线程执行。重复接入幂等；停用或观察到授权撤销时恢复原函数体，且仅在当前对象仍持有本插件装入的函数体时恢复，不覆盖后来的所有者。退休闭包即使仍被调用也委托原内容，代次门禁继续保护账号/播放操作。
5. 页面由 Kotlin/Compose compiler 2.4.0 编译；不手写布局协议、不打包第二套 Salt/Kotlin/Compose类。唯一保留的UI Hook为已经有实机截图证据的侧栏 `afterComposable`。

独立UI profile固定20个入口/主题/内容类，包含页面singleton、`ComposableLambdaImpl` 和实际desktop导航构建类，用于验证内容对象的来源。音频17类profile保持独立。旧普通解析器/Advice不再作为运行时UI适配的入口。

宿主 Kotlin module 信息不能直接满足全部源码导入，构建使用哈希锁定的 compile-only 元数据（Compose foundation 1.10.3、AndroidX runtime 1.10.5）；不假设这些版本号就是宿主依赖版本。
`scripts/verify-native-ui.py` 对生成的每一个宿主方法/字段引用核验实际宿主 ABI，缺失即拒绝构建。编译依赖和宿主实现都不入包；内联例程的 Apache 许可随包保留。

## 操作与生命周期

- 授权启用实验包会安装一个侧栏UI Hook并可逆更新一个宿主页面内容对象，有本插件保存时自动联网校验恢复；不打开音频监听、不接引擎、不改变队列。
- 页面状态轮询只读本插件内存快照；点击栏目才请求列表，可见封面后台读取，单击曲目只选中。
- 双击曲目才后台解析、内部启用适配并用本次一次性能力提交；无需人工arm/确认，不整单批量入队或自动重试。
- 原账号/分页/曲目能力门禁继续使用既有控制器，UI 不接收任意 UID、Cookie 或 CDN URL。
- 离开页面清理列表与准备结果。停用清理本插件 Hook 和样式引用；旧页面回调使用启用代次门禁，不能在重新启用后操作新账号。
- 撤销权限后旧 UI 不自行恢复。重新授权时可在模组配置中点击“重新接入 Salt 原生侧栏”。

平台范围没有扩大：网易云匿名发现可用；B站匿名发现仍未实现。个人列表要求扫码后的已验证账号；B站 type21 合集禁用；网易云会员/单购/试听拒绝。列表与底部封面已实现，歌词、搜索、整单自动连播和自动租约续期仍未实现。

## 构建与安装

首次准备只用于编译的固定工具：

```powershell
.\scripts\setup-native-ui.ps1
```

构建实验包：

```powershell
.\scripts\build-local.ps1 `
  -SaltHome 'D:\SteamLibrary\steamapps\common\Salt Player for Windows' `
  -JdkHome '.\.tools\jdk25\zulu25.38.109-ca-jdk25.0.5.0.3-win_x64' `
  -Experimental
```

输出：`plugin/build/libs/plugin-io.github.yghfv.saltneri.online-0.7.6-flow-cover.spmod`。
通过 Salt 的“模组管理”本地导入，替换旧实验包，不需要将源码或编译器放进播放器目录，更不能替换 `app/ffmpeg-x64.dll`。
被动包与实验包使用同一插件ID，不要同时加载。构建不自动安装、授权或启用，不访问真实账号和播放队列。

Gradle 另需 `-PsaltHostPackage=<实际宿主classpath包>`；该路径仍未完整构建验证，本轮使用本地脚本且没有绕过TLS。

## 验证边界

- `NativeUiContractTest`：实际宿主NavEntry/页面缓存对象/编译后Compose分派函数体调用，使用人工Composer和侧栏注册器，不仅手动调用普通Hook；覆盖双端精确分派、其他模组委托、metadata/内容键保持、退出/权限/请求与旧闭包，新增封面源不出现在快照、离页失效检查。
- `SaltPageContentBindingContractTest` 12项：实际页面对象、原始函数体解包、UI线程、重复安装、恢复、权限拒绝、已关闭安装拒绝和不覆盖后来所有者。
- `NativePageRenderTest` 41项：实际缓存页面对象、真实离屏Compose/Skia宽窄页面、人工QR、合成封面像素与鼠标单/双击；假适配和队列，只测试手势分派与一用动作，不进行实际扫码、窗口、浏览器或音频播放。
- `CoverLoaderContractTest` 52项：假图片HTTP与真实JPEG/PNG解码、主机/查询门禁、无Cookie、响应/像素上限、缩略图、缓存与撤销。`NativeDeskPlaybackContractTest` 58项：模拟旧确认/内部设置及新增双击意图、行门禁、后台直接提交一次、设置/队列失败、导航/权限/普通prepare取消、设置期间代次变化。
- 20个UI指纹继续核验，新增Compose/Skia方法/字段也逐一对实际宿主检查；目录、工作流、双平台桥接与登录/HTTPS/取流/Range/引擎回归继续执行。实验和被动包均检查依赖隔离、许可与配置。
- 离屏图可证明平台代码真实执行/布局/绘制，不等于运行中Salt的PF4J更新流程、主题/高DPI所有组合或官方手机扫码/实际发声验收。测试图中的 `example.invalid` 二维码是人工样本，请勿扫描。

建议手动验收顺序：导入并授权新版 → 先回到音乐库，再点击 Bilibili／网易云 → 确认内容区为平台页而非“模组管理／无项目” → 切换主题/缩放及窗口大小 → 点击网易云发现 → 在对应官方App扫码 → 验证个人歌单/收藏 → 单击曲目检查选中与封面 → 愿意改变当前播放时双击曲目，无需手动启用适配或再次确认。
本次重点：重载列表检查左侧封面；单击曲目不打断当前播放，双击才直接播放，页面不再有适配/确认按钮。若仍无法播放，提供安全状态码及底部是否切换歌曲；勿发送Cookie、二维码或签名链接。列表失败则提供栏目与安全阶段码即可。
若仍落到模组页，切回配置点“查看实验状态”，只提供这几项计数和错误类别；`bound=false` 表示实际页面对象未绑定；`bound=true, dispatch=0` 表示没有进入新函数体；`platform>0` 但 `rendered=0` 表示命中后平台页没有组合，不能视为二维码已显示。
若入口没有出现，先查看配置状态并“重新接入 Salt 原生侧栏”；提供界面截图或不含凭据的错误类别即可，不要发送 Cookie、二维码URL或音频签名URL。

## 0.7.2 历史产物

| 产物 | 大小 | SHA-256 |
| --- | --- | --- |
| `plugin-io.github.yghfv.saltneri.online-0.7.2-native-content-fix.spmod` | 340178字节 | `f2e349f660474e61670a180e602b8c415d2eff60a2bd7202c865e4c37b193991` |
| `plugin-io.github.yghfv.saltneri.online-0.1.0-hostprobe.spmod` | 337963字节 | `dde61a36269e66a579421a1170f3c70bd61409100a28513e2144448585e33ff8` |

以上是0.7.2历史归档信息，不代表当前被动包哈希。

## 0.7.3 历史产物

| 产物（plugin/build/libs） | 大小 | SHA-256 |
| --- | --- | --- |
| `plugin-io.github.yghfv.saltneri.online-0.7.3-catalog-fix.spmod` | 347240字节 | `f992d6d03c7e413b8b4aeb9fcc4b7929c5e192d2ffc785d9e2ba89ab521f0fc1` |
| `plugin-io.github.yghfv.saltneri.online-0.1.0-hostprobe.spmod` | 345033字节 | `2fa56d922a6b400531ffe708d188176b0de6580501f832600fdb78e6acc3b9ac` |

0.7.3双构建与旧回归通过；上述被动包已被后续构建覆盖，哈希仅作历史记录。

## 0.7.5 历史产物

| 产物（plugin/build/libs） | 大小 | SHA-256 |
| --- | --- | --- |
| `plugin-io.github.yghfv.saltneri.online-0.7.5-doubleclick-play.spmod` | 366956字节 | `81ae8174d86e7bc4699d393a120484fc8197727849b6d0b627e071749741d912` |
| `plugin-io.github.yghfv.saltneri.online-0.1.0-hostprobe.spmod` | 364738字节 | `dafb29cbaf67689ced599d33a00a8235cb971da59026ce6f9f2a3ac849892199` |

双构建完整离线回归通过：121项目录、38项工作流、58项播放意图/确认亦在java.base下复跑；52项封面、50项UI、12项内容绑定、41项离屏/鼠标手势、20个UI指纹及144个实际宿主ABI引用通过。核心70项与inspector6组检查通过。每包168项归档条目，无宿主/API/Kotlin/Compose/Skia运行时类、浏览器页面或测试类。
人工检查的本轮渲染图位于`plugin/build/local/444b675ab678407294aab5dd6fafaf7a/tmp/native-ui-render`。不含真实账号/网络图片；本轮不安装到运行中的Salt，不发起真实平台请求、不操作真实播放队列。Gradle完整路径仍未实测。

2026-11-07续接会话复核：两包归档校验及上述SHA-256一致，实验包全部条目与原测试构建逐字节一致。复跑121项目录、38项工作流、58项播放意图、52项封面、50项UI、12项内容绑定和41项离屏/鼠标手势检查通过；人工检查`plugin/build/resume-verify/026c902bde6e40db996890df62513a31/native-ui-render/cover-prepared-740.png`确认合成封面显示、页面没有适配/确认按钮。本次只修正文档中的旧操作说明，未改动插件二进制，真实发声仍待手动验收。

## 0.7.6 当前产物

`plugin/build/libs/plugin-io.github.yghfv.saltneri.online-0.7.6-flow-cover.spmod`已完成最终双构建与归档验证：372278字节，SHA-256 `92ca704b21c03ce50b51c35d3daebf2ddb36cffa5b9538fa6d02d964323d31e9`。新增默认记住/自动恢复登录、明确add→play、实际宿主封面标签读取、标题作者对齐和透明宿主背景。75项原生渲染/背景/手势检查及既有回归通过；完整计数、被动包哈希、临时封面存储变化与手动验收见[0.7.6记录](FLOW_POLISH_0.7.6.md)。
