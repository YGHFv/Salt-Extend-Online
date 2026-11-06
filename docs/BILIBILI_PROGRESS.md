# B站匿名解析与安全传输进度

更新：2026-11-06。实验包 `0.5.0-dual-resolver-preview`。
**已完成公开视频解析和长度探测，不等于 Salt 已能播放平台音频。**
0.4新增两端二维码登录。`BiliClient`默认匿名，也能绑定本插件已验证账号revision；账号仅用于API请求，不把Cookie发送到CDN。
登录/退出使旧解析失效，nav另校验账号身份；登录流程与创建探测见 `LOGIN_PROGRESS.md`。

## 当前链路

`明确点击 → 后台请求范围 → nav/WBI → view/分P → playurl/DASH → 音轨/CDN排序 → bytes=0-0 → 内存租约`

- 输入只支持 BV、av 和 bilibili.com／www／m 的直接 video URL，分P取显式 `p`；默认第1P。
- 页面序号必须有对应正 CID，不以数组位置、整个视频默认 CID 或旧编码猜测身份。
- nav 允许匿名 `code=-101` 且存在有效 WBI key；其他平台错误停止，不绕风控。
- WBI key 缓存10分钟；短key permutation、过滤／排序／UTF-8 URL编码／MD5按固定 Neri 版本移植。
- DASH 合并普通 audio、dolby.audio 和 flac.audio，支持 camelCase／snake_case 字段别名。
- 音质偏好依次为 Dolby、Hi-Res、Lossless、High、Medium、Low，按指定档向下选择；带标签轨不伪装成普通高码率轨。
- `bandwidth / 1000` 与上游可执行代码一致，不照抄上游陈旧的乘8注释。
- CDN只允许 bilivideo.com／bilivideo.cn；稳定去重并优先 upos-*。同选中音轨的服务端备用URL可用于普通网络／5xx失败。
- 403／412／429立即停止，不轮换备用URL规避限制，不降级到试播或重新签名重试。
- 拒绝付费／充电专属／交互式内容、平台明确预览标记、缺失或明显不匹配的音频时长、无有效DASH音轨。
- 单字节探测要求206、精确 `bytes 0-0/total`、Content-Length=1、identity编码和1字节实际内容；不读取整曲。
- 资源上限2GiB；租约最多5分钟，并受已知deadline更早到期约束；过期需用户再次显式解析。
- 封面仅保留受限HTTPS候选；未下载、解码、缓存、写入宿主或显示歌词。

## 网络边界

`PinnedHttpsConnection` 使用 java.base 实现隔离的 HTTP/1.1 over TLS：

1. 一次解析全部 DNS 结果，出现私网／特殊用途／混合地址即拒绝；实际 TCP 使用验证后的 InetAddress，不再次以hostname连接。
2. Proxy.NO_PROXY 原始 Socket 分层为原域名 TLS；默认信任管理器、HTTPS证书端点校验、显式SNI；不关闭任何TLS验证。
3. 不查询全局 CookieHandler、Authenticator、ProxySelector 或全局 HttpsURLConnection socket factory；不读取浏览器／宿主账号。
4. 媒体请求仅GET／HEAD；独立专用POST工厂只开放固定网易云登录／歌曲API，不给媒体代理开放POST。严格状态行／header／CL或chunked framing、64KiB header边界、重复关键header／歧义／截断拒绝。
5. HTTP header名使用RFC token集合，允许合法下划线；CRLF注入／非法空白仍拒绝。
6. API JSON最多2MiB；严格UTF-8、重复key、32层深度、100000节点、字符串／数值界限和精确整数。
7. 媒体重定向最多3次且仅同主机HTTPS；不跨CDN主机转发凭据，不经HTTP跳转。
8. 解析操作总期限30秒、权限／活动状态250ms监视；连接建立前发布Socket以支持取消，配置修改、新请求和停用阻止旧结果发布。

DNS查询本身若被系统解析器长时间阻塞，不能保证立即返回；期限到达后不会再建立连接或发布结果。
后台单worker和单待执行槽位有界；新请求取消并清理旧待执行任务，不无限创建线程或堆积请求。

## 实网记录

2026-11-06，独立CLI、JDK25、`--limit-modules=java.base`，未将Salt宿主放入classpath：

| 项目 | 结果 |
| --- | --- |
| 公共样例 | BV17x411w7KC，第1P，CID279786 |
| API与TLS | 匿名nav、签名view／playurl通过；原域名证书验证和SNI未关闭 |
| 可用音轨 | audio/mp4，mp4a.40.2，50kbps（高偏好降级到匿名实际可用轨） |
| 音频时长 | 199400ms |
| CDN长度探测 | 1265842字节，Range仅0-0 |
| 副作用 | 不下载整曲、不载入Salt、不安装Hook、不入队、不读取账号、不打印签名URL |

首次探测因HTTP header名未允许合法下划线而本地拒绝；修正RFC token检查并新增回归后通过。
这不是平台风控重试，未切换代理、登录态或降低TLS／CDN授权规则。
未保存真实API JSON、CDN链接、Cookie或音频。音轨／长度会随平台变化，此记录不是永久可用保证。
CLI证据不能替代Salt内PF4J加载、BASS解码、拖动、权限撤销和设备重建的实际验收。

## 验证与重现

默认本地构建完全离线：144项HTTPS／取消、40项JSON、152项B站协议、15项异步检查，另保留所有旧宿主契约。
B站fixture租约贯穿真实回环和Salt HTTP reader，覆盖尾部seek、EOF和账号撤销；测试不构造BASS或真实队列。
只有显式传入 `-BilibiliProbe` 才运行公网探测；平台拒绝或网络失败停止，不自动绕过：

```powershell
.\scripts\build-local.ps1 `
  -SaltHome 'D:\SteamLibrary\steamapps\common\Salt Player for Windows' `
  -JdkHome '.\.tools\jdk25\zulu25.38.109-ca-jdk25.0.5.0.3-win_x64' `
  -Experimental -BilibiliProbe 'BV17x411w7KC'
```

此命令只在项目构建目录输出产物，不安装插件，也不自动改变Salt播放。

## 明确差异与剩余项

- 不支持 Neri 的 mountaintoys.cn CDN；已知 bilivideo HTTP地址只规范化为HTTPS，TLS失败不退回HTTP。
- 已实现本插件二维码账号Cookie供API解析；未移植ticket后备、b23短链、HTML5／progressive后备、搜索、收藏、歌单、租约刷新或会员能力。
- 解析结果尚未注册到原生会话或供平台播放按钮使用；当前音频按钮仍只有用户明确触发的本机测试音。
- 会话与实验接线仍需合并统一所有权／停用清理，再独立提供“播放已准备音轨”的明确动作，不能后台任务自动入队。
- Neri完整歌词／CC／AMLL／词轴和封面双原生入口仍未实现；网易云二维码客户端已通过创建探测，歌曲详情／EAPI取址客户端已实现并通过离线契约；实网取流未验收，见 `NETEASE_PROGRESS.md`。

协议／音质移植固定于Neri `fd7fdb757821fda7a62aee3a2b7cecf3a4b8bfe0`，保留GPL来源说明。

## 0.6后续进度

当前实验包为`0.6.0-library-playback-preview`，新增本机发现/个人歌单/收藏分页与独立明确单曲播放提交；原有解析入口仍不自动入队。
这里的0.4/0.5实现与实网证据为阶段记录，最新边界见 [听歌桌与单曲播放](LIBRARY_PLAYBACK_PROGRESS.md)。真实账号列表和平台BASS实播仍未验收。
