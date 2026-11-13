package io.github.yghfv.saltneri.plugin.host;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;

/** A blocked disk/hook initializer must not hold up startup or the AWT event queue. */
public final class DeferredPluginStartupTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Startup " + checks); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Startup latch timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    public static void main(String[] args) throws Exception {
        Object lifecycle = new Object();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var done = new CountDownLatch(1); var startupReturned = new CountDownLatch(1);
        var startup = new AtomicReference<DeferredPluginStartup>();
        var initialized = new AtomicBoolean(); var onUiThread = new AtomicBoolean(true);
        var problem = new AtomicReference<Throwable>();
        SwingUtilities.invokeLater(() -> {
            synchronized (lifecycle) {
                startup.set(new DeferredPluginStartup(lifecycle, () -> {
                    onUiThread.set(SwingUtilities.isEventDispatchThread()); entered.countDown();
                    await(release); initialized.set(true); done.countDown();
                }, problem::set));
            }
            startupReturned.countDown();
        });
        await(startupReturned); await(entered);
        var uiTick = new CountDownLatch(1); SwingUtilities.invokeLater(uiTick::countDown); await(uiTick);
        check(!onUiThread.get() && !initialized.get());
        var closing = new CountDownLatch(1); var closed = new CountDownLatch(1);
        Thread stopper = new Thread(() -> { closing.countDown(); startup.get().close(); closed.countDown(); });
        stopper.start(); await(closing);
        check(closed.getCount() == 1); // Loader retirement waits for the in-flight initializer.
        release.countDown(); await(done); await(closed); stopper.join(5000);
        check(initialized.get() && problem.get() == null);
        startup.get().close();

        var calls = new AtomicInteger();
        synchronized (lifecycle) {
            try (var pending = new DeferredPluginStartup(lifecycle, calls::incrementAndGet, problem::set)) {
                pending.close(); // Stop before the worker acquires the lifecycle monitor.
            }
        }
        var barrier = new CountDownLatch(1);
        try (var next = new DeferredPluginStartup(lifecycle, barrier::countDown, problem::set)) { await(barrier); }
        check(calls.get() == 0); // No stale initialization can publish after stop/restart.

        var failed = new CountDownLatch(1);
        try (var broken = new DeferredPluginStartup(lifecycle, () -> { throw new NoClassDefFoundError("fixture"); },
                error -> { problem.set(error); failed.countDown(); })) { await(failed); }
        check(problem.get() instanceof NoClassDefFoundError);
        System.out.println("PASS: " + checks + " deferred startup/UI responsiveness and loader retirement checks");
    }
}
