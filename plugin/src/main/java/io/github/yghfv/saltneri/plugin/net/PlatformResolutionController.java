package io.github.yghfv.saltneri.plugin.net;

import io.github.yghfv.saltneri.plugin.auth.AccountCredentials;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** Shared resolve-only lifecycle: one worker/one queued task, account identity and publication fences. */
public final class PlatformResolutionController<T, Q, R> implements AutoCloseable {
    @FunctionalInterface public interface Resolver<T, Q, R> {
        R resolve(T target, Q quality, String accountScope, RequestScope request) throws Exception;
    }
    public record Snapshot<R>(String status, R resolved) { }
    private static final class Operation {
        final AtomicBoolean active = new AtomicBoolean(true);
        final AccountCredentials account;
        final RequestScope request;
        Future<?> future;
        Operation(AccountCredentials account, BooleanSupplier authorized) {
            this.account = account;
            request = new RequestScope(() -> active.get() && authorized.getAsBoolean() && (account == null || account.isActive()));
        }
        void cancel() { active.set(false); request.close(); if (future != null) future.cancel(true); }
    }
    private final String platform, anonymous = "anonymous:" + UUID.randomUUID();
    private final Function<AccountCredentials, Resolver<T, Q, R>> resolvers;
    private final Supplier<AccountCredentials> accounts;
    private final BooleanSupplier authorized;
    private final Clock clock;
    private final Function<R, Instant> expiry;
    private final Function<R, String> summary;
    private final Function<Throwable, String> rejection;
    private final ThreadPoolExecutor worker;
    private Operation current;
    private boolean closed;
    private String status;
    private R resolved;
    public PlatformResolutionController(String platform, Function<AccountCredentials, Resolver<T, Q, R>> resolvers,
                                        Supplier<AccountCredentials> accounts, BooleanSupplier authorized, Clock clock,
                                        Function<R, Instant> expiry, Function<R, String> summary, Function<Throwable, String> rejection) {
        this.platform = Objects.requireNonNull(platform); this.resolvers = Objects.requireNonNull(resolvers);
        this.accounts = Objects.requireNonNull(accounts); this.authorized = Objects.requireNonNull(authorized); this.clock = Objects.requireNonNull(clock);
        this.expiry = Objects.requireNonNull(expiry); this.summary = Objects.requireNonNull(summary); this.rejection = Objects.requireNonNull(rejection);
        status = "No " + platform + " track resolved. Resolution does not play audio.";
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
            Thread thread = new Thread(runnable, "salt-neri-" + platform.toLowerCase(java.util.Locale.ROOT) + "-resolve"); thread.setDaemon(true); return thread;
        });
    }
    private boolean permitted() { try { return authorized.getAsBoolean(); } catch (RuntimeException failure) { return false; } }
    private boolean usable(Operation operation) {
        return !closed && current == operation && operation.active.get() && permitted()
                && (operation.account == null || operation.account.isActive()) && accounts.get() == operation.account;
    }
    public synchronized void start(T target, Q quality) {
        if (closed) throw new IllegalStateException(platform + " resolver stopped");
        if (!permitted()) throw new SecurityException("Experimental permission required");
        Objects.requireNonNull(target); Objects.requireNonNull(quality);
        if (current != null) current.cancel(); current = null; worker.purge(); resolved = null;
        AccountCredentials account = accounts.get(); Resolver<T, Q, R> selected = resolvers.apply(account);
        String scope = account == null ? anonymous : account.accountScope();
        Operation operation = new Operation(account, this::permitted); current = operation;
        status = "Resolving " + platform + " in background (" + (account == null ? "anonymous" : "verified account") + "); no queue changes. Check status after completion.";
        try { operation.future = worker.submit(() -> run(operation, target, quality, selected, scope)); }
        catch (RuntimeException failure) { operation.cancel(); current = null; status = platform + " resolver busy or stopped."; throw failure; }
    }
    private void run(Operation operation, T target, Q quality, Resolver<T, Q, R> selected, String scope) {
        try (RequestScope request = operation.request) {
            request.check(); R result = Objects.requireNonNull(selected.resolve(target, quality, scope, request)); request.check();
            synchronized (this) {
                // Recheck under the publication lock, including anonymous -> signed-in replacement.
                if (!usable(operation)) return;
                if (!clock.instant().isBefore(expiry.apply(result))) { status = platform + " resolution expired. Resolve again explicitly; nothing played."; return; }
                resolved = result; status = summary.apply(result);
            }
        } catch (CancellationException cancelled) { failure(operation, platform + " resolution cancelled or timed out; nothing played."); }
        catch (Exception | LinkageError error) {
            String denied = rejection.apply(error);
            failure(operation, denied != null ? denied + "; stopped without bypass or automatic retry."
                    : platform + " resolution failed (" + error.getClass().getSimpleName() + "); nothing played. No signed URL logged.");
        }
    }
    private synchronized void failure(Operation operation, String detail) {
        if (!closed && current == operation && operation.active.get()) { resolved = null; status = detail; }
    }
    public synchronized void invalidate(String detail) {
        if (current != null) current.cancel(); current = null; worker.purge(); resolved = null; status = detail;
    }
    public synchronized Snapshot<R> snapshot() {
        if (!closed && !permitted()) invalidate(platform + " resolution unavailable: experimental permission revoked.");
        if (!closed && current != null && ((current.account != null && !current.account.isActive()) || accounts.get() != current.account)) {
            invalidate(platform + " account revision changed; previous result retired. Resolve explicitly again.");
        }
        if (resolved != null && !clock.instant().isBefore(expiry.apply(resolved))) {
            resolved = null; status = platform + " resolution expired. Resolve again explicitly; nothing played.";
        }
        return new Snapshot<>(status, resolved);
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true; invalidate(platform + " resolver stopped; no queue changes."); worker.shutdownNow();
    }
}
