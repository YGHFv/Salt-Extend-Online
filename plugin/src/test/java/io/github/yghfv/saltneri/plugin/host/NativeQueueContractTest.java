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
        int expectedIndex;
        Runnable onAdd = () -> {};
        public void addToPlay(Object item) { calls.add("add"); if (failAdd) throw new IllegalStateException("SECRET"); onAdd.run(); }
        public void play() { calls.add("play"); if (failPlay) throw new IllegalStateException("SECRET"); paused = false; }
        public void playOrPause() { throw new AssertionError("Never toggle"); }
        public void setPlaybackQueue(List<Object> items,int index) { if(items.size()!=3 || index!=expectedIndex) throw new AssertionError(); calls.add("replace"); }
    }
    public static final class InsertController {
        Object position, item;
        int insertions;
        boolean paused = true, fail;
        public void addToPlayNext(com.xuncorp.spc.core.queue.PlaybackQueue.\u037f position,
                                  com.xuncorp.pisces.PiscesMediaItem[] items) {
            if (fail) throw new IllegalStateException("SECRET");
            check(items.length == 1); this.position=position; item=items[0]; insertions++;
        }
        public void play() { throw new AssertionError("Insertion must not start playback"); }
        public void prepare() { throw new AssertionError("Insertion must not reload playback"); }
        public void setPlaybackQueue(List<?> items,int index) { throw new AssertionError("Insertion must not replace queue"); }
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
        controller.calls.clear(); controller.expectedIndex=2;
        Salt1193PlaybackSession.replaceAndPlay(controller,List.of(new Object(),new Object(),new Object()),2,allowed::get);
        check(controller.calls.equals(List.of("replace","play")));
        check(host.getMethod("setPlaybackQueue",List.class,int.class).getReturnType()==void.class);
        var profile=Salt1193Probe.inspect(NativeQueueContractTest.class.getClassLoader(),"1.19.3","Steam");
        Object item=profile.mediaFactory().create("saltneri:insert:test","Fixture","Artist","","","saltneri://fixture");
        var insertion=new InsertController();
        Class<?> position=Class.forName("com.xuncorp.spc.core.queue.PlaybackQueue$\u037f");
        Class<?> media=item.getClass();
        check(host.getMethod("addToPlayNext",position,java.lang.reflect.Array.newInstance(media,0).getClass()).getReturnType()==void.class);
        var actions=io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.QueueAction.values();
        String[] names={"AfterCurrent","AfterLastAdded","AtQueueEnd"};
        for(int i=1;i<actions.length;i++) {
            Salt1193PlaybackSession.submitNext(insertion,media,item,actions[i],()->true);
            check(((Enum<?>)insertion.position).name().equals(names[i-1]) && insertion.item==item && insertion.paused);
        }
        try { Salt1193PlaybackSession.submitNext(insertion,media,item,actions[1],()->false); throw new AssertionError(); }
        catch(SecurityException expected) {check(insertion.insertions==3);}
        insertion.fail=true;
        try { Salt1193PlaybackSession.submitNext(insertion,media,item,actions[1],()->true); throw new AssertionError(); }
        catch(ReflectiveOperationException expected) {check(!expected.toString().contains("SECRET") && expected.getCause()==null);}
        System.out.println("PASS: " + checks + " explicit add-then-play ordering/revoke/failure checks; recording controller only");
    }
}
