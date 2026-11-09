package io.github.yghfv.saltneri.plugin.host;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.Audio;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.transport.LoopbackRangeServer;
import java.net.URI;
import java.util.*;
import java.util.function.BooleanSupplier;
import io.github.yghfv.saltneri.plugin.ui.CoverMode;
import io.github.yghfv.saltneri.plugin.ui.CoverLoader;
import java.util.concurrent.*;

/** Saved native playlists survive page changes/restarts; only leases are account-scoped. */
public final class SavedTrackSession implements AutoCloseable {
    public interface Resolver {
        Audio resolve(SavedTrackStore.Entry entry, RequestScope request) throws Exception;
        Object revision(AccountPlatform platform);
        default OnlineLyrics.Source lyrics(SavedTrackStore.Entry entry) { return null; }
        default byte[] cover(URI source,CoverMode mode,RequestScope request) throws Exception {
            try(var loader=new CoverLoader()) {return loader.loadPlayback(source,request,mode);}
        }
    }
    private static final class Binding {
        final SavedTrackStore.Entry entry;
        OnlineLyrics.Binding lyrics;
        RequestScope request;
        RequestScope coverRequest;
        URI coverSource;
        CoverMode coverMode=CoverMode.FIT;
        int coverAttempts;
        long coverRetryAt;
        long coverRevision;
        boolean coverPending;
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
    private PlaylistPrefetch prefetch;
    private Salt1193PlaybackSession.QueueSink queue;
    private final ScheduledExecutorService coverWatch;
    private final ThreadPoolExecutor coverWorker;
    private Object notifiedCover;
    private PlaybackCoverRefresh.Selection selectedCover;
    private int coverPending;
    public synchronized boolean contains(String id,String path) {
        Binding binding=bindings.get(path);return binding!=null && binding.entry.trackId().equals(id);
    }
    public synchronized void prefetch(Salt1193PlaybackSession.QueueSink queue) {
        this.queue=queue;
        if(prefetch!=null)return;
        prefetch=new PlaylistPrefetch(()->{
            try{return queue.upcoming();}catch(ReflectiveOperationException | RuntimeException ignored){return NativePlaylistOrder.Plan.EMPTY;}
        },new PlaylistPrefetch.Source() {
            private Binding binding(String id) {
                synchronized(SavedTrackSession.this) {
                    return bindings.values().stream().filter(b->b.entry.trackId().equals(id)).findFirst().orElse(null);
                }
            }
            public boolean contains(String id) {return binding(id)!=null;}
            public URI resolve(String id,BooleanSupplier valid) throws Exception {
                Binding binding=binding(id);if(binding==null)throw new java.util.concurrent.CancellationException();
                return SavedTrackSession.this.resolve(binding,valid);
            }
            public Object revision() {return Arrays.stream(AccountPlatform.values()).map(resolver::revision).toList();}
        },transport,()->!closed && active.getAsBoolean());
    }
    public SavedTrackSession(StreamBinding adapter,LoopbackRangeServer transport,BooleanSupplier active,Resolver resolver) {
        this.adapter=adapter; this.transport=transport; this.active=active; this.resolver=resolver;
        coverWorker=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{
            Thread thread=new Thread(r,"salt-saved-cover-retry");thread.setDaemon(true);return thread;
        });
        coverWatch=Executors.newSingleThreadScheduledExecutor(r->{
            Thread thread=new Thread(r,"salt-saved-cover-selection");thread.setDaemon(true);return thread;
        });
        coverWatch.scheduleWithFixedDelay(this::watchCover,100,250,TimeUnit.MILLISECONDS);
    }
    private void watchCover() {
        try {
            Salt1193PlaybackSession.QueueSink sink;
            synchronized(this) {if(closed || !active.getAsBoolean() || queue==null)return;sink=queue;}
            var selection=sink.coverSelection();
            synchronized(this) {
                if(closed || !active.getAsBoolean())return;
                Binding binding=selection==null?null:bindings.get(selection.path());
                if(binding==null || !binding.entry.trackId().equals(selection.id())) {notifiedCover=null;return;}
                if(!selection.equals(selectedCover)) {
                    boolean revisit=selectedCover!=null;
                    selectedCover=selection;
                    if(revisit && !binding.coverPending) {binding.coverAttempts=0;binding.coverRetryAt=0;}
                }
                scheduleCover(binding);
                Object key=List.of(selection,binding.generation,binding.entry.cover().hasPicture());
                if(!key.equals(notifiedCover)) {notifiedCover=key;notifyCover(binding);}
            }
        } catch(ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
    }
    private void notifyCover(Binding binding) {
        if(queue==null)return;
        long generation=binding.generation;
        Object revision=resolver.revision(binding.entry.target().platform());
        try {queue.coverChanged(binding.entry.trackId(),binding.entry.path(),
                ()->active.getAsBoolean() && valid(binding,generation,revision));}
        catch(ReflectiveOperationException | RuntimeException ignored) { }
    }
    private void scheduleCover(Binding binding) {
        if(closed || binding.coverSource==null || binding.coverPending || coverPending>=10
                || binding.coverAttempts>=3 || System.nanoTime()<binding.coverRetryAt || binding.entry.cover().hasPicture())return;
        URI source=binding.coverSource;CoverMode mode=binding.coverMode;
        long generation=binding.generation,coverRevision=binding.coverRevision;
        Object revision=resolver.revision(binding.entry.target().platform());
        binding.coverPending=true;binding.coverAttempts++;coverPending++;
        try {coverWorker.execute(()->{
            try(var request=new RequestScope(()->active.getAsBoolean() && validCover(binding,generation,revision,coverRevision),8000)) {
                synchronized(this) {request.check();binding.coverRequest=request;}
                request.check();byte[] png=resolver.cover(source,mode,request);request.check();
                if(png==null)return;
                synchronized(this) {
                    request.check();binding.entry.cover().replace(png);notifyCover(binding);
                }
            } catch(Exception | LinkageError ignored) { /* Independent artwork failure never retries or revokes audio. */ }
            finally {
                synchronized(this) {
                    binding.coverRequest=null;binding.coverPending=false;coverPending--;
                    binding.coverRetryAt=System.nanoTime()+TimeUnit.SECONDS.toNanos(binding.coverAttempts==1?1:3);
                }
            }
        });} catch(RejectedExecutionException ignored) {binding.coverPending=false;coverPending--;binding.coverAttempts--;}
    }
    public synchronized void register(SavedTrackStore.Entry entry) {
        check(); if(bindings.containsKey(entry.path())) return;
        Binding binding=new Binding(entry);
        adapter.registerDeferred(entry.path(),()->{
            synchronized(this) {
                if(opened!=binding && !binding.coverPending) {binding.coverAttempts=0;binding.coverRetryAt=0;}
                opened=binding;
            }
            return resolve(binding,()->true);
        });
        bindLyrics(binding);
        bindings.put(entry.path(),binding);
    }
    private void bindLyrics(Binding binding) {
        OnlineLyrics.Source lyrics=resolver.lyrics(binding.entry);
        if(lyrics!=null) binding.lyrics=OnlineLyrics.bind(binding.entry.trackId(),binding.entry.path(),()->!closed && active.getAsBoolean(),lyrics);
    }
    private void check() { if(closed || !active.getAsBoolean()) throw new java.util.concurrent.CancellationException("Saved playback unavailable"); }
    public URI warm(SavedTrackStore.Entry entry,BooleanSupplier valid) throws Exception {
        Binding binding;
        synchronized(this) {register(entry);binding=bindings.get(entry.path());}
        return resolve(binding,valid);
    }
    public synchronized void prime(SavedTrackStore.Entry entry,Audio audio,Object revision) {
        register(entry);validate(entry,audio);
        Binding binding=bindings.get(entry.path());
        // Never revoke an already decoding copy merely because the user inserts it again.
        if(binding==opened && cache.containsKey(binding)) return;
        retire(cache.remove(binding));publish(binding,audio,revision);
    }
    private void validate(SavedTrackStore.Entry entry,Audio audio) {
        if(!audio.key().platform().name().equals(entry.target().platform().name())
                || !audio.transportMatches()
                || !SavedTrackStore.matches(entry,audio.target()) || !transport.now().isBefore(audio.expiresAt())) throw new SecurityException("Saved target mismatch or expiry");
    }
    private URI publish(Binding binding,Audio audio,Object revision) {
        URI uri=transport.register(audio.lease());
        if(!Objects.equals(binding.coverSource,audio.coverSource()) || binding.coverMode!=audio.coverMode()) {
            binding.coverRevision++;
            if(binding.coverRequest!=null)binding.coverRequest.close();
            binding.coverAttempts=0;binding.coverRetryAt=0;
        }
        binding.coverSource=audio.coverSource();binding.coverMode=audio.coverMode();
        try {
            if(audio.metadata().coverPng()!=null) binding.entry.cover().replace(audio.metadata().coverPng());
            else binding.entry.cover().ensureFile();
        } catch(java.io.IOException ignored) { }
        notifyCover(binding);scheduleCover(binding);
        cache.put(binding,new Cached(uri,audio.expiresAt().isBefore(audio.lease().expiresAt())?audio.expiresAt():audio.lease().expiresAt(),revision));
        while(cache.size()>8) retire(cache.remove(cache.keySet().stream().filter(b->b!=opened).findFirst().orElseThrow()));
        return uri;
    }
    private URI resolve(Binding binding,BooleanSupplier selectionValid) throws Exception {
        synchronized(binding) {
            long generation; Object revision=resolver.revision(binding.entry.target().platform());
            synchronized(this) {
                check(); if(!selectionValid.getAsBoolean()) throw new java.util.concurrent.CancellationException(); generation=binding.generation;
                Cached cached=cache.get(binding);
                if(cached!=null && Objects.equals(revision,cached.revision()) && transport.isAvailable(cached.uri())
                        && transport.now().plusSeconds(Math.max(30,binding.entry.duration()/1000+30)).isBefore(cached.expiry())) {
                    scheduleCover(binding);notifyCover(binding);return cached.uri();
                }
                retire(cache.remove(binding));
            }
            try(RequestScope request=new RequestScope(()->active.getAsBoolean() && selectionValid.getAsBoolean() && valid(binding,generation,revision))) {
                synchronized(this) { check(); binding.request=request; }
                request.check(); Audio audio=resolver.resolve(binding.entry,request); request.check();
                validate(binding.entry,audio);
                synchronized(this) {
                    check(); request.check();
                    return publish(binding,audio,revision);
                }
            } finally { synchronized(this) {binding.request=null;} }
        }
    }
    private synchronized boolean valid(Binding binding,long generation,Object revision) {
        return !closed && binding.generation==generation && Objects.equals(revision,resolver.revision(binding.entry.target().platform()));
    }
    private synchronized boolean validCover(Binding binding,long generation,Object revision,long coverRevision) {
        return binding.coverRevision==coverRevision && valid(binding,generation,revision);
    }
    private void retire(Cached cached) { if(cached!=null) transport.revoke(cached.uri()); }
    public synchronized void revoke(AccountPlatform platform) {
        for(Binding binding:bindings.values()) if(binding.entry.target().platform()==platform) {
            binding.generation++; if(binding.request!=null) binding.request.close(); retire(cache.remove(binding));
            if(binding.coverRequest!=null)binding.coverRequest.close();
            binding.coverSource=null;binding.coverAttempts=0;binding.coverRetryAt=0;
            if(binding.lyrics!=null) {binding.lyrics.close();binding.lyrics=null;bindLyrics(binding);}
        }
    }
    @Override public synchronized void close() {
        if(closed) return; closed=true;
        coverWatch.shutdownNow();coverWorker.shutdownNow();
        if(prefetch!=null)prefetch.close();
        for(Binding binding:bindings.values()) {
            if(binding.request!=null) binding.request.close();
            if(binding.coverRequest!=null)binding.coverRequest.close();
            if(binding.lyrics!=null) binding.lyrics.close(); adapter.unregister(binding.entry.path());
        }
        cache.values().forEach(this::retire); cache.clear(); bindings.clear();
    }
}
