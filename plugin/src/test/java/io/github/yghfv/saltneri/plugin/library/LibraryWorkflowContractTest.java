package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.host.Salt1193PlaybackSession.Metadata;
import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

public final class LibraryWorkflowContractTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Workflow check " + checks); }
    private static void waitFor(BooleanSupplier condition) throws Exception { long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10); check(condition.getAsBoolean()); }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(LibraryController library) { return (List<Map<String,Object>>)(List<?>)library.snapshot().get("items"); }
    private static OnlinePlaybackController.Audio audio(String scope) {
        var expiry = CatalogContractTest.CLOCK.instant().plusSeconds(300);
        var lease = new MediaLease(MediaLease.Platform.NETEASE, URI.create("https://m7.music.126.net/fixture?token=URL_SECRET"), Map.of(), scope, expiry, 4096);
        return new OnlinePlaybackController.Audio(TrackKey.netease("123"), new Metadata("Fixture", "Artist", "Album", "Artist"), lease, expiry);
    }
    public static void main(String[] args) throws Exception {
        retainedCoverSources();
        playlistRejection();
        var account = CatalogContractTest.account(AccountPlatform.NETEASE); AtomicReference<AccountCredentials> accounts = new AtomicReference<>(account);
        List<OnlinePlaybackController.Target> selections = new ArrayList<>(); AtomicInteger navigation = new AtomicInteger();
        try (LibraryController library = new LibraryController(p -> accounts.get(), () -> true, selections::add, navigation::incrementAndGet, CatalogContractTest.CLOCK,
                a -> (q,k,s,r) -> new Page(q.view().name() + q.page(), q.view() == View.FOLDER ? List.of(new Item(Kind.TRACK,"123","Song","Artist",120000,true))
                        : List.of(new Item(Kind.FOLDER,"10","Playlist","Mine",20,true)), true, CatalogContractTest.CLOCK.instant().plusSeconds(300)))) {
            library.pageLimit(20); library.browse(AccountPlatform.NETEASE, View.MINE); waitFor(() -> !rows(library).isEmpty());
            String folderToken = (String)rows(library).getFirst().get("token"); library.choose(folderToken); waitFor(() -> library.snapshot().get("title").equals("FOLDER1"));
            CatalogContractTest.fails(() -> library.choose(folderToken));
            String trackToken = (String)rows(library).getFirst().get("token"); library.choose(trackToken); check(selections.size() == 1 && selections.getFirst().id().equals("123"));
            check(navigation.get() == 2); library.turn(1); waitFor(() -> library.snapshot().get("title").equals("FOLDER2"));
            CatalogContractTest.fails(() -> library.choose(trackToken)); check(navigation.get() == 3);
            check(!new String(BoundedJson.encode(library.snapshot()), java.nio.charset.StandardCharsets.UTF_8).contains("SECRET"));
            account.retire(); check(rows(library).isEmpty()); CatalogContractTest.fails(() -> library.choose(trackToken));
        } finally { account.retire(); }
        var bili = CatalogContractTest.account(AccountPlatform.BILIBILI);
        var fixture = new CatalogContractTest.Fixture();
        fixture.created = "{\"code\":0,\"data\":{\"count\":2,\"list\":["
                + "{\"id\":20,\"mid\":42,\"title\":\"Default\",\"type\":1},"
                + "{\"season_id\":30,\"name\":\"Collection\",\"type\":21}]}}";
        List<OnlinePlaybackController.Target> biliSelections = new ArrayList<>();
        try (LibraryController library = new LibraryController(p -> bili, () -> true, biliSelections::add, () -> { }, CatalogContractTest.CLOCK,
                a -> (q,k,s,r) -> new CatalogClient(a, CatalogContractTest.CLOCK, fixture).load(q,r),
                (a,q,r) -> new CatalogClient(a, CatalogContractTest.CLOCK, fixture).collectionCover(q,r))) {
            library.pageLimit(20); library.browse(AccountPlatform.BILIBILI, View.MINE); waitFor(() -> rows(library).size() == 1);
            check(Boolean.TRUE.equals(rows(library).getFirst().get("enabled")));
            check(!library.snapshot().toString().contains("Collection"));
            library.choose((String)rows(library).getFirst().get("token")); waitFor(() -> library.snapshot().get("view").equals("FOLDER") && rows(library).size() == 1);
            check(biliSelections.isEmpty());
            library.choose((String)rows(library).getFirst().get("token"));
            check(biliSelections.size() == 1 && biliSelections.getFirst().platform() == AccountPlatform.BILIBILI && biliSelections.getFirst().id().equals("BV17x411w7KC"));
            fixture.statusPath = "/x/v3/fav/resource/list"; fixture.status = 412;
            library.turn(1); waitFor(() -> library.snapshot().get("status").toString().contains("BILI_CONTENTS/HTTP=412"));
            check(rows(library).isEmpty() && biliSelections.size() == 1);
            check(!library.snapshot().toString().contains("SECRET"));
            check(library.snapshot().get("error").toString().contains("BILI_CONTENTS/HTTP=412"));
            fixture.created = "{\"code\":0,\"data\":{\"count\":1,\"list\":[{\"id\":900,\"season_id\":30,\"name\":\"Saved collection\",\"upper\":{\"mid\":99}}]}}";
            library.browse(AccountPlatform.BILIBILI, View.SAVED); waitFor(() -> rows(library).size() == 1);
            String collectionToken = (String)rows(library).getFirst().get("token");
            check(library.snapshot().get("error").equals(""));
            try (var request = new io.github.yghfv.saltneri.plugin.net.RequestScope(() -> true)) {
                check(library.cover(collectionToken) == null);
                check(library.resolveCover(collectionToken,request).toString().equals("https://i0.hdslb.com/first.jpg"));
            }
            library.choose(collectionToken); waitFor(() -> library.snapshot().get("view").equals("COLLECTION") && rows(library).size() == 1);
            check(biliSelections.size() == 1);
            String videoToken = (String)rows(library).getFirst().get("token");
            library.playRow(videoToken, biliSelections::add);
            check(biliSelections.size() == 2 && biliSelections.getLast().id().equals("BV17x411w7KC"));
            check(!library.snapshot().toString().contains("Deleted"));
            library.turn(1); waitFor(() -> library.snapshot().get("page").equals(2) && !rows(library).isEmpty());
            check(fixture.requests.getLast().getURL().getQuery().contains("mid=99") && fixture.requests.getLast().getURL().getQuery().contains("season_id=30"));
            check(Boolean.FALSE.equals(library.snapshot().get("more")));
            CatalogContractTest.fails(() -> library.turn(1));
            CatalogContractTest.fails(() -> library.playRow(videoToken, biliSelections::add));
            library.turn(-1); waitFor(() -> library.snapshot().get("page").equals(1) && !rows(library).isEmpty());
            check(Boolean.TRUE.equals(library.snapshot().get("more")));
            CatalogContractTest.fails(() -> library.choose(collectionToken));
            fixture.created = fixture.created.replace("\"name\":\"Saved collection\"", "\"name\":\"Saved collection\",\"cover\":\"https://i0.hdslb.com/old.jpg\"");
            library.invalidate(); // Changed upstream fixture; ordinary navigation may reuse recent metadata.
            library.browse(AccountPlatform.BILIBILI, View.SAVED); waitFor(() -> rows(library).size()==1);
            String covered = (String)rows(library).getFirst().get("token");
            int beforeCover = fixture.requests.size();
            try (var request = new io.github.yghfv.saltneri.plugin.net.RequestScope(() -> true)) {
                check(library.resolveCover(covered,request).toString().equals("https://i0.hdslb.com/old.jpg") && fixture.requests.size()==beforeCover);
                check(library.resolveCover(covered,request,true).toString().equals("https://i0.hdslb.com/first.jpg"));
            }
            library.search("Music & song"); waitFor(() -> rows(library).size()==1 && library.snapshot().get("view").equals("SEARCH"));
            check(library.snapshot().get("totalPages").equals(2));
            String resultToken = (String)rows(library).getFirst().get("token");
            library.playRow(resultToken,biliSelections::add);
            check(biliSelections.getLast().id().equals("BV17x411w7KC"));
            fixture.search = fixture.search.replace("\"page\":1", "\"page\":2");
            library.turn(1); waitFor(() -> Boolean.TRUE.equals(library.snapshot().get("loaded")));
            check(library.snapshot().get("page").equals(2) && library.snapshot().get("totalPages").equals(2));
            check(fixture.requests.getLast().getURL().getQuery().contains("keyword=Music%20%26%20song"));
            CatalogContractTest.fails(() -> library.playRow(resultToken,biliSelections::add));
            library.search("Another"); library.invalidate(); check(rows(library).isEmpty());
        } finally { bili.retire(); }
        AtomicInteger coverLookups = new AtomicInteger(); CountDownLatch lookupEntered = new CountDownLatch(1), lookupRelease = new CountDownLatch(1);
        try (LibraryController library = new LibraryController(p -> null, () -> true, t -> { throw new AssertionError("No artwork playback"); }, () -> {}, CatalogContractTest.CLOCK,
                a -> (q,k,s,r) -> new Page("Artwork", List.of(new Item(Kind.COLLECTION,"30","Collection","",1,true,null,99),
                        new Item(Kind.FOLDER,"40","Deleted","",0,false)), true, CatalogContractTest.CLOCK.instant().plusSeconds(300)),
                (a,q,r) -> { coverLookups.incrementAndGet(); lookupEntered.countDown(); try { lookupRelease.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return URI.create("https://i0.hdslb.com/late.jpg"); })) {
            library.pageLimit(20); library.browse(AccountPlatform.BILIBILI, View.SAVED); waitFor(() -> rows(library).size()==1);
            check(coverLookups.get()==0 && Boolean.TRUE.equals(library.snapshot().get("more")));
            String row=(String)rows(library).getFirst().get("token");
            var task = new java.util.concurrent.FutureTask<URI>(() -> { try(var request=new io.github.yghfv.saltneri.plugin.net.RequestScope(() -> true)) { return library.resolveCover(row,request); } });
            Thread worker=new Thread(task); worker.start(); check(lookupEntered.await(2,TimeUnit.SECONDS));
            library.invalidate(); lookupRelease.countDown(); check(task.get(2,TimeUnit.SECONDS)==null);
            check(coverLookups.get()==1 && rows(library).isEmpty());
        } finally { lookupRelease.countDown(); }
        try (LibraryController library = new LibraryController(p -> null, () -> true, t -> { throw new AssertionError("Failure must not prepare"); }, () -> { },
                CatalogContractTest.CLOCK, a -> (q,k,s,r) -> { throw new java.io.IOException("COOKIE_SECRET https://example.invalid/UID_SECRET"); })) {
            library.pageLimit(20); library.browse(AccountPlatform.NETEASE, View.DISCOVER);
            waitFor(() -> library.snapshot().get("status").toString().contains("UNKNOWN/RESPONSE"));
            check(rows(library).isEmpty() && !library.snapshot().toString().contains("SECRET") && !library.snapshot().toString().contains("https:"));
        }
        try (LibraryController library = new LibraryController(p -> null, () -> true, t -> { throw new AssertionError("No login must not prepare"); }, () -> { },
                CatalogContractTest.CLOCK, a -> (q,k,s,r) -> new CatalogClient(null, CatalogContractTest.CLOCK,
                        (u,f) -> { throw new AssertionError("No login must not request"); }).load(q,r))) {
            library.pageLimit(20); library.browse(AccountPlatform.BILIBILI, View.MINE);
            waitFor(() -> library.snapshot().get("status").toString().contains("BILI_CREATED/LOGIN_REQUIRED"));
            check(rows(library).isEmpty() && library.snapshot().get("status").toString().contains("\u8bf7\u5148\u626b\u7801\u767b\u5f55"));
        }
        accounts.set(null); AtomicInteger queue = new AtomicInteger(); AtomicBoolean armed = new AtomicBoolean(false), allowed = new AtomicBoolean(true);
        AtomicReference<BooleanSupplier> playingScope = new AtomicReference<>();
        try (OnlinePlaybackController online = new OnlinePlaybackController(p -> accounts.get(), allowed::get, armed::get,
                (media,active) -> { queue.incrementAndGet(); playingScope.set(active); }, CatalogContractTest.CLOCK, a -> (t,q,s,r) -> audio(s))) {
            online.prepare(new OnlinePlaybackController.Target(AccountPlatform.NETEASE, "123")); waitFor(() -> Boolean.TRUE.equals(online.snapshot().get("ready")));
            String token = (String)online.snapshot().get("token"); check(queue.get() == 0);
            CatalogContractTest.fails(() -> online.play(token)); check(queue.get() == 0 && Boolean.TRUE.equals(online.snapshot().get("ready")));
            armed.set(true); CatalogContractTest.fails(() -> online.play("stale")); online.play(token); check(queue.get() == 1);
            CatalogContractTest.fails(() -> online.play(token)); check(queue.get() == 1 && !Boolean.TRUE.equals(online.snapshot().get("ready")));
            check(playingScope.get().getAsBoolean());
            AccountCredentials next = CatalogContractTest.account(AccountPlatform.NETEASE); accounts.set(next);
            try {
                online.prepare(new OnlinePlaybackController.Target(AccountPlatform.NETEASE, "123")); waitFor(() -> Boolean.TRUE.equals(online.snapshot().get("ready")));
                check(!playingScope.get().getAsBoolean()); // The old anonymous stream must not adopt the new account.
                String current = (String)online.snapshot().get("token"); next.retire(); CatalogContractTest.fails(() -> online.play(current)); check(queue.get() == 1);
            } finally { next.retire(); accounts.set(null); }
            online.prepare(new OnlinePlaybackController.Target(AccountPlatform.NETEASE, "123")); waitFor(() -> Boolean.TRUE.equals(online.snapshot().get("ready")));
            allowed.set(false); CatalogContractTest.fails(() -> online.play((String)online.snapshot().get("token"))); check(queue.get() == 1);
        }
        try (OnlinePlaybackController failed = new OnlinePlaybackController(p -> null, () -> true, () -> true,
                (media,active) -> { throw new java.io.IOException("COOKIE_SECRET"); }, CatalogContractTest.CLOCK, a -> (t,q,s,r) -> audio(s))) {
            failed.prepare(new OnlinePlaybackController.Target(AccountPlatform.NETEASE,"123")); waitFor(() -> Boolean.TRUE.equals(failed.snapshot().get("ready")));
            String token = (String)failed.snapshot().get("token");
            try { failed.play(token); throw new AssertionError("Expected queue failure"); } catch (java.io.IOException expected) { checks++; }
            check(!failed.snapshot().get("status").toString().contains("SECRET")); check(!Boolean.TRUE.equals(failed.snapshot().get("ready"))); CatalogContractTest.fails(() -> failed.play(token));
        }
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (OnlinePlaybackController cancelled = new OnlinePlaybackController(p -> null, () -> true, () -> true, (m,a) -> queue.incrementAndGet(), CatalogContractTest.CLOCK,
                a -> (t,q,s,r) -> { entered.countDown(); for (;;) { try { release.await(); break; } catch (InterruptedException ignored) { } } return audio(s); })) {
            cancelled.prepare(new OnlinePlaybackController.Target(AccountPlatform.NETEASE,"123")); check(entered.await(2,TimeUnit.SECONDS)); cancelled.invalidate("Navigation changed"); release.countDown();
            check(!Boolean.TRUE.equals(cancelled.snapshot().get("ready"))); check(queue.get() == 1);
        } finally { release.countDown(); }
        System.out.println("PASS: " + checks + " library rows/selection/one-use playback/account capture checks (recording queue)");
    }
    private static void playlistRejection() throws Exception {
        for(boolean unavailable:List.of(false,true)) {
            try(var online=new OnlinePlaybackController(p->null,()->true,()->true,(m,a)->{throw new AssertionError("No single playback");},
                    CatalogContractTest.CLOCK,a->(t,q,s,r)->audio(s))) {
                var list=new LibraryController.Playlist(AccountPlatform.NETEASE,List.of(new Item(Kind.TRACK,"123","Song","Artist",120000,true)),null);
                online.playPlaylist(list,()->{throw new AssertionError("Already armed");},(playlist,active)->{
                    if(unavailable) throw new io.github.yghfv.saltneri.plugin.netease.NeteaseClient.TrackUnavailable(-1);
                    throw new io.github.yghfv.saltneri.plugin.netease.NeteaseClient.PlatformRejected(429);
                });
                waitFor(()->!online.snapshot().get("error").equals(""));
                check(online.snapshot().get("error").toString().contains(unavailable?"TRACK_UNAVAILABLE=-1":"NETEASE_REJECTED=429"));
                check(!online.snapshot().toString().contains("SECRET"));
            }
        }
    }
    private static void retainedCoverSources() throws Exception {
        // QQ supplies album artwork directly; it has no legacy missing-cover lookup.
        for(AccountPlatform platform:List.of(AccountPlatform.BILIBILI,AccountPlatform.NETEASE))for(boolean missing:List.of(true,false)) {
            URI original=URI.create(platform==AccountPlatform.BILIBILI?"https://i0.hdslb.com/old.jpg":"https://p1.music.126.net/old.jpg");
            URI alternate=URI.create(platform==AccountPlatform.BILIBILI?"https://i1.hdslb.com/new.jpg":"https://p2.music.126.net/new.jpg");
            var lookups=new AtomicInteger();
            try(var library=new LibraryController(p->null,()->true,t->{throw new AssertionError("No playback");},()->{},CatalogContractTest.CLOCK,
                    a->(q,k,s,r)->new Page("Covers",List.of(new Item(platform==AccountPlatform.BILIBILI?Kind.COLLECTION:Kind.FOLDER,
                            "30","Collection","Artist",1,true,missing?null:original,99)),false,java.time.Instant.MAX),
                    (a,q,r)->{lookups.incrementAndGet();return alternate;},(a,i,r)->{lookups.incrementAndGet();return alternate;});
                var request=new io.github.yghfv.saltneri.plugin.net.RequestScope(()->true)) {
                library.pageLimit(20); library.browse(platform,View.SAVED);waitFor(()->!rows(library).isEmpty());
                String old=(String)rows(library).getFirst().get("token");
                check(library.resolveCover(old,request,!missing).equals(alternate) && lookups.get()==1);
                check(library.cover(old).equals(alternate));
                library.browse(platform,View.DISCOVER);waitFor(()->!rows(library).isEmpty());
                library.browse(platform,View.SAVED);waitFor(()->!rows(library).isEmpty());
                String returned=(String)rows(library).getFirst().get("token");check(!returned.equals(old));
                check(library.cover(returned).equals(alternate));
                check(library.resolveCover(returned,request).equals(alternate) && lookups.get()==1);
                library.reload();waitFor(()->!rows(library).isEmpty());
                String refreshed=(String)rows(library).getFirst().get("token");
                check(Objects.equals(library.cover(refreshed),missing?null:original));
                check(library.resolveCover(refreshed,request,!missing).equals(alternate) && lookups.get()==2);
                library.accountChanged(platform);check(!library.currentRow(refreshed));
                library.browse(platform,View.SAVED);waitFor(()->!rows(library).isEmpty());
                String changed=(String)rows(library).getFirst().get("token");
                check(Objects.equals(library.cover(changed),missing?null:original));
                check(library.resolveCover(changed,request,!missing).equals(alternate) && lookups.get()==3);
            }
        }
    }
}
