# NetEase Device Environment 0.8.46

## Reproduced Causes

The reported remote-user error is not evidence of their installed version or environment. Separate anonymous probes on this machine reproduced three defects in the previous implementation:

- Page.navigate can return while the official document is loading and createNEFingerprint is still undefined. A single immediate token request cannot establish readiness.
- Network.getCookies for music.163.com also returns parent .163.com analytics cookies. Feeding those into the deliberately narrower music.163.com account jar rejects the entire snapshot.
- An official sDeviceId can contain percent-escaped base64 characters. The old alphanumeric-only device and chain-header checks reject valid snapshots intermittently. Directly inserting this raw cookie spelling into a QR query also decodes it prematurely.

Metadata-only probes confirmed percent escapes representing character codes 43 and 47 (+ and /). No live token, device value, credential or account identity is logged.

## Reference Mapping

- NeriPlayer's pinned NeteaseYdDeviceTokenProvider waits separately for the page, fingerprint API and asynchronous token. Its CookieManager mapping keeps the raw sDeviceId. NeteaseQrLoginClient passes the raw chain through OkHttp addQueryParameter rather than concatenating it into a URL.
- mygo-qyin implements QQ Music, not NetEase device preparation. Its isolated cookie handling, verified credentials and redacted staged errors inform the shared login boundaries; this release does not claim a NetEase implementation from that project.
- Android WebView integration, fake user agents, empty-token/unknown-device fallback, credential logging, security bypass and automatic risk-response retries are not adopted.

## Changes

- The production native Edge path uses an injectable device collector: page readiness up to 15 seconds, API readiness up to 12 seconds, one asynchronous token request up to 12 seconds, then device-cookie readiness up to five seconds.
- Short CDP polls do not use awaitPromise. Only verified navigation-context-unavailable errors are tolerated while waiting for the page/API; other transport failures retain bounded termination of the owned browser job.
- Parent and foreign cookies are filtered out before constructing the strict account jar. Account cookies retain their original host/domain/path scope. An existing MUSIC_U in a fresh device context, duplicate device cookies or a changed token fails closed.
- sDeviceId remains byte-for-byte the browser cookie spelling. Its bounded alphabet accepts base64-like characters and only the canonical %2B, %2F and %3D escapes (case-insensitive hex). Spaces, controls, malformed/nested escapes and delimiters remain invalid.
- The QR query encodes the raw chain once, so one query decode yields exactly the chain sent in x-login-chain-id. The actual Edge user agent is reused in the API request.
- NET_DEVICE preserves browser errors and distinguishes DEVICE_PAGE, DEVICE_API, DEVICE_TOKEN, DEVICE_COOKIE and DEVICE_CONTEXT. Preparation failures still offer user-completed official web login; no unsupported credential is accepted.
- The controller permits a 90-second request budget for the staged native flow. Cancelling the flow closes only its owned browser process tree and removes its private profile.

This release retains the earlier native-login/DPAPI compatibility, QQ full-song/QRC/icon fixes, persistent playlist artwork refresh and sealed-media decoder-switch fix.

## Verification Scope

Offline tests use synthetic accounts and virtual time. Real Edge probes use fresh private profiles with either about:blank synthetic JavaScript or the anonymous official public page. They do not create a QR code, poll authentication, read saved credentials, install a mod or restart Salt.

Passing anonymous device preparation proves a valid official token/device snapshot and cleanup in this environment. It does not prove successful account authentication or eliminate remote browser policies, network restrictions or official service risk checks.

Final production classes pass:

- All 77 inventoried contracts, 105 host/base executions, 12 host-free plugin contracts, 70 core checks and six inspector tests.
- 538 virtual-time device collector checks and 137 device/QR security checks, including escaped-device query round trips and exact header/cookie identity.
- 38 real native Edge checks executing the production readiness/start/poll JavaScript, with one getToken call, asynchronous settlement, invalid-token/origin guards and private-profile cleanup.
- Three consecutive real anonymous official-page device preparations with final production classes; an earlier incremental run also passes three consecutive preparations.
- Synthetic old .NET/new native DPAPI cross-decryption.
- Persistent artwork 68 checks plus three repeated runs, CoverLoader 181 checks, lazy artwork 359 checks and durable playlists 88 checks.
- 1,065 native callback lifecycle checks; isolated no-sound BASS/Media Foundation eight decoder iterations and 16 concurrent mixer switches, with zero remaining sealed media files.
- 21 native UI fingerprints and 364 emitted ABI references.

All 395 archive entries match the fresh verified build byte-for-byte. The artifact isolation validator confirms no bundled tests or proprietary host classes.

Package: `plugin/build/libs/plugin-io.github.yghfv.saltneri.online-0.8.46-netease-device.spmod`

Size: 973,452 bytes.

SHA-256: `A8A8095D9832CD6529CFCF0C51ED940B5FCD92B78D9533C88786DEA30B934930`
