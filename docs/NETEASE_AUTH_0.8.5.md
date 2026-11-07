# Salt Extend Online 0.8.5

Artifact: `plugin/build/libs/plugin-io.github.yghfv.saltneri.online-0.8.5-netease-auth.spmod`.

## Reference and fixes

Compared with YGHFv/NeriPlayer commit `fd7fdb757821fda7a62aee3a2b7cecf3a4b8bfe0` (also the current remote HEAD during this audit), particularly `NeteaseQrLoginClient` and its playback parser.

- Confirmed QR refresh-token fallback previously created a host-only MUSIC_U cookie. That logged in to music.163.com but could not authorize interface.music.163.com. Seed only this confirmed fallback as a music.163.com domain cookie, matching NeriPlayer. Never broaden arbitrary server host-only cookies or send login credentials to media CDNs.
- The playback resolver previously verified the account then discarded refreshed cookies, continuing with the old snapshot. It now uses the verified snapshot for song metadata/EAPI requests, retires it on every exit, and retains original-account revocation checks and queue scope.
- Match the reference QR web request's language, cache and client headers. Retain verified TLS, endpoint allowlists, bounded responses and no risk-control retry. Do not fake the reference's Android device-token provider or accept unverified credentials.
- Missing optional profile fields no longer invalidate a positive verified account ID. Conflicting identities and absent login credentials still fail closed.
- Login UI now reports fixed stage/reason codes (NET_CREATE, NET_POLL, NET_VERIFY; HTTP/API/COOKIE/IDENTITY/TIMEOUT/etc.). No upstream response messages, cookie values or URLs appear in errors.
- Logged-in VIP/purchased song flags no longer preempt the official playback authorization response. A successful full-track response is still required, including identity, duration, URL, size and no preview/trial; anonymous paid tracks remain rejected.

## Verification and remaining acceptance

- Offline fixtures cover QR refresh fallback through saved-session verification and playback API cookies; direct-cookie precedence; no scope widening or CDN leakage; refreshed-cookie use; optional profile; redacted stage errors; authenticated full-track versus preview/denied responses.
- All existing protocol, native UI, queue and artifact tests remain in the full local build.
- Before changing the code, live anonymous QR creation and one unscanned poll already succeeded. Anonymous account endpoint returned JSON, code 200 and no account, as expected. This does NOT reproduce or explain every possible cause of the user's generic IOException screenshot.
- The updated client also passed live QR creation and one unscanned WAITING_SCAN poll. Full local build passed 98 auth, 252 NetEase resolver and 241 catalog checks, along with the existing native UI/queue regressions. Neither live check confirms an account or proves signed-in playback.
- An anonymous song probe for 347230 stopped at the existing entitlement gate; no full audio or user account was accessed. Actual signed-in online playback still needs user acceptance.
- Upgrade and use the NetEase account icon to create a fresh QR, scan with the official app and confirm. Old plugin-generated host-only saved cookies are not silently widened. If login still fails, report the new stage/reason code and whether failure occurs before QR, after scan or after confirmation; do not share cookies or QR contents.
- Build/test renders are local and synthetic. No installed Salt files, real account data or audio devices are changed.
