# 0.8.49 QQ 登录、网易云推荐与 Apple Music

参考源码固定为 MusicOne `2a9be828c8bac38b38c5bd8fabf8611e44c5c134`
和 MineRadio Apple Music Edition `e0ddc69ff62d717515e804c3c2d9be7a5e4a35fd`。
来源、许可证和适配脚本哈希见 [第三方声明](../THIRD_PARTY_NOTICES.md)
及 [reference-lock.json](../reference-lock.json)。

## QQ 登录与发现

QQ 扫码轮询补齐官方客户端参数，保留严格 OAuth state 校验；签名回调接受
`ssl.ptlogin2.qq.com` 和 `ssl.ptlogin2.graph.qq.com` 的 HTTPS `/check_sig`。
原始签名查询不改写，登录 Cookie 和音乐 Cookie 继续隔离。

目录读取沿用登录／恢复时完成的验证，不再每页请求账号资料；每次读取仍检查凭据有效性。
QQ HTTP 状态、业务 code、subcode 和 retcode 失败会中止操作。已登录的推荐请求使用音乐密钥
计算 g_tk，不再覆盖为匿名值。

发现页有推荐歌单、每日推荐、猜你喜欢三个入口：

- 每日推荐读取账号的 `CgiGetDiss` / `dirid=202`，最多 30 首；账号和上海自然日隔离的快照
  保证 20 槽 API 分页及 30 行界面窗口读取同一批歌曲。
- 猜你喜欢读取 `music.radioProxy.MbTrackRadioSvr.get_radio_track`，显式 `id=99`、
  `ct=19`、账号 UIN 和 authst。支持分页／加载更多，每页保存本次返回结果。
- 未登录时个性化入口显示登录要求。加载推荐不会自动播放，不实现无人操作的无限续播。

QQ 点播继续走原来的正式音轨、音质权限和 Salt 队列通道。

## Cookie 登录临时入口

网易云和 QQ 音乐未登录页面增加“填写 Cookie 登录（临时方式）”，已登录账号菜单也可填写
Cookie 更换账号。粘贴 `name=value; ...` 或带 `Cookie:` 前缀的单行请求头，输入默认掩码。
提交、取消和离开页面会清空表单。

网易云需要 `MUSIC_U`，可同时带 `__csrf` 和设备上下文字段。QQ 接受 `qqmusic_key`、
`qm_keyst`、`musickey`，也支持参考项目的 `authst`；账号使用 `qqmusic_uin`／`uin`／`wxuin`，
登录类型支持 `tmeLoginType`、网页登录 `login_type` 和参考项目的 API `loginType`。
QQ 缺少类型时按音乐密钥前缀选择 QQ／微信类型，最终仍需平台验证。
重复字段、控制字符、超长或缺少凭据在发出请求前拒绝，其他平台的 Cookie 不导入。

网易云通过账号接口确定用户 ID，QQ 通过 `GetLoginUserInfo` 验证密钥和账号一致性；
验证成功后沿用 Windows CurrentUser DPAPI 保存／恢复和退出删除。未把手填文本写入日志、
配置或目录状态。Cookie 的本地有效期最多 30 天，实际有效性仍以平台验证为准。
格式错误不会替换当前已验证账号；开始验证有效格式的 Cookie 会撤销此前内存会话。

## 网易云发现恢复

发现页保留推荐歌单，并增加每日推荐、音乐漫游、私人歌单和推荐新歌。窄窗口分两行显示入口。
每日歌曲、私人歌单需要登录，分别读取 `/weapi/v3/discovery/recommend/songs`
和 `/weapi/v1/discovery/recommend/resource`；公开推荐新歌读取 `/weapi/personalized/newsong`。
接口及字段对照 MusicOne `NeteaseApiClient.kt`、MineRadio `server.js`。

音乐漫游入口采用 `/weapi/v1/radio/get` 私人 FM 推荐，并非网易云客户端完整的漫游模式协议。
接口小批次结果缓存在账号隔离的内存序列中，保留跨页余数，兼容 20 槽接口页和 30 行界面窗口。
每日推荐按上海自然日缓存；刷新、账号更换或退出会清理推荐。重复批次停止加载，
取消与账号撤销禁止旧结果回填。请求使用已验证的 Cookie／CSRF，失败明确显示，不切换为匿名推荐。
歌曲继续使用原来的网易云音质、歌词和 Salt 播放队列。浏览推荐不触发自动续播。

## Apple Music

增加第四平台侧栏／顶部入口、首页设置和平台默认栏目。支持热门歌曲、目录搜索、
资料库专辑、资料库歌单和曲目分页。

点击账号图标或官方网页登录，在独立临时 Edge 页面完成登录。只观察该页面的
`media-user-token`，通过 `/v1/me/storefront` 校验后按 Windows CurrentUser DPAPI
加密保存。恢复时重新校验，退出后撤销内存凭据和未完成的点播操作。
本地账号标识是令牌的哈希摘要，不采集 Apple ID 或密码。

公开 AMPWebPlay bearer 从官方前端 bundle 读取，缓存到有效期前一分钟。
公开页面地区跳转仅允许 `music.apple.com` 的地区路径；认证 API 不跟随重定向。
用户 token 只作为 `Music-User-Token` 发往固定 AMP API，不发给公开网页或封面 CDN。

资料库 ID（`l.*`、`p.*`、`i.*`）保持原值，与 catalog ID 分开。
可点播歌曲使用 storefront + 官方 catalog ID；没有 catalog ID 的项目保留显示，
但不可点播，避免错误播放另一个版本。封面只允许 `is1-ssl` 到 `is5-ssl.mzstatic.com`。

双击歌曲调用 Windows Apple Music 官方客户端，通过 UIA 定位、操作，再以 SMTC 确认
曲目和播放状态；失败返回固定阶段。辅助 PowerShell 进程隐藏运行，曲目文字通过 UTF-8
标准输入传递，插件关闭、退出账号、权限撤销或超时会终止辅助操作。

此版本支持官方客户端**单曲点播**。需要用户已安装并登录 Windows Apple Music，
并具备内容权限。音频由 Apple Music 播放，不进入 Salt 队列、音效、音质选择或音频缓存。
Apple Music 整单／随机播放、Salt 控件同步和 Apple 歌词尚未接入。

## 验证边界

新增 Apple 合成协议回归，覆盖 bearer 地区跳转、请求头隔离、账号验证、
storefront、资料库／catalog ID、缺失 ID、封面域、搜索、热门、分页和撤销。
QQ 回归增加每日分页快照、个人电台参数、登录要求和另一官方签名域名。
网易云回归覆盖每日／私人歌单／新歌／FM 接口、鉴权、批次余数、30 行窗口、
去重、跨日、刷新、空／异常响应和取消撤销。
Cookie 回归覆盖字段别名、平台验证、格式拒绝、账号更换、取消和退出；宿主离屏测试
检查 740／420 像素下掩码输入、清空、提交、账号菜单与推荐入口。
原生导航、首页、侧栏、品牌图标随第四平台更新并执行真实宿主离屏测试。

自动回归使用合成账号与 HTTP fixture。公开网络探测仅读取公开页面、bundle 和热门列表。
真实 Apple 网页账号登录、UIA 点播和 QQ／网易云个性化结果仍需使用用户账号实机验收；
编译和合成测试不代表已验证真实订阅账号的登录或发声。

2026-11-09 本地验收：JDK 25、Steam Salt 1.19.3 Java／Kotlin 编译与
368 条宿主 ABI 引用通过；81 项插件回归的 112 次宿主／java.base 执行均有通过日志，
13 项无宿主回归通过。Apple 公开热门实际读取 20 首，未传入账号或执行客户端播放。
Cookie 表单与网易云／QQ 推荐入口在 740／420 像素下通过实际 Compose 交互和截图检查。
完整构建末段修正首页设置测试的第四平台预期后，在同次编译产物继续执行全部余下回归，
并审核全矩阵日志后打包。
