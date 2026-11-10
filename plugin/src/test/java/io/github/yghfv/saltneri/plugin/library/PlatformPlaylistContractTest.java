package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

/** Real platform adapters against HTTP fixtures; never writes to a live user's library. */
public final class PlatformPlaylistContractTest {
    private static int checks;
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"),ZoneOffset.UTC);
    private static final String HASH="0123456789ABCDEF0123456789ABCDEF",MID="00123456789abc";
    private static void check(boolean ok){checks++;if(!ok)throw new AssertionError("Platform playlist "+checks);}
    @FunctionalInterface private interface Action{void run() throws Exception;}
    private static void fails(Action action) throws Exception {
        try{action.run();throw new AssertionError("Expected rejection");}
        catch(IOException | IllegalStateException expected){check(!String.valueOf(expected.getMessage()).contains("SECRET"));}
    }
    private static AccountCredentials account(AccountPlatform platform) throws IOException {
        var jar=new ScopedCookies(platform,CLOCK);
        var cookies=new ArrayList<>(List.of(platform.loginCookie()+"=SECRET; Path=/; Secure"));
        if(platform==AccountPlatform.NETEASE)cookies.add("__csrf=fixture_csrf; Path=/; Secure");
        if(platform==AccountPlatform.BILIBILI)cookies.add("bili_jct=fixture_csrf; Path=/; Secure");
        if(platform==AccountPlatform.QQMUSIC){cookies.add("tmeLoginType=1; Path=/; Secure");cookies.add("euin=fixture_euin; Path=/; Secure");}
        if(platform==AccountPlatform.APPLEMUSIC)cookies.add("storefront=us; Path=/; Secure");
        jar.accept(platform.accountUri(),cookies);
        return new AccountCredentials(platform,"42",jar.snapshot(),CLOCK);
    }
    private static final class Response extends HttpURLConnection {
        final byte[] body;boolean closed;final Map<String,List<String>> headers;
        Response(URI uri,int status,Object body) throws IOException {
            super(uri.toURL());responseCode=status;this.body=body instanceof String s?s.getBytes(StandardCharsets.UTF_8):BoundedJson.encode(body);
            headers=Map.of("Content-Type",List.of("application/json"));
        }
        public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){closed=true;}
        public int getResponseCode(){return responseCode;}public InputStream getInputStream(){return new ByteArrayInputStream(body);}
        public long getContentLengthLong(){return body.length;}
        public Map<String,List<String>> getHeaderFields(){return headers;}
        public String getHeaderField(String name){return name.equalsIgnoreCase("Content-Type")?"application/json":null;}
    }
    private static final class Fixture implements PlatformPlaylistGateway.Opener {
        final List<Response> responses=new CopyOnWriteArrayList<>();final List<Map<String,Object>> writes=new CopyOnWriteArrayList<>();
        boolean contains,denied,wrongOwner;int writeCalls;
        public HttpURLConnection open(AccountPlatform platform,URI uri,byte[] body) throws IOException {
            Object payload;int status=200;
            switch(platform) {
                case NETEASE -> payload=switch(uri.getPath()) {
                    case "/weapi/w/nuser/account/get" -> Map.of("code",200,"account",Map.of("id",42),"profile",Map.of("userId",42));
                    case "/weapi/user/playlist" -> Map.of("code",200,"more",false,"playlist",List.of(
                            Map.of("id",10,"name","Writable","creator",Map.of("userId",wrongOwner?99:42)),
                            Map.of("id",11,"name","Someone else's","creator",Map.of("userId",99),"subscribed",true)));
                    case "/api/v6/playlist/detail" -> Map.of("code",200,"playlist",Map.of("id",10,"trackIds",contains?List.of(Map.of("id",123)):List.of()));
                    case "/weapi/playlist/manipulate/tracks","/weapi/playlist/subscribe" -> {writeCalls++;yield Map.of("code",denied?500:200);}
                    default -> throw new AssertionError("Unexpected NetEase request "+uri.getPath());
                };
                case QQMUSIC -> {
                    if(!uri.getHost().equals("u.y.qq.com")) {
                        payload=Map.of("code",0,"data",Map.of("total",0,"cdlist",List.of()));break;
                    }
                    var root=BoundedJson.object(BoundedJson.parse(body));var call=BoundedJson.object(root.get("req_0"));
                    String method=(String)call.get("method");var param=BoundedJson.object(call.get("param"));Object data;
                    data=switch(method) {
                        case "GetPlaylistByUin" -> Map.of("v_playlist",List.of(Map.of("dirId",201,"tid",0,"dirName","我喜欢的音乐"),
                                Map.of("dirId",301,"tid",10,"dirName","Writable")));
                        case "CgiGetDiss" -> Map.of("dirinfo",Map.of("title","Writable","total_song_num",contains?1:0),
                                "songlist",contains?List.of(qqSong()):List.of(),"hasmore",false);
                        case "get_song_detail_yqq" -> Map.of("track_info",qqSong());
                        case "AddSonglist" -> {writeCalls++;writes.add(param);yield Map.of("retCode",denied?1:0);}
                        case "FavPlaylist" -> {writeCalls++;writes.add(param);yield Map.of("result",denied?1:0,"v_failedPlaylistId",List.of());}
                        default -> throw new AssertionError("Unexpected QQ request "+method);
                    };
                    payload=Map.of("code",0,"req_0",Map.of("code",0,"data",data));
                }
                case KUGOU -> payload=switch(uri.getPath()) {
                    case "/user/playlist" -> Map.of("has_next",0,"info",List.of(Map.of("listid",10,"list_create_gid","collection_1_2_3_4","name","Writable","type",0),
                            Map.of("listid",11,"list_create_gid","collection_9_2_3_4","name","Subscribed","type",1)));
                    case "/playlist/track/all" -> Map.of("count",contains?1:0,"songs",contains?List.of(Map.of("hash",HASH,"FileName","Song","SingerName","Artist","Duration",120)):List.of());
                    case "/privilege/lite" -> Map.of("list",List.of(Map.of("hash",HASH,"album_id",88,"album_audio_id",99)));
                    case "/playlist/tracks/add","/playlist/add" -> {
                        writeCalls++;if(body.length>0)writes.add(BoundedJson.object(BoundedJson.parse(body)));
                        yield Map.of("status",denied?0:1,"success",!denied);
                    }
                    default -> throw new AssertionError("Unexpected Kugou request "+uri.getPath());
                };
                case APPLEMUSIC -> {
                    if(uri.getHost().equals("music.apple.com")) {
                        if(uri.getPath().startsWith("/assets/")) {
                            String jwt="eyJhbGciOiJFUzI1NiJ9."+Base64.getUrlEncoder().withoutPadding().encodeToString(
                                    BoundedJson.encode(Map.of("iss","AMPWebPlay","exp",CLOCK.instant().plusSeconds(7200).getEpochSecond())))+".SECRET_SIGNATURE";
                            payload="const token='"+jwt+"';";
                        } else payload="<script src=\"/assets/index~fixture.js\"></script>";
                    } else if(body!=null) {
                        writeCalls++;writes.add(BoundedJson.object(BoundedJson.parse(body)));status=denied?403:204;payload="";
                    } else if(uri.getPath().equals("/v1/me/library/playlists"))payload=Map.of("data",List.of(
                            Map.of("id","p.mine","type","library-playlists","attributes",Map.of("name","Writable","canEdit",true)),
                            Map.of("id","p.other","type","library-playlists","attributes",Map.of("name","Read only","canEdit",false))));
                    else payload=Map.of("data",contains?List.of(Map.of("id","i.track","type","library-songs","attributes",
                            Map.of("name","Song","artistName","Artist","albumName","Album","durationInMillis",120000,"playParams",Map.of("catalogId","123")))):List.of());
                }
                case BILIBILI -> payload=switch(uri.getPath()) {
                    case "/x/web-interface/nav" -> Map.of("code",0,"data",Map.of("isLogin",true,"mid",42,"wbi_img",
                            Map.of("img_url","https://i0.hdslb.com/bfs/wbi/abcdefghijklmnopqrstuvwxyz012345.png","sub_url","https://i0.hdslb.com/bfs/wbi/012345abcdefghijklmnopqrstuvwxyz.png")));
                    case "/x/v3/fav/folder/created/list" -> Map.of("code",0,"data",Map.of("count",1,"has_more",false,"list",List.of(
                            Map.of("id",10,"title","Writable","mid",42,"type",0,"state",0))));
                    case "/x/v3/fav/folder/collected/list" -> Map.of("code",0,"data",Map.of("count",0,"has_more",false,"list",List.of()));
                    case "/x/v3/fav/resource/list" -> Map.of("code",0,"data",Map.of("info",Map.of("id",10,"title","Writable","media_count",contains?1:0),
                            "has_more",false,"medias",contains?List.of(Map.of("id",99,"bvid","BV17x411w7KC","title","Song","type",2,"duration",120)):List.of()));
                    case "/x/web-interface/view" -> Map.of("code",0,"data",Map.of("aid",99));
                    case "/x/v3/fav/resource/deal","/x/v3/fav/folder/fav","/x/v3/fav/season/fav" -> {
                        writeCalls++;String form=new String(body,StandardCharsets.US_ASCII);check(form.contains("csrf=fixture_csrf"));
                        writes.add(Map.of("form",form));yield Map.of("code",denied?-1:0);
                    }
                    default -> throw new AssertionError("Unexpected Bilibili request "+uri.getPath());
                };
                default -> throw new AssertionError();
            }
            var response=new Response(uri,status,payload);responses.add(response);return response;
        }
        private static Map<String,Object> qqSong(){return Map.of("mid",MID,"id",123,"type",0,"title","Song","interval",120,
                "singer",List.of(Map.of("name","Artist")),"album",Map.of("name","Album","mid","00123456789abc"),"file",Map.of("media_mid",MID));}
    }
    private static void adapters() throws Exception {
        for(var platform:AccountPlatform.values()) {
            var account=account(platform);var fixture=new Fixture();var gateway=new PlatformPlaylistGateway(fixture,CLOCK);
            var song=new Item(Kind.TRACK,switch(platform){case NETEASE->"123";case QQMUSIC->MID;case KUGOU->HASH;case APPLEMUSIC->"us:123";case BILIBILI->"BV17x411w7KC";},
                    "Song","Artist",120000,true);
            try(var request=new RequestScope(()->true)) {
                var choices=gateway.choices(account,request);check(!choices.isEmpty());check(choices.stream().noneMatch(c->c.title().contains("other")));
                var choice=choices.getLast();check(gateway.members(account,choice,request).isEmpty());
                gateway.add(account,song,choice,request);check(fixture.writeCalls==1);
                if(platform==AccountPlatform.QQMUSIC)check(((Number)fixture.writes.getFirst().get("dirId")).longValue()==301
                        && ((Number)fixture.writes.getFirst().get("tid")).longValue()==10 && fixture.writes.getFirst().get("v_songInfo") instanceof List<?>);
                if(platform==AccountPlatform.KUGOU)check(fixture.writes.getFirst().get("listId").equals("10"));
                if(platform==AccountPlatform.APPLEMUSIC)check(fixture.responses.getLast().getRequestProperty("Music-User-Token").equals("SECRET")
                        && fixture.responses.getLast().getRequestProperty("Cookie")==null);
                fixture.contains=true;check(gateway.members(account,choice,request).contains(PlatformPlaylistGateway.normalize(platform,song.target())));
                gateway.add(account,song,choice,request);check(fixture.writeCalls==1);fixture.contains=false;
                var list=new Item(Kind.FOLDER,platform==AccountPlatform.KUGOU?"collection_8_7_6_5":platform==AccountPlatform.APPLEMUSIC?"playlists/p.mine":"88","External","Owner",0,true);
                if(platform==AccountPlatform.APPLEMUSIC)check(gateway.subscribed(account,list,request));
                else {check(!gateway.subscribed(account,list,request));gateway.subscribe(account,list,request);check(fixture.writeCalls==2);}
                fixture.denied=true;fails(()->gateway.add(account,song,choice,request));
                if(platform==AccountPlatform.NETEASE){fixture.wrongOwner=true;fails(()->gateway.add(account,song,choice,request));}
                account.retire();int calls=fixture.responses.size();fails(()->gateway.choices(account,request));check(fixture.responses.size()==calls);
                check(fixture.responses.stream().allMatch(r->r.closed));
            } finally {account.retire();}
        }
    }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean() && System.nanoTime()<end)Thread.sleep(10);
        check(condition.getAsBoolean());
    }
    private static void controller() throws Exception {
        var account=account(AccountPlatform.NETEASE);var selected=new AtomicReference<>(account);var current=new AtomicBoolean(true);
        var membership=new AtomicInteger();var writes=new AtomicInteger();var notifications=new CopyOnWriteArrayList<String>();var changed=new AtomicInteger();
        var song=new Item(Kind.TRACK,"123","Song","Artist",120000,true);
        var selection=new LibraryController.Selection(AccountPlatform.NETEASE,song,account);
        var block=new AtomicReference<CountDownLatch>();
        try(var controller=new PlatformPlaylistController(new PlatformPlaylistController.Gateway() {
            public List<PlatformPlaylistController.Choice> choices(AccountCredentials a,RequestScope r){return List.of(
                    new PlatformPlaylistController.Choice("10","10","Already saved"),new PlatformPlaylistController.Choice("11","11","Writable"));}
            public Set<String> members(AccountCredentials a,PlatformPlaylistController.Choice c,RequestScope r){
                membership.incrementAndGet();return c.id().equals("10")?Set.of("123"):Set.of();
            }
            public boolean subscribed(AccountCredentials a,Item item,RequestScope r){return false;}
            public void add(AccountCredentials a,Item item,PlatformPlaylistController.Choice c,RequestScope r) throws IOException {
                CountDownLatch latch=block.get();
                if(latch!=null)try{latch.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Interrupted");}
                r.check();writes.incrementAndGet();
            }
            public void subscribe(AccountCredentials a,Item item,RequestScope r){r.check();writes.incrementAndGet();}
        },p->selected.get(),()->true,t->current.get(),p->changed.incrementAndGet(),notifications::add,CLOCK)) {
            controller.load("token",selection);waitFor(()->!controller.snapshot("token",selection).loading());
            var state=controller.snapshot("token",selection);check(state.entries().getFirst().checked() && state.entries().getFirst().contains());
            fails(()->controller.add("token",selection,"10"));check(writes.get()==0);
            controller.load("token",selection);waitFor(()->!controller.snapshot("token",selection).loading());check(membership.get()==2);
            controller.add("token",selection,"11");waitFor(()->writes.get()==1 && changed.get()==1 && notifications.size()==1);
            controller.load("token",selection);waitFor(()->!controller.snapshot("token",selection).loading());check(membership.get()==4);
            var latch=new CountDownLatch(1);block.set(latch);controller.add("token",selection,"11");selected.set(null);latch.countDown();
            Thread.sleep(100);check(writes.get()==1 && controller.snapshot("token",selection).entries().isEmpty());selected.set(account);
            controller.load("token",selection);waitFor(()->!controller.snapshot("token",selection).loading());
            current.set(false);fails(()->controller.add("token",selection,"11"));check(writes.get()==1);
        } finally {account.retire();}
    }
    public static void main(String[] args) throws Exception {
        adapters();controller();
        System.out.println("PASS: "+checks+" five-platform writable ownership/membership/duplicate guard/write/rejection/CSRF/token isolation/cache/revocation contracts; fixture traffic only");
    }
}
