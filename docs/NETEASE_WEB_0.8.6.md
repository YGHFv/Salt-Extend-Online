# Salt Extend Online 0.8.6

Artifact: `plugin/build/libs/plugin-io.github.yghfv.saltneri.online-0.8.6-web-verification.spmod`.

## Why this change

The user's new `NET_POLL/API=8821` report identifies a required interactive security-verification step, not a missing-cookie or waiting-for-scan state. The prior release's credential fixes do not satisfy that challenge. NeriPlayer includes an official web-login fallback, which this plugin had not yet implemented.

## Behavior

- NetEase API codes 8821 / -462 stop QR polling, discard the challenged QR locally, and expose an explicit official-web-login button. They never count as successful confirmation and never trigger automatic captcha retries.
- Only clicking that button opens the official `https://music.163.com/` desktop login page, matching NeriPlayer's desktop choice. The user clicks Login and completes the official QR/password/SMS/captcha flow themselves. No passwords or captcha answers are typed by the plugin.
- The helper uses the installed Microsoft Edge with a fresh plugin-owned profile. It does not attach to a running browser, import browser profiles, or use the user's existing cookies. PowerShell runs hidden; the official interactive Edge window remains visible for the user.
- Inherited anonymous pipes carry CDP requests that read only music.163.com-scoped cookies from this dedicated browser. No TCP debugging port or HTTP endpoint is opened. Java receives cookies over the helper's anonymous stdout pipe and requires the normal official account-identity check before publishing a session. Cookies are never printed to logs, written to repo files, sent to media CDNs or exposed to the conversation.
- Temporary browser data can contain the new web session on disk while the window is active; its root ACL is restricted to the current user. Successful completion, cancellation and deadline paths terminate only this helper's child processes and attempt bounded cleanup of only its own verified temporary directory. Cleanup succeeds in the tests; an external file lock or abrupt OS/process termination can leave a protected temporary directory behind. Existing Edge windows remain untouched.
- Account cancellation, logout, replacement, permission loss and plugin shutdown fence late results. Browser launch failure, closure or timeout cannot publish an account. Saved verified accounts continue to use CurrentUser DPAPI.
- The helper requires installed Edge and Windows PowerShell. There is no runtime download, browser binary bundled, TLS override, fabricated device token, captcha solver or stealth browser configuration. The normal Salt UI and QR path remain native.

## Verification

- Synthetic auth fixtures cover 8821 stopping at one poll, no implicit browser launch, explicit verified web publication, cancellation, permission loss, helper error redaction, cookie scope/path/expiry bounds, and rejection of unverified credentials.
- Real offscreen Compose fixtures cover the verification message, fallback button, in-progress status and cancel button at wide and narrow page sizes.
- `scripts/test-netease-browser.ps1` exercises installed Edge/CDP using only about:blank and a synthetic HttpOnly cookie. It opens no visible window or website and cleans its isolated profile.
- `WindowsWebLoginProbe --explicit-headless-fixture` exercises the actual Java-to-hidden-PowerShell anonymous-pipe bridge, private ACL profile, synthetic cookie transfer, forced cancellation and cleanup, again on about:blank only.
- Actual captcha completion, a real account login and subsequent audio playback remain user acceptance items. No test here claims to complete or bypass the user's 8821 verification.

## Install acceptance

Install this version and retry NetEase login. When verification is required, click the official-web-login button, then Login on the official page and finish the offered verification. The dedicated browser should close after credentials are received; Salt must verify the identity before displaying the account. You can cancel from Salt at any point. Report only the new stage/reason code if this still fails, never a cookie or QR payload.
