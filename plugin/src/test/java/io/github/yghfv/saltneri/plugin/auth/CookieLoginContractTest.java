package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.*;
import io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Explicit cookie import, official verification and lifecycle fixtures; no browser or real account. */
public final class CookieLoginContractTest {
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"),ZoneOffset.UTC);
    private static final String NET="Cookie: MUSIC_U=COOKIE_SECRET; __csrf=csrf_fixture; irrelevant=ignored;";
    private static final String QQ="uin=o00042; qm_keyst=MUSIC_SECRET; login_type=1; p_skey=UNRELATED_SECRET";
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("Cookie login check "+checks);}
    @FunctionalInterface interface Action {void run() throws Exception;}
    private static void fails(Action action) throws Exception {
        try {action.run();throw new AssertionError("Expected cookie rejection");}
        catch(IOException | IllegalArgumentException | SecurityException expected) {check(!String.valueOf(expected.getMessage()).contains("SECRET"));}
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean() && System.nanoTime()<until)Thread.sleep(10);
        check(condition.getAsBoolean());
    }
    private static final class Response extends HttpURLConnection {
        final byte[] json;boolean disconnected;
        Response(URI uri,Object body) throws IOException {super(uri.toURL());json=BoundedJson.encode(body);}
        public void connect(){}
        public int getResponseCode(){return 200;}
        public String getHeaderField(String key){return key.equalsIgnoreCase("Content-Type")?"application/json":null;}
        public Map<String,List<String>> getHeaderFields(){return Map.of();}
        public InputStream getInputStream(){return new ByteArrayInputStream(json);}
        public void disconnect(){disconnected=true;}
        public boolean usingProxy(){return false;}
    }
    private static final class Fixture {
        final List<Response> requests=new CopyOnWriteArrayList<>();
        final AtomicInteger verifications=new AtomicInteger();
        boolean reject;
        CountDownLatch entered,release;
        QrAuthClient client(AccountPlatform platform) {
            return platform==AccountPlatform.NETEASE?new NeteaseQrClient(CLOCK,new AuthHttp(this::open))
                    :new QqMusicQrClient(CLOCK,new QqMusicHttp(this::open));
        }
        HttpURLConnection open(URI uri,byte[] body) throws IOException {
            check(body!=null);verifications.incrementAndGet();
            if(entered!=null) {
                entered.countDown();
                try {release.await(3,TimeUnit.SECONDS);} catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new IOException("Cancelled");}
            }
            Object reply;
            if(uri.getHost().equals("music.163.com")) {
                check(uri.getPath().equals("/weapi/w/nuser/account/get"));
                reply=Map.of("code",reject?301:200,"account",Map.of("id",42),"profile",Map.of("userId",42,"nickname","Fixture"));
            } else {
                check(uri.equals(QqMusicHttp.GATEWAY));
                var envelope=BoundedJson.object(BoundedJson.parse(body));
                check(BoundedJson.object(envelope.get("req_0")).get("method").equals("GetLoginUserInfo"));
                reply=Map.of("code",0,"req_0",Map.of("code",reject?1000:0,"data",Map.of("info",Map.of("uin",42,"nick","Fixture"))));
            }
            var response=new Response(uri,reply);requests.add(response);return response;
        }
    }
    public static void main(String[] args) throws Exception {
        var net=CookieLogin.parse(AccountPlatform.NETEASE,NET,CLOCK);
        check(net.size()==2 && net.stream().allMatch(e->e.domain().equals("music.163.com") && e.secure() && !e.hostOnly()));
        var qq=CookieLogin.parse(AccountPlatform.QQMUSIC,QQ,CLOCK);
        check(qq.stream().noneMatch(e->e.name().equals("p_skey")));
        check(qq.stream().anyMatch(e->e.name().equals("qqmusic_uin") && e.value().equals("42")));
        check(qq.stream().anyMatch(e->e.name().equals("tmeLoginType") && e.value().equals("2")));
        var wx=CookieLogin.parse(AccountPlatform.QQMUSIC,"qqmusic_uin=42; authst=W_XSECRET; loginType=1",CLOCK);
        check(wx.stream().anyMatch(e->e.name().equals("tmeLoginType") && e.value().equals("1")));
        check(CookieLogin.parse(AccountPlatform.QQMUSIC,"uin=42; musickey=MUSIC_SECRET",CLOCK).stream().anyMatch(e->e.name().equals("qqmusic_key")));
        for(String invalid:List.of("","MUSIC_U=SECRET\r\nOther: x","MUSIC_U=a; MUSIC_U=b","MUSIC_U=\"SECRET\"",
                "MUSIC_U=SECRET,other=x","MUSIC_U=SECRET; bad", "MUSIC_U="+"x".repeat(8192)))
            fails(()->CookieLogin.parse(AccountPlatform.NETEASE,invalid,CLOCK));
        for(String invalid:List.of("uin=42","qm_keyst=SECRET","uin=42; qm_keyst=SECRET; loginType=3",
                "uin=42; qm_keyst=SECRET; login_type=2; tmeLoginType=2","uin=42; qm_keyst=A; qqmusic_key=B"))
            fails(()->CookieLogin.parse(AccountPlatform.QQMUSIC,invalid,CLOCK));
        fails(()->CookieLogin.parse(AccountPlatform.APPLEMUSIC,NET,CLOCK));
        for(var platform:List.of(AccountPlatform.NETEASE,AccountPlatform.QQMUSIC)) {
            var fixture=new Fixture();
            try(var client=fixture.client(platform);var request=new RequestScope(()->true)) {
                var verified=client.verifyCookies(platform==AccountPlatform.NETEASE?net:qq,CLOCK,request);
                check(verified.userId().equals("42") && verified.profile().name().equals("Fixture"));
                check(fixture.verifications.get()==1 && fixture.requests.getFirst().disconnected);
                check(fixture.requests.getFirst().getRequestProperty("Cookie").contains(platform==AccountPlatform.NETEASE?"COOKIE_SECRET":"MUSIC_SECRET"));
                check(!fixture.requests.getFirst().getRequestProperty("Cookie").contains("UNRELATED_SECRET"));
                verified.retire();
            }
            var changes=new AtomicInteger();var permitted=new AtomicBoolean(true);
            var controllerFixture=new Fixture();
            try(var controller=new AccountLoginController(platform,()->controllerFixture.client(platform),permitted::get,null,changes::incrementAndGet,CLOCK,10)) {
                controller.startCookieLogin(platform==AccountPlatform.NETEASE?NET:QQ,false);
                await(()->controller.credentials()!=null || controller.snapshot().phase()==AccountLoginController.Phase.ERROR);
                check(controller.snapshot().phase()==AccountLoginController.Phase.LOGGED_IN && controllerFixture.verifications.get()==1);
                var account=controller.credentials();
                fails(()->controller.startCookieLogin("invalid",false));
                check(controller.credentials()==account && account.isActive());
                check(!controller.snapshot().toString().contains("SECRET") && !controller.snapshot().status().contains("SECRET"));
                controller.logout();check(!account.isActive() && controller.credentials()==null);
                controllerFixture.reject=true;
                controller.startCookieLogin(platform==AccountPlatform.NETEASE?NET:QQ,false);
                await(()->controller.snapshot().phase()==AccountLoginController.Phase.ERROR);
                check(controller.credentials()==null && controller.snapshot().status().contains("COOKIE/"));
                check(!controller.snapshot().status().contains("SECRET"));
                controllerFixture.reject=false;controllerFixture.entered=new CountDownLatch(1);controllerFixture.release=new CountDownLatch(1);
                controller.startCookieLogin(platform==AccountPlatform.NETEASE?NET:QQ,false);
                check(controllerFixture.entered.await(2,TimeUnit.SECONDS));
                controller.cancelLogin();controllerFixture.release.countDown();
                check(controller.credentials()==null && controller.snapshot().phase()==AccountLoginController.Phase.IDLE);
                permitted.set(false);fails(()->controller.startCookieLogin(platform==AccountPlatform.NETEASE?NET:QQ,false));
                check(changes.get()>0);
            }
        }
        System.out.println("PASS: "+checks+" cookie header/aliases/official verification/redaction/cancel/logout/replacement checks; fixtures only");
    }
}
