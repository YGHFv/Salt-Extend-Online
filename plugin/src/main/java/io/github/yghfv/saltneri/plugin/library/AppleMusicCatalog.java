package io.github.yghfv.saltneri.plugin.library;

/* Library identity/artwork mapping follows MineRadio Apple Music edition (GPL-3.0). */
import io.github.yghfv.saltneri.plugin.applemusic.AppleMusicHttp;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;
import static io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.*;

public final class AppleMusicCatalog {
    private final AppleMusicHttp http;
    private final AccountCredentials account;
    private final Clock clock;
    public AppleMusicCatalog(AccountCredentials account,Clock clock,AppleMusicHttp http) {this.account=account;this.clock=clock;this.http=http;}
    public Page load(Query q,RequestScope request) throws IOException {
        try {
            if(account!=null && account.platform()!=AccountPlatform.APPLEMUSIC)throw AppleMusicHttp.failure("ACCOUNT");
            boolean personal=q.view()==View.MINE || q.view()==View.SAVED || q.view()==View.FOLDER;
            if(personal && account==null)throw CatalogFailure.of(CatalogFailure.Stage.APPLE_CONTENTS,CatalogFailure.Reason.LOGIN_REQUIRED);
            String sf=storefront(),path,title;Map<String,Object> collection;
            int offset=(q.page()-1)*20;
            switch(q.view()) {
                case DISCOVER -> {
                    path="/v1/catalog/"+sf+"/charts?types=songs&limit=20&offset="+offset;
                    var charts=array(object(http.get(path,null,request).get("results")).get("songs"));
                    if(charts.isEmpty())throw AppleMusicHttp.failure("CHARTS_SHAPE");
                    collection=object(charts.getFirst());title="热门歌曲";
                }
                case SEARCH -> {
                    path="/v1/catalog/"+sf+"/search?types=songs&limit=20&offset="+offset+"&term="+URLEncoder.encode(q.keyword(),StandardCharsets.UTF_8);
                    var results=object(http.get(path,null,request).get("results"));
                    collection=results.get("songs")==null?Map.of("data",List.of()):object(results.get("songs"));title="搜索："+q.keyword();
                }
                case MINE -> {collection=http.get("/v1/me/library/albums?limit=20&offset="+offset,account,request);title="我的专辑";}
                case SAVED -> {collection=http.get("/v1/me/library/playlists?limit=20&offset="+offset,account,request);title="我的歌单";}
                case FOLDER -> {collection=http.get("/v1/me/library/"+q.resource()+"/tracks?limit=20&offset="+offset,account,request);title="Apple Music 曲目";}
                default -> throw AppleMusicHttp.failure("VIEW");
            }
            var rows=array(collection.get("data"));if(rows.size()>20)throw AppleMusicHttp.failure("PAGE_BOUND");
            var items=new ArrayList<Item>();
            for(Object row:rows)try {var item=item(object(row),sf);if(item!=null)items.add(item);}
            catch(IOException invalid) { /* One malformed catalog item must not hide the remaining page. */ }
            boolean more=collection.get("next") instanceof String next && !next.isBlank() && q.page()<500;
            return new Page(title,items,more,clock.instant().plusSeconds(120),0,-1);
        } catch(IOException error) {
            if(error.getMessage()!=null && error.getMessage().startsWith("APPLE/"))throw safe(error);
            throw CatalogFailure.wrap(CatalogFailure.Stage.APPLE_CONTENTS,CatalogFailure.Reason.RESPONSE,error);
        }
    }
    private static CatalogFailure safe(IOException cause) {
        String message=cause.getMessage();
        if(message.startsWith("APPLE/HTTP_"))try {
            return CatalogFailure.code(CatalogFailure.Stage.APPLE_CONTENTS,CatalogFailure.Reason.HTTP,Long.parseLong(message.substring(11)));
        } catch(NumberFormatException ignored) {}
        if(message.equals("APPLE/LOGIN_REQUIRED"))return CatalogFailure.of(CatalogFailure.Stage.APPLE_CONTENTS,CatalogFailure.Reason.LOGIN_REQUIRED);
        return CatalogFailure.of(CatalogFailure.Stage.APPLE_CONTENTS,CatalogFailure.Reason.RESPONSE);
    }
    public CatalogClient.CollectionContents loadAll(Query q,RequestScope request) throws IOException {
        var items=new ArrayList<Item>();var seen=new HashSet<String>();
        for(int page=1;page<=500;page++) {
            var part=load(new Query(q.platform(),q.view(),q.folder(),page,0,"",false,Sort.DEFAULT,q.resource()),request);
            for(var item:part.items()) {
                if(!seen.add(item.target()))throw AppleMusicHttp.failure("DUPLICATE_PAGE");
                items.add(item);
            }
            if(!part.hasMore())return new CatalogClient.CollectionContents(part.title(),items,items.size());
            if(part.items().isEmpty())throw AppleMusicHttp.failure("TRUNCATED");
        }
        throw AppleMusicHttp.failure("COLLECTION_BOUND");
    }
    private String storefront() throws IOException {
        if(account!=null)for(var e:account.cookieSnapshot())if(e.name().equals("storefront") && e.value().matches("[a-z]{2}"))return e.value();
        return "us";
    }
    private static Item item(Map<String,Object> raw,String sf) throws IOException {
        String id=text(raw,"id"),type=text(raw,"type");var a=object(raw.get("attributes"));
        String title=text(a,"name");if(id.isBlank() || title.isBlank())return null;
        URI art=null;if(a.get("artwork") instanceof Map<?,?>)art=CoverSource.parse(AccountPlatform.APPLEMUSIC,
                text(object(a.get("artwork")),"url").replace("{w}","600").replace("{h}","600").replace("{f}","jpg"));
        String artist=text(a,"artistName","curatorName");
        if(type.equals("library-albums") || type.equals("library-playlists")) {
            if(!id.matches("[A-Za-z0-9.-]{1,128}"))return null;
            return new Item(Kind.FOLDER,(type.equals("library-albums")?"albums/":"playlists/")+id,title,artist,number(a.get("trackCount"),-1),true,art);
        }
        String catalog=type.equals("songs")?id:"";
        if(a.get("playParams") instanceof Map<?,?>) {
            String mapped=text(object(a.get("playParams")),"catalogId");if(!mapped.isBlank())catalog=mapped;
        }
        boolean playable=catalog.matches("[1-9][0-9]{0,18}");
        // Library IDs are never interpreted as catalog playback IDs.
        return new Item(Kind.TRACK,playable?sf+":"+catalog:"library:"+id,title,artist,number(a.get("durationInMillis"),0),playable,art);
    }
}
