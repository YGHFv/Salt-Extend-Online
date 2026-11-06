package io.github.yghfv.saltneri.plugin.host;

import io.github.yghfv.saltneri.plugin.library.*;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.transport.LoopbackRangeServer;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Queue logical tracks now; resolve expiring signed URLs only as Salt opens each stream. */
public final class LazyPlaylistSession implements AutoCloseable {
    @FunctionalInterface public interface Resolver {
        OnlinePlaybackController.Audio resolve(Catalog.Item item, String scope, RequestScope request) throws Exception;
    }
    private final Salt1193Probe.MediaFactory media;
    private final StreamBinding adapter;
    private final LoopbackRangeServer transport;
    private final Salt1193PlaybackSession.QueueSink queue;
    private final BooleanSupplier active;
    private final Resolver resolver;
    private final String scope;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<RequestScope> requests = ConcurrentHashMap.newKeySet();
    private final Set<String> aliases = ConcurrentHashMap.newKeySet();
    private final LinkedHashMap<Entry, Cached> cache = new LinkedHashMap<>(16,0.75f,true);
    private volatile boolean closed, submitted;
    private record Cached(URI uri, Instant expiry, PlaybackCoverFile cover) { }
    private static final class Entry {
        final Catalog.Item row;
        final String id, path;
        volatile Object media;
        Entry(Catalog.Item row) { this.row=row; String nonce=UUID.randomUUID().toString(); id="saltneri:queue:"+nonce; path="saltneri://queue/"+nonce; }
    }
    public LazyPlaylistSession(Salt1193Probe.Result profile, StreamBinding adapter, LoopbackRangeServer transport,
                               Salt1193PlaybackSession.QueueSink queue, List<Catalog.Item> items, String scope,
                               BooleanSupplier active, Resolver resolver) throws ReflectiveOperationException {
        if (!profile.matched() || items.isEmpty() || items.size()>10000) throw new IllegalArgumentException("Invalid playlist");
        this.media=profile.mediaFactory(); this.adapter=adapter; this.transport=transport; this.queue=queue;
        this.scope=scope; this.active=active; this.resolver=resolver;
        try {
            for (Catalog.Item row:items) {
                if(!row.enabled() || row.kind()!=Catalog.Kind.TRACK) throw new IllegalArgumentException("Unplayable queue item");
                check(); Entry entry=new Entry(row); entries.add(entry);
                entry.media=media.create(entry.id,row.title(),row.subtitle(),"","",entry.path);
                adapter.registerDeferred(entry.path,()->resolve(entry));
            }
        } catch (ReflectiveOperationException | RuntimeException failure) { close(); throw failure; }
    }
    private void check() { if(closed || !active.getAsBoolean()) throw new java.util.concurrent.CancellationException("Playlist retired"); }
    public void play() throws Exception {
        // A failed first track must not destroy the user's existing playback queue.
        resolve(entries.getFirst()); check();
        queue.replace(entries.stream().map(e->e.media).toList()); submitted=true;
    }
    private URI resolve(Entry entry) throws Exception {
        synchronized(entry) {
            check();
            synchronized(cache) { Cached old=cache.get(entry); if(old!=null && Instant.now().plusSeconds(10).isBefore(old.expiry())) return old.uri(); }
            try(RequestScope request=new RequestScope(()->!closed && active.getAsBoolean())) {
                requests.add(request); request.check(); var audio=resolver.resolve(entry.row,scope,request); request.check(); check();
                if(!audio.lease().belongsTo(scope)) throw new SecurityException("Playlist audio scope mismatch");
                URI local=transport.register(audio.lease()); PlaybackCoverFile cover=null;
                try {
                    if(audio.metadata().coverPng()!=null) {
                        try { cover=PlaybackCoverFile.create(audio.metadata().coverPng()); } catch(java.io.IOException ignored) { }
                    }
                    Object item=media.create(entry.id,audio.metadata().title(),audio.metadata().artist(),audio.metadata().album(),audio.metadata().albumArtist(),cover==null?entry.path:cover.path());
                    synchronized(cache) {
                        check();
                        if(cover!=null) { adapter.registerDeferred(cover.path(),()->resolve(entry)); aliases.add(cover.path()); }
                        Cached previous=cache.put(entry,new Cached(local,audio.expiresAt(),cover));
                        if(previous!=null) retire(previous);
                        entry.media=item;
                        while(cache.size()>8) { Entry first=cache.keySet().iterator().next(); retire(cache.remove(first)); }
                    }
                    if(submitted) { check(); queue.update(item); }
                    return local;
                } catch(Exception failure) {
                    synchronized(cache) { Cached current=cache.get(entry); if(current!=null && current.uri().equals(local)) cache.remove(entry); }
                    transport.revoke(local); if(cover!=null) { adapter.unregister(cover.path()); cover.close(); } throw failure;
                }
            } finally { requests.removeIf(r->{ try{r.check();return false;}catch(RuntimeException done){return true;} }); }
        }
    }
    private void retire(Cached cached) { transport.revoke(cached.uri()); if(cached.cover()!=null) cached.cover().close(); }
    @Override public void close() {
        closed=true; requests.forEach(RequestScope::close); requests.clear();
        for(Entry entry:entries) adapter.unregister(entry.path);
        synchronized(cache) { cache.values().forEach(this::retire); cache.clear(); }
        aliases.forEach(adapter::unregister); aliases.clear();
    }
}
