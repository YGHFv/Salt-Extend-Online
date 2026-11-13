package io.github.yghfv.saltneri.plugin.applemusic;

import io.github.yghfv.saltneri.plugin.transport.ByteRange;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;

/** Closing must abort logically before a delayed CDP read/browser cleanup drains. */
public final class AppleMusicRetirementTest {
    public static void main(String[] args) throws Exception {
        var reading=new CountDownLatch(1);var finishRead=new CountDownLatch(1);
        var closing=new CountDownLatch(1);var finishClose=new CountDownLatch(1);var closes=new AtomicInteger();
        var stall=new AtomicBoolean();
        var source=new AppleMusicPcmSource(1000,()->true,()->new AppleMusicPcmSource.Decoder() {
            public void seek(long frame) {}
            public byte[] pull(int bytes) {
                if(stall.get()){reading.countDown();uninterruptible(finishRead);}
                byte[] pcm=new byte[bytes];java.util.Arrays.fill(pcm,(byte)7);return pcm;
            }
            public void pause() {throw new AssertionError("Revocation attempted another CDP request");}
            public void close() {check(!SwingUtilities.isEventDispatchThread(),"Browser cleanup ran on AWT");closing.countDown();uninterruptible(finishClose);closes.incrementAndGet();}
        });
        source.read(new ByteRange(44,16427),()->true);
        var blocks=AppleMusicPcmSource.class.getDeclaredField("blocks");blocks.setAccessible(true);
        byte[] retained=(byte[])((java.util.Map<?,?>)blocks.get(source)).values().iterator().next();
        stall.set(true);
        var result=new AtomicReference<Throwable>();
        var reader=new Thread(()->{try{source.read(new ByteRange(16428,16683),()->true);result.set(new AssertionError("Retired read published PCM"));}catch(Throwable e){result.set(e);}},"apple-pcm-fixture");
        reader.start();check(reading.await(2,TimeUnit.SECONDS),"PCM fixture did not read");
        try {
            var ui=new CountDownLatch(1);SwingUtilities.invokeLater(()->{source.close();source.close();ui.countDown();});
            check(ui.await(2,TimeUnit.SECONDS),"PCM retirement waited for CDP/read lock on AWT");
            check(closing.await(2,TimeUnit.SECONDS),"Browser cleanup did not start independently of PCM monitor");
            // This read must fail immediately even while the previous read still owns the monitor.
            var revoked=new CompletableFuture<Boolean>();
            Thread.ofVirtual().start(()->{try{source.read(new ByteRange(0,43),()->true);revoked.complete(false);}catch(IOException expected){revoked.complete(true);}});
            check(revoked.get(2,TimeUnit.SECONDS),"Revoked PCM read remained available");
            var sentinel=new CountDownLatch(1);SwingUtilities.invokeLater(sentinel::countDown);
            check(sentinel.await(2,TimeUnit.SECONDS),"Browser cleanup blocked AWT");
        } finally {finishRead.countDown();finishClose.countDown();source.close();}
        source.released().get(2,TimeUnit.SECONDS);reader.join(2000);
        check(result.get() instanceof IOException && closes.get()==1,"PCM read cancellation/browser ownership mismatch");
        check(((java.util.Map<?,?>)blocks.get(source)).isEmpty(),"Retirement retained PCM cache");
        for(byte sample:retained)check(sample==0,"Retirement did not wipe previously cached PCM");

        var opening=new CountDownLatch(1);var finishOpen=new CountDownLatch(1);var lateCloses=new AtomicInteger();
        var late=new AppleMusicPcmSource(1000,()->true,()->{
            opening.countDown();uninterruptible(finishOpen);
            return new AppleMusicPcmSource.Decoder() {
                public void seek(long frame){}public byte[] pull(int bytes){return new byte[bytes];}public void pause(){}
                public void close(){lateCloses.incrementAndGet();}
            };
        });
        var prepared=new CompletableFuture<Boolean>();Thread.ofVirtual().start(()->{try{late.prepare();prepared.complete(false);}catch(IOException expected){prepared.complete(true);}});
        check(opening.await(2,TimeUnit.SECONDS),"Late decoder fixture did not open");
        late.close();finishOpen.countDown();
        check(prepared.get(2,TimeUnit.SECONDS),"Decoder opening survived retirement");
        late.released().get(2,TimeUnit.SECONDS);check(lateCloses.get()==1,"Late-opened decoder leaked or closed twice");
        System.out.println("PASS Apple PCM retirement: UI responds during CDP read/cleanup; revoked reads fail immediately; browser closes once; buffers drain");
    }
    private static void uninterruptible(CountDownLatch latch) {while(true)try{latch.await();return;}catch(InterruptedException ignored){}}
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
