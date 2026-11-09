<div align="center">

# Salt Extend Online

**在 Salt Player 中听 Bilibili、网易云和 QQ 音乐，并浏览 Apple Music**

浏览、搜索、管理歌单，沿用 Salt 原生播放队列、音效与歌词页。

[![Steam 创意工坊](https://img.shields.io/badge/Steam-创意工坊-1b2838?logo=steam&logoColor=white)](https://steamcommunity.com/sharedfiles/filedetails/?id=3835352817)
[![下载模组](https://img.shields.io/badge/GitHub-下载模组-24292f?logo=github&logoColor=white)](https://github.com/YGHFv/Salt-Extend-Online/releases)
[![构建状态](https://github.com/YGHFv/Salt-Extend-Online/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/YGHFv/Salt-Extend-Online/actions/workflows/build.yml)

**0.8.49-qq-apple · 开发预览** · Windows / Steam Salt 1.19.3

</div>

## 界面预览

实机截图，点击查看原图。

<table>
  <tr>
    <td width="50%" align="center">
      <a href="docs/images/bilibili-subscriptions.png"><img src="docs/images/bilibili-subscriptions.png" alt="Bilibili 订阅合集卡片与顶部平台快捷入口" width="100%"></a>
      <br><strong>Bilibili · 订阅合集</strong>
    </td>
    <td width="50%" align="center">
      <a href="docs/images/bilibili-search.png"><img src="docs/images/bilibili-search.png" alt="Bilibili 搜索结果、播放量与时长" width="100%"></a>
      <br><strong>Bilibili · 搜索</strong>
    </td>
  </tr>
  <tr>
    <td width="50%" align="center">
      <a href="docs/images/netease-discovery.png"><img src="docs/images/netease-discovery.png" alt="网易云发现歌单与 Salt 底部播放控制" width="100%"></a>
      <br><strong>网易云 · 发现歌单</strong>
    </td>
    <td width="50%" align="center">
      <a href="docs/images/plugin-settings.png"><img src="docs/images/plugin-settings.png" alt="Salt 原生模组设置中的侧栏、首页与音质选项" width="100%"></a>
      <br><strong>模组设置 · 侧栏与播放偏好</strong>
    </td>
  </tr>
</table>

## 主要功能

| 功能 | 说明 |
| --- | --- |
| 四平台浏览 | Bilibili / 网易云 / QQ 音乐发现、搜索、收藏；Apple Music 热门、搜索、资料库专辑和歌单 |
| QQ 个性化推荐 | 发现页增加每日推荐和猜你喜欢，登录后读取账号专属歌曲 |
| Apple Music 点播 | 独立官方网页登录并加密保存资料库凭据，双击曲目通过 Windows 官方客户端播放 |
| 原生播放与歌单 | 双击播放、整单播放、插播、添加到 Salt 歌单，在线队列与歌单支持重启恢复 |
| 逐字歌词 | 网易云 YRC、QQ 音乐 QRC，兼容普通 LRC 与翻译，显示在 Salt 原生歌词页 |
| 列表与封面 | 卡片／单列／双列切换，合集搜索、排序、分页，列表及播放封面加载 |
| 音质与缓存 | B站 6 档、网易云 8 档、QQ 音乐 10 档音质偏好，按实际队列预缓存后三首 |
| 界面设置 | 可选顶部侧栏快捷入口、按钮显隐、启动首页与各平台默认栏目，跟随 Salt 主题 |

## 安装与使用

1. 从 [Steam 创意工坊](https://steamcommunity.com/sharedfiles/filedetails/?id=3835352817) 订阅，或在 [Releases](https://github.com/YGHFv/Salt-Extend-Online/releases) 下载 `.spmod` 并导入 Salt 模组管理。
2. 启用模组并授予所需权限，包括 `CLASS_TRANSFORM`。更新后完整退出并重启 Salt，同 ID 旧版只保留一个启用实例。
3. 点击侧栏或顶部的 Bilibili / 网易云 / QQ 音乐入口，在平台页登录后浏览内容。双击曲目播放，右键可插播或加入 Salt 歌单。

网易云扫码和网易云／QQ「官方网页登录」需要 **Microsoft Edge**。登录使用独立临时浏览器环境，不读取日常浏览器已有账号；遇到平台风控时需在官方页面完成验证。

Apple Music 入口支持热门、搜索、我的专辑和我的歌单；点击账号图标在独立 Edge 中登录。双击歌曲会调用已安装、已登录的 Windows Apple Music 官方客户端，并由系统播放状态验证结果。此版本支持单曲点播；Apple Music 不进入 Salt 音频队列，不支持 Salt 音效、整单播放和随机播放。缺少 catalog ID 的资料库曲目显示为不可点播。

音质以当前账号的官方权限为准，只播放可访问的完整音轨，不提供试听替代、会员或风控绕过。B站搜索需要登录，网易云部分匿名接口也可能要求登录。

## 版本与兼容

当前版本 **0.8.49-qq-apple** 优化 QQ 授权登录，网易云／QQ 均增加填写 Cookie 登录的临时入口；QQ 增加每日推荐与猜你喜欢，网易云恢复每日推荐、音乐漫游（私人 FM）、私人歌单和推荐新歌，并接入 Apple Music 资料库和官方客户端单曲点播，详见 [本次更新与验证](docs/QQ_APPLE_0.8.49.md)。历史修复见 [更新记录](docs/HISTORY.md)。

实际回归基线为 **Windows / Steam Salt 1.19.3**。模组使用官方插件接口及部分原生 UI／播放内部绑定，按所需接口结构检查兼容性；未来宿主更新可能影响部分功能。

自动回归覆盖原生界面、播放生命周期、封面和登录协议，并验证本机独立 Edge 启动。远端用户账号风控、会员内容及真实连续发声仍需实机验证。

## 开发与文档

完整构建需要 Python 3、JDK 25、PowerShell 和合法安装的 Salt：

```powershell
./scripts/setup-native-ui.ps1
./scripts/build-local.ps1 -SaltHome 'D:\path\to\Salt Player for Windows' -JdkHome 'C:\path\to\jdk25' -Experimental
```

输出位于 `plugin/build/libs/`。提交到 `main` 后先运行 GitHub 离线回归，再由仓库专用 Windows runner 完整回归和打包；提升版本号后自动发布开发预览 Release。

[构建指南](docs/BUILD.md) · [自动发版](docs/SELF_HOSTED_RELEASE.md) · [架构](docs/ARCHITECTURE.md) · [更新记录](docs/HISTORY.md) · [反馈问题](https://github.com/YGHFv/Salt-Extend-Online/issues)

参考项目：[NeriPlayer](https://github.com/YGHFv/NeriPlayer) · [mygo-qyin](https://github.com/Amamiya23/mygo-qyin) · [MusicOne](https://github.com/HGODLK/MusicOne) · [MineRadio Apple Music Edition](https://github.com/FHJI-lgtm/MineRadio-Apple-Music-Specially-Customized-Edition)。来源与许可见 [第三方声明](THIRD_PARTY_NOTICES.md)。
