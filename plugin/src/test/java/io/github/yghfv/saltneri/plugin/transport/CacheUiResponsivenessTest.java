package io.github.yghfv.saltneri.plugin.transport;

import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;

/** Slow cache IO owns the same monitor; UI budget/clear/revoke/stop must still return. */
public final class CacheUiResponsivenessTest {
    public static void main(String[] args) throws Exception {
        var server = new LoopbackRangeServer();
        var field=LoopbackRangeServer.class.getDeclaredField("cache");field.setAccessible(true);
        Object cache=field.get(server);
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        var io=new Thread(()->{synchronized(cache){locked.countDown();uninterruptible(release);}},"slow-cache-fixture");
        io.start();check(locked.await(2,TimeUnit.SECONDS),"Cache fixture did not start");
        var ui=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        SwingUtilities.invokeLater(()->{
            try {
                server.cacheLimit(1024);server.cacheLimit(2048);
                server.clearCache();
                var track=server.registerDiagnosticTone();server.revoke(track);
                check(!server.isAvailable(track),"Revocation must become logical before disk cleanup");
                server.close();
            }catch(Throwable error){failure.set(error);}finally{ui.countDown();}
        });
        try {
            check(ui.await(2,TimeUnit.SECONDS),"UI waited for disk cache monitor during maintenance/revoke/stop");
            check(failure.get()==null,"Cache operations failed: "+failure.get());
            var sentinel=new CountDownLatch(1);SwingUtilities.invokeLater(sentinel::countDown);
            check(sentinel.await(2,TimeUnit.SECONDS),"Cache cleanup blocked AWT");
        }finally{release.countDown();io.join(2000);server.close();}
        Field executor=LoopbackRangeServer.class.getDeclaredField("cacheIo");executor.setAccessible(true);
        check(((ExecutorService)executor.get(server)).awaitTermination(2,TimeUnit.SECONDS),"Maintenance did not drain and exit");
        check(server.cacheBytes()==0,"Stopped cache retained entries");
        System.out.println("PASS cache maintenance: UI budget/clear/revoke/stop respond during slow IO; revocation immediate; executor drains");
    }
    private static void uninterruptible(CountDownLatch latch) {
        while(true)try{latch.await();return;}catch(InterruptedException ignored){}
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
