# NeriPlayer 词图对齐清单

## 参考版本

仓库：<https://github.com/YGHFv/NeriPlayer>

本次核对本机相邻仓库，并通过远端 HEAD 核验：
`fd7fdb757821fda7a62aee3a2b7cecf3a4b8bfe0`（2026-11-03）。
不以其他 Neri 分支、桌面仓库或旧 README 的通用描述替代这个版本的实际代码。
所有源码路径相对于该仓库根目录；后续升级应新建 fixture 差异报告，不静默追随 HEAD。

## 对齐的含义

对齐：协议入口、原始字段回退、来源与版本匹配、优先级、手动保护、原始时间和偏移语义。
不对齐：Android 组件、Media3 音频引擎、SAF、Compose 安卓布局、系统歌词输出。

当前仅实现本页标出的几个策略，不宣称词图结果已与 Neri 完全相同。
`LyricSelectionPolicy` 的输入是**调用者已经检验过**的结果，不能直接接搜索第一条返回值。

## 1. 上游位置

| 能力 | 主要参考文件 |
| --- | --- |
| 在线歌词主路径、指定来源资格和自动应用 | `modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/metadata/PlayerLyricsProvider.kt` |
| 网易云原文／翻译／音译字段提取 | `modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/metadata/PlayerLyricsSupport.kt` |
| 手动多源匹配 | `modules/platform/src/main/java/moe/ouom/neriplayer/platform/lyrics/repository/EditableLyricsMatcher.kt` |
| 排名、身份与版本 | `modules/platform/src/main/java/moe/ouom/neriplayer/platform/lyrics/matching/EditableLyricMatchPolicy.kt` |
| 中文／Live 文本归一 | `modules/platform/src/main/java/moe/ouom/neriplayer/platform/lyrics/matching/LyricMatchText.kt` |
| 一般外部歌词时长门禁 | `modules/platform/src/main/java/moe/ouom/neriplayer/platform/lyrics/matching/ExternalLyricMatchPolicy.kt` |
| AMLL 搜索、逐字与时间门禁 | `modules/platform/src/main/java/moe/ouom/neriplayer/platform/lyrics/repository/AmllLyricsResolver.kt`、`modules/platform/src/main/java/moe/ouom/neriplayer/platform/lyrics/matching/AmllMatchPolicy.kt` |
| QQ 原生与 AMLL 选择 | `modules/platform/src/main/java/moe/ouom/neriplayer/platform/lyrics/repository/QQMusicLyricsRepository.kt`、`modules/platform/src/main/java/moe/ouom/neriplayer/platform/lyrics/matching/QQMusicLyricSelection.kt` |
| 酷狗 KRC | `modules/platform/src/main/java/moe/ouom/neriplayer/platform/lyrics/api/codec/KugouLyricCodec.kt` |
| CC 轨道选择与字幕解析 | `modules/platform/src/main/java/moe/ouom/neriplayer/platform/bilibili/lyrics/BiliSubtitleParser.kt` |
| CC 读取、失败回退和缓存 | `modules/platform/src/main/java/moe/ouom/neriplayer/platform/bilibili/lyrics/BiliSubtitleLyricsLoader.kt` |
| 网易云专辑封面 | `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/mapping/NeteaseSongMapping.kt` |
| B 站缩略图 | `modules/platform/src/main/java/moe/ouom/neriplayer/platform/bilibili/api/image/BiliImageUrl.kt` |
| 封面候选和异步保留 | `app/src/main/java/moe/ouom/neriplayer/ui/util/CoverUrlState.kt` |
| 手动封面优先、不可用引用过滤 | `modules/local/src/main/java/moe/ouom/neriplayer/data/local/media/MediaDisplayExtensions.kt` |
| 手动匹配的词图分项应用 | `modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/metadata/SelectedSongMetadataLyrics.kt`、`PlayerMetadataHelper.kt`（同目录） |
| 原始歌词解析和逐字模型 | `modules/lyrics/src/main/java/moe/ouom/neriplayer/lyrics/`、`np-submodule/accompanist-lyrics-core/` |

这些源码依赖不是可直接在桌面全量导入的 Maven 库；迁移应抽取纯算法与协议，替换 Android 外部接口。

## 2. 默认值与存储值

| 项 | Neri 默认／存储值 | 本项目预留 |
| --- | --- | --- |
| 歌词源 | `automatic` | 相同 |
| 网易云指定源 | `cloud_music` | 相同，不用自定义 `netease` 冒充迁移值 |
| QQ 指定源 | `qq_music` | 相同 |
| 酷狗／LRCLIB／AMLL | `kugou` / `lrclib` / `amll_ttml` | 相同 |
| 优先逐字 | `true` | 相同 |
| AMLL 补全 | `true` | 相同 |
| B 站 CC 开关 | `true` | 相同 |
| CC 偏好语言 | 空字符串，自动 | 后续配置 |
| CC AI 后备资格 | `true` | 后续配置 |

参考 `PlaybackPreferenceSnapshot.kt`、`LyricSourcePreference.kt` 和
`PlayerLyricsProvider.kt` 的 `BiliSubtitleLyricsOptions`。
目前这些配置只是骨架里的预留界面，不代表网络功能已经生效。

## 3. 选择顺序不是固定来源链

### 必须保留的分层

```text
可用、已确认手动原文
  -> 有资格的指定来源
  -> 已保存／匹配／下载的原文状态
  -> 当前平台来源（网易云词 / B 站 CC）
  -> 只有满足逐字升级条件才尝试 AMLL
```

### 指定来源资格

上游 `shouldTryPreferredLyricSource` 要求：不是 Automatic、不是本地歌曲，
并且 `hasPreferredSourceFallback(song)` 成立。

- `lyricSyncEdited == true` 时，匹配原文、翻译、音译任一字段未设置（`null`）才有后备资格。
- 未被标为手工编辑时，保存原文明确等于 `""` 会关闭这一指定来源后备资格。
- 已确认可解析的手动原文在主路径更早返回，不会被指定来源覆盖。
- 因此“指定来源在代码中位于 CLEARED 分支之前”**不意味着**明确清空总能被它覆盖。

当前选择核心不含这项资格计算。调用者必须让 `preferred` 在不合资格时返回空，
并区分已确认手动原文与普通存储状态；测试只验证资格成立后的选择分支。

### 升级资格

CC 成功、平台已有逐字、关闭逐字偏好、关闭 AMLL，任一成立就不走逐字升级。
其他条件满足时 AMLL 仅接受可解析且真正逐字的候选，失败保留平台词。
不是所有没有歌词的 B 站视频都会轮询 QQ／酷狗；通用多源搜索主要属于指定来源和人工匹配路径。

### 原文、翻译、音译

上游既有独立获取路径，也有手动多源分项补全；不能把所有轨强制建模成同一 source ID。
目标 `LyricDocument` 为每条语言轨保存来源和对齐关系，原文来源变更原子发布。
当前 `Bundle` 只用一份汇总来源验证选择规则，不实现完整语言轨模型。

## 4. 网易云字段规则

- 原文：`yrc.lyric` **非空白**时优先；否则 `lrc.lyric`，后者做历史 LRC 时间戳规范化。
- 翻译：`ytlrc.lyric`，为空引用时回退 `tlyric.lyric`。
- 这里上游使用 nullable 回退，不是统一“空字符串就再找下一字段”。
- 音译：`romalrc.lyric`，并做历史 LRC 时间戳规范化。
- 保留原响应和结构化解析；YRC 声明存在但解析失败、塌缩时间轴与无歌词不是同一状态。
- 已知网易云 ID 的指定网易云歌词源优先按 ID 精确取词，不再以歌名重搜。

不能拿旧 `/api/song/lyric` 的单一 LRC 字段就声称与新 YRC 链路一致。
新歌词接口与会话维护参考 Neri `NeteaseClient.getLyricNew()`。

## 5. 人工匹配和自动应用

上游手动来源搜索顺序：酷狗、网易云、QQ、LRCLIB、AMLL、YouTube。
本需求只启用前五个，YouTube 不包含在首版功能中。这是**搜索汇总顺序**，不是最终获胜顺序。

- 每源最多 5 个搜索结果，最多 4 个详情候选，最终最多 20 项。
- 独立来源可并发，汇总与排序保持确定性；取消异常原样传播。
- 排序：开启逐字偏好时先按逐字层级，再按置信度、分数、时长差、来源优先级等稳定字段。
- 同层来源优先级数值为酷狗 5、网易云 4、QQ 3、LRCLIB 2、AMLL 1。
- 自动应用要求高置信度、可靠歌名／主歌手身份、版本兼容和已知兼容时长；
  高排名的低置信度逐字结果仍不能自动替换。
- 一般时长容差：6%，下限 7 秒，上限 15 秒；边界包含等号，未知时长拒绝。
- 全零／同一时间塌缩时间轴等必须按上游校验排除或按已有恢复策略处理。

当前只移植一般时长门禁。**尚未移植**完整分数、中文转换、版本规则、时间轴消毒与匹配客户端，
所以目前不能做真正的自动词图匹配。

## 6. AMLL 独立时间策略

`AmllLyricsResolver` 最多读取 5 个搜索候选；先要求有效逐字，再检查末行时间。

| 末行相对音频时长 | 容差 |
| --- | --- |
| 更短 | `min(60000, max(30000, expectedMs * 15 / 100))` |
| 更长／相等 | `min(30000, max(12000, expectedMs / 10))` |

`isAmllDurationCompatible` 本身对未知时长返回 true；
Resolver 在时长未知时另按 `requireDurationMatch` 判断，不能遗漏这个门禁。
在线主路径的 `requireDurationMatch=false` 不意味着已知明显超长候选能跳过检查。

当前两个门禁都已移植，正常时长取整与边界一致；另对超大无效 `long` 输入避免乘法溢出。
尚未移植 AMLL 搜索、下载和 TTML 解析。

## 7. B 站 CC 精确行为

- 没有登录态提前返回，不对每首重复做完整视频查询。
- `aid+cid` 隔离字幕列表；旧兼容 ID 先经 resolver 解析，不把旧编码直接当 avid。
- 自动排序先中文，再区分人工／AI，再按语言稳定排序；不是全世界任意语言人工都在中文 AI 前。
- 显式偏好轨在自动轨前；人工语言判断区分简繁与语言变体，AI 偏好须精确匹配。
- 上游候选函数对**显式指定的 AI 轨**与自动 AI 后备使用不同过滤路径，不能用一个总过滤改变语义。
- 翻译选择另一主语言轨，排除相同语种人工与 AI 的重复字幕。
- 字幕秒转换成毫秒；保留结束时间，不把字数平均分成“逐字”。
- 轨列表缓存 10 分钟；人工失败逐轨回退；所有候选失败清轨缓存。
- 原文用了后备轨或翻译不完整，不长期缓存缺失结果。
- 原文从 CC 成功加载时保留该来源标记，避免翻译从其他来源错配。

当前未移植轨道客户端／解析／缓存；只在选择核心中验证成功 CC 阻止 AMLL 升级。

## 8. 封面精确行为

- 网易云映射用歌曲专辑 `al`（或旧 `album`）的 `picUrl`，首个 `http://` 规范化为 `https://`。
- B 站默认视频图；手动选择的歌曲封面可以独立覆盖，不改音源。
- 手动非空有效引用优先；Neri 排除目录和失效 Android MediaStore 引用。
- UI 快候选使用当前显示封面与原始封面，慢解析期间保留同歌已知可用图。
- 已验证不可用的图才撤销；异步过程中暂无新图不立即把已有图置空。
- 真实图片可用性、下载、本地自定义覆盖与缓存是独立步骤，不是 URL 拼接本身保证。

`BiliThumbnail` 已移植：

- 只处理 `.hdslb.com` 与 `.biliimg.com`；相似域名不接受。
- `//` 规范化为 HTTPS；非法 URL、其他 host 或非法尺寸原样返回。
- 修改路径末段 `@` 操作，不误删父目录的 `@`。
- 保留 query 与 fragment；不篡改带签名的 URL 参数。

当前未实现图片下载、选择覆盖、本地映射、解码验证或 Salt 封面发布。
此 URL 辅助函数不是请求安全网关；网络层仍要检查 HTTPS、凭据、重定向、大小和类型。

## 9. 验证分层

另已新增B站匿名nav/WBI、view分P、playurl DASH映射／音质选择移植，细节见 `BILIBILI_PROGRESS.md`。
使用同一固定commit的可执行策略（特别是bandwidth/1000），不宣称ticket／progressive／全部CDN对等。
双端QR/WEAPI会话已移植，额外强制账号验证、可选DPAPI与本机QR页，未移植Android设备快照/网页登录/完整账号恢复后备；见 `LOGIN_PROGRESS.md`。
网易云详情GET／EAPI取址与响应映射新增移植，严格免费／非试听／完整时长门禁，见 `NETEASE_PROGRESS.md`。双平台共用JSON读取、CDN探测与账号发布生命周期。
B站152项本地协议fixture与15项后台检查、一次公网单字节探测，只证明解析边界，不证明Salt实播或词图一致；网易云当前仅离线协议／真实宿主reader证据。

当前 70 项检查：身份与非法输入、一般／AMLL 时间边界、B 站图片操作、选择短路、
语言轨整体切换、过期结果发布、适配不可用时显式失败。

它们是本项目的契约测试，**不是**运行整个 Neri 测试套件后得到的全量对等证明。

后续需要三类证据：

1. **纯策略 fixture 对比**：同 JSON、同设置、同手工状态，上游与迁移结果来源／时间／文本逐项相等。
2. **平台联调**：用户授权会话下搜索、详情、过期、无权限、CC 后备轨和语言轨失败，分辨实际协议变化。
3. **Salt 显示与播放**：序列化后的逐字、翻译、音译、偏移、封面和拖动在目标宿主实际验证。

Fixture 不保存真实 Cookie、token、签名 URL 或用户私有歌单；测试时使用脱敏固定响应，
每个 fixture 标明参考 commit、预期来源、拒绝理由及原始时序。

## 0.6后续进度

当前实验包为`0.6.0-library-playback-preview`，新增本机发现/个人歌单/收藏分页与独立明确单曲播放提交；原有解析入口仍不自动入队。
这里的0.4/0.5实现与实网证据为阶段记录，最新边界见 [听歌桌与单曲播放](LIBRARY_PLAYBACK_PROGRESS.md)。真实账号列表和平台BASS实播仍未验收。
