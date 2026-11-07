package io.github.yghfv.saltneri.plugin.ui;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.library.*;
import java.util.Map;
import java.util.Objects;
import java.util.function.*;

/** Native presentation boundary. No URLs, cookies or credentials in catalog/playback state. */
public final class NativeDesk {
    public enum Action { LOGIN, WEB_LOGIN, RESTORE, LOGOUT, SWITCH_ACCOUNT, CANCEL_LOGIN, ARM, STOP }
    public record State(Map<String, Object> catalog, Map<String, Object> playback,
                        AccountLoginController.Snapshot login) {
        @Override public String toString() { return "NativeDeskState[private QR redacted]"; }
    }
    private final LibraryController library;
    private final OnlinePlaybackController player;
    private final Function<AccountPlatform, AccountLoginController.Snapshot> accounts;
    private final BiConsumer<AccountPlatform, Action> actions;
    private final BooleanSupplier permitted;
    private Consumer<LibraryController.Playlist> playlistPlayer = list -> { throw new IllegalStateException("Playlist playback not configured"); };
    public void playlistPlayer(Consumer<LibraryController.Playlist> player) { playlistPlayer=Objects.requireNonNull(player); }
    private Supplier<CoverMode> coverMode=()->CoverMode.FIT;
    public void coverMode(Supplier<CoverMode> value) { coverMode=Objects.requireNonNull(value); }
    public CoverMode coverMode() { return coverMode.get(); }
    public NativeDesk(LibraryController library, OnlinePlaybackController player,
                      Function<AccountPlatform, AccountLoginController.Snapshot> accounts,
                      BiConsumer<AccountPlatform, Action> actions, BooleanSupplier permitted) {
        this.library = Objects.requireNonNull(library); this.player = Objects.requireNonNull(player);
        this.accounts = accounts; this.actions = actions; this.permitted = permitted;
    }
    private void check() { if (!permitted.getAsBoolean()) throw new SecurityException("Native desk unavailable"); }
    public State snapshot(AccountPlatform platform) {
        check(); var catalog=library.snapshot(platform); var pending=library.takePlaylist(); if(pending!=null) playlistPlayer.accept(pending);
        return new State(catalog, player.snapshot(), accounts.apply(platform));
    }
    public void browse(AccountPlatform platform, Catalog.View view) { check(); library.browse(platform, view); }
    public boolean enter(AccountPlatform platform) { check(); return library.enter(platform); }
    public void search(String keyword) { check(); library.search(keyword); }
    public void search(AccountPlatform platform, String keyword) { check(); library.search(platform, keyword); }
    public void reload() { check(); library.reload(); }
    public void filter(String keyword) { check(); library.filter(keyword); }
    public void sort(Catalog.Sort order) { check(); library.sort(order); }
    public void playPlaylist() { check(); library.requestPlaylist(); }
    public AccountLoginController.Snapshot account(AccountPlatform platform) { check(); return accounts.apply(platform); }
    public void page(int direction) { check(); library.turn(direction); }
    public void choose(String token) { check(); library.choose(token); }
    public void author(String token) { check(); library.author(token); }
    public void authorTab(boolean collections) { check(); library.authorTab(collections); }
    public void back() { check(); library.back(); }
    public void playRow(String token) {
        check(); library.playRow(token, target -> player.playSelected(target, () -> {
            check(); actions.accept(target.platform(), Action.ARM);
        }));
    }
    public void play(String token) throws Exception { check(); player.play(token); }
    /** This button explicitly authorizes both adapter setup and the current one-use submission. */
    public void confirmPlayback(String token) throws Exception {
        check(); AccountPlatform platform = player.validateConfirmation(token);
        if (!Boolean.TRUE.equals(player.snapshot().get("armed"))) {
            try { actions.accept(platform, Action.ARM); }
            catch (RuntimeException error) { player.adapterFailed(); throw error; }
        }
        check(); player.play(token);
    }
    public java.net.URI cover(String token) { check(); return library.cover(token); }
    public java.net.URI resolveCover(String token, io.github.yghfv.saltneri.plugin.net.RequestScope request) throws java.io.IOException {
        check(); return library.resolveCover(token, request);
    }
    public java.net.URI fallbackCover(String token, io.github.yghfv.saltneri.plugin.net.RequestScope request) throws java.io.IOException {
        check(); return library.resolveCover(token, request, true);
    }
    public boolean currentRow(String token) { return permitted.getAsBoolean() && library.currentRow(token); }
    public void leave() { library.cancelPendingPlayback(); player.invalidate("Page left; unsubmitted playback cancelled, catalog retained"); }
    public void action(AccountPlatform platform, Action action) { check(); actions.accept(platform, action); }
}
