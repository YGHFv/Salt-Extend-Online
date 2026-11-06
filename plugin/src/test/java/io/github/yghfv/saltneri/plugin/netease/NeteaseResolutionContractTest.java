package io.github.yghfv.saltneri.plugin.netease;

import io.github.yghfv.saltneri.plugin.auth.AccountCredentials;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Also tests the lifecycle shared by Bilibili; no platform, host engine or queue touched. */
public final class NeteaseResolutionContractTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Resolution check " + checks); }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        check(condition.getAsBoolean());
    }
    private static void uncooperative(CountDownLatch release) {
        for (;;) { try { release.await(); return; } catch (InterruptedException ignored) { } }
    }
    public static void main(String[] args) throws Exception {
        var result = NeteaseClientContractTest.resolvedFixture(); var target = NeteaseClientContractTest.TARGET;
        var high = NeteaseClient.Quality.HIGH; var low = NeteaseClient.Quality.STANDARD;
        Clock clock = NeteaseClientContractTest.CLOCK;
        AtomicInteger calls = new AtomicInteger(); AtomicBoolean permitted = new AtomicBoolean(true);
        AtomicReference<NeteaseClient.Quality> latestQuality = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var controller = new NeteaseResolutionController((t, q, s, r) -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); uncooperative(release); }
            latestQuality.set(q); return result;
        }, permitted::get, () -> null, clock)) {
            controller.start(target, high); check(entered.await(2, TimeUnit.SECONDS));
            check(controller.snapshot().resolved() == null && controller.snapshot().status().contains("anonymous"));
            controller.start(target, high); controller.start(target, low); release.countDown();
            waitFor(() -> controller.snapshot().resolved() != null);
            check(calls.get() == 2 && latestQuality.get() == low);
            check(controller.snapshot().status().contains("not enqueued") && !controller.snapshot().status().contains("SECRET"));
            controller.invalidate("Config changed"); check(controller.snapshot().resolved() == null);
            permitted.set(false); try { controller.start(target, high); throw new AssertionError("Permission bypass"); } catch (SecurityException expected) { checks++; }
        } finally { release.countDown(); }
        try (var failed = new NeteaseResolutionController((t,q,s,r) -> { throw new IOException("https://URL_SECRET"); }, () -> true, () -> null, clock)) {
            failed.start(target, high); waitFor(() -> failed.snapshot().status().contains("failed")); check(!failed.snapshot().status().contains("SECRET"));
        }
        try (var denied = new NeteaseResolutionController((t,q,s,r) -> { throw new NeteaseClient.PlatformRejected(301); }, () -> true, () -> null, clock)) {
            denied.start(target, high); waitFor(() -> denied.snapshot().status().contains("301")); check(denied.snapshot().status().contains("without bypass"));
        }
        try (var expired = new NeteaseResolutionController((t,q,s,r) -> result, () -> true, () -> null,
                Clock.fixed(Instant.parse("2026-11-06T00:03:00Z"), ZoneOffset.UTC))) {
            expired.start(target, high); waitFor(() -> expired.snapshot().status().contains("expired")); check(expired.snapshot().resolved() == null);
        }
        AtomicReference<AccountCredentials> account = new AtomicReference<>();
        AccountCredentials first = NeteaseClientContractTest.credentials(true), replacement = NeteaseClientContractTest.credentials(true);
        try {
            try (var controller = new NeteaseResolutionController((t,q,s,r) -> result, () -> true, account::get, clock)) {
                account.set(first); controller.start(target, high); waitFor(() -> controller.snapshot().resolved() != null);
                account.set(replacement); check(controller.snapshot().resolved() == null); check(controller.snapshot().status().contains("revision changed"));
            }
            account.set(null); CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1), returned = new CountDownLatch(1);
            try (var controller = new NeteaseResolutionController((t,q,s,r) -> {
                started.countDown(); uncooperative(finish); returned.countDown(); return result;
            }, () -> true, account::get, clock)) {
                controller.start(target, high); check(started.await(2, TimeUnit.SECONDS)); account.set(first);
                check(controller.snapshot().resolved() == null); finish.countDown(); check(returned.await(2, TimeUnit.SECONDS));
                check(controller.snapshot().resolved() == null);
            } finally { finish.countDown(); }
            account.set(first);
            try (var controller = new NeteaseResolutionController((t,q,s,r) -> {
                check(s.equals(first.accountScope())); account.set(replacement); return result;
            }, () -> true, account::get, clock)) {
                controller.start(target, high); waitFor(() -> controller.snapshot().status().contains("revision changed"));
                check(controller.snapshot().resolved() == null);
            }
            account.set(first);
            try (var controller = new NeteaseResolutionController((t,q,s,r) -> result, () -> true, account::get, clock)) {
                controller.start(target, high); waitFor(() -> controller.snapshot().resolved() != null); first.retire();
                check(controller.snapshot().resolved() == null); check(controller.snapshot().status().contains("revision changed"));
            }
        } finally { first.retire(); replacement.retire(); }
        CountDownLatch closeStarted = new CountDownLatch(1), closeFinish = new CountDownLatch(1);
        var stopped = new NeteaseResolutionController((t,q,s,r) -> { closeStarted.countDown(); uncooperative(closeFinish); return result; }, () -> true, () -> null, clock);
        try {
            stopped.start(target, high); check(closeStarted.await(2, TimeUnit.SECONDS)); stopped.close(); closeFinish.countDown();
            check(stopped.snapshot().resolved() == null);
            try { stopped.start(target, high); throw new AssertionError("Closed controller accepted request"); } catch (IllegalStateException expected) { checks++; }
        } finally { closeFinish.countDown(); stopped.close(); }
        permitted.set(true);
        try (var controller = new NeteaseResolutionController((t,q,s,r) -> result, permitted::get, () -> null, clock)) {
            controller.start(target, high); waitFor(() -> controller.snapshot().resolved() != null); permitted.set(false);
            check(controller.snapshot().resolved() == null); check(controller.snapshot().status().contains("permission revoked"));
        }
        System.out.println("PASS: " + checks + " dual-platform staging/account/publication checks (no host engine or queue)");
    }
}
