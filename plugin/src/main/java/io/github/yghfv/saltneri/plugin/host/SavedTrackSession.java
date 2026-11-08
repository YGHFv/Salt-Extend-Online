package io.github.yghfv.saltneri.plugin.host;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.Audio;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.transport.LoopbackRangeServer;
import java.net.URI;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Saved native playlists survive page changes/restarts; only leases are account-scoped. */
public final class SavedTrackSession implements AutoCloseable {
    public interface Resolver {
        Audio resolve(SavedTrackStore.Entry entry, RequestScope request) throws Exception;
        Object revision(AccountPlatform platform);
        default OnlineLyrics.Source lyrics(SavedTrackStore.Entry entry) { return null; }
    }
    private static final class Binding {
        final SavedTrackStore.Entry entry;
        OnlineLyrics.Binding lyrics;
        RequestScope request;
        long generation;
        Binding(SavedTrackStore.Entry entry) { this.entry=entry; }
    }
    private record Cached(URI uri, java.time.Instant expiry, Object revision) { }
    private final Map<String,Binding> bindings=new HashMap<>();
    private final LinkedHashMap<Binding,Cached> cache=new LinkedHashMap<>(16,0.75f,true);
    private final StreamBinding adapter;
    private final LoopbackRangeServer transport;
    private final BooleanSupplier active;
    private final Resolver resolver;
    private volatile boolean closed;
    private Binding opened;
    public SavedTrackSession(StreamBinding adapter,LoopbackRangeServer transport,BooleanSupplier active,Resolver resolver) {
        this.adapter=adapter; this.transport=transport; this.active=active; this.resolver=resolver;
    }
    public synchronized void register(SavedTrackStore.Entry entry) {
        check(); if(bindings.containsKey(entry.path())) return;
        Binding binding=new Binding(entry);
        adapter.registerDeferred(entry.path(),()->resolve(binding));
        bindLyrics(binding);
        bindings.put(entry.path(),binding);
    }
    private void bindLyrics(Binding binding) {
        OnlineLyrics.Source lyrics=resolver.lyrics(binding.entry);
        if(lyrics!=null) binding.lyrics=OnlineLyrics.bind(binding.entry.trackId(),binding.entry.path(),()->!closed && active.getAsBoolean(),lyrics);
    }
    private void check() { if(closed || !active.getAsBoolean()) throw new java.util.concurrent.CancellationException("Saved playback unavailable"); }
    private URI resolve(Binding binding) throws Exception {
        synchronized(binding) {
            long generation; Object revision=resolver.revision(binding.entry.target().platform());
            synchronized(this) {
                check(); opened=binding; generation=binding.generation;
                Cached cached=cache.get(binding);
                if(cached!=null && Objects.equals(revision,cached.revision()) && transport.isAvailable(cached.uri())
                        && transport.now().plusSeconds(Math.max(30,binding.entry.duration()/1000+30)).isBefore(cached.expiry())) return cached.uri();
                retire(cache.remove(binding));
            }
            try(RequestScope request=new RequestScope(()->active.getAsBoolean() && valid(binding,generation,revision))) {
                synchronized(this) { check(); binding.request=request; }
                request.check(); Audio audio=resolver.resolve(binding.entry,request); request.check();
                if(!audio.key().platform().name().equals(binding.entry.target().platform().name())
                        || !audio.lease().platform().name().equals(binding.entry.target().platform().name())
                        || !binding.entry.target().equals(audio.target()) || !transport.now().isBefore(audio.expiresAt())) throw new SecurityException("Saved target mismatch or expiry");
                synchronized(this) {
                    check(); request.check();
                    URI uri=transport.register(audio.lease());
                    if(audio.metadata().coverPng()!=null) try {binding.entry.cover().replace(audio.metadata().coverPng());} catch(java.io.IOException ignored) { }
                    cache.put(binding,new Cached(uri,audio.expiresAt().isBefore(audio.lease().expiresAt())?audio.expiresAt():audio.lease().expiresAt(),revision));
                    while(cache.size()>8) retire(cache.remove(cache.keySet().stream().filter(b->b!=opened).findFirst().orElseThrow()));
                    return uri;
                }
            } finally { synchronized(this) {binding.request=null;} }
        }
    }
    private synchronized boolean valid(Binding binding,long generation,Object revision) {
        return !closed && binding.generation==generation && Objects.equals(revision,resolver.revision(binding.entry.target().platform()));
    }
    private void retire(Cached cached) { if(cached!=null) transport.revoke(cached.uri()); }
    public synchronized void revoke(AccountPlatform platform) {
        for(Binding binding:bindings.values()) if(binding.entry.target().platform()==platform) {
            binding.generation++; if(binding.request!=null) binding.request.close(); retire(cache.remove(binding));
            if(binding.lyrics!=null) {binding.lyrics.close();binding.lyrics=null;bindLyrics(binding);}
        }
    }
    @Override public synchronized void close() {
        if(closed) return; closed=true;
        for(Binding binding:bindings.values()) {
            if(binding.request!=null) binding.request.close();
            if(binding.lyrics!=null) binding.lyrics.close(); adapter.unregister(binding.entry.path());
        }
        cache.values().forEach(this::retire); cache.clear(); bindings.clear();
    }
}
