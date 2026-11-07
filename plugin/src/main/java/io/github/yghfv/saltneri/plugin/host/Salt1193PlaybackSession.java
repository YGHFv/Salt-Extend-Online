package io.github.yghfv.saltneri.plugin.host;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.transport.LoopbackRangeServer;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.Audio;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Explicit, account-scoped assembly. Merely constructing it never resolves the audio singleton. */
public final class Salt1193PlaybackSession implements AutoCloseable {
    public record Metadata(String title, String artist, String album, String albumArtist, byte[] coverPng) {
        public Metadata(String title, String artist, String album, String albumArtist) { this(title, artist, album, albumArtist, null); }
        public Metadata {
            Objects.requireNonNull(title);
            Objects.requireNonNull(artist);
            Objects.requireNonNull(album);
            Objects.requireNonNull(albumArtist);
            if (coverPng != null) {
                if (coverPng.length > PlaybackCoverFile.MAX_PNG_BYTES) throw new IllegalArgumentException("Cover too large");
                coverPng = coverPng.clone();
            }
        }
        @Override public byte[] coverPng() { return coverPng == null ? null : coverPng.clone(); }
    }

    @FunctionalInterface public interface QueueSink {
        /** Call on the host's appropriate execution context, only after an explicit user action. */
        void addToPlay(Object mediaItem) throws ReflectiveOperationException;
        default void replace(java.util.List<Object> items) throws ReflectiveOperationException { throw new UnsupportedOperationException("Queue replacement unavailable"); }
        default int firstIndex(int size) throws ReflectiveOperationException { return 0; }
        default void replace(java.util.List<Object> items,int index) throws ReflectiveOperationException {
            if(index!=0) throw new UnsupportedOperationException("Indexed queue replacement unavailable");
            replace(items);
        }
        default NativePlaylistOrder.Plan upcoming() throws ReflectiveOperationException { return NativePlaylistOrder.Plan.EMPTY; }
        default void update(Object item) throws ReflectiveOperationException { }
    }
    @FunctionalInterface public interface Renewer {
        Audio resolve(RequestScope request) throws Exception;
        default Object revision() { return null; }
    }

    public final class PreparedTrack implements AutoCloseable {
        private final TrackKey key;
        private final String path;
        private URI localUri;
        private final Object mediaItem;
        private java.time.Instant expiresAt;
        private final PlaybackCoverFile cover;
        private final OnlineLyrics.Binding lyrics;
        private final Renewer renewer;
        private final Object resolveLock = new Object();
        private volatile boolean retired;
        private volatile RequestScope request;
        private Object qualityRevision;
        private PreparedTrack(TrackKey key, String path, URI localUri, Object mediaItem, java.time.Instant expiresAt, PlaybackCoverFile cover, OnlineLyrics.Binding lyrics, Renewer renewer) {
            this.key = key; this.path = path; this.localUri = localUri; this.mediaItem = mediaItem;
            this.expiresAt = expiresAt;
            this.cover = cover;
            this.lyrics = lyrics;
            this.renewer = renewer;
            this.qualityRevision=renewer==null?null:renewer.revision();
        }
        public TrackKey key() { return key; }
        public String path() { return path; }
        public Object mediaItem() { return mediaItem; }
        @Override public void close() { retire(this); }
        @Override public String toString() { return "PreparedTrack[" + key.stableId() + "]"; }
    }

    private final String accountScope;
    private final BooleanSupplier authorized;
    private final Salt1193Probe.MediaFactory media;
    private final StreamBinding adapter;
    private final LoopbackRangeServer transport;
    private final boolean ownsResources;
    private final ScheduledExecutorService revocations;
    private final Map<String, PreparedTrack> tracks = new HashMap<>();
    private final java.util.LinkedHashMap<PreparedTrack,Boolean> cached = new java.util.LinkedHashMap<>(16,0.75f,true);
    private volatile boolean closed;

    public static Salt1193PlaybackSession open(ClassLoader loader, Salt1193Probe.Result profile,
                                              String accountScope, BooleanSupplier authorized) throws IOException,
            ReflectiveOperationException {
        requireProfile(profile);
        Objects.requireNonNull(authorized);
        if (!authorized.getAsBoolean()) throw new SecurityException("CLASS_TRANSFORM not granted");
        LoopbackRangeServer transport = new LoopbackRangeServer(authorized);
        try {
            Salt1193StreamAdapter adapter = new Salt1193StreamAdapter(loader, profile, authorized,
                    Salt1193StreamAdapter.nativeFactory(loader));
            try { return new Salt1193PlaybackSession(profile, accountScope, authorized, adapter, transport); }
            catch (RuntimeException failure) { adapter.close(); throw failure; }
        } catch (ReflectiveOperationException | RuntimeException failure) { transport.close(); throw failure; }
    }

    /** Ownership of adapter and transport transfers on successful construction. Allows offline harnesses. */
    public Salt1193PlaybackSession(Salt1193Probe.Result profile, String accountScope, BooleanSupplier authorized,
                                   StreamBinding adapter, LoopbackRangeServer transport) {
        this(profile, accountScope, authorized, adapter, transport, true);
    }
    /** Shared sessions revoke only their own mappings; the armed runtime owns adapter/listener lifetime. */
    public Salt1193PlaybackSession(Salt1193Probe.Result profile, String accountScope, BooleanSupplier authorized,
                                   StreamBinding adapter, LoopbackRangeServer transport, boolean ownsResources) {
        requireProfile(profile);
        if (Objects.requireNonNull(accountScope).isBlank()) throw new IllegalArgumentException("Missing account scope");
        this.accountScope = accountScope;
        this.authorized = Objects.requireNonNull(authorized);
        this.adapter = Objects.requireNonNull(adapter);
        this.transport = Objects.requireNonNull(transport);
        this.ownsResources = ownsResources;
        this.media = profile.mediaFactory();
        if (!permitted()) throw new SecurityException("CLASS_TRANSFORM not granted");
        revocations = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "salt-neri-permission-watch");
            thread.setDaemon(true); return thread;
        });
        revocations.scheduleWithFixedDelay(() -> {
            if (!permitted()) {
                // A failed authorization lookup is also a revocation; never keep serving credentials.
                try { close(); } catch (RuntimeException ignored) { }
            } else retireExpired();
        }, 250, 250, TimeUnit.MILLISECONDS);
    }

    public synchronized PreparedTrack prepare(TrackKey key, Metadata metadata, MediaLease lease)
            throws ReflectiveOperationException {
        return prepare(key,metadata,lease,null);
    }
    public synchronized PreparedTrack prepare(TrackKey key, Metadata metadata, MediaLease lease, OnlineLyrics.Source lyricSource)
            throws ReflectiveOperationException {
        return prepare(key,metadata,lease,lyricSource,null);
    }
    public synchronized PreparedTrack prepare(TrackKey key, Metadata metadata, MediaLease lease, OnlineLyrics.Source lyricSource, Renewer renewer)
            throws ReflectiveOperationException {
        requireActive();
        Objects.requireNonNull(key);
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(lease);
        if (!lease.belongsTo(accountScope) || !lease.platform().name().equals(key.platform().name())) {
            throw new IllegalArgumentException("Media lease platform/account mismatch");
        }
        retireExpired();
        if (tracks.size() >= (renewer==null?64:10000)) throw new IllegalStateException("Queue item limit reached");
        URI local = transport.register(lease);
        // Each preparation keeps its logical identity; only its account-scoped expiring URL is renewed.
        String nonce = UUID.randomUUID().toString();
        PlaybackCoverFile cover = null;
        if (metadata.coverPng() != null) {
            try { cover = PlaybackCoverFile.create(metadata.coverPng()); }
            catch (IOException ignored) { /* Artwork failure must not prevent explicit audio playback. */ }
        }
        String path = cover == null ? "saltneri://media/" + nonce : cover.path();
        OnlineLyrics.Binding lyrics = null;
        try {
            String id="saltneri:" + key.stableId() + ":" + nonce;
            Object item = media.create(id, metadata.title(), metadata.artist(), metadata.album(),
                    metadata.albumArtist(), path);
            if(lyricSource!=null) lyrics=OnlineLyrics.bind(id,path,()->!closed && permitted(),lyricSource);
            requireActive();
            PreparedTrack track = new PreparedTrack(key, path, local, item, lease.expiresAt(), cover, lyrics, renewer);
            if(renewer==null) adapter.register(path,local);
            else adapter.registerDeferred(path,()->resolve(track));
            tracks.put(path, track);
            if(renewer!=null) { cached.put(track,Boolean.TRUE); trimCache(); }
            return track;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            adapter.unregister(path);
            transport.revoke(local);
            if (cover != null) cover.close();
            if (lyrics != null) lyrics.close();
            throw failure;
        }
    }

    public synchronized ReversibleInterceptorLists attach(Object bassPlayer) throws ReflectiveOperationException {
        requireActive();
        return adapter.attach(bassPlayer);
    }

    public synchronized void enqueue(PreparedTrack track, QueueSink queue) throws ReflectiveOperationException {
        requireActive();
        if (track == null || tracks.get(track.path) != track) throw new IllegalArgumentException("Foreign or retired track");
        if (track.renewer==null && !transport.now().isBefore(track.expiresAt)) { retire(track); throw new IllegalStateException("Prepared audio lease expired"); }
        if (!adapter.isAttached()) throw new IllegalStateException("Native stream adapter not attached to an audio engine");
        Objects.requireNonNull(queue).addToPlay(track.mediaItem);
    }

    public synchronized void revokeAccount(String scope) {
        if (accountScope.equals(scope)) close();
    }

    public synchronized boolean isClosed() { return closed; }
    private synchronized void retireExpired() {
        for (PreparedTrack track : java.util.List.copyOf(tracks.values())) if (!transport.now().isBefore(track.expiresAt)) {
            if(track.renewer==null) retire(track);
            else if(track.localUri!=null) { cached.remove(track); releaseLease(track); }
        }
    }

    private URI resolve(PreparedTrack track) throws Exception {
        synchronized(track.resolveLock) {
            synchronized(this) {
                requireTrack(track);
                if(track.localUri!=null && Objects.equals(track.qualityRevision,track.renewer.revision()) && transport.now().plusSeconds(10).isBefore(track.expiresAt)) {
                    cached.get(track); return track.localUri;
                }
                cached.remove(track); releaseLease(track);
            }
            // Never hold the session lock across upstream I/O; logout must cancel immediately.
            try(RequestScope request=new RequestScope(()->!closed && !track.retired && permitted())) {
                Object qualityRevision=track.renewer.revision();
                track.request=request; request.check(); Audio audio=track.renewer.resolve(request); request.check();
                if(!audio.key().equals(track.key) || !audio.lease().belongsTo(accountScope)
                        || !audio.lease().platform().name().equals(track.key.platform().name())) throw new SecurityException("Replay identity mismatch");
                synchronized(this) {
                    requireTrack(track); request.check();
                    if(!transport.now().isBefore(audio.expiresAt())) throw new IllegalStateException("Renewed audio already expired");
                    URI local=transport.register(audio.lease());
                    track.localUri=local;
                    track.qualityRevision=qualityRevision;
                    track.expiresAt=audio.expiresAt().isBefore(audio.lease().expiresAt())?audio.expiresAt():audio.lease().expiresAt();
                    if(track.cover!=null && audio.metadata().coverPng()!=null) {
                        try { track.cover.replace(audio.metadata().coverPng()); } catch(IOException ignored) { }
                    }
                    cached.put(track,Boolean.TRUE); trimCache(); return local;
                }
            } finally { track.request=null; }
        }
    }
    private void requireTrack(PreparedTrack track) {
        requireActive(); if(track.retired || tracks.get(track.path)!=track) throw new java.util.concurrent.CancellationException("Queue item retired");
    }
    private void trimCache() {
        while(cached.size()>8) { var oldest=cached.keySet().iterator().next(); cached.remove(oldest); releaseLease(oldest); }
    }
    private void releaseLease(PreparedTrack track) {
        if(track.localUri!=null) { transport.revoke(track.localUri); track.localUri=null; if(track.cover!=null) track.cover.clear(); }
    }

    private synchronized void retire(PreparedTrack track) {
        if (tracks.remove(track.path, track)) {
            track.retired=true; RequestScope pending=track.request; if(pending!=null) pending.close();
            cached.remove(track);
            if(track.lyrics!=null) track.lyrics.close();
            try { adapter.unregister(track.path); } finally { if(track.localUri!=null) transport.revoke(track.localUri); if (track.cover != null) track.cover.close(); }
        }
    }

    /** Lazy native queue port; no initialization until its addToPlay method is explicitly invoked. */
    public static QueueSink nativeQueue(ClassLoader loader, Salt1193Probe.Result profile, BooleanSupplier authorized) {
        requireProfile(profile);
        Objects.requireNonNull(authorized);
        return new QueueSink() {
          private Object controller() throws ReflectiveOperationException {
            if (!authorized.getAsBoolean()) throw new SecurityException("CLASS_TRANSFORM not granted");
            return Class.forName("com.xuncorp.voxzen.service.PlaybackController",false,loader).getField("INSTANCE").get(null);
          }
          public void replace(java.util.List<Object> items) throws ReflectiveOperationException {
            replaceAndPlay(controller(),items,authorized);
          }
          private Object queueState() throws ReflectiveOperationException {
            Object instance=controller(); Object flow=instance.getClass().getMethod("getPlaybackQueueState").invoke(instance);
            return ((kotlinx.coroutines.flow.StateFlow<?>)flow).getValue();
          }
          public int firstIndex(int size) throws ReflectiveOperationException {
            return NativePlaylistOrder.firstIndex(NativePlaylistOrder.mode(queueState()),size,
                    bound->java.util.concurrent.ThreadLocalRandom.current().nextInt(bound));
          }
          public NativePlaylistOrder.Plan upcoming() throws ReflectiveOperationException { return NativePlaylistOrder.read(queueState()); }
          public void replace(java.util.List<Object> items,int index) throws ReflectiveOperationException { replaceAndPlay(controller(),items,index,authorized); }
          public void update(Object item) throws ReflectiveOperationException {
            Object instance=controller(); Class<?> media=Class.forName("com.xuncorp.pisces.PiscesMediaItem",false,loader);
            Object array=java.lang.reflect.Array.newInstance(media,1); java.lang.reflect.Array.set(array,0,item);
            instance.getClass().getMethod("updateMediaItems",array.getClass()).invoke(instance,array);
            // This command is asynchronous. Refreshing here can still read the previous track;
            // Salt's onLoaded reads the stable artwork path once the stream actually opens.
          }
          public void addToPlay(Object item) throws ReflectiveOperationException {
            if (!authorized.getAsBoolean()) throw new SecurityException("CLASS_TRANSFORM not granted");
            Class<?> controller = Class.forName("com.xuncorp.voxzen.service.PlaybackController", false, loader);
            Class<?> media = Class.forName("com.xuncorp.pisces.PiscesMediaItem", false, loader);
            if (!media.isInstance(item)) throw new IllegalArgumentException("Not a native media item");
            Object instance = controller.getField("INSTANCE").get(null);
            submitAndPlay(instance, media, item, authorized);
          }
        };
    }

    static void replaceAndPlay(Object controller, java.util.List<Object> items, BooleanSupplier authorized) throws ReflectiveOperationException {
        replaceAndPlay(controller,items,0,authorized);
    }
    static void replaceAndPlay(Object controller, java.util.List<Object> items,int index, BooleanSupplier authorized) throws ReflectiveOperationException {
        if (items.isEmpty() || items.size()>10000) throw new IllegalArgumentException("Invalid queue size");
        Objects.checkIndex(index,items.size());
        if (!authorized.getAsBoolean()) throw new SecurityException("Queue permission revoked");
        try {
            controller.getClass().getMethod("setPlaybackQueue",java.util.List.class,int.class).invoke(controller,java.util.List.copyOf(items),index);
            if (!authorized.getAsBoolean()) throw new SecurityException("Queue permission revoked");
            controller.getClass().getMethod("play").invoke(controller);
        } catch (InvocationTargetException failure) { throw new ReflectiveOperationException("Native queue replacement failed"); }
    }

    static void submitAndPlay(Object controller, Class<?> media, Object item, BooleanSupplier authorized)
            throws ReflectiveOperationException {
        if (!authorized.getAsBoolean()) throw new SecurityException("CLASS_TRANSFORM revoked");
        try {
            // Both commands enter the same FIFO in Salt 1.19.3; never use the play/pause toggle.
            controller.getClass().getMethod("addToPlay", media).invoke(controller, item);
            if (!authorized.getAsBoolean()) throw new SecurityException("CLASS_TRANSFORM revoked");
            controller.getClass().getMethod("play").invoke(controller);
        } catch (InvocationTargetException failure) {
            // Internal host exceptions can include paths; do not publish causes to the plugin UI.
            throw new ReflectiveOperationException("Native queue submission failed");
        }
    }

    private static void requireProfile(Salt1193Probe.Result profile) {
        if (!Objects.requireNonNull(profile).matched() || profile.mediaFactory() == null) {
            throw new IllegalArgumentException("Host profile not verified");
        }
    }

    private boolean permitted() {
        try { return authorized.getAsBoolean(); }
        catch (RuntimeException failure) { return false; }
    }

    private void requireActive() {
        if (closed) throw new IllegalStateException("Playback session stopped");
        if (!permitted()) {
            try { close(); } catch (RuntimeException ignored) { }
            throw new SecurityException("CLASS_TRANSFORM revoked");
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        revocations.shutdownNow();
        for (PreparedTrack track : java.util.List.copyOf(tracks.values())) retire(track);
        if (ownsResources) { try { adapter.close(); } finally { transport.close(); } }
        // Shared transports can contain another platform's anonymous items with the same scope.
        // retire() revoked only this session's exact leases.
    }
}
