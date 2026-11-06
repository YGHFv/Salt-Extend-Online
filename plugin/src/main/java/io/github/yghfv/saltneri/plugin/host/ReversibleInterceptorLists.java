package io.github.yghfv.saltneri.plugin.host;

import java.lang.reflect.Field;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Per-engine transaction. Retains other mods' entries and never blindly restores old fields. */
public final class ReversibleInterceptorLists implements AutoCloseable {
    public interface Slot {
        List<?> read() throws ReflectiveOperationException;
        void write(List<?> value) throws ReflectiveOperationException;
    }

    public static Slot field(Object owner, Field field) {
        Objects.requireNonNull(owner);
        if (field.getType() != List.class || !field.trySetAccessible()) {
            throw new IllegalArgumentException("Interceptor field inaccessible");
        }
        return new Slot() {
            @Override public List<?> read() throws IllegalAccessException { return (List<?>)field.get(owner); }
            @Override public void write(List<?> value) throws IllegalAccessException { field.set(owner, value); }
        };
    }

    private static final class Overlay extends AbstractList<Object> {
        final List<?> delegate;
        final Object interceptor;
        volatile boolean active = true;
        Overlay(List<?> delegate, Object interceptor) { this.delegate = delegate; this.interceptor = interceptor; }
        @Override public int size() { return delegate.size() + (active ? 1 : 0); }
        @Override public Object get(int index) {
            boolean enabled = active;
            if (enabled && index == 0) return interceptor;
            return delegate.get(index - (enabled ? 1 : 0));
        }
        @Override public Iterator<Object> iterator() {
            List<Object> snapshot = new ArrayList<>();
            if (active) snapshot.add(interceptor);
            snapshot.addAll(delegate);
            return snapshot.iterator();
        }
    }

    private record Installed(Slot slot, Overlay overlay) { }
    private final List<Installed> installed = new ArrayList<>();
    private boolean closed;

    private ReversibleInterceptorLists() { }

    public synchronized boolean isClosed() { return closed; }

    public synchronized boolean isInstalled() {
        if (closed) return false;
        try {
            for (Installed entry : installed) {
                if (entry.slot.read().stream().noneMatch(value -> value == entry.overlay.interceptor)) return false;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }

    public static ReversibleInterceptorLists install(List<? extends Slot> slots, Object interceptor,
                                                     BooleanSupplier authorized) throws ReflectiveOperationException {
        Objects.requireNonNull(interceptor);
        Objects.requireNonNull(authorized);
        if (slots.isEmpty()) throw new IllegalArgumentException("Missing interceptor slots");
        ReversibleInterceptorLists transaction = new ReversibleInterceptorLists();
        try {
            for (Slot slot : slots) {
                if (!authorized.getAsBoolean()) throw new SecurityException("CLASS_TRANSFORM not granted");
                List<?> previous = Objects.requireNonNull(slot.read(), "Missing interceptor list");
                Overlay overlay = new Overlay(previous, interceptor);
                // Record before writing, so a setter that fails after mutation is also rolled back.
                transaction.installed.add(new Installed(slot, overlay));
                slot.write(overlay);
                if (slot.read() != overlay) throw new IllegalStateException("Interceptor field rejected installation");
            }
            if (!authorized.getAsBoolean()) throw new SecurityException("CLASS_TRANSFORM revoked during install");
            return transaction;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            try { transaction.close(); } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        // Disable even overlays retained inside another mod's later wrapper.
        installed.forEach(entry -> entry.overlay.active = false);
        RuntimeException failure = null;
        for (int index = installed.size() - 1; index >= 0; index--) {
            Installed entry = installed.get(index);
            try {
                if (entry.slot.read() == entry.overlay) entry.slot.write(entry.overlay.delegate);
            } catch (ReflectiveOperationException | RuntimeException problem) {
                if (failure == null) failure = new IllegalStateException("Could not detach an inactive interceptor overlay");
                failure.addSuppressed(problem);
            }
        }
        installed.clear();
        if (failure != null) throw failure;
    }
}
