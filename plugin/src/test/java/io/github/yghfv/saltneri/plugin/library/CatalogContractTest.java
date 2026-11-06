package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;
import static io.github.yghfv.saltneri.plugin.library.CatalogFailure.*;

/** Synthetic personal/public catalogs only; never reads an actual account or playlist. */
public final class CatalogContractTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC);
    private static int checks;
    static void check(boolean v) { checks++; if (!v) throw new AssertionError("Catalog check " + checks); }
    @FunctionalInterface interface Action { void run() throws Exception; }
    static void fails(Action action) throws Exception { try { action.run(); throw new AssertionError("Expected rejection"); } catch (IOException | IllegalArgumentException | IllegalStateException | SecurityException expected) { check(!String.valueOf(expected.getMessage()).contains("SECRET")); } }
    static void safeFailure(Action action, Stage stage, Reason reason, Long code) throws Exception {
        try { action.run(); throw new AssertionError("Expected safe catalog rejection"); }
        catch (CatalogFailure expected) {
            check(expected.stage() == stage && expected.reason() == reason && Objects.equals(expected.code(), code));
            String diagnostic = CatalogFailure.diagnostic(expected);
            check(diagnostic.contains(stage.name() + "/" + reason.name()));
            check(!diagnostic.contains("SECRET") && !diagnostic.contains("https:") && !diagnostic.contains("COOKIE") && expected.getCause() == null);
        }
    }
    static AccountCredentials account(AccountPlatform platform) throws IOException {
        var jar = new ScopedCookies(platform, CLOCK); jar.accept(URI.create("https://" + platform.domain() + "/"), List.of(platform.loginCookie() + "=COOKIE_SECRET; Domain=." + platform.domain() + "; Path=/; Secure; Max-Age=3600"));
        return new AccountCredentials(platform, "42", jar.snapshot(), CLOCK);
    }
    static final class Response extends HttpURLConnection {
        final String json; final List<String> cookies; boolean disconnected;
        int status = 200; String contentType = "application/json";
        Response(URI uri, String json, List<String> cookies) throws IOException { super(uri.toURL()); this.json = json; this.cookies = cookies; }
        public void connect() { } public int getResponseCode() { return status; }
        public String getHeaderField(String name) { return name.equalsIgnoreCase("Content-Type") ? contentType : null; }
        public Map<String,List<String>> getHeaderFields() { return Map.of("Set-Cookie", cookies); }
        public InputStream getInputStream() { return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)); }
        public void disconnect() { disconnected = true; } public boolean usingProxy() { return false; }
    }
    static final class Fixture implements CatalogHttp.Opener {
        String discovery = "{\"code\":200,\"result\":[{\"id\":10,\"name\":\"Discovery\",\"trackCount\":22}]}";
        String personal = "{\"code\":200,\"more\":true,\"playlist\":[{\"id\":10,\"name\":\"Liked\",\"trackCount\":22,\"creator\":{\"userId\":42},\"specialType\":5},{\"id\":11,\"name\":\"Mine\",\"creator\":{\"userId\":42}},{\"id\":12,\"name\":\"Saved\",\"creator\":{\"userId\":99},\"subscribed\":true}]}";
        String detail = "{\"code\":200,\"playlist\":{\"id\":10,\"name\":\"Playlist\",\"trackIds\":[{\"id\":1},{\"id\":2},{\"id\":3}]}}";
        String songs = "{\"code\":200,\"songs\":[{\"id\":2,\"name\":\"Paid\",\"fee\":1,\"dt\":120000,\"ar\":[]},{\"id\":1,\"name\":\"Free\",\"fee\":0,\"duration\":120000,\"artists\":[{\"name\":\"Artist\"}]}]}";
        String created = "{\"code\":0,\"data\":{\"count\":30,\"list\":[{\"id\":20,\"mid\":42,\"title\":\"Favorites\",\"media_count\":5},{\"season_id\":30,\"name\":\"Collection\",\"type\":21}]}}";
        String resources = "{\"code\":0,\"data\":{\"info\":{\"id\":20,\"title\":\"Favorites\"},\"has_more\":true,\"medias\":[{\"id\":1,\"type\":2,\"bvid\":\"BV17x411w7KC\",\"title\":\"Video\",\"duration\":60},{\"id\":2,\"type\":2,\"title\":\"Deleted\",\"duration\":0}]}}";
        String collection = "{\"code\":0,\"data\":{\"meta\":{\"season_id\":30,\"mid\":99,\"name\":\"Collection\",\"total\":21},\"page\":{\"total\":21},\"archives\":[{\"aid\":1,\"bvid\":\"BV17x411w7KC\",\"title\":\"Video\",\"duration\":60,\"pic\":\"//i0.hdslb.com/fixture.jpg\"},{\"aid\":2,\"title\":\"Deleted\",\"duration\":0}]}}";
        String identity = "42"; boolean csrf = true;
        String nextCreated;
        String search = "{\"code\":0,\"data\":{\"page\":1,\"pagesize\":20,\"numResults\":21,\"numPages\":2,\"result\":["
                + "{\"type\":\"video\",\"bvid\":\"BV17x411w7KC\",\"title\":\"A <em class=\\\"keyword\\\">song</em> &amp; music\",\"author\":\"Artist\",\"duration\":\"04:16\",\"pic\":\"//i0.hdslb.com/song.jpg\"},"
                + "{\"type\":\"video\",\"bvid\":\"invalid\",\"title\":\"Deleted\",\"duration\":\"00:00\"},"
                + "{\"type\":\"bili_user\",\"title\":\"Not a video\"}]}}";
        String collectionArtwork = "{\"code\":0,\"data\":{\"meta\":{\"season_id\":30,\"mid\":99,\"cover\":\"\"},\"archives\":[{\"pic\":\"//i0.hdslb.com/first.jpg@96w.webp\"}]}}";
        String failPath = "", statusPath = "", htmlPath = ""; int status = 200; IOException failure;
        final List<Response> requests = new ArrayList<>();
        public HttpURLConnection open(URI uri, byte[] form) throws IOException {
            if (uri.getPath().equals(failPath)) throw failure;
            String json = switch (uri.getPath()) {
                case "/" -> "<html></html>"; case "/weapi/w/nuser/account/get" -> "{\"code\":200,\"account\":{\"id\":" + identity + "},\"profile\":{\"userId\":" + identity + "}}";
                case "/x/web-interface/nav" -> "{\"code\":0,\"data\":{\"isLogin\":true,\"mid\":" + identity + ",\"wbi_img\":{\"img_url\":\"https://i0.hdslb.com/bfs/wbi/abcdefghijklmnopqrstuvwxyz012345.png\",\"sub_url\":\"https://i0.hdslb.com/bfs/wbi/012345abcdefghijklmnopqrstuvwxyz.png\"}}}";
                case "/weapi/personalized/playlist" -> discovery; case "/weapi/user/playlist" -> personal;
                case "/api/v6/playlist/detail" -> detail; case "/api/song/detail" -> songs;
                case "/x/v3/fav/folder/created/list", "/x/v3/fav/folder/collected/list" -> created;
                case "/x/v3/fav/resource/list" -> resources;
                case "/x/polymer/web-space/seasons_archives_list" -> collection;
                case "/x/web-interface/wbi/search/type" -> search;
                default -> throw new AssertionError("Unexpected endpoint");
            };
            if (uri.getPath().endsWith("/collected/list") && uri.getQuery().contains("pn=2") && nextCreated != null) json = nextCreated;
            if (uri.getPath().endsWith("/seasons_archives_list") && uri.getQuery().contains("page_size=1&")) json = collectionArtwork;
            if (uri.getPath().startsWith("/weapi/") && !uri.getPath().contains("account/get")) { check(form != null); check(uri.getRawQuery().equals("csrf_token=fixture_csrf")); }
            Response response = new Response(uri, json, uri.getPath().equals("/") && csrf ? List.of("__csrf=fixture_csrf; Path=/; Secure") : List.of());
            if (uri.getPath().equals(statusPath)) response.status = status;
            if (uri.getPath().equals(htmlPath)) response.contentType = "text/html";
            requests.add(response); return response;
        }
        Page load(Query query, AccountCredentials account) throws IOException { try (RequestScope scope = new RequestScope(() -> true)) { return new CatalogClient(account, CLOCK, this).load(query, scope); } }
    }
    public static void main(String[] args) throws Exception {
        Fixture publicPage = new Fixture(); Page discovery = publicPage.load(new Query(AccountPlatform.NETEASE, View.DISCOVER, 0, 1), null);
        check(discovery.items().size() == 1 && !discovery.hasMore()); check(publicPage.requests.size() == 2); check(publicPage.requests.stream().noneMatch(r -> String.valueOf(r.getRequestProperty("Cookie")).contains("MUSIC_U")));
        check(publicPage.requests.stream().allMatch(r -> r.disconnected));
        Page publicSongs = new Fixture().load(new Query(AccountPlatform.NETEASE, View.FOLDER, 10, 1), null);
        check(publicSongs.items().size() == 3); check(publicSongs.items().get(0).target().equals("1") && publicSongs.items().get(0).enabled());
        check(!publicSongs.items().get(1).enabled() && !publicSongs.items().get(2).enabled()); check(publicSongs.items().get(2).title().contains("Unavailable"));
        for (AccountPlatform platform : AccountPlatform.values()) {
            AccountCredentials account = account(platform);
            try {
                Fixture fixture = new Fixture(); Page mine = fixture.load(new Query(platform, View.MINE, 0, 1), account);
                check(mine.items().size() == 2 && mine.hasMore()); check(fixture.requests.getFirst().getRequestProperty("Cookie").contains("COOKIE_SECRET"));
                if (platform == AccountPlatform.NETEASE) { check(mine.items().getFirst().subtitle().equals("Liked songs")); Page saved = fixture.load(new Query(platform, View.SAVED, 0, 2), account); check(saved.items().size() == 1 && saved.items().getFirst().target().equals("12")); }
                else { check(mine.items().getFirst().enabled()); check(!mine.items().getLast().enabled()); Page videos = fixture.load(new Query(platform, View.FOLDER, 20, 1), account); check(videos.items().getFirst().target().equals("BV17x411w7KC")); check(videos.items().getFirst().enabled() && !videos.items().getLast().enabled()); }
                Fixture mismatch = new Fixture(); mismatch.identity = "43"; fails(() -> mismatch.load(new Query(platform, View.MINE, 0, 1), account)); check(mismatch.requests.size() == 1);
                account.retire(); fails(() -> fixture.load(new Query(platform, View.MINE, 0, 1), account));
            } finally { account.retire(); }
            fails(() -> new Fixture().load(new Query(platform, View.MINE, 0, 1), null));
            safeFailure(() -> new Fixture().load(new Query(platform, View.MINE, 0, 1), null),
                    platform == AccountPlatform.BILIBILI ? Stage.BILI_CREATED : Stage.NET_PLAYLISTS, Reason.LOGIN_REQUIRED, null);
        }
        Fixture missingCsrf = new Fixture(); missingCsrf.csrf = false; fails(() -> missingCsrf.load(new Query(AccountPlatform.NETEASE, View.DISCOVER, 0, 1), null)); check(missingCsrf.requests.size() == 1);
        safeFailure(() -> missingCsrf.load(new Query(AccountPlatform.NETEASE, View.DISCOVER, 0, 1), null), Stage.NET_CSRF, Reason.CSRF, null);
        AccountCredentials bili = account(AccountPlatform.BILIBILI);
        try {
            Fixture results = new Fixture();
            Query searchQuery = new Query(AccountPlatform.BILIBILI, View.SEARCH, 0, 1, 0, " \u6d1b\u5929\u4f9d & music ");
            Page found = results.load(searchQuery,bili);
            check(found.totalPages()==2 && found.hasMore() && found.items().size()==1);
            check(found.items().getFirst().title().equals("A song & music") && found.items().getFirst().count()==256000);
            check(found.items().getFirst().target().equals("BV17x411w7KC") && found.items().getFirst().cover()!=null);
            String signed = results.requests.getLast().getURL().getQuery();
            check(signed.contains("search_type=video") && signed.contains("keyword=%E6%B4%9B%E5%A4%A9%E4%BE%9D%20%26%20music") && signed.contains("w_rid="));
            check(!searchQuery.toString().contains("music"));
            results.search = results.search.replace("\"page\":1", "\"page\":2");
            check(!results.load(new Query(AccountPlatform.BILIBILI,View.SEARCH,0,2,0,searchQuery.keyword()),bili).hasMore());
            safeFailure(() -> results.load(searchQuery,bili),Stage.BILI_SEARCH,Reason.RESPONSE,null);
            results.search = "{\"code\":-412,\"message\":\"SECRET\"}";
            safeFailure(() -> results.load(searchQuery,bili),Stage.BILI_SEARCH,Reason.API,-412L);
            safeFailure(() -> results.load(searchQuery,null),Stage.BILI_SEARCH,Reason.LOGIN_REQUIRED,null);
            for(String invalid : List.of("", " ", "a\nb", "x".repeat(101))) fails(() -> new Query(AccountPlatform.BILIBILI,View.SEARCH,0,1,0,invalid));
            fails(() -> new Query(AccountPlatform.NETEASE,View.SEARCH,0,1,0,"music"));
            check(new Fixture().load(new Query(AccountPlatform.BILIBILI,View.MINE,0,1),bili).totalPages()==2);
            check(new Fixture().load(new Query(AccountPlatform.BILIBILI,View.COLLECTION,30,1,99),bili).totalPages()==2);
            Fixture paging = new Fixture();
            for (String flag : List.of("true", "1")) {
                paging.created = "{\"code\":0,\"data\":{\"has_more\":" + flag + ",\"list\":[{\"id\":20,\"title\":\"One visible row\"}]}}";
                check(paging.load(new Query(AccountPlatform.BILIBILI, View.SAVED, 0, 1), bili).hasMore());
            }
            String full = java.util.stream.LongStream.rangeClosed(1,20).mapToObj(id -> "{\"id\":"+id+",\"title\":\"Folder\"}").collect(java.util.stream.Collectors.joining(","));
            paging.created = "{\"code\":0,\"data\":{\"list\":["+full+"]}}";
            paging.nextCreated = "{\"code\":0,\"data\":{\"list\":[{\"id\":21,\"title\":\"Last\"}]}}";
            int before = paging.requests.size();
            check(paging.load(new Query(AccountPlatform.BILIBILI, View.SAVED, 0, 1), bili).hasMore());
            check(paging.requests.size() == before + 3 && paging.requests.getLast().getURL().getQuery().contains("pn=2"));
            paging.nextCreated = "{\"code\":0,\"data\":{\"list\":[]}}";
            check(!paging.load(new Query(AccountPlatform.BILIBILI, View.SAVED, 0, 1), bili).hasMore());
            paging.created = paging.created.replace("\"list\":", "\"count\":20,\"list\":");
            before = paging.requests.size();
            check(!paging.load(new Query(AccountPlatform.BILIBILI, View.SAVED, 0, 1), bili).hasMore());
            check(paging.requests.size() == before + 2);
            paging.resources = paging.resources.replace("\"has_more\":true", "\"has_more\":1");
            check(paging.load(new Query(AccountPlatform.BILIBILI, View.FOLDER, 20, 1), bili).hasMore());
            paging.resources = paging.resources.replace("\"has_more\":1,", "").replace("\"title\":\"Favorites\"", "\"title\":\"Favorites\",\"media_count\":21");
            check(paging.load(new Query(AccountPlatform.BILIBILI, View.FOLDER, 20, 1), bili).hasMore());
            paging.collection = paging.collection.replace("\"page\":{\"total\":21},", "");
            check(paging.load(new Query(AccountPlatform.BILIBILI, View.COLLECTION, 30, 1, 99), bili).hasMore());
            try (RequestScope request = new RequestScope(() -> true)) {
                var client = new CatalogClient(bili, CLOCK, paging); var query = new Query(AccountPlatform.BILIBILI, View.COLLECTION, 30, 1, 99);
                check(client.collectionCover(query,request).toString().equals("https://i0.hdslb.com/first.jpg"));
                check(paging.requests.getLast().getURL().getQuery().contains("page_size=1&"));
                paging.collectionArtwork = paging.collectionArtwork.replace("\"cover\":\"\"", "\"cover\":\"//i1.biliimg.com/meta.jpg\"");
                check(client.collectionCover(query,request).toString().equals("https://i1.biliimg.com/meta.jpg"));
                paging.collectionArtwork = paging.collectionArtwork.replace("\"mid\":99", "\"mid\":98");
                fails(() -> client.collectionCover(query,request));
            }
            Fixture types = new Fixture();
            types.created = "{\"code\":0,\"data\":{\"count\":7,\"list\":["
                    + "{\"id\":20,\"mid\":42,\"title\":\"Default\",\"type\":1},"
                    + "{\"id\":21,\"mid\":42,\"title\":\"Music\",\"type\":0},"
                    + "{\"id\":22,\"title\":\"Legacy\",\"type\":11},"
                    + "{\"id\":23,\"title\":\"No type\"},"
                    + "{\"season_id\":24,\"name\":\"Collection\",\"type\":21},"
                    + "{\"id\":25,\"season_id\":25,\"name\":\"Conflicting collection\",\"type\":11},"
                    + "{\"id\":26,\"title\":\"Unknown\",\"type\":99}]}}";
            Page createdTypes = types.load(new Query(AccountPlatform.BILIBILI, View.MINE, 0, 1), bili);
            check(createdTypes.items().size() == 7 && !createdTypes.hasMore());
            check(createdTypes.items().subList(0, 4).stream().allMatch(i -> i.kind() == Kind.FOLDER && i.enabled()));
            check(createdTypes.items().get(0).target().equals("20") && createdTypes.items().get(1).target().equals("21"));
            check(createdTypes.items().subList(4, 7).stream().noneMatch(Item::enabled));
            check(createdTypes.items().getLast().subtitle().contains("type=99"));
            Page savedTypes = types.load(new Query(AccountPlatform.BILIBILI, View.SAVED, 0, 1), bili);
            check(!savedTypes.items().get(0).enabled() && !savedTypes.items().get(1).enabled());
            check(savedTypes.items().get(2).enabled() && savedTypes.items().get(3).enabled());
            check(savedTypes.items().subList(4, 7).stream().noneMatch(Item::enabled));
            Fixture collections = new Fixture();
            collections.created = "{\"code\":0,\"data\":{\"count\":4,\"list\":["
                    + "{\"id\":900,\"season_id\":30,\"name\":\"Collection\",\"type\":21,\"upper\":{\"mid\":99}},"
                    + "{\"id\":31,\"title\":\"Typed collection\",\"type\":21,\"mid\":99},"
                    + "{\"id\":32,\"season_id\":32,\"name\":\"Deleted collection\",\"mid\":99,\"state\":-1},"
                    + "{\"id\":30,\"title\":\"Same ID folder\",\"type\":11}]}}";
            Page lists = collections.load(new Query(AccountPlatform.BILIBILI, View.SAVED, 0, 1), bili);
            check(lists.items().getFirst().kind() == Kind.COLLECTION && lists.items().getFirst().target().equals("30"));
            check(lists.items().getFirst().enabled() && lists.items().getFirst().owner() == 99);
            check(lists.items().get(1).enabled() && lists.items().get(1).target().equals("31"));
            check(!lists.items().get(2).enabled() && lists.items().getLast().kind() == Kind.FOLDER);
            Query collectionQuery = new Query(AccountPlatform.BILIBILI, View.COLLECTION, 30, 1, 99);
            Page archives = collections.load(collectionQuery, bili);
            check(archives.hasMore() && archives.items().size() == 2 && archives.title().equals("Collection"));
            check(archives.items().getFirst().enabled() && archives.items().getFirst().target().equals("BV17x411w7KC"));
            check(!archives.items().getLast().enabled() && archives.items().getFirst().cover() != null);
            String queryString = collections.requests.getLast().getURL().getQuery();
            String expectedQuery = io.github.yghfv.saltneri.plugin.bilibili.BiliWbi.sign("https://api.bilibili.com/x/polymer/web-space/seasons_archives_list",
                    Map.of("mid", "99", "season_id", "30", "page_num", "1", "page_size", "20", "sort_reverse", "false", "web_location", "333.999"),
                    io.github.yghfv.saltneri.plugin.bilibili.BiliWbi.mixin("https://i0.hdslb.com/bfs/wbi/abcdefghijklmnopqrstuvwxyz012345.png", "https://i0.hdslb.com/bfs/wbi/012345abcdefghijklmnopqrstuvwxyz.png"),
                    CLOCK.instant().getEpochSecond()).getRawQuery();
            check(queryString.equals(expectedQuery));
            check(!collections.load(new Query(AccountPlatform.BILIBILI, View.COLLECTION, 30, 2, 99), bili).hasMore());
            check(collections.requests.getLast().getURL().getQuery().contains("page_num=2"));
            collections.collection = collections.collection.replace("\"mid\":99", "\"mid\":98");
            safeFailure(() -> collections.load(collectionQuery, bili), Stage.BILI_COLLECTION, Reason.RESPONSE, null);
            collections.collection = "{\"code\":-404,\"message\":\"COOKIE_SECRET\"}";
            safeFailure(() -> collections.load(collectionQuery, bili), Stage.BILI_COLLECTION, Reason.API, -404L);
            fails(() -> new Query(AccountPlatform.NETEASE, View.COLLECTION, 30, 1, 99));
            fails(() -> new Query(AccountPlatform.BILIBILI, View.COLLECTION, 30, 1));
            Fixture duplicateFolder = new Fixture(); duplicateFolder.created = types.created.replace("\"id\":21", "\"id\":20");
            safeFailure(() -> duplicateFolder.load(new Query(AccountPlatform.BILIBILI, View.MINE, 0, 1), bili), Stage.BILI_CREATED, Reason.RESPONSE, null);
            Fixture biliDenied = new Fixture(); biliDenied.created = "{\"code\":-403,\"message\":\"COOKIE_SECRET https://example.invalid/UID_SECRET\"}";
            safeFailure(() -> biliDenied.load(new Query(AccountPlatform.BILIBILI, View.SAVED, 0, 1), bili), Stage.BILI_SAVED, Reason.API, -403L);
            Fixture contentsDenied = new Fixture(); contentsDenied.statusPath = "/x/v3/fav/resource/list"; contentsDenied.status = 412;
            safeFailure(() -> contentsDenied.load(new Query(AccountPlatform.BILIBILI, View.FOLDER, 20, 1), bili), Stage.BILI_CONTENTS, Reason.HTTP, 412L);
            check(contentsDenied.requests.stream().allMatch(r -> r.disconnected));
            Fixture verifyDenied = new Fixture(); verifyDenied.identity = "43";
            safeFailure(() -> verifyDenied.load(new Query(AccountPlatform.BILIBILI, View.MINE, 0, 1), bili), Stage.BILI_VERIFY, Reason.AUTH, null);
        } finally { bili.retire(); }
        Fixture wrongFolder = new Fixture(); wrongFolder.detail = wrongFolder.detail.replace("\"id\":10", "\"id\":99"); fails(() -> wrongFolder.load(new Query(AccountPlatform.NETEASE, View.FOLDER, 10, 1), null));
        Fixture duplicates = new Fixture(); duplicates.detail = duplicates.detail.replace("\"id\":2", "\"id\":1"); fails(() -> duplicates.load(new Query(AccountPlatform.NETEASE, View.FOLDER, 10, 1), null));
        Fixture foreign = new Fixture(); foreign.songs = foreign.songs.replace("\"id\":2", "\"id\":99"); fails(() -> foreign.load(new Query(AccountPlatform.NETEASE, View.FOLDER, 10, 1), null));
        Fixture denied = new Fixture(); denied.discovery = "{\"code\":301}"; fails(() -> denied.load(new Query(AccountPlatform.NETEASE, View.DISCOVER, 0, 1), null));
        safeFailure(() -> denied.load(new Query(AccountPlatform.NETEASE, View.DISCOVER, 0, 1), null), Stage.NET_DISCOVER, Reason.API, 301L);
        Fixture malformed = new Fixture(); malformed.discovery = "{\"code\":200,\"result\":\"COOKIE_SECRET https://example.invalid/UID_SECRET\"}";
        safeFailure(() -> malformed.load(new Query(AccountPlatform.NETEASE, View.DISCOVER, 0, 1), null), Stage.NET_DISCOVER, Reason.RESPONSE, null);
        Fixture html = new Fixture(); html.htmlPath = "/weapi/personalized/playlist";
        safeFailure(() -> html.load(new Query(AccountPlatform.NETEASE, View.DISCOVER, 0, 1), null), Stage.NET_DISCOVER, Reason.RESPONSE, null);
        Fixture httpDenied = new Fixture(); httpDenied.statusPath = "/weapi/personalized/playlist"; httpDenied.status = 429;
        safeFailure(() -> httpDenied.load(new Query(AccountPlatform.NETEASE, View.DISCOVER, 0, 1), null), Stage.NET_DISCOVER, Reason.HTTP, 429L);
        check(httpDenied.requests.size() == 2 && httpDenied.requests.stream().allMatch(r -> r.disconnected));
        Map<Reason, IOException> networkErrors = Map.of(Reason.NETWORK, new IOException("COOKIE_SECRET https://example.invalid/UID_SECRET"),
                Reason.DNS, new UnknownHostException("UID_SECRET"), Reason.TIMEOUT, new SocketTimeoutException("COOKIE_SECRET"),
                Reason.TLS, new javax.net.ssl.SSLException("URL_SECRET"));
        for (var entry : networkErrors.entrySet()) {
            Fixture network = new Fixture(); network.failPath = "/weapi/personalized/playlist"; network.failure = entry.getValue();
            safeFailure(() -> network.load(new Query(AccountPlatform.NETEASE, View.DISCOVER, 0, 1), null), Stage.NET_DISCOVER, entry.getKey(), null);
        }
        check(!CatalogFailure.diagnostic(new IOException("COOKIE_SECRET https://example.invalid/UID_SECRET")).contains("SECRET"));
        Fixture covers = new Fixture();
        covers.discovery = covers.discovery.replace("\"trackCount\":22", "\"trackCount\":22,\"picUrl\":\"http://p1.music.126.net/fixture.jpg\"");
        check(covers.load(new Query(AccountPlatform.NETEASE, View.DISCOVER, 0, 1), null).items().getFirst().cover().toString().equals("https://p1.music.126.net/fixture.jpg"));
        covers.songs = covers.songs.replace("\"fee\":0", "\"al\":{\"picUrl\":\"https://p2.music.126.net/fixture.jpg\"},\"fee\":0");
        check(covers.load(new Query(AccountPlatform.NETEASE, View.FOLDER, 10, 1), null).items().getFirst().cover().toString().equals("https://p2.music.126.net/fixture.jpg"));
        AccountCredentials coverAccount = account(AccountPlatform.BILIBILI);
        try {
            covers.created = covers.created.replace("\"media_count\":5", "\"media_count\":5,\"cover\":\"//i0.hdslb.com/bfs/archive/folder.jpg\"");
            check(covers.load(new Query(AccountPlatform.BILIBILI, View.MINE, 0, 1), coverAccount).items().getFirst().cover().getHost().equals("i0.hdslb.com"));
            covers.resources = covers.resources.replace("\"duration\":60", "\"duration\":60,\"cover\":\"http://i1.hdslb.com/bfs/archive/video.jpg\"");
            check(covers.load(new Query(AccountPlatform.BILIBILI, View.FOLDER, 20, 1), coverAccount).items().getFirst().cover().getScheme().equals("https"));
            covers.resources = covers.resources.replace("http://i1.hdslb.com/bfs/archive/video.jpg", "https://evil.example/image?token=COOKIE_SECRET");
            Page noCover = covers.load(new Query(AccountPlatform.BILIBILI, View.FOLDER, 20, 1), coverAccount);
            check(noCover.items().getFirst().enabled() && noCover.items().getFirst().cover() == null);
            check(!noCover.toString().contains("COOKIE_SECRET"));
        } finally { coverAccount.retire(); }
        Fixture paged = new Fixture(); String ids = java.util.stream.LongStream.rangeClosed(1, 22).mapToObj(i -> "{\"id\":" + i + "}").collect(java.util.stream.Collectors.joining(","));
        paged.detail = paged.detail.replace("[{\"id\":1},{\"id\":2},{\"id\":3}]", "[" + ids + "]"); paged.songs = "{\"code\":200,\"songs\":[]}";
        Page second = paged.load(new Query(AccountPlatform.NETEASE, View.FOLDER, 10, 2), null); check(second.items().size() == 2 && !second.hasMore()); check(second.items().getFirst().target().equals("21"));
        check(paged.requests.getLast().getURL().getQuery().equals("ids=%5B21%2C22%5D"));
        fails(() -> new Query(AccountPlatform.NETEASE, View.MINE, 0, 0)); fails(() -> new Query(AccountPlatform.NETEASE, View.FOLDER, 0, 1));
        HttpURLConnection post = CatalogHttp.openPinned(URI.create("https://music.163.com/weapi/user/playlist?csrf_token=fixture"), new byte[]{42}); check(post.getRequestMethod().equals("POST")); post.disconnect();
        fails(() -> CatalogHttp.openPinned(URI.create("https://music.163.com/weapi/playlist/manipulate/tracks"), new byte[]{42}));
        try (CatalogHttp http = new CatalogHttp(AccountPlatform.NETEASE, null, CLOCK, (u,f) -> { throw new AssertionError("Unallowed target opened"); }); RequestScope scope = new RequestScope(() -> true)) {
            fails(() -> http.json(URI.create("https://music.163.com/weapi/playlist/manipulate/tracks"), new byte[]{42}, scope));
            fails(() -> http.json(URI.create("https://evil.example/api/song/detail"), null, scope));
        }
        System.out.println("PASS: " + checks + " catalog protocol/pagination/identity/read-only checks (synthetic lists)");
    }
}
