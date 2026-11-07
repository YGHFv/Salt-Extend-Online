package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

public final class CatalogBulkContractTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if(!ok) throw new AssertionError("Bulk catalog "+checks); }
    private static String song(int id) { return "{\"id\":"+id+",\"name\":\"Song "+id+"\",\"fee\":0,\"dt\":1000,\"ar\":[{\"name\":\"Artist\"}],\"al\":{\"picUrl\":\"https://p1.music.126.net/fixture.jpg\"}}"; }
    private static void await(BooleanSupplier done) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!done.getAsBoolean() && System.nanoTime()<until) Thread.sleep(10);
        check(done.getAsBoolean());
    }
    public static void main(String[] args) throws Exception {
        Clock clock=CatalogContractTest.CLOCK;
        for(boolean loggedIn:List.of(false,true)) {
            var account=loggedIn?CatalogContractTest.account(AccountPlatform.NETEASE):null;
            var verify=new CatalogContractTest.Fixture(); List<Integer> batches=new ArrayList<>(); var details=new AtomicInteger();
            try(RequestScope request=new RequestScope(()->true)) {
                var client=new CatalogClient(account,clock,(uri,form)->{
                    if(uri.getPath().contains("account")) return verify.open(uri,form);
                    String json;
                    if(uri.getPath().equals("/api/v6/playlist/detail")) {
                        details.incrementAndGet();
                        var ids=new ArrayList<String>(); var tracks=new ArrayList<String>();
                        for(int i=1;i<=315;i++) { ids.add("{\"id\":"+i+"}"); if(i<=20) tracks.add(song(i)); }
                        json="{\"code\":200,\"playlist\":{\"id\":10,\"name\":\"Fixture\",\"trackIds\":["+String.join(",",ids)+"],\"tracks\":["+String.join(",",tracks)+"]}}";
                    } else if(uri.getPath().equals("/api/song/detail")) {
                        String decoded=URLDecoder.decode(uri.getRawQuery().substring(4),StandardCharsets.UTF_8);
                        var ids=BoundedJson.array(BoundedJson.parse(decoded.getBytes(StandardCharsets.UTF_8))); batches.add(ids.size()); check(ids.size()<=100);
                        var tracks=new ArrayList<String>(); for(Object id:ids) tracks.add(song((int)BoundedJson.integer(id)));
                        json="{\"code\":200,\"songs\":["+String.join(",",tracks)+"]}";
                    } else throw new AssertionError("Unexpected request");
                    return new CatalogContractTest.Response(uri,json,List.of());
                });
                var result=client.loadAll(new Query(AccountPlatform.NETEASE,View.FOLDER,10,1),request);
                check(result.items().size()==315 && result.totalCount()==315);
                for(int i=0;i<315;i++) check(result.items().get(i).target().equals(""+(i+1)));
                check(details.get()==1 && batches.equals(List.of(100,100,95)));
                check(verify.requests.size()==(loggedIn?1:0));
            } finally { if(account!=null) account.retire(); }
        }
        var bili=CatalogContractTest.account(AccountPlatform.BILIBILI); var fixture=new CatalogContractTest.Fixture();
        try(RequestScope request=new RequestScope(()->true)) {
            var client=new CatalogClient(bili,clock,(uri,form)->{
                if(uri.getPath().endsWith("nav")) return fixture.open(uri,form);
                int page=uri.getQuery().contains("pn=1&")?1:uri.getQuery().contains("pn=2&")?2:3;
                String id="BV17x411w7K"+(page==1?"C":page==2?"D":"E");
                String json="{\"code\":0,\"data\":{\"info\":{\"id\":10,\"title\":\"Folder\",\"media_count\":45},\"has_more\":"+(page<3)+",\"medias\":[{\"id\":"+page+",\"type\":2,\"bvid\":\""+id+"\",\"title\":\"Video\",\"duration\":60}]}}";
                return new CatalogContractTest.Response(uri,json,List.of());
            });
            check(client.loadAll(new Query(AccountPlatform.BILIBILI,View.FOLDER,10,1),request).items().size()==3);
            check(fixture.requests.size()==1); // Verified once for the entire explicit operation.
        } finally { bili.retire(); }
        var hold=new CountDownLatch(1); var entered=new CountDownLatch(1);
        try(var library=new LibraryController(p->null,()->true,t->{},()->{},Clock.systemUTC(),a->(q,k,s,r)->{
            if(q.view()!=View.FOLDER) return new Page("Folders",List.of(new Item(Kind.FOLDER,"10","Fixture","Artist",40,true)),false,Instant.MAX,1,1);
            if(k.equals("all")) { entered.countDown(); hold.await(3,TimeUnit.SECONDS); r.check(); }
            return new Page("Fixture",List.of(new Item(Kind.TRACK,""+q.page(),"Song","Artist",1000,true)),q.page()==1,Instant.MAX,2,40);
        })) {
            library.browse(AccountPlatform.NETEASE,View.SAVED); await(()->library.snapshot().get("loaded").equals(true));
            List<?> folders=(List<?>)library.snapshot().get("items"); library.choose((String)((Map<?,?>)folders.getFirst()).get("token"));
            await(()->library.snapshot().get("loaded").equals(true)); var original=library.snapshot();
            library.requestPlaylist(); check(entered.await(3,TimeUnit.SECONDS));
            check(library.snapshot().get("items").equals(original.get("items")) && library.snapshot().get("loaded").equals(true));
            check(library.snapshot().get("loadingAll").equals(true) && library.takePlaylist()==null);
            hold.countDown(); await(()->library.snapshot().get("loadingAll").equals(false));
            check(library.takePlaylist().items().size()==2 && library.takePlaylist()==null);
        } finally { hold.countDown(); }
        System.out.println("PASS: "+checks+" bulk 315-song batching/order/single account verification and visible-list preservation checks; fake HTTP, no audio");
    }
}
