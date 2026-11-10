package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.PlatformResolutionController;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;
import io.github.yghfv.saltneri.plugin.library.CatalogPaging.Window;

/** In-memory platform-local catalogs; account changes revoke every cached row capability. */
public final class LibraryController implements AutoCloseable {
    @FunctionalInterface public interface CoverLookup {
        java.net.URI resolve(AccountCredentials account, Query query, io.github.yghfv.saltneri.plugin.net.RequestScope request) throws java.io.IOException;
    }
    @FunctionalInterface public interface ItemCoverLookup {
        java.net.URI resolve(AccountCredentials account, Item item, io.github.yghfv.saltneri.plugin.net.RequestScope request) throws java.io.IOException;
    }
    public record Playlist(AccountPlatform platform, List<Item> items, AccountCredentials account) {
        public Playlist { items = List.copyOf(items); }
        @Override public String toString() { return "Playlist[private catalog]"; }
    }
    private record Contents(String title, List<Item> items) { }
    private record Location(Query query,String creator,String authorName,Sort sort,String filter,Contents contents,Window visible,int chunks,int limit) { }
    private final Function<AccountPlatform, AccountCredentials> accounts;
    private final CoverLookup coverLookup;
    private final ItemCoverLookup neteaseCoverLookup;
    private final Consumer<OnlinePlaybackController.Target> prepare;
    private final Runnable navigation;
    private final BooleanSupplier authorized;
    private final Map<AccountPlatform, State> states = new EnumMap<>(AccountPlatform.class);
    private AccountPlatform platform = AccountPlatform.NETEASE;
    private volatile int pageLimit=CatalogPaging.DEFAULT;
    private static final class State {
        final AccountPlatform platform;
        PlatformResolutionController<Query, Integer, Window> loader;
        PlatformResolutionController<Query, String, Contents> complete;
        CatalogPageCache cache;
        Query query;
        Window visible, delivered;
        int chunks=1,visibleChunks=1;
        boolean appending;
        String loadToken=UUID.randomUUID().toString();
        Contents contents;
        AccountCredentials account;
        int knownPages;
        long totalCount = -1;
        String creator = "", filter = "", error = "", title = "";
        Sort sort = Sort.DEFAULT;
        boolean fullMode, playPending, preserveWindow;
        volatile int completedPages, requestedPages;
        int resultPage=1;
        String authorName="";
        final Deque<Location> history=new ArrayDeque<>();
        final Map<String, Item> rows = new LinkedHashMap<>();
        final LinkedHashMap<String,java.net.URI> resolvedCovers=new LinkedHashMap<>(32,0.75f,true);
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
        this(accounts,authorized,prepare,navigation,clock,testLoader,coverLookup,
                testLoader==null?(account,item,request)->new CatalogClient(account).neteaseCover(item,request):(account,item,request)->null);
    }
    public LibraryController(Function<AccountPlatform, AccountCredentials> accounts, BooleanSupplier authorized, Consumer<OnlinePlaybackController.Target> prepare,
                             Runnable navigation, Clock clock, Function<AccountCredentials, PlatformResolutionController.Resolver<Query, String, Page>> testLoader,
                             CoverLookup coverLookup, ItemCoverLookup neteaseCoverLookup) {
        this.accounts = accounts; this.coverLookup = coverLookup; this.neteaseCoverLookup=neteaseCoverLookup; this.prepare = prepare; this.navigation = navigation; this.authorized = authorized;
        for (AccountPlatform p : AccountPlatform.values()) {
            State s = new State(p); states.put(p,s);
            s.cache = new CatalogPageCache(clock);
            Function<AccountCredentials, PlatformResolutionController.Resolver<Query, String, Page>> factory = account ->
                    testLoader == null ? (q,k,scope,r) -> new CatalogClient(account,clock,CatalogHttp::openPinned,s.cache.discovery,s.cache.sessions,s.cache.qqRecommendations,s.cache.appleHttp,s.cache.netRecommendations).kugouCache(s.cache.kugouCache).load(q,r) : testLoader.apply(account);
            // Catalog metadata has no signed audio URL; retain it until explicit refresh/account revocation.
            s.loader = new PlatformResolutionController<>("Catalog", account -> {
                var resolver = factory.apply(account);
                return (q,width,scope,r) -> CatalogPaging.load(q,width,r,next->s.cache.load(account,next,"read",scope,r,resolver));
            }, () -> accounts.apply(p), authorized, clock,
                    page -> Instant.MAX, page -> "", CatalogFailure::diagnostic);
            s.complete = new PlatformResolutionController<>("Playlist", account -> (q,k,scope,r) -> {
                if(testLoader==null) {
                    var result=s.cache.all(account,q,r,()->new CatalogClient(account,clock,CatalogHttp::openPinned,s.cache.discovery,s.cache.sessions,s.cache.qqRecommendations,s.cache.appleHttp,s.cache.netRecommendations)
                            .loadAll(q,r,s.cache,(done,total)->{s.completedPages=done;s.requestedPages=total;}));
                    return new Contents(result.title(),result.items().stream().filter(i->i.enabled() && i.kind()==Kind.TRACK).toList());
                }
                var resolver = factory.apply(account); List<Item> all = new ArrayList<>(); Set<String> ids = new HashSet<>(); String title = "";
                for (int page = 1; page <= 500; page++) {
                    r.check(); Page result = resolver.resolve(new Query(q.platform(),q.view(),q.folder(),page,q.owner(),q.keyword(),false,Sort.DEFAULT,q.resource()),k,scope,r);
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
    public synchronized void pageLimit(int value) {
        if(!Set.of(0,20,30,50,60,100).contains(value))throw new IllegalArgumentException("Invalid catalog page limit");
        if(pageLimit==value)return;
        pageLimit=value;
        for(State s:states.values()) {
            s.chunks=1;s.appending=false;s.playPending=false;s.preserveWindow=false;s.loadToken=UUID.randomUUID().toString();
            if(s.query==null)continue;
            s.query=at(s.query,1);s.resultPage=1;
            if(s.contents!=null)rebuild(s);
            else if(!s.fullMode)load(s,s.query);
            else {s.visible=null;s.delivered=null;s.rows.clear();}
        }
    }
    public synchronized boolean enter(AccountPlatform destination) {
        return enter(destination,View.DISCOVER);
    }
    public synchronized boolean enter(AccountPlatform destination,View home) {
        if(home!=View.DISCOVER && home!=View.MINE && home!=View.SAVED)throw new IllegalArgumentException("Invalid platform home");
        if (!permitted()) throw new SecurityException("Catalog unavailable");
        State s = states.get(destination); refresh(s);
        if (s.query != null) return false;
        browse(destination,home); return true;
    }
    private State stateFor(String token) { for (State s : states.values()) if (s.rows.containsKey(token)) return s; return null; }
    public synchronized void browse(AccountPlatform platform, View view) {
        if (view == View.FOLDER || view == View.COLLECTION || view == View.SEARCH) throw new IllegalArgumentException("Use matching catalog action");
        this.platform = platform; current().history.clear(); current().authorName=""; reset(current()); load(current(),new Query(platform,view,0,1));
    }
    public synchronized void search(String keyword) {
        search(AccountPlatform.BILIBILI, keyword);
    }
    public synchronized void search(AccountPlatform destination, String keyword) {
        Query q = new Query(destination,View.SEARCH,0,1,0,keyword);
        platform = q.platform(); current().history.clear(); current().authorName=""; reset(current()); load(current(),q);
    }
    /** Explicit refresh preserves location/order but never resumes a previous play intent. */
    public synchronized void reload() {
        State s = current(); if (s.query == null) throw new IllegalStateException("No current catalog");
        s.cache.clear();
        s.resolvedCovers.clear();
        s.loader.invalidate(""); s.complete.invalidate(""); s.contents = null; s.visible = null; s.delivered=null; s.rows.clear();
        s.chunks=1;s.appending=false;s.loadToken=UUID.randomUUID().toString();
        s.playPending = false; s.preserveWindow = false; s.error = ""; s.knownPages = 0; s.totalCount = -1;
        if (s.fullMode) {
            navigation.run(); s.resultPage=s.query.page(); s.account = accounts.apply(s.platform); s.complete.start(allQuery(s.query),"all");
        } else load(s,s.query);
    }
    private void reset(State s) {
        s.complete.invalidate(""); s.contents = null; s.fullMode = false; s.playPending = false; s.preserveWindow = false;
        s.knownPages = 0; s.totalCount = -1; s.creator = ""; s.filter = ""; s.sort = Sort.DEFAULT; s.error = ""; s.title="";
        s.completedPages=0; s.requestedPages=0;
        s.chunks=1;s.visibleChunks=1;s.appending=false;s.delivered=null;s.loadToken=UUID.randomUUID().toString();
    }
    private void load(State s, Query next) {
        navigation.run(); s.rows.clear(); s.visible = null; s.delivered=null; s.appending=false; s.chunks=1;s.visibleChunks=1;
        s.loadToken=UUID.randomUUID().toString();s.error = ""; s.query = next; s.account=accounts.apply(s.platform); s.loader.start(next,CatalogPaging.width(pageLimit));
    }
    private static Query at(Query q, int page) { return new Query(q.platform(),q.view(),q.folder(),page,q.owner(),q.keyword(),q.reverse(),q.order(),q.resource()); }
    private static Query allQuery(Query q) {return new Query(q.platform(),q.view(),q.folder(),1,q.owner(),q.keyword(),false,Sort.DEFAULT,q.resource());}
    public synchronized void turn(int direction) {
        State s = current(); refresh(s);
        if(pageLimit==0)throw new IllegalStateException("Continuous catalog has no page buttons");
        if(s.fullMode && s.contents==null) return;
        if (s.visible == null || direction != -1 && direction != 1) throw new IllegalStateException("No current page");
        if (direction == 1 && !s.visible.hasMore() || direction == -1 && s.query.page() == 1) throw new IllegalArgumentException("No more pages");
        Query next = at(s.query,s.query.page()+direction);
        if (s.fullMode) { s.query = next; rebuild(s); } else load(s,next);
    }
    public synchronized void loadMore(AccountPlatform destination,String token) {
        State s=states.get(destination);refresh(s);
        if(pageLimit!=0 || !s.loadToken.equals(token) || s.visible==null || !s.visible.hasMore() || s.appending || s.fullMode && s.contents==null)return;
        if(s.fullMode) {s.chunks++;rebuild(s,true);}
        else {s.appending=true;s.error="";s.loader.start(at(s.query,s.chunks+1),CatalogPaging.CHUNK);}
    }
    public synchronized void choose(String token) {
        State s = stateFor(token); if (s == null) throw new IllegalStateException("Stale row"); refresh(s);
        Item item = s.rows.get(token); if (item == null || !item.enabled()) throw new IllegalStateException("Stale row"); platform = s.platform;
        if (item.kind() != Kind.TRACK) {
            remember(s); reset(s); s.totalCount = item.count(); s.creator = item.subtitle(); s.title=item.title();
            load(s,platform==AccountPlatform.APPLEMUSIC || platform==AccountPlatform.KUGOU?new Query(platform,View.FOLDER,1,1,0,"",false,Sort.DEFAULT,item.target()):new Query(platform,item.kind() == Kind.COLLECTION ? View.COLLECTION : View.FOLDER,Long.parseLong(item.target()),1,item.owner()));
        } else prepare.accept(new OnlinePlaybackController.Target(platform,item.target()));
    }
    private void remember(State s) {
        if(s.query==null) return;
        if(s.history.size()>=16) s.history.removeFirst();
        s.history.addLast(new Location(s.query,s.creator,s.authorName,s.contents==null?(s.query.reverse()?Sort.REVERSE:s.query.order()):s.sort,
                s.contents==null?"":s.filter,s.contents,s.visible,s.visibleChunks,pageLimit));
    }
    public synchronized void author(String token) {
        State s=stateFor(token); if(s==null) throw new IllegalStateException("Stale author"); refresh(s);
        Item row=s.rows.get(token);
        if(row==null || s.platform!=AccountPlatform.BILIBILI || row.owner()<=0) throw new IllegalStateException("Missing author identity");
        platform=s.platform; remember(s); reset(s); s.authorName=row.subtitle().isBlank()?"UP "+row.owner():row.subtitle();
        load(s,new Query(s.platform,View.AUTHOR_VIDEOS,0,1,row.owner()));
    }
    public synchronized void authorTab(boolean collections) {
        State s=current(); refresh(s);
        if(s.query==null || s.query.view()!=View.AUTHOR_VIDEOS && s.query.view()!=View.AUTHOR_COLLECTIONS) throw new IllegalStateException("No author page");
        long owner=s.query.owner(); reset(s); load(s,new Query(s.platform,collections?View.AUTHOR_COLLECTIONS:View.AUTHOR_VIDEOS,0,1,owner));
    }
    public synchronized void back() {
        back(platform);
    }
    public synchronized boolean back(AccountPlatform destination) {
        State s=states.get(destination); refresh(s); if(s.history.isEmpty()) return false;
        platform=destination;
        Location previous=s.history.removeLast(); navigation.run(); reset(s); s.creator=previous.creator(); s.authorName=previous.authorName();
        s.sort=previous.sort(); s.filter=previous.filter(); s.query=previous.query();
        if(previous.limit()==pageLimit) {s.chunks=previous.chunks();s.visibleChunks=s.chunks;}
        if(previous.contents()!=null) {s.contents=previous.contents();s.fullMode=true;rebuild(s);}
        else if(pageLimit==0 && previous.limit()==0 && previous.visible()!=null) {
            s.loader.invalidate("");s.account=accounts.apply(s.platform);s.visible=previous.visible();
            s.title=s.visible.title();s.totalCount=s.visible.totalCount();s.knownPages=s.visible.totalPages();publish(s,s.visible.items());
        }
        else load(s,s.query);
        return true;
    }
    private PlatformResolutionController.Snapshot<?> refresh(State s) {
        if (!permitted() || s.account != accounts.apply(s.platform) || s.account != null && !s.account.isActive()) {
            invalidate(s); s.account = accounts.apply(s.platform);
        }
        var raw = s.loader.snapshot();
        if (s.fullMode) {
            var all = s.complete.snapshot();
            if (all.resolved()!=null && all.resolved() != s.contents) {
                s.contents = all.resolved();
                // Playback preparation must not republish visible row capabilities or reset scroll.
                if (!s.preserveWindow) { s.query=at(s.query,s.resultPage); rebuild(s); }
                s.preserveWindow=false;
            }
            if (all.failed()) {
                s.playPending = false;
                // Bulk failure must not strand a preserved, otherwise usable paginated catalog.
                if(s.visible!=null) { s.fullMode=false;s.chunks=s.visibleChunks; s.sort=s.query.reverse()?Sort.REVERSE:Sort.DEFAULT; s.filter=""; s.error=all.status(); s.complete.invalidate(""); }
            }
            return all;
        }
        Window page = raw.resolved();
        if(s.appending && raw.failed())s.appending=false;
        if (page != null && page != s.delivered) {
            if (page != null && page.totalPages() > 0 && s.query.page() > page.totalPages()) {
                // A refreshed catalog can shrink; fetch its last page rather than publishing 2/1.
                load(s,at(s.query,page.totalPages())); return s.loader.snapshot();
            }
            boolean append=s.appending;s.delivered=page;s.appending=false;
            if(append) {
                var joined=new ArrayList<>(s.visible.items());joined.addAll(page.items());s.chunks++;
                s.visible=new Window(page.title(),joined,page.hasMore(),page.totalPages(),page.totalCount());
            } else s.visible=page;
            s.visibleChunks=s.chunks;
            s.loadToken=UUID.randomUUID().toString();
            if (page != null) {
                s.title=page.title();
                if (page.totalPages() > 0) s.knownPages = page.totalPages();
                else if (!page.hasMore()) s.knownPages = s.query.page();
                if (page.totalCount() >= 0) s.totalCount = page.totalCount();
                publish(s,s.visible.items(),append);
            }
        }
        return raw;
    }
    private boolean permitted() { try { return authorized.getAsBoolean(); } catch (RuntimeException error) { return false; } }
    private void publish(State s, List<Item> items) {
        publish(s,items,false);
    }
    private void publish(State s,List<Item> items,boolean retainPrefix) {
        var old=new ArrayList<>(s.rows.entrySet());s.rows.clear();int index=0;
        for(Item item:items)if(item.enabled()) {
            if(retainPrefix && index<old.size() && old.get(index).getValue().equals(item)) {
                var row=old.get(index);s.rows.put(row.getKey(),row.getValue());
            } else s.rows.put(UUID.randomUUID().toString(),item);
            index++;
        }
    }
    private List<Item> ordered(State s, boolean filter) {
        List<Item> items = new ArrayList<>(s.contents.items().stream().map(i->withAuthor(s,i)).toList());
        if (filter && !s.filter.isBlank()) { String needle=s.filter.toLowerCase(Locale.ROOT); items.removeIf(i -> !(i.title()+" "+i.subtitle()).toLowerCase(Locale.ROOT).contains(needle)); }
        switch (s.sort) {
            case DEFAULT -> { }
            case REVERSE -> Collections.reverse(items);
            case TITLE -> items.sort(Comparator.comparing(Item::title,String.CASE_INSENSITIVE_ORDER));
            case ARTIST -> items.sort(Comparator.comparing(Item::subtitle,String.CASE_INSENSITIVE_ORDER));
            case DURATION -> items.sort(Comparator.comparingLong(Item::count));
            // Missing counts (-1) follow real zeroes; stable sort preserves upstream ties.
            case PLAY_COUNT -> items.sort(Comparator.comparingLong(Item::playCount).reversed());
        }
        return items;
    }
    private Item withAuthor(State s,Item item) {
        if(s.platform!=AccountPlatform.BILIBILI || s.query==null || s.query.view()!=View.COLLECTION || !item.subtitle().isBlank() || s.creator.isBlank()) return item;
        return new Item(item.kind(),item.target(),item.title(),s.creator,item.count(),item.enabled(),item.cover(),item.owner(),item.playCount(),item.updatedAt());
    }
    private void rebuild(State s) {
        rebuild(s,false);
    }
    private void rebuild(State s,boolean append) {
        List<Item> items=ordered(s,true);int width=CatalogPaging.width(pageLimit);
        s.knownPages=Math.max(1,(items.size()+width-1)/width); int page=pageLimit==0?1:Math.min(s.query.page(),s.knownPages);
        s.query=at(s.query,page); int from=(page-1)*width,to=Math.min(pageLimit==0?s.chunks*width:from+width,items.size());
        s.visible=new Window(s.contents.title(),items.subList(from,to),to<items.size(),s.knownPages,s.contents.items().size());
        s.visibleChunks=s.chunks;
        s.loadToken=UUID.randomUUID().toString();s.title=s.contents.title(); if(s.totalCount<0) s.totalCount=s.contents.items().size(); publish(s,s.visible.items(),append);
    }
    private void requireFolder(State s) {
        refresh(s); if (s.query == null || s.query.view()!=View.FOLDER && s.query.view()!=View.COLLECTION) throw new IllegalStateException("Open a playlist first");
    }
    private void complete(State s) {
        s.error="";
        if(!s.fullMode && s.visible!=null && s.query.page()==1 && !s.visible.hasMore()) {
            var items=new ArrayList<>(s.visible.items().stream().filter(i->i.enabled() && i.kind()==Kind.TRACK).toList());
            if(s.query.reverse()) Collections.reverse(items);
            s.contents=new Contents(s.title,List.copyOf(items));
            s.fullMode=true; return;
        }
        if (!s.fullMode) { s.loader.invalidate("");s.appending=false;s.fullMode=true; s.completedPages=0; s.requestedPages=0; s.complete.start(allQuery(s.query),"all"); }
        else if (s.complete.snapshot().failed()) s.complete.start(allQuery(s.query),"all");
    }
    public synchronized void filter(String keyword) {
        State s=current(); requireFolder(s); String value=keyword.strip();
        if (value.length()>100 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid filter");
        s.filter=value;s.chunks=1;s.preserveWindow=false;s.resultPage=1; complete(s); if(s.contents!=null) {s.query=at(s.query,1);rebuild(s);}
    }
    public synchronized void sort(Sort order) {
        State s=current(); refresh(s); Objects.requireNonNull(order);
        if(s.query!=null && s.query.view()==View.AUTHOR_VIDEOS) {
            if(order!=Sort.DEFAULT && order!=Sort.REVERSE && order!=Sort.PLAY_COUNT) throw new IllegalArgumentException("Unsupported author order");
            if(s.sort==order && s.error.isEmpty() && !s.loader.snapshot().failed()) return;
            s.sort=order;
            load(s,new Query(s.platform,View.AUTHOR_VIDEOS,0,1,s.query.owner(),"",order==Sort.REVERSE,order==Sort.PLAY_COUNT?order:Sort.DEFAULT));
            return;
        }
        requireFolder(s);
        if(s.sort==order && s.error.isEmpty() && !s.complete.snapshot().failed()) return;
        s.sort=order;s.preserveWindow=false;s.resultPage=1;
        s.chunks=1;
        if(s.platform==AccountPlatform.BILIBILI && s.query.view()==View.COLLECTION && s.contents==null && s.filter.isEmpty()
                && (order==Sort.DEFAULT || order==Sort.REVERSE)) {
            s.complete.invalidate("");s.fullMode=false;s.playPending=false;
            load(s,new Query(s.platform,View.COLLECTION,s.query.folder(),1,s.query.owner(),"",order==Sort.REVERSE)); return;
        }
        complete(s); if(s.contents!=null) {s.query=at(s.query,1);rebuild(s);}
    }
    public synchronized void requestPlaylist() {
        State s=current(); requireFolder(s); s.playPending=true; s.error="";
        if(!s.fullMode) {s.preserveWindow=true;s.resultPage=s.query.page();}
        complete(s);
    }
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
        s.rows.forEach((token,item)->{
            Map<String,Object> row=new HashMap<>();
            row.put("token",token); row.put("kind",item.kind().name()); row.put("title",item.title());
            row.put("subtitle",item.subtitle().isEmpty() ? (s.query!=null && (s.query.view()==View.AUTHOR_VIDEOS || s.query.view()==View.AUTHOR_COLLECTIONS)?s.authorName:s.creator) : item.subtitle());
            row.put("count",item.count()); row.put("enabled",item.enabled()); row.put("author",s.platform==AccountPlatform.BILIBILI && item.owner()>0);
            row.put("collection",""); row.put("updated",Catalog.updated(item.updatedAt())); row.put("playCount",Catalog.plays(item.playCount()));
            row.put("duration",item.kind()==Kind.TRACK?Catalog.duration(item.count()):""); items.add(Map.copyOf(row));
        });
        Map<String,Object> result=new HashMap<>(); result.put("status",status.status()); result.put("title",s.title);
        result.put("platform",platform.name()); result.put("view",s.query==null ? "" : s.query.view().name()); result.put("page",s.query==null ? 1 : s.query.page());
        result.put("more",s.visible!=null && s.visible.hasMore()); result.put("items",items); result.put("error",!s.error.isEmpty()?s.error:status.failed()?status.status():"");
        result.put("loaded",s.visible!=null); result.put("totalPages",s.knownPages); result.put("totalCount",s.totalCount); result.put("filter",s.filter); result.put("sort",s.sort.name());
        result.put("loadingAll",s.fullMode && s.contents==null && !status.failed());
        result.put("loadProgress",s.completedPages+"/"+(s.requestedPages>0?s.requestedPages:"?"));
        result.put("pageLimit",pageLimit);result.put("infinite",pageLimit==0);result.put("loadingMore",s.appending);
        result.put("loadToken",s.loadToken);
        result.put("back",!s.history.isEmpty()); result.put("authorName",s.authorName); return Map.copyOf(result);
    }
    public synchronized java.net.URI cover(String token) {
        State s = stateFor(token); if (s == null) return null; refresh(s); Item item = s.rows.get(token);
        return item == null ? null : s.resolvedCovers.getOrDefault(coverKey(item),item.cover());
    }
    private static String coverKey(Item item) {return item.kind()+":"+item.target()+":"+item.owner();}
    public java.net.URI resolveCover(String token, io.github.yghfv.saltneri.plugin.net.RequestScope request) throws java.io.IOException {
        return resolveCover(token, request, false);
    }
    public java.net.URI resolveCover(String token, io.github.yghfv.saltneri.plugin.net.RequestScope request, boolean fallback) throws java.io.IOException {
        Item item; AccountCredentials account; Query collection; AccountPlatform platform;
        synchronized (this) {
            State s = stateFor(token); if (s == null) return null; refresh(s); item = s.rows.get(token);
            if (item == null) return null;
            if(!fallback && s.resolvedCovers.containsKey(coverKey(item))) return s.resolvedCovers.get(coverKey(item));
            if (item.cover() != null && !fallback) return item.cover();
            if (fallback && item.cover() == null) return null;
            platform=s.platform;
            if(platform==AccountPlatform.QQMUSIC || platform==AccountPlatform.APPLEMUSIC || platform==AccountPlatform.KUGOU) return null;
            if(platform==AccountPlatform.BILIBILI && (item.kind()!=Kind.COLLECTION || item.owner()<=0)) return null;
            if(platform==AccountPlatform.NETEASE && item.kind()!=Kind.TRACK && item.kind()!=Kind.FOLDER) return null;
            account = accounts.apply(s.platform);
            collection = platform==AccountPlatform.BILIBILI?new Query(s.platform, View.COLLECTION, Long.parseLong(item.target()), 1, item.owner()):null;
        }
        // Network work must not hold the UI/catalog monitor. The row token also fences account changes.
        request.check();
        java.net.URI cover = platform==AccountPlatform.NETEASE?neteaseCoverLookup.resolve(account,item,request):coverLookup.resolve(account, collection, request);
        request.check();
        synchronized (this) {
            State s = states.get(platform); refresh(s);
            if(s.rows.get(token)!=item || accounts.apply(platform)!=account) return null;
            if(cover!=null) {
                s.resolvedCovers.put(coverKey(item),cover);
                while(s.resolvedCovers.size()>256)s.resolvedCovers.pollFirstEntry();
            }
            return cover;
        }
    }
    public synchronized void playRow(String token, Consumer<OnlinePlaybackController.Target> play) {
        State s = stateFor(token); if (s == null) throw new IllegalStateException("Stale row"); refresh(s); Item item = s.rows.get(token);
        if (item == null || !item.enabled() || item.kind() != Kind.TRACK) throw new IllegalStateException("Stale or unsupported track");
        play.accept(new OnlinePlaybackController.Target(s.platform, item.target()));
    }
    public synchronized void saveRow(String token,java.util.function.BiConsumer<OnlinePlaybackController.Target,Item> save) {
        playRow(token,target->{State s=stateFor(token);save.accept(target,withAuthor(s,s.rows.get(token)));});
    }
    public record Selection(AccountPlatform platform,Item item,AccountCredentials account) { }
    public synchronized Selection selection(String token) {
        State s=stateFor(token);if(s==null)throw new IllegalStateException("Stale row");
        refresh(s);Item item=s.rows.get(token);
        if(item==null || !item.enabled())throw new IllegalStateException("Stale row");
        return new Selection(s.platform,withAuthor(s,item),s.account);
    }
    public synchronized void requestPlaylist(String token) {
        Selection selection=selection(token);
        if(selection.item().kind()==Kind.TRACK)throw new IllegalArgumentException("Select a playlist");
        choose(token);requestPlaylist();
    }
    public synchronized void playlistsChanged(AccountPlatform changed) {
        State s=states.get(changed);s.cache.clear();
        s.history.clear();
        if(s.query!=null && (s.query.view()==View.MINE || s.query.view()==View.SAVED))load(s,s.query);
    }
    public synchronized boolean currentRow(String token) { State s = stateFor(token); if (s == null) return false; refresh(s); return s.rows.containsKey(token); }
    private void invalidate(State s) { s.cache.clear(); s.resolvedCovers.clear(); reset(s); s.history.clear(); s.authorName=""; s.rows.clear(); s.visible=null; s.query=null; s.loader.invalidate(""); }
    public synchronized void accountChanged(AccountPlatform changed) { invalidate(states.get(changed)); if(platform==changed) navigation.run(); }
    public synchronized void invalidate() { states.values().forEach(this::invalidate); navigation.run(); }
    @Override public synchronized void close() { invalidate(); for(State s:states.values()) { s.loader.close(); s.complete.close(); } }
}
