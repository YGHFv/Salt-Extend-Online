# Third-Party Notices

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
| `plugin/src/main/java/io/github/yghfv/saltneri/plugin/auth/NeteaseWeapi.java` | WEAPI AES/RSA branches of `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/api/crypto/NeteaseCrypto.kt` |
| `plugin/src/main/java/io/github/yghfv/saltneri/plugin/netease/NeteaseEapi.java` | EAPI framing/AES/MD5 branches of `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/api/crypto/NeteaseCrypto.kt` |
| `plugin/src/main/java/io/github/yghfv/saltneri/plugin/netease/NeteaseClient.java` | Song detail/audio parameters of `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/api/client/NeteaseClient.kt`; `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/mapping/NeteaseSongMapping.kt`; `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/api/playback/parser/NeteasePlaybackResponseParser.kt` |
| `plugin/src/main/java/io/github/yghfv/saltneri/plugin/library/CatalogClient.java` | Read-only favorites/playlist parameters and mapping of the same `BiliClient.kt` and `NeteaseClient.kt`; `modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/mapping/NeteasePlaylistMapping.kt` |
| `plugin/src/main/java/io/github/yghfv/saltneri/plugin/library/BiliDiscovery.java` | Music chart endpoints/mapping from `BiliClient.kt`; latest-period selection from `app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/tab/bili/BiliMusicHomeRepository.kt`; source priority from `modules/model/src/main/java/moe/ouom/neriplayer/data/model/bilibili/music/BiliMusicModels.kt` |

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
Android/WebView device token acquisition, legacy-account migration, and best-effort unverified
credential acceptance. Confirmation must pass a separate account identity check. Optional persistence
uses Windows CurrentUser DPAPI, never a plaintext fallback; restoration is explicit.
The current Bilibili `account.bilibili.com/h5/account-h5/auth/scan-web` QR payload route was observed
in a 2026-11-06 public creation-only probe, in addition to the pinned upstream legacy passport route.

The NetEase resolver ports exact song detail GET and EAPI audio URL parameters, not Android playback,
full account-session preheating, retry/fallback policies or membership features. It rejects preview,
paid/unknown-fee resources, missing/mismatched durations and CDN size mismatches. API account cookies
retain their original host/domain/path scope and are never sent to a media CDN. Tests use synthetic
fixtures; the fixed EAPI framing vector was independently computed with Windows .NET cryptography.
No live NetEase song resolution or actual Salt/BASS playback has been verified. See `docs/NETEASE_PROGRESS.md`.

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
This pure-Java library is the only intentionally bundled third-party runtime implementation;
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
