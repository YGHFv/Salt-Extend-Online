package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

/** Complete 607-row seasons use one view request, not 31 archive pages; no real accounts/network. */
public final class BiliSeasonSnapshotTest {
    private static int checks;
    private static synchronized void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static String bvid(int i){return "BV"+String.format(Locale.ROOT,"%010d",i);}
    private static String arc(int i){return "{\"aid\":"+i+",\"bvid\":\""+bvid(i)+"\",\"title\":\"Video "+i+"\",\"duration\":60,\"state\":"+(i==607?-1:0)+",\"author\":{\"mid\":99,\"name\":\"Uploader\"},\"stat\":{\"view\":"+i+"},\"pic\":\"https://i0.hdslb.com/fixture.jpg\"}";}
    private static String episode(int i){return "{\"aid\":"+i+",\"bvid\":\""+bvid(i)+"\",\"season_id\":30,\"arc\":"+arc(i)+"}";}
    private static String snapshot(String mode){
        if(mode.equals("missing"))return "{\"code\":0,\"data\":{\"bvid\":\""+bvid(1)+"\"}}";
        if(mode.equals("risk"))return "{\"code\":-352}";
        var episodes=new ArrayList<String>();for(int i=1;i<=(mode.equals("partial")?606:607);i++)episodes.add(episode(i));
        if(mode.equals("order"))Collections.swap(episodes,0,1);
        if(mode.equals("duplicate"))episodes.set(606,episodes.get(605));
        if(mode.equals("arc"))episodes.set(606,episodes.get(606).replace("\"arc\":{\"aid\":607","\"arc\":{\"aid\":608"));
        return "{\"code\":0,\"data\":{\"bvid\":\""+bvid(mode.equals("seed")?2:1)+"\",\"ugc_season\":{\"id\":30,\"mid\":"+(mode.equals("owner")?100:99)+",\"ep_count\":607,\"sections\":[{\"episodes\":["+String.join(",",episodes)+"]}]}}}";
    }
    public static void main(String[] args)throws Exception {
        for(String mode:List.of("complete","missing","partial","order","duplicate","arc","seed","owner","risk","cancel")){
            var account=CatalogContractTest.account(AccountPlatform.BILIBILI);var fixture=new CatalogContractTest.Fixture();
            var archiveCalls=new AtomicInteger();var viewCalls=new AtomicInteger();var active=new AtomicBoolean(true);
            var cache=new CatalogPageCache(CatalogContractTest.CLOCK);var query=new Query(AccountPlatform.BILIBILI,View.COLLECTION,30,1,99);
            try(var request=new RequestScope(active::get)){
                var client=new CatalogClient(account,CatalogContractTest.CLOCK,(uri,form)->{
                    if(uri.getPath().endsWith("nav"))return fixture.open(uri,form);
                    if(uri.getPath().equals("/x/web-interface/view")){
                        viewCalls.incrementAndGet();check(form==null,"View used a write request");
                        if(mode.equals("cancel")){active.set(false);request.check();}
                        return new CatalogContractTest.Response(uri,snapshot(mode),List.of());
                    }
                    check(uri.getPath().endsWith("seasons_archives_list"),"Unexpected endpoint");archiveCalls.incrementAndGet();
                    var params=new HashMap<String,String>();for(String pair:uri.getQuery().split("&")){var parts=pair.split("=",2);params.put(parts[0],parts[1]);}
                    int page=Integer.parseInt(params.get("page_num"));var rows=new ArrayList<String>();
                    for(int i=(page-1)*20+1;i<=Math.min(page*20,607);i++)rows.add(arc(i));
                    return new CatalogContractTest.Response(uri,"{\"code\":0,\"data\":{\"meta\":{\"season_id\":30,\"mid\":99,\"name\":\"Fixture\",\"total\":607},\"page\":{\"total\":607},\"archives\":["+String.join(",",rows)+"]}}",List.of());
                });
                cache.load(account,query,"read","",request,(q,k,s,r)->client.load(q,r));
                try {
                    var full=cache.all(account,query,request,()->client.loadAll(query,request,cache,(done,total)->{}));
                    check(!mode.equals("risk") && !mode.equals("cancel"),"Revoked operation published a catalog");
                    check(full.items().size()==607 && full.totalCount()==607,"Incomplete result submitted");
                    for(int i=0;i<607;i++)check(full.items().get(i).target().equals(bvid(i+1)),"Snapshot changed upstream ordering");
                    if(mode.equals("complete")){
                        check(archiveCalls.get()==1 && viewCalls.get()==1,"Fast path fetched remaining pages");
                        check(full.items().stream().allMatch(i->i.subtitle().equals("Uploader") && i.owner()==99 && i.playCount()>0),"Snapshot lost author/stats");
                        check(!full.items().getLast().enabled(),"Deleted video made playable");
                        check(cache.all(account,query,request,()->{throw new AssertionError("Warm snapshot refetched");})==full,"Snapshot not cached");
                    }else check(archiveCalls.get()==31 && viewCalls.get()==1,"Incomplete/mismatched snapshot not safely paginated");
                }catch(CatalogFailure rejected){check(mode.equals("risk") && rejected.code()==-352 && archiveCalls.get()==1,"Risk rejection triggered fallback");}
                catch(java.util.concurrent.CancellationException cancelled){check(mode.equals("cancel") && archiveCalls.get()==1,"Cancellation triggered fallback");}
            }finally{account.retire();}
        }
        System.out.println("PASS: "+checks+" complete season fast-path/count/order/identity/author/risk/cancel/fallback/cache checks; synthetic HTTP only");
    }
}
