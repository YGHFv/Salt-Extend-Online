package io.github.yghfv.saltneri.plugin.library;

/* Catalog parameters/field mapping adapted from pinned NeriPlayer BiliClient, NeteaseClient
 * and NeteasePlaylistMapping. GPL-3.0-or-later; no writes, retry or eager all-pages download. */

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.bilibili.BiliWbi;
import io.github.yghfv.saltneri.plugin.net.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;
import static io.github.yghfv.saltneri.plugin.library.CatalogFailure.*;

public final class CatalogClient {
    private final AccountCredentials account;
    private final Clock clock;
    private final CatalogHttp.Opener opener;
    private final BiliDiscovery.Cache discovery;
    private final CatalogHttp.SessionCache sessions;
    public CatalogClient(AccountCredentials account) { this(account, Clock.systemUTC(), CatalogHttp::openPinned); }
    public CatalogClient(AccountCredentials account, Clock clock, CatalogHttp.Opener opener) { this(account,clock,opener,new BiliDiscovery.Cache(clock)); }
    CatalogClient(AccountCredentials account, Clock clock, CatalogHttp.Opener opener, BiliDiscovery.Cache discovery) {
        this(account,clock,opener,discovery,null);
    }
    CatalogClient(AccountCredentials account, Clock clock, CatalogHttp.Opener opener, BiliDiscovery.Cache discovery,CatalogHttp.SessionCache sessions) {
        this.account = account; this.clock = clock; this.opener = opener; this.discovery = discovery; this.sessions=sessions;
    }
    public Page load(Query query, RequestScope request) throws IOException {
        boolean discover = query.view() == View.DISCOVER || query.view() == View.MUSIC_HOT || query.view() == View.MUSIC_ORIGINAL;
        boolean personal = query.view() == View.MINE || query.view() == View.SAVED || query.platform() == AccountPlatform.BILIBILI && !discover;
        if (personal && (account == null || account.platform() != query.platform())) {
            Stage stage = query.platform() == AccountPlatform.BILIBILI
                    ? query.view() == View.SEARCH ? Stage.BILI_SEARCH : query.view() == View.MINE ? Stage.BILI_CREATED : query.view() == View.SAVED ? Stage.BILI_SAVED : Stage.BILI_CONTENTS
                    : Stage.NET_PLAYLISTS;
            throw CatalogFailure.of(stage, Reason.LOGIN_REQUIRED);
        }
        try (CatalogHttp http = new CatalogHttp(query.platform(), discover ? null : account, clock, opener,sessions)) {
            try {
                if (!discover && account != null) http.verify(request);
                return query.platform() == AccountPlatform.BILIBILI ? bili(query, http, request) : netease(query, http, request);
            } catch (IOException error) { throw CatalogFailure.wrap(http.stage(), Reason.RESPONSE, error); }
        } catch (IOException error) { throw CatalogFailure.wrap(Stage.SESSION, Reason.RESPONSE, error); }
    }
    /** Explicit play/filter/sort only: one verified session, one NetEase ID list, bounded detail batches. */
    public record CollectionContents(String title,List<Item> items,long totalCount) {
        public CollectionContents { items=List.copyOf(items); if(items.size()>10000) throw new IllegalArgumentException("Collection too large"); }
    }
    public CollectionContents loadAll(Query query, RequestScope request) throws IOException {
        return loadAll(query,request,null,(done,total)->{});
    }
    CollectionContents loadAll(Query query, RequestScope request, CatalogPageCache cache,
                               java.util.function.BiConsumer<Integer,Integer> progress) throws IOException {
        if(query.view()!=View.FOLDER && query.view()!=View.COLLECTION) throw invalid();
        if(account!=null && account.platform()!=query.platform() || query.platform()==AccountPlatform.BILIBILI && account==null)
            throw CatalogFailure.of(Stage.SESSION,Reason.LOGIN_REQUIRED);
        try(CatalogHttp http=new CatalogHttp(query.platform(),account,clock,opener,sessions)) {
            if(account!=null) http.verify(request);
            if(query.platform()==AccountPlatform.NETEASE) return neteaseFolder(query,http,request,true);
            var worker=java.util.concurrent.Executors.newFixedThreadPool(3,r->{var t=new Thread(r,"salt-bili-catalog-batch");t.setDaemon(true);return t;});
            var pending=new ArrayList<java.util.concurrent.Future<Page>>();
            try {
                Page first=bulkPage(query,1,http,request,cache); int pages=first.totalPages();
                if(first.totalCount()>10000) throw invalid();
                if(query.view()==View.COLLECTION && first.hasMore()) {
                    CollectionContents snapshot=collectionSnapshot(query,first,http,request);
                    if(snapshot!=null) {progress.accept(pages,pages);return snapshot;}
                }
                List<Page> results=new ArrayList<>(); results.add(first); progress.accept(1,pages);
                // Unknown totals retain the conservative sequential end-of-list protocol.
                if(pages==0) {
                    for(int page=2;results.getLast().hasMore();page++) {
                        if(page>500) throw invalid();
                        results.add(bulkPage(query,page,http,request,cache)); progress.accept(page,0);
                    }
                } else for(int from=2;from<=pages;from+=3) {
                    request.check(); pending.clear();
                    for(int page=from;page<=Math.min(from+2,pages);page++) {
                        int index=page;
                        pending.add(worker.submit(()->{try(var child=http.fork(request)) {return bulkPage(query,index,child,request,cache);}}));
                    }
                    for(var future:pending) {
                        Page result;
                        try { result=future.get(); }
                        catch(InterruptedException error) { Thread.currentThread().interrupt(); request.check(); throw new IOException("Catalog interrupted"); }
                        catch(java.util.concurrent.ExecutionException error) {
                            if(error.getCause() instanceof IOException io) throw io;
                            if(error.getCause() instanceof RuntimeException runtime) throw runtime;
                            throw new IOException("Catalog worker failed");
                        }
                        request.check(); if(result.totalCount()!=first.totalCount()) throw new IOException("Catalog changed; refresh before sorting");
                        results.add(result); progress.accept(results.size(),pages);
                    }
                }
                List<Item> items=new ArrayList<>(); Set<String> ids=new HashSet<>();
                for(Page result:results) for(Item item:result.items()) if(item.enabled() && ids.add(item.target())) items.add(item);
                request.check(); return new CollectionContents(first.title(),items,first.totalCount());
            } finally { pending.forEach(f->f.cancel(true)); worker.shutdownNow(); }
        }
    }
    /** Video detail can embed the entire UGC season; accept only a complete, same-order snapshot. */
    private CollectionContents collectionSnapshot(Query query,Page first,CatalogHttp http,RequestScope parent) throws IOException {
        if(first.totalCount()<1 || first.items().isEmpty() || first.items().stream().anyMatch(i->!i.enabled())) return null;
        String seed=first.items().getFirst().target();
        try(var request=new RequestScope(()->{parent.check();return true;},3000)) {
            var root=http.json(URI.create("https://api.bilibili.com/x/web-interface/view?bvid="+seed),null,request);
            long code=number(root.get("code"),-1);
            if(code==-404 || code==62002) return null;
            var data=object(success(root,0,http.stage()).get("data"));
            if(!seed.equals(data.get("bvid")) || !(data.get("ugc_season") instanceof Map<?,?>)) return null;
            var season=object(data.get("ugc_season"));
            if(number(season.get("id"),0)!=query.folder() || number(season.get("mid"),0)!=query.owner()
                    || number(season.get("ep_count"),-1)!=first.totalCount()) return null;
            var items=new ArrayList<Item>();var ids=new HashSet<String>();
            for(Object section:bounded(arrayOrEmpty(season.get("sections")),10000)) {
                for(Object value:bounded(arrayOrEmpty(object(section).get("episodes")),10000)) {
                    request.check();var episode=object(value);String bvid=optional(episode.get("bvid"));
                    if(items.size()>=first.totalCount() || !bvid.matches("BV[0-9A-Za-z]{10}") || !ids.add(bvid)
                            || number(episode.get("season_id"),query.folder())!=query.folder()) return null;
                    if(!(episode.get("arc") instanceof Map<?,?>)) return null;
                    var arc=object(episode.get("arc"));
                    if(number(arc.get("aid"),0)<=0 || number(arc.get("aid"),0)!=number(episode.get("aid"),-1)) return null;
                    long duration=number(arc.get("duration"),0),state=number(arc.get("state"),0);
                    String title=display(arc.get("title"));
                    if(title.isBlank() || duration<=0) return null;
                    items.add(new Item(Kind.TRACK,bvid,title,author(arc),duration*1000,state>=0,
                            cover(AccountPlatform.BILIBILI,arc.get("pic")),authorId(arc,query.owner()),playCount(arc,"stat","view"),number(arc.get("pubdate"),0)));
                }
            }
            if(items.size()!=first.totalCount() || items.size()<first.items().size()) return null;
            for(int i=0;i<first.items().size();i++) if(!items.get(i).target().equals(first.items().get(i).target())) return null;
            parent.check();return new CollectionContents(first.title(),items,first.totalCount());
        } catch(java.util.concurrent.CancellationException timeout) {parent.check();return null;}
        catch(IOException failure) {
            parent.check();
            // Never turn authentication/risk-control rejection into extra catalog requests.
            if(failure instanceof CatalogFailure safe && (safe.reason()==Reason.API || safe.reason()==Reason.AUTH
                    || safe.reason()==Reason.HTTP && (Long.valueOf(401).equals(safe.code()) || Long.valueOf(403).equals(safe.code()) || Long.valueOf(429).equals(safe.code())))) throw failure;
            return null;
        }
    }
    private Page bulkPage(Query query,int page,CatalogHttp http,RequestScope request,CatalogPageCache cache) throws IOException {
        Query next=new Query(query.platform(),query.view(),query.folder(),page,query.owner());
        try { return cache==null ? bili(next,http,request) : cache.load(account,next,"read","",request,(q,k,s,r)->bili(q,http,r)); }
        catch(IOException | RuntimeException error) { throw error; }
        catch(Exception error) { throw new IOException("Catalog batch failed"); }
    }
    private Page netease(Query query, CatalogHttp http, RequestScope request) throws IOException {
        if (query.view() == View.SEARCH) {
            String csrf = http.csrf(request);
            var root = success(http.json(URI.create("https://music.163.com/weapi/cloudsearch/get/web?csrf_token=" + encode(csrf)),
                    NeteaseWeapi.form(neteaseSearchParameters(query)), request), 200, http.stage());
            var result = root.get("result") == null ? Map.<String,Object>of() : object(root.get("result"));
            var songs = bounded(arrayOrEmpty(result.get("songs")), 20);
            long total = number(result.get("songCount"), songs.isEmpty() ? 0 : -1);
            if (total < 0 || total == 0 && !songs.isEmpty()) throw invalid();
            List<Item> items = new ArrayList<>(); Set<Long> unique = new HashSet<>();
            for (Object row : songs) {
                var song = object(row); long songId = number(song.get("id"), 0);
                if (songId <= 0 || display(song.get("name")).isBlank() || !unique.add(songId)) continue;
                items.add(neteaseSong(song));
            }
            // Pagination counts upstream results, not the remaining playable rows.
            return page("\u641c\u7d22\uff1a" + query.keyword(), items, query.page() < 500 && query.page() * 20L < total, total);
        }
        if (query.view() == View.FOLDER) {
            var contents=neteaseFolder(query,http,request,false);
            return page(contents.title(),contents.items(),query.page()*20L<contents.totalCount(),contents.totalCount());
        }
        String path = query.view() == View.DISCOVER ? "/weapi/personalized/playlist" : "/weapi/user/playlist";
        Map<String, Object> parameters = new LinkedHashMap<>(); parameters.put("limit", "20");
        if (query.view() != View.DISCOVER) { parameters.put("uid", account.userId()); parameters.put("offset", Integer.toString((query.page() - 1) * 20)); parameters.put("includeVideo", "true"); }
        else if (query.page() != 1) throw new IllegalArgumentException("Discovery has a single bounded page");
        String csrf = http.csrf(request);
        var root = success(http.json(URI.create("https://music.163.com" + path + "?csrf_token=" + encode(csrf)), NeteaseWeapi.form(parameters), request), 200, http.stage());
        String field=query.view()==View.DISCOVER?"result":"playlist";
        if(query.view()!=View.DISCOVER && !root.containsKey(field) && root.containsKey("playlists")) field="playlists";
        if(!root.containsKey(field)) throw invalid();
        List<Object> upstream=bounded(arrayOrEmpty(root.get(field)),1000);
        List<Item> items = new ArrayList<>(); Set<Long> unique = new HashSet<>();
        for (Object row : upstream.subList(0,Math.min(20,upstream.size()))) {
            if(!(row instanceof Map<?,?>)) continue;
            var list=object(row); long listId=optionalNumber(list.get("id"),0);
            String title=display(list.get("name"));
            if(listId<=0 || title.isBlank() || !unique.add(listId)) continue;
            long owner=optionalNumber(list.get("userId"),0);
            if(owner<=0 && list.get("creator") instanceof Map<?,?> creator) owner=optionalNumber(creator.get("userId"),0);
            boolean subscribed=flag(list.get("subscribed"),false);
            // Legacy/liked playlists can omit creator. Keep readable metadata without inventing an identity.
            boolean mine=query.view()!=View.DISCOVER && (owner>0?Long.toString(owner).equals(account.userId()):!subscribed);
            if(query.view()==View.MINE && !mine || query.view()==View.SAVED && mine) continue;
            items.add(new Item(Kind.FOLDER,Long.toString(listId),title,neteaseAuthor(list),Math.max(-1,optionalNumber(list.get("trackCount"),-1)),true,
                    firstCover(AccountPlatform.NETEASE,list,"coverImgUrl","picUrl","coverUrl")));
        }
        long total=query.view()==View.DISCOVER?items.size():optionalNumber(root.get("count"),-1);
        boolean more=query.view()!=View.DISCOVER && query.page()<500 && (upstream.size()>20
                || flag(root.get("more"),total>=0?query.page()*20L<total:upstream.size()==20));
        if(more && total>=0 && total<=query.page()*20L) total=-1;
        return page(viewTitle(query),items,more,total);
    }
    private CollectionContents neteaseFolder(Query query,CatalogHttp http,RequestScope request,boolean full) throws IOException {
            Map<String, Object> root = success(http.json(URI.create("https://music.163.com/api/v6/playlist/detail"), form(Map.of("id", Long.toString(query.folder()), "n", query.page() == 1 ? "20" : "0", "s", "0")), request), 200, http.stage());
            Map<String, Object> list = object(root.get("playlist")); if (id(list.get("id")) != query.folder()) throw invalid();
            List<Object> ids = array(list.get("trackIds")); if (ids.size() > 10000) throw invalid();
            Set<Long> all = new HashSet<>(); List<Long> selected = new ArrayList<>(); int offset = (query.page() - 1) * 20;
            for (int i = 0; i < ids.size(); i++) { long song = id(object(ids.get(i)).get("id")); if (!all.add(song)) throw invalid(); if (full || i >= offset && i < offset + 20) selected.add(song); }
            List<Item> items = new ArrayList<>();
            if (!selected.isEmpty()) {
                Map<Long, Map<String, Object>> songs = new HashMap<>(); Set<Long> seen = new HashSet<>(),selectedIds=new HashSet<>(selected);
                // First-page playlist responses can already contain the same song metadata. Fetch only gaps.
                for (Object row : bounded(arrayOrEmpty(list.get("tracks")), 10000)) {
                    var song = object(row); long songId = id(song.get("id"));
                    if (!all.contains(songId) || !seen.add(songId)) throw invalid();
                    if (selectedIds.contains(songId) && song.get("name") instanceof String && song.get("fee") != null
                            && alias(song,"ar","artists") instanceof List<?>
                            && alias(song,"dt","duration") != null && neteaseSongCover(song)!=null) songs.put(songId,song);
                }
                List<Long> missing = selected.stream().filter(songId -> !songs.containsKey(songId)).toList();
                songs.putAll(neteaseDetails(missing,http,request));
                for (long songId : selected) {
                    var song = songs.get(songId);
                    if (song == null) { items.add(new Item(Kind.TRACK, Long.toString(songId), "Unavailable #" + songId, "Missing/deleted track", 0, false)); continue; }
                    items.add(neteaseSong(song));
                }
            }
            return new CollectionContents(display(list.get("name")),items,ids.size());
    }
    private Map<Long,Map<String,Object>> neteaseDetails(List<Long> missing,CatalogHttp http,RequestScope request) throws IOException {
        if(missing.size()<=100) return missing.isEmpty()?Map.of():neteaseBatch(missing,http,request);
        var worker=java.util.concurrent.Executors.newFixedThreadPool(3,r->{var t=new Thread(r,"salt-netease-catalog-batch");t.setDaemon(true);return t;});
        var pending=new ArrayList<java.util.concurrent.Future<Map<Long,Map<String,Object>>>>();
        var result=new HashMap<Long,Map<String,Object>>();
        try {
            for(int from=0;from<missing.size();from+=300) {
                request.check();pending.clear();
                for(int offset=from;offset<Math.min(from+300,missing.size());offset+=100) {
                    var batch=List.copyOf(missing.subList(offset,Math.min(offset+100,missing.size())));
                    pending.add(worker.submit(()->{try(var child=http.fork(request)) {return neteaseBatch(batch,child,request);}}));
                }
                for(var future:pending) {
                    try {result.putAll(future.get());}
                    catch(InterruptedException error) {Thread.currentThread().interrupt();request.check();throw new IOException("Catalog interrupted");}
                    catch(java.util.concurrent.ExecutionException error) {
                        if(error.getCause() instanceof IOException io) throw io;
                        if(error.getCause() instanceof RuntimeException runtime) throw runtime;
                        throw new IOException("Catalog worker failed");
                    }
                }
            }
            request.check();return result;
        } finally {pending.forEach(f->f.cancel(true));worker.shutdownNow();}
    }
    private Map<Long,Map<String,Object>> neteaseBatch(List<Long> batch,CatalogHttp http,RequestScope request) throws IOException {
        request.check();
        String joined=batch.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(",","[","]"));
        var details=success(http.json(URI.create("https://music.163.com/api/song/detail?ids="+encode(joined)),null,request),200,http.stage());
        List<Object> rows=array(details.get("songs"));if(rows.size()>batch.size()) throw invalid();
        var songs=new HashMap<Long,Map<String,Object>>();
        for(Object row:rows) {var song=object(row);long songId=id(song.get("id"));if(!batch.contains(songId) || songs.put(songId,song)!=null) throw invalid();}
        request.check();return songs;
    }
    private Item neteaseSong(Map<String, Object> song) throws IOException {
        long fee = number(song.get("fee"), -1), duration = number(alias(song, "dt", "duration"), 0);
        String title = display(song.get("name"));
        boolean enabled = !title.isBlank() && (fee == 0 || fee == 8 || account != null && (fee == 1 || fee == 4))
                && number(alias(song, "st", "status"), 0) >= 0 && duration > 0;
        List<String> artists = new ArrayList<>(); Object artistRows = alias(song, "ar", "artists");
        if (artistRows != null) for (Object row : bounded(array(artistRows), 64)) if(row instanceof Map<?,?> artist) {
            String label=display(artist.get("name")); if(!label.isBlank()) artists.add(label);
        }
        return new Item(Kind.TRACK, Long.toString(id(song.get("id"))), title, String.join(" / ", artists), duration, enabled,
                neteaseSongCover(song));
    }
    private static URI neteaseSongCover(Map<String,Object> song) {
        for(String field:List.of("al","album")) if(song.get(field) instanceof Map<?,?> album) {
            for(String key:List.of("picUrl","coverImgUrl")) { URI image=cover(AccountPlatform.NETEASE,album.get(key)); if(image!=null)return image; }
        }
        return null;
    }
    /** Lazy artwork-only lookup. No audio URL, queue, or recursive fallback; same account and HTTPS policy. */
    public URI neteaseCover(Item item,RequestScope request) throws IOException {
        if(item.kind()!=Kind.TRACK && item.kind()!=Kind.FOLDER) return null;
        long target;
        try { target=Long.parseLong(item.target()); } catch(NumberFormatException error) {throw invalid();}
        if(target<=0) throw invalid();
        try(var http=new CatalogHttp(AccountPlatform.NETEASE,account,clock,opener)) {
            if(account!=null) http.verify(request);
            if(item.kind()==Kind.TRACK) {
                var root=success(http.json(URI.create("https://music.163.com/api/song/detail?ids="+encode("["+target+"]")),null,request),200,http.stage());
                List<Object> songs=bounded(array(root.get("songs")),1);
                if(songs.isEmpty())return null;
                var song=object(songs.getFirst()); if(id(song.get("id"))!=target) throw invalid();
                return neteaseSongCover(song);
            }
            var root=success(http.json(URI.create("https://music.163.com/api/v6/playlist/detail"),form(Map.of("id",Long.toString(target),"n","0","s","0")),request),200,http.stage());
            var list=object(root.get("playlist")); if(id(list.get("id"))!=target)throw invalid();
            return firstCover(AccountPlatform.NETEASE,list,"coverImgUrl","picUrl","coverUrl");
        }
    }
    private static long optionalNumber(Object value,long fallback) {
        try {return value instanceof String text?Long.parseLong(text):number(value,fallback);}
        catch(IOException | NumberFormatException ignored) {return fallback;}
    }
    private static boolean flag(Object value,boolean fallback) {
        if(value instanceof Boolean b) return b;
        if("true".equals(value))return true; if("false".equals(value))return false;
        long n=optionalNumber(value,-1); return n==1 || n!=0 && fallback;
    }
    private static String display(Object value) {
        if(!(value instanceof String text)) return "";
        String cleaned=text.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+"," ").strip();
        int end=Math.min(4096,cleaned.length()); if(end>0 && Character.isHighSurrogate(cleaned.charAt(end-1)))end--;
        return cleaned.substring(0,end);
    }
    private static String neteaseAuthor(Map<String,Object> row) {
        if(row.get("creator") instanceof Map<?,?> person) {
            String name=display(person.get("nickname")); if(!name.isEmpty())return name;
        }
        return display(row.get("creatorName"));
    }
    static Map<String, Object> neteaseSearchParameters(Query query) {
        return Map.of("s", query.keyword(), "type", "1", "limit", "20",
                "offset", Integer.toString((query.page() - 1) * 20), "total", "true");
    }
    private Page bili(Query query, CatalogHttp http, RequestScope request) throws IOException {
        if (query.view() == View.DISCOVER || query.view() == View.MUSIC_HOT || query.view() == View.MUSIC_ORIGINAL) return discovery.load(query,http,request);
        String base = "https://api.bilibili.com";
        if(query.view()==View.AUTHOR_VIDEOS) return authorVideos(query,http,request);
        if(query.view()==View.AUTHOR_COLLECTIONS) {
            URI uri=BiliWbi.sign(base+"/x/polymer/web-space/seasons_series_list",Map.of("mid",Long.toString(query.owner()),
                    "page_num",Integer.toString(query.page()),"page_size","20","web_location","333.999"),wbi(http,request),clock.instant().getEpochSecond());
            var data=object(success(http.json(uri,null,request),0,http.stage()).get("data"));
            var lists=object(data.get("items_lists")); var paging=object(lists.get("page"));
            if(number(paging.get("page_num"),query.page())!=query.page()) throw invalid();
            List<Item> items=new ArrayList<>();
            for(Object row:bounded(arrayOrEmpty(lists.get("seasons_list")),20)) {
                var meta=object(object(row).get("meta"));
                if(number(meta.get("mid"),query.owner())!=query.owner()) throw invalid();
                items.add(new Item(Kind.COLLECTION,Long.toString(id(meta.get("season_id"))),text(meta.get("name")),author(meta),
                        number(meta.get("total"),-1),true,firstCover(AccountPlatform.BILIBILI,meta,"cover","pic"),query.owner(),-1,number(alias(meta,"ptime","mtime"),0)));
            }
            // Upstream paging also counts series; only playable season collections are exposed here.
            long total=number(paging.get("total"),-1);
            return page("\u5408\u96c6",items,total<0?items.size()==20:query.page()*20L<total,total);
        }
        if (query.view() == View.SEARCH) {
            URI uri = BiliWbi.sign(base + "/x/web-interface/wbi/search/type", Map.of(
                    "search_type", "video", "keyword", query.keyword(), "page", Integer.toString(query.page()),
                    "page_size", "20", "order", "totalrank", "duration", "0", "tids", "0"), wbi(http, request), clock.instant().getEpochSecond());
            var data = object(success(http.json(uri, null, request), 0, http.stage()).get("data"));
            if (number(data.get("page"), query.page()) != query.page() || number(data.get("pagesize"), 20) != 20) throw invalid();
            long total = number(data.get("numResults"), -1), count = number(data.get("numPages"), total < 0 ? -1 : Math.max(1, (total + 19) / 20));
            if (count < 0 || count > Integer.MAX_VALUE) throw invalid();
            int pages = (int)Math.max(1, Math.min(500, count));
            List<Item> items = new ArrayList<>(); Set<String> unique = new HashSet<>();
            for (Object row : bounded(arrayOrEmpty(data.get("result")), 20)) {
                var video = object(row);
                if (!"video".equals(video.get("type"))) continue;
                String bvid = optional(video.get("bvid"));
                long duration = searchDuration(video.get("duration"));
                if (!bvid.matches("BV[0-9A-Za-z]{10}") || duration <= 0 || !unique.add(bvid)) continue;
                items.add(new Item(Kind.TRACK, bvid, searchTitle(text(video.get("title"))), optional(video.get("author")), duration, true,
                        cover(AccountPlatform.BILIBILI, video.get("pic")), number(video.get("mid"),0), number(video.get("play"),-1),number(video.get("pubdate"),0)));
            }
            return new Page("\u641c\u7d22\uff1a" + query.keyword(), items, query.page() < pages, clock.instant().plusSeconds(300), pages);
        }
        if (query.view() == View.COLLECTION) {
            var data = collectionData(query, 20, http, request);
            var meta = object(data.get("meta"));
            List<Item> items = new ArrayList<>(); Set<String> unique = new HashSet<>();
            for (Object row : bounded(arrayOrEmpty(data.get("archives")), 20)) {
                var video = object(row); String bvid = optional(video.get("bvid"));
                long duration = number(video.get("duration"), 0);
                boolean enabled = bvid.matches("BV[0-9A-Za-z]{10}") && duration > 0;
                String target = enabled ? bvid : "unsupported:" + id(video.get("aid"));
                if (!unique.add(target)) throw invalid();
                items.add(new Item(Kind.TRACK, target, text(video.get("title")), author(video), duration * 1000, enabled,
                        cover(AccountPlatform.BILIBILI, video.get("pic")), authorId(video,query.owner()), playCount(video, "stat", "view")));
            }
            Map<String, Object> paging = data.get("page") == null ? Map.of() : object(data.get("page"));
            long total = number(paging.get("total"), number(meta.get("total"), -1));
            Boolean more = more(data, query.page(), total);
            if (more == null) more = items.size() == 20 && query.page() < 500 && !arrayOrEmpty(collectionData(
                    new Query(query.platform(), query.view(), query.folder(), query.page() + 1, query.owner(),"",query.reverse()), 20, http, request).get("archives")).isEmpty();
            return page(text(meta.get("name")), items, more && query.page() < 500, total);
        }
        if (query.view() == View.FOLDER) {
            var data = object(success(http.json(URI.create(base + "/x/v3/fav/resource/list?media_id=" + query.folder() + "&pn=" + query.page() + "&ps=20&order=mtime&platform=web"), null, request), 0, http.stage()).get("data"));
            var info = object(data.get("info")); if (id(info.get("id")) != query.folder()) throw invalid();
            List<Item> items = new ArrayList<>(); Set<String> unique = new HashSet<>();
            for (Object row : bounded(arrayOrEmpty(data.get("medias")), 20)) {
                var video = object(row); String bvid = optional(alias(video, "bvid", "bv_id"));
                boolean enabled = number(video.get("type"), 0) == 2 && bvid.matches("BV[0-9A-Za-z]{10}") && number(video.get("duration"), 0) > 0;
                String target = enabled ? bvid : "unsupported:" + id(video.get("id")); if (!unique.add(target)) throw invalid();
                String owner = video.get("upper") == null ? "" : optional(object(video.get("upper")).get("name"));
                items.add(new Item(Kind.TRACK, target, text(video.get("title")), owner, number(video.get("duration"), 0) * 1000, enabled,
                        cover(AccountPlatform.BILIBILI, video.get("cover")), authorId(video,0), playCount(video, "cnt_info", "play")));
            }
            Boolean more = more(data, query.page(), number(info.get("media_count"), -1));
            if (more == null) more = items.size() == 20 && query.page() < 500 && !arrayOrEmpty(biliData(http, request,
                    base + "/x/v3/fav/resource/list?media_id=" + query.folder() + "&pn=" + (query.page() + 1) + "&ps=20&order=mtime&platform=web").get("medias")).isEmpty();
            return page(text(info.get("title")), items, more && query.page() < 500, number(info.get("media_count"), -1));
        }
        String endpoint = query.view() == View.MINE ? "created/list" : "collected/list";
        var data = object(success(http.json(URI.create(base + "/x/v3/fav/folder/" + endpoint + "?up_mid=" + account.userId() + "&pn=" + query.page() + "&ps=20&platform=web&web_location=333.1387"), null, request), 0, http.stage()).get("data"));
        List<Item> items = new ArrayList<>(); Set<String> unique = new HashSet<>();
        for (Object row : bounded(arrayOrEmpty(data.get("list")), 20)) {
            var folder = object(row); long collection = number(alias(folder, "season_id", "series_id"), 0), folderId = number(folder.get("id"), collection);
            if (folderId <= 0) throw invalid(); long type = number(folder.get("type"), collection > 0 ? 21 : 11);
            // Created folders use 0/1 (normal/default), unlike collected/list's 11/21 item types.
            boolean isCollection = collection > 0 || type == 21;
            long owner = number(folder.get("mid"), 0);
            if (owner <= 0 && folder.get("upper") != null) owner = number(object(folder.get("upper")).get("mid"), 0);
            boolean enabled = (isCollection ? owner > 0 : type == 11 || query.view() == View.MINE && (type == 0 || type == 1))
                    && number(folder.get("state"), 0) == 0;
            if (!isCollection && query.view() == View.MINE && number(folder.get("mid"), Long.parseLong(account.userId())) != Long.parseLong(account.userId())) throw invalid();
            String target = Long.toString(isCollection && collection > 0 ? collection : folderId);
            if (!unique.add((isCollection ? "collection:" : "folder:") + target)) throw invalid();
            String subtitle = enabled ? author(folder)
                    : "\u672a\u652f\u6301\u7684\u5217\u8868\u7c7b\u578b (type=" + type + ")";
            if (subtitle.isEmpty() && query.view() == View.MINE) subtitle = account.profile().name();
            items.add(new Item(isCollection ? Kind.COLLECTION : Kind.FOLDER, target, text(alias(folder, "title", "name")), subtitle, number(alias(folder, "media_count", "total"), -1), enabled,
                    firstCover(AccountPlatform.BILIBILI, folder, "cover", "pic", "cover_url"), isCollection ? owner : 0));
        }
        Boolean more = more(data, query.page(), number(alias(data, "count", "total"), -1));
        // Some responses omit totals/flags. Probe at most one page, never crawl or count filtered UI rows.
        if (more == null) more = items.size() == 20 && query.page() < 500 && !arrayOrEmpty(biliData(http, request,
                base + "/x/v3/fav/folder/" + endpoint + "?up_mid=" + account.userId() + "&pn=" + (query.page() + 1) + "&ps=20&platform=web&web_location=333.1387").get("list")).isEmpty();
        return page(viewTitle(query), items, more && query.page() < 500, number(alias(data, "count", "total"), -1));
    }
    private record AuthorPage(List<Object> rows,long total) { }
    private AuthorPage authorPage(Query query,int page,CatalogHttp http,RequestScope request) throws IOException {
        URI uri=BiliWbi.sign("https://api.bilibili.com/x/space/wbi/arc/search",Map.of("mid",Long.toString(query.owner()),"pn",Integer.toString(page),
                "ps","20","order",query.order()==Sort.PLAY_COUNT?"click":"pubdate","platform","web","web_location","1550101"),wbi(http,request),clock.instant().getEpochSecond());
        var data=object(success(http.json(uri,null,request),0,http.stage()).get("data"));
        var paging=object(data.get("page"));
        if(number(paging.get("pn"),page)!=page) throw invalid();
        return new AuthorPage(bounded(arrayOrEmpty(object(data.get("list")).get("vlist")),20),number(paging.get("count"),-1));
    }
    private Page authorVideos(Query query,CatalogHttp http,RequestScope request) throws IOException {
        var first=authorPage(query,query.reverse()?1:query.page(),http,request);
        long total=first.total(); List<Object> rows=new ArrayList<>();
        if(!query.reverse()) rows.addAll(first.rows());
        else {
            // Reverse raw upstream positions before filtering unavailable rows. At most two tail pages,
            // plus the count probe; never download the whole uploader merely to show oldest-first.
            if(total<0 || total>10000) throw new IOException("Uploader reverse requires a bounded total");
            int end=(int)Math.max(0,total-(query.page()-1)*20L),start=Math.max(0,end-20);
            for(int page=start/20+1;start<end && page<=(end-1)/20+1;page++) {
                AuthorPage part=page==1?first:authorPage(query,page,http,request);
                if(part.total()!=total) throw new IOException("Uploader changed; refresh before sorting");
                int from=Math.max(0,start-(page-1)*20),to=Math.min(part.rows().size(),end-(page-1)*20);
                if(from<to) rows.addAll(part.rows().subList(from,to));
            }
            Collections.reverse(rows);
        }
        List<Item> items=new ArrayList<>(); Set<String> unique=new HashSet<>();
        for(Object row:rows) {
            var video=object(row); String bvid=optional(video.get("bvid")); long duration=searchDuration(video.get("length"));
            if(!bvid.matches("BV[0-9A-Za-z]{10}") || duration<=0 || !unique.add(bvid)) continue;
            items.add(new Item(Kind.TRACK,bvid,text(video.get("title")),optional(video.get("author")),duration,true,
                    cover(AccountPlatform.BILIBILI,video.get("pic")),query.owner(),number(video.get("play"),-1),number(video.get("created"),0)));
        }
        return page("\u89c6\u9891",items,total<0?rows.size()==20:query.page()*20L<total,total);
    }
    private static Boolean more(Map<String, Object> data, int page, long total) throws IOException {
        Object flag = alias(data, "has_more", "more");
        if (flag instanceof Boolean value) return value;
        if (flag != null) { long value = number(flag, 0); if (value != 0 && value != 1) throw invalid(); return value == 1; }
        return total <= 0 ? null : page * 20L < total;
    }
    private static Map<String, Object> biliData(CatalogHttp http, RequestScope request, String url) throws IOException {
        return object(success(http.json(URI.create(url), null, request), 0, http.stage()).get("data"));
    }
    private String wbi(CatalogHttp http, RequestScope request) throws IOException {
        String verified = http.verifiedWbiMixin(request); if (verified != null) return verified;
        var nav = biliData(http, request, "https://api.bilibili.com/x/web-interface/nav");
        var keys = object(nav.get("wbi_img"));
        try { return BiliWbi.mixin(text(keys.get("img_url")), text(keys.get("sub_url"))); }
        catch (IllegalArgumentException error) { throw invalid(); }
    }
    private Map<String, Object> collectionData(Query query, int size, CatalogHttp http, RequestScope request) throws IOException {
        URI uri = BiliWbi.sign("https://api.bilibili.com/x/polymer/web-space/seasons_archives_list", Map.of(
                "mid", Long.toString(query.owner()), "season_id", Long.toString(query.folder()),
                "page_num", Integer.toString(query.page()), "page_size", Integer.toString(size), "sort_reverse", Boolean.toString(query.reverse()), "web_location", "333.999"),
                wbi(http, request), clock.instant().getEpochSecond());
        var data = object(success(http.json(uri, null, request), 0, http.stage()).get("data"));
        var meta = object(data.get("meta"));
        if (id(meta.get("season_id")) != query.folder() || id(meta.get("mid")) != query.owner()) throw invalid();
        return data;
    }
    /** Visible collection artwork only: metadata cover, then its first video's picture; no audio resolution. */
    public URI collectionCover(Query query, RequestScope request) throws IOException {
        if (query.view() != View.COLLECTION || account == null || account.platform() != AccountPlatform.BILIBILI) throw invalid();
        try (CatalogHttp http = new CatalogHttp(AccountPlatform.BILIBILI, account, clock, opener)) {
            http.verify(request);
            var data = collectionData(query, 1, http, request);
            URI cover = firstCover(AccountPlatform.BILIBILI, object(data.get("meta")), "cover", "pic");
            if (cover != null) return cover;
            for (Object row : bounded(arrayOrEmpty(data.get("archives")), 1)) {
                cover = firstCover(AccountPlatform.BILIBILI, object(row), "pic", "cover");
                if (cover != null) return cover;
            }
            return null;
        }
    }
    private static URI firstCover(AccountPlatform platform, Map<String, Object> row, String... fields) {
        for (String field : fields) { URI uri = cover(platform, row.get(field)); if (uri != null) return uri; }
        return null;
    }
    private static long playCount(Map<String, Object> row, String container, String field) {
        // Optional statistics must not make an otherwise playable video disappear.
        if (!(row.get(container) instanceof Map<?, ?> stats)) return -1;
        Object value = stats.get(field);
        try { return Math.max(-1, value instanceof String text ? Long.parseLong(text) : BoundedJson.integer(value)); }
        catch (IOException | NumberFormatException ignored) { return -1; }
    }
    private static String viewTitle(Query query) {
        return switch (query.view()) {
            case DISCOVER -> "\u53d1\u73b0";
            case MUSIC_HOT -> "\u97f3\u4e50\u70ed\u95e8\u699c";
            case MUSIC_ORIGINAL -> "\u97f3\u4e50\u539f\u521b\u699c";
            case MINE -> query.platform() == AccountPlatform.BILIBILI ? "\u6536\u85cf" : "\u6211\u7684\u6b4c\u5355";
            case SAVED -> "\u8ba2\u9605";
            case AUTHOR_VIDEOS -> "Videos";
            case AUTHOR_COLLECTIONS -> "Collections";
            case FOLDER, COLLECTION -> throw new IllegalArgumentException("Folder title comes from its contents");
            case SEARCH -> "\u641c\u7d22";
        };
    }
    private Page page(String title, List<Item> items, boolean more, long total) {
        int pages = total < 0 || total == 0 && more ? 0 : (int)Math.max(1, Math.min(500, total / 20 + (total % 20 == 0 ? 0 : 1)));
        return new Page(title, items, more, clock.instant().plusSeconds(300), pages, total);
    }
    private static String author(Map<String, Object> row) throws IOException {
        for (String field : List.of("upper", "owner", "creator", "author")) {
            Object value = row.get(field);
            if (value instanceof String text && !text.isBlank()) return text;
            if (value instanceof Map<?,?>) {
                var person = object(value);
                for (String name : List.of("name", "uname", "nickname")) {
                    String label = optional(person.get(name)); if (!label.isEmpty()) return label;
                }
            }
        }
        return optional(alias(row, "uname", "author_name"));
    }
    private static long authorId(Map<String,Object> row,long fallback) throws IOException {
        for(String field:List.of("upper","owner","author")) if(row.get(field) instanceof Map<?,?> person) {
            long mid=number(person.get("mid"),0); if(mid>0) return mid;
        }
        return fallback;
    }
    private static long searchDuration(Object value) {
        if (!(value instanceof String text) || !text.matches("[0-9]{1,6}:[0-9]{2}(:[0-9]{2})?")) return 0;
        String[] parts = text.split(":"); long seconds = 0;
        for (int i = 0; i < parts.length; i++) {
            long n = Long.parseLong(parts[i]); if (i > 0 && n >= 60) return 0;
            seconds = seconds * 60 + n;
        }
        return seconds <= 7 * 24 * 3600 ? seconds * 1000 : 0;
    }
    private static String searchTitle(String value) {
        // API search highlighting is markup, not part of the media title. Never render upstream HTML.
        return value.replaceAll("<[^>]*>", "").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
    }
    private static URI cover(AccountPlatform platform, Object value) { return value instanceof String text ? CoverSource.parse(platform, text) : null; }
    private static Map<String, Object> success(Map<String, Object> root, long expected, Stage stage) throws IOException {
        long code = BoundedJson.integer(root.get("code")); if (code != expected) throw CatalogFailure.code(stage, Reason.API, code); return root;
    }
    private static Map<String, Object> object(Object value) throws IOException { return BoundedJson.object(value); }
    private static List<Object> array(Object value) throws IOException { return BoundedJson.array(value); }
    private static List<Object> arrayOrEmpty(Object value) throws IOException { return value == null ? List.of() : array(value); }
    private static List<Object> bounded(List<Object> values, int max) throws IOException { if (values.size() > max) throw invalid(); return values; }
    private static Object alias(Map<String, Object> values, String a, String b) { return values.get(a) == null ? values.get(b) : values.get(a); }
    private static long id(Object value) throws IOException { long n = BoundedJson.integer(value); if (n <= 0) throw invalid(); return n; }
    private static long number(Object value, long fallback) throws IOException { return value == null ? fallback : BoundedJson.integer(value); }
    private static String optional(Object value) throws IOException { return value == null ? "" : text(value); }
    private static String text(Object value) throws IOException { String text = BoundedJson.string(value); if (text.length() > 4096 || text.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid(); return text; }
    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    private static byte[] form(Map<String, String> values) { return values.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(java.util.stream.Collectors.joining("&")).getBytes(StandardCharsets.US_ASCII); }
    private static IOException invalid() { return new IOException("Invalid catalog response"); }
}
