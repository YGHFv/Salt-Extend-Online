package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

public final class PlaylistWorkflowContractTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if(!ok) throw new AssertionError("Playlist workflow "+checks); }
    private static void ready(LibraryController library) throws Exception {
        long until=System.nanoTime()+3_000_000_000L;
        while(!library.snapshot().get("loaded").equals(true) && System.nanoTime()<until) Thread.sleep(10);
        check(library.snapshot().get("loaded").equals(true));
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(LibraryController library) { return (List<Map<String,Object>>)library.snapshot().get("items"); }
    public static void main(String[] args) throws Exception {
        AtomicInteger requests=new AtomicInteger();
        try(LibraryController library=new LibraryController(p->null,()->true,t->{},()->{},Clock.systemUTC(),account->(q,k,s,r)->{
            requests.incrementAndGet();
            if(q.view()!=View.FOLDER) return new Page(q.platform().name(),List.of(new Item(Kind.FOLDER,"10","Collection","Creator",45,true)),false,Instant.now().plusSeconds(300),1,1);
            List<Item> items=new ArrayList<>();
            for(int i=(q.page()-1)*20+1;i<=Math.min(q.page()*20,45);i++) items.add(new Item(Kind.TRACK,""+i,String.format("Song %02d",i),"Artist",i*1000,true));
            return new Page("Collection",items,q.page()<3,Instant.now().plusSeconds(300),3,45);
        })) {
            library.browse(AccountPlatform.BILIBILI,View.MINE); ready(library);
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
            library.accountChanged(AccountPlatform.BILIBILI); check(rows(library).isEmpty() && !library.currentRow(token));
            library.snapshot(AccountPlatform.NETEASE); check(rows(library).size()==1);
        }
        System.out.println("PASS: "+checks+" platform-local navigation, whole-list filter/sort, unfiltered replacement intent and revocation checks");
    }
}
