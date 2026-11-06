package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.time.Clock;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

public final class BiliDiscoveryContractTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if(!ok) throw new AssertionError("Discovery "+checks); }
    public static void main(String[] args) throws Exception {
        List<CatalogContractTest.Response> requests=new ArrayList<>();
        CatalogHttp.Opener fixture=(uri,form)->{
            check(form==null);
            String json;
            switch(uri.getPath()) {
                case "/x/v2/rank/region" -> {
                    check(uri.getHost().equals("app.bilibili.com") && uri.getQuery().equals("rid=3&type=all&pn=1&ps=100"));
                    List<String> rows=new ArrayList<>();
                    for(int i=1;i<=21;i++) rows.add("{\"param\":\""+i+"\",\"duration\":60,\"title\":\"<b>Music</b>\",\"name\":\"Uploader\",\"cover\":\"//i0.hdslb.com/music.jpg\"}");
                    rows.add("{\"param\":\"0\",\"duration\":60}"); rows.add("{\"param\":\"21\",\"duration\":60}");
                    json="{\"code\":0,\"data\":["+String.join(",",rows)+"]}";
                }
                case "/x/copyright-music-publicity/toplist/all_period" -> {
                    check(uri.getQuery().matches("list_type=[12]"));
                    json="{\"code\":0,\"data\":{\"list\":{\"2025\":[{\"ID\":500,\"publish_time\":1}],\"2026\":[{\"ID\":10,\"publish_time\":200},{\"ID\":11,\"publish_time\":200}]}}}";
                }
                case "/x/copyright-music-publicity/toplist/music_list" -> {
                    check(uri.getQuery().equals("list_id=11"));
                    json="{\"code\":0,\"data\":{\"list\":[{\"music_title\":\"Song &amp; MV\",\"singer\":\"Singer\",\"mv_bvid\":\"BV17x411w7KC\",\"creation_aid\":1,\"creation_duration\":60},{\"music_title\":\"Upload\",\"creation_aid\":2,\"creation_duration\":120},{\"music_title\":\"No source\"}]}}";
                }
                default -> throw new AssertionError("Unexpected discovery endpoint");
            }
            var response=new CatalogContractTest.Response(uri,json,List.of()); requests.add(response); return response;
        };
        var account=CatalogContractTest.account(AccountPlatform.BILIBILI);
        try {
            for(View view:List.of(View.DISCOVER,View.MUSIC_HOT,View.MUSIC_ORIGINAL)) try(var request=new RequestScope(()->true)) {
                Page page=new CatalogClient(account,Clock.systemUTC(),fixture).load(new Query(AccountPlatform.BILIBILI,view,0,1),request);
                if(view==View.DISCOVER) {
                    check(page.items().size()==20 && page.totalPages()==2 && page.totalCount()==21 && page.hasMore());
                    check(page.items().getFirst().target().equals("av1") && page.items().getFirst().subtitle().equals("Uploader") && page.items().getFirst().title().equals("Music"));
                    Page next=new CatalogClient(null,Clock.systemUTC(),fixture).load(new Query(AccountPlatform.BILIBILI,view,0,2),request);
                    check(next.items().size()==1 && next.items().getFirst().target().equals("av21") && !next.hasMore());
                } else {
                    check(page.items().size()==2 && !page.hasMore());
                    check(page.items().getFirst().target().equals("BV17x411w7KC") && page.items().getFirst().count()==0 && page.items().getFirst().title().equals("Song & MV"));
                    check(page.items().getLast().target().equals("av2") && page.items().getLast().count()==120000);
                }
            }
            check(requests.stream().allMatch(r->r.disconnected && r.getRequestProperty("Cookie")==null));
            try(var request=new RequestScope(()->true)) {
                CatalogHttp.Opener deny=(uri,form)->new CatalogContractTest.Response(uri,"{\"code\":-412,\"message\":\"SECRET\"}",List.of());
                try { new CatalogClient(null,Clock.systemUTC(),deny).load(new Query(AccountPlatform.BILIBILI,View.DISCOVER,0,1),request);throw new AssertionError(); }
                catch(CatalogFailure e){check(e.stage()==CatalogFailure.Stage.BILI_DISCOVER && e.code()==-412 && !e.toString().contains("SECRET"));}
            }
        } finally {account.retire();}
        System.out.println("PASS: "+checks+" NeriPlayer music discovery endpoints/latest period/MV identity/pagination/anonymous cookie isolation checks");
    }
}
