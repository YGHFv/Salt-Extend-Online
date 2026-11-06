package io.github.yghfv.saltneri.plugin.host;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import kotlin.ResultKt;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.JobKt;

/** Experimental pinned adapter. Construction does not initialize an audio engine or install it. */
public final class Salt1193StreamAdapter implements StreamBinding {
    @FunctionalInterface
    public interface StreamFactory {
        /** Raw Kotlin Result<Int> representation from HttpRangeBassStream, not a boxed Result. */
        Object create(URI localUri, int flags) throws Exception;
    }

    private final Map<String, URI> paths = new ConcurrentHashMap<>();
    private final Map<String, Deferred> deferred = new ConcurrentHashMap<>();
    private final Map<Object, ReversibleInterceptorLists> attachments = new IdentityHashMap<>();
    private ReversibleInterceptorLists currentAttachment;
    private final BooleanSupplier authorized;
    private final Object interceptor;
    private volatile boolean closed;

    public Salt1193StreamAdapter(ClassLoader loader, Salt1193Probe.Result profile,
                                BooleanSupplier authorized, StreamFactory factory) throws ReflectiveOperationException {
        if (!profile.matched()) throw new IllegalArgumentException("Host profile not verified");
        this.authorized = Objects.requireNonNull(authorized);
        Objects.requireNonNull(factory);
        Class<?> contract = Class.forName("androidx.compose.ui.\u015b", false, loader);
        Method pathGetter = Class.forName("com.xuncorp.pisces.PiscesMediaItem", false, loader).getMethod("\u052c");
        Method idGetter = Class.forName("com.xuncorp.pisces.PiscesMediaItem", false, loader).getMethod("\u037f");
        Method box = Class.forName("kotlin.Result", false, loader).getMethod("box-impl", Object.class);
        interceptor = Proxy.newProxyInstance(loader, new Class<?>[]{contract}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "toString" -> "SaltNeriStreamInterceptor";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException();
            };
            if (!method.getName().equals("createStream-_2kQ-ls")) throw new UnsupportedOperationException();
            String path = (String)pathGetter.invoke(args[1]);
            if (!path.startsWith("saltneri://") && !((String)idGetter.invoke(args[1])).startsWith("saltneri:")) return null;
            JobKt.ensureActive(((Continuation<?>)args[5]).getContext());
            Object raw;
            try {
                if (closed || !authorized.getAsBoolean()) throw new SecurityException("Online stream adapter disabled");
                URI local = paths.get(path);
                if (local == null && deferred.containsKey(path)) {
                    local = deferred.get(path).resolve();
                    JobKt.ensureActive(((Continuation<?>)args[5]).getContext());
                }
                if (local == null) throw new IllegalStateException("Online stream lease unavailable");
                if ((long)args[2] != 0 || (long)args[3] != 0) throw new IllegalArgumentException("Subfile streams unsupported");
                raw = factory.create(local, (int)args[4]);
                if (!(raw instanceof Integer handle && handle != 0) && !(raw instanceof kotlin.Result.Failure)) {
                    throw new IllegalStateException("Invalid native stream result");
                }
                if (raw instanceof kotlin.Result.Failure failure) {
                    if (failure.exception instanceof CancellationException cancellation) throw cancellation;
                    raw = ResultKt.createFailure(new IllegalStateException("Online stream creation failed"));
                }
                // The host owns successful handles and frees them if the caller is cancelled.
                // Checking cancellation after create here would leak that just-created handle.
            } catch (CancellationException cancellation) {
                throw cancellation;
            } catch (Exception failure) {
                raw = ResultKt.createFailure(new IllegalStateException("Online stream creation failed"));
            }
            return box.invoke(null, raw);
        });
    }

    public synchronized void register(String path, URI localUri) {
        if (closed) throw new IllegalStateException("Stream adapter stopped");
        if (!(path.matches("saltneri://[a-z0-9/-]+") || PlaybackCoverFile.isPath(path)) || !"http".equals(localUri.getScheme())
                || !"127.0.0.1".equals(localUri.getHost()) || localUri.getPort() <= 0 || localUri.getPort() > 65535
                || localUri.getRawUserInfo() != null || localUri.getRawQuery() != null || localUri.getRawFragment() != null
                || !localUri.getRawPath().matches("/media/[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid plugin stream mapping");
        }
        if (paths.putIfAbsent(path, localUri) != null) throw new IllegalStateException("Stream path already registered");
    }

    public synchronized void registerDeferred(String path, Deferred source) {
        if (closed || !(path.matches("saltneri://[a-z0-9/-]+") || PlaybackCoverFile.isPath(path))) throw new IllegalArgumentException("Invalid deferred path");
        if (paths.containsKey(path) || deferred.putIfAbsent(path,Objects.requireNonNull(source)) != null) throw new IllegalStateException("Duplicate deferred path");
    }
    public void unregister(String path) { paths.remove(path); deferred.remove(path); }
    public Object interceptor() { return interceptor; }
    public synchronized boolean isAttached() {
        return !closed && currentAttachment != null && currentAttachment.isInstalled();
    }

    /** Explicit caller action only, after the host profile and CLASS_TRANSFORM grant are checked. */
    public synchronized ReversibleInterceptorLists attach(Object bassPlayer) throws ReflectiveOperationException {
        if (closed || !authorized.getAsBoolean()) throw new SecurityException("CLASS_TRANSFORM not granted");
        ReversibleInterceptorLists existing = attachments.get(bassPlayer);
        if (existing != null && existing.isInstalled()) { currentAttachment = existing; return existing; }
        currentAttachment = null;
        if (existing != null) existing.close();
        Class<?> player = bassPlayer.getClass();
        if (!player.getName().equals("com.xuncorp.pisces.\u0528")) throw new IllegalArgumentException("Unsupported audio engine");
        Object properties = read(bassPlayer, player.getDeclaredField("\u0528"));
        Object morvanium = read(bassPlayer, player.getDeclaredField("\u05ef"));
        Object hash = read(bassPlayer, player.getDeclaredField("\u0782"));
        ReversibleInterceptorLists transaction = ReversibleInterceptorLists.install(List.of(
                ReversibleInterceptorLists.field(properties, properties.getClass().getDeclaredField("\u052f")),
                ReversibleInterceptorLists.field(morvanium, morvanium.getClass().getDeclaredField("\u0529")),
                ReversibleInterceptorLists.field(hash, hash.getClass().getDeclaredField("\u052a"))), interceptor, authorized);
        attachments.put(bassPlayer, transaction);
        currentAttachment = transaction;
        return transaction;
    }

    /** Resolves the host singleton lazily, only when this factory is actually asked to open a stream. */
    public static StreamFactory nativeFactory(ClassLoader loader) {
        return (localUri, flags) -> {
            Class<?> stream = Class.forName("com.xuncorp.voxzen.cast.HttpRangeBassStream", true, loader);
            Class<?> owner = Class.forName("com.xuncorp.voxzen.cast.HttpRangeStreamOwner", true, loader);
            Object unspecified = owner.getField("Unspecified").get(null);
            try {
                return stream.getMethod("create-0E7RQCE", String.class, int.class, owner)
                        .invoke(stream.getField("INSTANCE").get(null), localUri.toString(), flags, unspecified);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception exception) throw exception;
                throw failure;
            }
            // Never call abortActiveStreams(Unspecified): it would include other mods' streams.
        };
    }

    private static Object read(Object owner, Field field) throws IllegalAccessException {
        if (!field.trySetAccessible()) throw new IllegalAccessException("Native owner inaccessible");
        return field.get(owner);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        paths.clear();
        deferred.clear();
        RuntimeException failure = null;
        for (ReversibleInterceptorLists transaction : new ArrayList<>(attachments.values())) {
            try { transaction.close(); }
            catch (RuntimeException problem) {
                if (failure == null) failure = new IllegalStateException("Could not detach stream adapter");
                failure.addSuppressed(problem);
            }
        }
        attachments.clear();
        currentAttachment = null;
        if (failure != null) throw failure;
    }
}
