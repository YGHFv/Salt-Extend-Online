package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

/** Fixed upstream pages, presentation windows and incremental navigation; no external APIs or playback. */
public final class CatalogPagingContractTest {
    private static int checks;
    private static void check(boolean ok) {checks++;if(!ok)throw new AssertionError("Paging check "+checks);}
    private static void await(BooleanSupplier ready) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!ready.getAsBoolean() && System.nanoTime()<end)Thread.sleep(5);
        check(ready.getAsBoolean());
    }
    private static Item item(int id) {return new Item(Kind.TRACK,""+id,String.format(Locale.ROOT,"Song %03d",id),"Artist",1000,true,null,42);}
    private static Page page(Query q,int total,boolean known) {
        int start=(q.page()-1)*20,end=Math.min(total,start+20);
        var items=new ArrayList<Item>();for(int i=start;i<end;i++)items.add(item(q.reverse()?total-i:i+1));
        return new Page("Songs",items,end<total,Instant.MAX,known?Math.max(1,(total+19)/20):0,known?total:-1);
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(LibraryController c) {return (List<Map<String,Object>>)(List<?>)c.snapshot().get("items");}
    private static String token(LibraryController c) {return (String)c.snapshot().get("loadToken");}
    public static void main(String[] args) throws Exception {
        for(String invalid:Arrays.asList(null,"","-1","200","unlimited","020"," 30"))check(CatalogPaging.parse(invalid)==30);
        for(int option:new int[]{0,20,30,50,60,100})check(CatalogPaging.parse(""+option)==option);
        windows();navigation();retryAndCancel();failedFullPrefix();
        System.out.println("PASS: "+checks+" configurable windows/30-item default/incremental append/stable rows/search/sort/play-all/retry/revocation checks; fixtures only");
    }
    private static void windows() throws Exception {
        try(var scope=new RequestScope(()->true)) {
            for(int width:new int[]{20,30,50,60,100})for(int total:new int[]{0,1,19,20,21,29,30,31,59,60,61,99,100,101,137,607})for(boolean known:List.of(true,false)) {
                var gathered=new ArrayList<Item>();int limit=Math.max(1,(total+width-1)/width);
                for(int p=1;p<=limit;p++) {
                    var q=new Query(AccountPlatform.NETEASE,View.SEARCH,0,p,0,"fixture");
                    var requests=new AtomicInteger();
                    var result=CatalogPaging.load(q,width,scope,raw->{requests.incrementAndGet();check(raw.keyword().equals("fixture"));return page(raw,total,known);});
                    check(result.items().size()<=width && result.hasMore()==(p<limit));
                    check(requests.get()<=(width+38)/20);
                    if(known || !result.hasMore())check(result.totalPages()==limit);
                    gathered.addAll(result.items());
                }
                check(gathered.size()==total);
                for(int i=0;i<total;i++)check(gathered.get(i).target().equals(""+(i+1)));
            }
            // Filtering upstream types can leave short interior pages; slot overlap must not lose or duplicate rows.
            var seen=new ArrayList<String>();
            for(int p=1;p<=3;p++) {
                var result=CatalogPaging.load(new Query(AccountPlatform.BILIBILI,View.SAVED,0,p),30,scope,q->{
                    Page raw=page(q,65,true);return new Page(raw.title(),raw.items().stream().filter(i->Integer.parseInt(i.target())%3==0).toList(),raw.hasMore(),Instant.MAX,4,65);
                });seen.addAll(result.items().stream().map(Item::target).toList());
            }
            check(seen.size()==21 && new HashSet<>(seen).size()==21);
        }
    }
    private static void navigation() throws Exception {
        for(AccountPlatform platform:List.of(AccountPlatform.BILIBILI,AccountPlatform.NETEASE,AccountPlatform.QQMUSIC)) {
            var calls=new AtomicInteger();
            try(var c=new LibraryController(p->null,()->true,t->{throw new AssertionError("No play");},()->{},Clock.systemUTC(),a->(q,k,s,r)->{
                calls.incrementAndGet();
                if(q.view()==View.MINE)return new Page("Lists",List.of(new Item(Kind.FOLDER,"10","List","Artist",137,true)),false,Instant.MAX,1,1);
                return page(q,137,true);
            })) {
                check(c.snapshot().get("pageLimit").equals(30));
                c.browse(platform,View.DISCOVER);await(()->rows(c).size()==30);
                check(calls.get()==2 && rows(c).getLast().get("title").equals("Song 030") && c.snapshot().get("totalPages").equals(5));
                c.turn(1);await(()->rows(c).size()==30);check(rows(c).getFirst().get("title").equals("Song 031"));
                c.pageLimit(0);await(()->rows(c).size()==30);check(c.snapshot().get("infinite").equals(true));
                String first=(String)rows(c).getFirst().get("token"),stale=token(c);
                c.loadMore(platform,stale);c.loadMore(platform,stale);await(()->rows(c).size()==60);
                int before=calls.get();c.loadMore(platform,stale);check(calls.get()==before && c.currentRow(first));
                check(rows(c).getLast().get("title").equals("Song 060") && c.snapshot().get("page").equals(1));
                for(int size:new int[]{90,120,137}) {c.loadMore(platform,token(c));await(()->rows(c).size()==size);}
                check(!Boolean.TRUE.equals(c.snapshot().get("more")) && c.currentRow(first));
                c.browse(platform,View.MINE);await(()->rows(c).size()==1);c.choose((String)rows(c).getFirst().get("token"));await(()->rows(c).size()==30);
                c.loadMore(platform,token(c));await(()->rows(c).size()==60);
                if(platform==AccountPlatform.BILIBILI) {
                    c.author((String)rows(c).getFirst().get("token"));await(()->rows(c).size()==30);
                    c.back();check(rows(c).size()==60 && c.snapshot().get("view").equals("FOLDER"));
                }
                c.filter("Song 090");await(()->rows(c).size()==1 && c.snapshot().get("loadingAll").equals(false));
                check(rows(c).getFirst().get("title").equals("Song 090"));
                c.sort(Sort.REVERSE);c.requestPlaylist();var playlist=c.takePlaylist();
                check(playlist!=null && playlist.items().size()==137 && playlist.items().getFirst().target().equals("137"));
                c.pageLimit(50);check(rows(c).size()==1 && c.snapshot().get("filter").equals("Song 090") && c.snapshot().get("sort").equals("REVERSE"));
                c.filter("");check(rows(c).size()==50 && rows(c).getFirst().get("title").equals("Song 137"));
                c.pageLimit(0);check(rows(c).size()==30);first=(String)rows(c).getFirst().get("token");
                c.loadMore(platform,token(c));check(rows(c).size()==60 && c.currentRow(first));
                c.search(platform,"remote search");await(()->rows(c).size()==30);check(c.snapshot().get("view").equals("SEARCH"));
                c.loadMore(platform,token(c));await(()->rows(c).size()==60);
            }
        }
    }
    private static void retryAndCancel() throws Exception {
        var fail=new AtomicBoolean();var block=new AtomicBoolean();var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var c=new LibraryController(p->null,()->true,t->{throw new AssertionError("No play");},()->{},Clock.systemUTC(),a->(q,k,s,r)->{
            if(q.page()==3 && fail.get())throw new java.io.IOException("Synthetic unavailable");
            if(q.page()==3 && block.get()) {started.countDown();release.await(3,TimeUnit.SECONDS);r.check();}
            return page(q,137,true);
        })) {
            c.pageLimit(0);c.browse(AccountPlatform.NETEASE,View.DISCOVER);await(()->rows(c).size()==30);
            String first=(String)rows(c).getFirst().get("token");fail.set(true);c.loadMore(AccountPlatform.NETEASE,token(c));
            await(()->!c.snapshot().get("error").toString().isEmpty());check(rows(c).size()==30 && c.currentRow(first) && !Boolean.TRUE.equals(c.snapshot().get("loadingMore")));
            fail.set(false);c.loadMore(AccountPlatform.NETEASE,token(c));await(()->rows(c).size()==60);check(c.currentRow(first));
            c.reload();await(()->rows(c).size()==30);block.set(true);String old=token(c);c.loadMore(AccountPlatform.NETEASE,old);check(started.await(2,TimeUnit.SECONDS));
            c.accountChanged(AccountPlatform.NETEASE);release.countDown();check(rows(c).isEmpty());c.loadMore(AccountPlatform.NETEASE,old);check(rows(c).isEmpty());
            block.set(false);c.browse(AccountPlatform.NETEASE,View.DISCOVER);await(()->rows(c).size()==30);
            c.pageLimit(100);await(()->rows(c).size()==100);check(c.snapshot().get("totalPages").equals(2));
        }finally{release.countDown();}
    }
    private static void failedFullPrefix() throws Exception {
        var fail=new AtomicBoolean(true);
        try(var c=new LibraryController(p->null,()->true,t->{throw new AssertionError("No play");},()->{},Clock.systemUTC(),a->(q,k,s,r)->{
            if(q.view()==View.MINE)return new Page("Lists",List.of(new Item(Kind.FOLDER,"10","List","Artist",137,true)),false,Instant.MAX,1,1);
            if(k.equals("all") && fail.get())throw new java.io.IOException("Synthetic full-list failure");
            return page(q,137,true);
        })) {
            c.pageLimit(0);c.browse(AccountPlatform.NETEASE,View.MINE);await(()->rows(c).size()==1);
            c.choose((String)rows(c).getFirst().get("token"));await(()->rows(c).size()==30);
            c.loadMore(AccountPlatform.NETEASE,token(c));await(()->rows(c).size()==60);
            String first=(String)rows(c).getFirst().get("token");c.filter("Song 090");
            await(()->!c.snapshot().get("error").toString().isEmpty());check(rows(c).size()==60 && c.currentRow(first));
            c.loadMore(AccountPlatform.NETEASE,token(c));await(()->rows(c).size()==90);
            check(rows(c).getLast().get("title").equals("Song 090") && rows(c).stream().map(i->i.get("title")).distinct().count()==90);
            fail.set(false);c.filter("Song 090");await(()->rows(c).size()==1);check(rows(c).getFirst().get("title").equals("Song 090"));
        }
    }
}
