package io.github.yghfv.saltneri.plugin.applemusic;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.library.*;
import io.github.yghfv.saltneri.plugin.net.*;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import io.github.yghfv.saltneri.plugin.host.Salt1193PlaybackSession.Metadata;
import java.io.IOException;
import java.time.Instant;

public final class AppleMusicClient {
    private static final AppleMusicHttp HTTP=new AppleMusicHttp();
    private final AccountCredentials account;
    public AppleMusicClient(AccountCredentials account) {this.account=account;}
    public OnlinePlaybackController.Audio resolve(String target,String scope,RequestScope request) throws IOException {
        TrackKey key=TrackKey.appleMusic(target);
        if(account==null || account.platform()!=AccountPlatform.APPLEMUSIC || !account.isActive())throw AppleMusicHttp.failure("LOGIN_REQUIRED");
        if(!scope.equals(account.accountScope()))throw AppleMusicHttp.failure("ACCOUNT_SCOPE");
        var rows=BoundedJson.array(HTTP.get("/v1/catalog/"+target.substring(0,2)+"/songs/"+target.substring(3),account,request).get("data"));
        if(rows.size()!=1)throw AppleMusicHttp.failure("SONG_UNAVAILABLE");
        var song=BoundedJson.object(rows.getFirst());
        if(!target.substring(3).equals(song.get("id")))throw AppleMusicHttp.failure("TRACK_IDENTITY");
        var attributes=BoundedJson.object(song.get("attributes"));
        long duration=BoundedJson.integer(attributes.get("durationInMillis"));
        String title=text(attributes.get("name")),artist=text(attributes.get("artistName")),album=text(attributes.get("albumName"));
        String bearer=HTTP.publicBearer(request);request.check();
        var source=new AppleMusicPcmSource(duration,account::isActive,()->new WindowsMusicKitDecoder(account,bearer,target,duration));
        try {
            source.prepare();request.check();
            // Verify actual CDM output before publication; a protected silence graph is not playback.
            byte[] pcm=source.read(new io.github.yghfv.saltneri.plugin.transport.ByteRange(0,Math.min(source.length(),524288)-1),()->{request.check();return true;});
            boolean signal=false;for(int i=44;i<pcm.length;i++)if(pcm[i]!=0){signal=true;break;}
            if(!signal)throw AppleMusicHttp.failure("PCM_SILENT");
            request.check();
        }
        catch(IOException | RuntimeException failure) {source.close();throw failure;}
        Instant expiry=Instant.now().plusSeconds(1790);
        var lease=MediaLease.appleMusic(source,scope,expiry,account::isActive);
        java.net.URI cover=null;
        if(attributes.get("artwork") instanceof java.util.Map<?,?>) {
            String url=text(BoundedJson.object(attributes.get("artwork")).get("url"));
            cover=CoverSource.parse(AccountPlatform.APPLEMUSIC,url.replace("{w}","600").replace("{h}","600").replace("{f}","jpg"));
        }
        return new OnlinePlaybackController.Audio(key,new Metadata(title,artist,album,artist,null),lease,expiry,
            new OnlinePlaybackController.Target(AccountPlatform.APPLEMUSIC,target),key,cover,io.github.yghfv.saltneri.plugin.ui.CoverMode.FIT);
    }
    private static String text(Object value) throws IOException {
        if(!(value instanceof String text) || text.length()>4096 || text.chars().anyMatch(Character::isISOControl))throw AppleMusicHttp.failure("METADATA");
        return text;
    }
}
