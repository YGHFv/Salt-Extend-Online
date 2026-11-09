# Login and Authentication Audit (0.8.48)

## References and Scope

- NeriPlayer snapshot `fd7fdb757821fda7a62aee3a2b7cecf3a4b8bfe0`:
  NetEase QR WEAPI payloads, official fingerprint preparation, refresh credential
  handling, QQ OAuth parameters, cookie aliases and profile response variants.
- mygo-qyin snapshot `152d32d2a0ba49a3074b120030684a2d22d899cf`:
  isolated QQ authorization cookies, `GetLoginUserInfo` verification without a
  returned UIN, optional expiry metadata and explicit session fencing.
- mygo-qyin does not implement NetEase authentication. This audit does not
  attribute a NetEase device protocol to that project.
- Scope: QR creation, scan/confirmation, credential exchange, official web
  capture, authenticated account verification, encrypted persistence and restore.

## Confirmed Problems and Changes

1. Native web capture returned cookies and closed Edge before account
   verification. A rejected partial credential left no window in which to finish
   the official verification. Both platforms now require two identical snapshots
   and verify while the owned window remains open. An unchanged rejected
   credential is never retried. Only a changed credential can be verified again,
   with at most three distinct candidates and the existing overall deadline.
   Changes to analytics alone do not trigger another authentication request.
2. Native QQ readiness omitted login type and could infer the wrong family.
   Require explicit `tmeLoginType` or `login_type`. QQ web type 1 maps to API type
   2; WeChat web type 2 maps to API type 1. Conflicting types and same-source key
   aliases are rejected, not tried as another family. Canonical aliases now
   include `musickey` and `login_type`, matching QR-created credentials.
3. QQ OAuth selected `p_skey` globally by insertion order, while the outgoing
   Cookie header was filtered by target domain/path. A parent-domain duplicate
   could produce a `g_tk` for a different key from the one used by authorization.
   Select the most-specific target-matching cookie, calculate `g_tk` from it,
   and send only `p_uin`, `p_skey` and optional `pt_oauth_token` to authorize.
   Cookie paths use boundary matching, not unrestricted prefix matching.
4. NetEase official web verification lost the actual browser UA; restart restore
   also fell back to a fixed UA. Carry the actual UA through verification,
   credentials and DPAPI persistence. QQ authenticated RPCs also retain that
   context. Existing version-1 encrypted files without `userAgent` remain
   readable, with the previous default; no other browser profile is imported.
5. QQ direct profile responses using only `nick` or `logo` were rejected despite
   those fields being accepted inside `info`. Align direct/nested profile fields.
   Continue to verify through `GetLoginUserInfo`, never a public profile lookup;
   a returned identity must agree with the credential identity.
6. NetEase account identities could fail on numeric strings or on a null
   `account.id` with a valid `profile.userId`. Accept exact positive integer
   strings or JSON integers, reject fractional/negative/overflow values and
   require agreement when both IDs are present.
7. Account save acquired the store lock before the controller revision lock,
   while disabling remembered login acquired them in reverse order. Move the
   revision promotion callback outside the store lock and retain serialization
   for the final atomic replacement and sign-out marker. Synthetic concurrent
   regression confirms a blocked promotion does not hold up storage reads.

QQ web credentials also honor a positive `psrf_access_token_expiresAt` earlier
than the browser-cookie expiry. Zero or unavailable optional metadata does not
invalidate an otherwise verifiable credential, consistent with QR metadata.

## Deliberately Not Adopted

- No empty/forged NetEase device token or fabricated device ID fallback.
- No acceptance of unverified QR cookies after a best-effort account check.
- No public QQ homepage as a replacement for authenticated verification.
- No automatic alternate login-type guessing, background token renewal, QIMEI
  simulation, CAPTCHA bypass, TLS bypass or Edge policy override.
- No default browser launch, existing profile/password/session import or
  TCP debugging port. Preserve the private Windows pipes and owned process Job.
- No new logging of Cookie, QR contents, OAuth code, device token, account ID or
  upstream raw error bodies.

## Verification

- Host-free suite: 12 plugin contracts and 70 core checks passed.
- Fresh Salt 1.19.3 build: all 77 contracts passed, including host/base variants,
  native Compose, artwork, playback lifecycle and QQ word-lyric regressions.
  Validated experimental package: 397 entries, 980994 bytes.
- Authentication fixtures: 98 protocol, 54 lifecycle/persistence, 137 NetEase
  device/session, 219 explicit web/capture and 143 QQ OAuth/cookie checks passed.
- All production Java sources compile against the actual Salt host.
- Native fixtures: 60 discovery/policy/origin/cookie checks passed.
- Real isolated headless Edge: 49 checks passed, including stable capture,
  rejection with the browser still alive, changed credential verification,
  standard pipes, actual exit code, cancellation, timeout and profile cleanup.
- Actual Windows CurrentUser DPAPI synthetic roundtrip and tamper checks passed.
- Anonymous official QQ and NetEase QR creation plus one unscanned poll each
  passed on this machine over verified TLS. No confirmation, real account,
  credential persistence or running Salt modification was performed.

These checks do not establish successful authentication for a remote user's
account, network, enterprise browser policy or server-side risk decision.
