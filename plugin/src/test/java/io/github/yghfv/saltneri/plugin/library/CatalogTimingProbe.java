package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit fixed public chart metadata only; prints counts/timing, never rows, covers or credentials. */
public final class CatalogTimingProbe {
    public static void main(String[] args) {
        if (args.length!=1 || !args[0].equals("--explicit-public-playlist") && !args[0].equals("--explicit-public-bili")) {
            System.err.println("Requires --explicit-public-playlist or --explicit-public-bili."); System.exit(2); return;
        }
        var requests=new AtomicInteger();
        try(var scope=new RequestScope(()->true)) {
            var client=new CatalogClient(null,Clock.systemUTC(),(uri,form)->{
                requests.incrementAndGet(); return CatalogHttp.openPinned(uri,form);
            });
            long start=System.nanoTime();
            if(args[0].equals("--explicit-public-bili")) {
                var first=client.load(new Catalog.Query(AccountPlatform.BILIBILI,Catalog.View.DISCOVER,0,1),scope);
                int before=requests.get();
                var next=client.load(new Catalog.Query(AccountPlatform.BILIBILI,Catalog.View.DISCOVER,0,2),scope);
                System.out.println("PASS: Bili public discovery; firstRows="+first.items().size()+"; secondRows="+next.items().size()
                        +"; firstRequests="+before+"; additionalPageRequests="+(requests.get()-before)+"; elapsedMs="+(System.nanoTime()-start)/1_000_000);
                System.out.println("No account, image, media URL, audio or host accessed; response not retained."); return;
            }
            var page=client.load(new Catalog.Query(AccountPlatform.NETEASE,Catalog.View.FOLDER,3778678,1),scope);
            System.out.println("PASS: fixed public playlist first page; requests="+requests.get()+"; rows="+page.items().size()
                    +"; elapsedMs="+(System.nanoTime()-start)/1_000_000);
            System.out.println("No account, image, media URL, audio or host accessed; public response not retained.");
        } catch(Exception failure) {
            System.err.println("STOP: "+CatalogFailure.diagnostic(failure)); System.exit(3);
        }
    }
}
