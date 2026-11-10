package io.github.yghfv.saltneri.plugin.net;

import java.net.HttpURLConnection;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** One explicit user operation. Cancels its sockets on timeout, permission loss or user cancellation. */
public final class RequestScope implements AutoCloseable {
    private final Set<HttpURLConnection> connections = new HashSet<>();
    private final BooleanSupplier active;
    private final ScheduledExecutorService timer;
    private volatile boolean closed;
    private volatile boolean timedOut;
    public static final class DeadlineExceeded extends CancellationException {
        DeadlineExceeded() {super("Platform operation timed out");}
    }
    public RequestScope(BooleanSupplier active) { this(active, 30000); }
    public RequestScope(BooleanSupplier active, long deadlineMillis) {
        this(active,deadlineMillis,300000);
    }
    /** Playback lifetime is owned by its media lease, independently of the preparation request. */
    public static RequestScope playback(BooleanSupplier active) {return new RequestScope(active,1800000,1800000);}
    private RequestScope(BooleanSupplier active,long deadlineMillis,long maximum) {
        if (deadlineMillis < 1 || deadlineMillis > maximum) throw new IllegalArgumentException("Invalid operation deadline");
        this.active = Objects.requireNonNull(active);
        timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "salt-neri-platform-request"); thread.setDaemon(true); return thread;
        });
        timer.schedule(()->{timedOut=true;close();}, deadlineMillis, TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(() -> { if (!permitted()) close(); }, 250, 250, TimeUnit.MILLISECONDS);
    }
    private boolean permitted() { try { return active.getAsBoolean(); } catch (RuntimeException failure) { return false; } }
    public void check() {
        if(Thread.currentThread().isInterrupted() || !permitted())throw new CancellationException("Platform operation cancelled");
        if(closed) {if(timedOut)throw new DeadlineExceeded();throw new CancellationException("Platform operation cancelled");}
    }
    public void attach(HttpURLConnection connection) {
        Objects.requireNonNull(connection);
        try {
            check();
            synchronized (this) {
                if (closed) throw new CancellationException("Platform operation cancelled");
                connections.add(connection);
            }
        } catch (CancellationException failure) { disconnect(connection); throw failure; }
    }
    public void release(HttpURLConnection connection) {
        synchronized (this) { connections.remove(connection); }
        disconnect(connection);
    }
    @Override public void close() {
        Set<HttpURLConnection> pending;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending = new HashSet<>(connections); connections.clear();
        }
        timer.shutdownNow();
        pending.forEach(RequestScope::disconnect);
    }
    private static void disconnect(HttpURLConnection connection) { try { connection.disconnect(); } catch (RuntimeException ignored) { } }
}
