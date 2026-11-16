package io.github.yghfv.saltneri.plugin.host;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Keep host startup/UI threads free; drain initialization before the plugin loader is retired. */
public final class DeferredPluginStartup implements AutoCloseable {
    private final Object lifecycle;
    private final Runnable initialize;
    private final Runnable support;
    private final Object supportLock = new Object();
    private final Consumer<Throwable> failure;
    private final ExecutorService worker;
    private volatile boolean closed;

    public DeferredPluginStartup(Object lifecycle, Runnable initialize, Consumer<Throwable> failure) {
        this(lifecycle,initialize,()->{},failure);
    }
    /** Optional work drains separately and never holds the monitor used by native UI actions. */
    public DeferredPluginStartup(Object lifecycle, Runnable initialize, Runnable support, Consumer<Throwable> failure) {
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.initialize = Objects.requireNonNull(initialize);
        this.support = Objects.requireNonNull(support);
        this.failure = Objects.requireNonNull(failure);
        worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "salt-extend-startup");
            thread.setDaemon(true);
            return thread;
        });
        worker.execute(this::run);
    }

    private void run() {
        try {
            synchronized (lifecycle) {
                if (closed) return;
                initialize.run();
            }
            synchronized(supportLock) {if(!closed)support.run();}
        } catch (RuntimeException | LinkageError error) {if(!closed)failure.accept(error);}
        finally {worker.shutdown();}
    }

    @Override public void close() {
        synchronized (lifecycle) {
            closed = true;
            worker.shutdownNow();
        }
        // Callers must release lifecycle before draining support, which publishes under that monitor.
        synchronized(supportLock) { }
    }
}
