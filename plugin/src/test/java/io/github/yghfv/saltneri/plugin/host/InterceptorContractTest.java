package io.github.yghfv.saltneri.plugin.host;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.JobKt;

/** No native player construction; verifies the real host's suspend nullable-Result ABI. */
public final class InterceptorContractTest {
    private static int checks;
    private static final URI LOCAL = URI.create("http://127.0.0.1:12345/media/" + "a".repeat(64));

    public static void main(String[] args) throws Exception {
        transactions();
        abi();
        System.out.println("PASS: " + checks + " interceptor/lifecycle checks (native audio engine not initialized)");
    }

    private static final class Slot implements ReversibleInterceptorLists.Slot {
        List<?> value;
        boolean failAfterWrite;
        Slot(List<?> value) { this.value = value; }
        @Override public List<?> read() { return value; }
        @Override public void write(List<?> next) throws IllegalAccessException {
            value = next;
            if (failAfterWrite) { failAfterWrite = false; throw new IllegalAccessException("Test write failed after mutation"); }
        }
    }

    private static void transactions() throws Exception {
        Object ours = new Object();
        Object other = new Object();
        List<Object> original = new ArrayList<>(List.of(other));
        Slot first = new Slot(original);
        Slot second = new Slot(List.of());
        Slot third = new Slot(List.of(other));
        ReversibleInterceptorLists transaction = ReversibleInterceptorLists.install(List.of(first, second, third), ours, () -> true);
        check(transaction.isInstalled(), "Installed transaction not healthy");
        check(first.value.get(0) == ours, "Plugin interceptor must run first for its own paths");
        check(first.value.get(1) == other, "Other mod lost");
        Object later = new Object();
        original.add(later);
        check(first.value.get(2) == later, "Original list mutation lost");
        List<?> retainedOverlay = second.value;
        transaction.close();
        transaction.close();
        check(transaction.isClosed(), "Transaction not marked closed");
        check(first.value == original, "Original list identity not restored");
        check(retainedOverlay.isEmpty(), "Retained overlay still active");

        first = new Slot(original);
        transaction = ReversibleInterceptorLists.install(List.of(first), ours, () -> true);
        List<?> overlay = first.value;
        List<Object> wrapper = new java.util.AbstractList<>() {
            @Override public int size() { return overlay.size() + 1; }
            @Override public Object get(int index) { return index == 0 ? "new-mod" : overlay.get(index - 1); }
        };
        first.value = wrapper;
        check(transaction.isInstalled(), "Later wrapping mod lost attachment health");
        transaction.close();
        check(first.value == wrapper, "Later mod wrapper overwritten");
        check(!first.value.contains(ours), "Inactive overlay retained our interceptor");
        check(first.value.contains(other), "Wrapped original interceptor lost");

        Slot replaced = new Slot(original);
        ReversibleInterceptorLists lost = ReversibleInterceptorLists.install(List.of(replaced), ours, () -> true);
        replaced.value = List.of("replacement-mod");
        check(!lost.isInstalled(), "Dropped interceptor still reported installed");
        lost.close();
        check(replaced.value.equals(List.of("replacement-mod")), "Replacement list overwritten on shutdown");

        Slot denied = new Slot(original);
        fails(SecurityException.class, () -> ReversibleInterceptorLists.install(List.of(denied), ours, () -> false));
        check(denied.value == original, "Denied install mutated the list");
        Slot partial = new Slot(original);
        Slot failing = new Slot(List.of(other));
        List<?> failingOriginal = failing.value;
        failing.failAfterWrite = true;
        fails(IllegalAccessException.class, () -> ReversibleInterceptorLists.install(List.of(partial, failing), ours, () -> true));
        check(partial.value == original, "First slot not rolled back");
        check(failing.value == failingOriginal, "Fail-after-mutation slot not rolled back");
        AtomicInteger authorizations = new AtomicInteger();
        fails(SecurityException.class, () -> ReversibleInterceptorLists.install(List.of(partial), ours,
                () -> authorizations.incrementAndGet() == 1));
        check(partial.value == original, "Revoke during installation not rolled back");

        final class FinalOwner { private final List<?> entries = List.of(other); }
        FinalOwner owner = new FinalOwner();
        ReversibleInterceptorLists.Slot field = ReversibleInterceptorLists.field(owner, FinalOwner.class.getDeclaredField("entries"));
        List<?> before = field.read();
        transaction = ReversibleInterceptorLists.install(List.of(field), ours, () -> true);
        check(field.read().get(0) == ours, "Final instance list field not installed");
        transaction.close();
        check(field.read() == before, "Final field identity not restored");
    }

    private static void abi() throws Exception {
        ClassLoader loader = InterceptorContractTest.class.getClassLoader();
        Salt1193Probe.Result profile = Salt1193Probe.inspect(loader, "1.19.3", "Steam");
        check(profile.matched(), profile.detail());
        Class<?> contract = Class.forName("androidx.compose.ui.\u015b", false, loader);
        Method create = contract.getMethods()[0];
        Method unbox = Class.forName("kotlin.Result").getMethod("unbox-impl");
        AtomicBoolean authorized = new AtomicBoolean(true);
        AtomicInteger opened = new AtomicInteger();
        Salt1193StreamAdapter adapter = new Salt1193StreamAdapter(loader, profile, authorized::get, (uri, flags) -> {
            check(uri.equals(LOCAL), "Factory received non-loopback URL");
            check(flags == 42, "Native flags not preserved");
            opened.incrementAndGet();
            return 123;
        });
        Continuation<Unit> continuation = continuation(EmptyCoroutineContext.INSTANCE);
        Object local = item(profile, "C:/Music/local.flac");
        check(invoke(create, adapter, local, 0, 0, continuation) == null, "Non-plugin item intercepted");
        eq(0, opened.get());
        adapter.register("saltneri://track/test", LOCAL);
        fails(IllegalStateException.class, () -> adapter.register("saltneri://track/test", LOCAL));
        Object online = item(profile, "saltneri://track/test");
        Object result = invoke(create, adapter, online, 0, 0, continuation);
        check(result.getClass().getName().equals("kotlin.Result"), "Nullable Result not boxed");
        eq(123, unbox.invoke(result));
        eq(1, opened.get());
        failure(unbox.invoke(invoke(create, adapter, online, 1, 0, continuation)), "Subfile stream not rejected");
        eq(1, opened.get());
        authorized.set(false);
        failure(unbox.invoke(invoke(create, adapter, online, 0, 0, continuation)), "Revoked adapter opened stream");
        eq(1, opened.get());
        authorized.set(true);
        Job cancelled = JobKt.Job((Job)null);
        cancelled.cancel(new CancellationException("Test cancellation"));
        fails(CancellationException.class, () -> invoke(create, adapter, online, 0, 0, continuation(cancelled)));
        eq(1, opened.get());
        adapter.unregister("saltneri://track/test");
        failure(unbox.invoke(invoke(create, adapter, online, 0, 0, continuation)), "Missing mapping not rejected");
        adapter.close();
        adapter.close();
        fails(IllegalStateException.class, () -> adapter.register("saltneri://track/closed", LOCAL));
        failure(unbox.invoke(invoke(create, adapter, online, 0, 0, continuation)), "Stopped adapter opened stream");
        check(invoke(create, adapter, local, 0, 0, continuation) == null, "Stopped adapter interfered with local media");

        for (Object raw : List.of(0, "wrong-ABI")) {
            try (Salt1193StreamAdapter invalid = new Salt1193StreamAdapter(loader, profile, () -> true, (uri, flags) -> raw)) {
                invalid.register("saltneri://track/test", LOCAL);
                failure(unbox.invoke(invoke(create, invalid, online, 0, 0, continuation)), "Invalid native handle accepted");
            }
        }
        try (Salt1193StreamAdapter throwing = new Salt1193StreamAdapter(loader, profile, () -> true,
                (uri, flags) -> { throw new IllegalStateException("signed-url-and-cookie-secret"); })) {
            throwing.register("saltneri://track/test", LOCAL);
            Object raw = unbox.invoke(invoke(create, throwing, online, 0, 0, continuation));
            try { ResultKt.throwOnFailure(raw); throw new AssertionError("Expected failure"); }
            catch (IllegalStateException expected) {
                check(!expected.toString().contains("secret") && expected.getCause() == null, "Credentials leaked via host failure");
            }
        }
        try (Salt1193StreamAdapter failedResult = new Salt1193StreamAdapter(loader, profile, () -> true,
                (uri, flags) -> ResultKt.createFailure(new IllegalStateException("signed-url-secret")))) {
            failedResult.register("saltneri://track/test", LOCAL);
            Object raw = unbox.invoke(invoke(create, failedResult, online, 0, 0, continuation));
            try { ResultKt.throwOnFailure(raw); throw new AssertionError("Expected native result failure"); }
            catch (IllegalStateException expected) {
                check(!expected.toString().contains("secret") && expected.getCause() == null, "Native Result failure leaked upstream");
            }
        }
        try (Salt1193StreamAdapter cancelledResult = new Salt1193StreamAdapter(loader, profile, () -> true,
                (uri, flags) -> ResultKt.createFailure(new CancellationException("Cancelled factory")))) {
            cancelledResult.register("saltneri://track/test", LOCAL);
            fails(CancellationException.class, () -> invoke(create, cancelledResult, online, 0, 0, continuation));
        }
    }

    private static Object item(Salt1193Probe.Result profile, String path) throws Exception {
        return profile.mediaFactory().create("test", "title", "artist", "album", "artist", path);
    }

    private static Continuation<Unit> continuation(CoroutineContext context) {
        return new Continuation<>() {
            @Override public CoroutineContext getContext() { return context; }
            @Override public void resumeWith(Object result) { throw new AssertionError("Synchronous adapter resumed continuation"); }
        };
    }

    private static Object invoke(Method method, Salt1193StreamAdapter adapter, Object item, long offset,
                                 long length, Continuation<?> continuation) throws Exception {
        try { return method.invoke(adapter.interceptor(), null, item, offset, length, 42, continuation); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }

    private static void failure(Object raw, String message) {
        try { ResultKt.throwOnFailure(raw); }
        catch (Exception expected) { checks++; return; }
        throw new AssertionError(message);
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void fails(Class<? extends Throwable> expected, Action action) {
        try { action.run(); }
        catch (Throwable failure) {
            if (expected.isInstance(failure)) { checks++; return; }
            throw new AssertionError("Unexpected failure", failure);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static void eq(Object expected, Object actual) { check(java.util.Objects.equals(expected, actual), "Value mismatch"); }
}
