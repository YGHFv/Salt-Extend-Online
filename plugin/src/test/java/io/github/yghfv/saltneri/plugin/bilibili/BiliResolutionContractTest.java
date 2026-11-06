package io.github.yghfv.saltneri.plugin.bilibili;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class BiliResolutionContractTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
    private static final BiliTarget TARGET = new BiliTarget("BV17x411w7KC", 2);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T00:00:01Z"), ZoneOffset.UTC);
    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        check(condition.getAsBoolean());
    }
    public static void main(String[] args) throws Exception {
        var resolved = BiliClientContractTest.resolvedFixture();
        AtomicInteger calls = new AtomicInteger(); AtomicBoolean allowed = new AtomicBoolean(true);
        CountDownLatch first = new CountDownLatch(1), release = new CountDownLatch(1), lateFinished = new CountDownLatch(1);
        try (BiliResolutionController controller = new BiliResolutionController((target, quality, scope, request) -> {
            if (calls.incrementAndGet() == 1) {
                first.countDown();
                // Deliberately emulate an uncooperative DNS/third-party operation ignoring interruption.
                for (;;) { try { release.await(); break; } catch (InterruptedException ignored) { } }
                lateFinished.countDown();
            }
            return resolved;
        }, allowed::get, CLOCK)) {
            controller.start(TARGET, BiliAudioSelector.Quality.HIGH); check(first.await(2, TimeUnit.SECONDS));
            check(controller.snapshot().resolved() == null);
            controller.start(TARGET, BiliAudioSelector.Quality.LOW); release.countDown();
            waitFor(() -> controller.snapshot().resolved() != null);
            check(calls.get() == 2 && lateFinished.getCount() == 0);
            check(controller.snapshot().status().contains("not enqueued"));
            check(!controller.snapshot().status().contains("SECRET"));
            controller.invalidate("Configuration changed"); check(controller.snapshot().resolved() == null);
            allowed.set(false);
            try { controller.start(TARGET, BiliAudioSelector.Quality.HIGH); throw new AssertionError("Permission bypass"); }
            catch (SecurityException expected) { checks++; }
        } finally { release.countDown(); }
        CountDownLatch started = new CountDownLatch(1), exit = new CountDownLatch(1);
        BiliResolutionController stopped = new BiliResolutionController((target, quality, scope, request) -> {
            started.countDown(); try { exit.await(); } catch (InterruptedException ignored) { } return resolved;
        }, () -> true, CLOCK);
        stopped.start(TARGET, BiliAudioSelector.Quality.HIGH); check(started.await(2, TimeUnit.SECONDS)); stopped.close(); exit.countDown();
        check(stopped.snapshot().resolved() == null);
        try { stopped.start(TARGET, BiliAudioSelector.Quality.HIGH); throw new AssertionError("Closed controller accepted work"); }
        catch (IllegalStateException expected) { checks++; }
        try (BiliResolutionController expired = new BiliResolutionController((t,q,s,r) -> resolved, () -> true,
                Clock.fixed(Instant.parse("2026-11-06T00:06:00Z"), ZoneOffset.UTC))) {
            expired.start(TARGET, BiliAudioSelector.Quality.HIGH);
            waitFor(() -> expired.snapshot().status().contains("expired")); check(expired.snapshot().resolved() == null);
        }
        try (BiliResolutionController failed = new BiliResolutionController((t,q,s,r) -> { throw new java.io.IOException("https://SECRET"); }, () -> true, CLOCK)) {
            failed.start(TARGET, BiliAudioSelector.Quality.HIGH);
            waitFor(() -> failed.snapshot().status().contains("failed")); check(!failed.snapshot().status().contains("SECRET"));
        }
        System.out.println("PASS: " + checks + " Bilibili background/revision checks (no native host or queue)");
    }
}
