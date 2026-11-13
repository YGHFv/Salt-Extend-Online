package io.github.yghfv.saltneri.plugin.ui;

import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.*;
import javax.swing.*;

/** Native WndProc predecessor addresses do not keep JNA's weak callback keys alive. */
public final class NativeWindowCallbackRetention {
    // Only JDK containers and host callbacks live here, never plugin-owned listeners or closures.
    // The root pane owns the pins across PF4J unload; destroying the window releases them.
    private static final String KEY = "io.github.yghfv.saltneri.native-window-callbacks";
    private NativeWindowCallbackRetention() { }

    public static void capture() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return;
        try {
            Field field = CallbackReference.class.getDeclaredField("callbackMap");
            field.setAccessible(true);
            Map<?, ?> weak = (Map<?, ?>) field.get(null);
            var callbacks = new ArrayList<Callback>();
            synchronized (weak) {
                for (Object key : weak.keySet())
                    if (key instanceof Callback callback && nativeWindowCallback(callback)) callbacks.add(callback);
            }
            // Capture strong references before transformation, then transfer them on AWT.
            // Never wait for AWT while plugin startup/stop holds its lifecycle monitor.
            if (callbacks.isEmpty()) return;
            Runnable transfer = () -> {
                for (Window window : Window.getWindows()) {
                    if (!window.isDisplayable() || !(window instanceof RootPaneContainer root)) continue;
                    var handles = new HashSet<Long>();
                    handles(window, handles);
                    var owned = new ArrayList<Callback>();
                    for (Callback callback : callbacks) {
                        try {
                            Object hwnd = callback.getClass().getMethod("getOriginalHwnd").invoke(callback);
                            Pointer pointer = ((com.sun.jna.PointerType) hwnd).getPointer();
                            if (handles.contains(Pointer.nativeValue(pointer))) owned.add(callback);
                        } catch (ReflectiveOperationException | RuntimeException ignored) { }
                    }
                    retain(root.getRootPane(), owned);
                }
            };
            if (EventQueue.isDispatchThread()) transfer.run(); else EventQueue.invokeLater(transfer);
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }

    private static boolean nativeWindowCallback(Callback callback) {
        for (Class<?> type = callback.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals("com.moriafly.salt.ui.platform.windows.BasicWindowProc")
                    || type.getName().equals("com.moriafly.salt.ui.window.windows.BasicWindowProc"))
                return callback.getClass().getClassLoader() == type.getClassLoader();
        }
        return false;
    }

    private static void handles(Component component, Set<Long> handles) {
        if ((component instanceof Window || component instanceof Canvas) && component.isDisplayable())
            handles.add(Pointer.nativeValue(Native.getComponentPointer(component)));
        if (component instanceof Container container)
            for (Component child : container.getComponents()) handles(child, handles);
    }

    @SuppressWarnings("unchecked")
    static void retain(JRootPane root, Collection<? extends Callback> callbacks) {
        if (callbacks.isEmpty()) return;
        Object previous = root.getClientProperty(KEY);
        Map<Long, Object> pins;
        if (previous instanceof Map<?, ?>) pins = (Map<Long, Object>) previous;
        else { pins = new LinkedHashMap<>(); root.putClientProperty(KEY, pins); }
        for (Callback callback : callbacks)
            pins.putIfAbsent(Pointer.nativeValue(CallbackReference.getFunctionPointer(callback)), callback);
    }
}
