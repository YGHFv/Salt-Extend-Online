package io.github.yghfv.saltneri.plugin.host;

import com.sun.jna.Memory;
import com.sun.net.httpserver.HttpServer;
import com.xuncorp.voxzen.cast.BassFileProcs;
import com.xuncorp.voxzen.cast.BassFileStreamRuntime;
import io.github.yghfv.saltneri.plugin.transport.LoopbackRangeServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real host file callbacks and HTTP source, fake native handle allocator, synthetic audio only. */
public final class NativeStreamLifecycleTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        failureAndEof();
        revokeAndUnload();
        openingRace();
        blockedRead();
        System.out.println("PASS: " + checks + " actual host callback lifecycle checks; no BASS output/account accessed");
    }

    private static void failureAndEof() throws Exception {
        try (Fixture http = new Fixture(); Native runtime = new Native();
             Factory factory = new Factory(runtime)) {
            int handle = handle(factory.streams.create(http.uri(), 42));
            BassFileProcs procs = runtime.callbacks.get(handle);
            check(runtime.flags == 42, "Native flags changed");
            try (Memory buffer = new Memory(1024)) {
                check(procs.read.invoke(buffer, 1024, null) == 1024, "Initial real read failed");
                var nativeRead = com.sun.jna.Function.getFunction(com.sun.jna.CallbackReference.getFunctionPointer(procs.read));
                check(nativeRead.invokeInt(new Object[]{buffer, 16, null}) == 16, "Native callback trampoline failed");
                check(procs.seek.invoke(Fixture.LENGTH, null), "EOF seek failed");
                check(procs.read.invoke(buffer, 1024, null) == 0, "EOF was not zero");
                check(procs.seek.invoke(0, null), "Normal EOF must remain seekable");
                check(procs.read.invoke(buffer, 16, null) == 16, "EOF poisoned decoder tail probe");
                check(factory.failures.get() == 0, "EOF invalidated the lease");
                http.fail = true;
                check(procs.seek.invoke(524288, null), "Failure seek failed");
                check(procs.read.invoke(buffer, 1024, null) == 0, "Failed read returned data");
                int requests = http.requests.get();
                for (int i = 0; i < 1000; i++) check(procs.read.invoke(buffer, 1024, null) == 0, "Terminal read reopened");
                check(http.requests.get() == requests, "Failure generated a request storm");
                check(!procs.seek.invoke(0, null), "Failed stream accepted seek");
                check(factory.failed.await(3, TimeUnit.SECONDS) && factory.failures.get() == 1, "Failed URI was not invalidated once");
                check(http.uri().equals(factory.failedUri), "Invalidated an unrelated URI");
                check(runtime.freed.get() == 0, "Plugin freed a host-owned handle");
            }
        }
    }

    private static void revokeAndUnload() throws Exception {
        try (LoopbackRangeServer transport = new LoopbackRangeServer(); Native runtime = new Native();
             Factory factory = new Factory(runtime); Fixture other = new Fixture()) {
            transport.onRevoke(factory.streams::revoke);
            URI one = transport.registerDiagnosticTone(), two = transport.registerDiagnosticTone();
            BassFileProcs first = runtime.callbacks.get(handle(factory.streams.create(one, 0)));
            BassFileProcs second = runtime.callbacks.get(handle(factory.streams.create(two, 0)));
            // Same Unspecified owner, but not created by our factory: must remain untouched.
            BassFileProcs unrelated = runtime.callbacks.get(handle(rawCreate(other.uri(), runtime.runtime)));
            transport.revoke(one);
            try (Memory buffer = new Memory(32)) {
                check(first.read.invoke(buffer, 32, null) == 0, "Revoked lease still read");
                check(second.read.invoke(buffer, 32, null) == 32, "Other lease was aborted");
                check(unrelated.read.invoke(buffer, 32, null) == 32, "Other plugin was aborted");
                factory.streams.close();
                check(second.read.invoke(buffer, 32, null) == 0, "Unload retained callback");
                check(unrelated.seek.invoke(524288, null), "Unrelated seek aborted on unload");
                check(unrelated.read.invoke(buffer, 32, null) == 32, "Unrelated stream aborted on unload");
                check(runtime.freed.get() == 0, "Unload freed native handles");
            }
        }
        try (LoopbackRangeServer transport = new LoopbackRangeServer(); Native runtime = new Native();
             Factory factory = new Factory(runtime)) {
            transport.onRevoke(factory.streams::revoke);
            BassFileProcs procs = runtime.callbacks.get(handle(factory.streams.create(transport.registerDiagnosticTone(), 0)));
            transport.close();
            check(!procs.seek.invoke(0, null), "Server shutdown lost its URI before notification");
        }
    }

    private static void openingRace() throws Exception {
        for (boolean unload : new boolean[]{false, true}) {
            try (Fixture http = new Fixture(); Native runtime = new Native(); Factory factory = new Factory(runtime);
                 var workers = Executors.newSingleThreadExecutor()) {
                runtime.hold = true;
                var result = workers.submit(() -> factory.streams.create(http.uri(), 0));
                check(runtime.entered.await(3, TimeUnit.SECONDS), "Native creation did not start");
                if (unload) factory.streams.close(); else factory.streams.revoke(http.uri());
                BassFileProcs opening = runtime.callbacks.values().iterator().next();
                check(!opening.seek.invoke(0, null), "Opening callback escaped cancellation");
                runtime.release.countDown();
                Object raw = result.get(4, TimeUnit.SECONDS);
                check(raw instanceof Integer || raw instanceof kotlin.Result.Failure, "Invalid opening result");
                if (raw instanceof Integer id) check(runtime.callbacks.containsKey(id), "Successful handle lost ownership");
                check(runtime.freed.get() <= 1, "Opening handle freed twice");
            }
        }
    }

    private static void blockedRead() throws Exception {
        try (Fixture http = new Fixture(); Native runtime = new Native(); Factory factory = new Factory(runtime);
             var workers = Executors.newSingleThreadExecutor(); Memory buffer = new Memory(1024)) {
            BassFileProcs procs = runtime.callbacks.get(handle(factory.streams.create(http.uri(), 0)));
            http.block = true;
            check(procs.seek.invoke(524288, null), "Blocked read seek failed");
            var read = workers.submit(() -> procs.read.invoke(buffer, 1024, null));
            check(http.entered.await(3, TimeUnit.SECONDS), "Read did not reach synthetic upstream");
            long start = System.nanoTime();
            factory.streams.close();
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500, "Unload waited for callback lock");
            check(!procs.seek.invoke(0, null), "Blocked callback allowed another seek");
            read.get(12, TimeUnit.SECONDS);
            http.release.countDown();
            var close = workers.submit(() -> procs.close.invoke(null));
            close.get(3, TimeUnit.SECONDS);
            check(true, "Host callback failed to drain");
        }
    }

    private static Object rawCreate(URI uri, BassFileStreamRuntime runtime) throws Exception {
        Class<?> stream = Class.forName("com.xuncorp.voxzen.cast.HttpRangeBassStream");
        Class<?> owner = Class.forName("com.xuncorp.voxzen.cast.HttpRangeStreamOwner");
        return stream.getMethod("create-BWLJW6A$Voxzen_composeApp", String.class, int.class, BassFileStreamRuntime.class, owner)
                .invoke(stream.getField("INSTANCE").get(null), uri.toString(), 0, runtime, owner.getField("Unspecified").get(null));
    }
    private static int handle(Object raw) {
        kotlin.ResultKt.throwOnFailure(raw);
        check(raw instanceof Integer id && id != 0, "Missing synthetic native handle"); return (int)raw;
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }

    private static final class Factory implements AutoCloseable {
        final ScopedNativeStreams streams;
        final AtomicInteger failures = new AtomicInteger();
        final CountDownLatch failed = new CountDownLatch(1);
        volatile URI failedUri;
        Factory(Native runtime) {
            streams = new ScopedNativeStreams(NativeStreamLifecycleTest.class.getClassLoader(), runtime.runtime, uri -> {
                failedUri = uri; failures.incrementAndGet(); failed.countDown();
            });
        }
        public void close() { streams.close(); }
    }
    private static final class Native implements AutoCloseable {
        static final AtomicInteger sequence = new AtomicInteger(1000000);
        final Map<Integer, BassFileProcs> callbacks = new ConcurrentHashMap<>();
        final AtomicInteger freed = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile boolean hold;
        volatile int flags;
        final BassFileStreamRuntime runtime = new BassFileStreamRuntime((f, procs) -> {
            flags = f;
            int id = sequence.incrementAndGet(); callbacks.put(id, procs); entered.countDown();
            if (hold) await(release);
            return id;
        }, id -> {
            BassFileProcs procs = callbacks.remove(id);
            if (procs == null) throw new AssertionError("Double native free");
            procs.close.invoke(null); freed.incrementAndGet(); return true;
        }, () -> 0);
        public void close() { release.countDown(); callbacks.values().forEach(p -> p.close.invoke(null)); callbacks.clear(); }
    }
    private static final class Fixture implements AutoCloseable {
        static final int LENGTH = 1048576;
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final java.util.concurrent.ExecutorService threads = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "synthetic-range-fixture"); thread.setDaemon(true); return thread;
        });
        final AtomicInteger requests = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile boolean fail, block;
        Fixture() throws Exception {
            server.setExecutor(threads);
            server.createContext("/media/", exchange -> {
                try (exchange) {
                    requests.incrementAndGet();
                    String range = exchange.getRequestHeaders().getFirst("Range");
                    String[] parts = range.substring(6).split("-", -1);
                    int first = Integer.parseInt(parts[0]);
                    int last = parts[1].isEmpty() ? LENGTH - 1 : Math.min(LENGTH - 1, Integer.parseInt(parts[1]));
                    if (first >= 524288 && block) { entered.countDown(); await(release); }
                    if (first >= 524288 && fail) { exchange.sendResponseHeaders(502, -1); return; }
                    byte[] data = new byte[last - first + 1];
                    for (int i = 0; i < data.length; i++) data[i] = (byte)(first + i);
                    exchange.getResponseHeaders().set("Content-Range", "bytes " + first + "-" + last + "/" + LENGTH);
                    exchange.sendResponseHeaders(206, data.length);
                    exchange.getResponseBody().write(data);
                }
            });
            server.start();
        }
        URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/media/" + "a".repeat(64)); }
        public void close() { release.countDown(); server.stop(0); threads.shutdownNow(); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(12, TimeUnit.SECONDS)) throw new AssertionError("Fixture timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
