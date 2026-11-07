package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

/** Fixture-only batch/read cache and in-plugin uploader navigation. */
public final class CatalogPresentationContractTest {
    private static int checks;
    private static void check(boolean ok) {checks++;if(!ok)throw new AssertionError("Catalog presentation "+checks);}
    private static void ready(LibraryController library) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<until) {var s=library.snapshot();if(s.get("loaded").equals(true) && s.get("loadingAll").equals(false))return;Thread.sleep(5);}
        throw new AssertionError("Catalog timeout");
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(LibraryController library) {return (List<Map<String,Object>>)library.snapshot().get("items");}
    public static void main(String[] args) throws Exception {
        batches(); authors(); navigation(); remoteOrder();
        check(Catalog.duration(125000).equals("02:05") && Catalog.duration(3661000).equals("1:01:01") && Catalog.duration(0).equals("--:--"));
        System.out.println("PASS: "+checks+" cached bounded parallel catalog, uploader APIs/navigation, duration and no redundant sort checks; fixtures only");
    }
    private static void remoteOrder() throws Exception {
        var queries=new CopyOnWriteArrayList<Query>();
        try(var library=new LibraryController(p->null,()->true,t->{},()->{},Clock.systemUTC(),a->(q,k,s,r)->{
            queries.add(q); if(k.equals("all"))throw new AssertionError("Default/reverse fetched complete catalog");
            if(q.view()!=View.COLLECTION) return new Page("Sets",List.of(new Item(Kind.COLLECTION,"30","Set","Uploader",606,true,null,99)),false,Instant.MAX,1,1);
            return new Page("Set",List.of(new Item(Kind.TRACK,"BV17x411w7KC",q.reverse()?"Last":"First","Uploader",125000,true,null,99)),true,Instant.MAX,31,606);
        })) {
            library.browse(AccountPlatform.BILIBILI,View.SAVED);ready(library);library.choose((String)rows(library).getFirst().get("token"));ready(library);
            int before=queries.size();library.sort(Sort.REVERSE);ready(library);
            check(queries.size()==before+1 && queries.getLast().reverse() && rows(library).getFirst().get("title").equals("Last"));
            library.turn(1);ready(library);check(queries.getLast().page()==2 && queries.getLast().reverse());
            before=queries.size();library.sort(Sort.DEFAULT);ready(library);
            check(queries.size()==before && rows(library).getFirst().get("title").equals("First"));
            check(library.snapshot().get("loadingAll").equals(false));
        }
    }
    private static void batches() throws Exception {
        var account=CatalogContractTest.account(AccountPlatform.BILIBILI);
        var fixture=new CatalogContractTest.Fixture();
        var cache=new CatalogPageCache(CatalogContractTest.CLOCK);
        var gate=new CountDownLatch(3); var active=new AtomicInteger(); var peak=new AtomicInteger();
        var pages=new ConcurrentLinkedQueue<Integer>(); var progress=new ArrayList<Integer>();
        var query=new Query(AccountPlatform.BILIBILI,View.COLLECTION,30,1,99);
        try(var request=new RequestScope(()->true)) {
            var client=new CatalogClient(account,CatalogContractTest.CLOCK,(uri,form)->{
                if(uri.getPath().endsWith("nav"))return fixture.open(uri,form);
                var params=new HashMap<String,String>();for(String pair:uri.getQuery().split("&")){var parts=pair.split("=",2);params.put(parts[0],parts[1]);}
                int page=Integer.parseInt(params.get("page_num")); pages.add(page);
                int concurrent=active.incrementAndGet();peak.accumulateAndGet(concurrent,Math::max);
                try {
                    if(page>=2 && page<=4) {gate.countDown();if(!gate.await(3,TimeUnit.SECONDS))throw new AssertionError("Batch serialized");}
                    String json="{\"code\":0,\"data\":{\"meta\":{\"season_id\":30,\"mid\":99,\"name\":\"Set\",\"total\":81},\"page\":{\"total\":81},\"archives\":[{\"aid\":"+page+",\"bvid\":\"BV17x411w7K"+page+"\",\"title\":\"Track "+page+"\",\"duration\":60}]}}";
                    return new CatalogContractTest.Response(uri,json,List.of());
                } catch(InterruptedException error){Thread.currentThread().interrupt();throw new java.io.IOException("Interrupted");}
                finally {active.decrementAndGet();}
            });
            cache.load(account,query,"read","scope",request,(q,k,s,r)->client.load(q,r));
            var result=cache.all(account,query,request,()->client.loadAll(query,request,cache,(done,total)->progress.add(done)));
            check(result.items().size()==5 && result.items().getFirst().target().endsWith("1") && result.items().getLast().target().endsWith("5"));
            check(pages.size()==5 && Collections.frequency(new ArrayList<>(pages),1)==1 && peak.get()==3);
            check(progress.equals(List.of(1,2,3,4,5)) && fixture.requests.size()==2);
            check(cache.all(account,query,request,()->{throw new AssertionError("Warm collection refetched");})==result);
            cache.clear();
            var calls=new AtomicInteger();cache.all(account,query,request,()->{calls.incrementAndGet();return result;});check(calls.get()==1);
        } finally {account.retire();}
    }
    private static void authors() throws Exception {
        var account=CatalogContractTest.account(AccountPlatform.BILIBILI);var fixture=new CatalogContractTest.Fixture();
        try(var request=new RequestScope(()->true)) {
            var client=new CatalogClient(account,CatalogContractTest.CLOCK,(uri,form)->{
                if(uri.getPath().endsWith("nav"))return fixture.open(uri,form);
                if(!uri.getQuery().contains("mid=99") || !uri.getQuery().contains("w_rid="))throw new AssertionError("Unsigned uploader");
                String json=uri.getPath().endsWith("arc/search")
                    ? "{\"code\":0,\"data\":{\"page\":{\"pn\":1,\"count\":21},\"list\":{\"vlist\":[{\"bvid\":\"BV17x411w7KC\",\"title\":\"Video\",\"author\":\"Uploader\",\"length\":\"02:05\"}]}}}"
                    : "{\"code\":0,\"data\":{\"items_lists\":{\"page\":{\"page_num\":1,\"total\":1},\"seasons_list\":[{\"meta\":{\"season_id\":30,\"mid\":99,\"name\":\"Set\",\"total\":81}}]}}}";
                return new CatalogContractTest.Response(uri,json,List.of());
            });
            var videos=client.load(new Query(AccountPlatform.BILIBILI,View.AUTHOR_VIDEOS,0,1,99),request);
            check(videos.hasMore() && videos.items().getFirst().owner()==99 && videos.items().getFirst().count()==125000);
            var collections=client.load(new Query(AccountPlatform.BILIBILI,View.AUTHOR_COLLECTIONS,0,1,99),request);
            check(!collections.hasMore() && collections.items().getFirst().kind()==Kind.COLLECTION && collections.items().getFirst().owner()==99);
        } finally {account.retire();}
    }
    private static void navigation() throws Exception {
        var requests=new AtomicInteger();
        try(var library=new LibraryController(p->null,()->true,t->{throw new AssertionError("Navigation played");},()->{},Clock.systemUTC(),a->(q,k,s,r)->{
            requests.incrementAndGet();
            var item=q.view()==View.AUTHOR_COLLECTIONS?new Item(Kind.COLLECTION,"30","Set","Uploader",2,true,null,99)
                    :q.view()==View.FOLDER || q.view()==View.AUTHOR_VIDEOS || q.view()==View.COLLECTION?new Item(Kind.TRACK,"BV17x411w7KC","Video","Uploader",125000,true,null,99)
                    :new Item(Kind.FOLDER,"10","Folder","Uploader",1,true);
            return new Page("Fixture",List.of(item),false,Instant.MAX,1,1);
        })) {
            library.browse(AccountPlatform.BILIBILI,View.MINE);ready(library);library.choose((String)rows(library).getFirst().get("token"));ready(library);
            int before=requests.get(); library.sort(Sort.DEFAULT);library.sort(Sort.TITLE);ready(library);check(requests.get()==before);
            var row=rows(library).getFirst(); check(row.get("duration").equals("02:05") && row.get("author").equals(true) && row.get("collection").equals("Fixture"));
            String old=(String)row.get("token"); library.author(old);ready(library);
            check(library.snapshot().get("view").equals("AUTHOR_VIDEOS") && library.snapshot().get("authorName").equals("Uploader"));
            library.authorTab(true);ready(library);check(rows(library).getFirst().get("kind").equals("COLLECTION"));
            library.choose((String)rows(library).getFirst().get("token"));ready(library);check(library.snapshot().get("view").equals("COLLECTION"));
            library.back();ready(library);check(library.snapshot().get("view").equals("AUTHOR_COLLECTIONS"));
            library.back();ready(library);check(library.snapshot().get("view").equals("FOLDER") && library.snapshot().get("sort").equals("TITLE"));
            library.accountChanged(AccountPlatform.BILIBILI);check(library.snapshot().get("back").equals(false) && !library.currentRow(old));
        }
    }
}
