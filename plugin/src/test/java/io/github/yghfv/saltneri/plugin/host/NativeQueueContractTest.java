package io.github.yghfv.saltneri.plugin.host;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real reflection port with a recording controller, not a running audio engine. */
public final class NativeQueueContractTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Queue check " + checks); }
    public static final class Controller {
        final List<String> calls = new ArrayList<>();
        boolean failAdd, failPlay, paused = true;
        Runnable onAdd = () -> {};
        public void addToPlay(Object item) { calls.add("add"); if (failAdd) throw new IllegalStateException("SECRET"); onAdd.run(); }
        public void play() { calls.add("play"); if (failPlay) throw new IllegalStateException("SECRET"); paused = false; }
        public void playOrPause() { throw new AssertionError("Never toggle"); }
        public void setPlaybackQueue(List<Object> items,int index) { if(items.size()!=3 || index!=0) throw new AssertionError(); calls.add("replace"); }
    }
    public static void main(String[] args) throws Exception {
        Controller controller = new Controller(); AtomicBoolean allowed = new AtomicBoolean(true);
        Salt1193PlaybackSession.submitAndPlay(controller, Object.class, new Object(), allowed::get);
        check(controller.calls.equals(List.of("add", "play")) && !controller.paused);
        controller.calls.clear();
        Salt1193PlaybackSession.submitAndPlay(controller, Object.class, new Object(), allowed::get);
        check(controller.calls.equals(List.of("add", "play")) && !controller.paused);
        for (int mode = 0; mode < 4; mode++) {
            controller.calls.clear(); controller.failAdd = mode == 1; controller.failPlay = mode == 2;
            allowed.set(mode != 0); controller.onAdd = mode == 3 ? () -> allowed.set(false) : () -> {};
            try { Salt1193PlaybackSession.submitAndPlay(controller, Object.class, new Object(), allowed::get); throw new AssertionError("Expected failure"); }
            catch (SecurityException | ReflectiveOperationException expected) { check(!expected.toString().contains("SECRET")); }
            check(controller.calls.equals(mode == 0 ? List.of() : mode == 2 ? List.of("add", "play") : List.of("add")));
        }
        var host = Class.forName("com.xuncorp.voxzen.service.PlaybackController", false, NativeQueueContractTest.class.getClassLoader());
        check(host.getMethod("play").getReturnType() == void.class);
        controller.failAdd=false; controller.failPlay=false; allowed.set(true); controller.calls.clear();
        Salt1193PlaybackSession.replaceAndPlay(controller,List.of(new Object(),new Object(),new Object()),allowed::get);
        check(controller.calls.equals(List.of("replace","play")));
        check(host.getMethod("setPlaybackQueue",List.class,int.class).getReturnType()==void.class);
        System.out.println("PASS: " + checks + " explicit add-then-play ordering/revoke/failure checks; recording controller only");
    }
}
