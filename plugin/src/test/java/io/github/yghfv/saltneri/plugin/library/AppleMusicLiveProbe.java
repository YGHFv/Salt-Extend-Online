package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.applemusic.AppleMusicHttp;
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.time.Clock;

/** Explicit public read only: no account token, browser or official-client playback. */
public final class AppleMusicLiveProbe {
    public static void main(String[] args) throws Exception {
        try(var request=new RequestScope(()->true,60000)) {
            var http=new AppleMusicHttp();
            var page=new AppleMusicCatalog(null,Clock.systemUTC(),http).load(
                    new Catalog.Query(AccountPlatform.APPLEMUSIC,Catalog.View.DISCOVER,0,1),request);
            if(page.items().isEmpty())throw new AssertionError("No public Apple chart songs");
            System.out.println("PASS: Apple public bearer/chart read, "+page.items().size()+" songs; no account or playback");
        }
    }
}
