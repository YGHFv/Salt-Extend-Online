package io.github.yghfv.saltneri.plugin.video;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.Target;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.transport.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Current and next-three video leases share cached ranges with actual mpv playback. */
public final class VideoPrecache implements AutoCloseable {
    @FunctionalInterface public interface Resolver {
        MediaLease resolve(Target target,VideoResolution resolution,AccountCredentials account,RequestScope request) throws Exception;
    }
    private record Key(Target target,VideoResolution resolution,AccountCredentials account) { }
    private final ConcurrentHashMap<Key,URI> ready=new ConcurrentHashMap<>();
    private final Set<Key> attempted=ConcurrentHashMap.newKeySet();
    private final Supplier<List<Target>> order;
    private final Supplier<VideoResolution> resolution;
    private final Function<AccountPlatform,AccountCredentials> accounts;
    private final BooleanSupplier active,enabled;
    private final LoopbackRangeServer transport;
    private final Resolver resolver;
    private final ScheduledExecutorService worker;
    private volatile RequestScope pending;
    private volatile boolean closed;
    private Object lastPlan;
    public VideoPrecache(Supplier<List<Target>> order,Supplier<VideoResolution> resolution,
            Function<AccountPlatform,AccountCredentials> accounts,BooleanSupplier active,BooleanSupplier enabled,
            LoopbackRangeServer transport,Resolver resolver) {
        this.order=order;this.resolution=resolution;this.accounts=accounts;this.active=active;this.enabled=enabled;
        this.transport=transport;this.resolver=resolver;
        worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"salt-video-precache");t.setDaemon(true);return t;});
        worker.scheduleWithFixedDelay(this::tick,250,500,TimeUnit.MILLISECONDS);
    }
    private boolean valid(Key key) {
        return !closed && active.getAsBoolean() && resolution.get()==key.resolution()
                && accounts.apply(key.target().platform())==key.account() && (key.account()==null || key.account().isActive());
    }
    private List<Target> snapshotOrder() {return order.get().stream().distinct().limit(4).toList();}
    public URI resolve(Target target,RequestScope request) throws Exception {
        var key=new Key(target,resolution.get(),accounts.apply(target.platform()));
        request.check();URI hit=ready.get(key);
        if(hit!=null && transport.isAvailable(hit))return hit;
        if(hit!=null)ready.remove(key,hit);
        var lease=resolver.resolve(target,key.resolution(),key.account(),request);
        if(lease==null)return null; // Exact song has no MV.
        URI local=null;
        try {
            request.check();if(!valid(key))throw new CancellationException();
            String scope=key.account()==null?"anonymous-video":key.account().accountScope();
            local=transport.register(lease.bindTo(scope,()->valid(key)));
            request.check();if(!valid(key))throw new CancellationException();
            URI previous=ready.putIfAbsent(key,local);
            if(previous!=null) {transport.revoke(local);return previous;}
            return local;
        } catch(Exception failure) {if(local!=null)transport.revoke(local);else lease.release();throw failure;}
    }
    private void tick() {
        try {
            if(closed || !active.getAsBoolean())return;
            List<Target> plan=snapshotOrder();
            var cap=resolution.get();long revision=transport.cacheRevision();
            var keys=plan.stream().map(t->new Key(t,cap,accounts.apply(t.platform()))).toList();
            Object version=List.of(keys,revision);
            if(!version.equals(lastPlan)) {lastPlan=version;attempted.clear();}
            for(var row:ready.entrySet())if(!keys.contains(row.getKey()) || !transport.isAvailable(row.getValue())) {
                if(ready.remove(row.getKey(),row.getValue()))transport.revoke(row.getValue());
            }
            if(!enabled.getAsBoolean())return;
            for(var key:keys) {
                if(!attempted.add(key))continue;
                BooleanSupplier current=()->valid(key) && enabled.getAsBoolean() && revision==transport.cacheRevision()
                        && plan.equals(snapshotOrder());
                try(var request=new RequestScope(current,300000)) {
                    pending=request;
                    URI uri=resolve(key.target(),request);
                    if(uri!=null) {
                        transport.prefetchVideoStart(uri,request);
                        transport.prefetch(uri,request);
                    }
                } catch(Exception | LinkageError ignored) { /* Speculation never changes playback or retries a denial. */ }
                finally {pending=null;}
            }
        } catch(RuntimeException ignored) { }
    }
    public void cancel() {var request=pending;if(request!=null)request.close();attempted.clear();}
    @Override public void close() {
        closed=true;cancel();worker.shutdownNow();
        for(var uri:ready.values())transport.revoke(uri);
        ready.clear();
    }
}
