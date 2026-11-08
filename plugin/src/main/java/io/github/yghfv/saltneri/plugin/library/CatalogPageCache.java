package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.AccountCredentials;
import io.github.yghfv.saltneri.plugin.net.PlatformResolutionController;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

/** Short-lived metadata only; no media leases or persistent account catalog. */
final class CatalogPageCache {
    static final int LIMIT = 32;
    private record Cached(Page page, Instant until) { }
    private final Clock clock;
    private final LinkedHashMap<Query, Cached> pages = new LinkedHashMap<>(16, 0.75f, true);
    private record Full(CatalogClient.CollectionContents contents,Instant until) { }
    private final LinkedHashMap<Query,Full> complete=new LinkedHashMap<>(4,0.75f,true);
    private AccountCredentials account;
    private long revision;
    final BiliDiscovery.Cache discovery;
    final CatalogHttp.SessionCache sessions=new CatalogHttp.SessionCache();
    CatalogPageCache(Clock clock) { this.clock = clock; discovery = new BiliDiscovery.Cache(clock); }
    synchronized void clear() { revision++; pages.clear(); complete.clear(); account = null; discovery.clear(); sessions.clear(); }
    @FunctionalInterface interface FullLoader { CatalogClient.CollectionContents load() throws Exception; }
    CatalogClient.CollectionContents all(AccountCredentials owner,Query query,RequestScope request,FullLoader loader) throws Exception {
        long captured;
        synchronized(this) {
            if(account!=owner) {clear();account=owner;} captured=revision; request.check();
            complete.entrySet().removeIf(e->!clock.instant().isBefore(e.getValue().until()));
            Full hit=complete.get(query); if(hit!=null) return hit.contents();
        }
        var result=loader.load(); request.check();
        synchronized(this) {
            request.check();
            if(captured==revision && account==owner) {
                complete.put(query,new Full(result,clock.instant().plusSeconds(120)));
                while(complete.size()>2 || complete.values().stream().mapToInt(e->e.contents().items().size()).sum()>10000)
                    complete.remove(complete.keySet().iterator().next());
            }
        }
        return result;
    }
    Page load(AccountCredentials owner, Query query, String quality, String scope, RequestScope request,
              PlatformResolutionController.Resolver<Query,String,Page> upstream) throws Exception {
        request.check(); long captured;
        synchronized (this) {
            if (account != owner) { clear(); account = owner; }
            captured = revision;
            pages.entrySet().removeIf(e -> !clock.instant().isBefore(e.getValue().until()));
            Cached hit = pages.get(query);
            if (hit != null) { request.check(); return hit.page(); }
        }
        try {
            Page page = upstream.resolve(query,quality,scope,request); request.check();
            synchronized (this) {
                request.check();
                if (revision == captured && account == owner) {
                    Instant until = clock.instant().plusSeconds(120);
                    if (page.expiresAt().isBefore(until)) until = page.expiresAt();
                    pages.put(query,new Cached(page,until));
                    while (pages.size() > LIMIT) pages.remove(pages.keySet().iterator().next());
                }
            }
            return page;
        } catch (Exception | LinkageError error) {
            synchronized (this) { if (revision == captured) clear(); }
            throw error;
        }
    }
}
