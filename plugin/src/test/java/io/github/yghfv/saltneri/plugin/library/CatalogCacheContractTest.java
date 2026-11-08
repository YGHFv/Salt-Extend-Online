package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

/** Deterministic cache/request counts; no private playlists, real network or media. */
public final class CatalogCacheContractTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Catalog cache " + checks); }
    static final class Time extends Clock {
        Instant now = Instant.parse("2026-11-07T00:00:00Z");
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
    private static Page page(Query q, Clock clock) {
        return new Page(q.view().name(),List.of(new Item(Kind.TRACK,"123","Song","Artist",1000,true)),
                q.page()==1,clock.instant().plusSeconds(300),2,21);
    }
    private static Query query(long folder) { return new Query(AccountPlatform.NETEASE,View.FOLDER,folder,1); }
    private static void ready(LibraryController library) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!Boolean.TRUE.equals(library.snapshot().get("loaded")) && System.nanoTime()<end) Thread.sleep(5);
        check(Boolean.TRUE.equals(library.snapshot().get("loaded")));
    }
    private static String token(LibraryController library) {
        return (String)((Map<?,?>)((List<?>)library.snapshot().get("items")).getFirst()).get("token");
    }
    public static void main(String[] args) throws Exception {
        Time clock=new Time(); var cache=new CatalogPageCache(clock); var calls=new AtomicInteger();
        PlatformResolutionController.Resolver<Query,String,Page> resolver=(q,k,s,r)->{calls.incrementAndGet(); return page(q,clock);};
        try(var request=new RequestScope(()->true)) {
            Page original=cache.load(null,query(1),"read","fixture",request,resolver);
            check(cache.load(null,query(1),"read","fixture",request,resolver)==original && calls.get()==1);
            clock.now=clock.now.plusSeconds(119);
            check(cache.load(null,query(1),"read","fixture",request,resolver)==original && calls.get()==1);
            clock.now=clock.now.plusSeconds(1);
            check(cache.load(null,query(1),"read","fixture",request,resolver)!=original && calls.get()==2);
            for(int i=2;i<=CatalogPageCache.LIMIT;i++) cache.load(null,query(i),"read","fixture",request,resolver);
            int before=calls.get(); cache.load(null,query(1),"read","fixture",request,resolver);
            check(calls.get()==before);
            cache.load(null,query(33),"read","fixture",request,resolver);
            cache.load(null,query(1),"read","fixture",request,resolver); check(calls.get()==before+1);
            cache.load(null,query(2),"read","fixture",request,resolver); check(calls.get()==before+2);
            cache.clear(); cache.load(null,query(1),"read","fixture",request,resolver); check(calls.get()==before+3);
            before=calls.get();
            try { cache.load(null,query(4),"read","fixture",request,(q,k,s,r)->{throw new java.io.IOException("fixture");});
                throw new AssertionError("Failure hidden"); } catch(java.io.IOException expected) { checks++; }
            cache.load(null,query(1),"read","fixture",request,resolver); check(calls.get()==before+1);
            var first=CatalogContractTest.account(AccountPlatform.NETEASE); var second=CatalogContractTest.account(AccountPlatform.NETEASE);
            try {
                before=calls.get(); cache.load(first,query(1),"read","fixture",request,resolver);
                cache.load(first,query(1),"read","fixture",request,resolver); check(calls.get()==before+1);
                cache.load(second,query(1),"read","fixture",request,resolver); check(calls.get()==before+2);
                cache.load(null,query(1),"read","fixture",request,resolver); check(calls.get()==before+3);
            } finally { first.retire(); second.retire(); }
            var cancelled=new RequestScope(()->true); cancelled.close();
            try { cache.load(null,query(1),"read","fixture",cancelled,resolver); throw new AssertionError("Cancelled cache hit"); }
            catch(CancellationException expected) { checks++; }
        }
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var request=new RequestScope(()->true)) {
            var task=new FutureTask<Page>(()->cache.load(null,query(9),"read","fixture",request,(q,k,s,r)->{
                entered.countDown(); release.await(); return page(q,clock);
            }));
            var thread=new Thread(task); thread.start(); check(entered.await(2,TimeUnit.SECONDS));
            cache.clear(); release.countDown(); task.get(2,TimeUnit.SECONDS);
            int before=calls.get(); cache.load(null,query(9),"read","fixture",request,resolver); check(calls.get()==before+1);
        } finally { release.countDown(); }
        controller();
        autoDiscover();
        sessions();
        System.out.println("PASS: "+checks+" catalog cache TTL/LRU/account/refresh/failure/cancellation/navigation checks; no network or audio");
    }
    private static void sessions() throws Exception {
        for(AccountPlatform platform:AccountPlatform.values()) {
            var clock=new Time(); var cache=new CatalogPageCache(clock); var fixture=new CatalogContractTest.Fixture();
            var account=CatalogContractTest.account(platform);
            try(var request=new RequestScope(()->true)) {
                var client=new CatalogClient(account,clock,fixture,cache.discovery,cache.sessions);
                client.load(new Query(platform,View.MINE,0,1),request);
                int first=fixture.requests.size();
                client.load(new Query(platform,View.MINE,0,2),request);
                check(fixture.requests.size()==first+1); // No repeated login verification / NetEase CSRF warmup.
                if(platform==AccountPlatform.BILIBILI) {
                    client.load(new Query(platform,View.SEARCH,0,1,0,"fixture"),request);
                    check(fixture.requests.size()==first+2); // Cached WBI mixin is still used for signing.
                }
                clock.now=clock.now.plusSeconds(120); int before=fixture.requests.size();
                client.load(new Query(platform,View.MINE,0,3),request);
                check(fixture.requests.size()>before+1);
                cache.clear(); before=fixture.requests.size();
                client.load(new Query(platform,View.MINE,0,4),request); check(fixture.requests.size()>before+1);
                before=fixture.requests.size(); account.retire();
                try {client.load(new Query(platform,View.MINE,0,5),request);throw new AssertionError("Retired session cache used");}
                catch(CancellationException | CatalogFailure expected) {checks++;}
                check(fixture.requests.size()==before);
            } finally {account.retire();}
        }
    }
    private static void controller() throws Exception {
        Time clock=new Time(); var calls=new AtomicInteger(); var account=new AtomicReference<AccountCredentials>(); var allowed=new AtomicBoolean(true);
        try(var library=new LibraryController(p->account.get(),allowed::get,t->{throw new AssertionError("Cache played");},()->{},clock,
                a->(q,k,s,r)->{calls.incrementAndGet(); return page(q,clock);})) {
            library.browse(AccountPlatform.NETEASE,View.MINE); ready(library); String old=token(library);
            library.turn(1); ready(library); library.turn(-1); ready(library);
            check(calls.get()==2 && !library.currentRow(old));
            library.browse(AccountPlatform.NETEASE,View.SAVED); ready(library);
            library.browse(AccountPlatform.NETEASE,View.MINE); ready(library); check(calls.get()==3);
            library.reload(); ready(library); check(calls.get()==4);
            library.turn(1); ready(library); check(calls.get()==5);
            clock.now=clock.now.plusSeconds(120); library.turn(-1); ready(library); check(calls.get()==6);
            account.set(CatalogContractTest.account(AccountPlatform.NETEASE)); library.accountChanged(AccountPlatform.NETEASE);
            check(((List<?>)library.snapshot().get("items")).isEmpty());
            library.browse(AccountPlatform.NETEASE,View.MINE); ready(library); check(calls.get()==7);
            old=token(library); account.get().retire(); check(!library.currentRow(old));
            account.set(null); library.browse(AccountPlatform.NETEASE,View.MINE); ready(library); check(calls.get()==8);
            allowed.set(false); library.snapshot(); allowed.set(true);
            library.browse(AccountPlatform.NETEASE,View.MINE); ready(library); check(calls.get()==9);
            check(library.takePlaylist()==null);
        } finally { if(account.get()!=null) account.get().retire(); }
    }
    private static void autoDiscover() throws Exception {
        var calls=new AtomicInteger(); var fail=new AtomicBoolean(); var allowed=new AtomicBoolean(true);
        var query=new AtomicReference<Query>(); var clock=new Time();
        try(var library=new LibraryController(p->null,allowed::get,t->{throw new AssertionError("Entry played");},()->{},clock,
                a->(q,k,s,r)->{calls.incrementAndGet();query.set(q);if(fail.get()) throw new java.io.IOException();return page(q,clock);})) {
            library.snapshot(AccountPlatform.BILIBILI); check(calls.get()==0);
            for(var platform:AccountPlatform.values()) {
                check(library.enter(platform)); library.snapshot(platform); ready(library);
                check(query.get().view()==View.DISCOVER && query.get().platform()==platform);
                int before=calls.get(); for(int i=0;i<5;i++) check(!library.enter(platform)); check(calls.get()==before);
                library.browse(platform,View.MINE); ready(library); before=calls.get();
                check(!library.enter(platform) && library.snapshot(platform).get("view").equals("MINE") && calls.get()==before);
            }
            library.invalidate(); fail.set(true); check(library.enter(AccountPlatform.BILIBILI));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(library.snapshot().get("error").toString().isEmpty() && System.nanoTime()<end) Thread.sleep(5);
            check(!library.snapshot().get("error").toString().isEmpty()); int before=calls.get();
            check(!library.enter(AccountPlatform.BILIBILI)); Thread.sleep(50); check(calls.get()==before);
            fail.set(false); library.accountChanged(AccountPlatform.BILIBILI);
            check(library.enter(AccountPlatform.BILIBILI)); ready(library); check(calls.get()==before+1);
            allowed.set(false);
            try { library.enter(AccountPlatform.NETEASE); throw new AssertionError("Unauthorized entry"); }
            catch(SecurityException expected) { checks++; }
            check(library.takePlaylist()==null);
        }
    }
}
