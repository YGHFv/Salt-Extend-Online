package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.kugou.*;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;
import static io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.*;

/** Read-only KA Music catalog mappings; stable global collection IDs stay distinct from local IDs. */
public final class KugouCatalog {
    private final AccountCredentials account;
    private final Clock clock;
    private final KugouHttp http;
    private final Cache cache;
    public static final class Cache {
        private AccountCredentials owner;
        private long revision;
        private record Batch(List<Item> items,boolean more,long total,java.time.Instant until) {}
        private final Map<String,Batch> batches=new LinkedHashMap<>();
        public synchronized void clear() {owner=null;revision++;batches.clear();}
    }
    public KugouCatalog(AccountCredentials account,Clock clock,KugouHttp http) {this(account,clock,http,new Cache());}
    public KugouCatalog(AccountCredentials account,Clock clock,KugouHttp http,Cache cache) {this.account=account;this.clock=clock;this.http=http;this.cache=cache;}
    public Page load(Query q,RequestScope request) throws IOException {
        request.check();
        if(account!=null && (account.platform()!=AccountPlatform.KUGOU || !account.isActive()))throw KugouHttp.failure("LOGIN_REQUIRED");
        if((q.view()==View.MINE || q.view()==View.SAVED) && account==null)throw KugouHttp.failure("LOGIN_REQUIRED");
        if(q.view()==View.SEARCH) {
            int start=(q.page()-1)*20,end=start+20;
            var items=new ArrayList<Item>();boolean more=false;
            for(int index=start/30+1;index<=(end-1)/30+1;index++) {
                Cache.Batch batch=searchBatch(q,index,request);
                int offset=(index-1)*30,from=Math.min(batch.items().size(),Math.max(0,start-offset)),to=Math.min(batch.items().size(),end-offset);
                if(to>from)items.addAll(batch.items().subList(from,to));
                more=to<batch.items().size() || batch.more();
                if(!batch.more())break;
            }
            return page("搜索："+q.keyword(),items,more,-1);
        }
        if(q.view()==View.FOLDER) {
            String id=q.resource().isEmpty()?Long.toString(q.folder()):q.resource();
            var data=object(http.get("/playlist/track/all",Map.of("id",id,"page",q.page(),"pagesize",20),account,request));
            var rows=KugouHttp.rows(data,"songs");long total=number(data.get("count"),-1);
            if(rows.size()>20 || total<0 || total>10000 || q.page()*20L<total && rows.isEmpty())throw KugouHttp.failure("PLAYLIST_PAGE");
            return page("酷狗音乐歌单",songs(rows),q.page()*20L<total,total);
        }
        boolean mine=q.view()==View.MINE || q.view()==View.SAVED;
        if(!mine && q.view()!=View.DISCOVER)throw KugouHttp.failure("VIEW");
        if(mine) {
            // Created and saved playlists share upstream pages. Pack the filtered
            // rows before applying UI offsets, otherwise a mixed page loses cards.
            int start=(q.page()-1)*20,end=start+20;
            var matching=new ArrayList<Item>();var seen=new HashSet<String>();
            for(int index=1;index<=500;index++) {
                Cache.Batch batch=batch(q,index,true,request);
                for(Item item:batch.items())if(seen.add(item.target()))matching.add(item);
                if(matching.size()>=end || !batch.more()) {
                    int from=Math.min(start,matching.size()),to=Math.min(end,matching.size());
                    return page(q.view()==View.MINE?"我的歌单":"收藏歌单",matching.subList(from,to),
                            matching.size()>end || batch.more(),batch.more()?-1:matching.size());
                }
            }
            throw KugouHttp.failure("PLAYLIST_BOUND");
        }
        // Discovery serves a fixed 35-row API batch. Slice those raw slots into UI pages
        // so presentation windows neither discard cards nor make duplicate API requests.
        int batchWidth=35,start=(q.page()-1)*20,end=start+20;
        var visible=new ArrayList<Item>();boolean more=false;
        for(int index=start/batchWidth+1;index<=(end-1)/batchWidth+1;index++) {
            Cache.Batch batch=batch(q,index,mine,request);
            int offset=(index-1)*batchWidth,from=Math.min(batch.items().size(),Math.max(0,start-offset)),to=Math.min(batch.items().size(),end-offset);
            if(to>from)visible.addAll(batch.items().subList(from,to));
            more=to<batch.items().size() || batch.more();
            if(!batch.more())break;
        }
        return page(mine?"我的歌单":"推荐歌单",visible,more,-1);
    }
    private Cache.Batch searchBatch(Query q,int page,RequestScope request) throws IOException {
        String key="SEARCH:"+q.keyword()+":"+page;long revision;
        synchronized(cache) {
            if(cache.owner!=account) {cache.clear();cache.owner=account;}
            revision=cache.revision;Cache.Batch hit=cache.batches.get(key);
            if(hit!=null && clock.instant().isBefore(hit.until()))return hit;
        }
        Object data=http.get("/search",Map.of("keywords",q.keyword(),"page",page,"pagesize",30,"type","song"),account,request);
        var rows=KugouHttp.rows(data,"songs","song","lists");if(rows.size()>30)throw KugouHttp.failure("PAGE_BOUND");
        var batch=new Cache.Batch(songs(rows),rows.size()==30,-1,clock.instant().plusSeconds(120));
        request.check();
        synchronized(cache) {
            if(revision!=cache.revision || cache.owner!=account || account!=null && !account.isActive())throw KugouHttp.failure("ACCOUNT_CHANGED");
            cache.batches.put(key,batch);
            while(cache.batches.size()>32)cache.batches.remove(cache.batches.keySet().iterator().next());
        }
        return batch;
    }
    private Cache.Batch batch(Query q,int page,boolean mine,RequestScope request) throws IOException {
        String key=q.view()+":"+page;long revision;
        synchronized(cache) {
            if(cache.owner!=account) {cache.clear();cache.owner=account;}
            revision=cache.revision;
            Cache.Batch hit=cache.batches.get(key);
            if(hit!=null && clock.instant().isBefore(hit.until()))return hit;
        }
        var data=object(http.get(mine?"/user/playlist":"/top/playlist",Map.of("page",page,"pagesize",20),mine?account:null,request));
        var rows=KugouHttp.rows(data,mine?"info":"special_list");if(rows.size()>1000)throw KugouHttp.failure("PAGE_BOUND");
        var items=new ArrayList<Item>();var seen=new HashSet<String>();
        if(rows.size()>(mine?20:35))throw KugouHttp.failure("PAGE_BOUND");
        for(Object value:rows) {
            var row=object(value);
            if(mine) {
                long type=number(row.get("type"),-1),isDefault=number(row.getOrDefault("is_def",row.get("is_default")),-1);
                boolean created=type==0 || type!=1 && (isDefault>=0 || account.userId().equals(String.valueOf(row.get("list_create_userid"))));
                if(q.view()==View.MINE && !created || q.view()==View.SAVED && created)continue;
            }
            String target=mine?text(row,"list_create_gid","global_collection_id"):text(row,"global_collection_id");
            if(target.isEmpty())target=String.valueOf(row.getOrDefault("listid",row.getOrDefault("specialid","")));
            if(!validCollection(target) || !seen.add(target))continue;
            String title=text(row,"specialname","name","title");if(title.isBlank())continue;
            items.add(new Item(Kind.FOLDER,target,title,text(row,"nickname","list_create_username"),
                    number(row.getOrDefault("count",row.get("songcount")),-1),true,
                    CoverSource.parse(AccountPlatform.KUGOU,text(row,"flexible_cover","imgurl","pic","cover").replace("{size}","400"))));
        }
        boolean more=number(data.get("has_next"),rows.size()>=20?1:0)==1;
        var batch=new Cache.Batch(List.copyOf(items),more,number(data.get("list_count"),-1),clock.instant().plusSeconds(120));
        request.check();
        synchronized(cache) {
            if(revision!=cache.revision || cache.owner!=account || account!=null && !account.isActive())throw KugouHttp.failure("ACCOUNT_CHANGED");
            cache.batches.put(key,batch);
            while(cache.batches.size()>32)cache.batches.remove(cache.batches.keySet().iterator().next());
        }
        return batch;
    }
    public CatalogClient.CollectionContents loadAll(Query q,RequestScope request) throws IOException {
        var items=new ArrayList<Item>();var seen=new HashSet<String>();long total=-1;
        for(int index=1;index<=500;index++) {
            var page=load(new Query(q.platform(),View.FOLDER,q.folder(),index,0,"",false,Sort.DEFAULT,q.resource()),request);
            if(total<0)total=page.totalCount();
            if(page.totalCount()!=total)throw KugouHttp.failure("PLAYLIST_CHANGED");
            for(Item item:page.items())if(!seen.add(item.target()))throw KugouHttp.failure("DUPLICATE_PAGE");else items.add(item);
            if(!page.hasMore()) {
                if(items.size()!=total)throw KugouHttp.failure("PLAYLIST_INCOMPLETE");
                return new CatalogClient.CollectionContents(page.title(),items,total);
            }
        }
        throw KugouHttp.failure("PLAYLIST_BOUND");
    }
    public static boolean validCollection(String id) {return id!=null && (id.matches("[1-9][0-9]{0,18}") || id.matches("collection_[0-9]+_[0-9]+_[0-9]+_[0-9]+")) && id.length()<=128;}
    private static List<Item> songs(List<Object> rows) {
        var items=new ArrayList<Item>();var seen=new HashSet<String>();
        for(Object value:rows)try {
            var song=KugouClient.parseSong(object(value));
            if(seen.add(song.hash()))items.add(new Item(Kind.TRACK,song.hash(),song.title(),song.artist(),song.duration(),true,song.cover()));
        } catch(IOException | IllegalArgumentException malformed) {}
        return items;
    }
    private Page page(String title,List<Item> items,boolean more,long total) {return new Page(title,items,more,clock.instant().plusSeconds(120),
            total<0?0:(int)Math.max(1,(total+19)/20),total);}
}
