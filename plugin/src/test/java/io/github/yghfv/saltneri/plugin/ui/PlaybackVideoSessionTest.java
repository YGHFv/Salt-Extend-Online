package io.github.yghfv.saltneri.plugin.ui;

import com.xuncorp.spw.workshop.api.hook.*;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.host.*;
import io.github.yghfv.saltneri.plugin.library.*;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.transport.*;
import io.github.yghfv.saltneri.plugin.video.MusicVideoClient;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;

/** Video mode follows the host queue through discovery, unsupported songs and cancellation. */
public final class PlaybackVideoSessionTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("Video session check "+checks);}
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
        while(!condition.getAsBoolean() && System.nanoTime()<end){SwingUtilities.invokeAndWait(()->{});Thread.sleep(20);}
        check(condition.getAsBoolean());
    }
    private static final class Hooks extends PlaybackVideoHostFixture.RegistrationOnlyHooks { }
    public static void main(String[] args) throws Exception {
        var failure=new AtomicReference<Throwable>();Thread.setDefaultUncaughtExceptionHandler((t,e)->failure.set(e));
        Class<?> monitor=Class.forName("com.xuncorp.pisces.\u0786"),media=Class.forName("com.xuncorp.pisces.PiscesMediaItem");
        var update=monitor.getMethod("\u037f",media);
        Object original=((kotlinx.coroutines.flow.StateFlow<?>)monitor.getMethod("\u0528").invoke(null)).getValue();
        var profile=Salt1193Probe.inspect(PlaybackVideoSessionTest.class.getClassLoader(),"1.19.3","STEAM");check(profile.matched());
        var requests=new CopyOnWriteArrayList<String>();var gate=new CountDownLatch(1);var discovering=new CountDownLatch(1);
        var errors=new CopyOnWriteArrayList<String>();
        var transport=TransportContractTest.videoServer(PlaybackVideoFixture.bytes(),()->true);
        Path directory=Files.createTempDirectory("video-session-");
        try(var tracks=new SavedTrackStore(directory);
            var video=new SaltVideoBindings(PlaybackVideoSessionTest.class.getClassLoader(),new Hooks(),()->true,tracks::find,p->null,errors::add,
                new SaltVideoBindings.VideoRequests() {
                    public MusicVideoClient.Source discover(MusicVideoClient.Source selected,AccountCredentials account,RequestScope request) throws Exception {
                        if(selected.track().id().equals("30")) {
                            discovering.countDown();
                            while(!gate.await(20,TimeUnit.MILLISECONDS))request.check();
                            request.check();
                        }
                        return selected.track().id().equals("20")?null:new MusicVideoClient.Source(selected.track(),"123");
                    }
                    public MediaLease resolve(MusicVideoClient.Source selected,AccountCredentials account,String scope,RequestScope request) {
                        request.check();requests.add(selected.track().id());
                        return new MediaLease(MediaLease.Platform.BILIBILI,java.net.URI.create("https://test.bilivideo.com/synthetic.mp4"),
                                Map.of(),scope,Instant.now().plusSeconds(60),PlaybackVideoFixture.bytes().length);
                    }
                },transport)) {
            java.util.function.BiFunction<AccountPlatform,String,Object> track=(platform,id)->{
                try {
                    var row=new Catalog.Item(Catalog.Kind.TRACK,id,"Fixture "+id,"Artist",6000,true);
                    var saved=tracks.save(new OnlinePlaybackController.Target(platform,id),row);
                    return profile.mediaFactory().create(saved.trackId(),row.title(),row.subtitle(),platform.label(),"",saved.path());
                } catch(Exception error){throw new AssertionError(error);}
            };
            Object first=track.apply(AccountPlatform.BILIBILI,"BV17x411w7KC"),second=track.apply(AccountPlatform.BILIBILI,"BV1xx411c7mD");
            Object mv=track.apply(AccountPlatform.NETEASE,"10"),audio=track.apply(AccountPlatform.NETEASE,"20"),slow=track.apply(AccountPlatform.NETEASE,"30");
            update.invoke(null,first);await(()->video.view().available());check(requests.isEmpty());
            SwingUtilities.invokeAndWait(video::toggle);await(()->video.view().player()!=null && !video.view().loading());
            Object retired=video.view().player();check(requests.equals(List.of("BV17x411w7KC")));
            update.invoke(null,second);await(()->video.view().player()!=null && video.view().player()!=retired && !video.view().loading());
            check(requests.equals(List.of("BV17x411w7KC","BV1xx411c7mD")));
            update.invoke(null,mv);await(()->requests.contains("10") && video.view().player()!=null && !video.view().loading());
            update.invoke(null,audio);await(()->!video.view().requested() && video.view().player()==null);
            check(!requests.contains("20"));
            update.invoke(null,second);await(()->video.view().requested() && video.view().player()!=null && !video.view().loading());
            update.invoke(null,slow);check(discovering.await(3,TimeUnit.SECONDS));
            await(()->video.view().loading());SwingUtilities.invokeAndWait(video::toggle);gate.countDown();
            await(()->!video.view().requested() && video.view().player()==null);check(!requests.contains("30"));
            int before=requests.size();update.invoke(null,first);await(()->video.view().available());check(requests.size()==before && !video.view().requested());
            check(errors.isEmpty() && failure.get()==null);
        } finally {gate.countDown();update.invoke(null,original);transport.close();}
        System.out.println("PASS: "+checks+" video queue continuation, asynchronous MV discovery, missing stream, cancellation and opt-out checks");
    }
}
