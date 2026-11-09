package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Virtual-time official-page stages; no Edge, website, saved account or QR request. */
public final class NeteaseDeviceCollectorContractTest {
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"),ZoneOffset.UTC);
    private static int checks;
    private static void check(boolean ok) {checks++;if(!ok)throw new AssertionError("Device collector "+checks);}
    private static Map<String,Object> cookie(String name,String value,String domain) {
        return Map.of("name",name,"value",value,"domain",domain,"path","/","secure",false,"session",true);
    }
    private static final class Page implements NeteaseDeviceCollector.Page {
        long time;int starts,polls,reads;
        boolean navigationError,changing,changedOnce,neverPage,neverApi,neverToken,neverCookie,foreign,changedToken;
        String tokenError;
        List<Object> cookies=new ArrayList<>(List.of(
                cookie("sDeviceId","fixture-device",".music.163.com"),
                cookie("WM_NI","SYNTHETIC",".music.163.com"),
                cookie("_ntes_nuid","SYNTHETIC_PARENT",".163.com"),
                cookie("_ntes_nnid","SYNTHETIC,ANALYTICS",".163.com")));
        Runnable paused=()->{};
        public Map<String,Object> call(String method,Map<String,Object> params) throws IOException {
            if(method.equals("Page.navigate")) {
                check(params.get("url").equals("https://music.163.com/"));
                return navigationError?Map.of("errorText","SYNTHETIC_NETWORK_ERROR"):Map.of();
            }
            if(method.equals("Network.getCookies")) {
                reads++;
                return Map.of("cookies",neverCookie || time<3_000_000_000L?List.of():cookies);
            }
            check(method.equals("Runtime.evaluate") && !params.containsKey("awaitPromise"));
            Object expression=params.get("expression");Map<String,Object> value;
            if(expression.equals(NeteaseDeviceCollector.READY_SCRIPT)) {
                if(changing && !changedOnce) {changedOnce=true;throw new NeteaseDeviceCollector.ContextUnavailable();}
                boolean loaded=!neverPage && time>=800_000_000L;
                boolean ready=!neverApi && time>=1_600_000_000L;
                value=Map.of("origin",foreign?"https://evil.invalid":loaded?"https://music.163.com":"null","loaded",loaded,"ready",ready);
            } else if(expression.equals(NeteaseDeviceCollector.START_SCRIPT)) {
                starts++;check(time>=1_600_000_000L);value=Map.of("started",true);
            } else {
                check(expression.equals(NeteaseDeviceCollector.POLL_SCRIPT));polls++;
                value=tokenError!=null?Map.of("error",tokenError):
                    neverToken || time<2_400_000_000L?Map.of("pending",true):
                    Map.of("token",changedToken && reads>0?"SYNTHETIC_CHANGED":"SYNTHETIC_TOKEN","userAgent",AuthHttp.DEFAULT_USER_AGENT);
            }
            return Map.of("result",Map.of("value",value));
        }
        public void pause(long millis) {time+=TimeUnit.MILLISECONDS.toNanos(millis);paused.run();}
    }
    private static byte[] collect(Page page,RequestScope request) throws IOException {
        return new NeteaseDeviceCollector(page,()->page.time,CLOCK).collect(request);
    }
    private static void rejects(Page page,AuthFailure.Reason reason) throws Exception {
        try(var request=new RequestScope(()->true)) {
            try {collect(page,request);throw new AssertionError("Expected device failure");}
            catch(AuthFailure safe) {
                check(safe.getMessage().equals("NET_DEVICE/"+reason));
                check(!safe.getMessage().contains("SYNTHETIC") && !AuthFailure.diagnostic(safe).contains("SYNTHETIC"));
                check(safe.requiresWebVerification());
            }
        }
    }
    public static void main(String[] args) throws Exception {
        var page=new Page();page.changing=true;
        try(var request=new RequestScope(()->true)) {
            byte[] output=collect(page,request);
            try {
                var snapshot=NeteaseDeviceSnapshot.read(output,CLOCK);
                check(snapshot.deviceId().equals("fixture-device") && snapshot.cookies().size()==2);
                check(snapshot.cookies().stream().allMatch(c->AccountPlatform.NETEASE.allows(c.domain())));
                check(page.starts==1 && page.polls>1 && page.reads>1 && page.changedOnce);
            } finally {Arrays.fill(output,(byte)0);}
        }
        for(String device:List.of("fixture%2Bdevice%2Fpart%3D","fixture%2bdevice%2fpart%3d")) {
            var encoded=new Page();encoded.cookies.set(0,cookie("sDeviceId",device,".music.163.com"));
            try(var request=new RequestScope(()->true)) {
                byte[] output=collect(encoded,request);
                try {
                    check(NeteaseDeviceSnapshot.read(output,CLOCK).deviceId().equals(device));
                    check(encoded.starts==1);
                } finally {Arrays.fill(output,(byte)0);}
            }
        }
        var bad=new Page();bad.navigationError=true;rejects(bad,AuthFailure.Reason.DEVICE_PAGE);check(bad.starts==0);
        bad=new Page();bad.neverPage=true;rejects(bad,AuthFailure.Reason.DEVICE_PAGE);check(bad.starts==0);
        bad=new Page();bad.neverApi=true;rejects(bad,AuthFailure.Reason.DEVICE_API);check(bad.starts==0);
        bad=new Page();bad.foreign=true;rejects(bad,AuthFailure.Reason.DEVICE_PAGE);check(bad.starts==0);
        bad=new Page();bad.neverToken=true;rejects(bad,AuthFailure.Reason.DEVICE_TOKEN);check(bad.starts==1);
        bad=new Page();bad.tokenError="SYNTHETIC_SCRIPT_ERROR";rejects(bad,AuthFailure.Reason.DEVICE_TOKEN);
        bad=new Page();bad.tokenError="DEVICE_CONTEXT";rejects(bad,AuthFailure.Reason.DEVICE_CONTEXT);
        bad=new Page();bad.neverCookie=true;rejects(bad,AuthFailure.Reason.DEVICE_COOKIE);check(bad.starts==1);
        bad=new Page();bad.changedToken=true;rejects(bad,AuthFailure.Reason.DEVICE_CONTEXT);
        bad=new Page();bad.cookies.add(cookie("MUSIC_U","SYNTHETIC_ACCOUNT",".music.163.com"));rejects(bad,AuthFailure.Reason.DEVICE_CONTEXT);
        bad=new Page();bad.cookies.add(cookie("sDeviceId","fixture-other","music.163.com"));rejects(bad,AuthFailure.Reason.DEVICE_COOKIE);
        bad=new Page();bad.cookies.set(0,cookie("sDeviceId","bad,id",".music.163.com"));rejects(bad,AuthFailure.Reason.DEVICE_COOKIE);
        var cancel=new Page();
        try(var request=new RequestScope(()->true)) {
            cancel.paused=request::close;
            try {collect(cancel,request);throw new AssertionError("Cancellation ignored");}
            catch(CancellationException expected) {check(cancel.starts==0);}
        }
        // Error transport must survive the QR caller's remapping, without any QR HTTP attempt.
        for(var reason:List.of(AuthFailure.Reason.BROWSER_MISSING,AuthFailure.Reason.BROWSER_POLICY,
                AuthFailure.Reason.DEVICE_PAGE,AuthFailure.Reason.DEVICE_API,AuthFailure.Reason.DEVICE_TOKEN,
                AuthFailure.Reason.DEVICE_COOKIE,AuthFailure.Reason.DEVICE_CONTEXT)) {
            try(var request=new RequestScope(()->true);var client=new NeteaseQrClient(CLOCK,
                new AuthHttp((uri,form)->{throw new AssertionError("Device failure reached QR HTTP");}),
                r->{throw AuthFailure.code(AuthFailure.Stage.NET_WEB,reason,42);})) {
                try {client.create(request);throw new AssertionError("Device failure accepted");}
                catch(AuthFailure safe) {check(safe.getMessage().equals("NET_DEVICE/"+reason+"=42"));}
            }
        }
        System.out.println("PASS: "+checks+" staged page/API/token/cookie readiness, transient navigation, one token call, parent-cookie isolation, no forged fallback, cancellation and QR error propagation; synthetic only");
    }
}
