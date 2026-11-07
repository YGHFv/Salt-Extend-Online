# Salt Extend Online 0.8.8

Artifact: `plugin/build/libs/plugin-io.github.yghfv.saltneri.online-0.8.8-cover-mime.spmod`.

## Cause and fix

NetEase's public image CDN returns valid JPEG files with `Content-Type: image/jpg`.
The shared image loader accepted only `image/jpeg`, PNG and WebP, rejecting these
responses before decoding. This left list rows showing the music placeholder even
when the API supplied a valid album cover URL.

The loader now accepts the exact `image/jpg` media type, including case variants and
ordinary parameters, as a JPEG-compatible response. This also applies to playback
artwork and avatars using the same loader. It still checks the actual bytes with
the existing bounded decoders: HTML, damaged files, oversized images, generic binary
content types and unrelated MIME strings are not accepted. HTTPS host policy,
cookie isolation, cancellation, byte/dimension budgets and original-aspect scaling
are unchanged. No new dependency or image service is introduced.

## Verification

- Before the fix, both a synthetic real JPEG labeled `image/jpg` and a public
  NetEase cover failed with the old loader. A public CDN HEAD response confirmed
  status 200 and `image/jpg` rather than a missing artwork URL or an API failure.
- After the fix, the same public cover passed through the actual pinned HTTPS
  loader and decoded to a 168 x 168 list thumbnail. Public probe date: 2026-11-07.
- Synthetic cover fixtures check MIME case/parameters, JPEG decoding, list/playback
  aspect ratios, HTML/truncated payload rejection, byte and dimension limits,
  no Cookie/Authorization headers, caching and stale-result cancellation.
- The real offscreen native-page regression now uses a NetEase image URL and real
  JPEG bytes labeled `image/jpg`. Pixel checks verify that the cover is painted,
  rather than accepting a semantics-only node or placeholder.
- The optional `CoverLiveProbe --explicit-public-cover` reads one fixed public
  song's metadata and its image using plugin transport. It prints dimensions only;
  it never saves or prints image bytes/URLs, touches accounts, resolves audio or
  initializes Salt playback. The user screenshot is not read by tools or forwarded.

Upgrade the plugin, restart Salt, then reopen the list or use its refresh button
to discard old placeholder cache entries. Existing files in the Salt installation
and user account storage are not changed by building this release.
