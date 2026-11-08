package io.github.yghfv.saltneri.plugin.host;

import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.transport.LoopbackRangeServer;
import java.net.URI;
import java.util.*;
import java.util.function.*;

/** Full next-three downloads follow the native order, with bounded retries for failed speculation. */
final class PlaylistPrefetch implements AutoCloseable {
    interface Source {
        boolean contains(String id);
        URI resolve(String id,BooleanSupplier valid) throws Exception;
        Object revision();
    }
    private static final class Attempt { int count; long retryAt; URI complete; }
    private final Supplier<NativePlaylistOrder.Plan> order;
    private final Source source;
    private final LoopbackRangeServer transport;
    private final BooleanSupplier active;
    private final java.util.concurrent.ScheduledExecutorService worker;
    private final Map<String,Attempt> attempts=new HashMap<>();
    private Object lastPlan;
    private volatile boolean closed;
    private volatile RequestScope request;
    PlaylistPrefetch(Supplier<NativePlaylistOrder.Plan> order,Source source,LoopbackRangeServer transport,BooleanSupplier active) {
        this.order=order;this.source=source;this.transport=transport;this.active=active;
        worker=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{
            Thread t=new Thread(r,"salt-online-playlist-prefetch");t.setDaemon(true);return t;
        });
        worker.scheduleWithFixedDelay(this::tick,250,500,java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    private boolean permitted() {return !closed && active.getAsBoolean();}
    void tick() {
        try {
            if(!permitted()) return;
            var plan=order.get();Object revision=source.revision();
            if(!source.contains(plan.current())) return;
            long cacheRevision=transport.cacheRevision();
            Object key=Arrays.asList(plan,revision,cacheRevision);
            if(!key.equals(lastPlan)) {lastPlan=key;attempts.clear();}
            // LRU pressure can evict a completed track without changing the playback plan.
            for(Attempt attempt:attempts.values())
                if(attempt.complete!=null && !transport.isFullyCached(attempt.complete)) {
                    attempt.complete=null;attempt.count=0;attempt.retryAt=0;
                }
            BooleanSupplier valid=()->permitted() && plan.equals(order.get()) && Objects.equals(revision,source.revision())
                    && cacheRevision==transport.cacheRevision();
            for(String id:plan.next()) {
                if(!valid.getAsBoolean()) return;
                if(!source.contains(id) || id.equals(plan.current())) continue;
                Attempt attempt=attempts.computeIfAbsent(id,ignored->new Attempt());
                if(attempt.complete!=null && transport.isFullyCached(attempt.complete)) continue;
                if(attempt.count>=3 || System.nanoTime()<attempt.retryAt) continue;
                attempt.count++;
                try {
                    URI uri=source.resolve(id,valid);
                    try(RequestScope scope=new RequestScope(valid,300000)) {
                        request=scope;
                        transport.prefetch(uri,scope);
                    } finally {request=null;}
                    attempt.complete=uri;
                } catch(Exception failure) {
                    if(failure instanceof io.github.yghfv.saltneri.plugin.bilibili.BiliClient.PlatformRejected
                            || failure instanceof io.github.yghfv.saltneri.plugin.netease.NeteaseClient.PlatformRejected)
                        attempt.count=3;
                    attempt.retryAt=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(attempt.count==1?3:10);
                }
            }
        } catch(RuntimeException ignored) { /* Revocation or a replaced queue cancels speculation only. */ }
    }
    @Override public void close() {
        closed=true;RequestScope scope=request;if(scope!=null)scope.close();worker.shutdownNow();
    }
}
