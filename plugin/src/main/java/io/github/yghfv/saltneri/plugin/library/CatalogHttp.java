package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Clock;
import java.util.Set;
import java.util.Map;
import static io.github.yghfv.saltneri.plugin.library.CatalogFailure.*;

/** Dedicated read-only catalog API allowlist, isolated cookies and bounded WEAPI session preheat. */
public final class CatalogHttp implements AutoCloseable {
    @FunctionalInterface public interface Opener { HttpURLConnection open(URI uri, byte[] form) throws IOException; }
    private static final Set<String> BILI = Set.of("/x/v3/fav/folder/created/list", "/x/v3/fav/folder/collected/list", "/x/v3/fav/resource/list",
            "/x/web-interface/nav", "/x/web-interface/view", "/x/polymer/web-space/seasons_archives_list", "/x/web-interface/wbi/search/type",
            "/x/space/wbi/arc/search", "/x/polymer/web-space/seasons_series_list",
            "/x/copyright-music-publicity/toplist/all_period", "/x/copyright-music-publicity/toplist/music_list");
    private static final Set<String> NET = Set.of("/weapi/personalized/playlist", "/weapi/user/playlist", "/api/v6/playlist/detail", "/api/song/detail", "/weapi/cloudsearch/get/web",
            "/weapi/v3/discovery/recommend/songs", "/weapi/v1/discovery/recommend/resource", "/weapi/v1/radio/get", "/weapi/personalized/newsong");
    private final Opener opener;
    private final ScopedCookies cookies;
    private final AccountCredentials account;
    private final AccountPlatform platform;
    private final Clock clock;
    private Stage stage = Stage.SESSION;
    private String wbiMixin;
    private final SessionCache sessions;
    private long sessionRevision;
    /** Per-platform verified context; no network under the cache lock and no persistent credentials. */
    static final class SessionCache {
        private AccountCredentials owner;
        private java.time.Instant until=java.time.Instant.MIN;
        private java.util.List<ScopedCookies.Entry> cookies=java.util.List.of();
        private String mixin;
        private long revision;
        synchronized void clear() { revision++; owner=null; cookies=java.util.List.of(); mixin=null; until=java.time.Instant.MIN; }
        synchronized boolean restore(CatalogHttp http,RequestScope request) throws IOException {
            http.check(request);
            if(owner!=http.account || !http.clock.instant().isBefore(until)) clear();
            http.sessionRevision=revision;
            if(owner==null) return false;
            http.cookies.restore(cookies); http.wbiMixin=mixin; return true;
        }
        synchronized void store(CatalogHttp http,RequestScope request,boolean verified) throws IOException {
            http.check(request);
            if(http.sessionRevision!=revision || http.account==null || !verified && owner!=http.account) return;
            owner=http.account; cookies=http.cookies.snapshot(); mixin=http.wbiMixin;
            if(verified) until=http.clock.instant().plusSeconds(120);
        }
    }
    String verifiedWbiMixin(RequestScope request) { check(request); return wbiMixin; }
    public Stage stage() { return stage; }
    /** Independent cookie jar per worker, verified once by the parent operation. */
    CatalogHttp fork(RequestScope request) throws IOException {
        check(request); var child=new CatalogHttp(platform,account,clock,opener);
        child.cookies.restore(cookies.snapshot()); child.wbiMixin=wbiMixin; return child;
    }
    public CatalogHttp(AccountPlatform platform, AccountCredentials account, Clock clock, Opener opener) throws IOException {
        this(platform,account,clock,opener,null);
    }
    CatalogHttp(AccountPlatform platform, AccountCredentials account, Clock clock, Opener opener,SessionCache sessions) throws IOException {
        if (account != null && account.platform() != platform) throw new IllegalArgumentException("Wrong catalog account");
        this.platform = platform; this.account = account; this.clock = clock; this.opener = opener; this.sessions=sessions;
        cookies = new ScopedCookies(platform, clock); if (account != null) cookies.restore(account.cookieSnapshot());
    }
    public static HttpURLConnection openPinned(URI uri, byte[] form) throws IOException {
        if (form == null) return new PinnedHttpsConnection(uri);
        if (io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.GATEWAY.equals(uri)) return PinnedHttpsConnection.qqMusicPost(uri,form);
        if ("/weapi/w/nuser/account/get".equals(uri.getPath())) return PinnedHttpsConnection.neteaseFormPost(uri, form);
        return PinnedHttpsConnection.neteaseCatalogPost(uri, form);
    }
    public void verify(RequestScope request) throws IOException {
        wbiMixin = null;
        stage = platform == AccountPlatform.BILIBILI ? Stage.BILI_VERIFY : Stage.NET_VERIFY;
        if (account == null) throw new SecurityException("Login required for personal catalog");
        check(request);
        if(sessions!=null && sessions.restore(this,request)) return;
        try (QrAuthClient client = platform == AccountPlatform.BILIBILI ? new BiliQrClient(clock, new AuthHttp(opener::open)) : new NeteaseQrClient(clock, new AuthHttp(opener::open))) {
            AccountCredentials checked = client.verify(account, request);
            try { cookies.restore(checked.cookieSnapshot()); } finally { checked.retire(); }
            if (client instanceof BiliQrClient bili) wbiMixin = bili.verifiedWbiMixin();
        } catch (IOException error) { throw CatalogFailure.wrap(stage, Reason.AUTH, error); }
        check(request); if(sessions!=null) sessions.store(this,request,true);
    }
    public String csrf(RequestScope request) throws IOException {
        check(request);
        URI home = URI.create("https://music.163.com/"); String csrf = cookies.value(home, "__csrf");
        // NeriPlayer preheats authenticated WEAPI sessions only; public reads allow an empty token.
        if (account == null || !csrf.isEmpty()) return csrf;
        stage = Stage.NET_CSRF;
        check(request); HttpURLConnection connection = open(home, null); request.attach(connection);
        try {
            configure(connection, home, null); int status = connection.getResponseCode();
            if (status != 200) throw CatalogFailure.code(stage, Reason.HTTP, status);
            if (connection.getContentLengthLong() > 262144) throw CatalogFailure.of(stage, Reason.RESPONSE);
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192]; int size = 0;
                for (;;) { check(request); int count = input.read(buffer, 0, Math.min(buffer.length, 262145 - size)); if (count < 0) break;
                    size += count; if (size > 262144) throw CatalogFailure.of(stage, Reason.RESPONSE); }
            }
            cookies.accept(home, JsonResponse.setCookies(connection));
        } catch (IOException error) { throw CatalogFailure.wrap(stage, Reason.NETWORK, error); }
        finally { request.release(connection); }
        check(request); csrf = cookies.value(home, "__csrf"); if (csrf.isEmpty()) throw CatalogFailure.of(stage, Reason.CSRF);
        if(sessions!=null) sessions.store(this,request,false); return csrf;
    }
    public Map<String, Object> json(URI uri, byte[] form, RequestScope request) throws IOException {
        stage = endpoint(uri.getPath());
        boolean allowed = platform == AccountPlatform.BILIBILI ? (("api.bilibili.com".equals(uri.getHost()) && BILI.contains(uri.getPath()))
                || "app.bilibili.com".equals(uri.getHost()) && "/x/v2/rank/region".equals(uri.getPath())) && form == null
                : "music.163.com".equals(uri.getHost()) && NET.contains(uri.getPath()) && (form == null) == uri.getPath().equals("/api/song/detail");
        if (!allowed || !"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) throw CatalogFailure.of(stage, Reason.POLICY);
        check(request); HttpURLConnection connection = open(uri, form); request.attach(connection);
        try {
            configure(connection, uri, form); int status = connection.getResponseCode(); check(request);
            if (status != 200) throw CatalogFailure.code(stage, Reason.HTTP, status);
            Map<String, Object> result;
            try { result = JsonResponse.read(connection, request, true,platform==AccountPlatform.NETEASE && uri.getPath().equals("/weapi/v1/discovery/recommend/resource")); }
            catch (IOException error) { throw CatalogFailure.wrap(stage, Reason.RESPONSE, error); }
            cookies.accept(uri, JsonResponse.setCookies(connection)); check(request); return result;
        } catch (IOException error) { throw CatalogFailure.wrap(stage, Reason.NETWORK, error); }
        finally { request.release(connection); }
    }
    private HttpURLConnection open(URI uri, byte[] form) throws IOException {
        try { return opener.open(uri, form); }
        catch (IOException error) { throw CatalogFailure.wrap(stage, Reason.NETWORK, error); }
    }
    private static Stage endpoint(String path) {
        return switch (path) {
            case "/x/v3/fav/folder/created/list" -> Stage.BILI_CREATED;
            case "/x/v3/fav/folder/collected/list" -> Stage.BILI_SAVED;
            case "/x/v3/fav/resource/list" -> Stage.BILI_CONTENTS;
            case "/x/web-interface/nav" -> Stage.BILI_VERIFY;
            case "/x/web-interface/view", "/x/polymer/web-space/seasons_archives_list", "/x/polymer/web-space/seasons_series_list", "/x/space/wbi/arc/search" -> Stage.BILI_COLLECTION;
            case "/x/web-interface/wbi/search/type" -> Stage.BILI_SEARCH;
            case "/x/v2/rank/region", "/x/copyright-music-publicity/toplist/all_period", "/x/copyright-music-publicity/toplist/music_list" -> Stage.BILI_DISCOVER;
            case "/weapi/personalized/playlist", "/weapi/v3/discovery/recommend/songs", "/weapi/v1/discovery/recommend/resource",
                    "/weapi/v1/radio/get", "/weapi/personalized/newsong" -> Stage.NET_DISCOVER;
            case "/weapi/user/playlist" -> Stage.NET_PLAYLISTS;
            case "/api/v6/playlist/detail" -> Stage.NET_CONTENTS;
            case "/api/song/detail" -> Stage.NET_SONGS;
            case "/weapi/cloudsearch/get/web" -> Stage.NET_SEARCH;
            default -> Stage.UNKNOWN;
        };
    }
    private void configure(HttpURLConnection connection, URI uri, byte[] form) throws IOException {
        connection.setInstanceFollowRedirects(false); connection.setUseCaches(false); connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
        connection.setRequestMethod(form == null ? "GET" : "POST");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/132.0.0.0 Safari/537.36");
        connection.setRequestProperty("Referer", platform == AccountPlatform.BILIBILI ? "https://www.bilibili.com/" : "https://music.163.com/");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Accept", "application/json");
        String cookie = cookies.header(uri); if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
    }
    private void check(RequestScope request) { request.check(); if (account != null && !account.isActive()) throw new java.util.concurrent.CancellationException("Catalog account retired"); }
    @Override public void close() { cookies.clear(); wbiMixin = null; }
}
