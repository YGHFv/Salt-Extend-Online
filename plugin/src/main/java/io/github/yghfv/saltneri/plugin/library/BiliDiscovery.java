package io.github.yghfv.saltneri.plugin.library;

/* Music discovery protocol adapted from NeriPlayer fd7fdb7 (GPL-3.0-or-later).
 * Mobile rid=3 chart, latest copyright HOT/ORIGINAL period; no frozen Web ranking/v2 feed. */
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.net.*;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

final class BiliDiscovery {
    private record Chart(String title, List<Item> items, Instant until) {
        Chart { items = List.copyOf(items); }
        Page page(Query query) {
            int pages=Math.max(1,(items.size()+19)/20),from=Math.min(items.size(),(query.page()-1)*20);
            return new Page(title,items.subList(from,Math.min(items.size(),from+20)),query.page()<pages,until,pages,items.size());
        }
    }
    /** These APIs already return a complete bounded chart, not a remote page. Retain it for local paging. */
    static final class Cache {
        private final Clock clock;
        private final Map<View,Chart> charts = new EnumMap<>(View.class);
        private long revision;
        Cache(Clock clock) { this.clock = clock; }
        synchronized void clear() { revision++; charts.clear(); }
        Page load(Query query, CatalogHttp http, RequestScope request) throws IOException {
            request.check(); long captured;
            synchronized (this) {
                captured = revision;
                Chart hit = charts.get(query.view());
                if (hit != null && clock.instant().isBefore(hit.until())) return hit.page(query);
                charts.remove(query.view());
            }
            Chart result = fetch(query,http,request,clock); request.check();
            synchronized (this) {
                request.check(); if (revision == captured) charts.put(query.view(),result);
            }
            return result.page(query);
        }
    }
    private static Object data(CatalogHttp http,String url,RequestScope request) throws IOException {
        var root=http.json(URI.create(url),null,request); long code=BoundedJson.integer(root.get("code"));
        if(code!=0) throw CatalogFailure.code(CatalogFailure.Stage.BILI_DISCOVER,CatalogFailure.Reason.API,code);
        return root.get("data");
    }
    private static List<Object> array(Object value,int max) throws IOException {
        if(value==null) return List.of(); var list=BoundedJson.array(value);
        if(list.size()>max) throw new IOException("Discovery bounds exceeded"); return list;
    }
    private static long number(Object value) {
        try { return value instanceof String text?Long.parseLong(text):value instanceof Number n?n.longValue():0; } catch(NumberFormatException ignored){return 0;}
    }
    private static String text(Object value) { return value instanceof String s?s.replaceAll("<[^>]*>","").replace("&amp;","&").replace("&quot;","\"").replace("&#39;","'"):""; }
    private static String target(Object bvid,Object aid) {
        String bv=text(bvid).strip(); if(bv.matches("BV[0-9A-Za-z]{10}")) return bv;
        long av=number(aid); return av>0?"av"+av:"";
    }
    private static Chart fetch(Query query,CatalogHttp http,RequestScope request,Clock clock) throws IOException {
        List<Item> items=new ArrayList<>(); Set<String> unique=new HashSet<>(); String title;
        if(query.view()==View.DISCOVER) {
            title="\u97f3\u4e50\u5206\u533a\u70ed\u95e8\u89c6\u9891";
            var rows=array(data(http,"https://app.bilibili.com/x/v2/rank/region?rid=3&type=all&pn=1&ps=100",request),100);
            for(Object value:rows) {
                var row=BoundedJson.object(value); String id=target(null,row.get("param")); long duration=number(row.get("duration"));
                if(id.isEmpty() || duration<=0 || duration>604800 || !unique.add(id)) continue;
                items.add(new Item(Kind.TRACK,id,text(row.get("title")),text(row.get("name")),duration*1000,true,CoverSource.parse(AccountPlatform.BILIBILI,text(row.get("cover")))));
            }
        } else {
            int type=query.view()==View.MUSIC_HOT?1:2;
            title=type==1?"\u97f3\u4e50\u70ed\u95e8\u699c":"\u97f3\u4e50\u539f\u521b\u699c";
            var periods=BoundedJson.object(data(http,"https://api.bilibili.com/x/copyright-music-publicity/toplist/all_period?list_type="+type,request));
            var years=BoundedJson.object(periods.get("list")); if(years.size()>100) throw new IOException("Too many periods");
            long selected=0,latest=-1;
            for(Object year:years.values()) for(Object value:array(year,1000)) {
                var period=BoundedJson.object(value); long id=number(period.get("ID")),time=number(period.get("publish_time"));
                if(id>0 && (time>latest || time==latest && id>selected)) {selected=id;latest=time;}
            }
            if(selected<=0) throw new IOException("No chart period");
            var songs=BoundedJson.object(data(http,"https://api.bilibili.com/x/copyright-music-publicity/toplist/music_list?list_id="+selected,request));
            for(Object value:array(songs.get("list"),1000)) {
                var row=BoundedJson.object(value);
                // Prefer the official MV. Real duration/identity is checked by the normal resolver on play.
                String id=target(row.get("mv_bvid"),row.get("mv_aid")); boolean official=!id.isEmpty();
                if(!official) id=target(row.get("creation_bvid"),row.get("creation_aid"));
                if(id.isEmpty() || !unique.add(id)) continue;
                String name=text(row.get("music_title")); if(name.isBlank()) name=text(row.get("creation_title"));
                String artist=text(row.get("singer")); if(artist.isBlank()) artist=text(row.get("creation_nickname"));
                URI cover=CoverSource.parse(AccountPlatform.BILIBILI,text(row.get("mv_cover")));
                if(cover==null) cover=CoverSource.parse(AccountPlatform.BILIBILI,text(row.get("creation_cover")));
                long duration=official?0:Math.max(0,Math.min(604800,number(row.get("creation_duration"))))*1000;
                items.add(new Item(Kind.TRACK,id,name,artist,duration,true,cover));
            }
        }
        return new Chart(title,items,clock.instant().plusSeconds(120));
    }
}
