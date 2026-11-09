package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.PlaybackSettings;
import io.github.yghfv.saltneri.plugin.bilibili.*;
import io.github.yghfv.saltneri.plugin.netease.*;
import io.github.yghfv.saltneri.plugin.net.PlatformResolutionController;
import io.github.yghfv.saltneri.plugin.host.Salt1193PlaybackSession.Metadata;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.*;
import java.util.concurrent.*;

/** Preparation is inert; an explicit double-click owns a cancellable, one-use resolve-and-play intent. */
public final class OnlinePlaybackController implements AutoCloseable {
    public record Target(AccountPlatform platform, String id) { }
    public record Audio(TrackKey key, Metadata metadata, MediaLease lease, Instant expiresAt, Target target, TrackKey sourceKey,
                        java.net.URI coverSource, io.github.yghfv.saltneri.plugin.ui.CoverMode coverMode) {
        public Audio(TrackKey key, Metadata metadata, MediaLease lease, Instant expiresAt, Target target, TrackKey sourceKey) {
            this(key,metadata,lease,expiresAt,target,sourceKey,null,io.github.yghfv.saltneri.plugin.ui.CoverMode.FIT);
        }
        public Audio {
            coverSource=CoverSource.original(coverSource);
            coverMode=coverMode==null?io.github.yghfv.saltneri.plugin.ui.CoverMode.FIT:coverMode;
        }
        public Audio(TrackKey key, Metadata metadata, MediaLease lease, Instant expiresAt, Target target) {this(key,metadata,lease,expiresAt,target,key);}
        public Audio(TrackKey key, Metadata metadata, MediaLease lease, Instant expiresAt) { this(key,metadata,lease,expiresAt,null); }
        public static Audio from(BiliClient.Resolved r) { return new Audio(r.key(), r.metadata(), r.lease(), r.expiresAt()); }
        public static Audio from(NeteaseClient.Resolved r) { return new Audio(r.key(), r.metadata(), r.lease(), r.expiresAt()); }
        public static Audio from(io.github.yghfv.saltneri.plugin.qqmusic.QqMusicClient.Resolved r) {return new Audio(r.key(),r.metadata(),r.lease(),r.expiresAt());}
        public boolean transportMatches() {
            return sourceKey!=null && sourceKey.platform().name().equals(lease.platform().name()) && lease.isActive()
                    && (sourceKey.equals(key) || (key.platform()==TrackKey.Platform.NETEASE || key.platform()==TrackKey.Platform.QQMUSIC)
                        && sourceKey.platform()==TrackKey.Platform.BILIBILI && target!=null
                        && key.platform().name().equals(target.platform().name())
                        && key.equals(target.platform()==AccountPlatform.NETEASE?TrackKey.netease(target.id()):TrackKey.qqmusic(target.id())));
        }
        @Override public String toString() { return "PreparedAudio[" + key.stableId() + ", URL redacted]"; }
    }
    public enum QueueAction { PLAY, AFTER_CURRENT, AFTER_LAST_ADDED, AT_QUEUE_END }
    @FunctionalInterface public interface Player {
        void play(Audio audio, BooleanSupplier active) throws Exception;
        default void insert(Audio audio, BooleanSupplier active, QueueAction action) throws Exception {
            throw new UnsupportedOperationException("Queue insertion unavailable");
        }
    }
    private final Function<AccountPlatform, AccountCredentials> accounts;
    private final BooleanSupplier authorized, armed;
    private final Player player;
    private final PlatformResolutionController<Target, String, Audio> resolver;
    private volatile AccountPlatform platform = AccountPlatform.NETEASE;
    private AccountCredentials selected;
    private volatile String token = "";
    private String status = "No track prepared.";
    private boolean consumed;
    private boolean submissionFailed;
    private record PlayIntent(String token, Runnable setup, long deadline, QueueAction action) { }
    private PlayIntent intent;
    private final ScheduledExecutorService playbackWorker;
    public OnlinePlaybackController(Function<AccountPlatform, AccountCredentials> accounts, BooleanSupplier authorized, BooleanSupplier armed, Player player) {
        this(accounts, authorized, armed, player, Clock.systemUTC(), null);
    }
    public OnlinePlaybackController(Function<AccountPlatform, AccountCredentials> accounts, BooleanSupplier authorized, BooleanSupplier armed, Player player, Supplier<PlaybackSettings> settings) {
        this(accounts,authorized,armed,player,Clock.systemUTC(),null,settings);
    }
    public OnlinePlaybackController(Function<AccountPlatform, AccountCredentials> accounts, BooleanSupplier authorized, BooleanSupplier armed,
                                    Player player, Clock clock, Function<AccountCredentials, PlatformResolutionController.Resolver<Target, String, Audio>> testResolver) {
        this(accounts,authorized,armed,player,clock,testResolver,()->PlaybackSettings.DEFAULT);
    }
    private OnlinePlaybackController(Function<AccountPlatform, AccountCredentials> accounts, BooleanSupplier authorized, BooleanSupplier armed,
                                    Player player, Clock clock, Function<AccountCredentials, PlatformResolutionController.Resolver<Target, String, Audio>> testResolver, Supplier<PlaybackSettings> settings) {
        this.accounts = accounts; this.authorized = authorized; this.armed = armed; this.player = player;
        resolver = new PlatformResolutionController<>("Online", account -> testResolver == null ? (target, quality, scope, request) -> resolveAudio(account,target,scope,request,settings.get(),accounts)
            : testResolver.apply(account), () -> accounts.apply(platform), authorized, clock, Audio::expiresAt,
                audio -> "\u97f3\u8f68\u5df2\u51c6\u5907\uff0c\u5c1a\u672a\u64ad\u653e\u3002", error -> null);
        playbackWorker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "salt-neri-doubleclick-play"); thread.setDaemon(true); return thread;
        });
        playbackWorker.scheduleWithFixedDelay(() -> {
            try { dispatchPlayIntent(); }
            catch (RuntimeException | LinkageError error) { synchronized (this) { intent = null; } }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }
    public static Audio resolveAudio(AccountCredentials account, Target target, String scope, io.github.yghfv.saltneri.plugin.net.RequestScope request) throws Exception {
            return resolveAudio(account,target,scope,request,PlaybackSettings.DEFAULT);
    }
    public static Audio resolveAudio(AccountCredentials account, Target target, String scope, io.github.yghfv.saltneri.plugin.net.RequestScope request, PlaybackSettings settings) throws Exception {
            return resolveAudio(account,target,scope,request,settings,p->null);
    }
    public static Audio resolveAudio(AccountCredentials account, Target target, String scope, io.github.yghfv.saltneri.plugin.net.RequestScope request, PlaybackSettings settings,Function<AccountPlatform,AccountCredentials> accounts) throws Exception {
            Audio audio;
            if(target.platform()==AccountPlatform.APPLEMUSIC)throw new java.io.IOException("APPLE/OFFICIAL_CLIENT_REQUIRED");
            if (target.platform() == AccountPlatform.BILIBILI) {
                var resolved = new BiliClient(account).resolve(BiliTarget.parse(target.id()), settings.bili(), scope, request);
                audio=withCover(Audio.from(resolved), resolved.coverCandidate(), request,settings.coverMode());
            } else if(target.platform()==AccountPlatform.QQMUSIC) {
                try {
                    var resolved=new io.github.yghfv.saltneri.plugin.qqmusic.QqMusicClient(account).resolve(
                            io.github.yghfv.saltneri.plugin.qqmusic.QqMusicTarget.parse(target.id()),settings.qqmusic(),scope,request);
                    audio=withCover(Audio.from(resolved),resolved.coverCandidate(),request,settings.coverMode());
                } catch(io.github.yghfv.saltneri.plugin.qqmusic.QqMusicClient.TrackUnavailable unavailable) {
                    if(!settings.qqmusicAutoSourceSwitch())throw unavailable;
                    audio=QqMusicAutoSourceResolver.resolve(account,target,scope,request,settings,accounts,unavailable);
                }
            } else {
                try {
                    var resolved = new NeteaseClient(account).resolve(NeteaseTarget.parse(target.id()), settings.netease(), scope, request);
                    audio=withCover(Audio.from(resolved), resolved.coverCandidate(), request,settings.coverMode());
                } catch(NeteaseClient.TrackUnavailable unavailable) {
                    if(!settings.neteaseAutoSourceSwitch())throw unavailable;
                    audio=NeteaseAutoSourceResolver.resolve(account,target,scope,request,settings,accounts,unavailable);
                }
            }
            return new Audio(audio.key(),audio.metadata(),audio.lease(),audio.expiresAt(),target,audio.sourceKey(),audio.coverSource(),audio.coverMode());
    }
    @FunctionalInterface public interface PlaylistPlayer { void play(LibraryController.Playlist list, BooleanSupplier active) throws Exception; }
    public synchronized void playPlaylist(LibraryController.Playlist list, Runnable setup, PlaylistPlayer submit) {
        invalidate("Preparing playlist"); platform=list.platform(); selected=list.account(); token=UUID.randomUUID().toString(); String expected=token;
        BooleanSupplier accountActive=()->authorized.getAsBoolean() && accounts.apply(list.platform())==list.account() && (list.account()==null || list.account().isActive());
        var published=new java.util.concurrent.atomic.AtomicBoolean();
        playbackWorker.execute(()->{
            try {
                synchronized(this) { if(!token.equals(expected) || !accountActive.getAsBoolean()) return; }
                if(!armed.getAsBoolean()) setup.run();
                submit.play(list,()->accountActive.getAsBoolean() && (published.get() || token.equals(expected)));
                published.set(true);
                synchronized(this) { if(token.equals(expected)) { consumed=true; status=""; } }
            } catch(Exception | LinkageError failure) {
                synchronized(this) { if(token.equals(expected)) {
                    submissionFailed=true;
                    String reason=playbackFailureReason(failure);
                    status="\u5408\u96c6\u64ad\u653e\u5931\u8d25 [PLAYLIST/"+reason+"]";
                } }
            }
        });
    }
    static String playbackFailureReason(Throwable failure) {
        if(failure instanceof AuthFailure safe)return safe.getMessage();
        if(failure instanceof io.github.yghfv.saltneri.plugin.qqmusic.QqMusicClient.MediaRejected rejected)
            return "QQ_MEDIA_"+rejected.reason().name();
        if(failure instanceof io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.Rejected denied)
            return (denied instanceof io.github.yghfv.saltneri.plugin.qqmusic.QqMusicClient.TrackUnavailable
                    ? "QQ_TRACK_UNAVAILABLE=":denied.httpStatus()?"QQ_HTTP=":"QQ_API=")+denied.code();
        if(failure instanceof io.github.yghfv.saltneri.plugin.net.MediaRangeProbe.Rejected denied)
            return "MEDIA_HTTP="+denied.status();
        if(failure instanceof NeteaseClient.PlatformRejected denied)
            return (denied instanceof NeteaseClient.TrackUnavailable?"TRACK_UNAVAILABLE=":"NETEASE_REJECTED=")+denied.code();
        if(failure instanceof java.net.SocketTimeoutException)return "NETWORK_TIMEOUT";
        if(failure instanceof java.net.UnknownHostException)return "NETWORK_DNS";
        if(failure instanceof javax.net.ssl.SSLException)return "NETWORK_TLS";
        return failure.getClass().getSimpleName();
    }
    static Audio withCover(Audio audio, java.net.URI source, io.github.yghfv.saltneri.plugin.net.RequestScope request, io.github.yghfv.saltneri.plugin.ui.CoverMode mode) {
        source=CoverSource.original(source);
        if (source == null) return audio;
        Audio pending=new Audio(audio.key(),audio.metadata(),audio.lease(),audio.expiresAt(),audio.target(),audio.sourceKey(),source,mode);
        try (var images = new io.github.yghfv.saltneri.plugin.ui.CoverLoader();
             var coverRequest = new io.github.yghfv.saltneri.plugin.net.RequestScope(() -> { request.check(); return true; }, 3000)) {
            byte[] png = images.loadPlayback(source, coverRequest,mode);
            Metadata old = audio.metadata();
            return new Audio(audio.key(), new Metadata(old.title(), old.artist(), old.album(), old.albumArtist(), png), audio.lease(), audio.expiresAt(),audio.target(),audio.sourceKey(),source,mode);
        } catch (java.io.IOException | RuntimeException ignored) { request.check(); return pending; }
    }
    public synchronized void prepare(Target target) {
        invalidate("Preparing selected track; no queue changes."); platform = target.platform(); selected = accounts.apply(platform);
        token = UUID.randomUUID().toString(); consumed = false; resolver.start(target, "default");
    }
    public synchronized java.util.Map<String, Object> snapshot() {
        var state = resolver.snapshot(); Audio audio = state.resolved();
        boolean ready = audio != null && !consumed && accounts.apply(platform) == selected && (selected == null || selected.isActive());
        return java.util.Map.of("status", consumed ? status : intent != null && ready ? "\u6b63\u5728\u64ad\u653e\u6240\u9009\u66f2\u76ee" : state.status(),
                "ready", ready, "token", ready ? token : "", "title", ready ? audio.metadata().title() : "", "armed", armed.getAsBoolean(), "active", !token.isEmpty(),
                "error", submissionFailed ? status : state.failed() ? state.status() : "");
    }
    public synchronized void playSelected(Target target, Runnable setup) {
        submitSelected(target, setup, QueueAction.PLAY);
    }
    public synchronized void insertSelected(Target target, Runnable setup, QueueAction action) {
        if (action == null || action == QueueAction.PLAY) throw new IllegalArgumentException("Insertion position required");
        submitSelected(target, setup, action);
    }
    private void submitSelected(Target target, Runnable setup, QueueAction action) {
        prepare(target);
        intent = new PlayIntent(token, java.util.Objects.requireNonNull(setup), System.nanoTime() + TimeUnit.SECONDS.toNanos(30), action);
    }
    private void dispatchPlayIntent() {
        PlayIntent pending;
        synchronized (this) {
            pending = intent;
            if (pending == null) return;
            if (!pending.token().equals(token) || accounts.apply(platform) != selected || !authorized.getAsBoolean()
                    || selected != null && !selected.isActive() || System.nanoTime() >= pending.deadline()) { intent = null; return; }
            if (resolver.snapshot().resolved() == null) return;
            intent = null;
        }
        boolean setupComplete = false;
        try {
            validateConfirmation(pending.token());
            if (!armed.getAsBoolean()) pending.setup().run();
            setupComplete = true;
            submit(pending.token(), pending.action());
        } catch (Exception | LinkageError error) {
            synchronized (this) {
                if (!setupComplete && token.equals(pending.token()) && !consumed) adapterFailed();
                else if (setupComplete && token.equals(pending.token()) && !consumed) failPreparation(
                        "\u64ad\u653e\u672a\u63d0\u4ea4 [PLAY_STATE_CHANGED]\uff0c\u8bf7\u53cc\u51fb\u66f2\u76ee\u91cd\u8bd5\u3002");
            }
        }
    }
    public synchronized AccountPlatform validateConfirmation(String capability) {
        validatedAudio(capability); return platform;
    }
    private Audio validatedAudio(String capability) {
        var state = resolver.snapshot(); Audio audio = state.resolved();
        if (!authorized.getAsBoolean() || selected != null && !selected.isActive() || accounts.apply(platform) != selected) throw new SecurityException("Account or permission changed");
        if (audio == null || consumed || token.isEmpty() || !token.equals(capability)) throw new IllegalStateException("Prepare and confirm the current track first");
        if (!audio.transportMatches() || !audio.key().platform().name().equals(platform.name()) || selected != null && !audio.lease().belongsTo(selected.accountScope())) throw new SecurityException("Prepared audio scope mismatch");
        return audio;
    }
    public synchronized void adapterFailed() {
        // Retire preparation on setup failure; no queue attempt occurred and no automatic retry is safe.
        failPreparation("\u64ad\u653e\u9002\u914d\u542f\u7528\u5931\u8d25 [ADAPTER_SETUP]\uff0c\u672a\u63d0\u4ea4\u64ad\u653e\u3002\u8bf7\u68c0\u67e5\u6a21\u7ec4\u6388\u6743\u4e0e\u5bbf\u4e3b\u7248\u672c\u540e\u91cd\u65b0\u51c6\u5907\u3002");
    }
    private void failPreparation(String detail) { submissionFailed = true; status = detail; resolver.invalidate(detail); }
    public synchronized void play(String capability) throws Exception {
        submit(capability, QueueAction.PLAY);
    }
    private synchronized void submit(String capability, QueueAction action) throws Exception {
        Audio audio = validatedAudio(capability);
        if (!armed.getAsBoolean()) throw new IllegalStateException("Arm the adapter in Salt first");
        AccountCredentials playingAccount = selected; AccountPlatform playingPlatform = platform;
        consumed = true; status = "\u6b63\u5728\u5411 Salt \u63d0\u4ea4\u64ad\u653e\uff0c\u4e0d\u81ea\u52a8\u91cd\u8bd5";
        try {
            BooleanSupplier active = () -> authorized.getAsBoolean() && (playingAccount == null || playingAccount.isActive()) && accounts.apply(playingPlatform) == playingAccount;
            if (action == QueueAction.PLAY) player.play(audio, active); else player.insert(audio, active, action);
            status = action == QueueAction.PLAY
                    ? "\u5df2\u5411 Salt \u63d0\u4ea4\u64ad\u653e [SUBMITTED]\uff1b\u8bf7\u68c0\u67e5\u5e95\u90e8\u64ad\u653e\u5668\u548c\u58f0\u97f3\uff0c\u63d0\u4ea4\u4e0d\u4ee3\u8868\u5df2\u53d1\u58f0\u3002"
                    : "\u5df2\u5411 Salt \u63d0\u4ea4\u63d2\u64ad [SUBMITTED]\uff0c\u4e0d\u6539\u53d8\u5f53\u524d\u64ad\u653e\u72b6\u6001\u3002";
        } catch (Exception | LinkageError failure) { submissionFailed = true; status = "\u64ad\u653e\u63d0\u4ea4\u5931\u8d25 [QUEUE_SUBMIT/" + failure.getClass().getSimpleName() + "]\uff0c\u4e0d\u81ea\u52a8\u91cd\u8bd5\u3002"; throw failure; }
    }
    public synchronized void invalidate(String detail) { intent = null; token = ""; consumed = false; submissionFailed = false; resolver.invalidate(detail); status = detail; }
    public synchronized void accountChanged(AccountPlatform changed) { if (platform == changed) invalidate("Account changed; old preparation retired."); }
    @Override public synchronized void close() { invalidate("Online playback disabled."); playbackWorker.shutdownNow(); resolver.close(); }
}
