package io.github.yghfv.saltneri.plugin.transport;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.host.*;
import io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.Audio;
import io.github.yghfv.saltneri.plugin.library.Catalog;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.*;

/** Real native item/interceptor/HTTP reader; controllable time, fake upstream and audio handles. */
public final class QueueReplayContractTest {
    private static int checks;
    private static void check(boolean value) { checks++; if(!value) throw new AssertionError("Queue replay check "+checks); }
    private static final class Time extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-11-07T00:00:00Z"));
        public Instant instant() { return now.get(); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        void advance(long seconds) { now.updateAndGet(t->t.plusSeconds(seconds)); }
    }
    private static final class Binding implements StreamBinding {
        final Salt1193StreamAdapter adapter;
        final Set<String> paths=ConcurrentHashMap.newKeySet();
        Binding(Salt1193StreamAdapter adapter) { this.adapter=adapter; }
        public void register(String path,URI uri) { adapter.register(path,uri); paths.add(path); }
        public void registerDeferred(String path,Deferred source) { adapter.registerDeferred(path,source); paths.add(path); }
        public void unregister(String path) { adapter.unregister(path); paths.remove(path); }
        public ReversibleInterceptorLists attach(Object engine) { return null; }
        public boolean isAttached() { return true; }
        public void close() { adapter.close(); }
    }
    private static Audio audio(TrackKey key,String scope,Clock clock,byte[] png) {
        var expiry=clock.instant().plusSeconds(60);
        var platform=MediaLease.Platform.valueOf(key.platform().name());
        var lease=new MediaLease(platform,URI.create(platform==MediaLease.Platform.NETEASE?"https://m7.music.126.net/audio":"https://test.bilivideo.com/audio"),Map.of(),scope,expiry,100);
        return new Audio(key,new Salt1193PlaybackSession.Metadata("Fixture","Artist","","",png),lease,expiry);
    }
    private static Object open(Salt1193StreamAdapter adapter,Object item) throws Exception {
        var create=Class.forName("androidx.compose.ui.\u015b").getMethods()[0];
        Continuation<Unit> continuation=new Continuation<>() {
            public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }
            public void resumeWith(Object value) { throw new AssertionError("Unexpected suspend"); }
        };
        try {
            Object box=create.invoke(adapter.interceptor(),null,item,0L,0L,0,continuation);
            return Class.forName("kotlin.Result").getMethod("unbox-impl").invoke(box);
        } catch(java.lang.reflect.InvocationTargetException error) {
            if(error.getCause() instanceof CancellationException cancelled) throw cancelled;
            throw error;
        }
    }
    private static void rejected(Salt1193StreamAdapter adapter,Object item) throws Exception {
        try { ResultKt.throwOnFailure(open(adapter,item)); throw new AssertionError("Stale/foreign replay accepted"); }
        catch(IllegalStateException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        var profile=Salt1193Probe.inspect(QueueReplayContractTest.class.getClassLoader(),"1.19.3","Steam"); check(profile.matched());
        for(TrackKey key:List.of(TrackKey.netease("123"),TrackKey.bilibili("BV17x411w7KC",279786))) {
            Time clock=new Time(); AtomicBoolean active=new AtomicBoolean(true); AtomicInteger renewals=new AtomicInteger(),streams=new AtomicInteger();
            byte[] png=PlaybackCoverContractTest.png(); AtomicReference<URI> opened=new AtomicReference<>();
            var server=new LoopbackRangeServer(clock,uri->new TransportContractTest.FakeConnection(uri,100),active::get);
            var adapter=new Salt1193StreamAdapter(QueueReplayContractTest.class.getClassLoader(),profile,active::get,(local,flags)->{
                try(var reader=HostRangeReader.open(local)) { byte[] data=new byte[5]; reader.seek(95); check(reader.read(data)==5 && data[4]==99); }
                opened.set(local); streams.incrementAndGet(); return 321;
            });
            List<Object> queue=new ArrayList<>(); String[] fields;
            try(var session=new Salt1193PlaybackSession(profile,"account-A",active::get,new Binding(adapter),server)) {
                var initial=audio(key,"account-A",clock,png);
                var track=session.prepare(key,initial.metadata(),initial.lease(),r->"[00:01.000]Fixture",
                        r->{renewals.incrementAndGet();return audio(key,"account-A",clock,png);});
                session.enqueue(track,queue::add); fields=profile.mediaFactory().read(track.mediaItem());
                Path cover=Path.of(track.path()); check(queue.size()==1 && renewals.get()==0);
                check(open(adapter,queue.getFirst()).equals(321) && renewals.get()==0); URI old=opened.get();
                clock.advance(61); Thread.sleep(400);
                check(Files.isRegularFile(cover));
                check(open(adapter,queue.getFirst()).equals(321) && renewals.get()==1 && !old.equals(opened.get()));
                check(Arrays.equals(fields,profile.mediaFactory().read(track.mediaItem())) && queue.size()==1);
                check(Files.size(cover)>42 && OnlineLyrics.load(fields[0],fields[5]).contains("Fixture"));
                check(open(adapter,queue.getFirst()).equals(321) && renewals.get()==1);
                for(int i=0;i<9;i++) {
                    var next=audio(key,"account-A",clock,png);
                    session.prepare(key,next.metadata(),next.lease(),null,r->audio(key,"account-A",clock,png));
                }
                check(Files.isRegularFile(cover) && Files.size(cover)==42);
                check(open(adapter,queue.getFirst()).equals(321) && renewals.get()==2 && Files.size(cover)>42);
                // Logical queue entries must not consume one live URL each or hit the former 64-lease limit.
                for(int i=0;i<70;i++) { var next=audio(key,"account-A",clock,null); session.prepare(key,next.metadata(),next.lease(),null,r->audio(key,"account-A",clock,null)); }
                check(open(adapter,queue.getFirst()).equals(321) && renewals.get()==3);
                URI failedUri=opened.get(); server.revoke(failedUri);
                check(open(adapter,queue.getFirst()).equals(321) && renewals.get()==4 && !failedUri.equals(opened.get()));
                var failure=audio(key,"account-A",clock,null); AtomicBoolean wrong=new AtomicBoolean(true); AtomicInteger attempts=new AtomicInteger();
                var bad=session.prepare(key,failure.metadata(),failure.lease(),null,r->{attempts.incrementAndGet();return audio(key,wrong.get()?"other-account":"account-A",clock,null);});
                clock.advance(61); rejected(adapter,bad.mediaItem()); check(attempts.get()==1);
                wrong.set(false); check(open(adapter,bad.mediaItem()).equals(321) && attempts.get()==2);
                TrackKey other=key.platform()==TrackKey.Platform.NETEASE?TrackKey.netease("999"):TrackKey.bilibili(key.remoteId(),1);
                var identity=session.prepare(key,audio(key,"account-A",clock,null).metadata(),audio(key,"account-A",clock,null).lease(),null,r->audio(other,"account-A",clock,null));
                clock.advance(61); rejected(adapter,identity.mediaItem());
                AtomicInteger quality=new AtomicInteger(),qualityResolves=new AtomicInteger(); var qa=audio(key,"account-A",clock,null);
                var qualityTrack=session.prepare(key,qa.metadata(),qa.lease(),null,new Salt1193PlaybackSession.Renewer() {
                    public Object revision() { return quality.get(); }
                    public Audio resolve(io.github.yghfv.saltneri.plugin.net.RequestScope r) { qualityResolves.incrementAndGet(); return audio(key,"account-A",clock,null); }
                });
                check(open(adapter,qualityTrack.mediaItem()).equals(321) && qualityResolves.get()==0);
                quality.incrementAndGet(); check(open(adapter,qualityTrack.mediaItem()).equals(321) && qualityResolves.get()==1);
                // A transient first artwork failure must not permanently force a non-file queue path.
                var missing=audio(key,"account-A",clock,null);
                var recovered=session.prepare(key,missing.metadata(),missing.lease(),null,r->audio(key,"account-A",clock,png));
                Path recoveredPath=Path.of(recovered.path());
                check(recoveredPath.isAbsolute() && !Files.exists(recoveredPath));
                String[] recoveredFields=profile.mediaFactory().read(recovered.mediaItem());
                clock.advance(61);
                check(open(adapter,recovered.mediaItem()).equals(321) && Files.size(recoveredPath)>42);
                check(Arrays.equals(recoveredFields,profile.mediaFactory().read(recovered.mediaItem())));
                recovered.close(); check(!Files.exists(recoveredPath));
                AtomicInteger concurrent=new AtomicInteger(); CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
                var next=audio(key,"account-A",clock,null);
                var parallel=session.prepare(key,next.metadata(),next.lease(),null,r->{concurrent.incrementAndGet();started.countDown();release.await(3,TimeUnit.SECONDS);r.check();return audio(key,"account-A",clock,null);});
                clock.advance(61);
                try(var executor=Executors.newFixedThreadPool(2)) {
                    var a=executor.submit(()->open(adapter,parallel.mediaItem())); check(started.await(3,TimeUnit.SECONDS));
                    var b=executor.submit(()->open(adapter,parallel.mediaItem())); release.countDown();
                    check(a.get(3,TimeUnit.SECONDS).equals(321) && b.get(3,TimeUnit.SECONDS).equals(321) && concurrent.get()==1);
                }
                CountDownLatch waiting=new CountDownLatch(1),resume=new CountDownLatch(1); var finalAudio=audio(key,"account-A",clock,null);
                var late=session.prepare(key,finalAudio.metadata(),finalAudio.lease(),null,r->{waiting.countDown();resume.await(3,TimeUnit.SECONDS);r.check();return audio(key,"account-A",clock,null);});
                clock.advance(61);
                try(var executor=Executors.newSingleThreadExecutor()) {
                    var result=executor.submit(()->{rejected(adapter,late.mediaItem());return true;});
                    check(waiting.await(3,TimeUnit.SECONDS)); int before=streams.get(); session.close(); resume.countDown();
                    check(result.get(3,TimeUnit.SECONDS) && streams.get()==before);
                } finally { resume.countDown(); }
                check(!Files.exists(cover) && OnlineLyrics.load(fields[0],fields[5])==null && queue.size()==1);
                rejected(adapter,queue.getFirst());
            }
        }
        playlist(profile);
        System.out.println("PASS: "+checks+" dual-platform expired/evicted native queue replay, stable artwork, 70+ entries, identity, concurrent renewal and revocation checks; fake upstream/engine only");
    }
    private static void playlist(Salt1193Probe.Result profile) throws Exception {
        Time clock=new Time(); AtomicBoolean active=new AtomicBoolean(true); AtomicInteger calls=new AtomicInteger(),quality=new AtomicInteger();
        var server=new LoopbackRangeServer(clock,uri->new TransportContractTest.FakeConnection(uri,100),active::get);
        var adapter=new Salt1193StreamAdapter(QueueReplayContractTest.class.getClassLoader(),profile,active::get,(local,flags)->{
            try(var reader=HostRangeReader.open(local)) { check(reader.length()==100); } return 321;
        });
        var binding=new Binding(adapter); var queue=new ArrayList<Object>(); byte[] png=PlaybackCoverContractTest.png();
        var sink=new Salt1193PlaybackSession.QueueSink() {
            public void addToPlay(Object item) { throw new AssertionError("Unexpected queue mutation"); }
            public void replace(List<Object> items) { queue.clear(); queue.addAll(items); }
            public void update(Object item) throws ReflectiveOperationException {
                String id=profile.mediaFactory().read(item)[0];
                for(int i=0;i<queue.size();i++) if(profile.mediaFactory().read(queue.get(i))[0].equals(id)) {queue.set(i,item);return;}
                throw new AssertionError("Updated foreign queue item");
            }
        };
        List<Catalog.Item> rows=new ArrayList<>();
        for(int i=1;i<=10;i++) rows.add(new Catalog.Item(Catalog.Kind.TRACK,""+i,"Song "+i,"Artist",1000,true));
        List<Path> covers=new ArrayList<>();
        try(server;adapter;var session=new LazyPlaylistSession(profile,binding,server,sink,rows,"list-A",active::get,
                new LazyPlaylistSession.Resolver() {
                    public Object revision() { return quality.get(); }
                    public Audio resolve(Catalog.Item row,String scope,io.github.yghfv.saltneri.plugin.net.RequestScope r) { calls.incrementAndGet();return audio(TrackKey.netease(row.target()),scope,clock,png); }
                },row->r->"[00:01.000]"+row.title())) {
            session.play(); Object first=queue.getFirst(); String[] original=profile.mediaFactory().read(first);
            for(int i=0;i<10;i++) check(open(adapter,queue.get(i)).equals(321));
            for(Object item:queue) covers.add(Path.of(profile.mediaFactory().read(item)[5]));
            check(calls.get()==10 && binding.paths.size()==10 && Files.size(covers.getFirst())==42);
            check(open(adapter,first).equals(321) && calls.get()==11 && Files.size(covers.getFirst())>42);
            check(Arrays.equals(original,profile.mediaFactory().read(queue.getFirst())));
            check(OnlineLyrics.load(original[0],original[5]).contains("Song 1"));
            quality.incrementAndGet(); check(open(adapter,first).equals(321) && calls.get()==12);
            for(int i=0;i<12;i++) { clock.advance(61); check(open(adapter,first).equals(321)); }
            check(binding.paths.size()==10 && queue.size()==10);
            check(Arrays.equals(original,profile.mediaFactory().read(queue.getFirst())) && covers.stream().allMatch(Files::isRegularFile));
            active.set(false); int before=calls.get(); rejected(adapter,first); check(calls.get()==before);
        }
        check(binding.paths.isEmpty() && covers.stream().noneMatch(Files::exists));
    }
}
