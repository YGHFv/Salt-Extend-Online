package io.github.yghfv.saltneri.plugin.bilibili;

import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Pinned Neri protocol fixtures; never contacts a platform or instantiates a native player. */
public final class BiliClientContractTest {
    private static int checks;
    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String BV = "BV17x411w7KC";
    private static final String NAV = "{\"code\":-101,\"data\":{\"wbi_img\":{\"img_url\":\"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png\",\"sub_url\":\"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png\"}}}";
    private static final String VIEW = "{\"code\":0,\"data\":{\"aid\":170001,\"bvid\":\"" + BV + "\",\"title\":\"Fixture title\",\"pic\":\"http://i0.hdslb.com/bfs/archive/fake.jpg\",\"owner\":{\"name\":\"Fixture owner\"},\"rights\":{\"pay\":0},\"pages\":[{\"cid\":10,\"page\":1,\"part\":\"Part 1\",\"duration\":60},{\"cid\":20,\"page\":2,\"part\":\"Part 2\",\"duration\":120}]}}";
    private static final String URL = "https://upos-fixture.bilivideo.com/audio.m4s?token=SECRET";
    private static String stream(int id, int bandwidth, String url) {
        return "{\"id\":" + id + ",\"bandwidth\":" + bandwidth + ",\"base_url\":\"" + url + "\",\"backup_url\":[\"https://backup.bilivideo.cn/audio.m4s\"],\"mime_type\":\"audio/mp4\",\"codecs\":\"mp4a.40.2\"}";
    }
    private static final String PLAY = "{\"code\":0,\"data\":{\"timelength\":120000,\"dash\":{\"audio\":[" + stream(30280, 192000, URL) + "]}}}";
    @FunctionalInterface interface Action { void run() throws Exception; }
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
    private static void fails(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected rejection"); }
        catch (IOException | IllegalArgumentException | CancellationException expected) { checks++; check(!String.valueOf(expected.getMessage()).contains("SECRET")); }
    }
    public static final class Response extends HttpURLConnection {
        final Map<String, String> headers;
        final InputStream input;
        boolean disconnected;
        int reads;
        Response(URI uri, int code, Map<String, String> headers, byte[] body) throws IOException {
            super(uri.toURL()); responseCode = code; this.headers = headers;
            input = new ByteArrayInputStream(body) {
                @Override public synchronized int read() { reads++; return super.read(); }
                @Override public synchronized int read(byte[] bytes, int off, int len) { int count = super.read(bytes, off, len); if (count > 0) reads += count; return count; }
            };
        }
        @Override public void connect() { connected = true; }
        @Override public int getResponseCode() { return responseCode; }
        @Override public String getHeaderField(String key) { return headers.get(key); }
        @Override public long getContentLengthLong() { return headers.containsKey("Content-Length") ? Long.parseLong(headers.get("Content-Length")) : -1; }
        @Override public InputStream getInputStream() { return input; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
    }
    private static final class Fixture implements BiliClient.Opener {
        String nav = NAV, view = VIEW, play = PLAY;
        int apiStatus = 200;
        Map<String, String> apiHeaders = Map.of("Content-Type", "application/json; charset=utf-8");
        final List<Response> requests = new ArrayList<>();
        Function<URI, Integer> status = uri -> 206;
        Map<String, String> probeHeaders = Map.of("Content-Length", "1", "Content-Range", "bytes 0-0/4096");
        byte[] probeBody = new byte[]{42, 77};
        @Override public HttpURLConnection open(URI uri) throws IOException {
            String json = switch (uri.getPath()) {
                case "/x/web-interface/nav" -> nav;
                case "/x/web-interface/wbi/view" -> view;
                case "/x/player/wbi/playurl" -> play;
                default -> null;
            };
            Response response = json == null ? new Response(uri, status.apply(uri), probeHeaders, probeBody)
                    : new Response(uri, apiStatus, apiHeaders, json.getBytes(StandardCharsets.UTF_8));
            requests.add(response); return response;
        }
        BiliClient client() { return new BiliClient(CLOCK, this); }
    }
    private static BiliClient.Resolved resolve(BiliClient client) throws IOException {
        try (RequestScope scope = new RequestScope(() -> true)) {
            return client.resolve(new BiliTarget(BV, 2), BiliAudioSelector.Quality.HIGH, "fixture-anonymous", scope);
        }
    }
    public static BiliClient.Resolved resolvedFixture() throws IOException { return resolve(new Fixture().client()); }
    public static void main(String[] args) throws Exception {
        try(var request=new RequestScope(()->true)) {
            var fixture=new Fixture();
            var pages=fixture.client().pages(BV,request);
            check(pages.size()==2 && pages.getLast().target().page()==2 && pages.getLast().durationMillis()==120000);
            check(pages.getLast().title().equals("Part 2 Fixture title"));
        }
        check(BiliTarget.parse(BV).equals(new BiliTarget(BV, 1)));
        check(BiliTarget.parse("av170001").page() == 1);
        check(BiliTarget.parse("https://www.bilibili.com/video/" + BV + "/?p=2&spm_id_from=test#reply").page() == 2);
        check(BiliTarget.parse("http://m.bilibili.com/video/av170001?p=2").id().equals("av170001"));
        for (String target : List.of("https://b23.tv/abc", "https://www.bilibili.com.evil/video/" + BV, "https://u@www.bilibili.com/video/" + BV,
                "https://www.bilibili.com:443/video/" + BV, "https://www.bilibili.com/video/" + BV + "?p=0",
                "https://www.bilibili.com/video/" + BV + "?p=1&%70=2", "https://www.bilibili.com/video/" + BV + "?p=10001",
                "BVbroken", "av9223372036854775808", "file:///video/" + BV)) fails(() -> BiliTarget.parse(target));
        check(BiliWbi.mixin("https://x/abc.png", "https://x/def.png").equals("cdfabe"));
        String mixin = BiliWbi.mixin("https://x/7cd084941338484aae1ad9425b84077c.png", "https://x/4932caff0ff746eab6f01bf08b70ac45.png");
        check(mixin.equals("ea1db124af3c7062474693fa704f4ff8"));
        URI signed = BiliWbi.sign("https://api.bilibili.com/test", Map.of("foo", "114", "bar", "514", "zab", "1919810"), mixin, 1702204169);
        check(signed.getRawQuery().equals("bar=514&foo=114&wts=1702204169&zab=1919810&w_rid=8f6f2b5b3d485fe1886cec6a0be8c5d4"));
        check(BiliWbi.sign("https://api.bilibili.com/test", Map.of("v", "a !'()*b"), mixin, 1).getRawQuery().startsWith("v=a%20b&wts=1&"));
        List<URI> candidates = BiliAudioSelector.candidates(List.of("http://cdn.bilivideo.com/a?x=1%2F2", "https://upos-fixture.bilivideo.cn/a", "https://cdn.bilivideo.com/a?x=1%2F2",
                "https://evil/a", "https://cdn.bilivideo.com.evil/a", "https://u@cdn.bilivideo.com/a", "https://cdn.bilivideo.com/a#x", "https://mountaintoys.cn/a", "https://cdn.bilivideo.com:8080/a"));
        check(candidates.size() == 2 && candidates.getFirst().getHost().startsWith("upos-"));
        check(candidates.get(1).getRawQuery().equals("x=1%2F2"));
        List<BiliAudioSelector.Audio> audios = new ArrayList<>();
        for (int kbps : List.of(64, 128, 192, 800)) audios.add(new BiliAudioSelector.Audio(kbps, "audio/mp4", "mp4a", kbps, null, List.of(URI.create("https://cdn.bilivideo.com/" + kbps))));
        check(BiliAudioSelector.select(audios, BiliAudioSelector.Quality.HIGH).bitrateKbps() == 192);
        check(BiliAudioSelector.select(audios, BiliAudioSelector.Quality.MEDIUM).bitrateKbps() == 128);
        check(BiliAudioSelector.select(audios, BiliAudioSelector.Quality.LOW).bitrateKbps() == 64);
        check(BiliAudioSelector.select(audios, BiliAudioSelector.Quality.HIRES).bitrateKbps() == 800);
        audios.add(new BiliAudioSelector.Audio(1, "audio/flac", "flac", 1200, "hires", List.of(URI.create("https://cdn.bilivideo.com/hires"))));
        audios.add(new BiliAudioSelector.Audio(2, "audio/eac3", "eac3", 256, " DOLBY ", List.of(URI.create("https://cdn.bilivideo.com/dolby"))));
        check(BiliAudioSelector.select(audios, BiliAudioSelector.Quality.DOLBY).id() == 2);
        check(BiliAudioSelector.select(audios, BiliAudioSelector.Quality.LOSSLESS).id() == 1);
        check(BiliAudioSelector.select(audios, BiliAudioSelector.Quality.HIRES).id() == 1);
        check(BiliAudioSelector.select(List.of(), BiliAudioSelector.Quality.HIGH) == null);
        check(BiliAudioSelector.Quality.fromKey("unknown") == BiliAudioSelector.Quality.HIGH);
        check(BiliAudioSelector.select(List.of(new BiliAudioSelector.Audio(0, "audio/mp4", "", 192, null, List.of())), BiliAudioSelector.Quality.HIGH) == null);
        Fixture fixture = new Fixture(); BiliClient client = fixture.client();
        BiliClient.Resolved result = resolve(client);
        check(result.key().cid() == 20 && result.key().remoteId().equals(BV));
        check(result.metadata().title().equals("Fixture title / Part 2"));
        check(result.durationMillis() == 120000 && result.bitrateKbps() == 192 && result.codec().equals("mp4a.40.2"));
        check(result.coverCandidate().getScheme().equals("https"));
        check(result.lease().length() == 4096 && result.lease().belongsTo("fixture-anonymous"));
        check(result.expiresAt().equals(NOW.plusSeconds(1800)));
        check(!result.toString().contains("SECRET") && !result.lease().toString().contains("SECRET"));
        check(fixture.requests.size() == 4 && fixture.requests.stream().allMatch(r -> r.disconnected));
        check(fixture.requests.get(2).getURL().getQuery().contains("cid=20"));
        check(!fixture.requests.get(2).getURL().getQuery().contains("try_look"));
        Response media = fixture.requests.getLast(); check(media.reads == 1 && media.getRequestProperty("Range").equals("bytes=0-0"));
        check(fixture.requests.stream().noneMatch(r -> r.getRequestProperty("Cookie") != null));
        var jar = new io.github.yghfv.saltneri.plugin.auth.ScopedCookies(io.github.yghfv.saltneri.plugin.auth.AccountPlatform.BILIBILI, CLOCK);
        jar.accept(URI.create("https://passport.bilibili.com/"), List.of("SESSDATA=COOKIE_SECRET; Domain=.bilibili.com; Path=/; Secure; Max-Age=3600"));
        var logged = new io.github.yghfv.saltneri.plugin.auth.AccountCredentials(io.github.yghfv.saltneri.plugin.auth.AccountPlatform.BILIBILI, "42", jar.snapshot(), CLOCK);
        Fixture authenticated = new Fixture(); authenticated.nav = NAV.replace("-101", "0").replace("\"wbi_img\":", "\"isLogin\":true,\"mid\":42,\"wbi_img\":");
        try (RequestScope request = new RequestScope(logged::isActive)) {
            var authorizedResult = new BiliClient(CLOCK, authenticated, logged).resolve(new BiliTarget(BV, 2), BiliAudioSelector.Quality.HIGH, logged.accountScope(), request);
            check(authorizedResult.lease().belongsTo(logged.accountScope()));
            check(authenticated.requests.subList(0, 3).stream().allMatch(r -> r.getRequestProperty("Cookie").contains("COOKIE_SECRET")));
            check(authenticated.requests.getLast().getRequestProperty("Cookie") == null);
        }
        Fixture wrongAccount = new Fixture(); wrongAccount.nav = authenticated.nav.replace("\"mid\":42", "\"mid\":43");
        try (RequestScope request = new RequestScope(logged::isActive)) {
            fails(() -> new BiliClient(CLOCK, wrongAccount, logged).resolve(new BiliTarget(BV, 2), BiliAudioSelector.Quality.HIGH, logged.accountScope(), request));
        }
        logged.retire();
        try (RequestScope request = new RequestScope(() -> true)) {
            fails(() -> new BiliClient(CLOCK, authenticated, logged).resolve(new BiliTarget(BV, 2), BiliAudioSelector.Quality.HIGH, logged.accountScope(), request));
        }
        resolve(client); check(fixture.requests.size() == 7); // Cached nav key.
        class MutableClock extends Clock {
            Instant value = NOW;
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return value; }
        }
        MutableClock time = new MutableClock(); Fixture refresh = new Fixture();
        BiliClient refreshing = new BiliClient(time, refresh);
        resolve(refreshing); time.value = NOW.plusSeconds(601); resolve(refreshing); check(refresh.requests.size() == 8);
        for (BiliAudioSelector.Quality preference : List.of(BiliAudioSelector.Quality.DOLBY, BiliAudioSelector.Quality.HIRES)) {
            Fixture tagged = new Fixture();
            String stream = stream(1, 1200000, URL).replace("audio/mp4", preference == BiliAudioSelector.Quality.HIRES ? "audio/flac" : "audio/eac3");
            tagged.play = "{\"code\":0,\"data\":{\"timelength\":120000,\"dash\":{\"audio\":[],\""
                    + (preference == BiliAudioSelector.Quality.HIRES ? "flac" : "dolby") + "\":{\"audio\":"
                    + (preference == BiliAudioSelector.Quality.DOLBY ? "[" + stream + "]" : stream) + "}}}}";
            try (RequestScope scope = new RequestScope(() -> true)) {
                var selected = tagged.client().resolve(new BiliTarget(BV, 2), preference, "a", scope);
                check(selected.bitrateKbps() == 1200 && selected.mimeType().equals(preference == BiliAudioSelector.Quality.HIRES ? "audio/flac" : "audio/eac3"));
            }
        }
        Fixture camel = new Fixture(); camel.play = PLAY.replace("base_url", "baseUrl").replace("backup_url", "backupUrl").replace("mime_type", "mimeType");
        check(resolve(camel.client()).bitrateKbps() == 192);
        for (Map<String, String> headers : List.of(Map.of("Content-Type", "text/html"), Map.of("Content-Type", "application/json", "Content-Encoding", "gzip"),
                Map.of("Content-Type", "application/json", "Content-Length", "2097153"))) {
            Fixture invalid = new Fixture(); invalid.apiHeaders = headers; fails(() -> resolve(invalid.client()));
            check(invalid.requests.size() == 1 && invalid.requests.getFirst().reads == 0);
        }
        Fixture risk = new Fixture(); risk.apiStatus = 412; fails(() -> resolve(risk.client())); check(risk.requests.size() == 1);
        Fixture av = new Fixture();
        try (RequestScope scope = new RequestScope(() -> true)) { check(av.client().resolve(new BiliTarget("av170001", 2), BiliAudioSelector.Quality.HIGH, "a", scope).key().cid() == 20); }
        Fixture fallback = new Fixture(); fallback.status = uri -> uri.getHost().startsWith("upos-") ? 503 : 206;
        check(resolve(fallback.client()).lease().length() == 4096); check(fallback.requests.size() == 5);
        for (int status : List.of(403, 412, 429)) {
            Fixture denied = new Fixture(); denied.status = uri -> status; fails(() -> resolve(denied.client())); check(denied.requests.size() == 4);
        }
        for (String view : List.of(VIEW.replace("\"pay\":0", "\"pay\":1"), VIEW.replace("\"code\":0", "\"code\":-412"),
                VIEW.replace("\"cid\":20", "\"cid\":0"), VIEW.replace("\"page\":2", "\"page\":3"), VIEW.replace(BV, "BV27x411w7KC"),
                VIEW.replace("\"title\":", "\"is_upower_exclusive\":true,\"title\":"))) {
            Fixture invalid = new Fixture(); invalid.view = view; fails(() -> resolve(invalid.client())); check(invalid.requests.size() <= 2);
        }
        for (String play : List.of(PLAY.replace("\"code\":0", "\"code\":-352"), PLAY.replace("120000", "30000"),
                PLAY.replace("\"dash\":", "\"is_preview\":1,\"dash\":"), "{\"code\":0,\"data\":{\"timelength\":120000,\"durl\":[]}}",
                PLAY.replace("audio/mp4", "video/mp4"), PLAY.replace("192000", "192000.5"))) {
            Fixture invalid = new Fixture(); invalid.play = play; fails(() -> resolve(invalid.client())); check(invalid.requests.size() == 3);
        }
        for (Map<String, String> headers : List.of(Map.of("Content-Length", "2", "Content-Range", "bytes 0-0/99"),
                Map.of("Content-Length", "1", "Content-Range", "bytes 1-1/99"), Map.of("Content-Length", "1", "Content-Range", "bytes 0-0/2147483649"),
                Map.of("Content-Length", "1", "Content-Range", "bytes 0-0/99", "Content-Encoding", "gzip"))) {
            Fixture invalid = new Fixture(); invalid.probeHeaders = headers; fails(() -> resolve(invalid.client()));
        }
        Fixture truncated = new Fixture(); truncated.probeBody = new byte[0]; fails(() -> resolve(truncated.client()));
        Fixture deadline = new Fixture(); deadline.play = PLAY.replace("token=SECRET", "deadline=" + NOW.plusSeconds(60).getEpochSecond());
        check(resolve(deadline.client()).expiresAt().equals(NOW.plusSeconds(60)));
        Fixture expired = new Fixture(); expired.play = PLAY.replace("token=SECRET", "deadline=" + NOW.minusSeconds(1).getEpochSecond())
                .replace("https://backup.bilivideo.cn/audio.m4s", "https://backup.bilivideo.cn/audio.m4s?deadline=" + NOW.minusSeconds(1).getEpochSecond());
        fails(() -> resolve(expired.client())); check(expired.requests.size() == 3);
        Fixture redirect = new Fixture();
        redirect.status = uri -> uri.getPath().equals("/audio.m4s") ? 302 : 206;
        redirect.probeHeaders = Map.of("Location", "/next", "Content-Length", "1", "Content-Range", "bytes 0-0/4096");
        check(resolve(redirect.client()).lease().length() == 4096); check(redirect.requests.size() == 5);
        for(String location:List.of("https://backup.bilivideo.cn/next","https://"+"a".repeat(32)+".v.smtcdns.com/next")) {
            Fixture edge=new Fixture();edge.status=uri->uri.getPath().equals("/audio.m4s")?302:206;
            edge.probeHeaders=Map.of("Location",location,"Content-Length","1","Content-Range","bytes 0-0/4096");
            check(resolve(edge.client()).lease().length()==4096 && edge.requests.size()==5);
        }
        for (String location : List.of("https://evil/media", "http://upos-fixture.bilivideo.com/next",
                "https://evil.v.smtcdns.com/next","https://"+"a".repeat(32)+".v.smtcdns.com.evil.test/next",
                "https://"+"a".repeat(32)+".v.smtcdns.com:444/next","https://127.0.0.1/next")) {
            Fixture invalid = new Fixture(); invalid.status = uri -> 302; invalid.probeHeaders = Map.of("Location", location); fails(() -> resolve(invalid.client()));
        }
        Fixture anonymousDenied = new Fixture(); anonymousDenied.nav = NAV.replace("-101", "-412");
        fails(() -> resolve(anonymousDenied.client())); check(anonymousDenied.requests.size() == 1);
        AtomicBoolean active = new AtomicBoolean(true); Fixture cancelled = new Fixture();
        BiliClient cancelClient = new BiliClient(CLOCK, uri -> { active.set(false); return cancelled.open(uri); });
        try (RequestScope scope = new RequestScope(active::get)) { fails(() -> cancelClient.resolve(new BiliTarget(BV, 2), BiliAudioSelector.Quality.HIGH, "a", scope)); }
        check(cancelled.requests.size() == 1 && cancelled.requests.getFirst().disconnected);
        System.out.println("PASS: " + checks + " anonymous Bilibili checks (fake API/CDN, no playback)");
    }
}
