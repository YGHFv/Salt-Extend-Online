package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.net.RequestScope;

/** Opt-in public metadata only: one page, no account, cover, media resolution or queue. */
public final class CatalogLiveProbe {
    public static void main(String[] args) {
        if (args.length != 2 || !args[0].equals("--explicit-public-probe")
                || !args[1].equals("search") && !args[1].equals("discover")) {
            System.err.println("Requires --explicit-public-probe search|discover."); System.exit(2); return;
        }
        boolean search = args[1].equals("search");
        try (RequestScope request = new RequestScope(() -> true)) {
            var query = new Catalog.Query(AccountPlatform.NETEASE, search ? Catalog.View.SEARCH : Catalog.View.DISCOVER,
                    0, 1, 0, search ? "music" : "");
            var page = new CatalogClient(null).load(query,request);
            System.out.println("PASS: anonymous NetEase " + args[1] + "; rows=" + page.items().size()
                    + "; enabled=" + page.items().stream().filter(Catalog.Item::enabled).count() + "; pages=" + page.totalPages());
            System.out.println("No account, image, media, host or queue access; no response body retained.");
        } catch (Exception failure) {
            System.err.println("STOP: " + CatalogFailure.diagnostic(failure)); System.exit(3);
        }
    }
}
