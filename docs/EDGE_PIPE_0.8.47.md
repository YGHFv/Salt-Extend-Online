# Edge Pipe Compatibility 0.8.47

## Evidence and Scope

The remote-user screenshot reports NET_DEVICE/BROWSER_EXIT. It does not prove an actual browser exit: the previous transport stopped its owned job on an I/O error, then checked whether the browser was alive. A synthetic failed CDP command reproduced this misclassification.

QQ Music QR creation uses official HTTPS endpoints, not Edge. Bilibili QR availability proves neither QQ authorization connectivity nor native Edge launch. Anonymous QQ creation succeeds locally; no claim is made that the remote QQ failure is identified without its specific failure stage.

## Changes

- Native launch supplies Windows CRT descriptors 0..4 in the STARTUPINFO reserved descriptor table, mapping 3/4 to the private input/output pipes. The production command uses standard remote-debugging-pipe without depending on remote-debugging-io-pipes.
- Both Chromium's standard file-descriptor route and explicit-handle route are exercised in real hidden Edge tests. The descriptor layout is independently checked for 32-bit and 64-bit pointer widths.
- The explicit inherited-handle allowlist and suspend-before-owned-job assignment remain. No unrelated Salt handles, existing browser profile, TCP debug listener, no-sandbox flag or policy override is introduced.
- A pipe failure remains BROWSER_CONNECT rather than inspecting process liveness after cleanup. Only an observed process exit before cleanup becomes BROWSER_EXIT with a numeric Windows exit code.
- Cancellation remains cancellation even if an owned process exits concurrently. QQ controller remapping preserves existing QQ authorization stages and translates native browser stages without discarding reasons/codes.
- QQ QR creation distinguishes QR_IMAGE and QR_COOKIE, and classifies transport failures as NETWORK/DNS/TLS/TIMEOUT. Failed replacement invalidates the previous session but permits a fresh explicit attempt.
- The release pipeline now exercises real hidden Edge compatibility using synthetic data after full native regression; no account, official webpage or visible window is involved in this automatic probe.

NeriPlayer and mygo-qyin retain separate official QR/network and browser-login flows. This change does not manufacture device tokens, bypass verification, log credentials or assume that a synthetic browser pass confirms remote authentication.

## Cloud Build Options

The native package currently compiles and tests against the actual Steam Salt 1.19.3 classpath and native libraries. Public workflow source and the open-source compiler dependencies are insufficient for full host ABI regression.

An independently available, authorized host build bundle can be fetched by a GitHub-hosted Windows job from private storage with a scoped download credential and pinned SHA-256. This removes local-runner availability as a runtime dependency, but the storage/license/access boundary must be established before uploading proprietary Salt binaries.

Other options are a dedicated always-on Windows builder, or a pure-public-API build with a separate explicitly reduced validation scope. This release does not publicly redistribute Salt, silently upload host files or claim hosted-only packaging is configured.
