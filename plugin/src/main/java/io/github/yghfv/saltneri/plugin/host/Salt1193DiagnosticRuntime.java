package io.github.yghfv.saltneri.plugin.host;

import com.xuncorp.spw.workshop.api.hook.HookRegistrar;
import io.github.yghfv.saltneri.plugin.transport.LoopbackRangeServer;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import io.github.yghfv.saltneri.core.TrackKey;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** User-armed local diagnostic. Does not request platform media or modify the queue at startup. */
public final class Salt1193DiagnosticRuntime implements AutoCloseable {
    private final Salt1193Probe.MediaFactory media;
    private final Salt1193Probe.Result profile;
    private final StreamBinding adapter;
    private final LoopbackRangeServer transport;
    private final Salt1193EngineLink engine;
    private final Salt1193PlaybackSession.QueueSink queue;
    private final BooleanSupplier authorized;
    private final ScheduledExecutorService watchdog;
    private URI toneUri;
    private String tonePath;
    private volatile boolean closed;
    private java.util.function.Supplier<io.github.yghfv.saltneri.plugin.PlaybackSettings> settings=()->io.github.yghfv.saltneri.plugin.PlaybackSettings.DEFAULT;
    public void settings(java.util.function.Supplier<io.github.yghfv.saltneri.plugin.PlaybackSettings> source) { settings=Objects.requireNonNull(source); }
    private java.util.function.Function<io.github.yghfv.saltneri.plugin.auth.AccountPlatform,io.github.yghfv.saltneri.plugin.auth.AccountCredentials> accounts=p->null;
    public void accounts(java.util.function.Function<io.github.yghfv.saltneri.plugin.auth.AccountPlatform,io.github.yghfv.saltneri.plugin.auth.AccountCredentials> source) {accounts=Objects.requireNonNull(source);}
    public void cacheLimit(long bytes) { transport.cacheLimit(bytes); }
    public void clearCache() { transport.clearCache(); }
    public long cacheBytes() { return transport.cacheBytes(); }
    private LazyPlaylistSession playlist;
    private SavedTrackSession savedTracks;
    private SavedTrackStore savedStore;
    private SavedTrackSession.Resolver savedResolver;
    private AutomaticPlaybackRecovery recovery;
    private java.util.function.Consumer<String> errorToast=text->{};
    public void errorToast(java.util.function.Consumer<String> notifier) {errorToast=Objects.requireNonNull(notifier);}
    public synchronized void savedTracks(SavedTrackStore store,SavedTrackSession.Resolver resolver) {
        if(closed || !permitted()) throw new IllegalStateException("Adapter unavailable");
        if(savedTracks==null) {savedResolver=resolver;savedStore=store;savedTracks=new SavedTrackSession(adapter,transport,()->!closed && permitted(),resolver);}
        store.entries().forEach(savedTracks::register);
        savedTracks.prefetch(queue);
    }
    public synchronized void submitOnline(io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.Audio audio,
                                         BooleanSupplier accountActive,io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.QueueAction action) throws Exception {
        if(closed || !permitted() || !accountActive.getAsBoolean() || savedTracks==null) throw new IllegalStateException("Persistent playback unavailable");
        engine.ensureAttached();
        var entry=savedStore.save(audio.target(),new io.github.yghfv.saltneri.plugin.library.Catalog.Item(
                io.github.yghfv.saltneri.plugin.library.Catalog.Kind.TRACK,audio.target().id(),audio.metadata().title(),audio.metadata().artist(),0,true));
        if(!accountActive.getAsBoolean()) throw new SecurityException("Account changed");
        savedTracks.prime(entry,audio,savedResolver.revision(entry.target().platform()));
        Object item=media.create(entry.trackId(),audio.metadata().title(),audio.metadata().artist(),audio.metadata().album(),audio.metadata().albumArtist(),entry.path());
        if(!accountActive.getAsBoolean()) throw new SecurityException("Account changed");
        if(action==io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.QueueAction.PLAY) queue.addToPlay(item);else queue.insert(item,action);
    }
    private MediaLease.Platform playlistPlatform;
    private final java.util.Map<String, Salt1193PlaybackSession> sessions = new java.util.HashMap<>();
    private final java.util.Map<String, MediaLease.Platform> sessionPlatforms = new java.util.HashMap<>();

    public static Salt1193DiagnosticRuntime open(ClassLoader loader, Salt1193Probe.Result profile,
                                                HookRegistrar registrar, BooleanSupplier authorized)
            throws IOException, ReflectiveOperationException {
        if (!profile.matched() || profile.mediaFactory() == null) throw new IllegalArgumentException("Host profile not verified");
        if (!authorized.getAsBoolean()) throw new SecurityException("CLASS_TRANSFORM not granted");
        LoopbackRangeServer transport = new LoopbackRangeServer(authorized);
        Salt1193StreamAdapter adapter = null;
        try {
            adapter = new Salt1193StreamAdapter(loader, profile, authorized, Salt1193StreamAdapter.nativeFactory(loader, transport::revoke));
            Salt1193DiagnosticRuntime runtime=new Salt1193DiagnosticRuntime(profile, adapter, transport,
                    Salt1193EngineLink.nativeHost(loader, profile), Salt1193EngineLink.hooks(registrar),
                    Salt1193PlaybackSession.nativeQueue(loader, profile, authorized,registrar), authorized);
            try {
                runtime.recovery=new AutomaticPlaybackRecovery(AutomaticPlaybackRecovery.nativeHost(loader,profile),
                        Salt1193EngineLink.hooks(registrar),()->!runtime.closed && authorized.getAsBoolean(),
                        (id,path)->runtime.savedTracks!=null && runtime.savedTracks.contains(id,path),text->runtime.errorToast.accept(text));
                adapter.recovery(runtime.recovery);
                return runtime;
            } catch(ReflectiveOperationException | RuntimeException | LinkageError failure) {runtime.close();throw failure;}
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            try { if (adapter != null) adapter.close(); } finally { transport.close(); }
            throw failure;
        }
    }

    /** Transfers ownership on success; explicit injection keeps native audio out of offline tests. */
    public Salt1193DiagnosticRuntime(Salt1193Probe.Result profile, StreamBinding adapter, LoopbackRangeServer transport,
                                     Salt1193EngineLink.HostAccess host, Salt1193EngineLink.HookInstaller hooks,
                                     Salt1193PlaybackSession.QueueSink queue, BooleanSupplier authorized)
            throws ReflectiveOperationException {
        if (!profile.matched() || profile.mediaFactory() == null) throw new IllegalArgumentException("Host profile not verified");
        this.profile = profile; this.media = profile.mediaFactory();
        this.adapter = Objects.requireNonNull(adapter);
        this.transport = Objects.requireNonNull(transport);
        transport.onRevoke(adapter::revoke);
        this.queue = Objects.requireNonNull(queue);
        this.authorized = Objects.requireNonNull(authorized);
        this.engine = new Salt1193EngineLink(profile, adapter, host, authorized);
        try { engine.connect(hooks); }
        catch (ReflectiveOperationException | RuntimeException failure) { transport.close(); throw failure; }
        watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "salt-neri-diagnostic-permission");
            thread.setDaemon(true); return thread;
        });
        watchdog.scheduleWithFixedDelay(() -> {
            if (!permitted() || engine.isClosed()) { try { close(); } catch (RuntimeException ignored) { } }
        }, 250, 250, TimeUnit.MILLISECONDS);
    }

    /** Explicit queue-changing action. Submission is not proof that the stream actually became audible. */
    public synchronized void playTone() throws ReflectiveOperationException {
        if (closed) throw new IllegalStateException("Diagnostic adapter stopped");
        if (!permitted()) { close(); throw new SecurityException("CLASS_TRANSFORM revoked"); }
        try { engine.ensureAttached(); }
        catch (ReflectiveOperationException | RuntimeException failure) { close(); throw failure; }
        URI local = transport.registerDiagnosticTone();
        String path = "saltneri://diagnostic/" + UUID.randomUUID();
        try {
            adapter.register(path, local);
            Object item = media.create(path, "Salt Neri - 2 second test tone", "Local diagnostic", "Not platform audio", "", path);
            if (!permitted()) throw new SecurityException("CLASS_TRANSFORM revoked");
            queue.addToPlay(item);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            adapter.unregister(path); transport.revoke(local); throw failure;
        }
        if (tonePath != null) { adapter.unregister(tonePath); transport.revoke(toneUri); }
        tonePath = path;
        toneUri = local;
    }

    public boolean isClosed() { return closed; }
    /** Explicit platform queue submission. Preparation/resolution elsewhere never calls this method. */
    public synchronized void playTrack(TrackKey key, Salt1193PlaybackSession.Metadata metadata, MediaLease lease,
                                       BooleanSupplier accountActive) throws ReflectiveOperationException {
        playTrack(key,metadata,lease,accountActive,null);
    }
    public synchronized void playTrack(TrackKey key, Salt1193PlaybackSession.Metadata metadata, MediaLease lease,
                                       BooleanSupplier accountActive, OnlineLyrics.Source lyrics) throws ReflectiveOperationException {
        playTrack(key,metadata,lease,accountActive,lyrics,null);
    }
    public synchronized void playTrack(TrackKey key, Salt1193PlaybackSession.Metadata metadata, MediaLease lease,
                                       BooleanSupplier accountActive, OnlineLyrics.Source lyrics, Salt1193PlaybackSession.Renewer renewer) throws ReflectiveOperationException {
        submitTrack(key, metadata, lease, accountActive, lyrics, renewer,
                io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.QueueAction.PLAY);
    }
    public synchronized void submitTrack(TrackKey key, Salt1193PlaybackSession.Metadata metadata, MediaLease lease,
                                       BooleanSupplier accountActive, OnlineLyrics.Source lyrics, Salt1193PlaybackSession.Renewer renewer,
                                       io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.QueueAction action) throws ReflectiveOperationException {
        if (closed || !permitted()) throw new IllegalStateException("Arm the experimental adapter first");
        if (!accountActive.getAsBoolean()) throw new SecurityException("Account revision revoked");
        engine.ensureAttached();
        sessions.entrySet().removeIf(entry -> { if (entry.getValue().isClosed()) { sessionPlatforms.remove(entry.getKey()); return true; } return false; });
        String sessionId=lease.platform().name()+":"+lease.scope();
        Salt1193PlaybackSession session = sessions.get(sessionId);
        if (session == null) {
            if (sessions.size() >= 5) throw new IllegalStateException("Too many active account sessions");
            session = new Salt1193PlaybackSession(profile, lease.scope(), () -> permitted() && accountActive.getAsBoolean(), adapter, transport, false);
            sessions.put(sessionId, session); sessionPlatforms.put(sessionId, lease.platform());
        }
        Salt1193PlaybackSession.PreparedTrack track = session.prepare(key, metadata, lease, lyrics,renewer);
        try { session.enqueue(track, item -> {
            if (!permitted() || !accountActive.getAsBoolean()) throw new SecurityException("Account/permission revoked before enqueue");
            if (action == io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.QueueAction.PLAY) queue.addToPlay(item);
            else queue.insert(item, action);
        }); }
        catch (ReflectiveOperationException | RuntimeException failure) { track.close(); throw failure; }
    }
    public synchronized void revokePlatform(MediaLease.Platform platform) {
        queue.cancelStart();
        if(savedTracks!=null) savedTracks.revoke(io.github.yghfv.saltneri.plugin.auth.AccountPlatform.valueOf(platform.name()));
        if(playlist!=null && playlistPlatform==platform) { playlist.close(); playlist=null; }
        for (String scope : java.util.List.copyOf(sessions.keySet())) if (sessionPlatforms.get(scope) == platform) {
            sessions.remove(scope).close(); sessionPlatforms.remove(scope);
        }
    }
    public void playPlaylist(io.github.yghfv.saltneri.plugin.library.LibraryController.Playlist list, BooleanSupplier accountActive) throws Exception {
        synchronized(this) { if(closed || !permitted()) throw new IllegalStateException("Adapter stopped"); engine.ensureAttached(); }
        String scope=list.account()==null ? "anonymous:"+UUID.randomUUID() : list.account().accountScope();
        BooleanSupplier active=()->!isClosed() && permitted() && accountActive.getAsBoolean();
        var identities=savedTracks==null?null:savedStore.saveAll(list.platform(),list.items(),active).iterator();
        LazyPlaylistSession next=new LazyPlaylistSession(profile,adapter,transport,queue,list.items(),scope,
                active,
                new LazyPlaylistSession.Resolver() {
                    public Object revision() { return settings.get().qualityKey(); }
                    public io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.Audio resolve(io.github.yghfv.saltneri.plugin.library.Catalog.Item item,String identity,io.github.yghfv.saltneri.plugin.net.RequestScope request) throws Exception {
                        return io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.resolveAudio(list.account(),
                            new io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.Target(list.platform(),item.target()),identity,request,settings.get(),accounts);
                    }
                },
                item -> list.platform()==io.github.yghfv.saltneri.plugin.auth.AccountPlatform.NETEASE
                        ? request -> new io.github.yghfv.saltneri.plugin.netease.NeteaseLyricsClient(list.account()).load(Long.parseLong(item.target()),request)
                        : list.platform()==io.github.yghfv.saltneri.plugin.auth.AccountPlatform.QQMUSIC
                            ? request -> new io.github.yghfv.saltneri.plugin.qqmusic.QqMusicClient(list.account()).lyrics(io.github.yghfv.saltneri.plugin.qqmusic.QqMusicTarget.parse(item.target()),request)
                            : list.platform()==io.github.yghfv.saltneri.plugin.auth.AccountPlatform.KUGOU
                                ? request -> new io.github.yghfv.saltneri.plugin.kugou.KugouClient(list.account()).lyrics(item.target(),request) : null,
                savedTracks,item->identities.next());
        try {
            next.play();
            synchronized(this) { if(closed || !accountActive.getAsBoolean()) throw new SecurityException("Account retired"); if(playlist!=null) playlist.close(); playlist=next; playlistPlatform=MediaLease.Platform.valueOf(list.platform().name()); }
        } catch(Exception failure) { next.close(); throw failure; }
    }
    private boolean permitted() {
        try { return authorized.getAsBoolean(); }
        catch (RuntimeException failure) { return false; }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if(recovery!=null)recovery.close();
        queue.close();
        if(savedTracks!=null) {savedTracks.close();savedTracks=null;}
        if(playlist!=null) { playlist.close(); playlist=null; }
        watchdog.shutdownNow();
        tonePath = null; toneUri = null;
        try { for (var session : sessions.values()) session.close(); }
        finally { sessions.clear(); sessionPlatforms.clear(); try { engine.close(); } finally { transport.close(); } }
        // Never clear the host queue or abort global Unspecified streams during cleanup.
    }
}
