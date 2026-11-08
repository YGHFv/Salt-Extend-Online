# 0.8.35 Navigation Settings

- Settings stays visible even if an older config hides every sidebar entry.
- Sidebar Management is one entry in the mod config, opening a secondary page with Salt's native header, back button and switch widgets.
- The secondary page saves through the existing mod ConfigManager. It does not create a config namespace based on its internal route.
- The main config has one group and 16 rows. Open Online Music and Remember Login are removed; remembering login is standard behavior, with logout retained in account menus.
- Salt Home supports Restore Last, Songs, Bilibili, NetEase and one option for each local playlist. Playlist options are read on an IO worker with a three-second bound.
- Restore Last remembers supported page identities, not playback intent, credentials, remote collections or the native navigation stack. Each platform's initial column can be Discover, Favorites or Subscriptions; returning to an already opened platform preserves its current navigation.
- Startup navigation is deferred until composition has completed. Playlist lookup does not block the UI thread; an intervening user navigation cancels startup redirection. Reloading a mod from a non-home page does not redirect the user.
- Caption playlist shortcuts reuse the native playlist flyout and native delete confirmation. Confirmed deletion calls the existing sidebar view model's deletion bridge, not a guessed repository method. Removed playlists and revoked plugin permission invalidate callbacks.
- The 0.8.34 hidden-toggle caption alignment fix is retained, including DPI regression coverage.

Offline checks cover native resolver signatures, locked Settings, dynamic playlist options, config persistence/rollback, deferred startup, native back navigation, real secondary-pointer dispatch, edit routing and recording-only delete confirmation. No installed Salt config, real playlist, account or audio engine is touched.
