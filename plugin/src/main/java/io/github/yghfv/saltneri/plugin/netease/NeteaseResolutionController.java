package io.github.yghfv.saltneri.plugin.netease;

import io.github.yghfv.saltneri.plugin.auth.AccountCredentials;
import io.github.yghfv.saltneri.plugin.net.PlatformResolutionController;
import java.time.Clock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** NetEase presentation over the shared, account-fenced resolve-only lifecycle. */
public final class NeteaseResolutionController implements AutoCloseable {
    public record Snapshot(String status, NeteaseClient.Resolved resolved) { }
    private final PlatformResolutionController<NeteaseTarget, NeteaseClient.Quality, NeteaseClient.Resolved> controller;
    public NeteaseResolutionController(BooleanSupplier authorized, Supplier<AccountCredentials> accounts) {
        this(null, authorized, accounts, Clock.systemUTC());
    }
    NeteaseResolutionController(PlatformResolutionController.Resolver<NeteaseTarget, NeteaseClient.Quality, NeteaseClient.Resolved> resolver,
                               BooleanSupplier authorized, Supplier<AccountCredentials> accounts, Clock clock) {
        controller = new PlatformResolutionController<>("NetEase", account -> resolver == null ? new NeteaseClient(account)::resolve : resolver,
                accounts, authorized, clock, NeteaseClient.Resolved::expiresAt, result ->
                "NetEase resolved: song " + result.key().remoteId() + ", " + result.bitrateKbps() + " kbps / " + result.level() + ", "
                        + result.durationMillis() / 1000 + "s, " + result.lease().length() + " bytes. Range probe only; not enqueued or played. Lyrics/covers not published.",
                error -> error instanceof NeteaseClient.PlatformRejected denied ? denied.getMessage() : null);
    }
    public void start(NeteaseTarget target, NeteaseClient.Quality quality) { controller.start(target, quality); }
    public void invalidate(String detail) { controller.invalidate(detail); }
    public Snapshot snapshot() { var state = controller.snapshot(); return new Snapshot(state.status(), state.resolved()); }
    @Override public void close() { controller.close(); }
}
