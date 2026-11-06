package io.github.yghfv.saltneri.plugin.ui;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.library.*;
import java.util.Map;
import java.util.Objects;
import java.util.function.*;

/** Native presentation boundary. No URLs, cookies or credentials in catalog/playback state. */
public final class NativeDesk {
    public enum Action { LOGIN, RESTORE, LOGOUT, CANCEL_LOGIN, ARM, STOP }
    public record State(Map<String, Object> catalog, Map<String, Object> playback,
                        AccountLoginController.Snapshot login) {
        @Override public String toString() { return "NativeDeskState[private QR redacted]"; }
    }
    private final LibraryController library;
    private final OnlinePlaybackController player;
    private final Function<AccountPlatform, AccountLoginController.Snapshot> accounts;
    private final BiConsumer<AccountPlatform, Action> actions;
    private final BooleanSupplier permitted;
    public NativeDesk(LibraryController library, OnlinePlaybackController player,
                      Function<AccountPlatform, AccountLoginController.Snapshot> accounts,
                      BiConsumer<AccountPlatform, Action> actions, BooleanSupplier permitted) {
        this.library = Objects.requireNonNull(library); this.player = Objects.requireNonNull(player);
        this.accounts = accounts; this.actions = actions; this.permitted = permitted;
    }
    private void check() { if (!permitted.getAsBoolean()) throw new SecurityException("Native desk unavailable"); }
    public State snapshot(AccountPlatform platform) {
        check(); return new State(library.snapshot(), player.snapshot(), accounts.apply(platform));
    }
    public void browse(AccountPlatform platform, Catalog.View view) { check(); library.browse(platform, view); }
    public void page(int direction) { check(); library.turn(direction); }
    public void choose(String token) { check(); library.choose(token); }
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
    public boolean currentRow(String token) { return permitted.getAsBoolean() && library.currentRow(token); }
    public void leave() { library.invalidate(); }
    public void action(AccountPlatform platform, Action action) { check(); actions.accept(platform, action); }
}
