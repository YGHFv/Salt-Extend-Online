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
    @FunctionalInterface public interface CookieLoginHandler {void login(AccountPlatform platform,String cookie) throws java.io.IOException;}
    private CookieLoginHandler cookieLogin=(p,c)->{throw new java.io.IOException("Cookie login unavailable");};
    public void cookieLogin(CookieLoginHandler handler) {cookieLogin=Objects.requireNonNull(handler);}
    public void loginCookie(AccountPlatform platform,String cookie) throws java.io.IOException {
        check();
        if(platform!=AccountPlatform.NETEASE && platform!=AccountPlatform.QQMUSIC)throw new IllegalArgumentException("Cookie login unavailable");
        cookieLogin.login(platform,cookie);
    }
    public static final String APPLE_PLAYBACK_INFO="登录有订阅的 Apple Music 账号后，双击歌曲即可在 Salt 内播放。";
    private Consumer<String> errorToast = text -> {};
    private final Map<String,String> reportedErrors = new java.util.HashMap<>();
    public void errorToast(Consumer<String> notifier) { errorToast=Objects.requireNonNull(notifier); }
    public void error(String text) { if(permitted.getAsBoolean() && text!=null && !text.isBlank()) errorToast.accept(text); }
    private synchronized void report(String source,String text) {
        String previous=reportedErrors.put(source,text);
        if(!text.equals(previous)) error(text);
    }
    private Consumer<LibraryController.Playlist> playlistPlayer = list -> { throw new IllegalStateException("Playlist playback not configured"); };
    public void playlistPlayer(Consumer<LibraryController.Playlist> player) { playlistPlayer=Objects.requireNonNull(player); }
    private SavedPlaylistController savedPlaylists;
    public void savedPlaylists(SavedPlaylistController controller) {savedPlaylists=controller;}
    public SavedPlaylistController.Snapshot savedPlaylists() {return savedPlaylists==null?new SavedPlaylistController.Snapshot(java.util.List.of(),false,""):savedPlaylists.snapshot();}
    public void loadSavedPlaylists() {check();if(savedPlaylists!=null) savedPlaylists.load();}
    public void addToPlaylist(String token,String playlist) {
        check(); if(savedPlaylists==null) throw new IllegalStateException("Native playlists unavailable");
        library.saveRow(token,(target,row)->{
            savedPlaylists.add(target,row,playlist,()->permitted.getAsBoolean() && library.currentRow(token));
        });
    }
    private PlatformPlaylistController platformPlaylists;
    public void platformPlaylists(PlatformPlaylistController controller){platformPlaylists=Objects.requireNonNull(controller);}
    public void loadPlatformPlaylists(String token) {
        check();if(platformPlaylists!=null)platformPlaylists.load(token,library.selection(token));
    }
    public PlatformPlaylistController.Snapshot platformPlaylists(String token) {
        if(platformPlaylists==null || !currentRow(token))return PlatformPlaylistController.Snapshot.empty();
        return platformPlaylists.snapshot(token,library.selection(token));
    }
    public void favoriteRow(String token,String playlist) {
        check();if(platformPlaylists==null)throw new IllegalStateException("Platform playlists unavailable");
        platformPlaylists.add(token,library.selection(token),playlist);
    }
    public void subscribePlaylist(String token) {
        check();if(platformPlaylists==null)throw new IllegalStateException("Platform playlists unavailable");
        platformPlaylists.subscribe(token,library.selection(token));
    }
    public void playPlaylist(String token) {check();library.requestPlaylist(token);}
    private Supplier<CoverMode> coverMode=()->CoverMode.FIT;
    public void coverMode(Supplier<CoverMode> value) { coverMode=Objects.requireNonNull(value); }
    public CoverMode coverMode() { return coverMode.get(); }
    private CatalogPreferences preferences=new CatalogPreferences();
    public void preferences(CatalogPreferences value) { preferences=Objects.requireNonNull(value); }
    public String layout(AccountPlatform platform) { return preferences.layout(platform); }
    public void layout(AccountPlatform platform,String value) { check(); preferences.layout(platform,value); }
    public NativeDesk(LibraryController library, OnlinePlaybackController player,
                      Function<AccountPlatform, AccountLoginController.Snapshot> accounts,
                      BiConsumer<AccountPlatform, Action> actions, BooleanSupplier permitted) {
        this.library = Objects.requireNonNull(library); this.player = Objects.requireNonNull(player);
        this.accounts = accounts; this.actions = actions; this.permitted = permitted;
    }
    private void check() { if (!permitted.getAsBoolean()) throw new SecurityException("Native desk unavailable"); }
    public State snapshot(AccountPlatform platform) {
        check(); var catalog=library.snapshot(platform); var pending=library.takePlaylist();
        if(pending!=null) try {playlistPlayer.accept(pending);}
        catch(RuntimeException failure) {error("\u64ad\u653e\u5168\u90e8\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u6388\u6743\u540e\u91cd\u8bd5");}
        var playback=player.snapshot();
        report("catalog:"+platform,(String)catalog.getOrDefault("error",""));
        report("playback",Boolean.TRUE.equals(playback.get("active"))?(String)playback.getOrDefault("error",""):"");
        var saved=savedPlaylists();
        report("saved",saved.loading() || saved.status().equals("\u5df2\u6dfb\u52a0\u5230\u6b4c\u5355")?"":saved.status());
        return new State(catalog, playback, accounts.apply(platform));
    }
    public void browse(AccountPlatform platform, Catalog.View view) { check(); library.browse(platform, view); }
    private Function<AccountPlatform,Catalog.View> platformHome=p->Catalog.View.DISCOVER;
    public void platformHome(Function<AccountPlatform,Catalog.View> home) {platformHome=Objects.requireNonNull(home);}
    public Catalog.View platformHome(AccountPlatform platform) {return platformHome.apply(platform);}
    public boolean enter(AccountPlatform platform) { check(); return library.enter(platform,platformHome(platform)); }
    public void search(String keyword) { check(); library.search(keyword); }
    public void search(AccountPlatform platform, String keyword) { check(); library.search(platform, keyword); }
    public void reload() { check(); library.reload(); }
    public void filter(String keyword) { check(); library.filter(keyword); }
    public void sort(Catalog.Sort order) { check(); library.sort(order); }
    public void playPlaylist() { check(); library.requestPlaylist(); }
    public AccountLoginController.Snapshot account(AccountPlatform platform) { check(); return accounts.apply(platform); }
    public void page(int direction) { check(); library.turn(direction); }
    public void loadMore(AccountPlatform platform,String token) {check();library.loadMore(platform,token);}
    public void choose(String token) { check(); library.choose(token); }
    public void author(String token) { check(); library.author(token); }
    public void authorTab(boolean collections) { check(); library.authorTab(collections); }
    public void back() { check(); library.back(); }
    public boolean back(AccountPlatform platform) { check(); return library.back(platform); }
    public void playRow(String token) {
        check();
        library.playRow(token, target -> player.playSelected(target, () -> {
            check(); actions.accept(target.platform(), Action.ARM);
        }));
    }
    public void insertRow(String token, OnlinePlaybackController.QueueAction action) {
        check(); library.playRow(token, target -> {
            player.insertSelected(target, () -> {
            check(); actions.accept(target.platform(), Action.ARM);
        }, action);});
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
