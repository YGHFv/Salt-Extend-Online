package io.github.yghfv.saltneri.plugin.transport;

import io.github.yghfv.saltneri.plugin.host.EngineLinkContractTest;
import io.github.yghfv.saltneri.plugin.host.HostRangeReader;
import io.github.yghfv.saltneri.plugin.host.Salt1193DiagnosticRuntime;
import io.github.yghfv.saltneri.plugin.host.Salt1193Probe;
import io.github.yghfv.saltneri.plugin.host.Salt1193StreamAdapter;
import io.github.yghfv.saltneri.plugin.host.ReversibleInterceptorLists;
import io.github.yghfv.saltneri.plugin.host.StreamBinding;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;

/** Actual Salt reader/proxy -> locally generated PCM, with fake engine and recording queue. */
public final class DiagnosticContractTest {
    private static int checks;
    public static void main(String[] args) throws Throwable {
        byte[] fixture = DiagnosticTone.wave();
        ByteBuffer header = ByteBuffer.wrap(fixture).order(ByteOrder.LITTLE_ENDIAN);
        require(new String(fixture, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF"), "Not WAV");
        require(header.getInt(4) == fixture.length - 8 && header.getInt(40) == fixture.length - 44, "WAV bounds");
        require(header.getShort(20) == 1 && header.getShort(22) == 1 && header.getInt(24) == 44100, "PCM format");
        require(header.getShort(34) == 16 && fixture.length == 44 + 44100 * 2 * 2, "PCM duration");
        int peak = 0;
        for (int offset = 44; offset < fixture.length; offset += 2) peak = Math.max(peak, Math.abs((int)header.getShort(offset)));
        require(peak <= 1600 && peak > 1000 && header.getShort(44) == 0 && header.getShort(fixture.length - 2) == 0, "Loudness/fade");

        ClassLoader loader = DiagnosticContractTest.class.getClassLoader();
        Salt1193Probe.Result profile = Salt1193Probe.inspect(loader, "1.19.3", "Steam");
        AtomicBoolean grant = new AtomicBoolean(true);
        AtomicInteger outbound = new AtomicInteger();
        LoopbackRangeServer transport = new LoopbackRangeServer(Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC), uri -> {
            outbound.incrementAndGet(); throw new AssertionError("Diagnostic made platform request");
        }, grant::get);
        Salt1193StreamAdapter adapter = new Salt1193StreamAdapter(loader, profile, grant::get, (local, flags) -> {
            try (HostRangeReader source = HostRangeReader.open(local)) {
                require(source.length() == fixture.length, "Diagnostic size probe");
                byte[] bytes = new byte[fixture.length];
                require(source.read(bytes) == bytes.length && Arrays.equals(bytes, fixture), "Diagnostic PCM changed in transport");
                source.seek(fixture.length - 100);
                require(source.read(new byte[100]) == 100, "Diagnostic tail seek");
            }
            return 456;
        });
        TestBinding binding = new TestBinding(adapter);
        List<Object> queue = new ArrayList<>();
        EngineLinkContractTest.Hooks hooks = new EngineLinkContractTest.Hooks();
        try (Salt1193DiagnosticRuntime runtime = new Salt1193DiagnosticRuntime(profile, binding, transport,
                new EngineLinkContractTest.FakeHost(), hooks, queue::add, grant::get)) {
            require(queue.isEmpty(), "Arming changed queue");
            require(hooks.installs == 1, "Arming didn't bind hook");
            runtime.playTone();
            require(queue.size() == 1, "Explicit tone not enqueued");
            String[] fields = profile.mediaFactory().read(queue.getFirst());
            require(fields[5].startsWith("saltneri://diagnostic/") && fields[1].contains("test tone"), "Tone masquerades as platform track");
            Method create = Class.forName("androidx.compose.ui.\u015b").getMethods()[0];
            Object boxed = create.invoke(adapter.interceptor(), null, queue.getFirst(), 0L, 0L, 0, continuation());
            Object result = Class.forName("kotlin.Result").getMethod("unbox-impl").invoke(boxed);
            require(result.equals(456), "Diagnostic didn't resolve through native ABI");
            require(outbound.get() == 0, "Diagnostic made outbound request");
            grant.set(false);
            long until = System.nanoTime() + 3_000_000_000L;
            while (!runtime.isClosed() && System.nanoTime() < until) Thread.sleep(20);
            require(runtime.isClosed(), "Idle revocation didn't disarm");
            require(hooks.unhooks == 1 && binding.closed, "Revocation didn't detach");
            require(queue.size() == 1, "Cleanup mutated unrelated queue state");
        }
        grant.set(true);
        LoopbackRangeServer rejectedTransport = new LoopbackRangeServer(() -> true);
        AtomicReference<Object> rejectedItem = new AtomicReference<>();
        Salt1193StreamAdapter rejectedAdapter = new Salt1193StreamAdapter(loader, profile, () -> true,
                (uri, flags) -> { throw new AssertionError("Rejected queue submission retained a live media path"); });
        TestBinding rejectedBinding = new TestBinding(rejectedAdapter);
        EngineLinkContractTest.FakeHost rejectedHost = new EngineLinkContractTest.FakeHost();
        EngineLinkContractTest.Hooks rejectedHooks = new EngineLinkContractTest.Hooks();
        try (Salt1193DiagnosticRuntime runtime = new Salt1193DiagnosticRuntime(profile, rejectedBinding, rejectedTransport,
                rejectedHost, rejectedHooks, item -> {
                    rejectedItem.set(item); throw new IllegalStateException("Fixture queue rejected");
                }, () -> true)) {
            try { runtime.playTone(); throw new AssertionError("Expected queue rejection"); }
            catch (IllegalStateException expected) { checks++; }
            require(rejectedItem.get() != null && !runtime.isClosed(), "Queue rejection state incorrect");
            Method create = Class.forName("androidx.compose.ui.\u015b").getMethods()[0];
            Object boxed = create.invoke(rejectedAdapter.interceptor(), null, rejectedItem.get(), 0L, 0L, 0, continuation());
            Object result = Class.forName("kotlin.Result").getMethod("unbox-impl").invoke(boxed);
            try { ResultKt.throwOnFailure(result); throw new AssertionError("Rejected path still resolved"); }
            catch (IllegalStateException expected) { checks++; }
            rejectedBinding.failAttach = true;
            rejectedHooks.callback.after(new EngineLinkContractTest.Call(rejectedHost.service(), rejectedHost.player(rejectedHost.service()), null));
            long until = System.nanoTime() + 3_000_000_000L;
            while (!runtime.isClosed() && System.nanoTime() < until) Thread.sleep(20);
            require(runtime.isClosed() && rejectedBinding.closed, "Hook failure didn't close whole runtime");
            require(rejectedHooks.unhooks == 1, "Hook failure leaked registration");
            try { rejectedTransport.registerDiagnosticTone(); throw new AssertionError("Hook failure retained listener"); }
            catch (IllegalStateException expected) { checks++; }
        }
        System.out.println("PASS: " + checks + " diagnostic checks (actual Salt reader/ABI, generated PCM; simulated engine and queue)");
    }

    private static final class TestBinding implements StreamBinding {
        final Salt1193StreamAdapter adapter;
        boolean attached;
        boolean closed;
        boolean failAttach;
        TestBinding(Salt1193StreamAdapter adapter) { this.adapter = adapter; }
        @Override public void register(String path, URI uri) { adapter.register(path, uri); }
        @Override public void unregister(String path) { adapter.unregister(path); }
        @Override public ReversibleInterceptorLists attach(Object engine) throws IllegalAccessException {
            if (failAttach) throw new IllegalAccessException("Fixture attachment failed");
            attached = true; return null;
        }
        @Override public boolean isAttached() { return attached && !closed; }
        @Override public void close() { closed = true; adapter.close(); }
    }
    private static Continuation<Unit> continuation() {
        return new Continuation<>() {
            @Override public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }
            @Override public void resumeWith(Object result) { throw new AssertionError("Unexpected resume"); }
        };
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
