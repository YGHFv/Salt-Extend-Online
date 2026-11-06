# 网易云歌曲解析与安全取流进度

更新：2026-11-07。实验包：`0.5.0-dual-resolver-preview`。
**已实现歌曲详情、EAPI音频地址与单字节长度探测；未进行真实网易云取流、Salt加载或平台音频实播验收。**

## 当前入口

在插件配置填入正整数歌曲ID或官方`music.163.com/song?id=…`、`music.163.com/#/song?id=…`链接，明确点击“解析/探测网易云免费完整歌曲（不播放）”。
输入不接受短链、歌单、用户URL、任意CDN地址；拒绝重复id参数、用户信息、显式端口、非法ID及溢出。
修改配置、新解析、账号切换、退出、撤销权限及停用会撤销旧准备结果；不存在后台自动播放或自动重新取流。

```text
明确点击 → 捕获账号revision → 登录态账号重验（若已登录）
  → GET歌曲详情 → 免费/权限/身份门禁 → EAPI音频地址
  → 非试听/音质/完整时长/大小门禁 → CDN bytes=0-0 → 内存租约
```

## 固定参考与有意差异

协议仍参考NeriPlayer `fd7fdb757821fda7a62aee3a2b7cecf3a4b8bfe0`：

- `NeteaseClient.getSongDetailByIdsCancellable`：`GET https://music.163.com/api/song/detail?ids=[songId]`，方括号按UTF-8 URL编码。
- `NeteaseClient.getSongDownloadUrl`：`POST https://interface.music.163.com/eapi/song/enhance/player/url/v1`，参数`ids`字符串、`level`、`encodeType=flac`。
- `NeteaseCrypto.eApiEncrypt`：路径`/eapi`转换`/api`、固定分隔与MD5盐、AES-ECB/PKCS5Padding、十六进制大写form编码。
- `NeteaseSongMapping`：歌名、艺术家` / `连接、专辑封面；支持新`ar/al/dt`和所选旧GET端点的`artists/album/duration`字段。
- `NeteasePlaybackResponseParser`：兼容data对象/数组，保留id/time/size/br/type/level字段含义；任何非null `freeTrialInfo`都按试听处理。

本插件**比参考流程更保守**：

1. 详情与取流均要求唯一、精确的歌曲ID，不取列表第一项代替身份校验。
2. 只开放`standard`与`exhigh`，服务端可降到standard；不暴露无损、母带、环绕或会员能力。
3. 必须明确fee为0或8（允许免费低档资源）；fee1/4及未知fee拒绝，即使用户已登录也不猜测会员或单购权限。
4. 存在privileges时校验id、fee、负st及无可播放pl；试听标记、不能听原因和未知音质停止。
5. 音频必须有正`time`，与详情时长相差不超过`max(2000ms,1%)`；缺失时长不冒充完整歌曲。正size必须与CDN探测总长度完全一致。
6. 不移植301后的会话预热重试、HTTP2重试、账号降级、外部解锁或试听fallback。平台拒绝只返回脱敏状态。

因此某些合法歌曲也可能因缺少验证字段被拒绝；拒绝不等同于平台无资源，也不能靠放宽权限门禁掩盖接口变化。
这一步不获取歌词、下载封面、搜索或读取私有歌单；详情里的封面只保留受限候选，未解码或发布。

## 账号与网络边界

- 默认匿名；已登录时重验原账号UID，一旦失效/不一致就停止，不静默匿名解析。
- 仅使用本插件显式二维码登录的账号。Cookie保持domain/host-only/path隔离；若MUSIC_U只覆盖music.163.com、不覆盖interface.music.163.com，明确失败，**不扩大Cookie作用域**。
- CDN仅允许`music.126.net`及其子域；已知平台HTTP媒体地址规范化HTTPS，证书失败不回退HTTP。
- API Cookie永不发送到CDN或本机浏览器。CDN租约只携带固定User-Agent/Referer。
- 专用POST工厂只开放一个固定歌曲EAPI端点；登录工厂仍只有三个WEAPI路径，媒体传输仍仅GET/HEAD。form最多32KiB。
- 共用`JsonResponse`有界JSON读取：2MiB上限、严格UTF-8/结构，允许identity/gzip，拒绝Brotli和其他编码。
- 共用`MediaRangeProbe`：精确206、Content-Length=1、`bytes 0-0/total`、实际1字节；只允许同主机HTTPS重定向，最多3次；403/412/429直接停止。
- 共用`PlatformResolutionController`：每平台一个worker/一个待执行槽位，发布锁内检查权限与账号对象身份，包括anonymous→signed-in切换；过期仅明确重新解析。
- 单操作30秒；租约取`min(平台expi,300秒,URL中已知deadline)`，总资源上限2GiB；不下载整曲、不安装Hook、不修改队列。

## 验证与可选实网探测

默认构建完全离线。新增237项网易云协议fixture检查覆盖EAPI参数与独立.NET计算的固定加密向量、身份/费用/试听/时长/长度/类型门禁、Cookie作用域、账号重验与撤销、gzip上限、重定向及错误脱敏。
32项双平台共享后台生命周期检查覆盖旧任务不合作、待执行槽替换、账号对象替换、匿名转登录、权限撤销、过期和停用。
网易云fixture租约已接真实回环与真实Salt HTTP reader，检查尾部seek、EOF和账号撤销；上游与资源字节为模拟，不构造BASS或真实队列。
所有协议/后台检查另在`--limit-modules=java.base`下复跑，原有B站、登录与宿主契约保留。

2026-11-07最终双构建均通过完整本地检查与资源/许可/宿主类隔离验证；另通过70项核心策略和6组inspector单元测试。
两个包各125项归档条目，构建未修改Salt/Steam安装或用户数据：

| 产物 | 大小 | SHA-256 |
| --- | --- | --- |
| `0.5.0-dual-resolver-preview.spmod` | 251687字节 | `c73de23c3786e832670b38d9d21f724aa1b358ffb867aa7aa098f438a5fdd671` |
| `0.1.0-hostprobe.spmod` | 249972字节 | `5b2e98d51a6ca7e3946c9c68ae2e5febb1ccc276a2f2a28aa910e81306b89f21` |

完整文件名前缀均为`plugin-io.github.yghfv.saltneri.online-`，位于`plugin/build/libs/`；这是同插件ID的两种构建，不要同时启用。

可选`-NeteaseProbe '歌曲ID'`才运行匿名API和CDN单字节实网探测，不读取账号、不保存响应正文、不打印签名URL。
本轮没有执行此实网选项，不能把离线fixture通过写成网易云端到端成功。

```powershell
.\scripts\build-local.ps1 `
  -SaltHome 'D:\SteamLibrary\steamapps\common\Salt Player for Windows' `
  -JdkHome '.\.tools\jdk25\zulu25.38.109-ca-jdk25.0.5.0.3-win_x64' `
  -Experimental
```

## 下一步

1. 用户手动验证0.5实验包加载、双端扫码账号，以及明确歌曲解析/退出/配置变更/权限撤销行为。
2. 统一准备结果、账号revision和原生播放会话的所有权，再增加明确“播放已准备音轨”动作；不得解析完成自动入队。
3. 原生加载/BASS发声/设备重建验收独立进行；搜索、歌单、租约续期、YRC/CC/AMLL与封面双入口另行推进。

## 0.6后续进度

当前实验包为`0.6.0-library-playback-preview`，新增本机发现/个人歌单/收藏分页与独立明确单曲播放提交；原有解析入口仍不自动入队。
这里的0.4/0.5实现与实网证据为阶段记录，最新边界见 [听歌桌与单曲播放](LIBRARY_PLAYBACK_PROGRESS.md)。真实账号列表和平台BASS实播仍未验收。
