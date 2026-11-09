# 0.8.40 Decoder Input Lifetime

## Observed Local Hang

On November 9, 2026, the running Salt 1.19.3 JVM with installed plugin
0.8.37 had its AWT event thread unchanged in `BASS_Mixer_StreamAddChannel`
across three JVM samples. Windows' `Responding=True` did not detect the
blocked event dispatcher.

Windows Wait Chain Traversal identified an audio thread as the owner of
the critical section needed by that AWT thread. Two native stack samples
placed the owner in `mfreadwrite` / `MFPlat`, called from BASS decoding,
FX, and mixing. A narrow, read-only stream metadata snapshot showed both
online streams aborted before their advertised physical length, with
terminal plugin callback gates. No account contents, media URLs, heap
dumps, or changes to installed host code were collected.

A separate no-sound BASS process using a generated 120-second fragmented
AAC/M4A reproduced the trigger: closing the old factory after opening
allowed four buffered PCM reads, then `BASS_ChannelGetData` did not return
within the process's 20-second deadline. A complete ordinary EOF worked.
Returning -1 or enabling asynchronous user-file reads did not resolve
the incomplete-input case. This evidence establishes the tested trigger,
not the original reason each live lease was revoked or every possible
device/decoder hang.

## Fix And Tradeoffs

MP4-family boxes, ADTS AAC, and ASF headers now select complete private
temporary input staging before a native handle is published. Preparation
uses the existing authorized loopback source and validates every byte
through that source. Partial, cancelled, oversized, or timed-out inputs
never reach Media Foundation.

The exact existing BASS library creates the local file handle with the
host's flags plus Windows Unicode-path support. A per-handle FREE sync
keeps the file, callback, and source ownership until the host frees the
handle. Revocation cancels network access and future opens immediately;
the already-authorized complete decoder input remains stable until that
handle is released. No global owner abort, engine replacement, global
Media Foundation disable, forced host-owned handle free, or host binary
patch is used.

Other formats retain the guarded Range callback path. Staging is bounded
to 512 MiB per file, 1 GiB across active files, and 60 seconds checked
between bounded host reads. The temporary copy is independent of the
configurable reusable Range cache. Windows delayed native file closing
uses bounded deletion retries for only that exact owned file. A crash can
leave a temporary file; startup does not scan or delete user directories.

This trades increased AAC/MP4 first-play latency and disk usage for stable
decoder teardown. Native revocation cannot retract bytes already consumed
by a decoder; the complete private input is not reused for future opens
after account/permission revocation. No offline download/export is added.

## Verification

`NativeStreamLifecycleTest` includes real host callbacks with injected
native allocation: format selection, complete file transfer, partial input
rejection, size/cancellation bounds, retention through revoke/unload,
FREE cleanup, and the existing 1,000-call no-retry-storm regression.

`NativeDecoderSwitchProbe` is explicit, not part of ordinary offline
tests. It takes the installed BASS library and a self-generated AAC/M4A
fixture, initializes only BASS's no-sound device, repeatedly decodes,
revokes, reaches full EOF, and frees in its own process. The parent must
enforce a process timeout. It never attaches to the user's Salt or uses
real account/audio content.

Full UI/account/transport/native contract build and the host-free suite
are required before delivery. Real account continuous playback, random
switching, output device rebuild, and unrelated third-party mods remain
separate acceptance boundaries. The hung old native call cannot be
safely unwound by hot-updating the plugin; fully exit/restart Salt before
using the new package. User credentials, preferences, queues and cache
are not cleared.

The targeted callback regression passed 1,065 checks, including caller
song-switch cancellation during staging. Using the same
generated 120-second AAC fixture and same installed native libraries,
the old factory timed out during its first revoke/decode/free iteration;
the fixed factory completed eight revoke/seek/EOF or mid-track free
iterations, plus 16 concurrent mixer add/revoke/free switches, and left
zero owned staging files in the isolated test directory. ADTS AAC also
passed eight decoder iterations. This is real decoder validation,
not synthetic native handle allocation or audible playback.

## Local Installation

The final 0.8.40 package passed all 74 inventoried contracts (including
host and java.base repetitions), the 11 host-free plugin contracts,
70 core checks, six inspector tests, and 357 emitted UI ABI checks.
The extra final cancellation regression passed 1,065 callback checks
and three repeat runs. All 372 packaged entries match the fresh final
verified build. Package size is 918,638 bytes; SHA-256 is
`8629000776910187F7A87CB716FF12E45C55E408DF30C7BA3F02A311FDD0F6C6`.

At the user's explicit request, the verified hung Salt processes were
terminated, old plugin package/extraction moved outside the scan directory
to a timestamped backup, and 0.8.40 installed and restarted. Enabled-mod
and permission files remained byte-for-byte unchanged. Credentials,
preferences, playlists, and reusable cache were not edited.

The restarted process extracted the expected 0.8.40 manifest, reported
`safeMode=false`, started all three platform workers, and showed an AWT
dispatcher in normal event-queue waiting in two samples with advancing
thread CPU time rather than the previous mixer stall. The existing queue
was restored without an explicit test queue submission. This verifies
installation and recovery from the old hang; prolonged real-account
switching remains an acceptance boundary.
