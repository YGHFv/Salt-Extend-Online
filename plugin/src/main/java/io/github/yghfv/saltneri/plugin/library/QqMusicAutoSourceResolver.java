package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.PlaybackSettings;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.bilibili.*;
import io.github.yghfv.saltneri.plugin.netease.NeteaseClient;
import io.github.yghfv.saltneri.plugin.qqmusic.*;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;

/** Uses the same NeriPlayer matching engine as NetEase, keeping QQ identity, artwork and lyrics. */
final class QqMusicAutoSourceResolver {
    static OnlinePlaybackController.Audio resolve(AccountCredentials original,OnlinePlaybackController.Target target,String scope,
            RequestScope request,PlaybackSettings settings,Function<AccountPlatform,AccountCredentials> accounts,
            QqMusicClient.TrackUnavailable unavailable) throws IOException {
        var bili=accounts.apply(AccountPlatform.BILIBILI);
        if(bili==null || !bili.isActive())throw unavailable;
        var catalog=new CatalogClient(bili);var client=new BiliClient(bili);
        var info=new java.util.concurrent.atomic.AtomicReference<QqMusicClient.Song>();
        var audio=NeteaseAutoSourceResolver.resolve(original,target,scope,request,accounts,bili,unavailable,new NeteaseAutoSourceResolver.Backend() {
            public NeteaseClient.SongInfo songInfo(RequestScope work) throws IOException {
                var song=new QqMusicClient(original).song(QqMusicTarget.parse(target.id()),work);info.set(song);
                return new NeteaseClient.SongInfo(song.metadata(),song.cover(),song.duration());
            }
            public List<Catalog.Item> search(String query,int duration,RequestScope work) throws IOException {return catalog.autoSourceSearch(query,duration,work).items();}
            public List<BiliClient.VideoPage> pages(String bvid,RequestScope work) throws IOException {return client.pages(bvid,work);}
            public OnlinePlaybackController.Audio audio(BiliTarget video,RequestScope work) throws IOException {
                return OnlinePlaybackController.Audio.from(client.resolve(video,settings.bili(),bili.accountScope(),work));
            }
        });
        return OnlinePlaybackController.withCover(audio,info.get().cover(),request,settings.coverMode());
    }
}
