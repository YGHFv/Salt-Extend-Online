package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

/** Recommendation protocol/window fixtures only: no account login, upstream music or queue. */
public final class NeteaseRecommendationContractTest {
    private static int checks;
    private static void check(boolean value) {checks++;if(!value)throw new AssertionError("NetEase recommendation check "+checks);}
    @FunctionalInterface interface Action {void run() throws Exception;}
    private static void fails(Action action) throws Exception {
        try {action.run();throw new AssertionError("Expected rejection");}
        catch(IOException | IllegalArgumentException | java.util.concurrent.CancellationException expected) {check(!String.valueOf(expected.getMessage()).contains("SECRET"));}
    }
    private static final class MutableClock extends Clock {
        Instant now=Instant.parse("2026-11-09T14:00:00Z");
        public Instant instant(){return now;}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(now,zone);}
    }
    private static final class Response extends HttpURLConnection {
        final byte[] body;boolean disconnected;
        Response(URI uri,Object body) throws IOException {super(uri.toURL());this.body=BoundedJson.encode(body);}
        public void connect(){}
        public int getResponseCode(){return 200;}
        public String getHeaderField(String name){return name.equalsIgnoreCase("Content-Type")?"application/json":null;}
        public InputStream getInputStream(){return new ByteArrayInputStream(body);}
        public Map<String,List<String>> getHeaderFields(){return Map.of();}
        public void disconnect(){disconnected=true;}
        public boolean usingProxy(){return false;}
    }
    private static final class Fixture implements CatalogHttp.Opener {
        final List<Response> requests=new ArrayList<>();
        int dailyCalls,radioCalls,playlistCalls,newCalls,verifyCalls;
        boolean denied,malformed,repeated;
        public HttpURLConnection open(URI uri,byte[] form) throws IOException {
            check(form!=null && uri.getHost().equals("music.163.com"));
            Object body;
            switch(uri.getPath()) {
                case "/weapi/w/nuser/account/get" -> {verifyCalls++;body=Map.of("code",200,"account",Map.of("id",42),"profile",Map.of("userId",42,"nickname","Fixture"));}
                case "/weapi/v3/discovery/recommend/songs" -> {
                    dailyCalls++;
                    var songs=new ArrayList<Object>();for(int id=1;id<=30;id++)songs.add(song(id));
                    songs.add(song(1));songs.add(Map.of("id","invalid","name","Broken"));songs.add("bad row");
                    body=denied?Map.of("code",301):malformed?Map.of("code",200,"data",Map.of()):Map.of("code",200,"data",Map.of("dailySongs",songs));
                }
                case "/weapi/v1/radio/get" -> {
                    radioCalls++;var songs=new ArrayList<Object>();
                    for(int id=1;id<=3;id++)songs.add(song((repeated?0:(radioCalls-1)*3)+id));
                    body=Map.of("code",200,"data",songs);
                }
                case "/weapi/v1/discovery/recommend/resource" -> {playlistCalls++;body=Map.of("code",200,"recommend",List.of(Map.of("id",123,"name","Personal playlist","picUrl","https://p1.music.126.net/fixture.jpg","creator",Map.of("nickname","Creator"))));}
                case "/weapi/personalized/newsong" -> {newCalls++;body=Map.of("code",200,"result",List.of(Map.of("song",song(100))));}
                default -> throw new AssertionError("Unexpected endpoint "+uri.getPath());
            }
            var response=new Response(uri,body);requests.add(response);return response;
        }
        private Map<String,Object> song(int id) {
            return Map.of("id",id,"name","Song "+id,"fee",0,"duration",123000,"artists",List.of(Map.of("name","Artist")),
                    "album",Map.of("picUrl","https://p1.music.126.net/fixture.jpg"));
        }
    }
    private static AccountCredentials account(Clock clock) throws IOException {
        var jar=new ScopedCookies(AccountPlatform.NETEASE,clock);
        jar.accept(URI.create("https://music.163.com/"),List.of("MUSIC_U=FIXTURE_SECRET; Path=/; Secure; Max-Age=86400","__csrf=fixture_csrf; Path=/; Secure; Max-Age=86400"));
        return new AccountCredentials(AccountPlatform.NETEASE,"42",jar.snapshot(),clock);
    }
    private static Query query(View view,int page){return new Query(AccountPlatform.NETEASE,view,0,page);}
    private static CatalogClient client(AccountCredentials account,Clock clock,Fixture fixture,CatalogPageCache cache) {
        return new CatalogClient(account,clock,fixture,cache.discovery,cache.sessions,cache.qqRecommendations,cache.appleHttp,cache.netRecommendations);
    }
    public static void main(String[] args) throws Exception {
        var clock=new MutableClock();var fixture=new Fixture();var cache=new CatalogPageCache(clock);
        var account=account(clock);
        try(var request=new RequestScope(()->true)) {
            var first=client(account,clock,fixture,cache).load(query(View.DAILY,1),request);
            var second=client(account,clock,fixture,cache).load(query(View.DAILY,2),request);
            check(first.items().size()==20 && second.items().size()==10 && second.items().getFirst().target().equals("21"));
            check(first.totalCount()==30 && first.hasMore() && !second.hasMore());
            check(fixture.dailyCalls==1 && fixture.verifyCalls==1);
            var window=CatalogPaging.load(query(View.DAILY,1),30,request,q->client(account,clock,fixture,cache).load(q,request));
            check(window.items().size()==30 && !window.hasMore() && fixture.dailyCalls==1);
            check(window.items().getFirst().cover()!=null && window.items().getFirst().subtitle().equals("Artist"));
            var fm=CatalogPaging.load(query(View.PERSONAL_RADIO,1),30,request,q->client(account,clock,fixture,cache).load(q,request));
            check(fm.items().size()==30 && fm.hasMore() && fixture.radioCalls==14);
            check(fm.items().getLast().target().equals("30"));
            var next=CatalogPaging.load(query(View.PERSONAL_RADIO,2),30,request,q->client(account,clock,fixture,cache).load(q,request));
            check(next.items().size()==30 && next.items().getFirst().target().equals("31") && next.items().getLast().target().equals("60"));
            int calls=fixture.radioCalls;
            client(account,clock,fixture,cache).load(query(View.PERSONAL_RADIO,1),request);
            check(calls==fixture.radioCalls);
            var lists=client(account,clock,fixture,cache).load(query(View.PERSONAL_PLAYLISTS,1),request);
            check(lists.items().getFirst().kind()==Kind.FOLDER && lists.items().getFirst().target().equals("123") && lists.items().getFirst().subtitle().equals("Creator"));
            check(fixture.requests.stream().allMatch(r->r.disconnected && r.getRequestProperty("Cookie").contains("FIXTURE_SECRET")));
            clock.now=clock.now.plusSeconds(3*3600); // Shanghai midnight, while credentials remain active.
            client(account,clock,fixture,cache).load(query(View.DAILY,1),request);check(fixture.dailyCalls==2);
            cache.clear();
            client(account,clock,fixture,cache).load(query(View.DAILY,1),request);check(fixture.dailyCalls==3);
            var publicFixture=new Fixture();
            var publicSongs=new CatalogClient(null,clock,publicFixture).load(query(View.NEW_SONGS,1),request);
            check(publicSongs.items().size()==1 && publicSongs.items().getFirst().target().equals("100"));
            check(publicFixture.verifyCalls==0 && publicFixture.requests.getFirst().getRequestProperty("Cookie")==null);
            for(View view:List.of(View.DAILY,View.PERSONAL_RADIO,View.PERSONAL_PLAYLISTS)) {
                int before=publicFixture.requests.size();
                fails(()->new CatalogClient(null,clock,publicFixture).load(query(view,1),request));
                check(before==publicFixture.requests.size());
            }
            fixture.denied=true;cache.clear();
            fails(()->client(account,clock,fixture,cache).load(query(View.DAILY,1),request));
            fixture.denied=false;fixture.malformed=true;cache.clear();
            fails(()->client(account,clock,fixture,cache).load(query(View.DAILY,1),request));
            fixture.malformed=false;fixture.repeated=true;cache.clear();
            var end=client(account,clock,fixture,cache).load(query(View.PERSONAL_RADIO,1),request);
            check(end.items().size()==3 && !end.hasMore());
            try(var cancelled=new RequestScope(()->false)) {fails(()->client(account,clock,fixture,cache).load(query(View.DAILY,1),cancelled));}
            account.retire();
            fails(()->client(account,clock,fixture,cache).load(query(View.DAILY,1),request));
            for(View view:List.of(View.DAILY,View.PERSONAL_RADIO,View.PERSONAL_PLAYLISTS,View.NEW_SONGS))
                fails(()->new Query(AccountPlatform.APPLEMUSIC,view,0,1));
        } finally {account.retire();}
        System.out.println("PASS: "+checks+" NetEase daily/private playlist/new-song/FM auth, snapshot, remainder, refresh, date and cancellation checks; fixtures only");
    }
}
