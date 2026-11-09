package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.kugou.*;
import io.github.yghfv.saltneri.plugin.net.*;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import io.github.yghfv.saltneri.plugin.host.SavedTrackStore;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

/** Actual adapters against bounded HTTP fixtures; no remote login or media playback. */
public final class KugouContractTest {
    private static int checks;
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"),ZoneOffset.UTC);
    private static final String HASH="0123456789ABCDEF0123456789ABCDEF";
    private static void check(boolean value) {checks++;if(!value)throw new AssertionError("Kugou check "+checks);}
    @FunctionalInterface interface Action {void run() throws Exception;}
    private static void fails(Action action) throws Exception {
        try {action.run();throw new AssertionError("Expected rejection");}
        catch(IOException | IllegalArgumentException | java.util.concurrent.CancellationException expected) {check(!String.valueOf(expected.getMessage()).contains("SECRET"));}
    }
    private static final class Response extends HttpURLConnection {
        final byte[] bytes;final Map<String,String> headers;boolean released;
        Response(URI uri,int status,Object value,Map<String,String> headers) throws IOException {
            super(uri.toURL());responseCode=status;bytes=value instanceof byte[] b?b:BoundedJson.encode(value);this.headers=headers;
        }
        public void connect() {}
        public boolean usingProxy() {return false;}
        public void disconnect() {released=true;}
        public int getResponseCode() {return responseCode;}
        public InputStream getInputStream() {return new ByteArrayInputStream(bytes);}
        public long getContentLengthLong() {return bytes.length;}
        public String getHeaderField(String key) {return headers.get(key);}
    }
    private static final class Fixture implements KugouHttp.Opener {
        final List<Response> requests=new ArrayList<>();
        int discovery,searchRequests,qrStatus=1;boolean wrongHash,preview,shortMedia,denied,highQuality,freePart;
        public HttpURLConnection open(URI uri) throws IOException {
            Object body;Map<String,String> headers=Map.of();int code=200;
            String path=uri.getPath();
            switch(path) {
                case "/login/qr/key" -> {
                    body=Map.of("qrcode","0123456789abcdef0123456789abcdef","qrcode_img","data:image/png;base64,"+Base64.getEncoder().encodeToString(new byte[]{(byte)137,80,78,71,13,10,26,10}));
                    headers=Map.of("X-Kg-Session-Id","SESSION_SECRET");
                }
                case "/login/qr/check" -> body=qrStatus==4?Map.of("status",4,"token","TOKEN_SECRET","userid",42):Map.of("status",qrStatus);
                case "/user/detail" -> body=Map.of("nickname","Fixture","userid",42);
                case "/top/playlist" -> {
                    discovery++;var rows=new ArrayList<Object>();
                    int page=Integer.parseInt(query(uri,"page"));
                    for(int index=(page-1)*35;index<page*35;index++)rows.add(Map.of("global_collection_id","collection_1_42_"+(index+1)+"_0",
                            "specialname","Playlist "+index,"imgurl","http://imge.kugou.com/cover.jpg"));
                    body=Map.of("has_next",1,"special_list",rows);
                }
                case "/search" -> {
                    searchRequests++;var rows=new ArrayList<Object>();int page=Integer.parseInt(query(uri,"page"));
                    int end=Math.min(page*30,45);
                    for(int index=(page-1)*30;index<end;index++) {
                        var song=new LinkedHashMap<String,Object>(searchSong());
                        song.put("FileHash",String.format(Locale.ROOT,"%032X",index+1));
                        song.put("FileName","Artist - Fixture "+index);rows.add(song);
                    }
                    body=rows;
                }
                case "/user/playlist" -> {
                    var rows=new ArrayList<Object>();int page=Integer.parseInt(query(uri,"page"));
                    for(int index=(page-1)*20;index<Math.min(page*20,60);index++)
                        rows.add(Map.of("type",index%2,"list_create_gid","collection_1_42_"+(index+1)+"_0","name","Account playlist "+index));
                    body=Map.of("has_next",page<3?1:0,"info",rows);
                }
                case "/playlist/track/all" -> body=Map.of("count",1,"songs",List.of(searchSong()));
                case "/privilege/lite" -> body=List.of(Map.of("hash",HASH,"name","Fixture","singername","Artist",
                        "album_id","10","album_audio_id",123,"quality","128",
                        "info",Map.of("duration",120000,"filesize",4096,"image","http://imge.kugou.com/cover.jpg"),
                        "relate_goods",highQuality?List.of(Map.of("hash","A".repeat(32),"quality","320","info",Map.of("duration",120000,"filesize",8192))):List.of()));
                case "/song/url" -> body=Map.of("hash",wrongHash?"F".repeat(32):highQuality && query(uri,"quality").equals("320")?"A".repeat(32):HASH,
                        "url",List.of(preview?"https://fs.kugou.com/preview/clip.mp3":"https://fs.kugou.com/"+HASH+".mp3"),
                        "err_code",denied?403:0,"free_part",freePart);
                case "/search/lyric" -> body=Map.of("status",200,"candidates",List.of(Map.of("id","123","accesskey","ACCESSSECRET","adjust",100)));
                case "/lyric" -> body=Map.of("decodedContent","[offset:50]\n[1000,700]<0,300,0>你<400,300,0>好\n","decodedTranslation","");
                default -> {
                    code=206;body=new byte[]{42};headers=Map.of("Content-Length","1","Content-Range","bytes 0-0/"+(shortMedia?4095:highQuality?8192:4096));
                }
            }
            var response=new Response(uri,code,body,headers);requests.add(response);return response;
        }
        private Map<String,Object> searchSong() {return Map.of("FileHash",HASH,"FileName","Artist - Fixture","SingerName","Artist","Duration",120,"Image","http://imge.kugou.com/cover.jpg");}
    }
    private static String query(URI uri,String name) {for(String part:uri.getQuery().split("&"))if(part.startsWith(name+"="))return part.substring(name.length()+1);return "";}
    public static void main(String[] args) throws Exception {
        check(TrackKey.kugou(HASH.toLowerCase(Locale.ROOT)).stableId().equals("kugou:song:"+HASH));
        for(String bad:List.of("123",HASH+"x","../"+HASH))fails(()->KugouHttp.hash(bad));
        check(CoverSource.parse(AccountPlatform.KUGOU,"http://imge.kugou.com/cover.jpg")!=null);
        check(CoverSource.parse(AccountPlatform.KUGOU,"https://imge.kugou.com.evil/cover.jpg")==null);
        fails(()->MediaLease.requireTarget(MediaLease.Platform.KUGOU,URI.create("https://kugou.com.evil/a")));
        var fixture=new Fixture();var http=new KugouHttp(fixture);
        try(var request=new RequestScope(()->true);var qr=new KugouQrClient(CLOCK,http)) {
            var session=qr.create(request);check(qr.qrImage(session).length==8);
            check(qr.poll(session,request).state()==QrAuthClient.State.WAITING_SCAN);
            check(fixture.requests.getLast().getRequestProperty("X-Kg-Session-Id").equals("SESSION_SECRET"));
            fixture.qrStatus=2;check(qr.poll(session,request).state()==QrAuthClient.State.WAITING_CONFIRMATION);
            fixture.qrStatus=4;var account=qr.poll(session,request).credentials();
            try {
                check(account.userId().equals("42") && KugouHttp.token(account).equals("SESSION_SECRET"));
                check(!account.toString().contains("SECRET"));
                var cache=new KugouCatalog.Cache();var catalog=new KugouCatalog(account,CLOCK,http,cache);
                Query discover=new Query(AccountPlatform.KUGOU,View.DISCOVER,0,1);
                var first=catalog.load(discover,request);var second=catalog.load(new Query(AccountPlatform.KUGOU,View.DISCOVER,0,2),request);
                check(first.items().size()==20 && second.items().size()==20 && fixture.discovery==2);
                check(second.items().getFirst().title().equals("Playlist 20"));
                var window=CatalogPaging.load(discover,30,request,q->catalog.load(q,request));
                check(window.items().size()==30 && window.items().getLast().title().equals("Playlist 29") && fixture.discovery==2);
                var folder=new Query(AccountPlatform.KUGOU,View.FOLDER,1,1,0,"",false,Sort.DEFAULT,first.items().getFirst().target());
                check(catalog.loadAll(folder,request).items().size()==1);
                var search=catalog.load(new Query(AccountPlatform.KUGOU,View.SEARCH,0,1,0,"Fixture"),request);
                check(search.items().getFirst().title().equals("Fixture 0") && search.items().getFirst().subtitle().equals("Artist"));
                var searchWindow=CatalogPaging.load(new Query(AccountPlatform.KUGOU,View.SEARCH,0,1,0,"Fixture"),30,request,q->catalog.load(q,request));
                var nextWindow=CatalogPaging.load(new Query(AccountPlatform.KUGOU,View.SEARCH,0,2,0,"Fixture"),30,request,q->catalog.load(q,request));
                check(searchWindow.items().size()==30 && searchWindow.items().getLast().title().equals("Fixture 29"));
                check(nextWindow.items().size()==15 && nextWindow.items().getFirst().title().equals("Fixture 30") && !nextWindow.hasMore());
                check(fixture.searchRequests==2);
                for(View view:List.of(View.MINE,View.SAVED)) {
                    var playlists=CatalogPaging.load(new Query(AccountPlatform.KUGOU,view,0,1),30,request,q->catalog.load(q,request));
                    check(playlists.items().size()==30 && !playlists.hasMore());
                    check(playlists.items().getLast().title().equals("Account playlist "+(view==View.MINE?58:59)));
                }
                var client=new KugouClient(account,CLOCK,fixture);
                var audio=client.resolve(HASH,"128",account.accountScope(),request);
                check(audio.key().equals(TrackKey.kugou(HASH)) && audio.lease().length()==4096);
                check(fixture.requests.getLast().getRequestProperty("X-Kg-Session-Id")==null && fixture.requests.getLast().getRequestProperty("Cookie")==null);
                check(query(fixture.requests.get(fixture.requests.size()-2).getURL().toURI(),"free_part").equals("false"));
                client.resolve(HASH,"flac",account.accountScope(),request);
                check(query(fixture.requests.get(fixture.requests.size()-2).getURL().toURI(),"quality").equals("128"));
                fixture.highQuality=true;
                check(client.resolve(HASH,"320",account.accountScope(),request).lease().length()==8192);
                check(query(fixture.requests.get(fixture.requests.size()-2).getURL().toURI(),"quality").equals("320"));
                fixture.highQuality=false;fixture.freePart=true;
                fails(()->client.resolve(HASH,"128",account.accountScope(),request));fixture.freePart=false;
                check(client.lyrics(HASH,request).contains("<00:01.150>你<00:01.450><00:01.550>好<00:01.850>"));
                fixture.wrongHash=true;fails(()->client.resolve(HASH,"128",account.accountScope(),request));fixture.wrongHash=false;
                fixture.preview=true;fails(()->client.resolve(HASH,"128",account.accountScope(),request));fixture.preview=false;
                fixture.shortMedia=true;fails(()->client.resolve(HASH,"128",account.accountScope(),request));fixture.shortMedia=false;
                fixture.denied=true;fails(()->client.resolve(HASH,"128",account.accountScope(),request));fixture.denied=false;
                Path root=Files.createTempDirectory("salt-kugou-store-");
                try {
                    try(var store=new SavedTrackStore(root)) {store.save(new OnlinePlaybackController.Target(AccountPlatform.KUGOU,HASH),catalog.load(folder,request).items().getFirst());}
                    try(var store=new SavedTrackStore(root)) {check(store.entries().getFirst().target().id().equals(HASH));}
                } finally {try(var files=Files.walk(root)) {for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
                account.retire();check(!audio.lease().isActive());
                int before=fixture.requests.size();fails(()->client.resolve(HASH,"128",account.accountScope(),request));check(fixture.requests.size()==before);
            } finally {account.retire();}
            fails(()->http.get("/../secret",Map.of(),null,request));
            check(fixture.requests.stream().allMatch(r->r.released));
            try(var cancelled=new RequestScope(()->false)) {fails(()->http.get("/search",Map.of(),null,cancelled));}
        }
        System.out.println("PASS: "+checks+" Kugou QR/session-header/catalog/page/full-track/hash/lyrics/saved-target/revocation checks; fixtures only");
    }
}
