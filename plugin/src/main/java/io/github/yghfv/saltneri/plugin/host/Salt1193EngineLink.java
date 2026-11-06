package io.github.yghfv.saltneri.plugin.host;

import com.xuncorp.spw.workshop.api.hook.HookHandle;
import com.xuncorp.spw.workshop.api.hook.HookRegistrar;
import com.xuncorp.spw.workshop.api.hook.MethodHook;
import com.xuncorp.spw.workshop.api.hook.MethodHookParam;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Owns only the selected controller's engine, not other standalone playback services. */
public final class Salt1193EngineLink implements AutoCloseable {
    public static final String SERVICE = "com.xuncorp.voxzen.service.PlaybackService";
    public static final String PLAYER = "com.xuncorp.pisces.PiscesPlayer";

    public interface HostAccess {
        Object service() throws ReflectiveOperationException;
        Object player(Object service) throws ReflectiveOperationException;
    }
    @FunctionalInterface public interface HookInstaller {
        HookHandle install(String type, String method, List<String> parameters, MethodHook callback);
    }

    private final StreamBinding binding;
    private final HostAccess host;
    private final BooleanSupplier authorized;
    private HookHandle hook;
    private Object service;
    private boolean closed;

    public Salt1193EngineLink(Salt1193Probe.Result profile, StreamBinding binding, HostAccess host,
                              BooleanSupplier authorized) {
        if (!Objects.requireNonNull(profile).matched()) throw new IllegalArgumentException("Host profile not verified");
        this.binding = Objects.requireNonNull(binding);
        this.host = Objects.requireNonNull(host);
        this.authorized = Objects.requireNonNull(authorized);
    }

    /** Called from an explicit plugin action, not from the passive startup probe. */
    public synchronized void connect(HookInstaller installer) throws ReflectiveOperationException {
        try {
            requireActive();
            if (hook != null) { ensureAttached(); return; }
            hook = Objects.requireNonNull(installer.install(SERVICE, "onPlayerSet", List.of(PLAYER), new MethodHook() {
                @Override public void after(MethodHookParam call) {
                    synchronized (Salt1193EngineLink.this) {
                        if (closed || service == null || call.getThisObject() != service || call.getThrowable() != null) return;
                        // The context belongs to this synchronous call; retain only the actual player, never call itself.
                        Object[] args = call.getArgs();
                        if (args.length != 1) { failClosed(); return; }
                        try { requireActive(); binding.attach(args[0]); }
                        catch (ReflectiveOperationException | RuntimeException failure) { failClosed(); }
                    }
                }
            }), "Missing hook handle");
            requireActive();
            service = Objects.requireNonNull(host.service(), "Missing playback service");
            ensureAttached();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            failClosed();
            throw failure;
        }
    }

    /** Re-resolve/recheck before each explicit enqueue; onPlayerSet is only a construction notification. */
    public synchronized void ensureAttached() throws ReflectiveOperationException {
        try {
            requireActive();
            if (hook == null || service == null) throw new IllegalStateException("Engine link not connected");
            Object current = host.service();
            if (current != service) throw new IllegalStateException("Controller service identity changed");
            binding.attach(Objects.requireNonNull(host.player(service), "Missing native player"));
            requireActive();
            if (!binding.isAttached()) throw new IllegalStateException("Native interceptor installation unavailable");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            failClosed(); throw failure;
        }
    }

    public static HookInstaller hooks(HookRegistrar registrar) {
        return (type, method, parameters, callback) -> registrar.hookMethod(type, method, parameters, callback);
    }

    public static HostAccess nativeHost(ClassLoader loader, Salt1193Probe.Result profile) throws ReflectiveOperationException {
        if (!profile.matched()) throw new IllegalArgumentException("Host profile not verified");
        Class<?> controller = Class.forName("com.xuncorp.voxzen.service.PlaybackController", false, loader);
        Field field = controller.getDeclaredField("service");
        if (!field.trySetAccessible()) throw new IllegalAccessException("Playback service inaccessible");
        var getter = Class.forName(SERVICE, false, loader).getMethod("getPlayer");
        return new HostAccess() {
            @Override public Object service() throws IllegalAccessException { return field.get(null); }
            @Override public Object player(Object service) throws ReflectiveOperationException { return getter.invoke(service); }
        };
    }

    private void requireActive() {
        if (closed) throw new IllegalStateException("Engine link stopped");
        boolean granted;
        try { granted = authorized.getAsBoolean(); }
        catch (RuntimeException failure) { granted = false; }
        if (!granted) throw new SecurityException("CLASS_TRANSFORM not granted");
    }
    private void failClosed() { try { close(); } catch (RuntimeException ignored) { } }
    public synchronized boolean isClosed() { return closed; }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        service = null;
        try { if (hook != null) hook.close(); }
        finally { hook = null; binding.close(); }
    }
}
