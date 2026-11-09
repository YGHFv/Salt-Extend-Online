package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Real direct Win32/Edge private-pipe probe; about:blank and synthetic data only. */
public final class WindowsNativeWebLoginProbe {
    private static Set<Path> roots() throws Exception {
        var result=new HashSet<Path>();
        try(var paths=Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")),"salt-edge-web-*")) {paths.forEach(result::add);}
        return result;
    }
    private static Map<String,Object> evaluate(WindowsEdgeProcess browser,String session,String expression) throws Exception {
        var result=browser.call("Runtime.evaluate",Map.of("expression",expression,"returnByValue",true),session);
        if(result.get("exceptionDetails")!=null)throw new AssertionError("Fixture JavaScript failed");
        return BoundedJson.object(BoundedJson.object(result.get("result")).get("value"));
    }
    private static String deviceScript(String script,String origin,String api,String loaded) {
        return "(function(location,createNEFingerprint,window,document){return "+script+
                "})({origin:'"+origin+"'},"+api+",globalThis.__saltFixtureWindow,{readyState:'"+loaded+"'})";
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1 || !args[0].equals("--explicit-headless-fixture")) throw new IllegalArgumentException("Explicit hidden fixture required");
        var before=roots();int checks=0;
        try(var request=new RequestScope(()->true,30000);var browser=WindowsEdgeProcess.open(request,true,null)) {
            if(!browser.inOwnedJob()) throw new AssertionError("Browser not owned by job");checks++;
            if(!BoundedJson.array(browser.call("Storage.getCookies",Map.of()).get("cookies")).isEmpty())
                throw new AssertionError("Existing browser cookies imported");checks++;
            if(ProcessHandle.current().descendants().anyMatch(p->p.info().command().orElse("").toLowerCase(Locale.ROOT).contains("powershell")))
                throw new AssertionError("Native login launched PowerShell");checks++;
            var acl=Files.getFileAttributeView(browser.root(),java.nio.file.attribute.AclFileAttributeView.class).getAcl();
            if(acl.size()!=1 || !acl.getFirst().principal().equals(Files.getOwner(browser.root())))
                throw new AssertionError("Private profile ACL changed");checks++;
            var page=WindowsEdgeWebLogin.pages(browser).getFirst();
            if(!"about:blank".equals(page.get("url"))) throw new AssertionError("Unexpected website");checks++;
            String session=BoundedJson.string(browser.call("Target.attachToTarget",Map.of("targetId",page.get("targetId"),"flatten",true)).get("sessionId"));
            browser.call("Network.setCookies",Map.of("cookies",List.of(
                Map.of("name","MUSIC_U","value","SYNTHETIC_SECRET","domain",".music.163.com","path","/","secure",true,"httpOnly",true))),session);
            byte[] output=BoundedJson.encode(browser.call("Network.getCookies",Map.of("urls",List.of("https://music.163.com/")),session));
            try {
                var cookies=WindowsNeteaseWebLogin.readCookies(output,Clock.systemUTC());
                if(cookies.size()!=1 || !cookies.getFirst().value().equals("SYNTHETIC_SECRET")) throw new AssertionError("Native cookie mismatch");checks++;
            } finally {Arrays.fill(output,(byte)0);}
            browser.call("Network.setCookies",Map.of("cookies",List.of(
                Map.of("name","qm_keyst","value","SYNTHETIC_SECRET","domain","y.qq.com","path","/","secure",true,"httpOnly",true),
                Map.of("name","uin","value","o00042","domain",".qq.com","path","/","secure",true),
                Map.of("name","tracking","value","not-imported","domain","y.qq.com","path","/"),
                Map.of("name","p_skey","value","NOT_IMPORTED","domain",".qq.com","path","/"))),session);
            var cookies=WindowsEdgeWebLogin.qqCookies(browser);
            if(!WindowsEdgeWebLogin.ready(AccountPlatform.QQMUSIC,cookies)) throw new AssertionError("QQ not ready");checks++;
            output=BoundedJson.encode(Map.of("cookies",cookies));
            try {
                var account=QqMusicWebCookies.decode(output,Clock.systemUTC());
                try {
                    if(!account.userId().equals("42") || account.cookieSnapshot().stream().anyMatch(c->c.name().equals("p_skey") || c.name().equals("tracking")))
                        throw new AssertionError("QQ credentials mismatch");checks++;
                } finally {account.retire();}
            } finally {Arrays.fill(output,(byte)0);}
            String official="https://music.163.com";
            String api="function(options){globalThis.__saltFixtureCalls++;globalThis.__saltFixtureOptions=options;"+
                "return {getToken:()=>new Promise(resolve=>{globalThis.__saltFixtureResolve=resolve})}}";
            evaluate(browser,session,"(globalThis.__saltFixtureWindow={},globalThis.__saltFixtureCalls=0,{ok:true})");
            for(String loaded:List.of("interactive","complete","loading")) {
                var value=evaluate(browser,session,deviceScript(NeteaseDeviceCollector.READY_SCRIPT,official,api,loaded));
                if(!official.equals(value.get("origin")) || !Boolean.TRUE.equals(value.get("ready")) ||
                    !Boolean.valueOf(!loaded.equals("loading")).equals(value.get("loaded")))
                    throw new AssertionError("Device page readiness mismatch");checks++;
            }
            var value=evaluate(browser,session,deviceScript(NeteaseDeviceCollector.READY_SCRIPT,official,"undefined","complete"));
            if(!Boolean.FALSE.equals(value.get("ready")))throw new AssertionError("Missing API not detected");checks++;
            value=evaluate(browser,session,deviceScript(NeteaseDeviceCollector.START_SCRIPT,official,"undefined","complete"));
            if(!"DEVICE_API".equals(value.get("error")))throw new AssertionError("Missing API started token");checks++;
            for(int start=0;start<2;start++) {
                value=evaluate(browser,session,deviceScript(NeteaseDeviceCollector.START_SCRIPT,official,api,"complete"));
                if(!Boolean.TRUE.equals(value.get("started")))throw new AssertionError("Token not started");checks++;
            }
            value=evaluate(browser,session,"({calls:globalThis.__saltFixtureCalls,appId:globalThis.__saltFixtureOptions.appId,timeout:globalThis.__saltFixtureOptions.timeout})");
            if(BoundedJson.integer(value.get("calls"))!=1 || !value.get("appId").equals("9d0ef7e0905d422cba1ecf7e73d77e67") ||
                BoundedJson.integer(value.get("timeout"))!=6000)throw new AssertionError("Token API repeated or options changed");checks++;
            value=evaluate(browser,session,deviceScript(NeteaseDeviceCollector.POLL_SCRIPT,official,api,"complete"));
            if(!Boolean.TRUE.equals(value.get("pending")))throw new AssertionError("Asynchronous token not pending");checks++;
            evaluate(browser,session,"(globalThis.__saltFixtureResolve({token:'SYNTHETIC_TOKEN'}),{ok:true})");
            value=evaluate(browser,session,deviceScript(NeteaseDeviceCollector.POLL_SCRIPT,official,api,"complete"));
            if(!"SYNTHETIC_TOKEN".equals(value.get("token")) || !AuthHttp.validUserAgent((String)value.get("userAgent")))
                throw new AssertionError("Current token/actual UA mismatch");checks++;
            for(String script:List.of(NeteaseDeviceCollector.START_SCRIPT,NeteaseDeviceCollector.POLL_SCRIPT)) {
                value=evaluate(browser,session,deviceScript(script,"https://evil.invalid",api,"complete"));
                if(!"DEVICE_CONTEXT".equals(value.get("error")))throw new AssertionError("Device origin guard failed");checks++;
            }
            for(String failure:List.of("throw new Error('SYNTHETIC')","return Promise.reject('SYNTHETIC')",
                    "return Promise.resolve({token:''})","return Promise.resolve({token:'bad\\nSYNTHETIC'})")) {
                evaluate(browser,session,"(globalThis.__saltFixtureWindow={},{ok:true})");
                value=evaluate(browser,session,deviceScript(NeteaseDeviceCollector.START_SCRIPT,official,
                    "function(){return {getToken:function(){"+failure+"}}}","complete"));
                if(!Boolean.TRUE.equals(value.get("started")))throw new AssertionError("Failure fixture not started");checks++;
                value=evaluate(browser,session,deviceScript(NeteaseDeviceCollector.POLL_SCRIPT,official,api,"complete"));
                if(!"DEVICE_TOKEN".equals(value.get("error")))throw new AssertionError("Invalid token accepted");checks++;
            }
            evaluate(browser,session,"(delete globalThis.__saltFixtureWindow.__saltNeteaseDevice,{ok:true})");
            value=evaluate(browser,session,deviceScript(NeteaseDeviceCollector.POLL_SCRIPT,official,api,"complete"));
            if(!"DEVICE_CONTEXT".equals(value.get("error")))throw new AssertionError("Lost token state accepted");checks++;
        }
        if(!roots().equals(before)) throw new AssertionError("Native profile leak");checks++;
        try(var request=new RequestScope(()->true,30000);var browser=WindowsEdgeProcess.open(request,true,null,true)) {
            if(!browser.inOwnedJob() || !browser.alive())throw new AssertionError("Explicit pipe compatibility failed");checks++;
            if(!BoundedJson.array(browser.call("Storage.getCookies",Map.of()).get("cookies")).isEmpty())
                throw new AssertionError("Compatibility profile imported cookies");checks++;
        }
        if(!roots().equals(before))throw new AssertionError("Compatibility profile leak");checks++;
        try(var request=new RequestScope(()->true,30000);var browser=WindowsEdgeProcess.open(request,true,null)) {
            var ready=WindowsEdgeProcess.class.getDeclaredField("ready");ready.setAccessible(true);ready.setBoolean(browser,false);
            try {browser.call("Synthetic.invalidMethod",Map.of());throw new AssertionError("CDP failure ignored");}
            catch(AuthFailure safe) {
                if(!"NET_WEB/BROWSER_CONNECT".equals(safe.getMessage()))throw new AssertionError("Cleanup fabricated browser exit");checks++;
            }
        }
        if(!roots().equals(before))throw new AssertionError("Failed-connect profile leak");checks++;
        try(var request=new RequestScope(()->true,30000);var browser=WindowsEdgeProcess.open(request,true,null)) {
            var ready=WindowsEdgeProcess.class.getDeclaredField("ready");ready.setAccessible(true);ready.setBoolean(browser,false);
            var process=WindowsEdgeProcess.class.getDeclaredField("process");process.setAccessible(true);
            var handle=(com.sun.jna.platform.win32.WinNT.HANDLE)process.get(browser);
            if(!com.sun.jna.platform.win32.Kernel32.INSTANCE.TerminateProcess(handle,7))
                throw new AssertionError("Owned exit fixture failed");
            com.sun.jna.platform.win32.Kernel32.INSTANCE.WaitForSingleObject(handle,3000);
            try {browser.call("Browser.getVersion",Map.of());throw new AssertionError("Real exit ignored");}
            catch(AuthFailure safe) {
                if(!"NET_WEB/BROWSER_EXIT=7".equals(safe.getMessage()))throw new AssertionError("Actual browser exit code lost");checks++;
            }
        }
        if(!roots().equals(before))throw new AssertionError("Exited browser profile leak");checks++;
        var permitted=new java.util.concurrent.atomic.AtomicBoolean(true);
        try(var request=new RequestScope(permitted::get,30000);var browser=WindowsEdgeProcess.open(request,true,null)) {
            permitted.set(false);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(browser.alive() && System.nanoTime()<end) Thread.sleep(20);
            if(browser.alive()) throw new AssertionError("Cancellation did not stop job");checks++;
        }
        if(!roots().equals(before)) throw new AssertionError("Cancelled native profile leak");checks++;
        // Verify a never-completing CDP evaluation is bounded and terminates the owned job.
        try(var request=new RequestScope(()->true,30000);var browser=WindowsEdgeProcess.open(request,true,null)) {
            var page=WindowsEdgeWebLogin.pages(browser).getFirst();
            String session=BoundedJson.string(browser.call("Target.attachToTarget",Map.of("targetId",page.get("targetId"),"flatten",true)).get("sessionId"));
            try {browser.call("Runtime.evaluate",Map.of("expression","new Promise(()=>{})","awaitPromise",true),session,300);throw new AssertionError("CDP timeout ignored");}
            catch(AuthFailure safe) {if(!safe.getMessage().equals("NET_WEB/BROWSER_TIMEOUT")) throw new AssertionError("Timeout classification");checks++;}
        }
        if(!roots().equals(before)) throw new AssertionError("Timed-out native profile leak");checks++;
        String previousTemp=System.getProperty("java.io.tmpdir");
        Path unusual=Files.createTempDirectory(Path.of(previousTemp),"native-space-\u6d4b\u8bd5-quote'-");
        try {
            System.setProperty("java.io.tmpdir",unusual.toString());
            for(int i=0;i<3;i++) {
                try(var request=new RequestScope(()->true,30000);var browser=WindowsEdgeProcess.open(request,true,null)) {
                    if(!browser.inOwnedJob() || !browser.alive()) throw new AssertionError("Repeated Unicode profile launch failed");checks++;
                }
            }
            try(var entries=Files.list(unusual)) {if(entries.findAny().isPresent()) throw new AssertionError("Repeated launch leftover");checks++;}
        } finally {System.setProperty("java.io.tmpdir",previousTemp);Files.delete(unusual);}
        System.out.println("PASS: "+checks+" direct Win32 Edge handle-allowlist/job/CDP/synthetic QQ+NetEase/device/origin/cancel/timeout/profile checks; no PowerShell, website, account or visible window");
    }
}
