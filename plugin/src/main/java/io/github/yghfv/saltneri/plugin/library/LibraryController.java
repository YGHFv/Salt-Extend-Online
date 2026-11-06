package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.PlatformResolutionController;
import java.time.Clock;
import java.util.*;
import java.util.function.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

/** One visible page, opaque revision-bound row actions, no private disk cache or all-pages crawling. */
public final class LibraryController implements AutoCloseable {
    @FunctionalInterface public interface CoverLookup {
        java.net.URI resolve(AccountCredentials account, Query query, io.github.yghfv.saltneri.plugin.net.RequestScope request) throws java.io.IOException;
    }
    private final Function<AccountPlatform, AccountCredentials> accounts;
    private final CoverLookup coverLookup;
    private final PlatformResolutionController<Query, String, Page> loader;
    private final Consumer<OnlinePlaybackController.Target> prepare;
    private final Runnable navigation;
    private volatile AccountPlatform platform = AccountPlatform.NETEASE;
    private Query query;
    private Page visible;
    private final Map<String, Item> rows = new LinkedHashMap<>();
    public LibraryController(Function<AccountPlatform, AccountCredentials> accounts, BooleanSupplier authorized,
                             Consumer<OnlinePlaybackController.Target> prepare, Runnable navigation) {
        this(accounts, authorized, prepare, navigation, Clock.systemUTC(), null);
    }
    public LibraryController(Function<AccountPlatform, AccountCredentials> accounts, BooleanSupplier authorized, Consumer<OnlinePlaybackController.Target> prepare,
                             Runnable navigation, Clock clock, Function<AccountCredentials, PlatformResolutionController.Resolver<Query, String, Page>> testLoader) {
        this(accounts, authorized, prepare, navigation, clock, testLoader, (account, query, request) -> new CatalogClient(account).collectionCover(query, request));
    }
    public LibraryController(Function<AccountPlatform, AccountCredentials> accounts, BooleanSupplier authorized, Consumer<OnlinePlaybackController.Target> prepare,
                             Runnable navigation, Clock clock, Function<AccountCredentials, PlatformResolutionController.Resolver<Query, String, Page>> testLoader, CoverLookup coverLookup) {
        this.accounts = accounts; this.coverLookup = coverLookup;
        this.prepare = prepare; this.navigation = navigation;
        loader = new PlatformResolutionController<>("Catalog", account -> testLoader == null ? (q,k,s,r) -> new CatalogClient(account).load(q,r) : testLoader.apply(account),
                () -> accounts.apply(platform), authorized, clock, Page::expiresAt,
                page -> "\u5217\u8868\u5df2\u8bfb\u53d6", CatalogFailure::diagnostic);
    }
    public synchronized void browse(AccountPlatform platform, View view) {
        if (view == View.FOLDER || view == View.COLLECTION) throw new IllegalArgumentException("Open a visible folder instead");
        load(new Query(platform, view, 0, 1));
    }
    private void load(Query next) {
        navigation.run(); rows.clear(); visible = null; query = next; platform = next.platform(); loader.start(next, "read");
    }
    public synchronized void turn(int direction) {
        refresh(); if (visible == null || query == null || direction != -1 && direction != 1) throw new IllegalStateException("No current page");
        if (direction == 1 && !visible.hasMore() || direction == -1 && query.page() == 1) throw new IllegalArgumentException("No more pages");
        load(new Query(platform, query.view(), query.folder(), query.page() + direction, query.owner()));
    }
    public synchronized void choose(String token) {
        refresh(); Item item = rows.get(token); if (item == null || !item.enabled()) throw new IllegalStateException("Stale or unsupported row");
        if (item.kind() == Kind.FOLDER) load(new Query(platform, View.FOLDER, Long.parseLong(item.target()), 1));
        else if (item.kind() == Kind.COLLECTION) load(new Query(platform, View.COLLECTION, Long.parseLong(item.target()), 1, item.owner()));
        else prepare.accept(new OnlinePlaybackController.Target(platform, item.target()));
    }
    private PlatformResolutionController.Snapshot<Page> refresh() {
        var state = loader.snapshot(); Page page = state.resolved();
        if (page != visible) { visible = page; rows.clear(); if (page != null) for (Item item : page.items()) if (item.enabled()) rows.put(UUID.randomUUID().toString(), item); }
        return state;
    }
    public synchronized Map<String, Object> snapshot() {
        var state = refresh(); List<Object> items = new ArrayList<>();
        rows.forEach((token,item) -> items.add(Map.of("token", token, "kind", item.kind().name(), "title", item.title(), "subtitle", item.subtitle(), "count", item.count(), "enabled", item.enabled())));
        return Map.of("status", state.status(), "title", visible == null ? "" : visible.title(), "platform", platform.name(), "view", query == null ? "" : query.view().name(),
                "page", query == null ? 1 : query.page(), "more", visible != null && visible.hasMore(), "items", items,
                "error", state.failed() ? state.status() : "", "loaded", visible != null);
    }
    public synchronized java.net.URI cover(String token) {
        refresh(); Item item = rows.get(token); return item == null ? null : item.cover();
    }
    public java.net.URI resolveCover(String token, io.github.yghfv.saltneri.plugin.net.RequestScope request) throws java.io.IOException {
        return resolveCover(token, request, false);
    }
    public java.net.URI resolveCover(String token, io.github.yghfv.saltneri.plugin.net.RequestScope request, boolean fallback) throws java.io.IOException {
        Item item; AccountCredentials account; Query collection;
        synchronized (this) {
            refresh(); item = rows.get(token);
            if (item == null) return null;
            if (item.cover() != null && !fallback) return item.cover();
            if (fallback && item.cover() == null) return null;
            if (item.kind() != Kind.COLLECTION || item.owner() <= 0) return null;
            account = accounts.apply(platform);
            collection = new Query(platform, View.COLLECTION, Long.parseLong(item.target()), 1, item.owner());
        }
        // Network work must not hold the UI/catalog monitor. The row token also fences account changes.
        request.check();
        java.net.URI cover = coverLookup.resolve(account, collection, request);
        request.check();
        synchronized (this) { refresh(); return rows.get(token) == item && accounts.apply(collection.platform()) == account ? cover : null; }
    }
    public synchronized void playRow(String token, Consumer<OnlinePlaybackController.Target> play) {
        refresh(); Item item = rows.get(token);
        if (item == null || !item.enabled() || item.kind() != Kind.TRACK) throw new IllegalStateException("Stale or unsupported track");
        play.accept(new OnlinePlaybackController.Target(platform, item.target()));
    }
    public synchronized boolean currentRow(String token) { refresh(); return rows.containsKey(token); }
    public synchronized void accountChanged(AccountPlatform changed) { if (platform == changed) invalidate(); }
    public synchronized void invalidate() { rows.clear(); visible = null; query = null; loader.invalidate("\u8bf7\u70b9\u51fb\u680f\u76ee\u8bfb\u53d6\u5217\u8868"); navigation.run(); }
    @Override public synchronized void close() { invalidate(); loader.close(); }
}
