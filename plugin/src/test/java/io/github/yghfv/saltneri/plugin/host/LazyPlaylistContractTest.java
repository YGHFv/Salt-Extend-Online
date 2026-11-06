package io.github.yghfv.saltneri.plugin.host;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.library.*;
import io.github.yghfv.saltneri.plugin.transport.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class LazyPlaylistContractTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if(!ok) throw new AssertionError("Lazy playlist "+checks); }
    static final class Binding implements StreamBinding {
        final Map<String,Deferred> pending=new ConcurrentHashMap<>();
        public void register(String path,URI uri) { throw new AssertionError("Must be deferred"); }
        public void registerDeferred(String path,Deferred source) { pending.put(path,source); }
        public void unregister(String path) { pending.remove(path); }
        public boolean isAttached() { return true; }
        public ReversibleInterceptorLists attach(Object engine) { return null; }
        public void close() { pending.clear(); }
    }
    static final class Queue implements Salt1193PlaybackSession.QueueSink {
        List<Object> items=List.of(); int replacements,updates;
        public void addToPlay(Object item) { throw new AssertionError("Must replace, not append"); }
        public void replace(List<Object> next) { items=List.copyOf(next); replacements++; }
        public void update(Object item) { updates++; }
    }
    private static OnlinePlaybackController.Audio audio(Catalog.Item item,String scope) {
        Instant expiry=Instant.now().plusSeconds(600);
        return new OnlinePlaybackController.Audio(TrackKey.netease(item.target()),new Salt1193PlaybackSession.Metadata(item.title(),"Artist","",""),
                new MediaLease(MediaLease.Platform.NETEASE,URI.create("https://m7.music.126.net/fixture"),Map.of(),scope,expiry,4096),expiry);
    }
    public static void main(String[] args) throws Exception {
        var profile=Salt1193Probe.inspect(LazyPlaylistContractTest.class.getClassLoader(),"1.19.3","Steam"); check(profile.matched());
        var binding=new Binding(); var queue=new Queue(); var allowed=new AtomicBoolean(true); var resolves=new AtomicInteger();
        List<Catalog.Item> rows=new ArrayList<>(); for(int i=1;i<=100;i++) rows.add(new Catalog.Item(Catalog.Kind.TRACK,""+i,"Song "+i,"Artist",1000,true));
        try(var transport=new LoopbackRangeServer(allowed::get)) {
            try(var session=new LazyPlaylistSession(profile,binding,transport,queue,rows,"test",allowed::get,(item,scope,r)->{resolves.incrementAndGet(); return audio(item,scope);})) {
                check(resolves.get()==0 && binding.pending.size()==100 && queue.replacements==0);
                session.play(); check(queue.replacements==1 && queue.items.size()==100 && resolves.get()==1);
                for(int i=0;i<10;i++) {
                    String path=profile.mediaFactory().read(queue.items.get(i))[5];
                    check(binding.pending.get(path).resolve().getHost().equals("127.0.0.1"));
                }
                check(resolves.get()==10 && queue.updates==9);
                String first=profile.mediaFactory().read(queue.items.getFirst())[5]; binding.pending.get(first).resolve();
                check(resolves.get()==11);
                allowed.set(false);
                try { binding.pending.get(first).resolve(); throw new AssertionError("Revoked account opened"); } catch(CancellationException expected) { checks++; }
            }
            check(binding.pending.isEmpty()); allowed.set(true);
            int before=queue.replacements;
            try(var session=new LazyPlaylistSession(profile,binding,transport,queue,rows,"test",allowed::get,(i,s,r)->{throw new java.io.IOException("No audio");})) {
                try {session.play();throw new AssertionError("Expected first track failure");}catch(java.io.IOException expected){checks++;}
                check(queue.replacements==before);
            }
        }
        check(binding.pending.isEmpty());
        System.out.println("PASS: "+checks+" lazy 100-track native queue, bounded URL cache, first-track failure and account revocation checks; no audio/network");
    }
}
