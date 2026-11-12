package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.*;
import androidx.compose.ui.ImageComposeScene;
import androidx.compose.ui.unit.*;
import com.xuncorp.spw.workshop.api.hook.*;
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.host.*;
import io.github.yghfv.saltneri.plugin.library.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import kotlin.Unit;
import kotlin.jvm.functions.*;
import kotlinx.coroutines.Dispatchers;

/** Real native caption rendering, exact hook-site resolution and owned-track switching. */
public final class PlaybackVideoRenderTest {
    private static int checks;
    private static void check(boolean condition){checks++;if(!condition)throw new AssertionError("Playback video check "+checks);}
    private static void await(java.util.function.BooleanSupplier ready) throws Exception {
        long end=System.nanoTime()+5_000_000_000L;
        while(!ready.getAsBoolean() && System.nanoTime()<end){SwingUtilities.invokeAndWait(()->{});Thread.sleep(20);}
        check(ready.getAsBoolean());
    }
    private static int transportPixels(java.awt.Robot robot,java.awt.Window window) {
        var origin=window.getLocationOnScreen();
        var pixels=robot.createScreenCapture(new java.awt.Rectangle(origin.x+100,origin.y+window.getHeight()-145,
                window.getWidth()-200,95));
        int visible=0;
        for(int y=0;y<pixels.getHeight();y++)for(int x=0;x<pixels.getWidth();x++) {
            int rgb=pixels.getRGB(x,y);
            if(((rgb>>16)&255)>170 && ((rgb>>8)&255)>170 && (rgb&255)>170)visible++;
        }
        return visible;
    }
    @SuppressWarnings("unchecked")
    private static ProvidedValue<?>[] windowLocals(Composer composer,ProvidedValue<?>... values) {
        try {
            Class<?> defaults=Class.forName("androidx.compose.ui.\u012e$\u037f");
            Method factory=Arrays.stream(defaults.getMethods()).filter(m->m.getName().equals("\u037f")).findFirst().orElseThrow();
            Object properties=factory.invoke(null,0L,null,null,0f,false,0f,false,null,false,0f,0f,false,false,false,composer,0,16383);
            var propertiesLocal=(ProvidableCompositionLocal<Object>)Class.forName("androidx.compose.ui.\u0689").getMethod("\u0528").invoke(null);
            var stateLocal=(ProvidableCompositionLocal<Object>)Class.forName("androidx.compose.ui.\u0372").getMethod("\u037f").invoke(null);
            Method rememberState=Arrays.stream(Class.forName("androidx.compose.ui.window.WindowState_desktopKt").getMethods())
                    .filter(m->m.getName().startsWith("rememberWindowState-") && m.getParameterCount()==7).findFirst().orElseThrow();
            Object windowState=rememberState.invoke(null,null,false,null,0L,composer,0,15);
            var locals=new ArrayList<ProvidedValue<?>>(Arrays.asList(values));
            locals.add(propertiesLocal.provides(properties));locals.add(stateLocal.provides(windowState));
            Class<?> blur=Class.forName("com.xuncorp.voxzen.ui.component.\u052c");
            Object haze=Class.forName("dev.chrisbanes.haze.HazeState").getConstructor().newInstance();
            locals.add(((ProvidableCompositionLocal<Object>)blur.getMethod("\u037f").invoke(null)).provides(haze));
            locals.add(((ProvidableCompositionLocal<Object>)blur.getMethod("\u0528").invoke(null)).provides(haze));
            return locals.toArray(ProvidedValue[]::new);
        } catch(Exception error) {throw new AssertionError("Native Salt window fixture",error);}
    }
    private static final class Hooks extends HookRegistrar {
        ComposableHook content;int removed;
        public HookHandle afterComposable(String owner,String name,String caller,List<String> parameters,int priority,ComposableHook content) {
            throw new AssertionError("Video must replace the host title bar to avoid duplicate caption hit tests");
        }
        public HookHandle hookMethod(String c,String m,List<String> p,MethodHook h){throw new AssertionError();}
        public HookHandle replaceComposable(String c,String m,String i,List<String> p,int n,ComposableHook h){
            if(c.equals(SaltVideoBindings.TITLE)) {
                check(i.equals(SaltVideoBindings.WINDOW) && p.equals(SaltVideoBindings.TITLE_PARAMETERS));content=h;
            } else check(c.equals(SaltVideoBindings.TRANSPORT) && i.equals(SaltVideoBindings.TRANSPORT_CALLER) && p.equals(SaltVideoBindings.TRANSPORT_PARAMETERS));
            return new HookHandle(){public void close(){removed++;}public void unhook(){close();}};
        }
        public <S> HookHandle appendContent(String c,String m,String i,String a,Class<S> s,List<String> p,int n,ContentHook<S> h){throw new AssertionError();}
    }
    public static void main(String[] args) throws Exception {
        // The real MiniPlayer initializes the controller; supply the installed native paths.
        // AOT fixture properties use zero volume; no track is submitted to the audio engine.
        System.setProperty("isAotTraining","true");
        Class<?> nativePaths=Class.forName("com.xuncorp.pisces.\u0783");
        String[] setters={"\u037f","\u0528","\u0529","\u052a","\u052b","\u052c"};
        String[] libraries={"bass.dll","bass_fx.dll","bassmix.dll","basswasapi.dll","bassasio.dll","basscd.dll"};
        for(int i=0;i<setters.length;i++)nativePaths.getMethod(setters[i],String.class).invoke(null,
                Path.of(System.getProperty("skiko.library.path"),"resources",libraries[i]).toString());
        var uncaught=new AtomicReference<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler((t,e)->{uncaught.set(e);e.printStackTrace();if(t.getName().equals("main"))System.exit(1);});
        ClassLoader host=PlaybackVideoRenderTest.class.getClassLoader();
        Class<?> targetResolver=Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052a");
        Object target=targetResolver.getMethod("\u037f",String.class,String.class,String.class,List.class).invoke(
                targetResolver.getField("\u037f").get(null),SaltVideoBindings.TITLE,"\u037f",SaltVideoBindings.WINDOW,SaltVideoBindings.TITLE_PARAMETERS);
        check(target!=null);
        check(targetResolver.getMethod("\u037f",String.class,String.class,String.class,List.class).invoke(
                targetResolver.getField("\u037f").get(null),SaltVideoBindings.TRANSPORT,"\u037f",SaltVideoBindings.TRANSPORT_CALLER,
                SaltVideoBindings.TRANSPORT_PARAMETERS)!=null);
        Path output=Path.of(System.getProperty("java.io.tmpdir")).resolve("playback-video");Files.createDirectories(output);
        var profile=Salt1193Probe.inspect(host,"1.19.3","STEAM");check(profile.matched());
        Class<?> monitor=Class.forName("com.xuncorp.pisces.\u0786"),item=Class.forName("com.xuncorp.pisces.PiscesMediaItem");
        Method current=monitor.getMethod("\u037f",item);
        Object original=((kotlinx.coroutines.flow.StateFlow<?>)monitor.getMethod("\u0528").invoke(null)).getValue();
        Class<?> progress=Class.forName("com.xuncorp.pisces.PiscesMediaController$Progress");
        Method updateProgress=monitor.getMethod("\u037f",progress);
        Object originalProgress=((kotlinx.coroutines.flow.StateFlow<?>)monitor.getMethod("\u052a").invoke(null)).getValue();
        Hooks hooks=new Hooks();
        var toasts=new ArrayList<String>();
        try(var store=new SavedTrackStore(output.resolve("tracks"));
            var video=new SaltVideoBindings(host,hooks,()->true,store::find,p->null,toasts::add)) {
            var row=new Catalog.Item(Catalog.Kind.TRACK,"BV17x411w7KC","Video fixture","Artist",120000,true);
            var saved=store.save(new OnlinePlaybackController.Target(AccountPlatform.BILIBILI,row.target()),row);
            Object owned=profile.mediaFactory().create(saved.trackId(),row.title(),row.subtitle(),"Bilibili","",saved.path());
            current.invoke(null,owned);
            long end=System.nanoTime()+3_000_000_000L;
            while(!video.view().available() && System.nanoTime()<end){SwingUtilities.invokeAndWait(()->{});Thread.sleep(20);}
            check(video.view().available() && !video.view().requested() && !video.view().loading());
            Method draw=hooks.content.getClass().getMethod("Content",UiHookCall.class,Composer.class,int.class);
            Class<?> sheet=Class.forName("androidx.compose.ui.\u1076"),sheetValue=Class.forName("androidx.compose.ui.\u1077");
            Object expanded=sheetValue.getField("\u0528").get(null);
            Object animation=Class.forName("androidx.compose.animation.core.AnimationSpecKt").getMethod("tween",int.class,int.class,
                    Class.forName("androidx.compose.animation.core.Easing")).invoke(null,0,0,
                    Class.forName("androidx.compose.animation.core.EasingKt").getMethod("getLinearEasing").invoke(null));
            Object bottom=sheet.getConstructor(sheetValue,Density.class,Class.forName("androidx.compose.animation.core.AnimationSpec"),Function1.class)
                    .newInstance(expanded,DensityKt.Density(1f,1f),animation,(Function1<Object,Boolean>)v->true);
            Object playerSheet=Class.forName("com.xuncorp.voxzen.ui.component.PlayerSheetState").getConstructor(sheet).newInstance(bottom);
            var local=(ProvidableCompositionLocal<Object>)Class.forName("androidx.compose.ui.\u041a").getMethod("\u037f").invoke(null);
            Class<?> windowInfo=Class.forName("androidx.compose.ui.\u0f65"),alignment=Class.forName("androidx.compose.ui.\u03d4");
            Object caption=windowInfo.getConstructor(float.class,alignment,float.class,byte.class)
                    .newInstance(44f,alignment.getField("\u0528").get(null),144f,(byte)0);
            var captionLocal=(ProvidableCompositionLocal<Object>)Class.forName("androidx.compose.ui.\u0f40").getMethod("\u037f").invoke(null);
            var navigatorLocal=(ProvidableCompositionLocal<Object>)Class.forName("com.xuncorp.voxzen.ui.navigation.AppNavigationKt").getMethod("getLocalNavigator").invoke(null);
            Object navigator=NavigationUiRenderTest.navigator();
            var hitTestLocal=(ProvidableCompositionLocal<Object>)Class.forName("androidx.compose.ui.\u056e").getMethod("\u037f").invoke(null);
            Class<?> hitTestScope=Class.forName("androidx.compose.ui.\u0578");
            Object hitTest=hitTestScope.getConstructor().newInstance();
            Method captionActive=hitTestScope.getMethod("\u037f");
            Object titleMode=Class.forName(SaltVideoBindings.TITLE_PARAMETERS.getFirst()).getField("\u0529").get(null);
            Object titleState=Class.forName(SaltVideoBindings.TITLE_PARAMETERS.get(1)).getConstructor().newInstance();
            UiHookCall call=new UiHookCall(){
                public Object getThisObject(){return null;}
                public List<Object> getArguments(){return Arrays.asList(titleMode,titleState,video.originalVideoState(),null);}
                public Object getArgument(String name){return null;}
            };
            SwingUtilities.invokeAndWait(()->{
                var scene=new ImageComposeScene(1160,770,DensityKt.Density(1f,1f),LayoutDirection.Ltr,Dispatchers.getUnconfined(),
                        (composer,flags)->{
                            CompositionLocalKt.CompositionLocalProvider(windowLocals(composer,local.provides(playerSheet),captionLocal.provides(caption),hitTestLocal.provides(hitTest),navigatorLocal.provides(navigator)),(c,f)->{
                                try{draw.invoke(hooks.content,call,c,0);}catch(ReflectiveOperationException e){throw new AssertionError(e);}
                                return Unit.INSTANCE;
                            },composer,0);
                            return Unit.INSTANCE;
                        });
                try {
                    HeaderSearchRenderTest.render(scene);
                    check(Boolean.TRUE.equals(captionActive.invoke(hitTest)));
                    var button=HeaderSearchRenderTest.find(scene,"播放视频",androidx.compose.ui.semantics.SemanticsActions.INSTANCE.getOnClick());
                    check(button!=null);
                    var bounds=button.getBoundsInRoot();
                    check(bounds.getTop()<10 && bounds.getRight()<1010 && bounds.getLeft()>850);
                    try(var image=scene.render(System.nanoTime());var png=image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG,100,0)){
                        Files.write(output.resolve("video-button.png"),png.getBytes());
                    }
                }catch(Exception e){throw new AssertionError(e);}finally{scene.close();}
            });
            check(Boolean.FALSE.equals(captionActive.invoke(hitTest)));
            current.invoke(null,profile.mediaFactory().create("local","Local fixture","Artist","","",output.resolve("local.mp3").toString()));
            end=System.nanoTime()+3_000_000_000L;
            while(video.view().available() && System.nanoTime()<end){SwingUtilities.invokeAndWait(()->{});Thread.sleep(20);}
            check(!video.view().available() && !video.view().requested());
            Class<?> mv=Class.forName("com.xuncorp.voxzen.data.entity.MusicVideo");
            Object foreign=mv.getConstructor(String.class,String.class,String.class,String.class,int.class,long.class)
                    .newInstance("mv","other-song","Video","",0,0L);
            Object matching=mv.getConstructor(String.class,String.class,String.class,String.class,int.class,long.class)
                    .newInstance("mv","local","Video","",0,0L);
            class State {
                final Object value;State(Object value){this.value=value;}
                public Object \u0528(){return value;}
            }
            check(!video.hasNativeVideo(new State(foreign)) && video.hasNativeVideo(new State(matching)));
            var stream=io.github.yghfv.saltneri.plugin.transport.TransportContractTest.videoServer(PlaybackVideoFixture.bytes(),()->true);
            var clip=stream.register(new io.github.yghfv.saltneri.plugin.transport.MediaLease(
                    io.github.yghfv.saltneri.plugin.transport.MediaLease.Platform.BILIBILI,
                    java.net.URI.create("https://test.bilivideo.com/synthetic.mp4"),Map.of(),"fixture",
                    java.time.Instant.now().plusSeconds(60),PlaybackVideoFixture.bytes().length));
            try {
            updateProgress.invoke(null,progress.getConstructor(long.class,long.class).newInstance(1000L,6000L));
            Method start=SaltVideoBindings.class.getDeclaredMethod("startPlayer",long.class,
                    io.github.yghfv.saltneri.plugin.auth.AccountCredentials.class,java.net.URI.class);
            start.setAccessible(true);
            Field ticket=SaltVideoBindings.class.getDeclaredField("generation");ticket.setAccessible(true);
            long first=ticket.getLong(video);
            SwingUtilities.invokeAndWait(()->{
                try{start.invoke(video,first,null,clip);}catch(Exception failure){throw new AssertionError(failure);}
            });
            await(()->video.view().player()!=null && !video.view().loading());
            Object renderer=video.view().player(),handle=renderer.getClass().getMethod("\u052b").invoke(renderer);
            Method nativeBool=handle.getClass().getMethod("\u0528",String.class);
            Method nativeInt=handle.getClass().getMethod("\u037f",String.class);
            Method nativeNumber=handle.getClass().getMethod("\u0529",String.class);
            check((int)nativeInt.invoke(handle,"width")==320 && (boolean)nativeBool.invoke(handle,"mute"));
            await(()->{try{return (boolean)nativeBool.invoke(handle,"pause");}catch(Exception failure){throw new AssertionError(failure);}});
            updateProgress.invoke(null,progress.getConstructor(long.class,long.class).newInstance(2500L,6000L));
            await(()->{
                try{return Math.abs((double)nativeNumber.invoke(handle,"time-pos")-2.5)<0.3;}
                catch(Exception failure){throw new AssertionError(failure);}
            });
            var window=new AtomicReference<androidx.compose.ui.awt.ComposeWindow>();
            SwingUtilities.invokeAndWait(()->{
                var actual=new androidx.compose.ui.awt.ComposeWindow();
                window.set(actual);actual.setTitle("Salt video regression");
                actual.setSize(1160,770);
                actual.setContent((scope,c,flags)->{
                    CompositionLocalKt.CompositionLocalProvider(
                            windowLocals(c,local.provides(playerSheet),captionLocal.provides(caption),hitTestLocal.provides(hitTest),navigatorLocal.provides(navigator)),(composer,f)->{
                                try{draw.invoke(hooks.content,call,composer,0);}catch(Exception failure){throw new AssertionError(failure);}
                                return Unit.INSTANCE;
                            },c,0);
                    return Unit.INSTANCE;
                });
                actual.setVisible(true);
            });
            try {
                var actual=window.get();var robot=new java.awt.Robot();
                await(()->{
                    if(uncaught.get()!=null)throw new AssertionError("Window video draw crashed",uncaught.get());
                    try {
                        var location=actual.getLocationOnScreen();
                        var pixel=robot.getPixelColor(location.x+actual.getWidth()/2,location.y+actual.getHeight()/2);
                        return pixel.getBlue()>150 && pixel.getRed()<60 && pixel.getGreen()<60;
                    } catch(java.awt.IllegalComponentStateException pending){return false;}
                });
                check(actual.getRenderApi()==org.jetbrains.skiko.GraphicsApi.DIRECT3D && toasts.isEmpty());
                check(Boolean.TRUE.equals(captionActive.invoke(hitTest)));
                var location=actual.getLocationOnScreen();
                robot.mouseMove(location.x+actual.getWidth()/2,location.y+actual.getHeight()/2);
                await(()->{try{return Boolean.TRUE.equals(captionActive.invoke(hitTest));}catch(Exception error){throw new AssertionError(error);}});
                await(()->transportPixels(robot,actual)>300);
                javax.imageio.ImageIO.write(robot.createScreenCapture(new java.awt.Rectangle(location.x,location.y,
                        actual.getWidth(),actual.getHeight())),"PNG",output.resolve("video-window.png").toFile());
                // Native expanded-caption animations can retain colored pixels after disposal.
                // The window-scoped registration directly tracks caption visibility.
                await(()->{try{return Boolean.FALSE.equals(captionActive.invoke(hitTest));}catch(Exception error){throw new AssertionError(error);}});
                check(Boolean.FALSE.equals(captionActive.invoke(hitTest)));
                await(()->transportPixels(robot,actual)==0);
                javax.imageio.ImageIO.write(robot.createScreenCapture(new java.awt.Rectangle(location.x,location.y,
                        actual.getWidth(),actual.getHeight())),"PNG",output.resolve("video-window-hidden.png").toFile());
                robot.mouseMove(location.x+actual.getWidth()/2+30,location.y+actual.getHeight()/2+20);
                await(()->{try{return Boolean.TRUE.equals(captionActive.invoke(hitTest));}catch(Exception error){throw new AssertionError(error);}});
                check(Boolean.TRUE.equals(captionActive.invoke(hitTest)));
                await(()->transportPixels(robot,actual)>300);
                // Leaving the native MiniPlayer must not let its independent Exit handler hide it.
                robot.mouseMove(location.x+actual.getWidth()/2,location.y+actual.getHeight()-90);
                Thread.sleep(150);
                robot.mouseMove(location.x+actual.getWidth()/2+60,location.y+actual.getHeight()/2+40);
                Thread.sleep(250);
                check(transportPixels(robot,actual)>300);
                updateProgress.invoke(null,progress.getConstructor(long.class,long.class).newInstance(4000L,6000L));
                await(()->{
                    if(uncaught.get()!=null)throw new AssertionError("Seek draw crashed",uncaught.get());
                    var origin=actual.getLocationOnScreen();
                    var pixel=robot.getPixelColor(origin.x+actual.getWidth()/2,origin.y+actual.getHeight()/2);
                    return pixel.getRed()>150 && pixel.getBlue()<60 && pixel.getGreen()<60;
                });
                check((boolean)nativeBool.invoke(handle,"pause") && (boolean)nativeBool.invoke(handle,"mute"));
                SwingUtilities.invokeAndWait(()->actual.setSize(960,650));
                await(()->{
                    var origin=actual.getLocationOnScreen();
                    var pixel=robot.getPixelColor(origin.x+actual.getWidth()/2,origin.y+actual.getHeight()/2);
                    return pixel.getRed()>150 && pixel.getBlue()<60;
                });
                var surface=new AtomicReference<SaltVideoSurface>();
                SwingUtilities.invokeAndWait(()->surface.set(video.surface(renderer,actual,()->{})));
                check(surface.get()!=null);
                SwingUtilities.invokeAndWait(()->surface.get().fail(new IllegalStateException("Synthetic draw failure")));
                await(()->!video.view().requested() && video.view().player()==null);
                await(()->{try{return Boolean.TRUE.equals(captionActive.invoke(hitTest));}catch(Exception error){throw new AssertionError(error);}});
                check(toasts.size()==1 && uncaught.get()==null);
                SwingUtilities.invokeAndWait(()->video.disposeSurface(surface.get()));
            } finally {SwingUtilities.invokeAndWait(()->window.get().dispose());}
            check(Boolean.FALSE.equals(captionActive.invoke(hitTest)));
            SwingUtilities.invokeAndWait(video::toggle);
            check(!video.view().requested() && video.view().player()==null);
            current.invoke(null,owned);await(()->video.view().available());
            long second=ticket.getLong(video);
            SwingUtilities.invokeAndWait(()->{
                try{start.invoke(video,second,null,clip);}catch(Exception failure){throw new AssertionError(failure);}
            });
            await(()->video.view().player()!=null && !video.view().loading());
            current.invoke(null,profile.mediaFactory().create("next","Next","","","",output.resolve("next.mp3").toString()));
            await(()->video.view().player()==null && !video.view().requested());
            }finally {stream.close();}
        }finally{current.invoke(null,original);updateProgress.invoke(null,originalProgress);}
        SwingUtilities.invokeAndWait(()->{});
        check(hooks.removed==2 && uncaught.get()==null);
        System.out.println("PASS: "+checks+" playback video hook, native bottom controls, pointer recovery, caption render, selection and disposal checks; "+output);
    }
}
