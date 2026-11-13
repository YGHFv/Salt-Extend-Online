package io.github.yghfv.saltneri.plugin.ui;

import com.moriafly.salt.ui.platform.windows.BasicWindowProc;
import com.sun.jna.*;
import com.sun.jna.platform.win32.WinDef.*;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Real host predecessor chain, native HWND, forced GC, and a harmless private window message. */
public final class NativeWindowCallbackRetentionTest {
    private static final AtomicInteger messages = new AtomicInteger();
    private static final int MESSAGE = 0x8000 + 271;
    public static final class Proc extends BasicWindowProc {
        public Proc(HWND hwnd) { super(hwnd); }
        @Override public LRESULT callback(HWND hwnd, int message, WPARAM wp, LPARAM lp) {
            if (message == MESSAGE) messages.incrementAndGet();
            return super.callback(hwnd, message, wp, lp);
        }
    }
    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            System.out.println("SKIP native callback lifetime: Windows required"); return;
        }
        var window = new AtomicReference<JFrame>();
        var current = new AtomicReference<Proc>();
        var predecessor = new AtomicReference<WeakReference<Proc>>();
        var hwnd = new AtomicReference<HWND>();
        var original = new AtomicReference<Pointer>();
        var user32 = NativeLibrary.getInstance("user32");
        var set = user32.getFunction("SetWindowLongPtrW", Function.ALT_CONVENTION);
        SwingUtilities.invokeAndWait(() -> {
            var frame = new JFrame(); frame.setUndecorated(true); frame.setSize(1, 1);
            frame.setLocation(-32000, -32000); frame.setFocusableWindowState(false); frame.setVisible(true);
            window.set(frame); hwnd.set(new HWND(Native.getComponentPointer(frame)));
            Proc first = new Proc(hwnd.get());
            original.set(new Pointer(first.getOriginalWindowProc().longValue()));
            predecessor.set(new WeakReference<>(first));
            NativeWindowCallbackRetention.capture();
            current.set(new Proc(hwnd.get()));
            NativeWindowCallbackRetention.capture();
            NativeWindowCallbackRetention.capture();
        });
        try {
            for (int i = 0; i < 8; i++) { System.gc(); Thread.sleep(30); }
            check(predecessor.get().get() != null, "Native predecessor was collected while successor still forwards to it");
            SwingUtilities.invokeAndWait(() -> {
                Map<?, ?> pins = (Map<?, ?>)window.get().getRootPane().getClientProperty("io.github.yghfv.saltneri.native-window-callbacks");
                check(pins != null && pins.size() == 2, "Repeated captures must deduplicate host callback objects");
                check(pins.getClass().getClassLoader() == null, "Lifetime storage must survive plugin loader retirement");
            });
            messages.set(0);
            var result = (Pointer)user32.getFunction("SendMessageTimeoutW", Function.ALT_CONVENTION)
                    .invoke(Pointer.class, new Object[]{hwnd.get(), MESSAGE, new WPARAM(0), new LPARAM(0), 2, 2000, new com.sun.jna.ptr.PointerByReference()});
            check(result != null && Pointer.nativeValue(result) != 0, "Native message timed out");
            check(messages.get() == 2, "Message did not traverse both live host callbacks");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                set.invoke(Pointer.class, new Object[]{hwnd.get(), -4, original.get()});
                window.getAndSet(null).dispose(); current.set(null);
            });
        }
        for(int i=0;i<50 && predecessor.get().get()!=null;i++) {System.gc();Thread.sleep(20);SwingUtilities.invokeAndWait(()->{});}
        check(predecessor.get().get()==null,"Disposed window left callback pins globally retained");
        System.out.println("PASS native callback lifetime: predecessor retained through GC; real message forwarded; pins deduplicated; disposal releases pins");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
