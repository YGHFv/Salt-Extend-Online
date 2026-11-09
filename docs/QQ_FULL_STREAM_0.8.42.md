# QQ 正式音频播放修复 0.8.42

## 本机复现

2026-11-09 用户截图提示 `合集播放失败 [PLAYLIST/IOException]`，界面停留在已加载的 85 首 QQ 个人歌单。该提示来自队列提交前的播放准备，不是这次发生了原生切歌死锁。

只读取插件自身保存的 QQ 登录，通过插件 DPAPI 与官方接口检查；不读取浏览器登录，不记录 Cookie、签名音频 URL 或原始资料：

1. 已保存 QQ 凭据的 `GetLoginUserInfo` 鉴权成功。
2. 截图对应歌单共 5 页，原始行数为 20／20／20／20／5，均解析成功，无重复歌曲；`loadAll` 得到完整 85 首。失败不在目录或歌单分页。
3. 前三首调用正式 `music.vkey.GetVkey/UrlGetVkey`，业务返回 `result=0`，歌曲身份与 vkey 均匹配。
4. 请求文件名是 `M500 + song MID + media MID + .mp3`，官方返回的 `purl` 文件名却是 `M500 + media MID + .mp3`。原来的 `purl.startsWith(requestedFilename + "?")` 将这种正式路径误判为试听／身份不符，并抛出普通 IOException。
5. 同次官方 `sip` 返回 `aqqmusic.tc.qq.com` 与 `sjy6.stream.qqmusic.qq.com`。前者不是原先允许的 `music.tc.qq.com` 子域，因此也需要补上这一确切 CDN。

两个固定参考项目的正式音频分支都将请求的歌曲身份与返回项匹配后使用官方 `purl`，没有要求 `purl` 与请求文件名完全相同。本轮沿用其正式请求，不移植 NeriPlayer 的试听降级，也不扩大 mygo-qyin 的泛 QQ CDN 白名单。

## 修复

- 请求保持歌曲 MID、媒体 MID、当前音质和账号授权不变。
- 仅接受两个精确文件名：原请求文件名，或该次歌曲详情与音质版本所对应的单 media MID 文件名。
- 返回 `songmid` 继续严格匹配；有 `filename` 回显时必须匹配请求。
- 拒绝 RS02／其他歌曲／其他音质／扩展名不符／绝对 URL／目录穿越／非法字符。
- 按独立查询字段验证唯一且完整匹配的 `vkey`；拒绝重复、后缀伪匹配、其他参数嵌入伪 vkey。
- 保留一字节 Range、HTTP 206、Content-Range、详情中正式音质完整长度以及已授权账号生命周期检查，不因兼容路径而接受试听。
- 增加精确 `aqqmusic.tc.qq.com`，不允许任意 `qq.com`／`tc.qq.com` 子域，不更改跨域跳转规则，媒体请求不发送账号 Cookie。
- 合集失败显示安全的 `QQ_MEDIA_PATH`、`QQ_MEDIA_AUTHORIZATION`、`QQ_API`、`QQ_HTTP`、`QQ_TRACK_UNAVAILABLE`、`QQ_VERIFY`、`MEDIA_HTTP` 等阶段／数值，不显示签名、Cookie 或原始异常信息。
- 保留 0.8.40 的完整暂存解码生命周期、0.8.41 网页登录／封面／图标以及 QRC 逐字歌词。

## 验证

- 截图对应前三首，修复后标准音质均通过正式鉴权与一字节 Range 验证；完整长度分别为 2825204、4337772、2917175 字节，没有下载整首音频或播放。
- 完整 85 首实际歌单通过生产 `SavedTrackStore`、`SavedTrackSession`、首曲正式音频与封面准备、`LazyPlaylistSession`、实际 Salt Media 对象，最终提交到记录用模拟队列；顺序／身份全部一致，仅解析首曲一次。使用本次私有临时存储，不写入用户歌单或真实队列，不初始化 BASS。
- QQ 合同测试 142 次检查，覆盖全部 10 档音质的标准与 canonical 文件布局、实际 CDN、签名字段、跨域边界、试听／错曲／错音质和完整长度拒绝。
- 合集流程测试 89 次检查，补充失败阶段与错误脱敏测试；已有队列提交前首曲失败保护保持有效。

最终正式构建完整 74 项合同矩阵通过，独立 11 项无宿主离线测试通过；364 个实际宿主 ABI 引用通过。正式包 376 项／927484 字节，全部内容逐字节匹配最终构建。SHA-256：`9936884964beacac3af424e5d8f215bad80d3d3b08284579857148688fdfe759`。通过不等于已经实机听到声音：本轮不打断用户现有播放、不覆盖已安装模组、不重启 Salt。

正式包：`plugin/build/libs/plugin-io.github.yghfv.saltneri.online-0.8.42-qq-full-stream.spmod`。更新后需要完整重启 Salt，再在该歌单点击「全部播放」或双击单曲现场验收。
