package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.PlatformResolutionController;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

/** In-memory platform-local catalogs; account changes revoke every cached row capability. */
public final class LibraryController implements AutoCloseable {
    @FunctionalInterface public interface CoverLookup {
        java.net.URI resolve(AccountCredentials account, Query query, io.github.yghfv.saltneri.plugin.net.RequestScope request) throws java.io.IOException;
    }
    public record Playlist(AccountPlatform platform, List<Item> items, AccountCredentials account) {
        public Playlist { items = List.copyOf(items); }
        @Override public String toString() { return "Playlist[private catalog]"; }
    }
    private record Contents(String title, List<Item> items) { }
    private final Function<AccountPlatform, AccountCredentials> accounts;
    private final CoverLookup coverLookup;
    private final Consumer<OnlinePlaybackController.Target> prepare;
    private final Runnable navigation;
    private final Map<AccountPlatform, State> states = new EnumMap<>(AccountPlatform.class);
    private AccountPlatform platform = AccountPlatform.NETEASE;
    private static final class State {
        final AccountPlatform platform;
        PlatformResolutionController<Query, String, Page> loader;
        PlatformResolutionController<Query, String, Contents> complete;
        Query query;
        Page visible;
        Contents contents;
        AccountCredentials account;
        int knownPages;
        long totalCount = -1;
        String creator = "", filter = "", error = "", title = "";
        Sort sort = Sort.DEFAULT;
        boolean fullMode, playPending;
        final Map<String, Item> rows = new LinkedHashMap<>();
        State(AccountPlatform platform) { this.platform = platform; }
    }
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
        this.accounts = accounts; this.coverLookup = coverLookup; this.prepare = prepare; this.navigation = navigation;
        Function<AccountCredentials, PlatformResolutionController.Resolver<Query, String, Page>> factory = account ->
                testLoader == null ? (q,k,scope,r) -> new CatalogClient(account).load(q,r) : testLoader.apply(account);
        for (AccountPlatform p : AccountPlatform.values()) {
            State s = new State(p); states.put(p,s);
            // Catalog metadata has no signed audio URL; retain it until explicit refresh/account revocation.
            s.loader = new PlatformResolutionController<>("Catalog", factory, () -> accounts.apply(p), authorized, clock,
                    page -> Instant.MAX, page -> "", CatalogFailure::diagnostic);
            s.complete = new PlatformResolutionController<>("Playlist", account -> (q,k,scope,r) -> {
                var resolver = factory.apply(account); List<Item> all = new ArrayList<>(); Set<String> ids = new HashSet<>(); String title = "";
                for (int page = 1; page <= 500; page++) {
                    r.check(); Page result = resolver.resolve(new Query(q.platform(),q.view(),q.folder(),page,q.owner()),k,scope,r);
                    if(result.totalCount()>10000 || page==500 && result.totalCount()<0 && result.items().size()==20) throw new java.io.IOException("Playlist exceeds bounded catalog");
                    title = result.title();
                    for (Item item : result.items()) if (item.enabled() && item.kind() == Kind.TRACK && ids.add(item.target())) all.add(item);
                    if (!result.hasMore()) return new Contents(title,List.copyOf(all));
                }
                throw new java.io.IOException("Playlist exceeds 10000 entries; no partial queue submitted");
            }, () -> accounts.apply(p), authorized, clock, result -> Instant.MAX, result -> "", CatalogFailure::diagnostic, 180000);
        }
    }
    private State current() { return states.get(platform); }
    private State stateFor(String token) { for (State s : states.values()) if (s.rows.containsKey(token)) return s; return null; }
    public synchronized void browse(AccountPlatform platform, View view) {
        if (view == View.FOLDER || view == View.COLLECTION || view == View.SEARCH) throw new IllegalArgumentException("Use matching catalog action");
        this.platform = platform; reset(current()); load(current(),new Query(platform,view,0,1));
    }
    public synchronized void search(String keyword) {
        Query q = new Query(AccountPlatform.BILIBILI,View.SEARCH,0,1,0,keyword);
        platform = q.platform(); reset(current()); load(current(),q);
    }
    private void reset(State s) {
        s.complete.invalidate(""); s.contents = null; s.fullMode = false; s.playPending = false;
        s.knownPages = 0; s.totalCount = -1; s.creator = ""; s.filter = ""; s.sort = Sort.DEFAULT; s.error = ""; s.title="";
    }
    private void load(State s, Query next) {
        navigation.run(); s.rows.clear(); s.visible = null; s.query = next; s.account=accounts.apply(s.platform); s.loader.start(next,"read");
    }
    private static Query at(Query q, int page) { return new Query(q.platform(),q.view(),q.folder(),page,q.owner(),q.keyword()); }
    public synchronized void turn(int direction) {
        State s = current(); refresh(s);
        if (s.visible == null || direction != -1 && direction != 1) throw new IllegalStateException("No current page");
        if (direction == 1 && !s.visible.hasMore() || direction == -1 && s.query.page() == 1) throw new IllegalArgumentException("No more pages");
        Query next = at(s.query,s.query.page()+direction);
        if (s.fullMode) { s.query = next; rebuild(s); } else load(s,next);
    }
    public synchronized void choose(String token) {
        State s = stateFor(token); if (s == null) throw new IllegalStateException("Stale row"); refresh(s);
        Item item = s.rows.get(token); if (item == null || !item.enabled()) throw new IllegalStateException("Stale row"); platform = s.platform;
        if (item.kind() != Kind.TRACK) {
            reset(s); s.totalCount = item.count(); s.creator = item.subtitle();
            load(s,new Query(platform,item.kind() == Kind.COLLECTION ? View.COLLECTION : View.FOLDER,Long.parseLong(item.target()),1,item.owner()));
        } else prepare.accept(new OnlinePlaybackController.Target(platform,item.target()));
    }
    private PlatformResolutionController.Snapshot<?> refresh(State s) {
        var raw = s.loader.snapshot();
        if (s.fullMode) {
            var all = s.complete.snapshot();
            if (all.resolved() != s.contents) { s.contents = all.resolved(); s.rows.clear(); s.visible = null; if (s.contents != null) rebuild(s); }
            if (all.failed()) s.playPending = false;
            return all;
        }
        Page page = raw.resolved();
        if (page != s.visible) {
            s.visible = page; s.rows.clear();
            if (page != null) {
                s.title=page.title();
                if (page.totalPages() > 0) s.knownPages = page.totalPages();
                else if (!page.hasMore()) s.knownPages = s.query.page();
                if (page.totalCount() >= 0) s.totalCount = page.totalCount();
                publish(s,page.items());
            }
        }
        return raw;
    }
    private void publish(State s, List<Item> items) {
        s.rows.clear(); for (Item item : items) if (item.enabled()) s.rows.put(UUID.randomUUID().toString(),item);
    }
    private List<Item> ordered(State s, boolean filter) {
        List<Item> items = new ArrayList<>(s.contents.items());
        if (filter && !s.filter.isBlank()) { String needle=s.filter.toLowerCase(Locale.ROOT); items.removeIf(i -> !(i.title()+" "+i.subtitle()).toLowerCase(Locale.ROOT).contains(needle)); }
        switch (s.sort) {
            case DEFAULT -> { }
            case REVERSE -> Collections.reverse(items);
            case TITLE -> items.sort(Comparator.comparing(Item::title,String.CASE_INSENSITIVE_ORDER));
            case ARTIST -> items.sort(Comparator.comparing(Item::subtitle,String.CASE_INSENSITIVE_ORDER));
            case DURATION -> items.sort(Comparator.comparingLong(Item::count));
        }
        return items;
    }
    private void rebuild(State s) {
        List<Item> items=ordered(s,true); s.knownPages=Math.max(1,(items.size()+19)/20); int page=Math.min(s.query.page(),s.knownPages);
        s.query=at(s.query,page); int from=(page-1)*20;
        s.visible=new Page(s.contents.title(),items.subList(from,Math.min(from+20,items.size())),page<s.knownPages,Instant.MAX,s.knownPages,s.contents.items().size());
        s.title=s.contents.title(); if(s.totalCount<0) s.totalCount=s.contents.items().size(); publish(s,s.visible.items());
    }
    private void requireFolder(State s) {
        refresh(s); if (s.query == null || s.query.view()!=View.FOLDER && s.query.view()!=View.COLLECTION) throw new IllegalStateException("Open a playlist first");
    }
    private void complete(State s) {
        if (!s.fullMode) { s.fullMode=true; s.rows.clear(); s.visible=null; s.complete.start(at(s.query,1),"all"); }
        else if (s.complete.snapshot().failed()) s.complete.start(at(s.query,1),"all");
    }
    public synchronized void filter(String keyword) {
        State s=current(); requireFolder(s); String value=keyword.strip();
        if (value.length()>100 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid filter");
        s.filter=value; s.query=at(s.query,1); complete(s); if(s.contents!=null) rebuild(s);
    }
    public synchronized void sort(Sort order) { State s=current(); requireFolder(s); s.sort=Objects.requireNonNull(order); s.query=at(s.query,1); complete(s); if(s.contents!=null) rebuild(s); }
    public synchronized void requestPlaylist() { State s=current(); requireFolder(s); s.playPending=true; s.error=""; complete(s); }
    public synchronized void cancelPendingPlayback() { for(State s:states.values()) s.playPending=false; }
    public synchronized Playlist takePlaylist() {
        State s=current(); refresh(s); if(!s.playPending || s.contents==null) return null; s.playPending=false;
        if(accounts.apply(s.platform)!=s.account || s.account!=null && !s.account.isActive()) { invalidate(s); return null; }
        // Play means the whole collection in the selected order, not only the current page/filter.
        List<Item> items=ordered(s,false);
        if(items.isEmpty()) { s.error="\u5408\u96c6\u4e2d\u6ca1\u6709\u53ef\u64ad\u653e\u5185\u5bb9"; return null; }
        return new Playlist(s.platform,items,s.account);
    }
    public synchronized Map<String,Object> snapshot(AccountPlatform p) { platform=p; return snapshot(); }
    public synchronized Map<String,Object> snapshot() {
        State s=current(); var status=refresh(s); List<Object> items=new ArrayList<>();
        s.rows.forEach((token,item)->items.add(Map.of("token",token,"kind",item.kind().name(),"title",item.title(),"subtitle",item.subtitle().isEmpty() && item.kind()==Kind.TRACK ? s.creator : item.subtitle(),"count",item.count(),"enabled",item.enabled())));
        Map<String,Object> result=new HashMap<>(); result.put("status",status.status()); result.put("title",s.title);
        result.put("platform",platform.name()); result.put("view",s.query==null ? "" : s.query.view().name()); result.put("page",s.query==null ? 1 : s.query.page());
        result.put("more",s.visible!=null && s.visible.hasMore()); result.put("items",items); result.put("error",!s.error.isEmpty()?s.error:status.failed()?status.status():"");
        result.put("loaded",s.visible!=null); result.put("totalPages",s.knownPages); result.put("totalCount",s.totalCount); result.put("filter",s.filter); result.put("sort",s.sort.name());
        result.put("loadingAll",s.fullMode && s.contents==null && !status.failed()); return Map.copyOf(result);
    }
    public synchronized java.net.URI cover(String token) {
        State s = stateFor(token); if (s == null) return null; refresh(s); Item item = s.rows.get(token); return item == null ? null : item.cover();
    }
    public java.net.URI resolveCover(String token, io.github.yghfv.saltneri.plugin.net.RequestScope request) throws java.io.IOException {
        return resolveCover(token, request, false);
    }
    public java.net.URI resolveCover(String token, io.github.yghfv.saltneri.plugin.net.RequestScope request, boolean fallback) throws java.io.IOException {
        Item item; AccountCredentials account; Query collection;
        synchronized (this) {
            State s = stateFor(token); if (s == null) return null; refresh(s); item = s.rows.get(token);
            if (item == null) return null;
            if (item.cover() != null && !fallback) return item.cover();
            if (fallback && item.cover() == null) return null;
            if (item.kind() != Kind.COLLECTION || item.owner() <= 0) return null;
            account = accounts.apply(s.platform);
            collection = new Query(s.platform, View.COLLECTION, Long.parseLong(item.target()), 1, item.owner());
        }
        // Network work must not hold the UI/catalog monitor. The row token also fences account changes.
        request.check();
        java.net.URI cover = coverLookup.resolve(account, collection, request);
        request.check();
        synchronized (this) { State s = states.get(collection.platform()); refresh(s); return s.rows.get(token) == item && accounts.apply(collection.platform()) == account ? cover : null; }
    }
    public synchronized void playRow(String token, Consumer<OnlinePlaybackController.Target> play) {
        State s = stateFor(token); if (s == null) throw new IllegalStateException("Stale row"); refresh(s); Item item = s.rows.get(token);
        if (item == null || !item.enabled() || item.kind() != Kind.TRACK) throw new IllegalStateException("Stale or unsupported track");
        play.accept(new OnlinePlaybackController.Target(s.platform, item.target()));
    }
    public synchronized boolean currentRow(String token) { State s = stateFor(token); if (s == null) return false; refresh(s); return s.rows.containsKey(token); }
    private void invalidate(State s) { reset(s); s.rows.clear(); s.visible=null; s.query=null; s.loader.invalidate(""); }
    public synchronized void accountChanged(AccountPlatform changed) { invalidate(states.get(changed)); if(platform==changed) navigation.run(); }
    public synchronized void invalidate() { states.values().forEach(this::invalidate); navigation.run(); }
    @Override public synchronized void close() { invalidate(); for(State s:states.values()) { s.loader.close(); s.complete.close(); } }
}
