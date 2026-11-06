# Salt Extend Online 0.8.4

Artifact: `plugin/build/libs/plugin-io.github.yghfv.saltneri.online-0.8.4-centered-tabs.spmod`.

## Changes

- Bilibili's primary tabs now read Discover, Favorites, Subscriptions in Chinese. Corresponding catalog headings use the same shorter names. NetEase keeps its existing personal-playlist label and uses the shorter subscription label.
- Primary navigation and discovery rankings share equal-width tabs with centered text, symmetric padding and a 40 dp minimum height. The old navigation row reserved an empty leading icon slot, which offset labels.
- Tabs retain theme text/background colors, rounded corners and the accent selection marker. Selection is also exposed through tab accessibility semantics.
- Sidebar items and menu entries remain left-aligned; playlist action icons, search behavior, sorting and playback are unchanged.

## Verification

- Offscreen Compose checks cover 420, 580 and 740 dp widths at 100% and 150% density, comparing text and button centers and actual text-layout line alignment.
- Both navigation rows retain equal widths, matching columns and working selection state across all destinations.
- Catalog fixtures check renamed headings; existing search, icon rendering, play-count sorting, playlist replacement and navigation restoration regressions remain in the build.
- Test renders are local and synthetic. No screenshots or image encodings are returned to conversation context, and installed Salt files and user accounts are not modified.
