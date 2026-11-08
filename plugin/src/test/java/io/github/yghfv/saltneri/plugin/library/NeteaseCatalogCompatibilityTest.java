package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;
import static io.github.yghfv.saltneri.plugin.library.CatalogContractTest.*;

/** Reproduces heterogeneous playlist metadata and artwork-only recovery using synthetic HTTP. */
public final class NeteaseCatalogCompatibilityTest {
    private static int checks;
    private static void check(boolean ok) {checks++;if(!ok)throw new AssertionError("NetEase compatibility "+checks);}
    private static final URI COVER=URI.create("https://p2.music.126.net/fixture.jpg");
    private static String song(int id,String picture) {
        return "{\"id\":"+id+",\"name\":\"Song\",\"fee\":0,\"dt\":120000,\"ar\":[{\"name\":\"Artist\"}],\"al\":{\"picUrl\":\""+picture+"\"}}";
    }
    public static void main(String[] args) throws Exception {
        personal(); inline(); artwork(); revoke();
        System.out.println("PASS: "+checks+" NetEase partial creator/labels/paging/inline metadata/artwork-only recovery and revocation checks; fake HTTP only");
    }
    private static void personal() throws Exception {
        var account=account(AccountPlatform.NETEASE);
        try {
            Fixture f=new Fixture(); f.personal="""
                {"code":200,"more":"1","playlist":[
                  {"id":1,"name":"Missing creator","trackCount":"bad"},
                  {"id":2,"name":"  Multi\\nline\\tplaylist  ","userId":"42","creator":{"nickname":"A\\r\\nB"},"coverImgUrl":"","picUrl":"https://p2.music.126.net/fixture.jpg"},
                  {"id":3,"name":"Liked","creator":{},"specialType":5,"subscribed":false},
                  {"id":4,"name":"Saved","creator":null,"subscribed":1},
                  {"id":5,"name":"Other owner","creator":{"userId":99}},
                  {"id":2,"name":"Duplicate"},{"id":0,"name":"Deleted"},null,{"id":6,"name":{}},
                  {"id":7,"name":"Owned","creator":{"userId":42},"subscribed":true}]}
                """;
            var mine=f.load(new Query(AccountPlatform.NETEASE,View.MINE,0,1),account);
            check(mine.items().stream().map(Item::target).toList().equals(List.of("1","2","3","7")));
            check(mine.items().get(1).title().equals("Multi line playlist") && mine.items().get(1).subtitle().equals("A B"));
            check(mine.items().getFirst().count()==-1 && mine.items().get(1).cover().equals(COVER) && mine.hasMore());
            var saved=f.load(new Query(AccountPlatform.NETEASE,View.SAVED,0,1),account);
            check(saved.items().stream().map(Item::target).toList().equals(List.of("4","5")));
            f.personal=f.personal.replace("\"playlist\":","\"playlists\":");
            check(f.load(new Query(AccountPlatform.NETEASE,View.MINE,0,1),account).items().size()==4);
            f.personal="{\"code\":200,\"more\":false,\"playlist\":null}";
            check(f.load(new Query(AccountPlatform.NETEASE,View.MINE,0,1),account).items().isEmpty());
            f.personal="{\"code\":200}";
            safeFailure(()->f.load(new Query(AccountPlatform.NETEASE,View.MINE,0,1),account),CatalogFailure.Stage.NET_PLAYLISTS,CatalogFailure.Reason.RESPONSE,null); checks++;
            f.personal="{\"code\":301,\"message\":\"SECRET\",\"playlist\":[]}";
            safeFailure(()->f.load(new Query(AccountPlatform.NETEASE,View.MINE,0,1),account),CatalogFailure.Stage.NET_PLAYLISTS,CatalogFailure.Reason.API,301L);checks++;
            var values=new ArrayList<String>();for(int i=1;i<=21;i++)values.add("{\"id\":"+i+",\"name\":\"List\"}");
            f.personal="{\"code\":200,\"more\":false,\"playlist\":["+String.join(",",values)+"]}";
            var overfilled=f.load(new Query(AccountPlatform.NETEASE,View.MINE,0,1),account);
            check(overfilled.items().size()==20 && overfilled.hasMore() && overfilled.totalPages()==0);
            f.personal="{\"code\":200,\"count\":21,\"playlist\":[{\"id\":21,\"name\":\"Last\"}]}";
            var last=f.load(new Query(AccountPlatform.NETEASE,View.MINE,0,2),account);check(!last.hasMore() && last.totalPages()==2);
        } finally {account.retire();}
    }
    private static void inline() throws Exception {
        Query query=new Query(AccountPlatform.NETEASE,View.FOLDER,10,1);
        for(String missing:List.of("","https://evil.example/SECRET.jpg")) {
            Fixture f=new Fixture();f.detail="{\"code\":200,\"playlist\":{\"id\":10,\"name\":\"List\",\"trackIds\":[{\"id\":1}],\"tracks\":["+song(1,missing)+"]}}";
            f.songs="{\"code\":200,\"songs\":["+song(1,COVER.toString())+"]}";
            check(f.load(query,null).items().getFirst().cover().equals(COVER) && f.requests.size()==2);
        }
        Fixture f=new Fixture(); var ids=new ArrayList<String>();var songs=new ArrayList<String>();
        for(int i=1;i<=25;i++){ids.add("{\"id\":"+i+"}");songs.add(song(i,COVER.toString()));}
        f.detail="{\"code\":200,\"playlist\":{\"id\":10,\"name\":\"List\",\"trackIds\":["+String.join(",",ids)+"],\"tracks\":["+String.join(",",songs)+"]}}";
        var first=f.load(query,null);check(first.items().size()==20 && first.hasMore() && f.requests.size()==1);
        var last=f.load(new Query(AccountPlatform.NETEASE,View.FOLDER,10,2),null);
        check(last.items().size()==5 && last.items().getFirst().target().equals("21") && f.requests.size()==2);
    }
    private static void artwork() throws Exception {
        Fixture f=new Fixture(); var client=new CatalogClient(null,CLOCK,f);
        Item track=new Item(Kind.TRACK,"1","Song","",120000,true);
        Item folder=new Item(Kind.FOLDER,"10","List","",1,true);
        f.songs="{\"code\":200,\"songs\":["+song(1,"").replace("\"al\":{\"picUrl\":\"\"}","\"al\":{},\"album\":{\"picUrl\":\""+COVER+"\"}")+"]}";
        f.detail="{\"code\":200,\"playlist\":{\"id\":10,\"coverImgUrl\":\"\",\"picUrl\":\""+COVER+"\"}}";
        try(var request=new RequestScope(()->true)) {
            check(client.neteaseCover(track,request).equals(COVER));check(f.requests.size()==1 && f.requests.getFirst().getURL().getPath().equals("/api/song/detail"));
            check(client.neteaseCover(folder,request).equals(COVER));check(new String(f.playlistForm,java.nio.charset.StandardCharsets.US_ASCII).contains("n=0"));
            f.detail=f.detail.replace("\"id\":10","\"id\":11"); fails(()->client.neteaseCover(folder,request));checks++;
            f.songs=f.songs.replace("\"id\":1","\"id\":2");fails(()->client.neteaseCover(track,request));checks++;
            f.songs="{\"code\":200,\"songs\":[]}";check(client.neteaseCover(track,request)==null);
        }
    }
    private static String row(LibraryController library) throws Exception {
        for(int i=0;i<400;i++){var rows=(List<?>)library.snapshot().get("items");if(!rows.isEmpty())return (String)((Map<?,?>)rows.getFirst()).get("token");Thread.sleep(5);}throw new AssertionError("Timeout");
    }
    private static void revoke() throws Exception {
        var calls=new AtomicInteger();var started=new CountDownLatch(1);var release=new CountDownLatch(1);var blocked=new AtomicBoolean();
        try(var library=new LibraryController(p->null,()->true,t->{throw new AssertionError("No playback");},()->{},Clock.systemUTC(),a->(q,k,s,r)->
                new Page("List",List.of(new Item(Kind.TRACK,"1","Song","",1000,true,q.page()==1?null:COVER)),true,Instant.MAX),
                (a,q,r)->{throw new AssertionError("Not Bili");},(a,item,r)->{
                    calls.incrementAndGet(); if(blocked.get()){started.countDown();try{release.await();}catch(InterruptedException e){throw new java.io.IOException(e);}}return COVER;
                });var request=new RequestScope(()->true)) {
            library.browse(AccountPlatform.NETEASE,View.DISCOVER);String token=row(library);
            check(calls.get()==0);check(library.resolveCover(token,request).equals(COVER) && calls.get()==1);
            check(library.resolveCover(token,request,true)==null && calls.get()==1);
            library.turn(1);token=row(library);check(library.resolveCover(token,request).equals(COVER) && calls.get()==1);
            check(library.resolveCover(token,request,true).equals(COVER) && calls.get()==2);
            blocked.set(true);String active=token;var result=new AtomicReference<URI>();var error=new AtomicReference<Throwable>();
            Thread worker=Thread.ofPlatform().start(()->{try{result.set(library.resolveCover(active,request,true));}catch(Throwable e){error.set(e);}});
            try {check(started.await(2,TimeUnit.SECONDS));library.accountChanged(AccountPlatform.NETEASE);}finally{release.countDown();worker.join(2000);}
            check(!worker.isAlive() && result.get()==null && error.get()==null);
        } finally {release.countDown();}
    }
}
