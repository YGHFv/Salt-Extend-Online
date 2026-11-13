package io.github.yghfv.saltneri.plugin.host;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Keep host startup/UI threads free; drain initialization before the plugin loader is retired. */
public final class DeferredPluginStartup implements AutoCloseable {
    private final Object lifecycle;
    private final Runnable initialize;
    private final Consumer<Throwable> failure;
    private final ExecutorService worker;
    private boolean closed;

    public DeferredPluginStartup(Object lifecycle, Runnable initialize, Consumer<Throwable> failure) {
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.initialize = Objects.requireNonNull(initialize);
        this.failure = Objects.requireNonNull(failure);
        worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "salt-extend-startup");
            thread.setDaemon(true);
            return thread;
        });
        worker.execute(this::run);
    }

    private void run() {
        synchronized (lifecycle) {
            if (closed) return;
            try { initialize.run(); }
            catch (RuntimeException | LinkageError error) { failure.accept(error); }
            finally { worker.shutdown(); }
        }
    }

    @Override public void close() {
        synchronized (lifecycle) {
            closed = true;
            worker.shutdownNow();
        }
    }
}
