package io.github.yghfv.saltneri.plugin.qqmusic;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.host.SavedTrackStore;
import io.github.yghfv.saltneri.plugin.library.*;
import io.github.yghfv.saltneri.plugin.net.*;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.*;

/** Synthetic official gateway/CDN fixtures; no accounts, network, browser or audio engine. */
public final class QqMusicContractTest {
    private static int checks;
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"),ZoneOffset.UTC);
    private static final String MID="00123456789abc",MEDIA="00987654321def",ALBUM="003456789abcde";
    private static void check(boolean ok) {checks++;if(!ok)throw new AssertionError("QQ check "+checks);}
    @FunctionalInterface private interface Action {void run() throws Exception;}
    private static void fails(Action action) throws Exception {
        try {action.run();throw new AssertionError("QQ rejection expected");}
        catch(IOException | IllegalArgumentException | java.util.concurrent.CancellationException expected) {check(!String.valueOf(expected.getMessage()).contains("SECRET"));}
    }
    private static Map<String,Object> song(int index) {
        return Map.of("mid",index==0?MID:String.format(Locale.ROOT,"00%012d",index),"title","QQ fixture "+index,"interval",120,
                "singer",List.of(Map.of("name","Artist")),"album",Map.of("mid",ALBUM,"name","Album"),
                "file",Map.of("media_mid",MEDIA,"size_128mp3",4096,"size_flac",4096,"size_new",List.of(0,0,0,4096)));
    }
    private static final class Response extends HttpURLConnection {
        final byte[] bytes;final Map<String,String> headers;boolean disconnected;
        Response(URI uri,int status,byte[] bytes,Map<String,String> headers) throws IOException {super(uri.toURL());responseCode=status;this.bytes=bytes;this.headers=headers;}
        public void connect() {}
        public int getResponseCode() {return responseCode;}
        public InputStream getInputStream() {return new ByteArrayInputStream(bytes);}
        public String getHeaderField(String key) {return headers.get(key);}
        public Map<String,List<String>> getHeaderFields() {return headers.containsKey("Set-Cookie")?Map.of("Set-Cookie",List.of(headers.get("Set-Cookie"))):Map.of();}
        public long getContentLengthLong() {return Long.parseLong(headers.getOrDefault("Content-Length","-1"));}
        public void disconnect() {disconnected=true;}
        public boolean usingProxy() {return false;}
    }
    private static final class Fixture implements Opener {
        final List<Response> requests=new ArrayList<>();
        final List<Map<String,Object>> bodies=new ArrayList<>();
        String preview="",vkeyMid=MID,profileId="42",mediaSize="4096";
        int businessCode,dataCode,dataSubcode,denyHigh,denied,playlistTotal=23;
        boolean truncated,wrongSong,allQualities,canonicalPath,badEcho;String malformed;
        List<String> sip=List.of("http://dl.stream.qqmusic.qq.com/");
        Map<String,Object> discoveryCover=Map.of("default_url","https://y.qq.com/cover.jpg");
        public HttpURLConnection open(URI uri,byte[] body) throws IOException {
            Object data;var headers=new HashMap<String,String>();int status=200;
            if(body!=null) {
                var root=object(BoundedJson.parse(body));bodies.add(root);var req=object(root.get("req_0"));
                var param=object(req.get("param"));String method=text(req,"method");
                data=switch(method) {
                    case "GetLoginUserInfo" -> Map.of("info",Map.of("uin",profileId,"nick","Fixture","logo","https://y.qq.com/avatar.jpg"));
                    case "get_song_detail_yqq" -> {
                        var track=new LinkedHashMap<>(wrongSong?song(1):song(0));
                        if(allQualities) {
                            track.put("vs",Collections.nCopies(31,MEDIA));
                            track.put("file",Map.of("media_mid",MEDIA,"size_128mp3",4096,"size_192ogg",4096,
                                    "size_flac",4096,"size_dolby",4096,"size_new",Collections.nCopies(10,4096)));
                        }
                        yield Map.of("track_info",track);
                    }
                    case "UrlGetVkey","CgiGetVkey" -> {
                        String filename=(String)array(param.get("filename")).getFirst();
                        boolean reject=denied!=0 || denyHigh!=0 && !filename.startsWith("M500");
                        String returned=canonicalPath?filename.substring(0,4)+MEDIA+filename.substring(filename.indexOf('.')):filename;
                        yield Map.of("sip",sip,"midurlinfo",List.of(Map.of(
                                "songmid",vkeyMid,"result",reject?104003:0,"vkey","TOKEN_SECRET","filename",badEcho?"FOREIGN_SECRET":filename,
                                "purl",reject?"":preview.isEmpty()?returned+"?guid=1234567890&vkey=TOKEN_SECRET&uin=42&fromtag=0&src=M500":preview)));
                    }
                    case "get_radio_track" -> Map.of("tracks",List.of(Map.of("Track",song(0)),Map.of("Track",song(1))));
                    case "GetPlayLyricInfo" -> Map.of("lyric",Base64.getEncoder().encodeToString("[00:01.00]Original\n".getBytes(StandardCharsets.UTF_8)),
                            "trans",Base64.getEncoder().encodeToString("[00:01.00]Translation\n[00:02.00]//\n".getBytes(StandardCharsets.UTF_8)));
                    case "DoSearchForQQMusicDesktop" -> Map.of("meta",Map.of("sum",23),"body",Map.of("song",Map.of("list",List.of(song(0)))));
                    case "GetRecommendFeed" -> Map.of("HasMore",false,"List",List.of(Map.of("Playlist",Map.of("basic",Map.of("tid",123,"title","Discover","song_cnt",23,
                            "coverUrl","https://y.qq.com/cover.jpg","cover",discoveryCover)))));
                    case "GetPlaylistByUin" -> Map.of("v_playlist",List.of(Map.of("tid",123,"dirId",201,"dirName","Liked")));
                    case "CgiGetDiss" -> {
                        if(number(param.get("dirid"),0)==202) {
                            var daily=new ArrayList<Object>();for(int i=0;i<30;i++)daily.add(Map.of("songInfo",song(i)));
                            yield Map.of("songlist",daily);
                        }
                        int offset=(int)number(param.get("song_begin"),0);
                        var songs=new ArrayList<Object>();
                        for(int i=offset;i<Math.min(offset+20,playlistTotal-(truncated?1:0));i++)songs.add(song(i));
                        yield Map.of("dirinfo",Map.of("title","Playlist","total_song_num",playlistTotal),"songlist",songs,"hasmore",offset+20<playlistTotal);
                    }
                    default -> throw new IOException("Unexpected QQ fixture method");
                };
                var payload=new LinkedHashMap<>(object(data));
                if(dataCode!=0)payload.put("code",dataCode);
                if(dataSubcode!=0)payload.put("subcode",dataSubcode);
                byte[] bytes=jsonBytes(Map.of("code",businessCode,"req_0",Map.of("code",0,"data",payload)));
                if(malformed!=null)bytes=malformed.getBytes(StandardCharsets.UTF_8);
                var response=new Response(uri,status,bytes,headers);requests.add(response);return response;
            }
            if("c.y.qq.com".equals(uri.getHost())) {
                if(uri.getPath().contains("fcg_ucc_getcdinfo")) {
                    var songs=new ArrayList<Object>();for(int i=0;i<playlistTotal-(truncated?1:0);i++)songs.add(song(i));
                    data=Map.of("code",0,"cdlist",List.of(Map.of("dissid",123,"dissname","Public Playlist","total_song_num",playlistTotal,"songlist",songs)));
                } else data=Map.of("code",0,"data",Map.of("totaldiss",0,"cdlist",List.of()));
            }
            else {
                headers.put("Content-Range","bytes 0-0/"+mediaSize);headers.put("Content-Length","1");
                var response=new Response(uri,206,new byte[]{42},headers);requests.add(response);return response;
            }
            var response=new Response(uri,status,jsonBytes(data),headers);requests.add(response);return response;
        }
        QqMusicClient client(AccountCredentials a) {return new QqMusicClient(a,CLOCK,this);}
        QqMusicCatalog catalog(AccountCredentials a) {return new QqMusicCatalog(a,CLOCK,this);}
    }
    private static AccountCredentials account() throws IOException {
        return new AccountCredentials(AccountPlatform.QQMUSIC,"42",List.of(
                new ScopedCookies.Entry("qqmusic_key","COOKIE_SECRET","y.qq.com","/",false,true,CLOCK.instant().plusSeconds(3600)),
                new ScopedCookies.Entry("tmeLoginType","2","y.qq.com","/",false,true,CLOCK.instant().plusSeconds(3600))),CLOCK);
    }
    private static QqMusicClient.Resolved resolve(Fixture f,AccountCredentials a,QqMusicClient.Quality q) throws IOException {
        try(var r=new RequestScope(()->true)) {return f.client(a).resolve(QqMusicTarget.parse(MID),q,a==null?"anonymous-fixture":a.accountScope(),r);}
    }
    public static void main(String[] args) throws Exception {
        check(TrackKey.qqmusic(MID).stableId().equals("qqmusic:song:"+MID));
        for(String bad:List.of("123",MID+"x","https://y.qq.com/"+MID,"../"+MID))fails(()->QqMusicTarget.parse(bad));
        check(!AccountPlatform.QQMUSIC.allows("qq.com") && !AccountPlatform.QQMUSIC.allows("y.qq.com.evil"));
        check(CoverSource.parse(AccountPlatform.QQMUSIC,"http://y.qq.com/music/photo_new/cover.jpg")!=null);
        check(CoverSource.parse(AccountPlatform.QQMUSIC,"https://y.qq.com/cover.jpg?vkey=SECRET")==null);
        fails(()->MediaLease.requireTarget(MediaLease.Platform.QQMUSIC,URI.create("https://dl.stream.qqmusic.qq.com.evil/a")));
        fails(()->MediaLease.requireTarget(MediaLease.Platform.QQMUSIC,URI.create("http://dl.stream.qqmusic.qq.com/a")));
        try(var a= new AutoCloseableAccount(account())) {
            fails(()->a.value.header(URI.create("https://graph.qq.com/")));
            var f=new Fixture();var resolved=resolve(f,a.value,QqMusicClient.Quality.STANDARD);
            check(resolved.key().equals(TrackKey.qqmusic(MID)) && resolved.lease().length()==4096);
            check(resolved.lease().belongsTo(a.value.accountScope()) && resolved.metadata().artist().equals("Artist"));
            check(resolved.coverCandidate().toString().contains(ALBUM));
            check(f.requests.size()==4 && f.requests.stream().allMatch(r->r.disconnected));
            check(f.requests.getLast().getRequestProperty("Cookie")==null);
            check(f.requests.getLast().getRequestProperty("Range").equals("bytes=0-0"));
            check(object(f.bodies.getFirst().get("comm")).get("authst").equals("COOKIE_SECRET"));
            f=new Fixture();f.profileId="43";Fixture mismatch=f;fails(()->resolve(mismatch,a.value,QqMusicClient.Quality.STANDARD));check(f.requests.size()==1);
            f=new Fixture();f.businessCode=1000;Fixture rejected=f;fails(()->resolve(rejected,a.value,QqMusicClient.Quality.MASTER));check(f.requests.size()==1);
            for(boolean subcode:List.of(false,true)) {
                var dataRejected=new Fixture();
                if(subcode)dataRejected.dataSubcode=1000;else dataRejected.dataCode=1000;
                fails(()->resolve(dataRejected,a.value,QqMusicClient.Quality.MASTER));
                check(dataRejected.requests.size()==1);
            }
            a.value.retire();check(!resolved.lease().isActive());
        }
        var f=new Fixture();check(resolve(f,null,QqMusicClient.Quality.STANDARD).quality().equals("standard"));
        String[] prefixes={"M500","O600","O800","F000","Q000","Q001","Q003","D004","DT03","AI00"};
        String[] extensions={"mp3","ogg","ogg","flac","flac","flac","ogg","mp4","mp4","flac"};
        for(var quality:QqMusicClient.Quality.values()) {
            var tier=new Fixture();tier.allQualities=true;
            check(resolve(tier,null,quality).quality().equals(quality.key()));
            var param=object(object(tier.bodies.getLast().get("req_0")).get("param"));
            String expected=prefixes[quality.ordinal()]+(quality.ordinal()<4?MID+MEDIA:MEDIA)+"."+extensions[quality.ordinal()];
            check(array(param.get("filename")).equals(List.of(expected)));
            check(tier.requests.getLast().getRequestProperty("Cookie")==null);
            var canonical=new Fixture();canonical.allQualities=true;canonical.canonicalPath=true;
            check(resolve(canonical,null,quality).quality().equals(quality.key()));
            check(canonical.requests.getLast().getURL().getPath().equals("/"+prefixes[quality.ordinal()]+MEDIA+"."+extensions[quality.ordinal()]));
        }
        var authenticated=new Fixture();authenticated.canonicalPath=true;
        authenticated.sip=List.of("http://aqqmusic.tc.qq.com/","http://sjy6.stream.qqmusic.qq.com/");
        try(var a=new AutoCloseableAccount(account())) {
            var resolved=resolve(authenticated,a.value,QqMusicClient.Quality.STANDARD);
            check(authenticated.requests.getLast().getURL().getHost().equals("aqqmusic.tc.qq.com"));
            check(authenticated.requests.getLast().getRequestProperty("Cookie")==null && resolved.lease().length()==4096);
        }
        for(String host:List.of("aqqmusic.tc.qq.com.evil","evil.tc.qq.com","qq.com","random.qq.com"))
            fails(()->MediaLease.requireTarget(MediaLease.Platform.QQMUSIC,URI.create("https://"+host+"/media.mp3")));
        f=new Fixture();f.denyHigh=1;check(resolve(f,null,QqMusicClient.Quality.LOSSLESS).quality().equals("standard"));
        check(f.bodies.stream().filter(b->textUnchecked(b).contains("Vkey")).count()==3);
        for(String preview:List.of("RS02"+MEDIA+".mp3?vkey=TOKEN_SECRET","M500"+MID+MEDIA+".mp3?vkey=OTHER","https://evil/SECRET",
                "M50000999999999999.mp3?vkey=TOKEN_SECRET","F000"+MEDIA+".mp3?vkey=TOKEN_SECRET",
                "M500"+MEDIA+".flac?vkey=TOKEN_SECRET","M500"+MID+MEDIA+".mp3?notvkey=TOKEN_SECRET",
                "M500"+MEDIA+".mp3?vkey=TOKEN_SECRET&vkey=TOKEN_SECRET",
                "M500"+MEDIA+".mp3?vkey=TOKEN_SECRET_SUFFIX","M500"+MEDIA+".mp3?vkey=OTHER&foo=vkey=TOKEN_SECRET",
                "/M500"+MEDIA+".mp3?vkey=TOKEN_SECRET","../M500"+MEDIA+".mp3?vkey=TOKEN_SECRET",
                "M500"+MEDIA+".mp3#vkey=TOKEN_SECRET","M500%"+MEDIA+".mp3?vkey=TOKEN_SECRET")) {
            var bad=new Fixture();bad.preview=preview;fails(()->resolve(bad,null,QqMusicClient.Quality.STANDARD));check(bad.requests.size()==2);
        }
        var echo=new Fixture();echo.badEcho=true;fails(()->resolve(echo,null,QqMusicClient.Quality.STANDARD));
        var bad=new Fixture();bad.vkeyMid="00999999999999";Fixture wrongMid=bad;fails(()->resolve(wrongMid,null,QqMusicClient.Quality.STANDARD));
        bad=new Fixture();bad.mediaSize="4095";Fixture shortMedia=bad;fails(()->resolve(shortMedia,null,QqMusicClient.Quality.STANDARD));
        bad=new Fixture();bad.denied=1;Fixture denied=bad;fails(()->resolve(denied,null,QqMusicClient.Quality.STANDARD));
        check(denied.bodies.stream().noneMatch(b->b.toString().contains("RS02")));
        var requests=new Fixture();
        try(var r=new RequestScope(()->true)) {
            var search=requests.catalog(null).load(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.SEARCH,0,1,0,"Test"),r);
            check(search.hasMore() && search.totalCount()==23 && search.items().getFirst().target().equals(MID));
            check(number(object(requests.bodies.getFirst().get("comm")).get("ct"),0)==11);
            var discovery=requests.catalog(null).load(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.DISCOVER,0,1),r);
            check(discovery.items().getFirst().target().equals("123"));
            for(var cover:List.<Map<String,Object>>of(Map.of("big_url","https://music-file.y.qq.com/songlist/u/fixture.jpg?imageView2/4/w/600/h/600"),
                    Map.of("medium_url","http://qpic.y.qq.com/music_cover/fixture/300?n=1"),
                    Map.of("small_url","https://p.qpic.cn/music_cover/fixture/150?n=1"),Map.of())) {
                requests.discoveryCover=cover;
                var page=requests.catalog(null).load(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.DISCOVER,0,1),r);
                check(page.items().getFirst().cover()!=null);
                check(!cover.isEmpty() || page.items().getFirst().cover().toString().equals("https://y.qq.com/cover.jpg"));
            }
            var all=requests.catalog(null).loadAll(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.FOLDER,123,1),r);
            check(all.items().size()==23 && all.items().getLast().title().equals("QQ fixture 22"));
            requests.truncated=true;fails(()->requests.catalog(null).loadAll(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.FOLDER,123,1),r));
            fails(()->requests.catalog(null).load(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.MINE,0,1),r));
            try(var a=new AutoCloseableAccount(account())) {
                requests.truncated=false;
                check(requests.catalog(a.value).loadAll(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.FOLDER,123,1),r).items().size()==23);
                requests.truncated=true;
                fails(()->requests.catalog(a.value).loadAll(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.FOLDER,123,1),r));
                var mine=requests.catalog(a.value).load(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.MINE,0,1),r);
                check(mine.items().getFirst().target().equals(Long.toString(Long.MAX_VALUE)));
                check(requests.catalog(a.value).load(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.SAVED,0,1),r).items().isEmpty());
            }
            try(var a=new AutoCloseableAccount(account())) {
                var cache=new QqMusicCatalog.Recommendations();
                var catalog=new QqMusicCatalog(a.value,CLOCK,requests,cache);
                int start=requests.bodies.size();
                var daily=catalog.load(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.DAILY,0,1),r);
                var second=new QqMusicCatalog(a.value,CLOCK,requests,cache).load(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.DAILY,0,2),r);
                check(daily.items().size()==20 && daily.hasMore() && second.items().size()==10 && !second.hasMore());
                check(second.items().getFirst().title().equals("QQ fixture 20") && requests.bodies.size()==start+1);
                var radio=catalog.load(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.PERSONAL_RADIO,0,1),r);
                check(radio.items().size()==2 && radio.hasMore());
                var last=object(requests.bodies.getLast().get("req_0"));
                check(number(object(last.get("param")).get("id"),0)==99 && number(object(requests.bodies.getLast().get("comm")).get("ct"),0)==19);
                check(number(object(requests.bodies.getLast().get("comm")).get("g_tk"),0)==QqMusicQrClient.hash("COOKIE_SECRET",5381));
                cache.clear();catalog.load(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.DAILY,0,1),r);
                check(requests.bodies.size()==start+3);
                a.value.retire();fails(()->catalog.load(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.DAILY,0,1),r));
            }
            fails(()->requests.catalog(null).load(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.DAILY,0,1),r));
            fails(()->requests.catalog(null).load(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.PERSONAL_RADIO,0,1),r));
            var lyric=requests.client(null).lyrics(QqMusicTarget.parse(MID),r);
            check(lyric.contains("[00:01.000]Original") && lyric.contains("[00:01.000]Translation") && !lyric.contains("//"));
        }
        var logical=new OnlinePlaybackController.Target(AccountPlatform.QQMUSIC,MID);
        Path root=Files.createTempDirectory("salt-qq-store-");
        try {
            try(var store=new SavedTrackStore(root)) {store.save(logical,new Catalog.Item(Catalog.Kind.TRACK,MID,"Title","Manual Artist",120000,true));}
            try(var store=new SavedTrackStore(root)) {var saved=store.entries().getFirst();check(saved.target().equals(logical) && saved.artist().equals("Manual Artist"));}
        } finally {
            try(var paths=Files.walk(root)) {for(var p:paths.sorted(Comparator.reverseOrder()).toList())if(p.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))Files.deleteIfExists(p);}
        }
        var payload=jsonBytes(Map.of("req_0",Map.of("module","test","method","test","param",Map.of()),"comm",Map.of()));
        var connection=PinnedHttpsConnection.qqMusicPost(GATEWAY,payload);
        check(connection.getRequestMethod().equals("POST") && connection.getRequestProperty("Content-Type").startsWith("application/json"));connection.disconnect();
        fails(()->PinnedHttpsConnection.qqMusicPost(URI.create("https://u.y.qq.com/evil"),payload));
        fails(()->new QqMusicHttp(new Fixture()).exchange(URI.create("https://graph.qq.com/evil"),null,"",new RequestScope(()->true),100));
        System.out.println("PASS: "+checks+" QQ identity/catalog/quality/full-track/lyrics/saved-target/account-isolation checks; synthetic only");
    }
    private static String textUnchecked(Map<String,Object> body) {return body.toString();}
    private static final class AutoCloseableAccount implements AutoCloseable {
        final AccountCredentials value;AutoCloseableAccount(AccountCredentials value) {this.value=value;}
        public void close() {value.retire();}
    }
}
