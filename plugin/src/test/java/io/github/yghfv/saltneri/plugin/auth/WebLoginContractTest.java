package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Synthetic risk response and explicit web-login lifecycle; no browser or real account. */
public final class WebLoginContractTest {
    private static int checks;
    private static final java.time.Clock CLOCK=AuthProtocolContractTest.CLOCK;
    private static final String COOKIE="{\"name\":\"MUSIC_U\",\"value\":\"SYNTHETIC_SECRET\",\"domain\":\".music.163.com\",\"path\":\"/\",\"secure\":true,\"session\":true,\"expires\":-1}";
    private static void check(boolean value) { checks++; if(!value) throw new AssertionError("Web login check "+checks); }
    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!condition.getAsBoolean() && System.nanoTime()<until) Thread.sleep(10);
        check(condition.getAsBoolean());
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static List<ScopedCookies.Entry> parse(String cookie) throws IOException { return WindowsNeteaseWebLogin.readCookies(bytes("{\"cookies\":["+cookie+"]}"),CLOCK); }
    public static void main(String[] args) throws Exception {
        var entries=parse(COOKIE);
        check(entries.size()==1 && !entries.getFirst().hostOnly() && entries.getFirst().expiry().equals(CLOCK.instant().plusSeconds(30*86400L)));
        check(parse(COOKIE.replace(".music.163.com","music.163.com")).getFirst().hostOnly());
        check(parse(COOKIE.replace("\"session\":true","\"session\":false").replace("\"expires\":-1","\"expires\":"+(CLOCK.instant().getEpochSecond()+100)+".75")).getFirst().expiry().equals(CLOCK.instant().plusSeconds(100)));
        for(String invalid:List.of(COOKIE.replace("music.163.com","evil.invalid"), COOKIE.replace("music.163.com","music.163.com.evil"),
                COOKIE.replace("MUSIC_U","OTHER"),COOKIE.replace("SYNTHETIC_SECRET","bad;COOKIE_SECRET"),COOKIE+","+COOKIE,
                COOKIE.replace("\"path\":\"/\"","\"path\":\"bad\""),COOKIE.replace("\"session\":true","\"session\":false"))) {
            try { parse(invalid); throw new AssertionError("Invalid web cookie accepted"); }
            catch(IOException expected) { check(!String.valueOf(expected.getMessage()).contains("SECRET")); }
        }
        for(String error:List.of("EDGE_MISSING","WEB_TIMEOUT","WEB_CLOSED","COOKIE_SECRET")) {
            try { WindowsNeteaseWebLogin.readCookies(bytes("{\"error\":\""+error+"\"}"),CLOCK); throw new AssertionError("Expected helper failure"); }
            catch(AuthFailure expected) { check(expected.getMessage().startsWith("NET_WEB/") && !AuthFailure.diagnostic(expected).contains("SECRET")); }
        }
        var verificationCalls=new AtomicInteger();
        try(var client=new NeteaseQrClient(CLOCK,new AuthHttp((uri,body)->{
            verificationCalls.incrementAndGet(); check(uri.getPath().equals("/weapi/w/nuser/account/get"));
            return new AuthProtocolContractTest.Response(uri,"{\"code\":200,\"account\":{\"id\":42},\"profile\":null}",List.of(),null);
        }));var request=new RequestScope(()->true)) {
            var verified=client.verifyWebCookies(entries,request);
            check(verified.userId().equals("42") && verificationCalls.get()==1); verified.retire();
        }
        try(var client=new NeteaseQrClient(CLOCK,new AuthHttp((uri,body)->new AuthProtocolContractTest.Response(uri,"{\"code\":200,\"account\":null,\"profile\":null}",List.of(),null)));
            var request=new RequestScope(()->true)) {
            try { client.verifyWebCookies(entries,request); throw new AssertionError("Unverified browser credential accepted"); }
            catch(AuthFailure expected) { check(expected.getMessage().equals("NET_VERIFY/IDENTITY")); }
        }
        var polls=new AtomicInteger(); var browserCalls=new AtomicInteger();
        java.util.function.Supplier<QrAuthClient> clients=()->new NeteaseQrClient(CLOCK,new AuthHttp((uri,body)->{
            if(uri.getPath().endsWith("unikey")) return new AuthProtocolContractTest.Response(uri,"{\"code\":200,\"unikey\":\"abcdefghijklmnop1234567890abcdef\"}",List.of(),null);
            polls.incrementAndGet(); return new AuthProtocolContractTest.Response(uri,"{\"code\":8821,\"message\":\"COOKIE_SECRET\",\"data\":{\"url\":\"https://secret.invalid\"}}",List.of(),null);
        }), AuthProtocolContractTest::device);
        try(var controller=new AccountLoginController(AccountPlatform.NETEASE,clients,()->true,null,()->{},CLOCK,10)) {
            controller.start(false);
            waitFor(()->controller.snapshot().phase()==AccountLoginController.Phase.VERIFICATION_REQUIRED);
            check(controller.credentials()==null && controller.snapshot().qrContent()==null);
            check(controller.snapshot().status().contains("8821") && !controller.snapshot().status().contains("SECRET") && !controller.snapshot().status().contains("https://"));
            Thread.sleep(150); check(polls.get()==1 && browserCalls.get()==0);
            controller.startWebLogin(false,request->{browserCalls.incrementAndGet();return AuthProtocolContractTest.credentials(AccountPlatform.NETEASE);});
            waitFor(()->controller.snapshot().phase()==AccountLoginController.Phase.LOGGED_IN);
            check(controller.credentials()!=null && browserCalls.get()==1 && polls.get()==1);
            controller.logout(); check(controller.credentials()==null);
            var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var stale=AuthProtocolContractTest.credentials(AccountPlatform.NETEASE);
            controller.startWebLogin(false,request->{
                entered.countDown(); for(;;) {try {release.await();break;} catch(InterruptedException ignored) {}}
                return stale;
            });
            check(entered.await(2,TimeUnit.SECONDS)); check(controller.snapshot().phase()==AccountLoginController.Phase.WEB_LOGIN);
            controller.cancelLogin(); release.countDown(); waitFor(()->!stale.isActive());
            check(controller.credentials()==null && controller.snapshot().phase()==AccountLoginController.Phase.IDLE);
            controller.startWebLogin(false,request->{throw AuthFailure.of(AuthFailure.Stage.NET_WEB,AuthFailure.Reason.BROWSER_MISSING);});
            waitFor(()->controller.snapshot().phase()==AccountLoginController.Phase.ERROR);
            check(controller.snapshot().status().contains("Microsoft Edge") && controller.credentials()==null);
        }
        var allowed=new AtomicBoolean(true); var started=new CountDownLatch(1); var cancelled=new CountDownLatch(1);
        try(var controller=new AccountLoginController(AccountPlatform.NETEASE,clients,allowed::get,null,()->{},CLOCK,10)) {
            controller.startWebLogin(false,request->{
                started.countDown(); try { while(true) {request.check(); try {Thread.sleep(10);} catch(InterruptedException ignored) {}} }
                finally {cancelled.countDown();}
            });
            check(started.await(2,TimeUnit.SECONDS)); allowed.set(false);
            check(cancelled.await(2,TimeUnit.SECONDS)); check(controller.credentials()==null);
        }
        check(!AuthFailure.code(AuthFailure.Stage.BILI_POLL,AuthFailure.Reason.API,8821).requiresWebVerification());
        check(AuthFailure.code(AuthFailure.Stage.NET_CREATE,AuthFailure.Reason.API,-462).requiresWebVerification());
        System.out.println("PASS: "+checks+" risk-stop/explicit web login/cookie bounds/identity/cancellation/no credential leakage checks; synthetic only");
    }
}
