package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.applemusic.*;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

/** Synthetic Apple Web/AMP fixtures; no Apple login or official client playback. */
public final class AppleMusicContractTest {
    private static int checks;
    private static void check(boolean value) {checks++;if(!value)throw new AssertionError("Apple check "+checks);}
    @FunctionalInterface interface Action {void run() throws Exception;}
    private static void fails(Action action) throws Exception {
        try {action.run();throw new AssertionError("Expected rejection");}
        catch(IOException | IllegalArgumentException expected) {check(!String.valueOf(expected.getMessage()).contains("SECRET"));}
    }
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"),ZoneOffset.UTC);
    private static final class Response extends HttpURLConnection {
        final byte[] body;final String location;boolean released;
        Response(URI uri,int code,byte[] body,String location) throws IOException {super(uri.toURL());responseCode=code;this.body=body;this.location=location;}
        public void connect() {}
        public int getResponseCode() {return responseCode;}
        public InputStream getInputStream() {return new ByteArrayInputStream(body);}
        public long getContentLengthLong() {return body.length;}
        public String getHeaderField(String key) {return key.equals("Location")?location:null;}
        public void disconnect() {released=true;}
        public boolean usingProxy() {return false;}
    }
    private static final class Fixture implements AppleMusicHttp.Opener {
        final List<Response> requests=new ArrayList<>();
        int status=200;
        boolean foreignRedirect,missingCatalog;
        public HttpURLConnection open(URI uri) throws IOException {
            Object payload;String location=null;int code=200;
            if(uri.getHost().equals("music.apple.com")) {
                if(uri.getPath().equals("/us/browse")) {code=302;location=foreignRedirect?"https://evil.invalid/SECRET":"/cn";payload="";}
                else if(uri.getPath().equals("/cn")) {code=301;location="https://music.apple.com/cn/new";payload="";}
                else if(uri.getPath().startsWith("/assets/")) {
                    String jwt="eyJhbGciOiJFUzI1NiJ9."+Base64.getUrlEncoder().withoutPadding().encodeToString(
                            BoundedJson.encode(Map.of("iss","AMPWebPlay","exp",CLOCK.instant().plusSeconds(7200).getEpochSecond())))+".SIGNATURE_SECRET";
                    payload="const token='"+jwt+"';";
                } else payload="<script type=\"module\" src=\"/assets/index~test.js\"></script>";
            } else {
                code=status;
                if(uri.getPath().equals("/v1/me/storefront"))payload=Map.of("data",List.of(Map.of("id","cn")));
                else if(uri.getPath().equals("/v1/me/library/albums"))payload=Map.of("data",List.of(Map.of("id","l.abc","type","library-albums",
                        "attributes",Map.of("name","Library album","artistName","Artist","trackCount",21))),"next","/v1/me/library/albums?offset=20");
                else if(uri.getPath().equals("/v1/me/library/playlists"))payload=Map.of("data",List.of(Map.of("id","p.abc","type","library-playlists",
                        "attributes",Map.of("name","Playlist","curatorName","Owner"))));
                else if(uri.getPath().endsWith("/tracks"))payload=Map.of("data",List.of(Map.of("id","i.local","type","library-songs","attributes",
                        Map.of("name","Library song","artistName","Artist","playParams",missingCatalog?Map.of("id","i.local"):Map.of("catalogId","12345")))));
                else if(uri.getPath().endsWith("/charts"))payload=Map.of("results",Map.of("songs",List.of(Map.of("data",List.of(track())))));
                else payload=Map.of("results",Map.of("songs",Map.of("data",List.of(track()),"next","/v1/catalog/cn/search?offset=20")));
            }
            byte[] bytes=payload instanceof String text?text.getBytes(StandardCharsets.UTF_8):BoundedJson.encode(payload);
            var response=new Response(uri,code,bytes,location);requests.add(response);return response;
        }
        private Map<String,Object> track() {return Map.of("id","12345","type","songs","attributes",Map.of("name","Song","artistName","Artist",
                "durationInMillis",123000,"artwork",Map.of("url","https://is1-ssl.mzstatic.com/image/thumb/test/{w}x{h}bb.jpg")));}
    }
    public static void main(String[] args) throws Exception {
        var fixture=new Fixture();var http=new AppleMusicHttp(fixture,CLOCK);
        var raw=AppleMusicAuthClient.fromToken("USER_TOKEN_SECRET",CLOCK);
        try(var request=new RequestScope(()->true);var verifier=new AppleMusicAuthClient(http,CLOCK)) {
            var verified=verifier.verify(raw,request);
            check(verified.isActive() && verified.profile().name().contains("CN"));
            check(!verified.toString().contains("SECRET") && !verified.accountScope().contains("SECRET"));
            fails(()->verified.header(URI.create("https://amp-api.music.apple.com/")));
            var api=fixture.requests.getLast();
            check(api.getRequestProperty("Music-User-Token").equals("USER_TOKEN_SECRET") && api.getRequestProperty("Cookie")==null);
            check(fixture.requests.stream().filter(r->r.getURL().getHost().equals("music.apple.com"))
                    .allMatch(r->r.getRequestProperty("Music-User-Token")==null && r.getRequestProperty("Authorization")==null));
            var catalog=new AppleMusicCatalog(verified,CLOCK,http);
            var mine=catalog.load(new Query(AccountPlatform.APPLEMUSIC,View.MINE,0,1),request);
            check(mine.items().getFirst().target().equals("albums/l.abc") && mine.hasMore());
            var folder=new Query(AccountPlatform.APPLEMUSIC,View.FOLDER,1,1,0,"",false,Sort.DEFAULT,"albums/l.abc");
            var tracks=catalog.load(folder,request);
            check(tracks.items().getFirst().target().equals("cn:12345") && tracks.items().getFirst().enabled());
            fixture.missingCatalog=true;
            tracks=catalog.load(folder,request);check(!tracks.items().getFirst().enabled() && tracks.items().getFirst().target().equals("library:i.local"));
            fixture.missingCatalog=false;
            check(catalog.loadAll(folder,request).items().size()==1);
            var search=catalog.load(new Query(AccountPlatform.APPLEMUSIC,View.SEARCH,0,1,0,"Song Artist"),request);
            check(search.items().getFirst().cover()!=null && search.hasMore());
            check(fixture.requests.getLast().getRequestProperty("Music-User-Token")==null);
            check(catalog.load(new Query(AccountPlatform.APPLEMUSIC,View.DISCOVER,0,1),request).items().size()==1);
            fixture.status=403;fails(()->catalog.load(folder,request));fixture.status=200;
            int before=fixture.requests.size();verified.retire();fails(()->catalog.load(folder,request));check(fixture.requests.size()==before);
            fails(()->http.get("/v1/me/library/../../SECRET",raw,request));
            check(fixture.requests.stream().allMatch(r->r.released && !r.getInstanceFollowRedirects()));
        } finally {raw.retire();}
        fails(()->AppleMusicAuthClient.fromToken("BAD SECRET",CLOCK));
        fails(()->new Query(AccountPlatform.APPLEMUSIC,View.FOLDER,1,1));
        fails(()->new Query(AccountPlatform.QQMUSIC,View.FOLDER,1,1,0,"",false,Sort.DEFAULT,"albums/l.abc"));
        var redirect=new Fixture();redirect.foreignRedirect=true;
        try(var request=new RequestScope(()->true)) {
            fails(()->new AppleMusicHttp(redirect,CLOCK).get("/v1/catalog/us/charts",null,request));check(redirect.requests.size()==1);
        }
        check(CoverSource.parse(AccountPlatform.APPLEMUSIC,"https://is1-ssl.mzstatic.com/image.jpg?token=SECRET")==null);
        check(CoverSource.parse(AccountPlatform.APPLEMUSIC,"https://is1-ssl.mzstatic.com.evil/image.jpg")==null);
        System.out.println("PASS: "+checks+" Apple auth/header isolation, storefront, library IDs, search/charts, pagination, cancellation and redirect checks; synthetic only");
    }
}
