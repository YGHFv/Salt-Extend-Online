package io.github.yghfv.saltneri.plugin.ui;

import java.lang.reflect.*;
import java.util.function.*;
import javax.swing.SwingUtilities;
import kotlin.jvm.functions.Function3;

/** Reversible update of one existing page lambda, not a replacement Compose runtime or method Hook. */
public final class SaltPageContentBinding implements AutoCloseable {
    public static final String SINGLETON = "com.xuncorp.voxzen.ui.navigation.ComposableSingletons$AppNavigation_desktopKt";
    public static final String GETTER = "getLambda$1605156558$Voxzen_composeApp";
    private final Object slot;
    private final Field block;
    private final Method update;
    private final Function<Object, Object> factory;
    private final BooleanSupplier active;
    private volatile Object original, installed;
    private volatile boolean closed;
    private volatile String failure = "none";

    public SaltPageContentBinding(ClassLoader host, Function<Object, Object> factory, BooleanSupplier active) throws Exception {
        this.factory = factory; this.active = active;
        Class<?> owner = Class.forName(SINGLETON, true, host);
        slot = owner.getMethod(GETTER).invoke(owner.getField("INSTANCE").get(null));
        Class<?> lambda = Class.forName("androidx.compose.runtime.internal.ComposableLambdaImpl", false, host);
        if (!lambda.isInstance(slot)) throw new IllegalStateException("Unsupported page content object");
        block = lambda.getDeclaredField("_block"); block.setAccessible(true);
        update = lambda.getMethod("update", Object.class);
    }
    public static void onUiThread(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) task.run(); else SwingUtilities.invokeLater(task);
    }
    public void install() { onUiThread(this::installOnUiThread); }
    private void installOnUiThread() {
        if (closed || !active.getAsBoolean()) return;
        try {
            Object current = block.get(slot);
            if (installed != null) {
                if (current != installed) throw new IllegalStateException("Page content changed by another owner");
                return;
            }
            if (!(current instanceof Function3)) throw new IllegalStateException("Unsupported page content arity");
            Object replacement = callableBlock(factory.apply(current));
            if (!(replacement instanceof Function3)) throw new IllegalStateException("Invalid native page content");
            original = current;
            update.invoke(slot, replacement);
            installed = replacement;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { failure = error.getClass().getSimpleName(); }
    }
    public Object callableBlock(Object content) {
        try {
            // Compose emits a multi-arity container; _block expects the raw arity-3 body, not that container.
            return block.getDeclaringClass().isInstance(content) ? block.get(content) : content;
        } catch (IllegalAccessException error) { throw new IllegalStateException("Native content body inaccessible"); }
    }
    public void requireInstalled() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Page navigation must use the Salt UI thread");
        installOnUiThread();
        if (!isInstalled()) throw new IllegalStateException("Native page content is not attached");
    }
    public boolean isInstalled() {
        if (closed || installed == null) return false;
        try { return block.get(slot) == installed; }
        catch (IllegalAccessException error) { failure = error.getClass().getSimpleName(); return false; }
    }
    public String failure() { return failure; }
    @Override public void close() {
        closed = true;
        onUiThread(() -> {
            try {
                // Never overwrite a later owner's changes. Retired wrappers also delegate to their original block.
                if (installed != null && block.get(slot) == installed) update.invoke(slot, original);
            } catch (ReflectiveOperationException | RuntimeException error) { failure = error.getClass().getSimpleName(); }
        });
    }
}
