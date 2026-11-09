package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

public final class PlaylistWorkflowContractTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if(!ok) throw new AssertionError("Playlist workflow "+checks); }
    private static void ready(LibraryController library) throws Exception {
        long until=System.nanoTime()+3_000_000_000L;
        while((!library.snapshot().get("loaded").equals(true) || library.snapshot().get("loadingAll").equals(true)) && System.nanoTime()<until) Thread.sleep(10);
        check(library.snapshot().get("loaded").equals(true) && !library.snapshot().get("loadingAll").equals(true));
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(LibraryController library) { return (List<Map<String,Object>>)library.snapshot().get("items"); }
    public static void main(String[] args) throws Exception {
        AtomicInteger requests=new AtomicInteger();
        try(LibraryController library=new LibraryController(p->null,()->true,t->{},()->{},Clock.systemUTC(),account->(q,k,s,r)->{
            requests.incrementAndGet();
            if(q.view()!=View.FOLDER) return new Page(q.platform().name(),List.of(new Item(Kind.FOLDER,"10","Collection","Creator",45,true)),false,Instant.now().plusSeconds(300),1,1);
            List<Item> items=new ArrayList<>();
            for(int i=(q.page()-1)*20+1;i<=Math.min(q.page()*20,45);i++) items.add(new Item(Kind.TRACK,""+i,String.format("Song %02d",i),"Artist",i*1000,true,null,0,
                    i==1 || i==3 ? -1 : i==2 ? 0 : i>=44 ? 9000000000L : i));
            return new Page("Collection",items,q.page()<3,Instant.now().plusSeconds(300),3,45);
        })) {
            library.pageLimit(20); library.browse(AccountPlatform.BILIBILI,View.MINE); ready(library);
            library.choose((String)rows(library).getFirst().get("token")); ready(library);
            String token=(String)rows(library).getFirst().get("token");
            check(library.snapshot().get("totalCount").equals(45L));
            library.browse(AccountPlatform.NETEASE,View.DISCOVER); ready(library);
            int count=requests.get(); library.snapshot(AccountPlatform.BILIBILI);
            check(rows(library).getFirst().get("token").equals(token) && requests.get()==count);
            library.turn(1); ready(library); check(rows(library).getFirst().get("title").equals("Song 21"));
            library.snapshot(AccountPlatform.NETEASE); check(rows(library).getFirst().get("title").equals("Collection"));
            library.snapshot(AccountPlatform.BILIBILI); check(library.snapshot().get("page").equals(2));
            library.filter("Song 45"); ready(library);
            check(rows(library).size()==1 && rows(library).getFirst().get("title").equals("Song 45") && library.snapshot().get("totalPages").equals(1));
            count=requests.get(); library.filter(""); library.sort(Sort.REVERSE); ready(library);
            check(requests.get()==count && rows(library).getFirst().get("title").equals("Song 45"));
            library.filter("Song 45"); library.requestPlaylist(); var playlist=library.takePlaylist();
            check(playlist!=null && playlist.items().size()==45 && playlist.items().getFirst().target().equals("45"));
            check(library.takePlaylist()==null);
            library.filter(""); library.sort(Sort.TITLE); check(rows(library).getFirst().get("title").equals("Song 01"));
            count=requests.get(); library.sort(Sort.PLAY_COUNT); ready(library);
            check(requests.get()==count && library.snapshot().get("page").equals(1));
            check(rows(library).getFirst().get("title").equals("Song 44") && rows(library).get(1).get("title").equals("Song 45"));
            library.turn(1); ready(library); check(rows(library).getFirst().get("title").equals("Song 25"));
            library.turn(1); ready(library);
            check(rows(library).stream().map(i->i.get("title")).toList().equals(List.of("Song 05","Song 04","Song 02","Song 01","Song 03")));
            library.filter("Song 45"); check(rows(library).size()==1);
            library.requestPlaylist(); playlist=library.takePlaylist();
            check(playlist!=null && playlist.items().size()==45 && playlist.items().getFirst().target().equals("44"));
            check(playlist.items().get(1).target().equals("45") && playlist.items().getLast().target().equals("3"));
            library.filter(""); library.sort(Sort.DEFAULT); check(rows(library).getFirst().get("title").equals("Song 01"));
            library.sort(Sort.DURATION); check(rows(library).getFirst().get("title").equals("Song 01"));
            library.turn(1); ready(library); count=requests.get();
            library.requestPlaylist(); library.reload(); ready(library);
            check(requests.get()==count+3 && library.snapshot().get("page").equals(2));
            check(library.snapshot().get("sort").equals("DURATION") && rows(library).getFirst().get("title").equals("Song 21"));
            check(library.takePlaylist()==null && !library.currentRow(token));
            library.filter("Song 45"); ready(library); count=requests.get(); library.reload(); ready(library);
            check(requests.get()==count+3 && library.snapshot().get("filter").equals("Song 45") && rows(library).size()==1);
            check(library.snapshot().get("totalCount").equals(45L));
            library.accountChanged(AccountPlatform.BILIBILI); check(rows(library).isEmpty() && !library.currentRow(token));
            library.snapshot(AccountPlatform.NETEASE); check(rows(library).size()==1);
        }
        searchAndRetry();
        failedFullLoad();
        playbackDiagnostics();
        System.out.println("PASS: "+checks+" platform-local navigation, whole-list filter/sort, search/retry, unfiltered replacement intent and revocation checks");
    }
    private static void playbackDiagnostics() throws Exception {
        var cases=new LinkedHashMap<Throwable,String>();
        cases.put(new io.github.yghfv.saltneri.plugin.qqmusic.QqMusicClient.MediaRejected(
                io.github.yghfv.saltneri.plugin.qqmusic.QqMusicClient.MediaRejected.Reason.PATH),"QQ_MEDIA_PATH");
        cases.put(new io.github.yghfv.saltneri.plugin.qqmusic.QqMusicClient.MediaRejected(
                io.github.yghfv.saltneri.plugin.qqmusic.QqMusicClient.MediaRejected.Reason.AUTHORIZATION),"QQ_MEDIA_AUTHORIZATION");
        cases.put(new io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.Rejected(1000),"QQ_API=1000");
        cases.put(new io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.Rejected(403,true),"QQ_HTTP=403");
        cases.put(new io.github.yghfv.saltneri.plugin.qqmusic.QqMusicClient.TrackUnavailable(104003),"QQ_TRACK_UNAVAILABLE=104003");
        cases.put(AuthFailure.code(AuthFailure.Stage.QQ_VERIFY,AuthFailure.Reason.API,1000),"QQ_VERIFY/API=1000");
        cases.put(new io.github.yghfv.saltneri.plugin.net.MediaRangeProbe.Rejected(403),"MEDIA_HTTP=403");
        cases.put(new java.net.SocketTimeoutException("URL_SECRET"),"NETWORK_TIMEOUT");
        cases.put(new java.io.IOException("COOKIE_SECRET https://secret.invalid"),"IOException");
        try(var player=new OnlinePlaybackController(p->null,()->true,()->true,(audio,active)->{throw new AssertionError("No playback");})) {
            for(var test:cases.entrySet()) {
                check(OnlinePlaybackController.playbackFailureReason(test.getKey()).equals(test.getValue()));
                var list=new LibraryController.Playlist(AccountPlatform.QQMUSIC,
                        List.of(new Item(Kind.TRACK,"00123456789abc","Fixture","Artist",120000,true)),null);
                player.playPlaylist(list,()->{},(playlist,active)->{throw (Exception)test.getKey();});
                long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);String status="";
                while(System.nanoTime()<until) {
                    status=String.valueOf(player.snapshot().get("error"));
                    if(status.contains("PLAYLIST/"))break;Thread.sleep(10);
                }
                check(status.contains("[PLAYLIST/"+test.getValue()+"]") && !status.contains("SECRET") && !status.contains("https://"));
            }
        }
    }
    private static void failedFullLoad() throws Exception {
        var fail=new AtomicBoolean();
        var pause=new AtomicBoolean();
        var started=new java.util.concurrent.CountDownLatch(1);
        var resume=new java.util.concurrent.CountDownLatch(1);
        try(var library=new LibraryController(p->null,()->true,t->{},()->{},Clock.systemUTC(),account->(q,k,s,r)->{
            if(q.view()!=View.FOLDER) return new Page("Folders",List.of(new Item(Kind.FOLDER,"10","Folder","Author",21,true)),false,Instant.MAX,1);
            if(fail.get()) throw new java.io.IOException("Fixture complete failure");
            if(pause.get() && k.equals("all")) { started.countDown(); resume.await(3,java.util.concurrent.TimeUnit.SECONDS); r.check(); }
            var items=new ArrayList<Item>();
            for(int i=(q.page()-1)*20+1;i<=Math.min(q.page()*20,21);i++) items.add(new Item(Kind.TRACK,""+i,"Track "+i,"Author",0,true));
            return new Page("Folder",items,q.page()==1,Instant.MAX,2,21);
        })) {
            library.pageLimit(20); library.browse(AccountPlatform.NETEASE,View.MINE); ready(library);
            library.choose((String)rows(library).getFirst().get("token")); ready(library);
            fail.set(true); library.requestPlaylist();
            long until=System.nanoTime()+3_000_000_000L;
            while(library.snapshot().get("error").equals("") && System.nanoTime()<until) Thread.sleep(10);
            check(!library.snapshot().get("error").equals("") && rows(library).size()==20);
            check(library.takePlaylist()==null);
            fail.set(false); library.turn(1); ready(library);
            check(library.snapshot().get("page").equals(2) && rows(library).getFirst().get("title").equals("Track 21"));
            check(library.snapshot().get("error").equals(""));
            pause.set(true); library.requestPlaylist();
            check(started.await(3,java.util.concurrent.TimeUnit.SECONDS));
            check(rows(library).size()==1 && library.snapshot().get("page").equals(2));
            library.sort(Sort.REVERSE);
            check(rows(library).size()==1 && library.snapshot().get("page").equals(2));
            resume.countDown(); ready(library);
            check(rows(library).getFirst().get("title").equals("Track 21") && library.snapshot().get("error").equals(""));
        } finally { resume.countDown(); }
    }
    private static void searchAndRetry() throws Exception {
        var requests=new AtomicInteger(); var queried=new AtomicReference<Query>(); var fail=new AtomicBoolean();
        var shrink=new AtomicBoolean();
        try(LibraryController library=new LibraryController(p->null,()->true,t->{throw new AssertionError("Search/refresh played");},()->{},Clock.systemUTC(),account->(q,k,s,r)->{
            requests.incrementAndGet(); queried.set(q);
            if(fail.get()) throw CatalogFailure.code(CatalogFailure.Stage.NET_SEARCH,CatalogFailure.Reason.HTTP,429);
            if(q.view()!=View.SEARCH) return new Page("Bili folder",List.of(new Item(Kind.FOLDER,"10","Folder","",1,true)),false,Instant.MAX,1);
            var fixture=new CatalogContractTest.Fixture();
            if(shrink.get()) fixture.netSearch=q.page()>1 ? "{\"code\":200,\"result\":{\"songCount\":1,\"songs\":[]}}"
                    : fixture.netSearch.replace("\"songCount\":21","\"songCount\":1");
            return new CatalogClient(null,CatalogContractTest.CLOCK,fixture).load(q,r);
        })) {
            library.pageLimit(20); library.browse(AccountPlatform.BILIBILI,View.MINE); ready(library); String biliToken=(String)rows(library).getFirst().get("token");
            library.search(AccountPlatform.NETEASE,"music"); ready(library);
            check(queried.get().platform()==AccountPlatform.NETEASE && queried.get().keyword().equals("music"));
            check(rows(library).size()==1 && rows(library).getFirst().get("title").equals("Free"));
            check(library.snapshot().get("totalPages").equals(2) && library.snapshot().get("more").equals(true));
            library.turn(1); ready(library); var second=queried.get(); String token=(String)rows(library).getFirst().get("token");
            check(second.page()==2 && second.keyword().equals("music") && library.snapshot().get("more").equals(false));
            int before=requests.get(); library.snapshot(AccountPlatform.BILIBILI);
            check(rows(library).getFirst().get("token").equals(biliToken));
            library.snapshot(AccountPlatform.NETEASE); check(rows(library).getFirst().get("token").equals(token) && requests.get()==before);
            fail.set(true); library.reload();
            long until=System.nanoTime()+3_000_000_000L;
            while(library.snapshot().get("error").toString().isEmpty() && System.nanoTime()<until) Thread.sleep(10);
            check(library.snapshot().get("error").toString().contains("NET_SEARCH/HTTP=429"));
            check(rows(library).isEmpty() && !library.currentRow(token) && requests.get()==before+1);
            Thread.sleep(100); check(requests.get()==before+1 && library.takePlaylist()==null);
            fail.set(false); library.reload(); ready(library);
            check(queried.get().equals(second) && rows(library).size()==1 && library.snapshot().get("error").equals(""));
            shrink.set(true); before=requests.get(); library.reload(); ready(library);
            check(requests.get()==before+2 && queried.get().page()==1 && library.snapshot().get("page").equals(1));
            check(library.snapshot().get("totalPages").equals(1) && library.snapshot().get("more").equals(false) && rows(library).size()==1);
            library.accountChanged(AccountPlatform.NETEASE); check(rows(library).isEmpty());
            try { library.reload(); throw new AssertionError("Revoked query refreshed"); } catch(IllegalStateException expected) { checks++; }
            library.snapshot(AccountPlatform.BILIBILI); check(library.currentRow(biliToken));
        }
    }
}
