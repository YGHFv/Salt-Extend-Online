package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.*;
import androidx.compose.ui.Modifier;
import com.xuncorp.spw.workshop.api.hook.*;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.host.SavedTrackStore;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.transport.*;
import io.github.yghfv.saltneri.plugin.video.MusicVideoClient;
import io.github.yghfv.saltneri.plugin.video.VideoResolution;
import io.github.yghfv.saltneri.plugin.video.VideoPrecache;
import io.github.yghfv.saltneri.plugin.host.NativePlaylistOrder;
import java.lang.reflect.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function3;
import kotlinx.coroutines.flow.StateFlow;

/** One muted host mpv surface, driven by Salt's existing audio clock and selection. */
public final class SaltVideoBindings implements AutoCloseable {
    public static final String TITLE="androidx.compose.ui.\u0b61",WINDOW="androidx.compose.ui.\u0537";
    public static final List<String> TITLE_PARAMETERS=List.of("androidx.compose.ui.\u04d7","androidx.compose.ui.\u0aa0",
            "androidx.compose.ui.\u091a","androidx.compose.ui.Modifier");
    public static final String TRANSPORT="com.xuncorp.voxzen.ui.player.mini.\u052b",TRANSPORT_CALLER="androidx.compose.ui.\u0525";
    public static final List<String> TRANSPORT_PARAMETERS=List.of("kotlin.jvm.functions.Function0","androidx.compose.ui.Modifier",
            "long","androidx.compose.ui.\u0aa0","com.xuncorp.voxzen.ui.player.mini.\u05ef");
    public static final String SCREEN="androidx.compose.ui.\u0451";
    public static final List<String> SCREEN_PARAMETERS=List.of("kotlin.jvm.functions.Function0","boolean","androidx.compose.ui.\u091a","androidx.compose.ui.Modifier");
    public static final String FULLSCREEN="androidx.compose.ui.\u0118";
    public static final String FLOAT_COVER="androidx.compose.ui.\u06a0";
    public static final List<String> FLOAT_COVER_PARAMETERS=List.of("boolean","boolean");
    public static final String CONDITIONAL="androidx.compose.ui.\u02b6";
    public static final List<String> CONDITIONAL_PARAMETERS=List.of("boolean","kotlin.jvm.functions.Function2");
    public static final String WINDOW_FRAME="com.moriafly.salt.ui.platform.windows.\u0560",WINDOW_FRAME_CALLER="androidx.compose.ui.\u0372";
    public static final List<String> WINDOW_FRAME_PARAMETERS=List.of("androidx.compose.ui.window.FrameWindowScope","boolean",
            "androidx.compose.ui.\u012e","kotlin.jvm.functions.Function3");
    public static final String TRANSPORT_STATE="com.xuncorp.voxzen.ui.player.mini.\u05ef",WINDOW_PROPERTIES="androidx.compose.ui.\u012e";
    public record View(String id,boolean available,boolean requested,boolean loading,String message,Object player) { }
    private final MutableState<View> view=SnapshotStateKt.mutableStateOf(
            new View("",false,false,false,"",null),SnapshotStateKt.structuralEqualityPolicy());
    private final ClassLoader host;
    private final BooleanSupplier active;
    private final BiFunction<String,String,SavedTrackStore.Entry> tracks;
    private final Function<AccountPlatform,AccountCredentials> accounts;
    private final Consumer<String> toast;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"salt-playback-video");t.setDaemon(true);return t;
    });
    private final ExecutorService resolver=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"salt-video-resolve");t.setDaemon(true);return t;
    });
    private final List<HookHandle> hooks=new ArrayList<>();
    private static final ExecutorService RETIREMENTS=Executors.newCachedThreadPool(r->{
        Thread t=new Thread(r,"salt-video-retirement");t.setDaemon(true);return t;
    });
    private final Set<SaltVideoSurface> surfaces=Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<CompletableFuture<Void>> surfaceRetirements=new ArrayList<>();
    private final StateFlow<?> current,progress,isPlaying,videoState;
    private final Method itemId,itemPath,progressPosition;
    private final Constructor<?> playerConstructor;
    private final Method handleGetter,command,property,number,integer,bool,captionButton,localCaption;
    private final Method getPlaybackSpeed,playOrPause;
    private final Field playbackController;
    private final Method captionWidth;
    private final Method titleBar,floatCover;
    private final Method conditional;
    private final Field transportState;
    private final MutableState<Object> captionProperties=SnapshotStateKt.mutableStateOf(null,SnapshotStateKt.referentialEqualityPolicy());
    private final MutableState<Function0<?>> screenOpacity=SnapshotStateKt.mutableStateOf(null,SnapshotStateKt.referentialEqualityPolicy());
    private final Map<Object,MutableState<Object>> transportOverrides=new ConcurrentHashMap<>();
    private volatile List<MutableState<androidx.compose.ui.geometry.Rect>> captionRegions=List.of();
    private final MutableState<Boolean> controlsVisible=SnapshotStateKt.mutableStateOf(true,SnapshotStateKt.structuralEqualityPolicy());
    private final MutableState<Long> activity=SnapshotStateKt.mutableStateOf(0L,SnapshotStateKt.structuralEqualityPolicy());
    private final MutableState<java.awt.Window> controlsWindow=SnapshotStateKt.mutableStateOf(null,SnapshotStateKt.structuralEqualityPolicy());
    private java.awt.event.MouseAdapter windowObserver;
    private volatile java.awt.Point lastPointer;
    private final LoopbackRangeServer transport;
    private final VideoPrecache precache;
    private volatile Supplier<VideoResolution> resolution=()->VideoResolution.DEFAULT;
    private volatile VideoResolution selectedResolution=VideoResolution.DEFAULT;
    private volatile StateFlow<?> queue;
    private volatile boolean queueUnavailable;
    private record QueuePlan(Object state,SavedTrackStore.Entry entry,String selection,
            List<io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.Target> targets) { }
    private volatile QueuePlan cachedOrder;
    interface VideoRequests {
        MusicVideoClient.Source discover(MusicVideoClient.Source selected,AccountCredentials credentials,RequestScope request) throws Exception;
        MediaLease resolve(MusicVideoClient.Source selected,AccountCredentials credentials,String scope,RequestScope request) throws Exception;
        default MediaLease resolve(MusicVideoClient.Source selected,VideoResolution resolution,AccountCredentials credentials,String scope,RequestScope request) throws Exception {
            return resolve(selected,credentials,scope,request);
        }
    }
    private final VideoRequests requests;
    private final Runnable playbackAction;
    private volatile RequestScope pending;
    private volatile boolean closed;
    private volatile long generation;
    private volatile String selection="";
    private volatile SavedTrackStore.Entry entry;
    private volatile MusicVideoClient.Source source;
    private AccountCredentials account;
    // Native commands/destruction share a worker-only lock. UI lifecycle methods never take it.
    private final Object nativeControl=new Object();
    private volatile Object player;
    private Object handle;
    private URI stream;
    private Boolean paused;
    private double speed=1;
    private double videoSpeed=1;
    private VideoAudioClock clock=new VideoAudioClock();
    private long loadDeadline,lastSeek,driftSince;
    private boolean loaded,nativeAvailable;
    private final MutableState<Boolean> expanded=SnapshotStateKt.mutableStateOf(false,SnapshotStateKt.structuralEqualityPolicy());
    private volatile boolean videoMode;

    public SaltVideoBindings(ClassLoader host,HookRegistrar registrar,BooleanSupplier active,
            BiFunction<String,String,SavedTrackStore.Entry> tracks,Function<AccountPlatform,AccountCredentials> accounts,
            Consumer<String> toast) throws Exception {
        this(host,registrar,active,tracks,accounts,toast,new VideoRequests() {
            public MusicVideoClient.Source discover(MusicVideoClient.Source selected,AccountCredentials credentials,RequestScope request) throws Exception {
                return new MusicVideoClient(credentials).discover(selected.track(),request);
            }
            public MediaLease resolve(MusicVideoClient.Source selected,AccountCredentials credentials,String scope,RequestScope request) throws Exception {
                return new MusicVideoClient(credentials).resolve(selected,scope,request);
            }
            public MediaLease resolve(MusicVideoClient.Source selected,VideoResolution resolution,AccountCredentials credentials,String scope,RequestScope request) throws Exception {
                return new MusicVideoClient(credentials).resolve(selected,resolution,scope,request);
            }
        },null);
    }
    public SaltVideoBindings(ClassLoader host,HookRegistrar registrar,BooleanSupplier active,
            BiFunction<String,String,SavedTrackStore.Entry> tracks,Function<AccountPlatform,AccountCredentials> accounts,
            Consumer<String> toast,Supplier<VideoResolution> resolution) throws Exception {
        this(host,registrar,active,tracks,accounts,toast);
        this.resolution=resolution;
    }
    SaltVideoBindings(ClassLoader host,HookRegistrar registrar,BooleanSupplier active,
            BiFunction<String,String,SavedTrackStore.Entry> tracks,Function<AccountPlatform,AccountCredentials> accounts,
            Consumer<String> toast,VideoRequests requests,LoopbackRangeServer transport) throws Exception {
        this(host,registrar,active,tracks,accounts,toast,requests,transport,null);
    }
    SaltVideoBindings(ClassLoader host,HookRegistrar registrar,BooleanSupplier active,
            BiFunction<String,String,SavedTrackStore.Entry> tracks,Function<AccountPlatform,AccountCredentials> accounts,
            Consumer<String> toast,VideoRequests requests,LoopbackRangeServer transport,Runnable playbackAction) throws Exception {
        NativeWindowCallbackRetention.capture();
        this.host=host;this.active=active;this.tracks=tracks;this.accounts=accounts;this.toast=toast;
        this.requests=requests;
        this.playbackAction=playbackAction;
        Class<?> monitor=type("com.xuncorp.pisces.\u0786");
        current=(StateFlow<?>)monitor.getMethod("\u0528").invoke(null);
        progress=(StateFlow<?>)monitor.getMethod("\u052a").invoke(null);
        isPlaying=(StateFlow<?>)monitor.getMethod("\u0529").invoke(null);
        videoState=(StateFlow<?>)type("androidx.compose.ui.\u017b").getMethod("\u037f").invoke(null);
        Class<?> item=type("com.xuncorp.pisces.PiscesMediaItem");
        itemId=item.getMethod("\u037f");itemPath=item.getMethod("\u052c");
        progressPosition=type("com.xuncorp.pisces.PiscesMediaController$Progress").getMethod("\u037f");
        Class<?> mpv=type("org.openani.mediamp.mpv.\u0588"),nativeHandle=type("org.openani.mediamp.mpv.\u052b");
        playerConstructor=mpv.getConstructor(Object.class,CoroutineContext.class);
        handleGetter=mpv.getMethod("\u052b");
        command=nativeHandle.getMethod("\u037f",String[].class);
        property=nativeHandle.getMethod("\u0528",String.class,String.class);
        number=nativeHandle.getMethod("\u0529",String.class);
        integer=nativeHandle.getMethod("\u037f",String.class);
        bool=nativeHandle.getMethod("\u0528",String.class);
        captionButton=type("com.xuncorp.voxzen.ui.component.\u0529").getMethod("\u037f",
                Function0.class,String.class,Modifier.class,boolean.class,String.class,Function3.class,Composer.class,int.class,int.class);
        localCaption=type("androidx.compose.ui.\u0f40").getMethod("\u037f");
        captionWidth=type("androidx.compose.ui.\u0f65").getMethod("\u0529");
        titleBar=type(TITLE).getMethod("\u037f",type(TITLE_PARAMETERS.get(0)),type(TITLE_PARAMETERS.get(1)),
                type(TITLE_PARAMETERS.get(2)),Modifier.class,Composer.class,int.class,int.class);
        floatCover=type(FLOAT_COVER).getMethod("\u037f",boolean.class,boolean.class,Composer.class,int.class,int.class);
        conditional=type(CONDITIONAL).getMethod("\u037f",boolean.class,kotlin.jvm.functions.Function2.class,Composer.class,int.class);
        transportState=type(TRANSPORT_STATE).getDeclaredField("\u037f");transportState.setAccessible(true);
        Class<?> controller=type("com.xuncorp.voxzen.service.PlaybackController");
        getPlaybackSpeed=controller.getMethod("getPlaybackSpeed");
        playOrPause=controller.getMethod("playOrPause");
        playbackController=controller.getField("INSTANCE");
        this.transport=transport==null?LoopbackRangeServer.video(()->!closed && active.getAsBoolean()):transport;
        precache=new VideoPrecache(this::videoOrder,()->resolution.get(),accounts,()->!closed && active.getAsBoolean(),
                ()->videoMode && view().player()!=null && !view().loading() && this.transport.cacheLimitBytes()>0,
                this.transport,(target,cap,credentials,request)->{
                    var selected=source;
                    if(selected==null || !selected.track().equals(target))selected=requests.discover(new MusicVideoClient.Source(target,""),credentials,request);
                    return selected==null?null:requests.resolve(selected,cap,credentials,
                            credentials==null?"anonymous-video":credentials.accountScope(),request);
                });
        try {
            Class<?> content=Class.forName("io.github.yghfv.saltneri.plugin.ui.SaltVideoContent",true,getClass().getClassLoader());
            hooks.add(registrar.replaceComposable(TITLE,"\u037f",WINDOW,TITLE_PARAMETERS,50,
                    (ComposableHook)content.getConstructor(SaltVideoBindings.class).newInstance(this)));
            replace(registrar,CONDITIONAL,TRANSPORT,CONDITIONAL_PARAMETERS,"SaltVideoTransportContent");
            // This single modifier-only Box is the separate capsule backdrop; the enclosing
            // MainSurface overload also has a restart call and cannot be replaced unambiguously.
            hooks.add(registrar.replaceComposable("androidx.compose.foundation.layout.BoxKt","Box",TRANSPORT_CALLER,
                    List.of("androidx.compose.ui.Modifier"),50,content("SaltVideoBackdropContent")));
            replace(registrar,CONDITIONAL,SCREEN,CONDITIONAL_PARAMETERS,"SaltVideoScreenContent");
            hooks.add(registrar.afterComposable(SCREEN,"\u037f",TRANSPORT_CALLER,SCREEN_PARAMETERS,50,
                    content("SaltVideoScreenStateContent")));
            replace(registrar,FLOAT_COVER,TRANSPORT_CALLER,FLOAT_COVER_PARAMETERS,"SaltVideoFloatCoverContent");
            // Replacing this call moves the native styler's remember group. Enabling or
            // updating the plugin in an existing window would install a second WndProc
            // whose predecessor can be collected. Observe after the original call instead.
            hooks.add(registrar.afterComposable(WINDOW_FRAME,"\u037f",WINDOW_FRAME_CALLER,
                    WINDOW_FRAME_PARAMETERS,50,content("SaltVideoWindowFrameContent")));
            replace(registrar,CONDITIONAL,WINDOW_FRAME,CONDITIONAL_PARAMETERS,"SaltVideoWindowCaptionContent");
            // Native input must never acquire the video session lock: Windows can call back
            // while MouseInfo waits on its thread. Preserve native returns; this host's method
            // advice skips the original for setResult but loses non-default return values.
            hooks.add(registrar.hookMethod(TRANSPORT_STATE,"\u037f",List.of(),new MethodHook() {
                @Override public void before(MethodHookParam param) {
                    if(!closed && expanded.getValue() && view().requested())activateNativeTransport(param.getThisObject());
                }
            }));
            hooks.add(registrar.hookMethod(WINDOW_FRAME,"\u037f",List.of("androidx.compose.ui.window.WindowState",
                    "androidx.compose.runtime.MutableState","androidx.compose.runtime.State","androidx.compose.runtime.MutableState",
                    "androidx.compose.runtime.State","androidx.compose.runtime.MutableState","androidx.compose.runtime.MutableState",
                    "androidx.compose.runtime.MutableState","float","float"),new MethodHook() {
                @SuppressWarnings("unchecked")
                @Override public void before(MethodHookParam param) {
                    Object[] args=param.getArgs();
                    if(((State<?>)args[2]).getValue()!=captionProperties.getValue())return;
                    captionRegions=List.of((MutableState<androidx.compose.ui.geometry.Rect>)args[3],
                            (MutableState<androidx.compose.ui.geometry.Rect>)args[5],
                            (MutableState<androidx.compose.ui.geometry.Rect>)args[6]);
                    if(!windowControlsVisible(controlsWindow.getValue()))clearCaptionRegions();
                }
            }));
            hooks.add(registrar.hookMethod("com.xuncorp.voxzen.util.AppConfig","getWindowFullScreenButton",List.of(),new MethodHook() {
                @Override public void before(MethodHookParam param) {
                    if(!windowControlsVisible(controlsWindow.getValue()))param.setResult(false);
                }
            }));
        } catch(Exception | LinkageError error) {close();throw error;}
        worker.scheduleWithFixedDelay(this::tick,0,100,TimeUnit.MILLISECONDS);
    }
    private ComposableHook content(String name) throws Exception {
        return (ComposableHook)Class.forName("io.github.yghfv.saltneri.plugin.ui."+name,true,getClass().getClassLoader())
                .getConstructor(SaltVideoBindings.class).newInstance(this);
    }
    private void replace(HookRegistrar registrar,String owner,String caller,List<String> parameters,String name) throws Exception {
        hooks.add(registrar.replaceComposable(owner,"\u037f",caller,parameters,50,content(name)));
    }
    public void captionProperties(Object value){
        NativeWindowCallbackRetention.capture();
        captionProperties.setValue(value);
    }
    private void activateNativeTransport(Object owner) {
        try {
            var state=transportState.get(owner);
            if(state instanceof TransportVisibility)return;
            @SuppressWarnings("unchecked") var original=(MutableState<Object>)state;
            transportOverrides.put(owner,original);
            transportState.set(owner,new TransportVisibility(original));
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Native transport state unavailable",failure);}
    }
    private final class TransportVisibility implements MutableState<Object> {
        private final MutableState<Object> original;
        private TransportVisibility(MutableState<Object> original){this.original=original;}
        @Override public Object getValue() {
            return !closed && expanded.getValue() && view().requested()?Boolean.TRUE:original.getValue();
        }
        @Override public void setValue(Object value){original.setValue(value);}
        @Override public Object component1(){return getValue();}
        @Override public kotlin.jvm.functions.Function1<Object,kotlin.Unit> component2(){return value->{setValue(value);return kotlin.Unit.INSTANCE;};}
    }
    private void restoreNativeTransport() {
        transportOverrides.forEach((owner,original)->{
            try{if(transportState.get(owner) instanceof TransportVisibility)transportState.set(owner,original);}
            catch(ReflectiveOperationException ignored){}
        });
        transportOverrides.clear();
    }
    public void screenOpacity(Function0<?> value){screenOpacity.setValue(value);}
    public float screenOpacity(){var opacity=screenOpacity.getValue();return opacity==null?0f:((Number)opacity.invoke()).floatValue();}
    public void originalConditional(UiHookCall call,Composer composer) {
        originalConditional(call,(boolean)call.getArgument(0),composer);
    }
    public void originalConditional(UiHookCall call,boolean dark,Composer composer) {
        try{conditional.invoke(null,dark,call.getArgument(1),composer,0);}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("Native video content unavailable",failure);}
    }
    private Class<?> type(String name) throws ClassNotFoundException {return Class.forName(name,false,host);}
    public View view() {return view.getValue();}
    public boolean controlsVisible() {return controlsVisible.getValue();}
    public long activity() {return activity.getValue();}
    public void showControls() {controlsVisible.setValue(true);}
    public void hideControls() {controlsVisible.setValue(false);clearCaptionRegions();}
    private void clearCaptionRegions(){captionRegions.forEach(state->state.setValue(androidx.compose.ui.geometry.Rect.Companion.getZero()));}
    public boolean captionRegionsEmpty(){var regions=captionRegions;return !regions.isEmpty() && regions.stream().allMatch(state->state.getValue().equals(androidx.compose.ui.geometry.Rect.Companion.getZero()));}
    public void pointerActivity() {
        if(closed || !expanded.getValue() || !view().requested())return;
        showControls();activity.setValue(activity.getValue()+1);
    }
    public void pointerMoved() {
        var pointer=java.awt.MouseInfo.getPointerInfo();
        if(pointer==null)return;
        var location=pointer.getLocation();
        if(location.equals(lastPointer))return;
        lastPointer=location;pointerActivity();
    }
    private void pollPointer() {
        if(!expanded.getValue() || !view().requested())return;
        var pointer=java.awt.MouseInfo.getPointerInfo();
        if(pointer==null || pointer.getLocation().equals(lastPointer))return;
        SaltPageContentBinding.onUiThread(()->{
            var window=controlsWindow.getValue();var currentPointer=java.awt.MouseInfo.getPointerInfo();
            if(window!=null && window.isShowing() && currentPointer!=null && window.getBounds().contains(currentPointer.getLocation()))pointerMoved();
        });
    }
    public boolean windowControlsVisible(java.awt.Window window) {
        boolean visible=controlsVisible();boolean video=windowVideoActive(window);
        return !video || visible;
    }
    public boolean windowVideoActive(java.awt.Window window) {
        var state=view();var owner=controlsWindow.getValue();boolean open=expanded.getValue();
        return !closed && window==owner && open && state.requested();
    }
    public AutoCloseable observeWindow(java.awt.Window window) {
        detachWindowObserver();
        controlsWindow.setValue(window);
        if(window!=null) {var pointer=java.awt.MouseInfo.getPointerInfo();lastPointer=pointer==null?null:pointer.getLocation();}
        var observer=new java.awt.event.MouseAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent event) {pointerMoved();}
            @Override public void mouseDragged(java.awt.event.MouseEvent event) {pointerMoved();}
            @Override public void mouseEntered(java.awt.event.MouseEvent event) {pointerMoved();}
            @Override public void mousePressed(java.awt.event.MouseEvent event) {pointerActivity();}
            @Override public void mouseWheelMoved(java.awt.event.MouseWheelEvent event) {pointerActivity();}
        };
        windowObserver=observer;
        if(window!=null) {window.addMouseMotionListener(observer);window.addMouseListener(observer);window.addMouseWheelListener(observer);}
        return ()->{
            if(windowObserver==observer) {detachWindowObserver();showControls();}
        };
    }
    private void detachWindowObserver() {
        var window=controlsWindow.getValue();
        if(window!=null && windowObserver!=null) {
            window.removeMouseMotionListener(windowObserver);window.removeMouseListener(windowObserver);window.removeMouseWheelListener(windowObserver);
        }
        controlsWindow.setValue(null);windowObserver=null;
    }
    public void togglePlayback() {
        if(closed || !active.getAsBoolean() || !expanded.getValue() || !view().requested())return;
        if(playbackAction!=null) {playbackAction.run();return;}
        try {playOrPause.invoke(playbackController.get(null));}
        catch(ReflectiveOperationException failure) {throw new IllegalStateException("Salt playback toggle unavailable",failure);}
    }
    public Object originalVideoState() {return videoState.getValue();}
    public boolean hasNativeVideo(Object state) {
        try {
            Object video=state==null?null:state.getClass().getMethod("\u0528").invoke(state);
            return video!=null && !currentId().isEmpty()
                    && currentId().equals(video.getClass().getMethod("getTrackId").invoke(video));
        }
        catch(ReflectiveOperationException ignored) {return false;}
    }
    public boolean isExpanded(Composer composer) {
        try {
            var local=(CompositionLocal<?>)type("androidx.compose.ui.\u041a").getMethod("\u037f").invoke(null);
            Object state=composer.consume(local);
            boolean open=(boolean)state.getClass().getMethod("\u0528").invoke(state);
            expanded.setValue(open);return open;
        } catch(ReflectiveOperationException failure) {expanded.setValue(false);return false;}
    }
    public float captionWidth(Composer composer) {
        try {return (float)captionWidth.invoke(composer.consume((CompositionLocal<?>)localCaption.invoke(null)));}
        catch(ReflectiveOperationException failure) {return 144f;}
    }
    public void button(String label,boolean enabled,Modifier modifier,Function0<kotlin.Unit> click,
                       Object icon,Composer composer) {
        try {
            Class<?> lambda=type("androidx.compose.runtime.internal.ComposableLambdaImpl");
            if(lambda.isInstance(icon)) {
                Field block=lambda.getDeclaredField("_block");block.setAccessible(true);icon=block.get(icon);
            }
            captionButton.invoke(null,click,label,modifier,enabled,null,icon,composer,0,16);
        }
        catch(ReflectiveOperationException failure) {throw new IllegalStateException("Video caption unavailable",failure);}
    }
    public void titleBar(UiHookCall call,Modifier modifier,Composer composer) {
        // Invoke the original function directly; the hook is scoped to its MainWindow call site.
        try {titleBar.invoke(null,call.getArgument(0),call.getArgument(1),call.getArgument(2),modifier,composer,0,0);}
        catch(ReflectiveOperationException failure) {throw new IllegalStateException("Video title bar unavailable",failure);}
    }
    public void originalTitleBar(UiHookCall call,Composer composer) {
        Modifier modifier=call.getArgument(3) instanceof Modifier supplied?supplied:Modifier.Companion;
        titleBar(call,modifier,composer);
    }
    @SuppressWarnings("unchecked")
    public ProvidedValue<?>[] darkThemeValues(Composer composer) {
        try {
            var local=(ProvidableCompositionLocal<Object>)type("androidx.compose.ui.\u02b6").getMethod("\u037f").invoke(null);
            Object dark=type("androidx.compose.ui.\u02e4").getMethod("\u037f",type("androidx.compose.ui.\u02e4"),boolean.class)
                    .invoke(null,composer.consume(local),true);
            return new ProvidedValue<?>[]{local.provides(dark)};
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Native video theme unavailable",failure);}
    }
    public void originalFloatCover(UiHookCall call,Composer composer) {
        try{floatCover.invoke(null,call.getArgument(0),call.getArgument(1),composer,0,0);}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("Native floating cover unavailable",failure);}
    }
    public synchronized SaltVideoSurface surface(Object renderer,java.awt.Window window,Runnable redraw) {
        if(closed || player!=renderer)return null;
        long ticket=generation;
        try {
            surfaceRetirements.removeIf(CompletableFuture::isDone);
            var ready=CompletableFuture.allOf(surfaceRetirements.toArray(CompletableFuture[]::new));
            var surface=new SaltVideoSurface(renderer,window,redraw,error->surfaceFailed(renderer,ticket),ready);
            surfaces.add(surface);return surface;
        } catch(Exception | LinkageError failure) {surfaceFailed(renderer,ticket);return null;}
    }
    public synchronized void disposeSurface(SaltVideoSurface surface) {
        if(surface==null || !surfaces.remove(surface))return;
        surface.close();surfaceRetirements.add(surface.released());
    }
    private void surfaceFailed(Object renderer,long ticket) {
        // Defer state changes and native teardown until the current Compose draw pass has finished.
        javax.swing.SwingUtilities.invokeLater(()->{
            synchronized(SaltVideoBindings.this) {if(player==renderer && generation==ticket)failed(ticket);}
        });
    }
    public void toggle() {
        if(closed || !active.getAsBoolean())return;
        synchronized(this) {
            if(videoMode) {videoMode=false;generation++;cancelRequest();precache.cancel();releasePlayer();publish(false,false,"");return;}
            videoMode=true;
            if(source==null) {
                if(hasNativeVideo(videoState.getValue()))toggleNativeVideo();
                else videoMode=false;
                return;
            }
            requestVideo();
        }
    }
    private synchronized void requestVideo() {
        if(!videoMode || source==null || closed)return;
        selectedResolution=resolution.get();
        long ticket=++generation;cancelRequest();publish(true,true,"正在加载视频");
        var selected=source;var credentials=account;
        pending=new RequestScope(()->valid(ticket,credentials),30000);
        var request=pending;
        resolver.execute(()->{
            try {
                URI local=precache.resolve(selected.track(),request);
                if(local==null)throw new IllegalStateException("No exact MV");
                SaltPageContentBinding.onUiThread(()->startPlayer(ticket,credentials,local));
            } catch(Exception | LinkageError failed) {failed(ticket);}
            finally {request.close();}
        });
    }
    private void toggleNativeVideo() {
        // Native local MV selection remains owned by Salt; the same muted surface handles its path.
        synchronized(this) {
            try {
                Object state=videoState.getValue(),video=state.getClass().getMethod("\u0528").invoke(state);
                if(!hasNativeVideo(state))return;
                String path=(String)video.getClass().getMethod("getVri").invoke(video);
                Object vri=type("com.xuncorp.spc.v.Vri").getConstructor(String.class).newInstance(path);
                String location=(String)vri.getClass().getMethod("\u037f").invoke(vri);
                URI uri;
                if(location.matches("^[A-Za-z]:[\\\\/].*"))uri=java.nio.file.Path.of(location).toUri();
                else uri=URI.create(location);
                if(!"file".equals(uri.getScheme()) || !java.nio.file.Files.isRegularFile(java.nio.file.Path.of(uri))) {
                    videoMode=false;toast.accept("此 MV 的来源暂不支持视频播放");return;
                }
                long ticket=++generation;publish(true,true,"正在加载视频");
                var credentials=account;
                SaltPageContentBinding.onUiThread(()->startPlayer(ticket,credentials,uri));
            } catch(Exception failed) {videoMode=false;toast.accept("MV 来源无法读取");}
        }
    }
    private boolean valid(long ticket,AccountCredentials credentials) {
        return !closed && active.getAsBoolean() && generation==ticket
                && (!videoMode || selectedResolution==resolution.get())
                && (entry==null || accounts.apply(entry.target().platform())==credentials)
                && (credentials==null || credentials.isActive()) && selection.equals(currentId());
    }
    private String currentId() {
        try {Object item=current.getValue();return item==null?"":(String)itemId.invoke(item);}
        catch(ReflectiveOperationException ignored) {return "";}
    }
    private void startPlayer(long ticket,AccountCredentials credentials,URI local) {
        try {worker.execute(()->openPlayer(ticket,credentials,local));}
        catch(RejectedExecutionException stopped) {transport.revoke(local);}
    }
    private void openPlayer(long ticket,AccountCredentials credentials,URI local) {
        synchronized(nativeControl) {
        synchronized(this) {
            if(!valid(ticket,credentials))return;
            releasePlayer();
        }
        Object created=null;
        try {
            created=playerConstructor.newInstance(this,EmptyCoroutineContext.INSTANCE);
            Object port=handleGetter.invoke(created);
            // Salt's bundled player defaults to AV1 only; permit FFmpeg's AVC and local MV decoders.
            for(var option:Map.of("vd","lavc:*","aid","no","mute","yes","pause","yes",
                    "keepaspect","yes","video-unscaled","no").entrySet())
                if(!Boolean.TRUE.equals(property.invoke(port,option.getKey(),option.getValue())))
                    throw new IllegalStateException("Video option rejected");
            // Keep a bounded demuxer buffer as well as disk ranges; mpv pauses during brief CDN gaps.
            property.invoke(port,"cache","yes");property.invoke(port,"cache-pause","yes");
            property.invoke(port,"demuxer-max-bytes","32MiB");property.invoke(port,"demuxer-readahead-secs","20");
            double position=position()/1000d;
            if(!Boolean.TRUE.equals(command.invoke(port,(Object)new String[]{"loadfile",local.toString(),"replace","-1","start="+position})))
                throw new IllegalStateException("Video stream rejected");
            synchronized(this) {
                if(!valid(ticket,credentials)) {retirePlayer(created,CompletableFuture.completedFuture(null));return;}
                player=created;handle=port;stream=local;paused=null;speed=1;videoSpeed=1;loaded=false;lastSeek=0;driftSince=0;
                clock=new VideoAudioClock();
                loadDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                publish(true,true,"正在加载视频");
            }
        } catch(Exception | LinkageError failed) {
            retirePlayer(created,CompletableFuture.completedFuture(null));
            transport.revoke(local);failed(ticket);
        }
        }
    }
    private synchronized void failed(long ticket) {
        if(generation!=ticket || closed)return;
        videoMode=false;generation++;cancelRequest();
        releasePlayer();publish(false,false,"");
        SaltPageContentBinding.onUiThread(()->{if(!closed)toast.accept("视频加载失败，请稍后重试");});
    }
    private long position() {
        try {return ((Number)progressPosition.invoke(progress.getValue())).longValue();}
        catch(ReflectiveOperationException | NullPointerException failure) {return 0;}
    }
    private void tick() {
        Object selectedPlayer,selectedHandle;long ticket;
        try {
            pollPointer();
            synchronized(this) {
                updateSelection();
                if(closed)return;
                selectedPlayer=player;selectedHandle=handle;ticket=generation;
            }
            if(selectedPlayer!=null)controlPlayer(selectedPlayer,selectedHandle,ticket);
        } catch(Exception | LinkageError failure) {
            synchronized(this) {if(player!=null)failed(generation);}
        }
    }
    private void updateSelection() throws ReflectiveOperationException {
            if(closed)return;
            if(!active.getAsBoolean()){close();return;}
            if(videoMode && source!=null && selectedResolution!=resolution.get())requestVideo();
            Object item=current.getValue();String id=item==null?"":(String)itemId.invoke(item);
            boolean changedAccount=entry!=null && accounts.apply(entry.target().platform())!=account;
            if(!id.equals(selection) || changedAccount) {
                if(changedAccount || id.isEmpty())videoMode=false;
                generation++;cancelRequest();releasePlayer();selection=id;entry=null;source=null;account=null;selectedResolution=resolution.get();
                if(item!=null)entry=tracks.apply(id,(String)itemPath.invoke(item));
                publish(false,false,"");
                if(entry!=null) {
                    var target=entry.target();account=accounts.apply(target.platform());var credentials=account;
                    long ticket=generation;
                    if(credentials!=null && !credentials.isActive())return;
                    if(target.platform()==AccountPlatform.BILIBILI) {
                        source=new MusicVideoClient.Source(target,target.id());
                        if(videoMode)requestVideo();else publish(false,false,"");
                    }
                    else if(target.platform()==AccountPlatform.NETEASE || target.platform()==AccountPlatform.QQMUSIC) {
                        if(videoMode)publish(true,true,"正在查找视频");
                        pending=new RequestScope(()->valid(ticket,credentials));var request=pending;
                        resolver.execute(()->{
                            try {
                                var result=requests.discover(new MusicVideoClient.Source(target,""),credentials,request);
                                synchronized(SaltVideoBindings.this) {
                                    if(valid(ticket,credentials)) {
                                        source=result;
                                        if(videoMode && result!=null)requestVideo();else publish(false,false,"");
                                    }
                                }
                            } catch(Exception ignored) {
                                synchronized(SaltVideoBindings.this) {if(valid(ticket,credentials))publish(false,false,"");}
                            }
                            finally {request.close();}
                        });
                    }
                }
            }
            if(account!=null && (!account.isActive() || accounts.apply(account.platform())!=account)) {
                videoMode=false;generation++;cancelRequest();releasePlayer();source=null;publish(false,false,"");return;
            }
            boolean available=hasNativeVideo(videoState.getValue());
            if(available!=nativeAvailable) {nativeAvailable=available;var state=view();publish(state.requested(),state.loading(),state.message());}
            if(videoMode && entry==null && available && player==null && !view().loading())toggleNativeVideo();
    }
    private void controlPlayer(Object selectedPlayer,Object handle,long ticket) {
        synchronized(nativeControl) {
            if(closed || player!=selectedPlayer)return;
            try {
                long now=System.nanoTime();
                if(!loaded) {
                    if(((Number)integer.invoke(handle,"width")).intValue()>0) {
                        synchronized(this) {
                            if(closed || player!=selectedPlayer || generation!=ticket)return;
                            loaded=true;publish(true,false,"");
                        }
                    } else {
                        if(now>loadDeadline)failed(ticket);
                        return;
                    }
                }
                if(Boolean.TRUE.equals(bool.invoke(handle,"idle-active"))) {failed(ticket);return;}
                boolean playing=Boolean.TRUE.equals(isPlaying.getValue());
                if(playing) {
                    double currentSpeed=((Number)getPlaybackSpeed.invoke(playbackController.get(null))).doubleValue();
                    if(Double.isFinite(currentSpeed) && currentSpeed>0)speed=currentSpeed;
                }
                boolean pause=!expanded.getValue() || !playing;
                if(paused==null || paused!=pause){property.invoke(handle,"pause",pause?"yes":"no");paused=pause;}
                double seconds=((Number)number.invoke(handle,"time-pos")).doubleValue();
                var sample=clock.sample(position(),playing,speed,now);
                double audio=sample.seconds();
                double duration=((Number)number.invoke(handle,"duration")).doubleValue();
                if(Double.isFinite(duration) && duration>0)audio=Math.min(audio,Math.max(0,duration-0.04));
                double drift=audio-seconds;
                boolean seeking=Boolean.TRUE.equals(bool.invoke(handle,"seeking"));
                boolean buffering=Boolean.TRUE.equals(bool.invoke(handle,"paused-for-cache"));
                if(Math.abs(drift)>1.5 && !seeking && !buffering) {
                    if(driftSince==0)driftSince=now;
                } else driftSince=0;
                boolean correction=driftSince!=0 && now-driftSince>TimeUnit.MILLISECONDS.toNanos(700)
                        && now-lastSeek>TimeUnit.SECONDS.toNanos(3);
                if(Double.isFinite(seconds) && !seeking && !buffering && (sample.discontinuity()
                        || (pause && Math.abs(drift)>0.08 && now-lastSeek>TimeUnit.SECONDS.toNanos(1)) || correction)) {
                    if(!Boolean.TRUE.equals(command.invoke(handle,(Object)new String[]{"seek",Double.toString(audio),"absolute+exact"})))
                        throw new IllegalStateException("Video seek rejected");
                    lastSeek=now;driftSince=0;
                }
                // Small clock drift is corrected gradually; exact seeks restart decoding from a keyframe.
                double targetSpeed=speed;
                if(!pause && !seeking && !buffering && Double.isFinite(drift) && Math.abs(drift)>0.12
                        && Math.abs(drift)<1.5)targetSpeed=speed*(1+Math.max(-0.04,Math.min(0.04,drift*0.06)));
                if(Math.abs(targetSpeed-videoSpeed)>0.002) {
                    property.invoke(handle,"speed",Double.toString(targetSpeed));videoSpeed=targetSpeed;
                }
            } catch(Exception | LinkageError failure) {failed(ticket);}
        }
    }
    private void publish(boolean requested,boolean loading,String message) {
        View next=new View(selection,source!=null || hasNativeVideo(videoState.getValue()),requested,loading,message,player);
        long ticket=generation;
        SaltPageContentBinding.onUiThread(()->{
            synchronized(SaltVideoBindings.this) {if(!closed && generation==ticket)view.setValue(next);}
        });
    }
    private void cancelRequest() {var request=pending;pending=null;if(request!=null)request.close();}
    private List<io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.Target> videoOrder() {
        var selected=entry;
        if(selected==null)return List.of();
        var result=new ArrayList<io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.Target>();
        result.add(selected.target());
        try {
            // Reading INSTANCE initializes PlaybackService/BASS. Wait until video is requested,
            // and do it on the precache worker, never during plugin startup or composition.
            if(videoMode && queue==null && !queueUnavailable)try {
                queue=(StateFlow<?>)playbackController.getDeclaringClass().getMethod("getPlaybackQueueState").invoke(playbackController.get(null));
            } catch(ReflectiveOperationException | LinkageError unavailable) {queueUnavailable=true;}
            if(queue!=null) {
                Object state=queue.getValue();String idNow=selection;var cached=cachedOrder;
                if(cached!=null && cached.state()==state && cached.entry()==selected && cached.selection().equals(idNow))return cached.targets();
                var plan=NativePlaylistOrder.read(state);
                if(plan.current().equals(idNow)) {
                    var wanted=new HashSet<>(plan.next());
                    var found=new HashMap<String,io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.Target>();
                    List<?> items=(List<?>)state.getClass().getMethod(plan.mode().equals("Random")?"\u052a":"\u0528").invoke(state);
                    for(Object row:items) {
                        Object item=row.getClass().getMethod("\u0528").invoke(row);
                        String id=(String)itemId.invoke(item);
                        if(wanted.remove(id)) {
                            var next=tracks.apply(id,(String)itemPath.invoke(item));
                            if(next!=null)found.put(id,next.target());
                            if(wanted.isEmpty())break;
                        }
                    }
                    for(String id:plan.next())if(found.containsKey(id) && !result.contains(found.get(id)))result.add(found.get(id));
                }
                cachedOrder=new QueuePlan(state,selected,idNow,List.copyOf(result));
            }
        } catch(ReflectiveOperationException ignored) { }
        return List.copyOf(result);
    }
    public void cacheLimit(long bytes) {transport.cacheLimit(bytes);precache.cancel();}
    public void clearCache() {transport.clearCache();precache.cancel();}
    private void releasePlayer() {
        var barriers=new ArrayList<>(surfaceRetirements);surfaceRetirements.clear();
        for(var surface:surfaces) {surface.close();barriers.add(surface.released());}
        surfaces.clear();
        Object retired=player;player=null;handle=null;
        stream=null;
        retirePlayer(retired,CompletableFuture.allOf(barriers.toArray(CompletableFuture[]::new)));
    }
    private void retirePlayer(Object retired,CompletableFuture<Void> released) {
        if(retired instanceof AutoCloseable closeable)
            released.thenRunAsync(()->{
                synchronized(nativeControl) {try{closeable.close();}catch(Exception ignored){}}
            },RETIREMENTS);
    }
    @Override public synchronized void close() {
        NativeWindowCallbackRetention.capture();
        if(closed)return;closed=true;generation++;cancelRequest();releasePlayer();
        precache.close();
        SaltPageContentBinding.onUiThread(this::restoreNativeTransport);
        detachWindowObserver();
        hooks.forEach(HookHandle::close);hooks.clear();worker.shutdownNow();resolver.shutdownNow();transport.close();
    }
}
