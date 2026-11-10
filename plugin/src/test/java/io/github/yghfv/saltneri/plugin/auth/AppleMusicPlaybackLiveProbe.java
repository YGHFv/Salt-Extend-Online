package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.applemusic.*;
import io.github.yghfv.saltneri.plugin.net.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

/** Explicit interactive subscription acceptance, using only the plugin's private Apple login. */
public final class AppleMusicPlaybackLiveProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=2 || !args[0].equals("--explicit-apple-login-and-playback"))
            throw new IllegalArgumentException("Explicit Apple login and plugin data directory required");
        var store=new EncryptedAccountStore(Path.of(args[1]));
        AccountCredentials account=null;
        try(var request=new RequestScope(()->true,300000)) {
            System.out.println("Waiting for Apple Music login in the isolated official page. No credentials are printed.");
            account=WindowsAppleMusicCapture.capture(request);
            store.save(account,account::isActive);
            System.out.println("PASS: Apple account verified and stored with CurrentUser DPAPI.");
            var http=new AppleMusicHttp();
            String sf=account.cookieSnapshot().stream().filter(e->e.name().equals("storefront")).map(ScopedCookies.Entry::value).findFirst().orElse("us");
            var data=BoundedJson.object(http.get("/v1/catalog/"+sf+"/charts?types=songs&limit=1",null,request).get("results"));
            var chart=BoundedJson.object(BoundedJson.array(data.get("songs")).getFirst());
            String id=BoundedJson.string(BoundedJson.object(BoundedJson.array(chart.get("data")).getFirst()).get("id"));
            var audio=new AppleMusicClient(account).resolve(sf+":"+id,account.accountScope(),request);
            try {
                if(!audio.transportMatches())throw new AssertionError("Apple source identity mismatch");
                System.out.println("PASS: subscribed MusicKit track identity, duration and non-silent CDM PCM verified; no official client launched.");
            } finally {audio.lease().release();}
        } finally {if(account!=null)account.retire();}
    }
}
