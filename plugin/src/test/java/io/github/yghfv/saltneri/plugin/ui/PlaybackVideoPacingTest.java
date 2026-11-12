package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.*;
import androidx.compose.ui.awt.ComposeWindow;
import com.xuncorp.spw.workshop.api.hook.*;
import io.github.yghfv.saltneri.plugin.transport.*;
import java.lang.reflect.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import kotlin.Unit;

/** Continuous 720p60 frames through the production surface and a real default Direct3D window. */
public final class PlaybackVideoPacingTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void await(java.util.function.BooleanSupplier ready) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!ready.getAsBoolean() && System.nanoTime()<until){SwingUtilities.invokeAndWait(()->{});Thread.sleep(20);}
        check(ready.getAsBoolean(),"Video frame did not become ready");
    }
    private static final class Hooks extends HookRegistrar {
        public HookHandle afterComposable(String c,String m,String i,List<String> p,int n,ComposableHook h) {
            return new HookHandle(){public void unhook(){}public void close(){}};
        }
        public HookHandle hookMethod(String c,String m,List<String> p,MethodHook h){return new HookHandle(){public void close(){}public void unhook(){}};}
        public HookHandle replaceComposable(String c,String m,String i,List<String> p,int n,ComposableHook h){
            return new HookHandle(){public void unhook(){}public void close(){}};
        }
        public <S> HookHandle appendContent(String c,String m,String i,String a,Class<S> s,List<String> p,int n,ContentHook<S> h){throw new AssertionError();}
    }
    public static void main(String[] args) throws Exception {
        clock();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{failure.set(error);error.printStackTrace();});
        byte[] motion=PlaybackVideoMotionFixture.bytes();
        try(var server=TransportContractTest.videoServer(motion,()->true);
            var video=new SaltVideoBindings(PlaybackVideoPacingTest.class.getClassLoader(),new Hooks(),()->true,
                    (id,path)->null,p->null,message->failure.set(new AssertionError(message)))) {
            // Isolate frame pacing from the unrelated audio engine; production sync is covered by clock/render tests.
            Field worker=SaltVideoBindings.class.getDeclaredField("worker");worker.setAccessible(true);
            ((ScheduledExecutorService)worker.get(video)).shutdownNow();
            synchronized(video){} // Join any in-progress selection tick.
            URI clip=server.register(new MediaLease(MediaLease.Platform.BILIBILI,URI.create("https://test.bilivideo.com/motion.mp4"),
                    Map.of(),"motion",Instant.now().plusSeconds(60),motion.length));
            Object player=Class.forName("org.openani.mediamp.mpv.\u0588").getConstructor(Object.class,kotlin.coroutines.CoroutineContext.class)
                    .newInstance(video,kotlin.coroutines.EmptyCoroutineContext.INSTANCE);
            Field owner=SaltVideoBindings.class.getDeclaredField("player");owner.setAccessible(true);owner.set(video,player);
            Object handle=player.getClass().getMethod("\u052b").invoke(player);
            Method set=handle.getClass().getMethod("\u0528",String.class,String.class);
            Method number=handle.getClass().getMethod("\u0529",String.class);
            Method command=handle.getClass().getMethod("\u037f",String[].class);
            set.invoke(handle,"vd","lavc:*");set.invoke(handle,"aid","no");set.invoke(handle,"mute","yes");set.invoke(handle,"pause","yes");
            command.invoke(handle,(Object)new String[]{"loadfile",clip.toString(),"replace"});
            Method content=Class.forName("io.github.yghfv.saltneri.plugin.ui.SaltVideoContentKt")
                    .getMethod("access$VideoSurface",SaltVideoBindings.class,Object.class,Composer.class,int.class);
            AtomicReference<ComposeWindow> window=new AtomicReference<>();
            SwingUtilities.invokeAndWait(()->{
                var actual=new ComposeWindow();window.set(actual);
                actual.setTitle("Salt video continuous frame regression");actual.setSize(1280,780);
                actual.setAlwaysOnTop(true); // Screen sampling must observe this fixture on the interactive runner.
                actual.setContent((scope,composer,flags)->{
                    try{content.invoke(null,video,player,composer,0);}catch(Exception error){throw new AssertionError(error);}
                    return Unit.INSTANCE;
                });
                actual.setVisible(true);
            });
            try {
                var actual=window.get();var robot=new java.awt.Robot();
                Field surfaces=SaltVideoBindings.class.getDeclaredField("surfaces");surfaces.setAccessible(true);
                AtomicReference<SaltVideoSurface> surface=new AtomicReference<>();
                await(()->{
                    synchronized(video) {
                        var all=(Set<SaltVideoSurface>)get(surfaces,video);
                        if(!all.isEmpty())surface.set(all.iterator().next());
                    }
                    return surface.get()!=null && surface.get().publishedFrames()>0;
                });
                check(actual.getRenderApi()==org.jetbrains.skiko.GraphicsApi.DIRECT3D,"Default Direct3D renderer required");
                // Let native decoding, JIT and the newly foregrounded window settle before measurement.
                set.invoke(handle,"pause","no");Thread.sleep(1500);
                long before=surface.get().publishedFrames(),draws=surface.get().drawnFrames(),start=System.nanoTime(),lastChange=start;
                double nativeStart=(double)number.invoke(handle,"time-pos");
                var gaps=new ArrayList<Double>();int previous=-1,changes=0;
                while(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(3500)) {
                    if(failure.get()!=null)throw new AssertionError("Continuous draw failed",failure.get());
                    var origin=actual.getLocationOnScreen();
                    int gray=robot.getPixelColor(origin.x+actual.getWidth()/2,origin.y+actual.getHeight()/2).getRed();
                    if(gray!=previous) {
                        long now=System.nanoTime();
                        if(previous!=-1)gaps.add((now-lastChange)/1e6);
                        lastChange=now;previous=gray;changes++;
                    }
                    Thread.sleep(5);
                }
                double seconds=(System.nanoTime()-start)/1e9;
                double publishFps=(surface.get().publishedFrames()-before)/seconds;
                double drawFps=(surface.get().drawnFrames()-draws)/seconds,screenFps=changes/seconds;
                double nativeElapsed=(double)number.invoke(handle,"time-pos")-nativeStart;
                Collections.sort(gaps);
                double p95=gaps.get(Math.min(gaps.size()-1,(int)(gaps.size()*0.95)));
                System.out.printf(Locale.ROOT,"Motion: publish=%.1f fps, Compose draw=%.1f fps, screen changes=%.1f fps, screen p95=%.1f ms, mpv elapsed=%.2f/%.2f s%n",
                        publishFps,drawFps,screenFps,p95,nativeElapsed,seconds);
                // A finite observation window can omit one frame at its start/end boundary.
                double edgeFrame=1/seconds;
                check(publishFps+edgeFrame>=40 && drawFps+edgeFrame>=35,"Frame delivery became a slideshow");
                check(screenFps+edgeFrame>=30 && p95<90,"Real window did not display continuous moving frames");
                check(Math.abs(nativeElapsed-seconds)<0.3,"Native video clock stalled");
                // A busy UI must retain the newest frame instead of accumulating stale frame callbacks.
                SwingUtilities.invokeAndWait(()->{
                    try{Thread.sleep(250);}catch(InterruptedException error){throw new AssertionError(error);}
                });
                long recovered=surface.get().publishedFrames();
                await(()->surface.get().publishedFrames()>recovered+3);
                set.invoke(handle,"pause","yes");Thread.sleep(150);
                long paused=surface.get().publishedFrames();Thread.sleep(220);
                check(surface.get().publishedFrames()-paused<=1,"Paused video repeatedly copies unchanged pixels");
                var originalDimensions=new AtomicReference<String>();
                SwingUtilities.invokeAndWait(()->{
                    var frame=surface.get().frame(1280,720);
                    check(frame!=null && frame.getWidth()<=1280 && frame.getHeight()<=720,"Readback exceeds the bounded native ring");
                    originalDimensions.set(frame.getWidth()+"x"+frame.getHeight());
                });
                // Window resizing must redraw the paused raster without rebuilding native buffer rings.
                for(int width:new int[]{600,680,520,1100,600,580}) {
                    long resizeStart=surface.get().drawnFrames();
                    SwingUtilities.invokeAndWait(()->actual.setSize(width,420));
                    await(()->surface.get().drawnFrames()>resizeStart);
                    var dimensions=new AtomicReference<String>();
                    SwingUtilities.invokeAndWait(()->{
                        var transform=actual.getGraphicsConfiguration().getDefaultTransform();
                        var frame=surface.get().frame((int)(actual.getContentPane().getWidth()*transform.getScaleX()),
                                (int)(actual.getContentPane().getHeight()*transform.getScaleY()));
                        check(frame!=null && (frame.getWidth()+"x"+frame.getHeight()).equals(originalDimensions.get())
                                && Math.abs((double)frame.getWidth()/frame.getHeight()-16d/9)<0.015,
                                "Window resize rebuilt the native ring or changed the source aspect ratio");
                        dimensions.set(frame==null?"null":frame.getWidth()+"x"+frame.getHeight());
                    });
                    System.out.println("Resize "+width+": "+dimensions.get());
                }
                check(surface.get().publishedFrames()-paused<=1,"Window resize recaptured unchanged paused video");
                check(failure.get()==null,"Uncaught error during resize");
            } finally {SwingUtilities.invokeAndWait(()->window.get().dispose());}
        }
        SwingUtilities.invokeAndWait(()->{});
        check(failure.get()==null,"Native surface release failed");
        System.out.println("PASS: "+checks+" continuous frame pacing, clock, pause and repeated resize checks");
    }
    private static Object get(Field field,Object owner) {
        try{return field.get(owner);}catch(Exception error){throw new AssertionError(error);}
    }
    private static void clock() {
        var clock=new VideoAudioClock();
        for(int i=0;i<=50;i++) {
            var sample=clock.sample((i/10)*1000,true,1,i*100_000_000L);
            check(!sample.discontinuity() && Math.abs(sample.seconds()-i/10d)<0.001,"Coarse progress sample caused a seek");
        }
        check(clock.sample(12000,true,1,5_100_000_000L).discontinuity(),"Forward seek was lost");
        check(clock.sample(3000,true,1,5_200_000_000L).discontinuity(),"Backward seek was lost");
        check(Math.abs(clock.sample(3000,false,1,5_300_000_000L).seconds()-3)<0.001,"Pause moved the clock");
        clock=new VideoAudioClock();
        clock.sample(1000,true,2,0);
        check(Math.abs(clock.sample(1000,true,2,300_000_000L).seconds()-1.6)<0.001,"Speed interpolation failed");
        check(clock.sample(1000,true,2,8_000_000_000L).seconds()<=3.4,"Stale clock extrapolated indefinitely");
    }
}
