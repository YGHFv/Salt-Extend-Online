package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.applemusic.*;
import io.github.yghfv.saltneri.plugin.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Private browser/CDM and Web Audio port. It never opens the Apple Music Windows client. */
public final class WindowsMusicKitDecoder implements AppleMusicPcmSource.Decoder {
    private final RequestScope lifetime;
    private WindowsEdgeProcess browser;
    private String session;
    private volatile boolean closed;
    private boolean running;
    public WindowsMusicKitDecoder(AccountCredentials account,String developerToken,String target,long duration) throws IOException {
        lifetime=RequestScope.playback(()->!closed && account.isActive());
        try {
            browser=WindowsEdgeProcess.open(lifetime,true,"https://music.apple.com/");
            var page=WindowsEdgeWebLogin.pages(browser).stream().filter(p->"about:blank".equals(p.get("url"))).findFirst()
                .orElseThrow(()->AppleMusicHttp.failure("BROWSER_PAGE"));
            session=BoundedJson.string(browser.call("Target.attachToTarget",Map.of("targetId",page.get("targetId"),"flatten",true)).get("sessionId"));
            // An official HTTPS origin is needed for MusicKit authorization, CORS and EME.
            browser.call("Page.enable",Map.of(),session);
            browser.call("Page.setBypassCSP",Map.of("enabled",true),session);
            browser.call("Network.enable",Map.of(),session);
            browser.call("Network.setBlockedURLs",Map.of("urls",List.of("https://music.apple.com/assets/*")),session);
            browser.call("Page.navigate",Map.of("url","https://music.apple.com/"+target.substring(0,2)+"/browse"),session);
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while(true) {
                lifetime.check();
                try {
                    Object ready=evaluate("({ready:location.origin==='https://music.apple.com'&&document.readyState!=='loading'})",false).get("ready");
                    if(Boolean.TRUE.equals(ready))break;
                } catch(NeteaseDeviceCollector.ContextUnavailable navigation) { }
                if(System.nanoTime()>=end)throw AppleMusicHttp.failure("BROWSER_PAGE_TIMEOUT");
                WindowsEdgeProcess.pause(40);
            }
            // Stop the site UI; only our MusicKit module remains in this private browser.
            browser.call("Page.stopLoading",Map.of(),session);
            String script;
            try(var input=WindowsMusicKitDecoder.class.getResourceAsStream("/musickit/pcm.js")) {
                if(input==null)throw AppleMusicHttp.failure("PCM_RESOURCE");
                script=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            }
            browser.call("Runtime.evaluate",Map.of("expression","document.head.replaceChildren();document.body.replaceChildren();"+script),session);
            String token=account.cookieSnapshot().stream().filter(e->e.name().equals("media-user-token")).map(ScopedCookies.Entry::value).findFirst()
                .orElseThrow(()->AppleMusicHttp.failure("LOGIN_REQUIRED"));
            String config=new String(BoundedJson.encode(Map.of("developerToken",developerToken,"userToken",token,
                "song",target.substring(3),"storefront",target.substring(0,2),"duration",duration)),StandardCharsets.UTF_8);
            var result=evaluate("saltApple.init("+config+")",true);
            requireSuccess(result);
            if(!Boolean.TRUE.equals(result.get("ready")))throw AppleMusicHttp.failure("PCM_NOT_READY");
        } catch(IOException | RuntimeException | LinkageError failure) {close();throw failure;}
    }
    private synchronized Map<String,Object> evaluate(String expression,boolean promise) throws IOException {
        lifetime.check();
        var response=browser.call("Runtime.evaluate",Map.of("expression",expression,"returnByValue",true,
            "awaitPromise",promise,"userGesture",true),session,25000);
        if(response.containsKey("exceptionDetails"))throw AppleMusicHttp.failure("PCM_SCRIPT");
        return BoundedJson.object(BoundedJson.object(response.get("result")).get("value"));
    }
    private static void requireSuccess(Map<String,Object> value) throws IOException {
        if(value.get("error") instanceof String error && !error.isBlank())
            throw AppleMusicHttp.failure(error.matches("[A-Z_]{1,48}")?error:"MUSICKIT");
    }
    @Override public synchronized void seek(long frame) throws IOException {
        var result=evaluate("saltApple.seek("+frame+")",true);requireSuccess(result);running=true;
    }
    @Override public synchronized byte[] pull(int maximumBytes) throws IOException {
        if(maximumBytes<4 || maximumBytes>65536 || maximumBytes%4!=0)throw AppleMusicHttp.failure("PCM_BOUNDS");
        maximumBytes=Math.min(maximumBytes,32768); // Base64 stays below the strict CDP JSON string bound.
        if(!running) {requireSuccess(evaluate("saltApple.resume()",true));running=true;}
        var result=evaluate("saltApple.pull("+maximumBytes+")",false);requireSuccess(result);
        try {return Base64.getDecoder().decode(BoundedJson.string(result.get("pcm")));}
        catch(IllegalArgumentException invalid) {throw AppleMusicHttp.failure("PCM_ENCODING");}
    }
    @Override public synchronized void pause() throws IOException {
        if(closed)return;
        requireSuccess(evaluate("saltApple.pause()",true));running=false;
    }
    @Override public void close() {
        closed=true;lifetime.close();
        var current=browser;if(current!=null)current.close();
    }
}
