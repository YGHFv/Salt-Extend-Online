package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AuthProtocolContractTest {
    private static int checks;
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC);
    private static final String KEY = "abcdefghijklmnop0123456789abcdef";
    @FunctionalInterface interface Action { void run() throws Exception; }
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("Auth check " + checks); }
    static void fails(Action action) throws Exception { try { action.run(); throw new AssertionError("Expected rejection"); } catch (IOException | IllegalArgumentException expected) { check(!String.valueOf(expected.getMessage()).contains("COOKIE_SECRET")); } }
    static AccountCredentials credentials(AccountPlatform platform) throws IOException {
        ScopedCookies cookies = new ScopedCookies(platform, CLOCK);
        cookies.accept(URI.create("https://" + platform.domain() + "/"), List.of(platform.loginCookie() + "=COOKIE_SECRET; Domain=." + platform.domain() + "; Path=/; Secure; Max-Age=3600"));
        return new AccountCredentials(platform, "42", cookies.snapshot(), CLOCK);
    }
    static NeteaseDeviceSnapshot device(RequestScope request) {
        request.check(); return new NeteaseDeviceSnapshot("SYNTHETIC_DEVICE_TOKEN", "fixture-device", AuthHttp.DEFAULT_USER_AGENT,
                List.of(new ScopedCookies.Entry("sDeviceId", "fixture-device", "music.163.com", "/", false, true, CLOCK.instant().plusSeconds(300))));
    }
    static final class Response extends HttpURLConnection {
        final String json; final List<String> cookies; final String refresh; boolean disconnected;
        int status=200; String contentType="application/json";
        Response(URI uri, String json, List<String> cookies, String refresh) throws IOException { super(uri.toURL()); this.json = json; this.cookies = cookies; this.refresh = refresh; }
        public void connect() { } public int getResponseCode() { return status; }
        public java.io.InputStream getInputStream() { return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)); }
        public String getHeaderField(String name) { return name.equalsIgnoreCase("Content-Type") ? contentType : name.equalsIgnoreCase("x-refresh-token") ? refresh : null; }
        public Map<String,List<String>> getHeaderFields() { return Map.of("Set-Cookie", cookies); }
        public void disconnect() { disconnected = true; } public boolean usingProxy() { return false; }
    }
    private static String biliQr() { return "{\"code\":0,\"data\":{\"qrcode_key\":\"" + KEY + "\",\"url\":\"https://passport.bilibili.com/h5-app/passport/login/scan?qrcode_key=" + KEY + "\"}}"; }
    public static void main(String[] args) throws Exception {
        ScopedCookies bili = new ScopedCookies(AccountPlatform.BILIBILI, CLOCK);
        URI passport = URI.create("https://passport.bilibili.com/x/passport-login/web/qrcode/poll"), api = URI.create("https://api.bilibili.com/x/test");
        bili.accept(passport, List.of("a=1; Path=/; Secure", "a=2; Domain=.bilibili.com; Path=/x/; Secure", "SESSDATA=COOKIE_SECRET; Domain=.bilibili.com; Path=/; Secure; Max-Age=3600"));
        check(bili.header(api).contains("SESSDATA=COOKIE_SECRET")); check(!bili.header(api).contains("a=1"));
        check(bili.header(api).contains("a=2")); check(!bili.header(URI.create("https://api.bilibili.com/xy")).contains("a=2"));
        fails(() -> bili.header(URI.create("https://upos-fixture.bilivideo.com/audio")));
        fails(() -> bili.header(URI.create("http://api.bilibili.com/")));
        fails(() -> bili.accept(passport, List.of("bad=1; Domain=evil.com")));
        fails(() -> bili.accept(passport, List.of("__Host-a=1; Domain=.bilibili.com; Path=/; Secure")));
        bili.accept(passport, List.of("SESSDATA=; Domain=.bilibili.com; Path=/; Max-Age=0")); check(bili.value(api, "SESSDATA").isEmpty());
        AccountCredentials account = credentials(AccountPlatform.BILIBILI);
        check(account.isActive()); check(!account.toString().contains("COOKIE_SECRET"));
        check(!account.cookieSnapshot().toString().contains("COOKIE_SECRET"));
        fails(() -> account.header(URI.create("https://music.163.com/")));
        account.retire(); check(!account.isActive()); fails(account::cookieSnapshot);
        List<Response> requests = new ArrayList<>(); int[] polls = {0};
        AuthHttp biliHttp = new AuthHttp((uri, form) -> {
            check(form == null); String json; List<String> cookies = List.of();
            if (uri.getPath().endsWith("generate")) json = biliQr();
            else if (uri.getPath().endsWith("poll")) {
                int code = new int[]{86101,86090,0}[polls[0]++]; json = "{\"code\":0,\"data\":{\"code\":" + code + "}}";
                if (code == 0) cookies = List.of("SESSDATA=COOKIE_SECRET; Domain=.bilibili.com; Path=/; Secure; Max-Age=3600", "DedeUserID=42; Domain=.bilibili.com; Path=/; Secure");
            } else json = "{\"code\":0,\"data\":{\"isLogin\":true,\"mid\":42,\"uname\":\"Fixture user\",\"face\":\"//i0.hdslb.com/avatar.jpg@96w.webp\"}}";
            Response response = new Response(uri, json, cookies, null); requests.add(response); return response;
        });
        try (BiliQrClient client = new BiliQrClient(CLOCK, biliHttp); RequestScope scope = new RequestScope(() -> true)) {
            var session = client.create(scope); check(!session.toString().contains(KEY)); check(session.expiresAt().equals(CLOCK.instant().plusSeconds(180)));
            check(client.poll(session, scope).state() == QrAuthClient.State.WAITING_SCAN);
            check(client.poll(session, scope).state() == QrAuthClient.State.WAITING_CONFIRMATION);
            var result = client.poll(session, scope); check(result.state() == QrAuthClient.State.CONFIRMED && result.credentials().userId().equals("42"));
            check(result.credentials().profile().name().equals("Fixture user"));
            check(result.credentials().profile().avatar().toString().equals("https://i0.hdslb.com/avatar.jpg"));
            check(!result.credentials().profile().toString().contains("avatar.jpg"));
            var restoredProfile = client.verify(result.credentials(), scope);
            check(restoredProfile.profile().equals(result.credentials().profile()) && !restoredProfile.revision().equals(result.credentials().revision()));
            restoredProfile.retire();
            check(requests.getLast().getRequestProperty("Cookie").contains("SESSDATA=COOKIE_SECRET"));
            check(requests.stream().allMatch(r -> r.disconnected)); result.credentials().retire();
            check(client.poll(new QrAuthClient.Session(KEY, session.qrContent(), "", CLOCK.instant()), scope).state() == QrAuthClient.State.EXPIRED);
        }
        check(AccountCredentials.Profile.from(AccountPlatform.BILIBILI,"Bad\nname","https://evil.example/avatar?SECRET").avatar()==null);
        check(AccountCredentials.Profile.from(AccountPlatform.BILIBILI,"Bad\nname",null).name().isEmpty());
        for (String json : List.of(biliQr().replace("https://passport.bilibili.com", "https://evil.com"), biliQr().replace("qrcode_key=" + KEY, "qrcode_key=wrong"), biliQr().replace("\"code\":0", "\"code\":-412"))) {
            try (BiliQrClient invalid = new BiliQrClient(CLOCK, new AuthHttp((uri,form) -> new Response(uri, json, List.of(), null))); RequestScope scope = new RequestScope(() -> true)) { fails(() -> invalid.create(scope)); }
        }
        try (BiliQrClient modern = new BiliQrClient(CLOCK, new AuthHttp((uri,form) -> new Response(uri,
                biliQr().replace("passport.bilibili.com/h5-app/passport/login/scan", "account.bilibili.com/h5/account-h5/auth/scan-web"), List.of(), null))); RequestScope scope = new RequestScope(() -> true)) {
            check(modern.create(scope).qrContent().getHost().equals("account.bilibili.com"));
        }
        try (BiliQrClient invalid = new BiliQrClient(CLOCK, new AuthHttp((uri,form) -> new Response(uri, "{\"code\":0,\"data\":{\"code\":0}}", List.of(), null))); RequestScope scope = new RequestScope(() -> true)) {
            fails(() -> invalid.poll(new QrAuthClient.Session(KEY, URI.create("https://passport.bilibili.com/"), "", CLOCK.instant().plusSeconds(10)), scope));
        }
        String secret = "abcdefghijklmnop"; String form = new String(NeteaseWeapi.form(Map.of("type",1,"noCheckToken",true), secret), StandardCharsets.US_ASCII);
        Map<String,String> values = new java.util.HashMap<>(); for (String part : form.split("&")) { var pair = part.split("=",2); values.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8)); }
        check(values.get("encSecKey").matches("[0-9a-f]{256}"));
        String inner = decrypt(values.get("params"), secret), plain = decrypt(inner, "0CoJUm6Qyw8W8jud");
        var payload = BoundedJson.object(BoundedJson.parse(plain.getBytes(StandardCharsets.UTF_8))); check(BoundedJson.integer(payload.get("type")) == 1 && payload.get("noCheckToken").equals(true));
        check(!java.util.Arrays.equals(NeteaseWeapi.form(Map.of("type",1)), NeteaseWeapi.form(Map.of("type",1))));
        int[] netPolls={0}; List<Response> netRequests=new ArrayList<>();
        AuthHttp netHttp = new AuthHttp((uri, body) -> {
            check(body != null && new String(body, StandardCharsets.US_ASCII).contains("encSecKey="));
            String json, refresh = null;
            if (uri.getPath().endsWith("unikey")) json = "{\"code\":200,\"unikey\":\""+KEY+"\"}";
            else if (uri.getPath().endsWith("login")) { int code = new int[]{801,802,803}[netPolls[0]++]; json="{\"code\":"+code+"}"; if(code==803)refresh="COOKIE_SECRET"; }
            else json="{\"code\":200,\"account\":{\"id\":42},\"profile\":{\"userId\":42}}";
            Response response=new Response(uri,json,List.of(),refresh); netRequests.add(response); return response;
        });
        try (NeteaseQrClient client = new NeteaseQrClient(CLOCK, netHttp, AuthProtocolContractTest::device); RequestScope scope=new RequestScope(() -> true)) {
            var session=client.create(scope); check(session.qrContent().getHost().equals("music.163.com"));
            check(client.poll(session,scope).state()==QrAuthClient.State.WAITING_SCAN);
            check(client.poll(session,scope).state()==QrAuthClient.State.WAITING_CONFIRMATION);
            var result=client.poll(session,scope); check(result.credentials().userId().equals("42"));
            check(result.credentials().header(URI.create("https://interface.music.163.com/eapi/song/enhance/player/url/v1")).contains("MUSIC_U=COOKIE_SECRET"));
            fails(()->result.credentials().header(URI.create("https://m7.music.126.net/audio")));
            var restored=client.verify(result.credentials(),scope);
            check(restored.header(URI.create("https://interface.music.163.com/")).contains("MUSIC_U=COOKIE_SECRET")); restored.retire();
            check(netRequests.getLast().getRequestProperty("Cookie").contains("MUSIC_U=COOKIE_SECRET"));
            check(netRequests.stream().allMatch(r->"1".equals(r.getRequestProperty("nm-gcore-status")) && "undefined".equals(r.getRequestProperty("x-channelsource"))));
            check(netRequests.get(1).getRequestProperty("x-loginmethod").equals("QrCode")); result.credentials().retire();
        }
        try (NeteaseQrClient invalid=new NeteaseQrClient(CLOCK,new AuthHttp((uri,body) -> new Response(uri,"{\"code\":803}",List.of(),null))); RequestScope scope=new RequestScope(() -> true)) {
            fails(() -> invalid.poll(new QrAuthClient.Session(KEY,URI.create("https://music.163.com/"),"chain",CLOCK.instant().plusSeconds(10)),scope));
        }
        ScopedCookies seeded=new ScopedCookies(AccountPlatform.NETEASE,CLOCK);
        seeded.seedNeteaseRefresh("COOKIE_SECRET"); seeded.seedNeteaseRefresh("DO_NOT_REPLACE");
        check(seeded.value(URI.create("https://interface.music.163.com/"),"MUSIC_U").equals("COOKIE_SECRET"));
        seeded.clear(); seeded.accept(URI.create("https://music.163.com/"),List.of("MUSIC_U=HOST_SECRET; Path=/; Secure")); seeded.seedNeteaseRefresh("DO_NOT_REPLACE");
        check(seeded.value(URI.create("https://interface.music.163.com/"),"MUSIC_U").isEmpty());
        for(String profile:List.of("null","{}","{\"nickname\":\"Fixture\"}","{\"userId\":42}")) {
            try(var client=new NeteaseQrClient(CLOCK,new AuthHttp((uri,body)->new Response(uri,"{\"code\":200,\"account\":{\"id\":42},\"profile\":"+profile+"}",List.of(),null)));
                var request=new RequestScope(()->true)) {
                var saved=credentials(AccountPlatform.NETEASE); var verified=client.verify(saved,request);
                check(verified.userId().equals("42")); saved.retire(); verified.retire();
            }
        }
        for(String path:List.of("/weapi/login/qrcode/unikey","/weapi/login/qrcode/client/login","/weapi/w/nuser/account/get")) {
            for(int mode=0;mode<5;mode++) {
                final int selected=mode; var jar=new ScopedCookies(AccountPlatform.NETEASE,CLOCK);
                var http=new AuthHttp((uri,body)->{
                    if(selected==0) throw new java.net.SocketTimeoutException("COOKIE_SECRET https://secret.invalid");
                    var response=new Response(uri,selected==2?"COOKIE_SECRET":"{}",selected==4?List.of("bad=COOKIE_SECRET; Domain=evil.invalid"):List.of(),null);
                    if(selected==1) response.status=429; if(selected==3) response.contentType="text/html"; return response;
                });
                try(var request=new RequestScope(()->true)) {
                    try { http.exchange(URI.create("https://music.163.com"+path),NeteaseWeapi.form(Map.of()),jar,Map.of(),request); throw new AssertionError("Expected safe failure"); }
                    catch(AuthFailure expected) {
                        String reason=switch(mode) {case 0->"TIMEOUT";case 1->"HTTP=429";case 4->"COOKIE";default->"RESPONSE";};
                        check(expected.getMessage().equals(AuthFailure.stage(path)+"/"+reason));
                        check(expected.getCause()==null && !AuthFailure.diagnostic(expected).contains("SECRET"));
                    }
                }
            }
        }
        try(var client=new NeteaseQrClient(CLOCK,new AuthHttp((uri,body)->new Response(uri,"{\"code\":-462,\"message\":\"COOKIE_SECRET\"}",List.of(),null)), AuthProtocolContractTest::device);var request=new RequestScope(()->true)) {
            try { client.create(request); throw new AssertionError("Expected API failure"); }
            catch(AuthFailure expected) { check(expected.getMessage().equals("NET_CREATE/API=-462")); }
        }
        check(!AuthFailure.diagnostic(new IOException("COOKIE_SECRET")).contains("COOKIE_SECRET"));
        System.out.println("PASS: "+checks+" QR protocol / scoped-cookie / WEAPI checks (fake HTTP, no real account)");
    }
    private static String decrypt(String text,String key) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding"); cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII),"AES"),new IvParameterSpec("0102030405060708".getBytes(StandardCharsets.US_ASCII)));
        return new String(cipher.doFinal(Base64.getDecoder().decode(text)),StandardCharsets.UTF_8);
    }
}
