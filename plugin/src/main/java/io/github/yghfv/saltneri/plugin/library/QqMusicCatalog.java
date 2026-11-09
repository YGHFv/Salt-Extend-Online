package io.github.yghfv.saltneri.plugin.library;

/* Playlist/search mappings adapted from NeriPlayer QQMusicClient/QQMusicSearchApi,
 * GPL-3.0-or-later. Preserve upstream paging and reject incomplete bulk queues. */

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.qqmusic.*;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;
import static io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.*;

public final class QqMusicCatalog {
    private final AccountCredentials account;
    private final QqMusicHttp http;
    private final Clock clock;
    public QqMusicCatalog(AccountCredentials account,Clock clock,QqMusicHttp.Opener opener) {this.account=account;this.clock=clock;this.http=new QqMusicHttp(opener);}
    public Page load(Query query,RequestScope request) throws IOException {
        if(account!=null && account.platform()!=AccountPlatform.QQMUSIC)throw new IOException("Wrong QQ account");
        if((query.view()==View.MINE || query.view()==View.SAVED) && account==null)
            throw CatalogFailure.of(CatalogFailure.Stage.QQ_PLAYLISTS,CatalogFailure.Reason.LOGIN_REQUIRED);
        try {
            if(account!=null)try(var verifier=new QqMusicQrClient(clock,http)) {var verified=verifier.verify(account,request);verified.retire();}
            return switch(query.view()) {
                case SEARCH -> search(query,request);
                case FOLDER -> folder(query,request);
                case DISCOVER -> discover(query,request);
                case MINE -> owned(query,request);
                case SAVED -> saved(query,request);
                default -> throw new IOException("Unsupported QQ catalog");
            };
        } catch(QqMusicHttp.Rejected rejected) {throw CatalogFailure.code(CatalogFailure.Stage.QQ_CONTENTS,CatalogFailure.Reason.API,rejected.code());}
        catch(IOException failure) {throw CatalogFailure.wrap(CatalogFailure.Stage.QQ_CONTENTS,CatalogFailure.Reason.RESPONSE,failure);}
    }
    private Page search(Query q,RequestScope r) throws IOException {
        var data=http.call("music.search.SearchCgiService","DoSearchForQQMusicDesktop",
                Map.of("query",q.keyword(),"search_type",0,"num_per_page",20,"page_num",q.page()),account,Map.of("ct",11,"cv",0),r);
        var song=object(object(data.get("body")).get("song"));
        var rows=array(song.get("list"));if(rows.size()>20)throw new IOException("QQ search page too large");
        long total=number(object(data.get("meta")).get("sum"),-1);
        if(total<0)throw new IOException("QQ search total missing");
        return page("\u641c\u7d22\uff1a"+q.keyword(),songs(rows),q.page()*20L<total,total);
    }
    private Page discover(Query q,RequestScope r) throws IOException {
        var data=http.call("music.playlist.PlaylistSquare","GetRecommendFeed",Map.of("From",(q.page()-1)*20,"Size",20),
                account,Map.of("ct",20,"cv",2276,"platform","wk_v17","g_tk",0,"g_tk_new_20200303",0),r);
        var rows=array(data.get("List"));if(rows.size()>20)throw new IOException("QQ discovery page too large");
        var items=new ArrayList<Item>();
        for(Object row:rows) {
            var entry=object(row);
            var list=entry.get("Playlist") instanceof Map<?,?>?object(entry.get("Playlist")):entry;
            if(!(list.get("basic") instanceof Map<?,?>))continue;
            var basic=object(list.get("basic"));var item=playlist(basic);if(item!=null)items.add(item);
        }
        return page("\u53d1\u73b0",items,flag(data.get("HasMore")),-1);
    }
    private Page owned(Query q,RequestScope r) throws IOException {
        var data=http.call("music.musicasset.PlaylistBaseRead","GetPlaylistByUin",Map.of("uin",account.userId()),account,r);
        if(!data.containsKey("v_playlist"))throw new IOException("QQ owned list missing");
        var rows=array(data.get("v_playlist"));if(rows.size()>10000)throw new IOException("QQ playlist bound");
        int start=Math.min(rows.size(),(q.page()-1)*20),end=Math.min(rows.size(),start+20);
        var items=new ArrayList<Item>();for(Object row:rows.subList(start,end)) {var item=playlist(object(row));if(item!=null)items.add(item);}
        return page("\u6536\u85cf",items,end<rows.size(),rows.size());
    }
    private Page saved(Query q,RequestScope r) throws IOException {
        var uri=URI.create("https://c.y.qq.com/fav/fcgi-bin/fcg_get_profile_order_asset.fcg?ct=20&cid=205360956&userid="+account.userId()
                +"&reqtype=3&sin="+((q.page()-1)*20)+"&ein="+(q.page()*20-1)+"&format=json&outCharset=utf-8");
        var root=http.exchange(uri,null,account.header(uri),r,2*1024*1024).json();
        if(number(root.get("code"),-1)!=0)throw new Rejected(number(root.get("code"),-1));
        var data=object(root.get("data"));long total=number(data.getOrDefault("totaldiss",data.get("total")),0);
        var rows=array(data.get("cdlist"));if(rows.size()>20 || total>0 && !data.containsKey("cdlist"))throw new IOException("QQ saved page invalid");
        var items=new ArrayList<Item>();for(Object row:rows) {var item=playlist(object(row));if(item!=null)items.add(item);}
        return page("\u8ba2\u9605",items,q.page()*20L<total,total);
    }
    private Page folder(Query q,RequestScope r) throws IOException {
        if(account==null && q.folder()!=Long.MAX_VALUE) return publicFolder(q,r);
        var params=new LinkedHashMap<String,Object>(Map.of("disstid",q.folder(),"dirid",0,"tag",true,"song_begin",(q.page()-1)*20,
                "song_num",20,"userinfo",true,"orderlist",true,"enc_host_uin",account==null?"":cookie(account,"euin")));
        // The liked list has no public disstid; a reserved logical folder maps to dirid 201.
        if(q.folder()==Long.MAX_VALUE) {
            if(account==null)throw CatalogFailure.of(CatalogFailure.Stage.QQ_CONTENTS,CatalogFailure.Reason.LOGIN_REQUIRED);
            params.put("disstid",0);params.put("dirid",201);
        }
        var data=http.call("music.srfDissInfo.DissInfo","CgiGetDiss",params,account,r);
        var info=data.get("dirinfo") instanceof Map<?,?>?object(data.get("dirinfo")):Map.<String,Object>of();
        long total=number(info.getOrDefault("total_song_num",info.getOrDefault("songnum",data.get("total_song_num"))),-1);
        var rows=array(data.get("songlist"));if(rows.size()>20 || total<0 || total>10000 || total>0 && !data.containsKey("songlist"))throw new IOException("QQ playlist page invalid");
        boolean more=q.page()*20L<total || flag(data.get("hasmore"));
        if(more && rows.isEmpty())throw new IOException("QQ playlist truncated");
        return page(text(info,"title","dissname","name"),songs(rows),more,total);
    }
    private Page publicFolder(Query q,RequestScope r) throws IOException {
        var contents=publicContents(q,r);
        var items=contents.items();
        int start=Math.min(items.size(),(q.page()-1)*20),end=Math.min(items.size(),start+20);
        return page(contents.title(),items.subList(start,end),end<items.size(),contents.totalCount());
    }
    private CatalogClient.CollectionContents publicContents(Query q,RequestScope r) throws IOException {
        var uri=URI.create("https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&new_format=1&disstid="
                +q.folder()+"&format=json&outCharset=utf-8");
        var root=http.exchange(uri,null,"",r,2*1024*1024).json();
        if(number(root.get("code"),-1)!=0)throw new Rejected(number(root.get("code"),-1));
        var lists=array(root.get("cdlist"));if(lists.size()!=1)throw new IOException("QQ public playlist missing");
        var info=object(lists.getFirst());
        if(number(info.getOrDefault("disstid",info.getOrDefault("dissid",q.folder())),0)!=q.folder())throw new IOException("QQ playlist identity mismatch");
        var rows=array(info.get("songlist"));long total=number(info.getOrDefault("total_song_num",info.get("songnum")),rows.size());
        if(rows.size()>10000 || total!=rows.size())throw new IOException("QQ public playlist incomplete");
        var items=songs(rows);if(items.size()!=total)throw new IOException("QQ public tracks incomplete");
        return new CatalogClient.CollectionContents(text(info,"dissname","title","name"),items,total);
    }
    public CatalogClient.CollectionContents loadAll(Query q,RequestScope r) throws IOException {
        if(account==null && q.folder()!=Long.MAX_VALUE)return publicContents(q,r);
        if(account!=null)try(var verifier=new QqMusicQrClient(clock,http)) {var verified=verifier.verify(account,r);verified.retire();}
        var items=new ArrayList<Item>();var ids=new HashSet<String>();long total=-1;String title="";
        for(int index=1;index<=500;index++) {
            var page=folder(new Query(AccountPlatform.QQMUSIC,View.FOLDER,q.folder(),index),r);
            if(index==1) {total=page.totalCount();title=page.title();}
            if(total!=page.totalCount())throw new IOException("QQ playlist changed");
            for(var item:page.items())if(!ids.add(item.target()))throw new IOException("QQ duplicate playlist page");else items.add(item);
            if(!page.hasMore()) {
                if(items.size()!=total)throw new IOException("QQ incomplete playlist");
                return new CatalogClient.CollectionContents(title,items,total);
            }
        }
        throw new IOException("QQ playlist exceeds page bound");
    }
    private static List<Item> songs(List<Object> rows) {
        var items=new ArrayList<Item>();var seen=new HashSet<String>();
        for(Object row:rows) try {
            var song=QqMusicClient.parseSong(object(row));if(!seen.add(song.mid()))continue;
            items.add(new Item(Kind.TRACK,song.mid(),song.metadata().title(),song.metadata().artist(),song.duration(),true,song.cover()));
        } catch(IOException | IllegalArgumentException invalid) { /* Isolate malformed tracks; bulk completion still checks the total. */ }
        return items;
    }
    private static Item playlist(Map<String,Object> raw) throws IOException {
        long dir=number(raw.getOrDefault("dirId",raw.get("dirid")),0);
        long id=number(raw.getOrDefault("tid",raw.getOrDefault("dissid",raw.get("id"))),0);
        if(dir==201)id=Long.MAX_VALUE;
        String name=text(raw,"dirName","title","dissname","diss_name","name");if(id<=0 || name.isBlank())return null;
        String cover=text(raw,"bigpicUrl","picUrl","picurl","diss_cover","logo","albumPicUrl","coverUrl");
        if(raw.get("cover") instanceof Map<?,?>) {
            String nested=text(object(raw.get("cover")),"big_url","medium_url","default_url","small_url","url","picurl");
            if(!nested.isBlank())cover=nested;
        }
        String creator=text(raw,"hostname","creator","nickname");
        if(raw.get("creator") instanceof Map<?,?>)creator=text(object(raw.get("creator")),"nick","nickname","name");
        return new Item(Kind.FOLDER,Long.toString(id),name,creator,
                Math.max(-1,number(raw.getOrDefault("songNum",raw.getOrDefault("songnum",raw.get("song_cnt"))),-1)),true,
                CoverSource.parse(AccountPlatform.QQMUSIC,cover),0,
                Math.max(-1,number(raw.getOrDefault("play_cnt",raw.getOrDefault("playCount",raw.get("listen_num"))),-1)));
    }
    private Page page(String title,List<Item> items,boolean more,long total) {return new Page(title,items,more,clock.instant().plusSeconds(120),
            total<0?0:(int)Math.max(1,(total+19)/20),total);}
    private static boolean flag(Object value) {return Boolean.TRUE.equals(value) || "1".equals(String.valueOf(value)) || "true".equals(String.valueOf(value));}
}
