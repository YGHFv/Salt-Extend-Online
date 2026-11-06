package io.github.yghfv.saltneri.plugin.bilibili;

import io.github.yghfv.saltneri.plugin.auth.AccountCredentials;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.net.PlatformResolutionController;
import java.time.Clock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Bilibili presentation over the shared, account-fenced resolve-only lifecycle. */
public final class BiliResolutionController implements AutoCloseable {
    @FunctionalInterface interface Resolver {
        BiliClient.Resolved resolve(BiliTarget target, BiliAudioSelector.Quality quality, String scope, RequestScope request) throws Exception;
    }
    public record Snapshot(String status, BiliClient.Resolved resolved) { }
    private final PlatformResolutionController<BiliTarget, BiliAudioSelector.Quality, BiliClient.Resolved> controller;
    public BiliResolutionController(BooleanSupplier authorized) { this(null, authorized, () -> null, Clock.systemUTC()); }
    public BiliResolutionController(BooleanSupplier authorized, Supplier<AccountCredentials> accounts) {
        this(null, authorized, accounts, Clock.systemUTC());
    }
    BiliResolutionController(Resolver resolver, BooleanSupplier authorized, Clock clock) { this(resolver, authorized, () -> null, clock); }
    private BiliResolutionController(Resolver resolver, BooleanSupplier authorized, Supplier<AccountCredentials> accounts, Clock clock) {
        controller = new PlatformResolutionController<>("Bilibili", account -> resolver == null ? new BiliClient(account)::resolve : resolver::resolve,
                accounts, authorized, clock, BiliClient.Resolved::expiresAt, result ->
                "Bilibili resolved: " + result.key().remoteId() + " / CID " + result.key().cid() + ", "
                        + result.bitrateKbps() + " kbps, " + result.durationMillis() / 1000 + "s, " + result.lease().length()
                        + " bytes. Range probe only; not enqueued or played. Lyrics/covers not published.",
                error -> error instanceof BiliClient.PlatformRejected denied ? denied.getMessage() : null);
    }
    public void start(BiliTarget target, BiliAudioSelector.Quality quality) { controller.start(target, quality); }
    public void invalidate(String detail) { controller.invalidate(detail); }
    public Snapshot snapshot() { var state = controller.snapshot(); return new Snapshot(state.status(), state.resolved()); }
    @Override public void close() { controller.close(); }
}
