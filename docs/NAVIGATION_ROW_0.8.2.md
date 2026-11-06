# Salt Extend Online 0.8.2

Artifact: `plugin/build/libs/plugin-io.github.yghfv.saltneri.online-0.8.2-navigation-row.spmod`.

## Change

- Keep the three primary catalog tabs on one equal-width row, including windows narrower than 620 dp.
- Share the same row component with Bilibili's three discovery tabs, preserving Salt's native selected style and click behavior.
- Keep playlist toolbar buttons, inline search toggling, account state, and playback unchanged.

## Verification

- Real offscreen Compose tests cover 420, 580, and 740 dp widths at 100% and 150% density.
- Assert that all three primary tabs occupy the same row, have equal widths, do not overlap, and align with the discovery tabs below.
- Exercise discovery rankings, saved catalogs, and created catalogs alongside existing playlist search, sort, play, and restoration checks.
- Test images remain local; no user screenshots or image encodings are returned to conversation context. No installed Salt files or user accounts are modified.
