package io.github.yghfv.saltneri.plugin.ui;

import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;

/** Exercise production close with a native read that ignores interruption and a delayed release. */
public final class VideoSurfaceRetirementTest {
    public static final class Handle {
        public long \u037f() { return 0; }
        public int \u037f(String key) { return 0; }
    }
    public static final class Player implements AutoCloseable {
        final AtomicInteger releases = new AtomicInteger();
        final AtomicBoolean destroyed = new AtomicBoolean();
        final CountDownLatch releasing = new CountDownLatch(1), finishRelease = new CountDownLatch(1);
        public Handle \u052b() { return new Handle(); }
        public void \u052f() {
            releasing.countDown(); uninterruptible(finishRelease);
            if (destroyed.get()) throw new AssertionError("Player destroyed before native surface release");
            releases.incrementAndGet();
        }
        public void close() { destroyed.set(true); }
    }
    public static void main(String[] args) throws Exception {
        var player = new Player();
        var surface = new SaltVideoSurface(player,null,()->{},error->{throw new AssertionError(error);});
        var reader = (ScheduledExecutorService)field(surface,"reader");
        Object lock = field(surface,"nativeLock");
        var reading = new CountDownLatch(1); var finishRead = new CountDownLatch(1);
        reader.execute(() -> { synchronized(lock) {reading.countDown();uninterruptible(finishRead);} });
        check(reading.await(2,TimeUnit.SECONDS),"Native read fixture did not start");
        var uiReturned = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {surface.close();surface.close();uiReturned.countDown();});
        try {
            check(uiReturned.await(2,TimeUnit.SECONDS),"Compose disposal waited for native read lock");
            check(!surface.released().isDone() && player.releases.get()==0,"Release overtook a live native read");
            surface.released().thenRun(player::close);
            finishRead.countDown();
            check(player.releasing.await(2,TimeUnit.SECONDS),"Native release did not follow read");
            var sentinel = new CountDownLatch(1);SwingUtilities.invokeLater(sentinel::countDown);
            check(sentinel.await(2,TimeUnit.SECONDS),"Native surface release blocked AWT");
            check(!player.destroyed.get(),"Player destroyed while native surface release still runs");
            player.finishRelease.countDown();surface.released().get(2,TimeUnit.SECONDS);
            check(player.releases.get()==1,"Surface released more than once");
            for(int i=0;i<100 && !player.destroyed.get();i++)Thread.sleep(5);
            check(player.destroyed.get(),"Player destruction did not follow surface release");
        } finally {finishRead.countDown();player.finishRelease.countDown();surface.close();}
        check(reader.awaitTermination(2,TimeUnit.SECONDS),"Retired frame reader did not exit");
        var previousRelease=new CompletableFuture<Void>();var replacementPlayer=new Player();
        replacementPlayer.finishRelease.countDown();
        var replacement=new SaltVideoSurface(replacementPlayer,null,()->{},error->{throw new AssertionError(error);},previousRelease);
        replacement.close();
        check(!replacementPlayer.releasing.await(100,TimeUnit.MILLISECONDS),"Unstarted replacement released the old native surface");
        previousRelease.complete(null);replacement.released().get(2,TimeUnit.SECONDS);
        check(replacementPlayer.releases.get()==1,"Replacement did not retire after predecessor release");
        System.out.println("PASS video retirement: AWT responds during stalled read/release; ordered handle destruction; idempotent close; reader exits");
    }
    private static Object field(Object target,String name) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static void uninterruptible(CountDownLatch latch) {
        boolean interrupted=false;
        while(true)try {latch.await();break;}catch(InterruptedException ignored){interrupted=true;}
        if(interrupted)Thread.currentThread().interrupt();
    }
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
