package io.github.yghfv.saltneri.plugin.net;

import java.net.HttpURLConnection;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** One explicit user operation. Cancels its sockets on timeout, permission loss or user cancellation. */
public final class RequestScope implements AutoCloseable {
    private final Set<HttpURLConnection> connections = new HashSet<>();
    private final BooleanSupplier active;
    private static final ScheduledThreadPoolExecutor TIMER = timer();
    private static final java.util.concurrent.ExecutorService DISCONNECTS = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "salt-network-disconnect"); thread.setDaemon(true); return thread;
    });
    private final ScheduledFuture<?> deadline;
    private final ScheduledFuture<?> permission;
    private static ScheduledThreadPoolExecutor timer() {
        var timer = new ScheduledThreadPoolExecutor(2, r -> {
            Thread thread = new Thread(r, "salt-neri-platform-deadlines"); thread.setDaemon(true); return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        timer.setKeepAliveTime(5,TimeUnit.SECONDS);
        timer.allowCoreThreadTimeOut(true);
        return timer;
    }
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
        deadline = TIMER.schedule(()->{timedOut=true;close();}, deadlineMillis, TimeUnit.MILLISECONDS);
        permission = TIMER.scheduleWithFixedDelay(() -> { if (!permitted()) close(); }, 250, 250, TimeUnit.MILLISECONDS);
        // A one-millisecond deadline can fire before the scheduled handles are assigned.
        if(closed) {deadline.cancel(false);permission.cancel(false);}
    }
    private boolean permitted() { try { return active.getAsBoolean(); } catch (RuntimeException failure) { return false; } }
    public void check() {
        if(closed) {if(timedOut)throw new DeadlineExceeded();throw new CancellationException("Platform operation cancelled");}
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
        // Attachment precedes reading; this unstarted connection has no concurrent read to drain.
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
        if(deadline!=null)deadline.cancel(false);
        if(permission!=null)permission.cancel(false);
        pending.forEach(RequestScope::cancelConnection);
    }
    /** HttpURLConnection.disconnect can wait for an in-progress read's internal monitor.
     * Publish cancellation immediately; never wait for that monitor on Compose/AWT or a controller lock. */
    public static void cancelConnection(HttpURLConnection connection) { DISCONNECTS.execute(() -> disconnect(connection)); }
    private static void disconnect(HttpURLConnection connection) { try { connection.disconnect(); } catch (RuntimeException ignored) { } }
}
