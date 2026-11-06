package io.github.yghfv.saltneri.plugin.library;

/* Catalog parameters/field mapping adapted from pinned NeriPlayer BiliClient, NeteaseClient
 * and NeteasePlaylistMapping. GPL-3.0-or-later; no writes, retry or eager all-pages download. */

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.bilibili.BiliWbi;
import io.github.yghfv.saltneri.plugin.net.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;
import static io.github.yghfv.saltneri.plugin.library.CatalogFailure.*;

public final class CatalogClient {
    private final AccountCredentials account;
    private final Clock clock;
    private final CatalogHttp.Opener opener;
    public CatalogClient(AccountCredentials account) { this(account, Clock.systemUTC(), CatalogHttp::openPinned); }
    public CatalogClient(AccountCredentials account, Clock clock, CatalogHttp.Opener opener) { this.account = account; this.clock = clock; this.opener = opener; }
    public Page load(Query query, RequestScope request) throws IOException {
        boolean discover = query.view() == View.DISCOVER || query.view() == View.MUSIC_HOT || query.view() == View.MUSIC_ORIGINAL;
        boolean personal = query.view() == View.MINE || query.view() == View.SAVED || query.platform() == AccountPlatform.BILIBILI && !discover;
        if (personal && (account == null || account.platform() != query.platform())) {
            Stage stage = query.platform() == AccountPlatform.BILIBILI
                    ? query.view() == View.SEARCH ? Stage.BILI_SEARCH : query.view() == View.MINE ? Stage.BILI_CREATED : query.view() == View.SAVED ? Stage.BILI_SAVED : Stage.BILI_CONTENTS
                    : Stage.NET_PLAYLISTS;
            throw CatalogFailure.of(stage, Reason.LOGIN_REQUIRED);
        }
        try (CatalogHttp http = new CatalogHttp(query.platform(), discover ? null : account, clock, opener)) {
            try {
                if (!discover && account != null) http.verify(request);
                return query.platform() == AccountPlatform.BILIBILI ? bili(query, http, request) : netease(query, http, request);
            } catch (IOException error) { throw CatalogFailure.wrap(http.stage(), Reason.RESPONSE, error); }
        } catch (IOException error) { throw CatalogFailure.wrap(Stage.SESSION, Reason.RESPONSE, error); }
    }
    private Page netease(Query query, CatalogHttp http, RequestScope request) throws IOException {
        if (query.view() == View.FOLDER) {
            Map<String, Object> root = success(http.json(URI.create("https://music.163.com/api/v6/playlist/detail"), form(Map.of("id", Long.toString(query.folder()), "n", "0", "s", "0")), request), 200, http.stage());
            Map<String, Object> list = object(root.get("playlist")); if (id(list.get("id")) != query.folder()) throw invalid();
            List<Object> ids = array(list.get("trackIds")); if (ids.size() > 10000) throw invalid();
            Set<Long> all = new HashSet<>(); List<Long> selected = new ArrayList<>(); int offset = (query.page() - 1) * 20;
            for (int i = 0; i < ids.size(); i++) { long song = id(object(ids.get(i)).get("id")); if (!all.add(song)) throw invalid(); if (i >= offset && i < offset + 20) selected.add(song); }
            List<Item> items = new ArrayList<>();
            if (!selected.isEmpty()) {
                String joined = selected.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(",", "[", "]"));
                var details = success(http.json(URI.create("https://music.163.com/api/song/detail?ids=" + encode(joined)), null, request), 200, http.stage());
                Map<Long, Map<String, Object>> songs = new HashMap<>(); List<Object> rows = array(details.get("songs")); if (rows.size() > selected.size()) throw invalid();
                for (Object row : rows) { var song = object(row); long songId = id(song.get("id")); if (!selected.contains(songId) || songs.put(songId, song) != null) throw invalid(); }
                for (long songId : selected) {
                    var song = songs.get(songId);
                    if (song == null) { items.add(new Item(Kind.TRACK, Long.toString(songId), "Unavailable #" + songId, "Missing/deleted track", 0, false)); continue; }
                    long fee = number(song.get("fee"), -1), duration = number(alias(song, "dt", "duration"), 0);
                    boolean enabled = (fee == 0 || fee == 8) && number(alias(song, "st", "status"), 0) >= 0 && duration > 0;
                    List<String> artists = new ArrayList<>(); Object artistRows = alias(song, "ar", "artists");
                    if (artistRows != null) for (Object row : bounded(array(artistRows), 64)) artists.add(text(object(row).get("name")));
                    Object album = alias(song, "al", "album");
                    items.add(new Item(Kind.TRACK, Long.toString(songId), text(song.get("name")), String.join(" / ", artists) + (enabled ? "" : " / unavailable or paid"), duration, enabled,
                            cover(AccountPlatform.NETEASE, album instanceof Map<?,?> ? object(album).get("picUrl") : null)));
                }
            }
            return page(text(list.get("name")), items, offset + 20 < ids.size(), ids.size());
        }
        String path = query.view() == View.DISCOVER ? "/weapi/personalized/playlist" : "/weapi/user/playlist";
        Map<String, Object> parameters = new LinkedHashMap<>(); parameters.put("limit", "20");
        if (query.view() != View.DISCOVER) { parameters.put("uid", account.userId()); parameters.put("offset", Integer.toString((query.page() - 1) * 20)); parameters.put("includeVideo", "true"); }
        else if (query.page() != 1) throw new IllegalArgumentException("Discovery has a single bounded page");
        String csrf = http.csrf(request);
        var root = success(http.json(URI.create("https://music.163.com" + path + "?csrf_token=" + encode(csrf)), NeteaseWeapi.form(parameters), request), 200, http.stage());
        List<Item> items = new ArrayList<>(); Set<Long> unique = new HashSet<>();
        for (Object row : bounded(array(root.get(query.view() == View.DISCOVER ? "result" : "playlist")), 20)) {
            var list = object(row); long listId = id(list.get("id")); if (!unique.add(listId)) throw invalid();
            boolean mine = query.view() != View.DISCOVER && Long.toString(id(object(list.get("creator")).get("userId"))).equals(account.userId());
            if (query.view() == View.MINE && !mine || query.view() == View.SAVED && (mine || !Boolean.TRUE.equals(list.get("subscribed")))) continue;
            items.add(new Item(Kind.FOLDER, Long.toString(listId), text(list.get("name")), author(list), number(list.get("trackCount"), -1), true,
                    cover(AccountPlatform.NETEASE, alias(list, "coverImgUrl", "picUrl"))));
        }
        return page(viewTitle(query), items, query.view() != View.DISCOVER && Boolean.TRUE.equals(root.get("more")),
                query.view() == View.DISCOVER ? items.size() : number(root.get("count"), -1));
    }
    private Page bili(Query query, CatalogHttp http, RequestScope request) throws IOException {
        if (query.view() == View.DISCOVER || query.view() == View.MUSIC_HOT || query.view() == View.MUSIC_ORIGINAL) return BiliDiscovery.load(query,http,request,clock);
        String base = "https://api.bilibili.com";
        if (query.view() == View.SEARCH) {
            URI uri = BiliWbi.sign(base + "/x/web-interface/wbi/search/type", Map.of(
                    "search_type", "video", "keyword", query.keyword(), "page", Integer.toString(query.page()),
                    "page_size", "20", "order", "totalrank", "duration", "0", "tids", "0"), wbi(http, request), clock.instant().getEpochSecond());
            var data = object(success(http.json(uri, null, request), 0, http.stage()).get("data"));
            if (number(data.get("page"), query.page()) != query.page() || number(data.get("pagesize"), 20) != 20) throw invalid();
            long total = number(data.get("numResults"), -1), count = number(data.get("numPages"), total < 0 ? -1 : Math.max(1, (total + 19) / 20));
            if (count < 0 || count > Integer.MAX_VALUE) throw invalid();
            int pages = (int)Math.max(1, Math.min(500, count));
            List<Item> items = new ArrayList<>(); Set<String> unique = new HashSet<>();
            for (Object row : bounded(arrayOrEmpty(data.get("result")), 20)) {
                var video = object(row);
                if (!"video".equals(video.get("type"))) continue;
                String bvid = optional(video.get("bvid"));
                long duration = searchDuration(video.get("duration"));
                if (!bvid.matches("BV[0-9A-Za-z]{10}") || duration <= 0 || !unique.add(bvid)) continue;
                items.add(new Item(Kind.TRACK, bvid, searchTitle(text(video.get("title"))), optional(video.get("author")), duration, true,
                        cover(AccountPlatform.BILIBILI, video.get("pic"))));
            }
            return new Page("\u641c\u7d22\uff1a" + query.keyword(), items, query.page() < pages, clock.instant().plusSeconds(300), pages);
        }
        if (query.view() == View.COLLECTION) {
            var data = collectionData(query, 20, http, request);
            var meta = object(data.get("meta"));
            List<Item> items = new ArrayList<>(); Set<String> unique = new HashSet<>();
            for (Object row : bounded(arrayOrEmpty(data.get("archives")), 20)) {
                var video = object(row); String bvid = optional(video.get("bvid"));
                long duration = number(video.get("duration"), 0);
                boolean enabled = bvid.matches("BV[0-9A-Za-z]{10}") && duration > 0;
                String target = enabled ? bvid : "unsupported:" + id(video.get("aid"));
                if (!unique.add(target)) throw invalid();
                items.add(new Item(Kind.TRACK, target, text(video.get("title")), author(video), duration * 1000, enabled,
                        cover(AccountPlatform.BILIBILI, video.get("pic"))));
            }
            Map<String, Object> paging = data.get("page") == null ? Map.of() : object(data.get("page"));
            long total = number(paging.get("total"), number(meta.get("total"), -1));
            Boolean more = more(data, query.page(), total);
            if (more == null) more = items.size() == 20 && query.page() < 500 && !arrayOrEmpty(collectionData(
                    new Query(query.platform(), query.view(), query.folder(), query.page() + 1, query.owner()), 20, http, request).get("archives")).isEmpty();
            return page(text(meta.get("name")), items, more && query.page() < 500, total);
        }
        if (query.view() == View.FOLDER) {
            var data = object(success(http.json(URI.create(base + "/x/v3/fav/resource/list?media_id=" + query.folder() + "&pn=" + query.page() + "&ps=20&order=mtime&platform=web"), null, request), 0, http.stage()).get("data"));
            var info = object(data.get("info")); if (id(info.get("id")) != query.folder()) throw invalid();
            List<Item> items = new ArrayList<>(); Set<String> unique = new HashSet<>();
            for (Object row : bounded(arrayOrEmpty(data.get("medias")), 20)) {
                var video = object(row); String bvid = optional(alias(video, "bvid", "bv_id"));
                boolean enabled = number(video.get("type"), 0) == 2 && bvid.matches("BV[0-9A-Za-z]{10}") && number(video.get("duration"), 0) > 0;
                String target = enabled ? bvid : "unsupported:" + id(video.get("id")); if (!unique.add(target)) throw invalid();
                String owner = video.get("upper") == null ? "" : optional(object(video.get("upper")).get("name"));
                items.add(new Item(Kind.TRACK, target, text(video.get("title")), owner + (enabled ? " / P1" : " / unavailable or unsupported"), number(video.get("duration"), 0) * 1000, enabled,
                        cover(AccountPlatform.BILIBILI, video.get("cover"))));
            }
            Boolean more = more(data, query.page(), number(info.get("media_count"), -1));
            if (more == null) more = items.size() == 20 && query.page() < 500 && !arrayOrEmpty(biliData(http, request,
                    base + "/x/v3/fav/resource/list?media_id=" + query.folder() + "&pn=" + (query.page() + 1) + "&ps=20&order=mtime&platform=web").get("medias")).isEmpty();
            return page(text(info.get("title")), items, more && query.page() < 500, number(info.get("media_count"), -1));
        }
        String endpoint = query.view() == View.MINE ? "created/list" : "collected/list";
        var data = object(success(http.json(URI.create(base + "/x/v3/fav/folder/" + endpoint + "?up_mid=" + account.userId() + "&pn=" + query.page() + "&ps=20&platform=web&web_location=333.1387"), null, request), 0, http.stage()).get("data"));
        List<Item> items = new ArrayList<>(); Set<String> unique = new HashSet<>();
        for (Object row : bounded(arrayOrEmpty(data.get("list")), 20)) {
            var folder = object(row); long collection = number(alias(folder, "season_id", "series_id"), 0), folderId = number(folder.get("id"), collection);
            if (folderId <= 0) throw invalid(); long type = number(folder.get("type"), collection > 0 ? 21 : 11);
            // Created folders use 0/1 (normal/default), unlike collected/list's 11/21 item types.
            boolean isCollection = collection > 0 || type == 21;
            long owner = number(folder.get("mid"), 0);
            if (owner <= 0 && folder.get("upper") != null) owner = number(object(folder.get("upper")).get("mid"), 0);
            boolean enabled = (isCollection ? owner > 0 : type == 11 || query.view() == View.MINE && (type == 0 || type == 1))
                    && number(folder.get("state"), 0) == 0;
            if (!isCollection && query.view() == View.MINE && number(folder.get("mid"), Long.parseLong(account.userId())) != Long.parseLong(account.userId())) throw invalid();
            String target = Long.toString(isCollection && collection > 0 ? collection : folderId);
            if (!unique.add((isCollection ? "collection:" : "folder:") + target)) throw invalid();
            String subtitle = enabled ? author(folder)
                    : "\u672a\u652f\u6301\u7684\u5217\u8868\u7c7b\u578b (type=" + type + ")";
            if (subtitle.isEmpty() && query.view() == View.MINE) subtitle = account.profile().name();
            items.add(new Item(isCollection ? Kind.COLLECTION : Kind.FOLDER, target, text(alias(folder, "title", "name")), subtitle, number(alias(folder, "media_count", "total"), -1), enabled,
                    firstCover(AccountPlatform.BILIBILI, folder, "cover", "pic", "cover_url"), isCollection ? owner : 0));
        }
        Boolean more = more(data, query.page(), number(alias(data, "count", "total"), -1));
        // Some responses omit totals/flags. Probe at most one page, never crawl or count filtered UI rows.
        if (more == null) more = items.size() == 20 && query.page() < 500 && !arrayOrEmpty(biliData(http, request,
                base + "/x/v3/fav/folder/" + endpoint + "?up_mid=" + account.userId() + "&pn=" + (query.page() + 1) + "&ps=20&platform=web&web_location=333.1387").get("list")).isEmpty();
        return page(viewTitle(query), items, more && query.page() < 500, number(alias(data, "count", "total"), -1));
    }
    private static Boolean more(Map<String, Object> data, int page, long total) throws IOException {
        Object flag = alias(data, "has_more", "more");
        if (flag instanceof Boolean value) return value;
        if (flag != null) { long value = number(flag, 0); if (value != 0 && value != 1) throw invalid(); return value == 1; }
        return total <= 0 ? null : page * 20L < total;
    }
    private static Map<String, Object> biliData(CatalogHttp http, RequestScope request, String url) throws IOException {
        return object(success(http.json(URI.create(url), null, request), 0, http.stage()).get("data"));
    }
    private String wbi(CatalogHttp http, RequestScope request) throws IOException {
        var nav = biliData(http, request, "https://api.bilibili.com/x/web-interface/nav");
        var keys = object(nav.get("wbi_img"));
        try { return BiliWbi.mixin(text(keys.get("img_url")), text(keys.get("sub_url"))); }
        catch (IllegalArgumentException error) { throw invalid(); }
    }
    private Map<String, Object> collectionData(Query query, int size, CatalogHttp http, RequestScope request) throws IOException {
        URI uri = BiliWbi.sign("https://api.bilibili.com/x/polymer/web-space/seasons_archives_list", Map.of(
                "mid", Long.toString(query.owner()), "season_id", Long.toString(query.folder()),
                "page_num", Integer.toString(query.page()), "page_size", Integer.toString(size), "sort_reverse", "false", "web_location", "333.999"),
                wbi(http, request), clock.instant().getEpochSecond());
        var data = object(success(http.json(uri, null, request), 0, http.stage()).get("data"));
        var meta = object(data.get("meta"));
        if (id(meta.get("season_id")) != query.folder() || id(meta.get("mid")) != query.owner()) throw invalid();
        return data;
    }
    /** Visible collection artwork only: metadata cover, then its first video's picture; no audio resolution. */
    public URI collectionCover(Query query, RequestScope request) throws IOException {
        if (query.view() != View.COLLECTION || account == null || account.platform() != AccountPlatform.BILIBILI) throw invalid();
        try (CatalogHttp http = new CatalogHttp(AccountPlatform.BILIBILI, account, clock, opener)) {
            http.verify(request);
            var data = collectionData(query, 1, http, request);
            URI cover = firstCover(AccountPlatform.BILIBILI, object(data.get("meta")), "cover", "pic");
            if (cover != null) return cover;
            for (Object row : bounded(arrayOrEmpty(data.get("archives")), 1)) {
                cover = firstCover(AccountPlatform.BILIBILI, object(row), "pic", "cover");
                if (cover != null) return cover;
            }
            return null;
        }
    }
    private static URI firstCover(AccountPlatform platform, Map<String, Object> row, String... fields) {
        for (String field : fields) { URI uri = cover(platform, row.get(field)); if (uri != null) return uri; }
        return null;
    }
    private static String viewTitle(Query query) {
        return switch (query.view()) {
            case DISCOVER -> "\u53d1\u73b0";
            case MUSIC_HOT -> "\u97f3\u4e50\u70ed\u95e8\u699c";
            case MUSIC_ORIGINAL -> "\u97f3\u4e50\u539f\u521b\u699c";
            case MINE -> query.platform() == AccountPlatform.BILIBILI ? "\u521b\u5efa\u7684\u6536\u85cf\u5939" : "\u6211\u7684\u6b4c\u5355";
            case SAVED -> "\u6536\u85cf / \u8ba2\u9605";
            case FOLDER, COLLECTION -> throw new IllegalArgumentException("Folder title comes from its contents");
            case SEARCH -> "\u641c\u7d22";
        };
    }
    private Page page(String title, List<Item> items, boolean more, long total) {
        int pages = total < 0 || total == 0 && more ? 0 : (int)Math.max(1, Math.min(500, total / 20 + (total % 20 == 0 ? 0 : 1)));
        return new Page(title, items, more, clock.instant().plusSeconds(300), pages, total);
    }
    private static String author(Map<String, Object> row) throws IOException {
        for (String field : List.of("upper", "owner", "creator", "author")) {
            Object value = row.get(field);
            if (value instanceof String text && !text.isBlank()) return text;
            if (value instanceof Map<?,?>) {
                var person = object(value);
                for (String name : List.of("name", "uname", "nickname")) {
                    String label = optional(person.get(name)); if (!label.isEmpty()) return label;
                }
            }
        }
        return optional(alias(row, "uname", "author_name"));
    }
    private static long searchDuration(Object value) {
        if (!(value instanceof String text) || !text.matches("[0-9]{1,6}:[0-9]{2}(:[0-9]{2})?")) return 0;
        String[] parts = text.split(":"); long seconds = 0;
        for (int i = 0; i < parts.length; i++) {
            long n = Long.parseLong(parts[i]); if (i > 0 && n >= 60) return 0;
            seconds = seconds * 60 + n;
        }
        return seconds <= 7 * 24 * 3600 ? seconds * 1000 : 0;
    }
    private static String searchTitle(String value) {
        // API search highlighting is markup, not part of the media title. Never render upstream HTML.
        return value.replaceAll("<[^>]*>", "").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
    }
    private static URI cover(AccountPlatform platform, Object value) { return value instanceof String text ? CoverSource.parse(platform, text) : null; }
    private static Map<String, Object> success(Map<String, Object> root, long expected, Stage stage) throws IOException {
        long code = BoundedJson.integer(root.get("code")); if (code != expected) throw CatalogFailure.code(stage, Reason.API, code); return root;
    }
    private static Map<String, Object> object(Object value) throws IOException { return BoundedJson.object(value); }
    private static List<Object> array(Object value) throws IOException { return BoundedJson.array(value); }
    private static List<Object> arrayOrEmpty(Object value) throws IOException { return value == null ? List.of() : array(value); }
    private static List<Object> bounded(List<Object> values, int max) throws IOException { if (values.size() > max) throw invalid(); return values; }
    private static Object alias(Map<String, Object> values, String a, String b) { return values.get(a) == null ? values.get(b) : values.get(a); }
    private static long id(Object value) throws IOException { long n = BoundedJson.integer(value); if (n <= 0) throw invalid(); return n; }
    private static long number(Object value, long fallback) throws IOException { return value == null ? fallback : BoundedJson.integer(value); }
    private static String optional(Object value) throws IOException { return value == null ? "" : text(value); }
    private static String text(Object value) throws IOException { String text = BoundedJson.string(value); if (text.length() > 4096 || text.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid(); return text; }
    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    private static byte[] form(Map<String, String> values) { return values.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(java.util.stream.Collectors.joining("&")).getBytes(StandardCharsets.US_ASCII); }
    private static IOException invalid() { return new IOException("Invalid catalog response"); }
}
