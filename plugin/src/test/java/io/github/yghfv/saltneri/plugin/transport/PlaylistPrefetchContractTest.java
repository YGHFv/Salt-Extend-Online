package io.github.yghfv.saltneri.plugin.transport;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.host.*;
import io.github.yghfv.saltneri.plugin.library.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

public final class PlaylistPrefetchContractTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if(!ok) throw new AssertionError("Prefetch "+checks); }
    private static void await(BooleanSupplier ready) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!ready.getAsBoolean() && System.nanoTime()<until) Thread.sleep(10);
        check(ready.getAsBoolean());
    }
    private static final class Binding implements StreamBinding {
        final Map<String,Deferred> pending=new ConcurrentHashMap<>();
        public void register(String path,URI uri) { throw new AssertionError(); }
        public void registerDeferred(String path,Deferred source) { pending.put(path,source); }
        public void unregister(String path) { pending.remove(path); }
        public boolean isAttached() { return true; }
        public ReversibleInterceptorLists attach(Object engine) { return null; }
        public void close() { pending.clear(); }
    }
    public static void main(String[] args) throws Exception {
        var profile=Salt1193Probe.inspect(PlaylistPrefetchContractTest.class.getClassLoader(),"1.19.3","Steam"); check(profile.matched());
        var active=new AtomicBoolean(true); var resolutions=new CopyOnWriteArrayList<String>();
        var bindings=new Binding(); var requests=new CopyOnWriteArrayList<TransportContractTest.FakeConnection>();
        var plan=new AtomicReference<>(NativePlaylistOrder.Plan.EMPTY); var queue=new CopyOnWriteArrayList<Object>();
        var selected=new AtomicInteger(-1); var block=new AtomicBoolean(); var started=new CountDownLatch(1); var cancelled=new CountDownLatch(1);
        Clock clock=Clock.systemUTC();
        try(var server=new LoopbackRangeServer(clock,uri->{var c=new TransportContractTest.FakeConnection(uri,100); requests.add(c); return c;},active::get)) {
            server.cacheLimit(2048);
            List<Catalog.Item> rows=new ArrayList<>(); for(int i=0;i<12;i++) rows.add(new Catalog.Item(Catalog.Kind.TRACK,""+(i+1),"Song "+i,"Artist",1000,true));
            var sink=new Salt1193PlaybackSession.QueueSink() {
                public void addToPlay(Object item) { throw new AssertionError(); }
                public int firstIndex(int size) { return 4; }
                public void replace(List<Object> items,int index) { queue.addAll(items); selected.set(index); }
                public NativePlaylistOrder.Plan upcoming() { return plan.get(); }
            };
            try(var session=new LazyPlaylistSession(profile,bindings,server,sink,rows,"scope",active::get,(row,scope,request)->{
                resolutions.add(row.target());
                if(block.get()) {
                    started.countDown();
                    try { while(true) { request.check(); Thread.sleep(10); } }
                    catch(CancellationException stopped) { cancelled.countDown(); throw stopped; }
                }
                Instant expiry=clock.instant().plusSeconds(600);
                return new OnlinePlaybackController.Audio(TrackKey.netease(row.target()),new Salt1193PlaybackSession.Metadata(row.title(),"Artist","",""),
                        new MediaLease(MediaLease.Platform.NETEASE,URI.create("https://m7.music.126.net/"+row.target()),Map.of(),scope,expiry,100),expiry);
            })) {
                check(resolutions.isEmpty()); session.play(); check(selected.get()==4 && resolutions.equals(List.of("5")));
                List<String> ids=new ArrayList<>(),paths=new ArrayList<>();
                for(Object item:queue) { String[] fields=profile.mediaFactory().read(item); ids.add(fields[0]); paths.add(fields[5]); }
                // Native random order, intentionally NOT the next three catalog rows.
                plan.set(new NativePlaylistOrder.Plan(ids.get(4),"Random",List.of(ids.get(9),ids.get(2),ids.get(7))));
                Thread.sleep(400); check(resolutions.size()==1 && requests.isEmpty()); // No stream opened yet.
                bindings.pending.get(paths.get(4)).resolve();
                await(()->server.cacheBytes()==300);
                check(resolutions.equals(List.of("5","10","3","8")) && requests.size()==3);
                Thread.sleep(600); check(resolutions.size()==4 && requests.size()==3);
                URI warmed=bindings.pending.get(paths.get(9)).resolve();
                check(resolutions.size()==4);
                var hit=TransportContractTest.call(warmed,"GET","Range: bytes=10-29\r\n","");
                check(hit.code()==206 && hit.body().length==20 && hit.body()[0]==10 && requests.size()==3);
                plan.set(new NativePlaylistOrder.Plan(ids.get(9),"Circle",List.of(ids.get(10),ids.get(11),ids.get(0))));
                await(()->server.cacheBytes()==600); check(resolutions.equals(List.of("5","10","3","8","11","12","1")));
                plan.set(new NativePlaylistOrder.Plan(ids.get(9),"Random",List.of(ids.get(5),ids.get(6),ids.get(8))));
                // The cache can hit 800 after only two new prefixes, before LRU replaces the third.
                await(()->server.cacheBytes()==800 && resolutions.size()==10 && requests.size()==9);
                check(resolutions.size()==10 && requests.size()==9);
                check(TransportContractTest.call(warmed,"GET","Range: bytes=0-9\r\n","").code()==206);
                // Changing lookahead repeatedly may evict old prefetched entries, never the playing lease.
                plan.set(new NativePlaylistOrder.Plan(ids.get(9),"RepeatOne",List.of()));
                Thread.sleep(600); check(resolutions.size()==10);
                block.set(true); plan.set(new NativePlaylistOrder.Plan(ids.get(9),"CircleEnd",List.of(ids.get(1))));
                check(started.await(5,TimeUnit.SECONDS));
                active.set(false); check(cancelled.await(3,TimeUnit.SECONDS));
                int before=resolutions.size(); Thread.sleep(500); check(resolutions.size()==before);
            }
            await(()->server.cacheBytes()==0);
            check(bindings.pending.isEmpty() && server.cacheBytes()==0);
        }
        nativePrefix();
        persistentRetry();
        System.out.println("PASS: "+checks+" indexed play + next-three full-track prefetch, native random order, full host cache hits, repeat-one and cancellation checks; fake CDN, no audio");
    }
    private static void persistentRetry() throws Exception {
        Clock clock=Clock.systemUTC();var active=new AtomicBoolean(true);var plan=new AtomicReference<>(NativePlaylistOrder.Plan.EMPTY);
        var calls=new ConcurrentHashMap<String,AtomicInteger>();var bindings=new Binding();
        var root=java.nio.file.Files.createTempDirectory("salt-prefetch-persistent-").toRealPath();
        int size=1024*1024+123;
        try(var store=new SavedTrackStore(root);
            var server=new LoopbackRangeServer(clock,uri->new TransportContractTest.FakeConnection(uri,size),active::get)) {
            server.cacheLimit(16L*1024*1024);
            var entries=new ArrayList<SavedTrackStore.Entry>();
            for(int i=1;i<=4;i++) {
                String id=""+i;entries.add(store.save(new OnlinePlaybackController.Target(io.github.yghfv.saltneri.plugin.auth.AccountPlatform.NETEASE,id),
                        new Catalog.Item(Catalog.Kind.TRACK,id,"Song "+i,"Artist",120000,true)));
            }
            try(var saved=new SavedTrackSession(bindings,server,active::get,new SavedTrackSession.Resolver() {
                public Object revision(io.github.yghfv.saltneri.plugin.auth.AccountPlatform platform){return "fixture";}
                public OnlinePlaybackController.Audio resolve(SavedTrackStore.Entry entry,io.github.yghfv.saltneri.plugin.net.RequestScope request)throws Exception {
                    int count=calls.computeIfAbsent(entry.id(),ignored->new AtomicInteger()).incrementAndGet();
                    if(entry==entries.get(1) && count==1)throw new java.io.IOException("Temporary fixture failure");
                    var expiry=clock.instant().plusSeconds(1800);
                    return new OnlinePlaybackController.Audio(TrackKey.netease(entry.target().id()),
                            new Salt1193PlaybackSession.Metadata(entry.title(),"Artist","",""),
                            new MediaLease(MediaLease.Platform.NETEASE,URI.create("https://m7.music.126.net/"+entry.target().id()),Map.of(),"fixture",expiry,size),expiry,entry.target());
                }
            })) {
                entries.forEach(saved::register);
                plan.set(new NativePlaylistOrder.Plan(entries.getFirst().trackId(),"Random",entries.subList(1,4).stream().map(SavedTrackStore.Entry::trackId).toList()));
                saved.prefetch(new Salt1193PlaybackSession.QueueSink() {
                    public void addToPlay(Object item){throw new AssertionError("Prefetch changed queue");}
                    public NativePlaylistOrder.Plan upcoming(){return plan.get();}
                });
                await(()->server.cacheBytes()==3L*size);
                check(calls.get(entries.get(1).id()).get()==2);
                for(var entry:entries.subList(1,4)) {
                    URI uri=bindings.pending.get(entry.path()).resolve();
                    check(server.isFullyCached(uri));
                    check(TransportContractTest.call(uri,"GET","Range: bytes="+(size-10)+"-"+(size-1)+"\r\n","").code()==206);
                }
                server.clearCache();await(()->server.cacheBytes()==3L*size);
                check(calls.get(entries.get(1).id()).get()==2);
                active.set(false);
            }
            await(()->server.cacheBytes()==0);
            check(server.cacheBytes()==0 && bindings.pending.isEmpty());
        } finally {
            try(var files=java.nio.file.Files.walk(root)) {
                for(var file:files.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.deleteIfExists(file);
            }
        }
    }
    private static void nativePrefix() throws Exception {
        Clock clock=Clock.systemUTC(); var calls=new AtomicInteger();
        try(var server=new LoopbackRangeServer(clock,uri->{calls.incrementAndGet();return new TransportContractTest.FakeConnection(uri,1024*1024);})) {
            server.cacheLimit(2*1024*1024);
            var lease=new MediaLease(MediaLease.Platform.NETEASE,URI.create("https://m7.music.126.net/prefix"),Map.of(),"prefix",clock.instant().plusSeconds(60),1024*1024);
            URI local=server.register(lease);
            try(var scope=new io.github.yghfv.saltneri.plugin.net.RequestScope(()->true)) { server.prefetch(local,scope); }
            await(()->server.cacheBytes()==1024*1024); check(calls.get()==2 && server.isFullyCached(local));
            try(var reader=HostRangeReader.open(local)) {
                byte[] bytes=new byte[16]; check(reader.read(bytes)==16 && bytes[15]==15);
                await(()->server.cacheBytes()==1024*1024);
                check(calls.get()==2);
                check(reader.seek(512*1024-16) && reader.read(bytes)==16 && bytes[15]==(byte)255 && calls.get()==2);
                check(reader.seek(512*1024) && reader.read(bytes)==16 && calls.get()==2);
                check(reader.seek(1024*1024-16) && reader.read(bytes)==16 && calls.get()==2);
            }
            server.cacheLimit(0); int before=calls.get();
            try(var scope=new io.github.yghfv.saltneri.plugin.net.RequestScope(()->true)) { server.prefetch(local,scope); }
            check(calls.get()==before && server.cacheBytes()==0);
            server.cacheLimit(512*1024);
            try(var scope=new io.github.yghfv.saltneri.plugin.net.RequestScope(()->true)) {
                try{server.prefetch(local,scope);throw new AssertionError("Oversized track downloaded");}
                catch(java.io.IOException expected){check(calls.get()==before && server.cacheBytes()==0);}
            }
        }
    }
}
