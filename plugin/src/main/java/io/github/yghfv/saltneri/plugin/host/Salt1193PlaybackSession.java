package io.github.yghfv.saltneri.plugin.host;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.transport.LoopbackRangeServer;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
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
        default void update(Object item) throws ReflectiveOperationException { }
    }

    public final class PreparedTrack implements AutoCloseable {
        private final TrackKey key;
        private final String path;
        private final URI localUri;
        private final Object mediaItem;
        private final java.time.Instant expiresAt;
        private final PlaybackCoverFile cover;
        private PreparedTrack(TrackKey key, String path, URI localUri, Object mediaItem, java.time.Instant expiresAt, PlaybackCoverFile cover) {
            this.key = key; this.path = path; this.localUri = localUri; this.mediaItem = mediaItem;
            this.expiresAt = expiresAt;
            this.cover = cover;
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
    private boolean closed;

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
        requireActive();
        Objects.requireNonNull(key);
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(lease);
        if (!lease.belongsTo(accountScope) || !lease.platform().name().equals(key.platform().name())) {
            throw new IllegalArgumentException("Media lease platform/account mismatch");
        }
        retireExpired();
        if (tracks.size() >= 64) throw new IllegalStateException("Retire unused tracks before preparing more");
        URI local = transport.register(lease);
        // Each preparation gets a fresh path. Old queued items cannot silently rebind to a new account/URL.
        String nonce = UUID.randomUUID().toString();
        PlaybackCoverFile cover = null;
        if (metadata.coverPng() != null) {
            try { cover = PlaybackCoverFile.create(metadata.coverPng()); }
            catch (IOException ignored) { /* Artwork failure must not prevent explicit audio playback. */ }
        }
        String path = cover == null ? "saltneri://media/" + nonce : cover.path();
        try {
            adapter.register(path, local);
            Object item = media.create("saltneri:" + key.stableId() + ":" + nonce, metadata.title(), metadata.artist(), metadata.album(),
                    metadata.albumArtist(), path);
            requireActive();
            PreparedTrack track = new PreparedTrack(key, path, local, item, lease.expiresAt(), cover);
            tracks.put(path, track);
            return track;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            adapter.unregister(path);
            transport.revoke(local);
            if (cover != null) cover.close();
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
        if (!transport.now().isBefore(track.expiresAt)) { retire(track); throw new IllegalStateException("Prepared audio lease expired"); }
        if (!adapter.isAttached()) throw new IllegalStateException("Native stream adapter not attached to an audio engine");
        Objects.requireNonNull(queue).addToPlay(track.mediaItem);
    }

    public synchronized void revokeAccount(String scope) {
        if (accountScope.equals(scope)) close();
    }

    public synchronized boolean isClosed() { return closed; }
    private synchronized void retireExpired() {
        for (PreparedTrack track : java.util.List.copyOf(tracks.values())) if (!transport.now().isBefore(track.expiresAt)) retire(track);
    }

    private synchronized void retire(PreparedTrack track) {
        if (tracks.remove(track.path, track)) {
            try { adapter.unregister(track.path); } finally { transport.revoke(track.localUri); if (track.cover != null) track.cover.close(); }
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
          public void update(Object item) throws ReflectiveOperationException {
            Object instance=controller(); Class<?> media=Class.forName("com.xuncorp.pisces.PiscesMediaItem",false,loader);
            Object array=java.lang.reflect.Array.newInstance(media,1); java.lang.reflect.Array.set(array,0,item);
            instance.getClass().getMethod("updateMediaItems",array.getClass()).invoke(instance,array);
            instance.getClass().getMethod("refreshCurrentCover").invoke(instance);
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
        if (items.isEmpty() || items.size()>10000) throw new IllegalArgumentException("Invalid queue size");
        if (!authorized.getAsBoolean()) throw new SecurityException("Queue permission revoked");
        try {
            controller.getClass().getMethod("setPlaybackQueue",java.util.List.class,int.class).invoke(controller,java.util.List.copyOf(items),0);
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
        else transport.revokeAccount(accountScope);
    }
}
