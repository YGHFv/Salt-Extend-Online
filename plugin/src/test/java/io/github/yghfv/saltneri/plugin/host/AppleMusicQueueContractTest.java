package io.github.yghfv.saltneri.plugin.host;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.library.*;
import io.github.yghfv.saltneri.plugin.applemusic.AppleMusicPcmSource;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import java.time.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Native playback controller ownership: cancelled preparation vs transferred Apple PCM. */
public final class AppleMusicQueueContractTest {
    private static void await(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean() && System.nanoTime()<end)Thread.sleep(10);
        if(!condition.getAsBoolean())throw new AssertionError("Apple queue fixture timed out");
    }
    public static void main(String[] args) throws Exception {
        var target=new OnlinePlaybackController.Target(AccountPlatform.APPLEMUSIC,"us:123");
        AtomicInteger closed=new AtomicInteger(),submitted=new AtomicInteger(),inserted=new AtomicInteger();
        AtomicReference<MediaLease> playing=new AtomicReference<>();
        try(var player=new OnlinePlaybackController(p->null,()->true,()->true,new OnlinePlaybackController.Player() {
            public void play(OnlinePlaybackController.Audio audio,BooleanSupplier active) {
                if(audio.key().platform()!=TrackKey.Platform.APPLEMUSIC || !audio.transportMatches())throw new AssertionError("Wrong native source");
                playing.set(audio.lease());submitted.incrementAndGet();
            }
            public void insert(OnlinePlaybackController.Audio audio,BooleanSupplier active,OnlinePlaybackController.QueueAction action) {
                playing.set(audio.lease());inserted.incrementAndGet();
            }
        },Clock.systemUTC(),account->(t,q,s,request)->{
            var pcm=new AppleMusicPcmSource(1000,()->true,()->new AppleMusicPcmSource.Decoder() {
                public void seek(long frame) {}
                public byte[] pull(int count) {return new byte[count];}
                public void pause() {}
                public void close() {closed.incrementAndGet();}
            });
            pcm.prepare();
            Instant expiry=Instant.now().plusSeconds(60);
            return new OnlinePlaybackController.Audio(TrackKey.appleMusic(t.id()),new Salt1193PlaybackSession.Metadata("Apple fixture","Artist","",""),
                MediaLease.appleMusic(pcm,s,expiry,()->true),expiry,t);
        })) {
            player.prepare(target);await(()->Boolean.TRUE.equals(player.snapshot().get("ready")));
            player.invalidate("Cancelled");if(closed.get()!=1)throw new AssertionError("Cancelled browser leaked");
            player.playSelected(target,()->{});await(()->submitted.get()==1);
            player.invalidate("Page changed");if(closed.get()!=1)throw new AssertionError("Navigation closed native playback");
            playing.get().release();if(closed.get()!=2)throw new AssertionError("Transferred source did not close");
            player.insertSelected(target,()->{},OnlinePlaybackController.QueueAction.AFTER_CURRENT);await(()->inserted.get()==1);
            player.invalidate("Navigation");if(closed.get()!=2)throw new AssertionError("Inserted native track revoked");
            playing.get().release();
        }
        if(closed.get()!=3)throw new AssertionError("PCM ownership mismatch");
        System.out.println("PASS: Apple native queue play/insert, cancelled preparation cleanup and playback survival across navigation");
    }
}
