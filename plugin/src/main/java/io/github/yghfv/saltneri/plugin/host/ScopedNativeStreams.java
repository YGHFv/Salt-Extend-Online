package io.github.yghfv.saltneri.plugin.host;

import com.xuncorp.voxzen.cast.BassFileProcs;
import com.xuncorp.voxzen.cast.BassFileStreamRuntime;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Wrap only callbacks created by this factory. The host retains native-handle ownership. */
final class ScopedNativeStreams implements Salt1193StreamAdapter.StreamFactory {
    private final ClassLoader loader;
    private final BassFileStreamRuntime injected;
    private final java.util.function.Consumer<URI> failed;
    private final Set<Scope> scopes = new HashSet<>();
    private final ThreadPoolExecutor aborts = new ThreadPoolExecutor(0, 2, 10, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "salt-neri-stream-abort");
                thread.setDaemon(true); return thread;
            });
    private boolean closed;

    ScopedNativeStreams(ClassLoader loader) { this(loader, null, uri -> { }); }
    ScopedNativeStreams(ClassLoader loader, BassFileStreamRuntime injected) {
        this(loader, injected, uri -> { });
    }
    ScopedNativeStreams(ClassLoader loader, BassFileStreamRuntime injected, java.util.function.Consumer<URI> failed) {
        this.loader = loader; this.injected = injected; this.failed = failed;
        aborts.setCorePoolSize(2);
        aborts.allowCoreThreadTimeOut(true);
    }

    @Override public Object create(URI uri, int flags) throws Exception {
        Scope scope = new Scope(uri);
        synchronized (this) {
            if (closed) throw new CancellationException("Online streams stopped");
            if (scopes.size() >= 128) throw new IllegalStateException("Too many online streams");
            scopes.add(scope);
        }
        boolean transferred = false;
        try {
            // Resolve capabilities before opening a source; a future incompatible host fails closed.
            Abi abi = new Abi(loader);
            BassFileStreamRuntime runtime = injected == null
                    ? (BassFileStreamRuntime)abi.defaultRuntime.invoke(abi.singleton) : injected;
            BassFileStreamRuntime guarded = new BassFileStreamRuntime((nativeFlags, procs) -> {
                scope.bind(abi, procs);
                if (scope.terminal.get()) return 0;
                return runtime.getCreateStream().invoke(nativeFlags, procs);
            }, runtime.getFreeChannel(), runtime.getGetErrorCode());
            Object result = abi.create.invoke(abi.singleton, uri.toString(), flags, guarded, abi.owner);
            transferred = result instanceof Integer handle && handle != 0;
            return result;
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        } finally {
            if (!transferred) retire(scope);
        }
    }

    @Override public synchronized void revoke(URI uri) {
        for (Scope scope : scopes) if (scope.uri.equals(uri)) scope.cancel();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        scopes.forEach(Scope::cancel);
        if (scopes.isEmpty()) aborts.shutdown();
    }

    private synchronized void retire(Scope scope) {
        scopes.remove(scope);
        if (closed && scopes.isEmpty()) aborts.shutdown();
    }

    private final class Scope {
        final URI uri;
        final AtomicBoolean terminal = new AtomicBoolean();
        final AtomicBoolean abortQueued = new AtomicBoolean();
        final AtomicBoolean readFailed = new AtomicBoolean();
        volatile Runnable abort;
        Scope(URI uri) { this.uri = uri; }

        void cancel() {
            terminal.set(true);
            Runnable action = abort;
            // HttpRangeSource.abort can wait for an in-flight InputStream. Never do that on
            // the UI/transport thread, or while holding a native callback/adapter lock.
            if (action != null && abortQueued.compareAndSet(false, true)) aborts.execute(action);
        }

        void bind(Abi abi, BassFileProcs procs) {
            try {
                Object active = null;
                for (Object candidate : (Set<?>)abi.opening.get(null)) {
                    if (abi.procs.invoke(candidate) == procs) { active = candidate; break; }
                }
                if (active == null) throw new IllegalStateException("Stream callback ownership unavailable");
                Object owned = active;
                Object source = abi.source.get(owned);
                abort = () -> {
                    try { abi.abort.invoke(owned); }
                    catch (ReflectiveOperationException ignored) { /* Callback gate is already terminal. */ }
                    finally { if (readFailed.get()) failed.accept(uri); }
                };
                var read = procs.read;
                var seek = procs.seek;
                var close = procs.close;
                procs.read = (buffer, length, user) -> {
                    if (terminal.get() || buffer == null || length <= 0) return 0;
                    try {
                        int count = read.invoke(buffer, length, user);
                        // A normal EOF is seekable (decoders probe the tail during open).
                        // Zero before EOF means the host swallowed a read failure/deadline.
                        if (count == 0 && (long)abi.position.invoke(source) < (long)abi.length.invoke(source)) fail();
                        return count;
                    } catch (Exception failure) {
                        fail(); return 0;
                    }
                };
                procs.seek = (offset, user) -> !terminal.get() && seek.invoke(offset, user);
                procs.close = user -> {
                    terminal.set(true);
                    try { close.invoke(user); }
                    finally { retire(this); }
                };
                procs.write();
                if (terminal.get()) cancel();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Scoped stream lifecycle unavailable");
            }
        }
        void fail() { readFailed.set(true); cancel(); }
    }

    private static final class Abi {
        final Object singleton, owner;
        final Field opening, source;
        final Method create, defaultRuntime, procs, abort, position, length;
        Abi(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> stream = Class.forName("com.xuncorp.voxzen.cast.HttpRangeBassStream", true, loader);
            Class<?> ownerType = Class.forName("com.xuncorp.voxzen.cast.HttpRangeStreamOwner", true, loader);
            Class<?> active = Class.forName("com.xuncorp.voxzen.cast.HttpRangeBassStream$ActiveStream", false, loader);
            singleton = stream.getField("INSTANCE").get(null);
            owner = ownerType.getField("Unspecified").get(null);
            create = stream.getMethod("create-BWLJW6A$Voxzen_composeApp", String.class, int.class, BassFileStreamRuntime.class, ownerType);
            defaultRuntime = accessible(stream.getDeclaredMethod("defaultRuntime"));
            opening = accessible(stream.getDeclaredField("openingStreams"));
            source = accessible(active.getDeclaredField("source"));
            procs = accessible(active.getMethod("getProcs"));
            abort = accessible(active.getMethod("abort"));
            position = source.getType().getMethod("getPosition");
            length = source.getType().getMethod("getLength");
        }
        private static <T extends java.lang.reflect.AccessibleObject> T accessible(T member) throws IllegalAccessException {
            if (!member.trySetAccessible()) throw new IllegalAccessException("Stream lifecycle inaccessible");
            return member;
        }
    }
}
