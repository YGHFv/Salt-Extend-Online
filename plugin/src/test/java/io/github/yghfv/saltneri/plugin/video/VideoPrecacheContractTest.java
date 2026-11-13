package io.github.yghfv.saltneri.plugin.video;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.Target;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.transport.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class VideoPrecacheContractTest {
    private static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
    private static void await(java.util.function.BooleanSupplier value) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!value.getAsBoolean() && System.nanoTime()<end)Thread.sleep(20);
        check(value.getAsBoolean(),"Video prefetch did not complete");
    }
    public static void main(String[] args) throws Exception {
        byte[] bytes=new byte[1024*1024+17];Arrays.fill(bytes,(byte)39);
        var targets=List.of("a","b","c","d","e").stream().map(id->new Target(AccountPlatform.BILIBILI,id)).toList();
        var order=new AtomicReference<List<Target>>(targets);var cap=new AtomicReference<>(VideoResolution.P720);
        var video=new AtomicBoolean();var calls=new CopyOnWriteArrayList<Target>();var qualities=new CopyOnWriteArrayList<VideoResolution>();
        try(var transport=TransportContractTest.videoServer(bytes,()->true)) {
            transport.cacheLimit(16L*1024*1024);
            try(var cache=new VideoPrecache(order::get,cap::get,p->null,()->true,video::get,transport,(target,resolution,account,request)->{
                request.check();calls.add(target);qualities.add(resolution);
                return new MediaLease(MediaLease.Platform.BILIBILI,URI.create("https://test.bilivideo.com/fixture.mp4"),
                        Map.of(),"anonymous-video",Instant.now().plusSeconds(60),bytes.length);
            });var request=new RequestScope(()->true)) {
                Thread.sleep(350);check(calls.isEmpty(),"Audio-only playback downloaded video");
                URI playing=cache.resolve(targets.getFirst(),request);
                video.set(true);await(()->transport.cacheBytes()==4L*bytes.length);
                check(cache.resolve(targets.getFirst(),request).equals(playing),"Playback did not reuse the precached lease");
                check(calls.equals(targets.subList(0,4)),"Prefetch exceeded current + next-three native order");
                check(qualities.stream().allMatch(q->q==VideoResolution.P720),"Precache ignored selected resolution");
                check(transport.isFullyCached(playing),"Current video was not fully cached");
                transport.clearCache();cache.cancel();await(()->transport.isFullyCached(playing));
                cap.set(VideoResolution.P480);await(()->!transport.isAvailable(playing));
                URI lower=cache.resolve(targets.getFirst(),request);
                check(!lower.equals(playing),"Resolution change reused old stream");
                order.set(List.of(targets.getLast()));await(()->!transport.isAvailable(lower));
                video.set(false);cache.cancel();transport.cacheLimit(0);
            }
            check(transport.cacheBytes()==0,"Video clear/disable left cached bytes");
        }
        System.out.println("PASS: video precache follows current + next-three, shares playback ranges and fences resolution/queue/cache changes");
    }
}
