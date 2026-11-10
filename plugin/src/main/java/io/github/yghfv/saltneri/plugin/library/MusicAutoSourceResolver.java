package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.PlaybackSettings;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.bilibili.*;
import io.github.yghfv.saltneri.plugin.netease.NeteaseClient;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.host.Salt1193PlaybackSession.Metadata;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;

/** Kugou and Apple use the same bounded Bilibili matching and two-account ownership as NetEase/QQ. */
final class MusicAutoSourceResolver {
    static boolean eligible(AccountPlatform platform,IOException failure) {
        String stage=failure.getMessage();
        if(platform==AccountPlatform.KUGOU)return Set.of("KUGOU/TRACK_UNAVAILABLE","KUGOU/FULL_TRACK_METADATA",
                "KUGOU/HTTP_400").contains(stage==null?"":stage);
        if(platform==AccountPlatform.APPLEMUSIC)return Set.of("APPLE/SUBSCRIPTION_REQUIRED","APPLE/PCM_SILENT",
                "APPLE/SONG_UNAVAILABLE").contains(stage==null?"":stage);
        return false;
    }
    static OnlinePlaybackController.Audio resolve(AccountCredentials original,OnlinePlaybackController.Target target,String scope,
            RequestScope request,PlaybackSettings settings,Function<AccountPlatform,AccountCredentials> accounts,IOException unavailable) throws IOException {
        var bili=accounts.apply(AccountPlatform.BILIBILI);
        if(bili==null || !bili.isActive())throw unavailable;
        var catalog=new CatalogClient(bili);var client=new BiliClient(bili);
        var metadata=new java.util.concurrent.atomic.AtomicReference<NeteaseClient.SongInfo>();
        var audio=NeteaseAutoSourceResolver.resolve(original,target,scope,request,accounts,bili,unavailable,new NeteaseAutoSourceResolver.Backend() {
            public NeteaseClient.SongInfo songInfo(RequestScope work) throws IOException {
                NeteaseClient.SongInfo info;
                if(target.platform()==AccountPlatform.APPLEMUSIC)info=new io.github.yghfv.saltneri.plugin.applemusic.AppleMusicClient(original).songInfo(target.id(),work);
                else {
                    var song=new io.github.yghfv.saltneri.plugin.kugou.KugouClient(original).songInfo(target.id(),work);
                    info=new NeteaseClient.SongInfo(new Metadata(song.title(),song.artist(),song.album(),song.artist()),song.cover(),song.duration());
                }
                metadata.set(info);return info;
            }
            public List<Catalog.Item> search(String query,int duration,RequestScope work) throws IOException {return catalog.autoSourceSearch(query,duration,work).items();}
            public List<BiliClient.VideoPage> pages(String bvid,RequestScope work) throws IOException {return client.pages(bvid,work);}
            public OnlinePlaybackController.Audio audio(BiliTarget video,RequestScope work) throws IOException {
                return OnlinePlaybackController.Audio.from(client.resolve(video,settings.bili(),bili.accountScope(),work));
            }
        });
        return OnlinePlaybackController.withCover(audio,metadata.get().cover(),request,settings.coverMode());
    }
}
