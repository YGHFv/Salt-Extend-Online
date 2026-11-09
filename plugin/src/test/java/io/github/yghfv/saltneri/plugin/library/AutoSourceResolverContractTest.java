package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.bilibili.*;
import io.github.yghfv.saltneri.plugin.netease.*;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.host.Salt1193PlaybackSession.Metadata;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

public final class AutoSourceResolverContractTest {
    private static int checks;
    private static void check(boolean ok) {checks++;if(!ok)throw new AssertionError("Auto source resolver "+checks);}
    private static AccountCredentials account(AccountPlatform platform) throws IOException {
        var jar=new ScopedCookies(platform,java.time.Clock.systemUTC());
        jar.accept(platform.accountUri(),List.of(platform.loginCookie()+"=fixture; Path=/; Secure"));
        return new AccountCredentials(platform,"42",jar.snapshot(),java.time.Clock.systemUTC());
    }
    private static final class Backend implements NeteaseAutoSourceResolver.Backend {
        int searches,pages,audios;boolean emptyFiltered,failFirst,wrong;Runnable beforeAudio=()->{};
        final AccountCredentials bili;
        Backend(AccountCredentials bili) {this.bili=bili;}
        public NeteaseClient.SongInfo songInfo(RequestScope request) {
            request.check();return new NeteaseClient.SongInfo(new Metadata("Original song","Original artist","Original album","Original artist"),null,240000);
        }
        public List<Catalog.Item> search(String query,int duration,RequestScope request) {
            request.check();searches++;
            if(emptyFiltered && duration!=0)return List.of();
            return List.of(new Catalog.Item(Catalog.Kind.TRACK,"BV17x411w7KC",wrong?"Wrong track":"Original song Original artist","Uploader",240000,true),
                    new Catalog.Item(Catalog.Kind.TRACK,"BV1xx411c7mD","Original song","Uploader",245000,true));
        }
        public List<BiliClient.VideoPage> pages(String bvid,RequestScope request) {
            request.check();pages++;
            return List.of(new BiliClient.VideoPage(new BiliTarget(bvid,1),wrong?"Wrong":"Original song cover","Uploader",240000),
                    new BiliClient.VideoPage(new BiliTarget(bvid,2),wrong?"Wrong":"Original song Original artist","Uploader",240000));
        }
        public OnlinePlaybackController.Audio audio(BiliTarget target,RequestScope request) throws IOException {
            beforeAudio.run();request.check();audios++;check(target.page()==2);
            if(failFirst && audios==1)throw new IOException("Fixture unavailable");
            var expiry=Instant.now().plusSeconds(300);
            return new OnlinePlaybackController.Audio(TrackKey.bilibili(target.id(),123),
                    new Metadata("Bili title","Uploader","Bilibili","Uploader"),
                    new MediaLease(MediaLease.Platform.BILIBILI,URI.create("https://fixture.bilivideo.com/audio"),Map.of(),
                            bili.accountScope(),expiry,4096),expiry);
        }
    }
    public static void main(String[] args) throws Exception {
        var bili=account(AccountPlatform.BILIBILI);var net=account(AccountPlatform.NETEASE);
        var currentBili=new AtomicReference<>(bili);var currentNet=new AtomicReference<>(net);
        Function<AccountPlatform,AccountCredentials> accounts=p->p==AccountPlatform.BILIBILI?currentBili.get():currentNet.get();
        var target=new OnlinePlaybackController.Target(AccountPlatform.NETEASE,"123");
        var unavailable=new NeteaseClient.TrackUnavailable(404);
        try(var request=new RequestScope(()->true)) {
            var backend=new Backend(bili);backend.emptyFiltered=true;backend.failFirst=true;
            var audio=NeteaseAutoSourceResolver.resolve(net,target,net.accountScope(),request,accounts,bili,unavailable,backend);
            check(backend.searches==2 && backend.audios==2 && backend.pages==2);
            check(audio.key().equals(TrackKey.netease("123")) && audio.target().equals(target));
            check(audio.sourceKey().platform()==TrackKey.Platform.BILIBILI && audio.transportMatches());
            check(audio.lease().platform()==MediaLease.Platform.BILIBILI && audio.lease().belongsTo(net.accountScope()));
            check(audio.metadata().title().equals("Original song") && audio.metadata().album().equals("Original album"));
            currentBili.set(null);check(!audio.lease().isActive() && !audio.transportMatches());currentBili.set(bili);
            currentNet.set(null);check(!audio.lease().isActive());currentNet.set(net);
            var bad=new OnlinePlaybackController.Audio(audio.key(),audio.metadata(),audio.lease(),audio.expiresAt(),null,audio.sourceKey());
            check(!bad.transportMatches());
            backend=new Backend(bili);backend.wrong=true;
            try {NeteaseAutoSourceResolver.resolve(net,target,net.accountScope(),request,accounts,bili,unavailable,backend);throw new AssertionError();}
            catch(NeteaseClient.TrackUnavailable expected) {check(expected==unavailable);}
            check(backend.audios==0 && backend.pages==2); // Deduplicated across all three queries.
            backend=new Backend(bili);
            try {NeteaseAutoSourceResolver.resolve(net,target,net.accountScope(),request,accounts,null,unavailable,backend);throw new AssertionError();}
            catch(NeteaseClient.TrackUnavailable expected) {check(backend.searches==0);}
            backend=new Backend(bili);backend.beforeAudio=()->currentBili.set(null);
            try {NeteaseAutoSourceResolver.resolve(net,target,net.accountScope(),request,accounts,bili,unavailable,backend);throw new AssertionError();}
            catch(java.util.concurrent.CancellationException expected) {checks++;}
            currentBili.set(bili);
            var qq=account(AccountPlatform.QQMUSIC);var currentQq=new AtomicReference<>(qq);
            Function<AccountPlatform,AccountCredentials> qqAccounts=p->p==AccountPlatform.BILIBILI?currentBili.get():p==AccountPlatform.QQMUSIC?currentQq.get():null;
            var qqTarget=new OnlinePlaybackController.Target(AccountPlatform.QQMUSIC,"00123456789abc");
            var qqUnavailable=new io.github.yghfv.saltneri.plugin.qqmusic.QqMusicClient.TrackUnavailable(104003);
            try {
                var qqAudio=NeteaseAutoSourceResolver.resolve(qq,qqTarget,qq.accountScope(),request,qqAccounts,bili,qqUnavailable,new Backend(bili));
                check(qqAudio.key().equals(TrackKey.qqmusic(qqTarget.id())) && qqAudio.transportMatches());
                check(qqAudio.lease().belongsTo(qq.accountScope()) && qqAudio.metadata().title().equals("Original song"));
                currentQq.set(null);check(!qqAudio.lease().isActive() && !qqAudio.transportMatches());
            } finally {qq.retire();}
        } finally {bili.retire();net.retire();}
        System.out.println("PASS: "+checks+" bounded fallback/page matching/retry/logical identity/two-account fences; no live traffic");
    }
}
