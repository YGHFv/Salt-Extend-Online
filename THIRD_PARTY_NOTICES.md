# Third-Party Notices

## Mygo-Qyin

- Repository: https://github.com/Amamiya23/mygo-qyin
- Snapshot revision: `152d32d2a0ba49a3074b120030684a2d22d899cf`
- License: AGPL-3.0; see `licenses/Mygo-Qyin-AGPL-3.0.txt`.
- Copyright: upstream authors; upstream notices remain authoritative.
- `QqMusicLoginResponse`, `QqMusicPtui`, QQ authenticated profile verification and
  session fencing adapt `internal/qqmusic/auth*.go`.
- `QqMusicQrcDecoder`, `QqMusicLyricsText` and `QqMusicLyricsClient` adapt
  `internal/qqmusic/qrc_decrypt.go`, `internal/qqmusic/playback.go` and
  `internal/lyrics/qrc.go` / `lyrics.go`. This AGPL-covered portion is combined
  with the GPL-3.0-or-later project under the licenses' version-3 combination terms.
- The decoder's DES tables derive from Go's `crypto/des`: Copyright 2010 The Go
  Authors. BSD-3-Clause notice reproduced in `licenses/Go-DES-BSD-3-Clause.txt`.
- QQ substitutions, byte order and key-schedule changes are a legacy lyric
  wire protocol, not general encryption or a membership/authorization bypass.
- Synthetic regression vectors contain only invented text. Upstream public-song
  encrypted samples are used locally for verification, not bundled in the mod.
- Corresponding local source is in this repository; the mod's existing
  `Plugin-Open-Source-Url` exposes the project source entry point. Distribution
  must include the source matching the distributed build.

## Simple Icons

- Bilibili and NetEase Cloud Music vector paths in `PlatformIcons.kt` are from Simple Icons 16.36.0.
- Source: https://github.com/simple-icons/simple-icons/tree/16.36.0/icons
- License: CC0-1.0; see `licenses/Simple-Icons-CC0.txt`.
- Brand names and trademarks belong to their respective owners; no affiliation is implied.

## NeriPlayer

- Repository: https://github.com/YGHFv/NeriPlayer
- Revision: `fd7fdb757821fda7a62aee3a2b7cecf3a4b8bfe0`
- Revision date: 2026-11-03
- License: GNU GPL version 3 or later; see `LICENSE`.
- Copyright: NeriPlayer developers; original notices in upstream source remain authoritative.

The following Java files are ports or adaptations, not independent claims of authorship:

| Local file | Upstream file relative to NeriPlayer |
| --- | --- |
| `core/src/main/java/io/github/yghfv/saltneri/core/ExternalLyricDurationPolicy.java` | `modules/platform/src/main/java/moe/ouom/neriplayer/platform/lyrics/matching/ExternalLyricMatchPolicy.kt` |
| `core/src/main/java/io/github/yghfv/saltneri/core/AmllDurationPolicy.java` | `modules/platform/src/main/java/moe/ouom/neriplayer/platform/lyrics/matching/AmllMatchPolicy.kt`; acceptance gate from `modules/platform/src/main/java/moe/ouom/neriplayer/platform/lyrics/repository/AmllLyricsResolver.kt` |
| `core/src/main/java/io/github/yghfv/saltneri/core/BiliThumbnail.java` | `modules/platform/src/main/java/moe/ouom/neriplayer/platform/bilibili/api/image/BiliImageUrl.kt` |
| `core/src/main/java/io/github/yghfv/saltneri/core/LyricSelectionPolicy.java` | Selection branches of `modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/metadata/PlayerLyricsProvider.kt` |
| `plugin/src/main/java/io/github/yghfv/saltneri/plugin/bilibili/BiliWbi.java` | WBI mixin/signing branches of `modules/platform/src/main/java/moe/ouom/neriplayer/platform/bilibili/api/client/BiliClient.kt` |
| `plugin/src/main/java/io/github/yghfv/saltneri/plugin/bilibili/BiliAudioSelector.java` | `modules/platform/src/main/java/moe/ouom/neriplayer/platform/bilibili/playback/quality/BiliAudioSelector.kt`; `modules/platform/src/main/java/moe/ouom/neriplayer/platform/bilibili/api/stream/BiliStreamUrls.kt`; `modules/model/src/main/java/moe/ouom/neriplayer/data/model/bilibili/playback/BiliQuality.kt` |
| `plugin/src/main/java/io/github/yghfv/saltneri/plugin/bilibili/BiliClient.java` | View/playurl and DASH mapping branches of `modules/platform/src/main/java/moe/ouom/neriplayer/platform/bilibili/api/client/BiliClient.kt` |
| `plugin/src/main/java/io/github/yghfv/saltneri/plugin/auth/BiliQrClient.java` | `modules/platform/src/main/java/moe/ouom/neriplayer/platform/bilibili/api/auth/BiliQrLoginClient.kt`; related QR auth model files |
| `plugin/src/main/java/io/github/yghfv/saltneri/plugin/auth/NeteaseQrClient.java` | `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/api/auth/NeteaseQrLoginClient.kt`; related QR auth model files |
| `plugin/src/main/resources/auth/netease-web-login.ps1` (legacy device script), `plugin/src/main/java/io/github/yghfv/saltneri/plugin/auth/NeteaseDeviceSnapshot.java`, `plugin/src/main/java/io/github/yghfv/saltneri/plugin/auth/NeteaseDeviceCollector.java` | Official `createNEFingerprint/getToken` parameters, page/API/token stages and raw device snapshot mapping from `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/api/auth/NeteaseYdDeviceTokenProvider.kt`. Native Edge isolation, cookie filtering, context fences and fail-closed validation are plugin-specific. |
| `plugin/src/main/java/io/github/yghfv/saltneri/plugin/auth/NeteaseWeapi.java` | WEAPI AES/RSA branches of `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/api/crypto/NeteaseCrypto.kt` |
| `plugin/src/main/java/io/github/yghfv/saltneri/plugin/netease/NeteaseEapi.java` | EAPI framing/AES/MD5 branches of `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/api/crypto/NeteaseCrypto.kt` |
| `plugin/src/main/java/io/github/yghfv/saltneri/plugin/netease/NeteaseClient.java` | Song detail/audio parameters of `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/api/client/NeteaseClient.kt`; `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/mapping/NeteaseSongMapping.kt`; `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/api/playback/parser/NeteasePlaybackResponseParser.kt` |
| `plugin/src/main/java/io/github/yghfv/saltneri/plugin/netease/NeteaseLyricsClient.java`, `plugin/src/main/java/io/github/yghfv/saltneri/plugin/netease/NeteaseLyricsText.java` | `getLyricNew` endpoint/parameters from the same `NeteaseClient.kt`; YRC/LRC and translation field priority from `modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/metadata/PlayerLyricsSupport.kt`. Salt serialization, transport and item bindings are plugin-specific. |
| `plugin/src/main/java/io/github/yghfv/saltneri/plugin/library/CatalogClient.java` | Read-only favorites/playlist and CloudSearch parameters of the same `BiliClient.kt` and `NeteaseClient.kt`; `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/mapping/NeteasePlaylistMapping.kt` and `NeteaseSongMapping.kt` |
| `plugin/src/main/java/io/github/yghfv/saltneri/plugin/library/AutoSourceMatcher.java`, `plugin/src/main/java/io/github/yghfv/saltneri/plugin/library/NeteaseAutoSourceResolver.java` | Query ordering, six-candidate scoring, duration filters and page matching from `modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/resolver/netease/PlayerManagerNeteaseAutoSourceSwitch.kt`. Two-account revocation, logical identity retention, alternative-version gates and bounded work are plugin-specific. QQ Music reuses the same matching engine with its original identity and account fences. |
| `plugin/src/main/java/io/github/yghfv/saltneri/plugin/library/CatalogHttp.java` | Authenticated-only WEAPI preheat condition from `shouldPreheatNeteaseWeapiSession` in `NeteaseClient.kt` |
| `plugin/src/main/java/io/github/yghfv/saltneri/plugin/library/CatalogFailure.java` | NetEase code 50000005 login hint from `buildHomeErrorText` in `app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/tab/HomeViewModel.kt` |
| `plugin/src/main/java/io/github/yghfv/saltneri/plugin/library/BiliDiscovery.java` | Music chart endpoints/mapping from `BiliClient.kt`; latest-period selection from `app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/tab/bili/BiliMusicHomeRepository.kt`; source priority from `modules/model/src/main/java/moe/ouom/neriplayer/data/model/bilibili/music/BiliMusicModels.kt` |

QQ Music additions in 0.8.38 are GPL-3.0-or-later adaptations of the same pinned revision:

- `auth/QqMusicQrClient.java`: `modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/auth/QQMusicQrLoginClient.kt` (QQ QR, OAuth, credential fields); account profile mapping from `qqmusic/api/profile/QQMusicAccountProfileClient.kt`.
- `qqmusic/QqMusicClient.java`, `qqmusic/QqMusicHttp.java`, `library/QqMusicCatalog.java`: `qqmusic/api/client/QQMusicClient.kt` (gateway, playlists, song details and quality/vkey mapping) and `qqmusic/api/search/QQMusicSearchApi.kt` (desktop search, native lyrics).
- `library/QqMusicAutoSourceResolver.java`: QQ source-switch behavior in the playback runtime, reusing the existing NetEase matching port.
- `PlatformIcons.kt` QQ path: `app/src/main/res/drawable/ic_qq_music.xml`; not part of the Simple Icons CC0 paths. Brand ownership remains unchanged.
- `auth/WindowsQqMusicWebLogin.java` and the shared isolated Edge helper are plugin-specific desktop integration. The QQ/WeChat official page completes authentication; no QIMEI device emulation or preview fallback is ported.

Arithmetic in the duration ports avoids overflow on invalid, extremely large input while retaining
upstream results on normal media durations. The selection port is intentionally incomplete:
it accepts already validated loader results and does not implement upstream caller eligibility,
parsers, matching, language alignment, or network clients. See `docs/NERIPLAYER_PARITY.md`.

The Bilibili port defaults to anonymous public DASH audio and can use an explicitly verified
plugin account on its API requests. It does not implement upstream ticket/progressive fallbacks or paid media.
It enforces HTTPS, a narrower CDN allowlist,
bounded responses and pull-only single-byte length probes. It preserves the upstream executable
`bandwidth / 1000` bitrate conversion, not the stale comment describing `* 8 / 1000`.
`BiliClientContractTest` uses synthetic protocol fixtures and a public WBI signing example;
it does not store real credentials, signed CDN addresses, or fetched response bodies.

QR authentication intentionally omits upstream credential/body logging, network retry policies,
Android/WebView integration, legacy-account migration, and best-effort unverified
credential acceptance. Confirmation must pass a separate account identity check. Optional persistence
uses Windows CurrentUser DPAPI, never a plaintext fallback; restoration is explicit.
The current Bilibili `account.bilibili.com/h5/account-h5/auth/scan-web` QR payload route was observed
in a 2026-11-06 public creation-only probe, in addition to the pinned upstream legacy passport route.

The explicit NetEase web-login fallback follows the desktop official-homepage choice and
user-completed login flow in upstream `NeteaseWebLoginUrl.kt` / `NeteaseWebLoginActivity.kt`.
The native Windows Edge bridge (and retained legacy PowerShell resource) is plugin-specific, uses a fresh isolated profile, and
does not redistribute Microsoft Edge, its runtime, or a captcha/device-fingerprint solver.

The NetEase resolver ports exact song detail GET and EAPI audio URL parameters, not Android playback,
full account-session preheating or automatic network retry policies. It rejects preview,
anonymous paid/unknown-fee resources, missing/mismatched durations and CDN size mismatches.
Logged-in paid tracks still require a server-authorized full-track response. API account cookies
retain their original host/domain/path scope and are never sent to a media CDN. Tests use synthetic
fixtures; the fixed EAPI framing vector was independently computed with Windows .NET cryptography.
No live NetEase song resolution or actual Salt/BASS playback has been verified. See `docs/NETEASE_PROGRESS.md`.

An opt-in alternative-source port searches Bilibili only after an explicit NetEase song-level
unavailable response, using a separately verified Bilibili account. It retains the NetEase
queue/lyrics identity and uses Bilibili CDN/cache policy. It never retries login/risk/transport
failures with another NetEase URL, does not use previews, and stops serving the fallback when
either original or source account revision changes. Unlike upstream eager backup resolution,
it returns the first qualifying full stream to avoid delaying initial playback.

Catalog ports are read-only. Ordinary browsing remains page-bounded; explicit collection search,
sort or play can fetch up to 500 pages / 10000 tracks with an overall deadline. Platform-local
metadata lives in memory until refresh/revocation, never in a private catalog database. They omit
remote writes, preheat retries and risk-control fallbacks. Whole-queue playback uses lazy stream
resolution so later items do not inherit stale signed audio URLs. Discovery uses the mobile rid=3
chart and latest HOT/ORIGINAL copyright chart, never the obsolete Web ranking/v2 music feed.
Unlike NeriPlayer's eager official-MV enrichment, this port resolves MV metadata when playing;
discovery does not request media URLs. Default startup and ordinary catalog loading never play.
Actual host queue replacement/continuous audible playback remain installation acceptance items.

## QR Code Generator

- Repository: https://github.com/nayuki/QR-Code-generator
- Revision: `bcfe1b018cec5f1f91b5705eca5bb4cdd34e9f8d`
- Copyright: Project Nayuki.
- License: MIT, included in `licenses/QR-Code-generator-MIT.txt` and the artifact's license directory.

Four unmodified Java source files (`QrCode`, `QrSegment`, `BitBuffer`, `DataTooLongException`) are
vendored under `plugin/src/main/java/io/nayuki/qrcodegen`; original copyright/license headers remain.
Their SHA-256 hashes are locked in `reference-lock.json` and checked before compilation.
This pure-Java library is the only intentionally bundled third-party Java runtime implementation;
it renders QR data locally as SVG, without an external image service or client CDN.
No proprietary Salt/API/Kotlin/Compose implementation is bundled.

## SPW Workshop API

- Repository: https://github.com/Moriafly/spw-workshop-api
- Version: `0.1.0-dev22`
- Revision: `4f5f164ccf2fb2563efa18afeb9ddaa611978a88`
- Copyright: 2025 Moriafly
- License: Apache License 2.0, included in `licenses/SPW-Workshop-API-Apache-2.0.txt`.

The API is a compile-time/annotation-processor dependency supplied at runtime by Salt.
It must not be bundled as a second host API implementation.

## Gradle Wrapper

`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` and its properties were copied
from the pinned SPW Workshop API example infrastructure. Gradle Wrapper is distributed under
Apache License 2.0. Script copyright notices are preserved. Gradle distribution version and
SHA-256 checksum are pinned in `gradle/wrapper/gradle-wrapper.properties`.

## Steam WebDAV Workshop Item

Reference: https://steamcommunity.com/sharedfiles/filedetails/?id=3829341669

Initially only public functionality descriptions were consulted. After the user supplied the Salt
installation directory, the already installed `com.saltplayermod.webdav` version `0.2.2` package was
inspected locally for interface evidence, then cross-checked against the actual host class files.
No implementation from this item is included in the project or distribution. Its license was not
established, so its implementation is not treated as reusable source code.

## Local Salt Host Inspection

`profiles/windows-steam-1.19.3.json` and the runtime `.properties` resource contain class names,
JVM member descriptors and fingerprints only. Proprietary host code and analysis intermediates are
excluded from distributions and remain in the ignored `.reference/` directory. The local build script
uses the user's installed host as a read-only compile dependency and never bundles it.

## Kotlin And Compose Native UI Build

The native UI uses the Kotlin and Compose compiler 2.4.0 (JetBrains / Android Open Source Project,
Apache License 2.0). Compile-only metadata is supplied by Compose foundation-desktop 1.10.3
(JetBrains / Android Open Source Project) and AndroidX runtime-desktop 1.10.5 (Android Open Source Project).
These artifacts and compiler dependencies are hash-locked in `scripts/kotlin-ui-toolchain.json`.
They are build tools / compile-only inputs, not bundled runtime classes. Kotlin / Compose compilation
may inline Apache-licensed library routines into project classes; the license is included at
`META-INF/licenses/Kotlin-Compose-Apache-2.0.txt`. Every emitted native UI host member reference is
checked against the installed Salt archive, rather than assuming that library version numbers match.

Native row/layout/theme methods are linked to the user's host through fingerprinted bindings;
no proprietary Salt or WebDAV implementation is copied into the plugin.

## MusicOne

- Repository: https://github.com/HGODLK/MusicOne
- Reviewed revision: `2a9be828c8bac38b38c5bd8fabf8611e44c5c134` (2026-11-09).
- License: GPL-3.0; the project GPL license is included in the artifact.

QQ QR polling parameters, the accepted check_sig hosts, daily recommendation directory 202,
and personalized radio 99 were adapted from `source-packs/qq/.../internal/`.
The platform clients, bounded transport and account lifecycle are implemented in Salt Extend.
NetEase daily/new-song recommendation endpoints and field normalization follow
`source-packs/netease/.../NeteaseApiClient.kt`.
Explicit QQ Cookie login accepts the music-key and loginType aliases used in `QqApiClient.kt`.

## MineRadio Apple Music Specially Customized Edition

- Repository: https://github.com/FHJI-lgtm/MineRadio-Apple-Music-Specially-Customized-Edition
- Reviewed revision: `e0ddc69ff62d717515e804c3c2d9be7a5e4a35fd` (2026-11-09).
- License: GPL-3.0, included in `licenses/MineRadio-GPL-3.0.txt` and the artifact license directory.

`plugin/src/main/resources/applemusic/lib/am-common.ps1`, `am-smtc.ps1`, `am-uia.ps1`
and `am-play.ps1` are adapted from `experiment/apple-music-windows-control/poc/lib/`.
Original headers are preserved. The adapter adds hidden process launch parameters and a bounded
UTF-8 stdin/JSON wrapper; it does not bundle Electron, Node.js, MusicKit or Apple client binaries.
Apple public bearer discovery, Web API headers, artwork and library/catalog identity mapping
follow `apple-music-web-lyrics.js`, `desktop/apple-music-web-api.js` and `apple-music-api.js`.
The pinned original and adapted resource hashes are recorded in `reference-lock.json`.
Apple Music audio is played only by the user's official Windows client, verified by SMTC.
NetEase private playlist and daily recommendation response mapping follow `server.js`.
The NetEase private FM protocol was cross-checked with
https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced at
`4a7d557326d16d2f1e5172ee69c9471eb3a50173` (`module/personal_fm.js` and
`module/personal_fm_mode.js`); no source from that project is bundled.
