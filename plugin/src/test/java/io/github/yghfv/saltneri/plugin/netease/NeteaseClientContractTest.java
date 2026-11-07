package io.github.yghfv.saltneri.plugin.netease;

import io.github.yghfv.saltneri.plugin.auth.AccountCredentials;
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.auth.ScopedCookies;
import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Synthetic pinned-protocol fixtures. No real account, platform request, browser or BASS. */
public final class NeteaseClientContractTest {
    private static int checks;
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC);
    static final NeteaseTarget TARGET = new NeteaseTarget(123);
    private static final String MEDIA = "http://m7.music.126.net/fixture/song.mp3?token=URL_SECRET";
    private static final String DETAIL = "{\"code\":200,\"songs\":[{\"id\":123,\"name\":\"Fixture song\",\"fee\":0,\"duration\":120000,\"artists\":[{\"name\":\"One\"},{\"name\":\"Two\"}],\"album\":{\"name\":\"Fixture album\",\"picUrl\":\"http://p1.music.126.net/cover.jpg\"}}]}";
    private static final String AUDIO = "{\"code\":200,\"data\":[{\"id\":123,\"code\":200,\"url\":\"" + MEDIA + "\",\"fee\":0,\"br\":128000,\"size\":4096,\"time\":120000,\"expi\":120,\"type\":\"mp3\",\"level\":\"standard\",\"freeTrialInfo\":null}]}";
    @FunctionalInterface interface Action { void run() throws Exception; }
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("NetEase check " + checks); }
    private static void fails(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected rejection"); }
        catch (IOException | IllegalArgumentException | CancellationException expected) {
            checks++; check(!String.valueOf(expected.getMessage()).contains("SECRET"));
        }
    }
    static AccountCredentials credentials(boolean domain) throws IOException {
        ScopedCookies jar = new ScopedCookies(AccountPlatform.NETEASE, CLOCK);
        jar.accept(URI.create("https://music.163.com/"), List.of("MUSIC_U=COOKIE_SECRET; " + (domain ? "Domain=.music.163.com; " : "") + "Path=/; Secure; Max-Age=3600"));
        return new AccountCredentials(AccountPlatform.NETEASE, "42", jar.snapshot(), CLOCK);
    }
    private static final class Response extends HttpURLConnection {
        final Map<String, String> headers; final byte[] bytes; int reads; boolean disconnected;
        Response(URI uri, int code, Map<String, String> headers, byte[] bytes) throws IOException {
            super(uri.toURL()); responseCode = code; this.headers = headers; this.bytes = bytes;
        }
        public void connect() { }
        public int getResponseCode() { return responseCode; }
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes) {
                public synchronized int read() { int result = super.read(); if (result >= 0) reads++; return result; }
                public synchronized int read(byte[] b, int o, int l) { int n = super.read(b, o, l); if (n > 0) reads += n; return n; }
            };
        }
        public String getHeaderField(String name) { return headers.get(name); }
        public Map<String, List<String>> getHeaderFields() { return headers.containsKey("Set-Cookie") ? Map.of("Set-Cookie",List.of(headers.get("Set-Cookie"))) : Map.of(); }
        public long getContentLengthLong() { return headers.containsKey("Content-Length") ? Long.parseLong(headers.get("Content-Length")) : -1; }
        public void disconnect() { disconnected = true; }
        public boolean usingProxy() { return false; }
    }
    private static final class Fixture implements NeteaseClient.Opener {
        String detail = DETAIL, audio = AUDIO, account = "{\"code\":200,\"account\":{\"id\":42},\"profile\":{\"userId\":42}}";
        int status = 200, mediaStatus = 206;
        Map<String, String> apiHeaders = Map.of("Content-Type", "application/json; charset=utf-8");
        Map<String, String> mediaHeaders = Map.of("Content-Length", "1", "Content-Range", "bytes 0-0/4096");
        byte[] mediaBytes = new byte[]{42, 77}; Consumer<URI> before = uri -> { };
        String redirect; boolean redirected;
        String refreshedCookie;
        final List<Response> requests = new ArrayList<>(); final List<byte[]> forms = new ArrayList<>();
        public HttpURLConnection open(URI uri, byte[] form) throws IOException {
            before.accept(uri); forms.add(form);
            String json = switch (uri.getPath()) {
                case "/api/song/detail" -> detail; case NeteaseEapi.PATH -> audio; case "/weapi/w/nuser/account/get" -> account; default -> null;
            };
            byte[] bytes = json == null ? mediaBytes : json.getBytes(StandardCharsets.UTF_8);
            if (json != null && "gzip".equals(apiHeaders.get("Content-Encoding"))) {
                ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(bytes); } bytes = compressed.toByteArray();
            }
            int code = json == null ? mediaStatus : status; Map<String, String> headers = json == null ? mediaHeaders : apiHeaders;
            if(uri.getPath().equals("/weapi/w/nuser/account/get") && refreshedCookie!=null) { headers=new LinkedHashMap<>(headers); headers.put("Set-Cookie",refreshedCookie); }
            if (json == null && redirect != null && !redirected) { redirected = true; code = 302; headers = Map.of("Location", redirect); }
            Response response = new Response(uri, code, headers, bytes);
            requests.add(response); return response;
        }
        NeteaseClient client(AccountCredentials account) { return new NeteaseClient(CLOCK, this, account); }
    }
    private static NeteaseClient.Resolved resolve(Fixture fixture, AccountCredentials credentials) throws IOException {
        try (RequestScope request = new RequestScope(() -> true)) {
            return fixture.client(credentials).resolve(TARGET, NeteaseClient.Quality.HIGH, credentials == null ? "fixture-anonymous" : credentials.accountScope(), request);
        }
    }
    public static NeteaseClient.Resolved resolvedFixture() throws IOException { return resolve(new Fixture(), null); }
    private static void rejectDetail(String detail) throws Exception {
        Fixture fixture = new Fixture(); fixture.detail = detail; fails(() -> resolve(fixture, null));
        check(fixture.requests.size() == 1);
    }
    private static void rejectAudio(String audio) throws Exception {
        Fixture fixture = new Fixture(); fixture.audio = audio; fails(() -> resolve(fixture, null));
        check(fixture.requests.size() == 2);
    }
    static String decryptEapi(byte[] form) throws Exception {
        String text = new String(form, StandardCharsets.US_ASCII); check(text.matches("params=[0-9A-F]+"));
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding"); cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec("e82ckenh8dichen8".getBytes(StandardCharsets.US_ASCII), "AES"));
        return new String(cipher.doFinal(HexFormat.of().parseHex(text.substring(7))), StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        // Test production factory dispatch without connecting, not just fake HTTP protocol responses.
        for (String endpoint : List.of("https://interface.music.163.com" + NeteaseEapi.PATH, "https://music.163.com/weapi/w/nuser/account/get?csrf_token=fixture")) {
            HttpURLConnection connection = NeteaseClient.openPinned(URI.create(endpoint), "params=ABCDEF".getBytes(StandardCharsets.US_ASCII));
            check(connection.getRequestMethod().equals("POST")); check(connection.getURL().getProtocol().equals("https")); connection.disconnect();
        }
        fails(() -> NeteaseClient.openPinned(URI.create("https://music.163.com/weapi/login/qrcode/unikey"), new byte[]{42}));
        check(NeteaseTarget.parse("123").songId() == 123);
        check(NeteaseTarget.parse("https://music.163.com/song?id=123&uct2=tracking").songId() == 123);
        check(NeteaseTarget.parse("https://music.163.com/#/song?id=123").songId() == 123);
        check(NeteaseTarget.parse("http://music.163.com/song/?id=123").songId() == 123);
        for (String input : List.of("0", "-1", "0123", "9223372036854775808", "https://music.163.com.evil/song?id=123", "https://u@music.163.com/song?id=123",
                "https://music.163.com:443/song?id=123", "https://music.163.com/playlist?id=123", "https://music.163.com/song?id=1&%69d=123",
                "https://music.163.com/#//evil/song?id=123", "https://music.163.com/#https://evil/song?id=123", "https://music.163.com/song", "https://163cn.tv/abc")) fails(() -> NeteaseTarget.parse(input));
        Fixture fixture = new Fixture(); var result = resolve(fixture, null);
        check(result.key().stableId().equals("netease:song:123")); check(result.durationMillis() == 120000 && result.bitrateKbps() == 128);
        check(result.lease().length() == 4096 && result.lease().belongsTo("fixture-anonymous"));
        check(result.mimeType().equals("audio/mpeg") && result.level().equals("standard"));
        check(result.coverCandidate().toString().equals("https://p1.music.126.net/cover.jpg"));
        check(result.metadata().artist().equals("One / Two")); check(result.metadata().title().equals("Fixture song"));
        check(result.expiresAt().equals(CLOCK.instant().plusSeconds(120))); check(!result.toString().contains("SECRET"));
        check(fixture.requests.size() == 3 && fixture.requests.stream().allMatch(r -> r.disconnected));
        check(fixture.requests.stream().noneMatch(r -> r.getRequestProperty("Cookie") != null));
        check(fixture.requests.getLast().getURL().getProtocol().equals("https"));
        check(fixture.requests.getLast().reads == 1 && fixture.requests.getLast().getRequestProperty("Range").equals("bytes=0-0"));
        String[] frame = decryptEapi(fixture.forms.get(1)).split("-36cd479b6b5-", -1);
        check(frame.length == 3 && frame[0].equals("/api/song/enhance/player/url/v1"));
        Map<String, Object> parameters = BoundedJson.object(BoundedJson.parse(frame[1].getBytes(StandardCharsets.UTF_8)));
        check(parameters.equals(Map.of("ids", "[123]", "level", "exhigh", "encodeType", "flac")));
        check(frame[2].equals(HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(("nobody" + frame[0] + "use" + frame[1] + "md5forencrypt").getBytes(StandardCharsets.UTF_8)))));
        check(java.util.Arrays.equals(NeteaseEapi.form(parameters), fixture.forms.get(1)));
        // Fixed vector computed independently with Windows .NET AES/MD5 from the pinned framing.
        Map<String, Object> vector = new LinkedHashMap<>(); vector.put("ids", "[123]"); vector.put("level", "standard"); vector.put("encodeType", "flac");
        check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(NeteaseEapi.form(vector)))
                .equals("d83ae39ae8219294cc783610f65d4e811e6ad80b8bd0f971684e9e6e12f3165a"));
        fails(() -> NeteaseEapi.form(Map.of("large", "a".repeat(8193))));
        check(NeteaseClient.Quality.fromKey("standard") == NeteaseClient.Quality.STANDARD); fails(() -> NeteaseClient.Quality.fromKey("unknown"));
        for(var requested:NeteaseClient.Quality.values()) {
            check(NeteaseClient.Quality.fromKey(requested.key())==requested);
            for(var actual:NeteaseClient.Quality.values()) {
                Fixture tier=new Fixture(); tier.audio=AUDIO.replace("\"level\":\"standard\"","\"level\":\""+actual.key()+"\"");
                try(var request=new RequestScope(()->true)) {
                    if(actual.ordinal()<=requested.ordinal()) {
                        check(tier.client(null).resolve(TARGET,requested,"fixture-anonymous",request).level().equals(actual.key()));
                        check(decryptEapi(tier.forms.get(1)).contains("\"level\":\""+requested.key()+"\""));
                    } else fails(()->tier.client(null).resolve(TARGET,requested,"fixture-anonymous",request));
                }
            }
        }
        for (String detail : List.of(DETAIL.replace("\"id\":123", "\"id\":124"), DETAIL.replace("\"fee\":0", "\"fee\":1"), DETAIL.replace("\"fee\":0", "\"fee\":4"),
                DETAIL.replace("\"code\":200", "\"code\":301"), DETAIL.replace("\"duration\":120000", "\"duration\":0"), DETAIL.replace("\"fee\":0", "\"fee\":0,\"status\":-1"),
                DETAIL.replace("\"name\":\"Fixture song\"", "\"name\":\"\""), DETAIL.replace("\"songs\":[", "\"songs\":[] , \"other\":["))) rejectDetail(detail);
        for (String audio : List.of(AUDIO.replace("\"id\":123", "\"id\":124"), AUDIO.replace("\"fee\":0", "\"fee\":1"), AUDIO.replace("\"code\":200", "\"code\":404"),
                AUDIO.replace("\"freeTrialInfo\":null", "\"freeTrialInfo\":{}"), AUDIO.replace("\"freeTrialInfo\":null", "\"freeTrialInfo\":{\"start\":0,\"end\":30}"),
                AUDIO.replace("\"time\":120000", "\"time\":30000"), AUDIO.replace("\"time\":120000", "\"time\":0"), AUDIO.replace("\"time\":120000,", ""),
                AUDIO.replace("\"size\":4096", "\"size\":0"), AUDIO.replace("\"expi\":120", "\"expi\":0"), AUDIO.replace("\"level\":\"standard\"", "\"level\":\"hires\""),
                AUDIO.replace("music.126.net", "music.126.net.evil"), AUDIO.replace("http://m7", "http://user@m7"), AUDIO.replace("URL_SECRET", "URL_SECRET#fragment"),
                AUDIO.replace("\"mp3\"", "\"unknown\""), AUDIO.replace("\"br\":128000", "\"br\":-1"), AUDIO.replace("\"freeTrialInfo\":null", "\"freeTrialInfo\":null,\"freeTrialPrivilege\":{\"cannotListenReason\":1}"))) rejectAudio(audio);
        Fixture aliases = new Fixture(); aliases.detail = DETAIL.replace("\"artists\"", "\"ar\"").replace("\"album\"", "\"al\"").replace("\"duration\"", "\"dt\"");
        check(resolve(aliases, null).metadata().artist().equals("One / Two"));
        Fixture objectData = new Fixture(); objectData.audio = AUDIO.replace("\"data\":[", "\"data\":").replace("}]}", "}}");
        check(resolve(objectData, null).lease().length() == 4096);
        rejectDetail(DETAIL.replace("\"songs\":", "\"privileges\":[{\"id\":123,\"fee\":0,\"st\":-1,\"pl\":128000}],\"songs\":"));
        rejectDetail(DETAIL.replace("\"songs\":", "\"privileges\":[{\"id\":123,\"fee\":0,\"st\":0,\"pl\":0}],\"songs\":"));
        rejectDetail(DETAIL.replace("\"fee\":0,", ""));
        rejectAudio(AUDIO.replace("\"size\":4096", "\"size\":2147483649"));
        rejectAudio(AUDIO.replace("\"time\":120000", "\"time\":117999"));
        Fixture durationBoundary = new Fixture(); durationBoundary.audio = AUDIO.replace("\"time\":120000", "\"time\":118000"); check(resolve(durationBoundary, null).durationMillis() == 118000);
        Fixture longLease = new Fixture(); longLease.audio = AUDIO.replace("\"expi\":120", "\"expi\":3600"); check(resolve(longLease, null).expiresAt().equals(CLOCK.instant().plusSeconds(300)));
        Fixture freeLow = new Fixture(); freeLow.detail = DETAIL.replace("\"fee\":0", "\"fee\":8"); freeLow.audio = AUDIO.replace("\"fee\":0", "\"fee\":8");
        check(resolve(freeLow, null).lease().length() == 4096);
        Fixture highQuality = new Fixture(); highQuality.audio = AUDIO.replace("\"level\":\"standard\"", "\"level\":\"exhigh\"").replace("\"br\":128000", "\"br\":320000");
        check(resolve(highQuality, null).bitrateKbps() == 320);
        try (RequestScope request = new RequestScope(() -> true)) {
            fails(() -> highQuality.client(null).resolve(TARGET, NeteaseClient.Quality.STANDARD, "fixture-anonymous", request));
        }
        Fixture badCover = new Fixture(); badCover.detail = DETAIL.replace("p1.music.126.net", "evil.example"); check(resolve(badCover, null).coverCandidate() == null);
        for (int status : List.of(301, 403, 412, 429, 500)) { Fixture denied = new Fixture(); denied.status = status; fails(() -> resolve(denied, null)); check(denied.requests.size() == 1); }
        for (int status : List.of(200, 403, 412, 429, 500)) { Fixture denied = new Fixture(); denied.mediaStatus = status; fails(() -> resolve(denied, null)); check(denied.requests.size() == 3); }
        for (Map<String, String> headers : List.of(Map.of("Content-Length", "1", "Content-Range", "bytes 0-0/4097"), Map.of("Content-Length", "2", "Content-Range", "bytes 0-0/4096"),
                Map.of("Content-Length", "1", "Content-Range", "bytes 0-0/4096", "Content-Encoding", "gzip"), Map.of("Content-Length", "1", "Content-Range", "bytes 1-1/4096"),
                Map.of("Location", "https://other.music.126.net/song"), Map.of("Location", "http://m7.music.126.net/song"))) {
            Fixture denied = new Fixture(); denied.mediaHeaders = headers; if (headers.containsKey("Location")) denied.mediaStatus = 302; fails(() -> resolve(denied, null)); check(denied.requests.size() == 3);
        }
        Fixture empty = new Fixture(); empty.mediaBytes = new byte[0]; fails(() -> resolve(empty, null));
        Fixture sameHost = new Fixture(); sameHost.redirect = "/fixture/redirected.mp3"; var redirected = resolve(sameHost, null);
        check(redirected.lease().length() == 4096 && sameHost.requests.size() == 4);
        check(sameHost.requests.subList(2, 4).stream().allMatch(r -> r.getRequestProperty("Cookie") == null && r.reads <= 1));
        Fixture compressed = new Fixture(); compressed.apiHeaders = Map.of("Content-Type", "application/json", "Content-Encoding", "gzip"); check(resolve(compressed, null).lease().length() == 4096);
        Fixture bomb = new Fixture(); bomb.apiHeaders = compressed.apiHeaders; bomb.detail = "{\"padding\":\"" + "x".repeat(BoundedJson.MAX_BYTES) + "\"}"; fails(() -> resolve(bomb, null));
        Fixture wrongType = new Fixture(); wrongType.apiHeaders = Map.of("Content-Type", "text/html"); fails(() -> resolve(wrongType, null));
        Fixture brotli = new Fixture(); brotli.apiHeaders = Map.of("Content-Type", "application/json", "Content-Encoding", "br"); fails(() -> resolve(brotli, null));
        AccountCredentials account = credentials(true);
        try {
            Fixture signedIn = new Fixture(); var signedResult = resolve(signedIn, account);
            check(signedResult.lease().belongsTo(account.accountScope())); check(signedIn.requests.size() == 4);
            check(signedIn.requests.subList(0, 3).stream().allMatch(r -> r.getRequestProperty("Cookie").contains("COOKIE_SECRET")));
            check(signedIn.requests.getLast().getRequestProperty("Cookie") == null);
            Fixture rotated=new Fixture(); rotated.refreshedCookie="MUSIC_U=ROTATED_SECRET; Domain=music.163.com; Path=/; Secure; Max-Age=3600";
            check(resolve(rotated,account).lease().belongsTo(account.accountScope()));
            check(rotated.requests.subList(1,3).stream().allMatch(r->r.getRequestProperty("Cookie").contains("ROTATED_SECRET") && !r.getRequestProperty("Cookie").contains("COOKIE_SECRET")));
            check(rotated.requests.getLast().getRequestProperty("Cookie")==null && account.isActive());
            for(int fee:List.of(1,4)) {
                Fixture entitled=new Fixture(); entitled.detail=DETAIL.replace("\"fee\":0","\"fee\":"+fee); entitled.audio=AUDIO.replace("\"fee\":0","\"fee\":"+fee);
                check(resolve(entitled,account).durationMillis()==120000);
                entitled.audio=entitled.audio.replace("\"freeTrialInfo\":null","\"freeTrialInfo\":{\"start\":0,\"end\":30}");
                fails(()->resolve(entitled,account));
                entitled.audio=AUDIO.replace("\"url\":\""+MEDIA+"\"","\"url\":null"); fails(()->resolve(entitled,account));
            }
            Fixture mismatch = new Fixture(); mismatch.account = mismatch.account.replace("42", "43"); fails(() -> resolve(mismatch, account)); check(mismatch.requests.size() == 1);
            Fixture expired = new Fixture(); expired.account = "{\"code\":301}"; fails(() -> resolve(expired, account)); check(expired.requests.size() == 1);
            Fixture wrongScope = new Fixture(); try (RequestScope request = new RequestScope(() -> true)) { fails(() -> wrongScope.client(account).resolve(TARGET, NeteaseClient.Quality.HIGH, "wrong", request)); } check(wrongScope.requests.isEmpty());
            Fixture revoked = new Fixture(); revoked.before = uri -> { if (uri.getHost().endsWith("music.126.net")) account.retire(); };
            fails(() -> resolve(revoked, account)); check(revoked.requests.getLast().disconnected);
        } finally { account.retire(); }
        AccountCredentials hostOnly = credentials(false);
        try { Fixture scoped = new Fixture(); fails(() -> resolve(scoped, hostOnly)); check(scoped.requests.size() == 1); } finally { hostOnly.retire(); }
        var seeded=new ScopedCookies(AccountPlatform.NETEASE,CLOCK); seeded.seedNeteaseRefresh("REFRESH_SECRET");
        var qrAccount=new AccountCredentials(AccountPlatform.NETEASE,"42",seeded.snapshot(),CLOCK);
        try { Fixture fromQr=new Fixture(); check(resolve(fromQr,qrAccount).lease().belongsTo(qrAccount.accountScope()));
            check(fromQr.requests.get(2).getRequestProperty("Cookie").contains("REFRESH_SECRET") && fromQr.requests.getLast().getRequestProperty("Cookie")==null);
        } finally {qrAccount.retire();}
        System.out.println("PASS: " + checks + " NetEase target/EAPI/full-track/scope checks (fake API/CDN, no playback)");
    }
}
