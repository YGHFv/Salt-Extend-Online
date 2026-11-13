package io.github.yghfv.saltneri.plugin.ui;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.transport.*;
import io.github.yghfv.saltneri.plugin.video.MusicVideoClient;
import java.lang.reflect.*;
import java.net.URI;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import kotlin.coroutines.CoroutineContext;

/** Production video tick/open/close with native operations deliberately ignoring interruption. */
public final class VideoControlResponsivenessTest {
    public static final class Handle {
        final CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);
        public int width(String key) {entered.countDown();uninterruptible(finish);return 0;}
        public boolean property(String key,String value) {
            check(!SwingUtilities.isEventDispatchThread(),"mpv configuration ran on AWT");
            entered.countDown();uninterruptible(finish);throw new IllegalStateException("Controlled load failure");
        }
    }
    public static final class Player implements AutoCloseable {
        static volatile Player opened;
        final Handle handle=new Handle();
        final AtomicInteger closes=new AtomicInteger();
        final CountDownLatch closing=new CountDownLatch(1),finishClose=new CountDownLatch(1);
        public Player(Object owner,CoroutineContext context) {opened=this;}
        public Handle handle() {return handle;}
        public void close() {
            check(!SwingUtilities.isEventDispatchThread(),"mpv destruction ran on AWT");
            closing.countDown();uninterruptible(finishClose);closes.incrementAndGet();
        }
    }
    private static SaltVideoBindings binding() throws Exception {
        var video=new SaltVideoBindings(VideoControlResponsivenessTest.class.getClassLoader(),
                new PlaybackVideoHostFixture.RegistrationOnlyHooks(),()->true,(id,path)->null,p->null,text->{},
                new SaltVideoBindings.VideoRequests() {
                    public MusicVideoClient.Source discover(MusicVideoClient.Source selected,AccountCredentials account,RequestScope request) {return null;}
                    public MediaLease resolve(MusicVideoClient.Source selected,AccountCredentials account,String scope,RequestScope request) {return null;}
                },new LoopbackRangeServer());
        var old=(ScheduledExecutorService)get(video,"worker");old.shutdownNow();
        check(old.awaitTermination(2,TimeUnit.SECONDS),"Selection worker did not stop");
        set(video,"worker",Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"video-control-fixture");t.setDaemon(true);return t;}));
        return video;
    }
    public static void main(String[] args) throws Exception {
        var video=binding();var player=new Player(null,null);player.finishClose.countDown();
        set(video,"player",player);set(video,"handle",player.handle);set(video,"videoMode",true);
        set(video,"integer",Handle.class.getMethod("width",String.class));set(video,"loadDeadline",Long.MAX_VALUE);
        Method tick=SaltVideoBindings.class.getDeclaredMethod("tick");tick.setAccessible(true);
        var worker=(ScheduledExecutorService)get(video,"worker");
        worker.execute(()->{try{tick.invoke(video);}catch(Exception e){throw new AssertionError(e);}});
        try {
            check(player.handle.entered.await(2,TimeUnit.SECONDS),"Native query did not start");
            ui(()->{video.toggle();video.close();},"Native query blocked video toggle/disposal");
            check(player.closes.get()==0,"Player destroyed during active native query");
        } finally {player.handle.finish.countDown();video.close();}
        check(player.closing.await(2,TimeUnit.SECONDS),"Retired query player was not closed");
        check(worker.awaitTermination(2,TimeUnit.SECONDS),"Query worker did not exit");

        var loading=binding();set(loading,"playerConstructor",Player.class.getConstructor(Object.class,CoroutineContext.class));
        set(loading,"handleGetter",Player.class.getMethod("handle"));set(loading,"property",Handle.class.getMethod("property",String.class,String.class));
        Method start=SaltVideoBindings.class.getDeclaredMethod("startPlayer",long.class,AccountCredentials.class,URI.class);start.setAccessible(true);
        Player previous=Player.opened;
        ui(()->{try{start.invoke(loading,0L,null,URI.create("file:///synthetic.mp4"));}catch(Exception e){throw new AssertionError(e);}},"Native loading blocked AWT");
        long openedDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(Player.opened==previous && System.nanoTime()<openedDeadline)Thread.sleep(5);
        check(Player.opened!=previous,"Background player constructor did not run");
        Player created=Player.opened;
        try {
            check(created.handle.entered.await(2,TimeUnit.SECONDS),"Native load fixture did not start");
            ui(loading::close,"Plugin disposal blocked on in-progress mpv configuration");
            created.handle.finish.countDown();
            check(created.closing.await(2,TimeUnit.SECONDS),"Failed/stale load did not retire");
            ui(()->{},"Failed mpv destruction blocked AWT");
        } finally {created.handle.finish.countDown();created.finishClose.countDown();loading.close();}
        check(((ScheduledExecutorService)get(loading,"worker")).awaitTermination(2,TimeUnit.SECONDS),"Loading worker did not exit");
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(created.closes.get()==0 && System.nanoTime()<end)Thread.sleep(5);
        check(created.closes.get()==1,"Failed player ownership was not released exactly once");
        var failing=binding();set(failing,"playerConstructor",Player.class.getConstructor(Object.class,CoroutineContext.class));
        set(failing,"handleGetter",Player.class.getMethod("handle"));set(failing,"property",Handle.class.getMethod("property",String.class,String.class));
        Player beforeFailure=Player.opened;
        start.invoke(failing,0L,null,URI.create("file:///synthetic.mp4"));
        long constructed=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(Player.opened==beforeFailure && System.nanoTime()<constructed)Thread.sleep(5);
        check(Player.opened!=beforeFailure,"Failure fixture did not open");
        Player rejected=Player.opened;
        try {
            check(rejected.handle.entered.await(2,TimeUnit.SECONDS),"Failure fixture did not configure");
            ui(()->{try{start.invoke(failing,0L,null,URI.create("file:///queued.mp4"));}catch(Exception e){throw new AssertionError(e);}},"Queued load blocked AWT");
            rejected.finishClose.countDown();rejected.handle.finish.countDown();
            var controls=(ScheduledExecutorService)get(failing,"worker");controls.submit(()->{}).get(2,TimeUnit.SECONDS);
            ui(()->{},"Failure publication blocked AWT");
            check(Player.opened==rejected && failing.view().player()==null,"Queued load revived a failed video generation");
        } finally {
            failing.close();rejected.handle.finish.countDown();rejected.finishClose.countDown();
            Player last=Player.opened;last.handle.finish.countDown();last.finishClose.countDown();
        }
        check(((ScheduledExecutorService)get(failing,"worker")).awaitTermination(2,TimeUnit.SECONDS),"Failed generation worker did not exit");
        System.out.println("PASS video control: stalled native query/load/destroy never blocks AWT; destruction follows queries; stale load not published");
    }
    private static Object get(Object target,String name) throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,String name,Object value) throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static void ui(Runnable action,String message) throws Exception {
        var returned=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        SwingUtilities.invokeLater(()->{try{action.run();}catch(Throwable e){failure.set(e);}finally{returned.countDown();}});
        check(returned.await(2,TimeUnit.SECONDS),message);if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private static void uninterruptible(CountDownLatch latch) {while(true)try{latch.await();return;}catch(InterruptedException ignored){}}
    private static void check(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
