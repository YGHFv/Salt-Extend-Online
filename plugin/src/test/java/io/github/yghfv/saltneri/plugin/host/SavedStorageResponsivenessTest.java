package io.github.yghfv.saltneri.plugin.host;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.library.*;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.transport.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;

/** Stalled saved transactions and artwork must not pin UI lookups or session shutdown. */
public final class SavedStorageResponsivenessTest {
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("saved-storage-responsiveness-");
        var store=new SavedTrackStore(root);
        var entry=store.save(new OnlinePlaybackController.Target(AccountPlatform.NETEASE,"1"),row("1"));
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);var transaction=new AtomicReference<Throwable>();
        var writer=new Thread(()->{
            try{store.saveAll(AccountPlatform.NETEASE,List.of(row("2")),()->{entered.countDown();uninterruptible(finish);return true;});}
            catch(Throwable e){transaction.set(e);}
        },"saved-transaction-fixture");
        writer.start();check(entered.await(2,TimeUnit.SECONDS),"Saved transaction did not start");
        try {
            ui(()->{
                check(store.find(entry.trackId(),entry.path())==entry && store.owns(entry.trackId(),entry.path()),"Committed identity disappeared during write");
                check(store.entries().size()==1,"Uncommitted identity became visible");store.close();
                check(store.find(entry.trackId(),entry.path())==null,"Closed store stayed visible");
            },"UI lookup/shutdown waited for saved transaction monitor");
        } finally {finish.countDown();writer.join(2000);store.close();}
        check(!writer.isAlive() && transaction.get()!=null && store.entries().isEmpty(),"Closed transaction published identities");
        try(var restored=new SavedTrackStore(root);var transport=new LoopbackRangeServer()) {
            check(restored.entries().size()==1,"Interrupted transaction damaged committed saved identities");
            var selected=restored.entries().getFirst();var resolved=new CountDownLatch(1);var releaseCover=new CountDownLatch(1);var locked=new CountDownLatch(1);
            var io=new Thread(()->{synchronized(selected.cover()){locked.countDown();uninterruptible(releaseCover);}},"cover-io-fixture");io.start();
            check(locked.await(2,TimeUnit.SECONDS),"Cover fixture did not acquire IO monitor");
            var session=new SavedTrackSession(new Binding(),transport,()->true,new SavedTrackSession.Resolver() {
                public Object revision(AccountPlatform platform){return "fixture";}
                public OnlinePlaybackController.Audio resolve(SavedTrackStore.Entry e,RequestScope request) {
                    resolved.countDown();Instant expiry=Instant.now().plusSeconds(600);
                    return new OnlinePlaybackController.Audio(TrackKey.netease("1"),new Salt1193PlaybackSession.Metadata("Fixture","Artist","",""),
                            new MediaLease(MediaLease.Platform.NETEASE,URI.create("https://m7.music.126.net/fixture"),Map.of(),"fixture",expiry,100),expiry,e.target());
                }
            });
            var outcome=new AtomicReference<Throwable>();
            var warming=new Thread(()->{try{session.warm(selected,()->true);outcome.set(new AssertionError("Closed session published playback"));}catch(Throwable e){outcome.set(e);}},"saved-cover-warm-fixture");
            warming.start();check(resolved.await(2,TimeUnit.SECONDS),"Saved resolver did not run");
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(warming.getState()!=Thread.State.BLOCKED && System.nanoTime()<end)Thread.sleep(5);
            try {
                check(warming.getState()==Thread.State.BLOCKED,"Warm-up did not reach cover IO");
                ui(()->{session.close();restored.close();},"Artwork IO blocked session/store shutdown on AWT");
            } finally {releaseCover.countDown();io.join(2000);warming.join(2000);session.close();}
            check(!warming.isAlive() && outcome.get() instanceof CancellationException,"Retired artwork work revived saved playback");
        }
        var temporary=PlaybackCoverFile.reserve();temporary.replace(PlaybackCoverContractTest.png());
        var artworkLocked=new CountDownLatch(1);var releaseArtwork=new CountDownLatch(1);
        var deleting=new Thread(()->{synchronized(temporary){artworkLocked.countDown();uninterruptible(releaseArtwork);}},"temporary-artwork-fixture");
        deleting.start();check(artworkLocked.await(2,TimeUnit.SECONDS),"Temporary artwork fixture did not start");
        try {ui(()->{temporary.clear();temporary.close();temporary.close();},"Temporary artwork clear/delete blocked AWT");}
        finally {releaseArtwork.countDown();deleting.join(2000);temporary.close();}
        temporary.released().get(2,TimeUnit.SECONDS);
        check(!Files.exists(Path.of(temporary.path()).getParent()),"Temporary artwork deletion did not drain");
        System.out.println("PASS saved storage: committed identity reads stay responsive during transactions; close cancels publication; artwork IO cannot block session/store retirement");
    }
    private static Catalog.Item row(String id) {return new Catalog.Item(Catalog.Kind.TRACK,id,"Fixture","Artist",1000,true);}
    private static final class Binding implements StreamBinding {
        public void register(String path,URI uri){}public void registerDeferred(String path,Deferred source){}public void unregister(String path){}
        public ReversibleInterceptorLists attach(Object player){return null;}public boolean isAttached(){return true;}public void close(){}
    }
    private static void ui(Runnable action,String message) throws Exception {
        var returned=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        SwingUtilities.invokeLater(()->{try{action.run();}catch(Throwable e){failure.set(e);}finally{returned.countDown();}});
        check(returned.await(2,TimeUnit.SECONDS),message);if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private static void uninterruptible(CountDownLatch latch) {while(true)try{latch.await();return;}catch(InterruptedException ignored){}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
