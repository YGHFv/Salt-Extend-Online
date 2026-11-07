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
    private AccountCredentials account;
    private long revision;
    CatalogPageCache(Clock clock) { this.clock = clock; }
    synchronized void clear() { revision++; pages.clear(); account = null; }
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
