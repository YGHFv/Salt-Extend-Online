package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.bilibili.BiliTarget;
import io.github.yghfv.saltneri.plugin.kugou.*;
import io.github.yghfv.saltneri.plugin.qqmusic.*;
import io.github.yghfv.saltneri.plugin.applemusic.*;
import io.github.yghfv.saltneri.plugin.net.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

/** Read membership before offering a write; platform IDs never become media URLs. */
public final class PlatformPlaylistGateway implements PlatformPlaylistController.Gateway {
    @FunctionalInterface public interface Opener {HttpURLConnection open(AccountPlatform platform,URI uri,byte[] body) throws IOException;}
    private final Opener opener;
    private final Clock clock;
    private final CatalogHttp.SessionCache sessions=new CatalogHttp.SessionCache();
    private final AppleMusicHttp apple;
    public PlatformPlaylistGateway() {this((p,u,b)->b==null?new PinnedHttpsConnection(u):
            p==AccountPlatform.QQMUSIC?PinnedHttpsConnection.qqMusicPost(u,b):
            p==AccountPlatform.NETEASE && !Set.of("/weapi/playlist/manipulate/tracks","/weapi/playlist/subscribe").contains(u.getPath())
                    ?CatalogHttp.openPinned(u,b):PinnedHttpsConnection.playlistPost(p,u,b),Clock.systemUTC());}
    public PlatformPlaylistGateway(Opener opener,Clock clock) {
        this.opener=opener;this.clock=clock;apple=new AppleMusicHttp(u->opener.open(AccountPlatform.APPLEMUSIC,u,null),clock);
    }
    private CatalogClient catalog(AccountCredentials account) {
        return new CatalogClient(account,clock,(u,b)->opener.open(account.platform(),u,b),
                new BiliDiscovery.Cache(clock),sessions,new QqMusicCatalog.Recommendations(),apple);
    }
    public List<PlatformPlaylistController.Choice> choices(AccountCredentials account,RequestScope request) throws IOException {
        requireAccount(account,request);
        var choices=new ArrayList<PlatformPlaylistController.Choice>();
        switch(account.platform()) {
            case NETEASE -> {
                try(var http=new CatalogHttp(AccountPlatform.NETEASE,account,clock,(u,b)->opener.open(AccountPlatform.NETEASE,u,b),sessions)) {
                    http.verify(request);String csrf=http.csrf(request);
                    for(int offset=0;offset<10000;offset+=20) {
                        var root=http.json(URI.create("https://music.163.com/weapi/user/playlist?csrf_token="+encode(csrf)),
                                NeteaseWeapi.form(Map.of("uid",account.userId(),"offset",offset,"limit",20,"includeVideo",true)),request);
                        success(root,200);var rows=array(root.getOrDefault("playlist",root.get("playlists")));
                        for(Object value:rows) {
                            var row=object(value);long owner=number(row.get("userId"),0);
                            if(owner==0 && row.get("creator") instanceof Map<?,?>)owner=number(object(row.get("creator")).get("userId"),0);
                            if(!Long.toString(owner).equals(account.userId()))continue;
                            String id=positive(row.get("id"));choices.add(new PlatformPlaylistController.Choice(id,id,text(row,"name")));
                        }
                        if(!Boolean.TRUE.equals(root.get("more")) && rows.size()<20)break;
                        if(rows.isEmpty())break;
                        if(offset==9980)throw new IOException("Playlist list exceeds bound");
                    }
                }
            }
            case QQMUSIC -> {
                var data=qq().call("music.musicasset.PlaylistBaseRead","GetPlaylistByUin",Map.of("uin",account.userId()),account,request);
                for(Object value:array(data.get("v_playlist"))) {
                    var row=object(value);
                    if(row.get("basic") instanceof Map<?,?>)row=object(row.get("basic"));
                    long dir=number(row.getOrDefault("dirId",row.get("dirid")),0);
                    if(dir<=0 || dir==202 || dir==203)continue;
                    Object remote=row.getOrDefault("tid",row.getOrDefault("dissid",row.getOrDefault("id",0)));
                    long publicId=number(remote,0);
                    if(dir!=201 && publicId<=0)continue;
                    choices.add(new PlatformPlaylistController.Choice(dir==201?Long.toString(Long.MAX_VALUE):Long.toString(publicId),
                            Long.toString(dir),dir==201?"我喜欢的音乐":text(row,"dirName","title","name","dissname")));
                }
            }
            case KUGOU -> {
                var http=kugou();
                for(int page=1;page<=500;page++) {
                    var data=object(http.get("/user/playlist",Map.of("page",page,"pagesize",20),account,request));
                    var rows=KugouHttp.rows(data,"info");
                    for(Object value:rows) {
                        var row=object(value);long type=number(row.get("type"),-1),def=number(row.getOrDefault("is_def",row.get("is_default")),-1);
                        if(type==1 || !(type==0 || def==0 || def==1 || def==2 || account.userId().equals(text(row,"list_create_userid"))))continue;
                        String id=text(row,"list_create_gid","global_collection_id");
                        if(id.isBlank())id=text(row,"listid");
                        if(!KugouCatalog.validCollection(id))continue;
                        choices.add(new PlatformPlaylistController.Choice(id,positive(row.get("listid")),text(row,"name","title","specialname")));
                    }
                    if(number(data.get("has_next"),rows.size()>=20?1:0)==0)break;
                    if(page==500)throw new IOException("Playlist list exceeds bound");
                }
            }
            case APPLEMUSIC -> {
                for(int offset=0;offset<10000;offset+=20) {
                    var data=apple.get("/v1/me/library/playlists?limit=20&offset="+offset,account,request);
                    for(Object value:array(data.get("data"))) {
                        var row=object(value);var attributes=object(row.get("attributes"));String id=text(row,"id");
                        if(!Boolean.TRUE.equals(attributes.get("canEdit")) || !id.matches("p\\.[A-Za-z0-9.-]{1,128}"))continue;
                        choices.add(new PlatformPlaylistController.Choice("playlists/"+id,id,text(attributes,"name")));
                    }
                    if(!(data.get("next") instanceof String next) || next.isBlank())break;
                    if(offset==9980)throw new IOException("Playlist list exceeds bound");
                }
            }
            case BILIBILI -> {
                for(Item item:lists(account,View.MINE,request))if(item.kind()==Kind.FOLDER)
                    choices.add(new PlatformPlaylistController.Choice(item.target(),item.target(),item.title()));
            }
        }
        requireAccount(account,request);
        var unique=new LinkedHashMap<String,PlatformPlaylistController.Choice>();
        for(var choice:choices)if(!choice.title().isBlank())unique.putIfAbsent(choice.id(),choice);
        if(unique.size()>1000)throw new IOException("Too many playlists");
        return List.copyOf(unique.values());
    }
    public Set<String> members(AccountCredentials account,PlatformPlaylistController.Choice choice,RequestScope request) throws IOException {
        if(account.platform()==AccountPlatform.NETEASE) {
            try(var http=new CatalogHttp(AccountPlatform.NETEASE,account,clock,(u,b)->opener.open(AccountPlatform.NETEASE,u,b),sessions)) {
                http.verify(request);
                var root=http.json(URI.create("https://music.163.com/api/v6/playlist/detail"),
                        form(Map.of("id",choice.id(),"n","0","s","0")),request);
                success(root,200);var list=object(root.get("playlist"));
                if(!choice.id().equals(positive(list.get("id"))))throw new IOException("Playlist identity changed");
                var ids=new HashSet<String>();var rows=array(list.get("trackIds"));
                if(rows.size()>10000)throw new IOException("Playlist too large");
                for(Object value:rows)ids.add(positive(object(value).get("id")));
                requireAccount(account,request);return Set.copyOf(ids);
            }
        }
        var query=new Query(account.platform(),View.FOLDER,account.platform()==AccountPlatform.KUGOU || account.platform()==AccountPlatform.APPLEMUSIC?1:Long.parseLong(choice.id()),
                1,0,"",false,Sort.DEFAULT,account.platform()==AccountPlatform.KUGOU || account.platform()==AccountPlatform.APPLEMUSIC?choice.id():"");
        var contents=catalog(account).loadAll(query,request);
        var ids=new HashSet<String>();for(Item item:contents.items())ids.add(normalize(account.platform(),item.target()));
        requireAccount(account,request);return Set.copyOf(ids);
    }
    public boolean subscribed(AccountCredentials account,Item item,RequestScope request) throws IOException {
        if(account.platform()==AccountPlatform.APPLEMUSIC)return item.target().startsWith("playlists/p.") || item.target().startsWith("albums/");
        for(View view:List.of(View.MINE,View.SAVED))for(Item list:lists(account,view,request))
            if(list.kind()==item.kind() && list.target().equals(item.target()))return true;
        return false;
    }
    private List<Item> lists(AccountCredentials account,View view,RequestScope request) throws IOException {
        var client=catalog(account);var result=new ArrayList<Item>();
        for(int page=1;page<=500;page++) {
            Page data=client.load(new Query(account.platform(),view,0,page),request);result.addAll(data.items());
            if(result.size()>1000)throw new IOException("Too many playlists");
            if(!data.hasMore())return List.copyOf(result);
        }
        throw new IOException("Playlist list exceeds bound");
    }
    public void add(AccountCredentials account,Item song,PlatformPlaylistController.Choice choice,RequestScope request) throws IOException {
        requireAccount(account,request);
        // Recheck writable ownership after a menu has been open. Never reuse another account's directory.
        if(!choices(account,request).contains(choice))throw new IOException("Playlist ownership changed");
        if(members(account,choice,request).contains(normalize(account.platform(),song.target())))return;
        switch(account.platform()) {
            case NETEASE -> {
                String id=positive(song.target());
                netWrite(account,"/weapi/playlist/manipulate/tracks",Map.of("op","add","pid",choice.id(),"id",choice.id(),
                        "tracks",id,"trackIds","["+id+"]","imme","true"),request);
            }
            case QQMUSIC -> {
                var detail=new QqMusicClient(account,clock,(u,b)->opener.open(AccountPlatform.QQMUSIC,u,b))
                        .song(QqMusicTarget.parse(song.target()),request);
                long id=number(detail.raw().get("id"),0),type=number(detail.raw().get("type"),0);
                if(id<=0)throw new IOException("QQ song catalog ID missing");
                var result=qq().call("music.musicasset.PlaylistDetailWrite","AddSonglist",Map.of("dirId",Long.parseLong(choice.writeId()),
                        "tid",choice.id().equals(Long.toString(Long.MAX_VALUE))?0:Long.parseLong(choice.id()),"bFmtUtf8",true,"source","normal",
                        "v_songInfo",List.of(Map.of("songId",id,"songType",type))),account,request);
                if(number(result.get("retCode"),-1)!=0)throw new IOException("QQ playlist write rejected");
            }
            case KUGOU -> {
                String hash=KugouHttp.hash(song.target());var rows=KugouHttp.rows(kugou().get("/privilege/lite",Map.of("hash",hash),account,request),"data","list");
                Map<String,Object> metadata=null;for(Object value:rows){var row=object(value);if(hash.equalsIgnoreCase(text(row,"hash"))){metadata=row;break;}}
                if(metadata==null)throw new IOException("Kugou song identity missing");
                var result=post(account,URI.create("https://"+KugouHttp.HOST+"/playlist/tracks/add"),jsonBytes(Map.of("listId",choice.writeId(),
                        "songs",List.of(Map.of("name",song.title(),"hash",hash,"albumId",positive(metadata.get("album_id")),
                                "mixSongId",positive(metadata.get("album_audio_id")))))),Map.of(),request);
                mutationSuccess(result);
            }
            case APPLEMUSIC -> {
                io.github.yghfv.saltneri.core.TrackKey.appleMusic(song.target());
                post(account,URI.create(AppleMusicHttp.API+"/v1/me/library/playlists/"+choice.writeId()+"/tracks"),
                        jsonBytes(Map.of("data",List.of(Map.of("id",song.target().substring(3),"type","songs")))),appleHeaders(account,request),request);
            }
            case BILIBILI -> {
                var target=BiliTarget.parse(song.target());
                var uri=URI.create("https://api.bilibili.com/x/web-interface/view?"+(target.isBvid()?"bvid="+target.id():"aid="+target.id().substring(2)));
                var response=read(account,uri,request);success(response,0);var data=object(response.get("data"));
                String aid=positive(data.get("aid"));biliWrite(account,"/x/v3/fav/resource/deal",
                        Map.of("rid",aid,"type","2","add_media_ids",choice.writeId(),"platform","web","ga","1","gaia_source","web_normal"),request);
            }
        }
        requireAccount(account,request);
    }
    public void subscribe(AccountCredentials account,Item item,RequestScope request) throws IOException {
        requireAccount(account,request);if(item.kind()==Kind.TRACK)throw new IllegalArgumentException("Playlist required");
        if(subscribed(account,item,request))return;
        switch(account.platform()) {
            case NETEASE -> netWrite(account,"/weapi/playlist/subscribe",Map.of("id",positive(item.target()),"t",1),request);
            case QQMUSIC -> {
                String uin=cookie(account,"euin");if(uin.isBlank())throw new IOException("QQ encrypted user ID missing");
                long id=Long.parseLong(positive(item.target()));
                var result=qq().call("music.musicasset.PlaylistFavWrite","FavPlaylist",Map.of("uin",uin,"v_playlistId",List.of(id)),account,request);
                if(number(result.get("result"),-1)!=0 || array(result.get("v_failedPlaylistId")).stream().anyMatch(v->String.valueOf(v).equals(Long.toString(id))))
                    throw new IOException("QQ playlist subscription rejected");
            }
            case KUGOU -> {
                if(!KugouCatalog.validCollection(item.target()))throw new IllegalArgumentException("Invalid Kugou playlist");
                mutationSuccess(post(account,URI.create("https://"+KugouHttp.HOST+"/playlist/add?name="+encode(item.title())+"&list_create_gid="+encode(item.target())),
                        new byte[0],Map.of(),request));
            }
            case APPLEMUSIC -> throw new IOException("Apple playlist already in library");
            case BILIBILI -> biliWrite(account,item.kind()==Kind.COLLECTION?"/x/v3/fav/season/fav":"/x/v3/fav/folder/fav",
                    Map.of(item.kind()==Kind.COLLECTION?"season_id":"media_id",positive(item.target()),"platform","web"),request);
        }
    }
    private QqMusicHttp qq(){return new QqMusicHttp((u,b)->opener.open(AccountPlatform.QQMUSIC,u,b));}
    private KugouHttp kugou(){return new KugouHttp(u->opener.open(AccountPlatform.KUGOU,u,null));}
    private void netWrite(AccountCredentials account,String path,Map<String,Object> values,RequestScope request) throws IOException {
        try(var http=new CatalogHttp(AccountPlatform.NETEASE,account,clock,(u,b)->opener.open(AccountPlatform.NETEASE,u,b),sessions)) {
            http.verify(request);String csrf=http.csrf(request);var payload=new LinkedHashMap<String,Object>(values);payload.put("csrf_token",csrf);
            URI uri=URI.create("https://music.163.com"+path+"?csrf_token="+encode(csrf));
            success(post(account,uri,NeteaseWeapi.form(payload),Map.of("Cookie",http.cookieHeader(uri,request)),request),200);
        }
    }
    private void biliWrite(AccountCredentials account,String path,Map<String,String> values,RequestScope request) throws IOException {
        String csrf=account.cookieSnapshot().stream().filter(c->c.name().equals("bili_jct")).map(ScopedCookies.Entry::value).findFirst().orElse("");
        if(csrf.isBlank())throw new IOException("Bilibili CSRF missing");
        var form=new LinkedHashMap<>(values);form.put("csrf",csrf);
        success(post(account,URI.create("https://api.bilibili.com"+path),form.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&")).getBytes(StandardCharsets.US_ASCII),Map.of(),request),0);
    }
    private Map<String,String> appleHeaders(AccountCredentials account,RequestScope request) throws IOException {
        return Map.of("Authorization","Bearer "+apple.publicBearer(request),"Music-User-Token",cookie(account,"media-user-token"),"Origin","https://music.apple.com");
    }
    private Map<String,Object> read(AccountCredentials account,URI uri,RequestScope request) throws IOException {return exchange(account,uri,null,Map.of(),request);}
    private Map<String,Object> post(AccountCredentials account,URI uri,byte[] body,Map<String,String> headers,RequestScope request) throws IOException {
        return exchange(account,uri,body,headers,request);
    }
    private Map<String,Object> exchange(AccountCredentials account,URI uri,byte[] body,Map<String,String> headers,RequestScope request) throws IOException {
        requireAccount(account,request);var connection=opener.open(account.platform(),uri,body);request.attach(connection);
        try {
            connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);connection.setRequestMethod(body==null?"GET":"POST");
            connection.setConnectTimeout(8000);connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent",account.userAgent());connection.setRequestProperty("Accept-Encoding","identity");
            connection.setRequestProperty("Referer",account.platform()==AccountPlatform.BILIBILI?"https://www.bilibili.com/":
                    account.platform()==AccountPlatform.NETEASE?"https://music.163.com/":"https://music.apple.com/");
            if(account.platform()==AccountPlatform.KUGOU)connection.setRequestProperty("X-Kg-Session-Id",KugouHttp.token(account));
            else if(account.platform()!=AccountPlatform.APPLEMUSIC)connection.setRequestProperty("Cookie",account.header(uri));
            for(var header:headers.entrySet())connection.setRequestProperty(header.getKey(),header.getValue());
            int status=connection.getResponseCode();requireAccount(account,request);
            if(account.platform()==AccountPlatform.APPLEMUSIC && (status==202 || status==204))return Map.of();
            if(status!=200)throw new IOException("Playlist HTTP_"+status);
            if(connection.getContentLengthLong()>BoundedJson.MAX_BYTES)throw new IOException("Playlist response exceeds bound");
            String encoding=connection.getHeaderField("Content-Encoding");
            if(encoding!=null && !encoding.equalsIgnoreCase("identity"))throw new IOException("Playlist response encoding");
            var result=JsonResponse.read(connection,request,true);requireAccount(account,request);return result;
        } finally {request.release(connection);}
    }
    private static void mutationSuccess(Map<String,Object> result) throws IOException {
        for(String key:List.of("error_code","err_code","_errno","code"))
            if(result.containsKey(key) && !Set.of(0L,200L).contains(number(result.get(key),-1)))throw new IOException("Playlist write rejected");
        if(Boolean.FALSE.equals(result.get("success")) || number(result.get("status"),1)==0)throw new IOException("Playlist write rejected");
        if(result.get("data") instanceof Map<?,?>)mutationSuccess(object(result.get("data")));
    }
    private static void success(Map<String,Object> root,long expected) throws IOException {if(number(root.get("code"),-1)!=expected)throw new IOException("Playlist API rejected");}
    static String normalize(AccountPlatform platform,String id) {return platform==AccountPlatform.KUGOU?KugouHttp.hash(id):id;}
    private static String positive(Object value) throws IOException {
        String id=String.valueOf(value);if(!id.matches("[1-9][0-9]{0,18}"))throw new IOException("Playlist numeric ID missing");return id;
    }
    private static byte[] form(Map<String,String> values) {
        return values.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&")).getBytes(StandardCharsets.US_ASCII);
    }
    private static void requireAccount(AccountCredentials account,RequestScope request) throws IOException {
        request.check();if(account==null || !account.isActive())throw new IOException("Playlist login required");
    }
}
