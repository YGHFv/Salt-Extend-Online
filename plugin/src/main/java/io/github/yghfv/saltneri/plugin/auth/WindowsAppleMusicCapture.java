package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.applemusic.AppleMusicHttp;
import io.github.yghfv.saltneri.plugin.net.*;
import java.io.IOException;
import java.time.Clock;
import java.util.*;

/** Only the explicitly opened isolated Apple Music page is observed. */
final class WindowsAppleMusicCapture {
    private WindowsAppleMusicCapture() {}
    static AccountCredentials capture(RequestScope request) throws IOException {
        try(var browser=WindowsEdgeProcess.open(request,false,"https://music.apple.com/");var verifier=new AppleMusicAuthClient()) {
            String observed="";String session=null,target=null;
            while(true) {
                request.check();if(!browser.alive())throw AppleMusicHttp.failure("CANCELLED");
                var page=WindowsEdgeWebLogin.pages(browser).stream().filter(p->official(p.get("url"))).findFirst().orElse(null);
                if(page!=null) {
                    String id=BoundedJson.string(page.get("targetId"));
                    if(!id.equals(target)) {
                        target=id;session=BoundedJson.string(browser.call("Target.attachToTarget",Map.of("targetId",id,"flatten",true)).get("sessionId"));
                    }
                    String token="";
                    for(Object row:BoundedJson.array(browser.call("Network.getCookies",Map.of("urls",List.of("https://music.apple.com/")),session).get("cookies"))) {
                        var cookie=BoundedJson.object(row);
                        if("media-user-token".equals(cookie.get("name")) && "music.apple.com".equals(
                                BoundedJson.string(cookie.get("domain")).replaceFirst("^\\.","")))token=BoundedJson.string(cookie.get("value"));
                    }
                    if(token.isEmpty())try {
                        String expression="(()=>{try{let m=window.MusicKit?.getInstance();return m?.musicUserToken||localStorage.getItem('music.ampwebplay.media-user-token')||localStorage.getItem('media-user-token')||''}catch(e){return ''}})()";
                        var result=browser.call("Runtime.evaluate",Map.of("expression",expression,"returnByValue",true),session);
                        var value=BoundedJson.object(result.get("result")).get("value");
                        if(value instanceof String text)token=text;
                    } catch(NeteaseDeviceCollector.ContextUnavailable navigation) {session=null;target=null;}
                    if(!token.isEmpty() && !token.equals(observed)) {
                        observed=token;var unverified=AppleMusicAuthClient.fromToken(token,Clock.systemUTC());
                        try {return verifier.verify(unverified,request);} finally {unverified.retire();}
                    }
                }
                WindowsEdgeProcess.pause(1000);
            }
        }
    }
    private static boolean official(Object value) {
        try {var uri=java.net.URI.create(String.valueOf(value));return "https".equals(uri.getScheme())
                && "music.apple.com".equals(uri.getHost()) && uri.getPort()==-1 && uri.getUserInfo()==null;}
        catch(IllegalArgumentException invalid) {return false;}
    }
}
