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
        default Object revision() { return null; }
    }
    private final Salt1193Probe.MediaFactory media;
    private final StreamBinding adapter;
    private final LoopbackRangeServer transport;
    private final Salt1193PlaybackSession.QueueSink queue;
    private final BooleanSupplier active;
    private final Resolver resolver;
    private final SavedTrackSession persistent;
    private final String scope;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String,Entry> byId = new HashMap<>();
    private final Set<RequestScope> requests = ConcurrentHashMap.newKeySet();
    private final LinkedHashMap<Entry, Cached> cache = new LinkedHashMap<>(16,0.75f,true);
    private volatile boolean closed, submitted;
    private PlaylistPrefetch preloader;
    private volatile Entry opened;
    private record Cached(URI uri, Instant expiry, Entry entry, Object revision) { }
    private static final class Entry {
        final Catalog.Item row;
        final String id, path;
        volatile Object media;
        OnlineLyrics.Binding lyrics;
        PlaybackCoverFile cover;
        io.github.yghfv.saltneri.core.TrackKey key;
        final SavedTrackStore.Entry saved;
        Entry(Catalog.Item row) {
            this(row,null);
        }
        Entry(Catalog.Item row,SavedTrackStore.Entry saved) {
            this.row=row; this.saved=saved;
            if(saved!=null) {id=saved.trackId();path=saved.path();return;}
            String nonce=UUID.randomUUID().toString(); id="saltneri:queue:"+nonce;
            try { cover=PlaybackCoverFile.reserve(); } catch(java.io.IOException ignored) { }
            path=cover==null?"saltneri://queue/"+nonce:cover.path();
        }
    }
    public LazyPlaylistSession(Salt1193Probe.Result profile, StreamBinding adapter, LoopbackRangeServer transport,
                               Salt1193PlaybackSession.QueueSink queue, List<Catalog.Item> items, String scope,
                               BooleanSupplier active, Resolver resolver) throws ReflectiveOperationException {
        this(profile,adapter,transport,queue,items,scope,active,resolver,item->null);
    }
    public LazyPlaylistSession(Salt1193Probe.Result profile, StreamBinding adapter, LoopbackRangeServer transport,
                               Salt1193PlaybackSession.QueueSink queue, List<Catalog.Item> items, String scope,
                               BooleanSupplier active, Resolver resolver, java.util.function.Function<Catalog.Item,OnlineLyrics.Source> lyrics)
            throws ReflectiveOperationException {
        this(profile,adapter,transport,queue,items,scope,active,resolver,lyrics,null,null);
    }
    public LazyPlaylistSession(Salt1193Probe.Result profile, StreamBinding adapter, LoopbackRangeServer transport,
                               Salt1193PlaybackSession.QueueSink queue, List<Catalog.Item> items, String scope,
                               BooleanSupplier active, Resolver resolver, java.util.function.Function<Catalog.Item,OnlineLyrics.Source> lyrics,
                               SavedTrackSession persistent,java.util.function.Function<Catalog.Item,SavedTrackStore.Entry> identities)
            throws ReflectiveOperationException {
        if (!profile.matched() || items.isEmpty() || items.size()>10000) throw new IllegalArgumentException("Invalid playlist");
        this.media=profile.mediaFactory(); this.adapter=adapter; this.transport=transport; this.queue=queue;
        this.scope=scope; this.active=active; this.resolver=resolver;
        this.persistent=persistent;
        try {
            for (Catalog.Item row:items) {
                if(!row.enabled() || row.kind()!=Catalog.Kind.TRACK) throw new IllegalArgumentException("Unplayable queue item");
                check(); Entry entry=new Entry(row,persistent==null?null:identities.apply(row)); entries.add(entry); byId.put(entry.id,entry);
                entry.media=media.create(entry.id,row.title(),row.subtitle(),"","",entry.path);
                if(persistent!=null) {persistent.register(entry.saved);continue;}
                var source=lyrics.apply(row);
                if(source!=null) entry.lyrics=OnlineLyrics.bind(entry.id,entry.path,()->!closed && active.getAsBoolean(),source);
                adapter.registerDeferred(entry.path,()->{ opened=entry; return resolve(entry,()->true); });
            }
        } catch (ReflectiveOperationException | RuntimeException failure) { close(); throw failure; }
    }
    private void check() { if(closed || !active.getAsBoolean()) throw new java.util.concurrent.CancellationException("Playlist retired"); }
    public void play() throws Exception {
        // Preserve the old queue until one full track is ready. Never retry account/network rejection.
        int first=Objects.checkIndex(queue.firstIndex(entries.size()),entries.size());
        int skipped=0;
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while(true) {
            check();
            try { resolve(entries.get(first),()->System.nanoTime()<deadline); break; }
            catch(io.github.yghfv.saltneri.plugin.netease.NeteaseClient.TrackUnavailable | io.github.yghfv.saltneri.plugin.qqmusic.QqMusicClient.TrackUnavailable unavailable) {
                check();
                if(entries.size()==1 || ++skipped>=5 || System.nanoTime()>=deadline) throw unavailable;
                Entry rejected=entries.remove(first); byId.remove(rejected.id);
                if(persistent==null) {
                    adapter.unregister(rejected.path);
                    if(rejected.lyrics!=null) rejected.lyrics.close();
                    if(rejected.cover!=null) rejected.cover.close();
                }
                first%=entries.size();
            }
        }
        check();
        queue.replace(entries.stream().map(e->e.media).toList(),first); submitted=true;
        synchronized(cache) {
            check();
            if(persistent==null) preloader=new PlaylistPrefetch(this::plan,new PlaylistPrefetch.Source() {
                public boolean contains(String id) {
                    Entry entry=byId.get(id);
                    return entry!=null && (entry.key==null || entry.key.platform()!=io.github.yghfv.saltneri.core.TrackKey.Platform.APPLEMUSIC)
                        && !entry.row.target().matches("[a-z]{2}:[1-9][0-9]{0,18}");
                }
                public URI resolve(String id,BooleanSupplier valid) throws Exception {return LazyPlaylistSession.this.resolve(byId.get(id),valid);}
                public Object revision() {return resolver.revision();}
            },transport,()->!closed && opened!=null && active.getAsBoolean());
        }
    }
    private URI resolve(Entry entry,BooleanSupplier valid) throws Exception {
        if(persistent!=null) {check();return persistent.warm(entry.saved,()->!closed && active.getAsBoolean() && valid.getAsBoolean());}
        synchronized(entry) {
            check(); if(!valid.getAsBoolean()) throw new java.util.concurrent.CancellationException("Prefetch superseded");
            // A prefetched URL must cover the track, not merely its first seconds. Otherwise a
            // valid-at-open lease can be revoked mid-song and leave the host waiting for bytes.
            long runway=Math.max(30,Math.min(1800,entry.row.count()/1000+30));
            synchronized(cache) { Cached old=cache.get(entry); if(old!=null && transport.isAvailable(old.uri()) && Objects.equals(old.revision(),resolver.revision()) && transport.now().plusSeconds(runway).isBefore(old.expiry())) return old.uri(); }
            try(RequestScope request=new RequestScope(()->!closed && active.getAsBoolean() && valid.getAsBoolean())) {
                Object revision=resolver.revision();
                requests.add(request); request.check(); var audio=resolver.resolve(entry.row,scope,request); request.check(); check();
                if(!audio.lease().belongsTo(scope) || !audio.transportMatches()
                        || entry.key!=null && !entry.key.equals(audio.key())) throw new SecurityException("Playlist audio identity mismatch");
                if(!transport.now().isBefore(audio.expiresAt())) throw new IllegalStateException("Playlist audio already expired");
                URI local=transport.register(audio.lease());
                try {
                    synchronized(cache) {
                        check();Cached previous=cache.remove(entry);if(previous!=null)retire(previous);
                    }
                    if(entry.cover!=null && audio.metadata().coverPng()!=null)
                        try {entry.cover.replace(audio.metadata().coverPng(),()->!closed && active.getAsBoolean() && valid.getAsBoolean());}
                        catch(java.io.IOException ignored) { }
                    Object item;
                    synchronized(cache) {
                        check();
                        // Salt's onLoaded callback can retain the original object, before async queue.update runs.
                        // Its path must already name this same file, now populated before the stream opens.
                        item=media.create(entry.id,audio.metadata().title(),audio.metadata().artist(),audio.metadata().album(),audio.metadata().albumArtist(),entry.path);
                        Instant expiry=audio.expiresAt().isBefore(audio.lease().expiresAt())?audio.expiresAt():audio.lease().expiresAt();
                        cache.put(entry,new Cached(local,expiry,entry,revision)); entry.key=audio.key();
                        entry.media=item;
                        // Speculative next-track work must never revoke the currently playing stream.
                        while(cache.size()>8) {
                            Entry first=cache.keySet().stream().filter(candidate->candidate!=opened).findFirst().orElseThrow();
                            retire(cache.remove(first));
                        }
                    }
                    if(submitted) { check(); request.check(); queue.update(item); }
                    return local;
                } catch(Exception failure) {
                    synchronized(cache) { Cached current=cache.get(entry); if(current!=null && current.uri().equals(local)) { cache.remove(entry); retire(current); } }
                    transport.revoke(local); throw failure;
                }
            } finally { requests.removeIf(r->{ try{r.check();return false;}catch(RuntimeException done){return true;} }); }
        }
    }
    private NativePlaylistOrder.Plan plan() {
        try { return queue.upcoming(); } catch(ReflectiveOperationException | RuntimeException ignored) { return NativePlaylistOrder.Plan.EMPTY; }
    }
    private void retire(Cached cached) { transport.revoke(cached.uri()); if(cached.entry().cover!=null) cached.entry().cover.clear(); }
    @Override public void close() {
        closed=true; requests.forEach(RequestScope::close); requests.clear();
        for(Entry entry:entries) { if(entry.lyrics!=null) entry.lyrics.close(); if(persistent==null) adapter.unregister(entry.path); }
        synchronized(cache) {
            if(preloader!=null) preloader.close();
            cache.values().forEach(c->transport.revoke(c.uri())); cache.clear();
            entries.forEach(e->{if(e.cover!=null) e.cover.close();});
        }
    }
}
