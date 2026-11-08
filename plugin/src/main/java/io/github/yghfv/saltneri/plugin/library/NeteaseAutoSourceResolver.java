package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.PlaybackSettings;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.bilibili.*;
import io.github.yghfv.saltneri.plugin.netease.*;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;

/** Alternative public full audio, not a retry of denied URLs or a paid-media bypass. */
final class NeteaseAutoSourceResolver {
    interface Backend {
        NeteaseClient.SongInfo songInfo(RequestScope request) throws IOException;
        List<Catalog.Item> search(String query,int duration,RequestScope request) throws IOException;
        List<BiliClient.VideoPage> pages(String bvid,RequestScope request) throws IOException;
        OnlinePlaybackController.Audio audio(BiliTarget target,RequestScope request) throws IOException;
    }
    static OnlinePlaybackController.Audio resolve(AccountCredentials original,OnlinePlaybackController.Target target,
            String scope,RequestScope request,PlaybackSettings settings,Function<AccountPlatform,AccountCredentials> accounts,
            NeteaseClient.TrackUnavailable unavailable) throws IOException {
        request.check();
        AccountCredentials biliAccount=accounts.apply(AccountPlatform.BILIBILI);
        // Salt's existing Bili search deliberately requires a separately verified Bili login.
        if(biliAccount==null || !biliAccount.isActive())throw unavailable;
        var catalog=new CatalogClient(biliAccount);
        var client=new BiliClient(biliAccount);
        var originalInfo=new java.util.concurrent.atomic.AtomicReference<NeteaseClient.SongInfo>();
        var audio=resolve(original,target,scope,request,accounts,biliAccount,unavailable,new Backend() {
            public NeteaseClient.SongInfo songInfo(RequestScope work) throws IOException {
                var info=new NeteaseClient(original).songInfo(NeteaseTarget.parse(target.id()),work);originalInfo.set(info);return info;
            }
            public List<Catalog.Item> search(String query,int duration,RequestScope work) throws IOException {return catalog.autoSourceSearch(query,duration,work).items();}
            public List<BiliClient.VideoPage> pages(String bvid,RequestScope work) throws IOException {return client.pages(bvid,work);}
            public OnlinePlaybackController.Audio audio(BiliTarget video,RequestScope work) throws IOException {
                return OnlinePlaybackController.Audio.from(client.resolve(video,settings.bili(),biliAccount.accountScope(),work));
            }
        });
        // Keep original artwork too; a bounded cover failure does not invalidate playable audio.
        return OnlinePlaybackController.withCover(audio,originalInfo.get().cover(),request,settings.coverMode());
    }
    static OnlinePlaybackController.Audio resolve(AccountCredentials original,OnlinePlaybackController.Target target,
            String scope,RequestScope request,Function<AccountPlatform,AccountCredentials> accounts,AccountCredentials biliAccount,
            NeteaseClient.TrackUnavailable unavailable,Backend backend) throws IOException {
        request.check();
        if(target.platform()!=AccountPlatform.NETEASE || original!=null && !scope.equals(original.accountScope()))
            throw new SecurityException("Alternative source original identity mismatch");
        if(biliAccount==null || !biliAccount.isActive())throw unavailable;
        Set<String> visited=new HashSet<>();
        try(var work=new RequestScope(()->{
            request.check();
            return accounts.apply(AccountPlatform.BILIBILI)==biliAccount && biliAccount.isActive()
                    && accounts.apply(AccountPlatform.NETEASE)==original && (original==null || original.isActive());
        },20000)) {
            var info=backend.songInfo(work);
            var song=new AutoSourceMatcher.Song(info.metadata().title(),info.metadata().artist(),info.durationMillis());
            for(String query:AutoSourceMatcher.queries(song)) {
                work.check();
                int duration=AutoSourceMatcher.durationFilter(song.durationMillis());
                var candidates=backend.search(query,duration,work);
                if(candidates.isEmpty() && duration!=0)candidates=backend.search(query,0,work);
                var ranked=candidates.stream().sorted(Comparator.comparingInt((Catalog.Item item)->
                        AutoSourceMatcher.score(song,item.title(),item.subtitle(),item.count())).reversed())
                        .limit(AutoSourceMatcher.SEARCH_LIMIT).toList();
                for(var candidate:ranked) {
                    if(!visited.add(candidate.target()))continue;
                    work.check();
                    try {
                        int candidateScore=AutoSourceMatcher.score(song,candidate.title(),candidate.subtitle(),candidate.count());
                        var page=backend.pages(candidate.target(),work).stream()
                                .filter(p->AutoSourceMatcher.acceptable(song,p.title(),p.author(),p.durationMillis(),
                                        candidateScore+AutoSourceMatcher.score(song,p.title(),p.author(),p.durationMillis())))
                                .max(Comparator.comparingInt(p->AutoSourceMatcher.score(song,p.title(),p.author(),p.durationMillis())));
                        if(page.isEmpty())continue;
                        var resolved=backend.audio(page.get().target(),work);
                        work.check();request.check();
                        if(resolved.key().platform()!=TrackKey.Platform.BILIBILI || !resolved.transportMatches()
                                || !resolved.lease().belongsTo(biliAccount.accountScope()))
                            throw new SecurityException("Alternative source account mismatch");
                        var lease=resolved.lease().bindTo(scope,()->biliAccount.isActive()
                                && accounts.apply(AccountPlatform.BILIBILI)==biliAccount
                                && accounts.apply(AccountPlatform.NETEASE)==original && (original==null || original.isActive()));
                        // Queue identity/lyrics/title stay NetEase; transport/cache policy stay Bilibili.
                        return new OnlinePlaybackController.Audio(TrackKey.netease(target.id()),info.metadata(),lease,
                                resolved.expiresAt(),target,resolved.key());
                    } catch(IOException candidateFailure) {work.check();}
                }
            }
        } catch(java.util.concurrent.CancellationException stopped) {request.check();throw stopped;}
        throw unavailable;
    }
}
