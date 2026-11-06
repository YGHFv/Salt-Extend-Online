package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.auth.*;
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
    public record Audio(TrackKey key, Metadata metadata, MediaLease lease, Instant expiresAt) {
        public static Audio from(BiliClient.Resolved r) { return new Audio(r.key(), r.metadata(), r.lease(), r.expiresAt()); }
        public static Audio from(NeteaseClient.Resolved r) { return new Audio(r.key(), r.metadata(), r.lease(), r.expiresAt()); }
        @Override public String toString() { return "PreparedAudio[" + key.stableId() + ", URL redacted]"; }
    }
    @FunctionalInterface public interface Player { void play(Audio audio, BooleanSupplier active) throws Exception; }
    private final Function<AccountPlatform, AccountCredentials> accounts;
    private final BooleanSupplier authorized, armed;
    private final Player player;
    private final PlatformResolutionController<Target, String, Audio> resolver;
    private volatile AccountPlatform platform = AccountPlatform.NETEASE;
    private AccountCredentials selected;
    private String token = "", status = "No track prepared.";
    private boolean consumed;
    private boolean submissionFailed;
    private record PlayIntent(String token, Runnable setup, long deadline) { }
    private PlayIntent intent;
    private final ScheduledExecutorService playbackWorker;
    public OnlinePlaybackController(Function<AccountPlatform, AccountCredentials> accounts, BooleanSupplier authorized, BooleanSupplier armed, Player player) {
        this(accounts, authorized, armed, player, Clock.systemUTC(), null);
    }
    public OnlinePlaybackController(Function<AccountPlatform, AccountCredentials> accounts, BooleanSupplier authorized, BooleanSupplier armed,
                                    Player player, Clock clock, Function<AccountCredentials, PlatformResolutionController.Resolver<Target, String, Audio>> testResolver) {
        this.accounts = accounts; this.authorized = authorized; this.armed = armed; this.player = player;
        resolver = new PlatformResolutionController<>("Online", account -> testResolver == null ? (target, quality, scope, request) -> {
            if (target.platform() == AccountPlatform.BILIBILI) {
                var resolved = new BiliClient(account).resolve(BiliTarget.parse(target.id()), BiliAudioSelector.Quality.HIGH, scope, request);
                return withCover(Audio.from(resolved), resolved.coverCandidate(), request);
            }
            var resolved = new NeteaseClient(account).resolve(NeteaseTarget.parse(target.id()), NeteaseClient.Quality.STANDARD, scope, request);
            return withCover(Audio.from(resolved), resolved.coverCandidate(), request);
        } : testResolver.apply(account), () -> accounts.apply(platform), authorized, clock, Audio::expiresAt,
                audio -> "\u97f3\u8f68\u5df2\u51c6\u5907\uff0c\u5c1a\u672a\u64ad\u653e\u3002", error -> null);
        playbackWorker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "salt-neri-doubleclick-play"); thread.setDaemon(true); return thread;
        });
        playbackWorker.scheduleWithFixedDelay(() -> {
            try { dispatchPlayIntent(); }
            catch (RuntimeException | LinkageError error) { synchronized (this) { intent = null; } }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }
    private static Audio withCover(Audio audio, java.net.URI source, io.github.yghfv.saltneri.plugin.net.RequestScope request) {
        if (source == null) return audio;
        try (var images = new io.github.yghfv.saltneri.plugin.ui.CoverLoader();
             var coverRequest = new io.github.yghfv.saltneri.plugin.net.RequestScope(() -> { request.check(); return true; }, 3000)) {
            byte[] png = images.loadPlayback(source, coverRequest);
            Metadata old = audio.metadata();
            return new Audio(audio.key(), new Metadata(old.title(), old.artist(), old.album(), old.albumArtist(), png), audio.lease(), audio.expiresAt());
        } catch (java.io.IOException | RuntimeException ignored) { request.check(); return audio; }
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
        prepare(target);
        intent = new PlayIntent(token, java.util.Objects.requireNonNull(setup), System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
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
            play(pending.token());
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
        if (!audio.key().platform().name().equals(platform.name()) || selected != null && !audio.lease().belongsTo(selected.accountScope())) throw new SecurityException("Prepared audio scope mismatch");
        return audio;
    }
    public synchronized void adapterFailed() {
        // Retire preparation on setup failure; no queue attempt occurred and no automatic retry is safe.
        failPreparation("\u64ad\u653e\u9002\u914d\u542f\u7528\u5931\u8d25 [ADAPTER_SETUP]\uff0c\u672a\u63d0\u4ea4\u64ad\u653e\u3002\u8bf7\u68c0\u67e5\u6a21\u7ec4\u6388\u6743\u4e0e\u5bbf\u4e3b\u7248\u672c\u540e\u91cd\u65b0\u51c6\u5907\u3002");
    }
    private void failPreparation(String detail) { submissionFailed = true; status = detail; resolver.invalidate(detail); }
    public synchronized void play(String capability) throws Exception {
        Audio audio = validatedAudio(capability);
        if (!armed.getAsBoolean()) throw new IllegalStateException("Arm the adapter in Salt first");
        AccountCredentials playingAccount = selected; AccountPlatform playingPlatform = platform;
        consumed = true; status = "\u6b63\u5728\u5411 Salt \u63d0\u4ea4\u64ad\u653e\uff0c\u4e0d\u81ea\u52a8\u91cd\u8bd5";
        try { player.play(audio, () -> authorized.getAsBoolean() && (playingAccount == null || playingAccount.isActive()) && accounts.apply(playingPlatform) == playingAccount);
            status = "\u5df2\u5411 Salt \u63d0\u4ea4\u64ad\u653e [SUBMITTED]\uff1b\u8bf7\u68c0\u67e5\u5e95\u90e8\u64ad\u653e\u5668\u548c\u58f0\u97f3\uff0c\u63d0\u4ea4\u4e0d\u4ee3\u8868\u5df2\u53d1\u58f0\u3002";
        } catch (Exception | LinkageError failure) { submissionFailed = true; status = "\u64ad\u653e\u63d0\u4ea4\u5931\u8d25 [QUEUE_SUBMIT/" + failure.getClass().getSimpleName() + "]\uff0c\u4e0d\u81ea\u52a8\u91cd\u8bd5\u3002"; throw failure; }
    }
    public synchronized void invalidate(String detail) { intent = null; token = ""; consumed = false; submissionFailed = false; resolver.invalidate(detail); status = detail; }
    public synchronized void accountChanged(AccountPlatform changed) { if (platform == changed) invalidate("Account changed; old preparation retired."); }
    @Override public synchronized void close() { invalidate("Online playback disabled."); playbackWorker.shutdownNow(); resolver.close(); }
}
