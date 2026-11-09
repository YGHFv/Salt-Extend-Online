package io.github.yghfv.saltneri.plugin.host;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.library.*;
import io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.*;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.transport.*;
import io.github.yghfv.saltneri.plugin.ui.CoverMode;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Original persisted queue objects, late/failed images and real Salt tag reads; synthetic only. */
public final class SavedPlaylistArtworkContractTest {
    private static int checks;
    private static void check(boolean ok,String message) {checks++;if(!ok)throw new AssertionError(message);}
    private static void await(BooleanSupplier condition) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
        while(!condition.getAsBoolean() && System.nanoTime()<until)Thread.sleep(20);
        check(condition.getAsBoolean(),"Fixture timed out");
    }
    private static final class Adapter implements StreamBinding {
        final Map<String,Deferred> paths=new ConcurrentHashMap<>();
        public void register(String path,URI uri) {paths.put(path,()->uri);}
        public void registerDeferred(String path,Deferred source) {paths.put(path,source);}
        public void unregister(String path) {paths.remove(path);}
        public ReversibleInterceptorLists attach(Object engine) {return null;}
        public boolean isAttached() {return true;}
        public void close() {paths.clear();}
    }
    private static final class Queue implements Salt1193PlaybackSession.QueueSink {
        final AtomicReference<PlaybackCoverRefresh.Selection> current=new AtomicReference<>();
        final List<Runnable> ui=new CopyOnWriteArrayList<>();
        final List<PlaybackCoverRefresh.Selection> refreshes=new CopyOnWriteArrayList<>();
        final PlaybackCoverRefresh refresh=new PlaybackCoverRefresh(new PlaybackCoverRefresh.Host() {
            public PlaybackCoverRefresh.Selection selection() {return current.get();}
            public void refresh() {refreshes.add(current.get());}
        },ui::add);
        public void addToPlay(Object item) {throw new AssertionError("No queue mutation");}
        public PlaybackCoverRefresh.Selection coverSelection() {return current.get();}
        public void coverChanged(String id,String path,BooleanSupplier valid) {refresh.changed(id,path,valid);}
        void select(SavedTrackStore.Entry entry) {current.set(new PlaybackCoverRefresh.Selection(entry.trackId(),entry.path()));}
        void drain() {for(Runnable task:List.copyOf(ui))if(ui.remove(task))task.run();}
    }
    private static Audio audio(SavedTrackStore.Entry entry,URI image) {
        var target=entry.target();String id=target.id();
        var key=switch(target.platform()) {
            case NETEASE->TrackKey.netease(id);
            case QQMUSIC->TrackKey.qqmusic(id);
            case BILIBILI->TrackKey.bilibili(id,123);
            case APPLEMUSIC->throw new IllegalArgumentException("External player");
        };
        var platform=MediaLease.Platform.valueOf(target.platform().name());
        URI media=URI.create(switch(target.platform()) {
            case NETEASE->"https://m7.music.126.net/fixture";
            case QQMUSIC->"https://dl.stream.qqmusic.qq.com/fixture";
            case BILIBILI->"https://test.bilivideo.com/fixture";
            case APPLEMUSIC->throw new IllegalArgumentException("External player");
        });
        Instant expiry=Instant.now().plusSeconds(900);
        return new Audio(key,new Salt1193PlaybackSession.Metadata("Fixture","Artist","",""),
                new MediaLease(platform,media,Map.of(),"fixture",expiry,100),expiry,target,key,image,CoverMode.FIT);
    }
    private static byte[] read(Object original,Salt1193Probe.Result profile) throws Exception {
        String path=profile.mediaFactory().read(original)[5];
        Class<?> tags=Class.forName("com.xuncorp.voxzen.tag.TagParser");
        var read=tags.getDeclaredMethod("readArtworkBinaryData",File.class);check(read.trySetAccessible(),"Tag reader inaccessible");
        return (byte[])read.invoke(tags.getField("INSTANCE").get(null),new File(path));
    }
    private static void restored(SavedTrackStore.Entry entry,Salt1193Probe.Result profile,byte[] png) throws Exception {
        Object original=profile.mediaFactory().create(entry.trackId(),entry.title(),entry.artist(),"","",entry.path());
        check(Arrays.equals(read(original,profile),png),"Restored original queue image missing");
    }
    public static void main(String[] args) throws Exception {
        var profile=Salt1193Probe.inspect(SavedPlaylistArtworkContractTest.class.getClassLoader(),"1.19.3","Steam");
        check(profile.matched(),"Missing host");
        byte[] png=PlaybackCoverContractTest.png();
        for(var platform:List.of(AccountPlatform.BILIBILI,AccountPlatform.NETEASE,AccountPlatform.QQMUSIC)) {
            Path root=Files.createTempDirectory("salt-cover-switch-fixture-").toRealPath();
            String id=platform==AccountPlatform.QQMUSIC?"00123456789abc":platform==AccountPlatform.NETEASE?"120":"BV17x411w7KC";
            String other=platform==AccountPlatform.QQMUSIC?"00987654321abc":platform==AccountPlatform.NETEASE?"121":"BV1Q541167Qg";
            URI source=URI.create("https://i0.hdslb.com/fixture-first.png"),nextSource=URI.create("https://i1.hdslb.com/fixture-next.png");
            var resolves=new AtomicInteger();var attempts=new AtomicInteger();var allowLate=new AtomicBoolean();var active=new AtomicBoolean(true);
            var queue=new Queue();var adapter=new Adapter();String originalPath;
            try(var store=new SavedTrackStore(root);var server=new LoopbackRangeServer(active::get);
                var session=new SavedTrackSession(adapter,server,active::get,new SavedTrackSession.Resolver() {
                    public Object revision(AccountPlatform p) {return "fixture";}
                    public Audio resolve(SavedTrackStore.Entry entry,RequestScope request) {
                        resolves.incrementAndGet();return audio(entry,entry.target().id().equals(id)?source:nextSource);
                    }
                    public byte[] cover(URI image,CoverMode mode,RequestScope request) throws Exception {
                        if(image.equals(source)) {
                            if(attempts.incrementAndGet()==1)throw new IOException("Synthetic initial image failure");
                            while(!allowLate.get()) {request.check();Thread.sleep(10);}
                        }
                        request.check();return png;
                    }
                })) {
                var entry=store.save(new Target(platform,id),new Catalog.Item(Catalog.Kind.TRACK,id,"Fixture","Artist",1000,true));
                var next=store.save(new Target(platform,other),new Catalog.Item(Catalog.Kind.TRACK,other,"Next","Artist",1000,true));
                session.register(entry);session.register(next);session.prefetch(queue);queue.select(entry);
                Object original=profile.mediaFactory().create(entry.trackId(),entry.title(),entry.artist(),"","",entry.path());
                originalPath=profile.mediaFactory().read(original)[5];
                long started=System.nanoTime();URI first=adapter.paths.get(originalPath).resolve();
                check(System.nanoTime()-started<TimeUnit.SECONDS.toNanos(1),"Artwork blocked audio open");
                check(!entry.cover().hasPicture(),"Unexpected initial artwork");
                await(()->attempts.get()==1);
                check(adapter.paths.get(originalPath).resolve().equals(first) && resolves.get()==1,"Image retry re-resolved audio");
                await(()->attempts.get()>=2);
                queue.select(next);session.warm(next,()->true);await(next.cover()::hasPicture);queue.drain();queue.refreshes.clear();
                allowLate.set(true);await(entry.cover()::hasPicture);Thread.sleep(300);queue.drain();
                check(queue.refreshes.stream().noneMatch(s->s.id().equals(entry.trackId())),"Late previous image refreshed wrong track");
                check(Arrays.equals(read(original,profile),png),"Original queued object did not see late picture");
                Class<?> tags=Class.forName("com.xuncorp.voxzen.tag.TagParser");
                Object image=tags.getMethod("readImage-0E7RQCE",String.class,int.class,int.class)
                        .invoke(tags.getField("INSTANCE").get(null),originalPath,96,96);
                kotlin.ResultKt.throwOnFailure(image);
                var bitmap=androidx.compose.ui.graphics.SkiaImageAsset_skikoKt.asSkiaBitmap((androidx.compose.ui.graphics.ImageBitmap)image);
                try {check(bitmap.getColor(12,12)==0xffdd5500,"Original persistent image decode failed");}
                finally {bitmap.close();}
                int before=resolves.get();queue.select(entry);
                await(()->!queue.ui.isEmpty());queue.drain();
                check(queue.refreshes.contains(new PlaybackCoverRefresh.Selection(entry.trackId(),entry.path())),"Previous-track cover not refreshed");
                check(adapter.paths.get(originalPath).resolve().equals(first) && resolves.get()==before,"Cached revisit re-resolved audio");
                queue.current.set(new PlaybackCoverRefresh.Selection(entry.trackId(),"foreign"));
                queue.coverChanged(entry.trackId(),entry.path(),()->true);queue.refreshes.clear();queue.drain();
                check(queue.refreshes.isEmpty(),"ID-only artwork match accepted foreign path");
                queue.select(entry);queue.coverChanged(entry.trackId(),entry.path(),active::get);
                active.set(false);session.close();queue.drain();
                check(queue.refreshes.isEmpty(),"Closed session refreshed stale artwork");
            }
            try(var store=new SavedTrackStore(root)) {
                var entry=store.entries().stream().filter(e->e.target().id().equals(id)).findFirst().orElseThrow();
                check(entry.path().equals(originalPath) && entry.cover().hasPicture(),"Restart lost durable cover");
                restored(entry,profile,png);
            }
        }
        cancellation(profile,png);
        retryBounds();
        System.out.println("PASS: "+checks+" persistent original queue artwork, initial failure/retry, audio isolation, fast switch, cached previous, restart, identity and cancellation checks; synthetic only");
    }
    private static void cancellation(Salt1193Probe.Result profile,byte[] png) throws Exception {
        for(boolean revoke:new boolean[]{false,true}) {
            var started=new CountDownLatch(1);var ended=new CountDownLatch(1);var allow=new AtomicBoolean();
            Path root=Files.createTempDirectory("salt-cover-cancel-fixture-").toRealPath();
            try(var store=new SavedTrackStore(root);var server=new LoopbackRangeServer(()->true);
                var session=new SavedTrackSession(new Adapter(),server,()->true,new SavedTrackSession.Resolver() {
                    public Object revision(AccountPlatform platform) {return 1;}
                    public Audio resolve(SavedTrackStore.Entry e,RequestScope request) {return audio(e,URI.create("https://i0.hdslb.com/cancel.png"));}
                    public byte[] cover(URI source,CoverMode mode,RequestScope request) throws Exception {
                        started.countDown();
                        try {while(!allow.get()) {request.check();Thread.sleep(10);}request.check();return png;}
                        finally {ended.countDown();}
                    }
                })) {
                var entry=store.save(new Target(AccountPlatform.NETEASE,"500"),new Catalog.Item(Catalog.Kind.TRACK,"500","Fixture","Artist",1,true));
                session.warm(entry,()->true);check(started.await(3,TimeUnit.SECONDS),"Image fixture not started");
                if(revoke)session.revoke(AccountPlatform.NETEASE);else session.close();
                check(ended.await(3,TimeUnit.SECONDS),"Cancelled image did not stop");
                allow.set(true);check(!entry.cover().hasPicture(),"Cancelled image persisted stale bytes");
            }
        }
    }
    private static void retryBounds() throws Exception {
        var attempts=new AtomicInteger();var audioCalls=new AtomicInteger();var queue=new Queue();
        Path root=Files.createTempDirectory("salt-cover-retries-fixture-").toRealPath();
        try(var store=new SavedTrackStore(root);var server=new LoopbackRangeServer(()->true);
            var session=new SavedTrackSession(new Adapter(),server,()->true,new SavedTrackSession.Resolver() {
                public Object revision(AccountPlatform platform) {return 1;}
                public Audio resolve(SavedTrackStore.Entry entry,RequestScope request) {
                    audioCalls.incrementAndGet();return audio(entry,URI.create("https://i0.hdslb.com/unavailable.png"));
                }
                public byte[] cover(URI source,CoverMode mode,RequestScope request) throws Exception {
                    attempts.incrementAndGet();throw new IOException("Synthetic unavailable image");
                }
            })) {
            var entry=store.save(new Target(AccountPlatform.NETEASE,"600"),new Catalog.Item(Catalog.Kind.TRACK,"600","Fixture","Artist",1,true));
            var other=store.save(new Target(AccountPlatform.NETEASE,"601"),new Catalog.Item(Catalog.Kind.TRACK,"601","Other","Artist",1,true));
            session.register(other);session.prefetch(queue);queue.select(entry);session.warm(entry,()->true);
            await(()->attempts.get()==3);Thread.sleep(700);
            check(attempts.get()==3 && audioCalls.get()==1,"Unbounded image retry or audio retry");
            queue.select(other);Thread.sleep(350);queue.select(entry);
            await(()->attempts.get()>=4);
            check(audioCalls.get()==1 && !entry.cover().hasPicture(),"Revisit failed to retry just artwork");
        }
    }
}
