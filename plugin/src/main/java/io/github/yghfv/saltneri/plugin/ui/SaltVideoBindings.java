package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.*;
import androidx.compose.ui.Modifier;
import com.xuncorp.spw.workshop.api.hook.*;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.host.SavedTrackStore;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.transport.*;
import io.github.yghfv.saltneri.plugin.video.MusicVideoClient;
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
    public static final String CAPTION="com.moriafly.salt.ui.platform.windows.\u0560";
    public static final List<String> CAPTION_PARAMETERS=List.of("androidx.compose.ui.\u012e","androidx.compose.foundation.layout.BoxScope",
            "androidx.compose.ui.window.WindowState","boolean","androidx.compose.ui.window.FrameWindowScope",
            "androidx.compose.runtime.MutableState","androidx.compose.runtime.MutableState","androidx.compose.runtime.MutableState",
            "androidx.compose.runtime.Composer","int");
    public static final String FULLSCREEN="androidx.compose.ui.\u0118";
    public static final List<String> FULLSCREEN_PARAMETERS=List.of("kotlin.jvm.functions.Function0","boolean","androidx.compose.ui.Modifier","boolean");
    public static final String COVER="com.xuncorp.voxzen.ui.player.mini.\u052d";
    public static final List<String> COVER_PARAMETERS=List.of("com.xuncorp.pisces.PiscesMediaItem","androidx.compose.runtime.Composer","int");
    public static final String FLOAT_COVER="androidx.compose.ui.\u06a0";
    public static final List<String> FLOAT_COVER_PARAMETERS=List.of("boolean","boolean");
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
    private final Set<SaltVideoSurface> surfaces=Collections.newSetFromMap(new IdentityHashMap<>());
    private final StateFlow<?> current,progress,isPlaying,videoState;
    private final Method itemId,itemPath,progressPosition;
    private final Constructor<?> playerConstructor;
    private final Method handleGetter,command,property,number,integer,bool,captionButton,localCaption;
    private final Method getPlaybackSpeed,playOrPause;
    private final Field playbackController;
    private final Method captionWidth;
    private final Method titleBar,transportControls,playerScreen,fullscreenButton;
    private final Method captionGroup,coverContent,floatCover;
    private final ThreadLocal<Set<String>> nativeInvocation=ThreadLocal.withInitial(HashSet::new);
    private final Constructor<?> transportState;
    private final Method transportActive;
    private final MutableState<Boolean> controlsVisible=SnapshotStateKt.mutableStateOf(true,SnapshotStateKt.structuralEqualityPolicy());
    private final MutableState<Long> activity=SnapshotStateKt.mutableStateOf(0L,SnapshotStateKt.structuralEqualityPolicy());
    private final MutableState<java.awt.Window> controlsWindow=SnapshotStateKt.mutableStateOf(null,SnapshotStateKt.structuralEqualityPolicy());
    private java.awt.event.MouseAdapter windowObserver;
    private final LoopbackRangeServer transport;
    interface VideoRequests {
        MusicVideoClient.Source discover(MusicVideoClient.Source selected,AccountCredentials credentials,RequestScope request) throws Exception;
        MediaLease resolve(MusicVideoClient.Source selected,AccountCredentials credentials,String scope,RequestScope request) throws Exception;
    }
    private final VideoRequests requests;
    private final Runnable playbackAction;
    private volatile RequestScope pending;
    private volatile boolean closed;
    private volatile long generation;
    private volatile String selection="";
    private volatile SavedTrackStore.Entry entry;
    private MusicVideoClient.Source source;
    private AccountCredentials account;
    private Object player,handle;
    private URI stream;
    private Boolean paused;
    private double speed=1;
    private double videoSpeed=1;
    private VideoAudioClock clock=new VideoAudioClock();
    private long loadDeadline,lastSeek,driftSince;
    private boolean loaded,nativeAvailable;
    private final MutableState<Boolean> expanded=SnapshotStateKt.mutableStateOf(false,SnapshotStateKt.structuralEqualityPolicy());
    private boolean videoMode;

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
        },null);
    }
    SaltVideoBindings(ClassLoader host,HookRegistrar registrar,BooleanSupplier active,
            BiFunction<String,String,SavedTrackStore.Entry> tracks,Function<AccountPlatform,AccountCredentials> accounts,
            Consumer<String> toast,VideoRequests requests,LoopbackRangeServer transport) throws Exception {
        this(host,registrar,active,tracks,accounts,toast,requests,transport,null);
    }
    SaltVideoBindings(ClassLoader host,HookRegistrar registrar,BooleanSupplier active,
            BiFunction<String,String,SavedTrackStore.Entry> tracks,Function<AccountPlatform,AccountCredentials> accounts,
            Consumer<String> toast,VideoRequests requests,LoopbackRangeServer transport,Runnable playbackAction) throws Exception {
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
        transportControls=type(TRANSPORT).getMethod("\u037f",Function0.class,
                Modifier.class,long.class,type("androidx.compose.ui.\u0aa0"),type("com.xuncorp.voxzen.ui.player.mini.\u05ef"),
                Composer.class,int.class,int.class);
        transportState=type("com.xuncorp.voxzen.ui.player.mini.\u05ef").getConstructor();
        transportActive=transportState.getDeclaringClass().getMethod("\u037f",boolean.class);
        playerScreen=type(SCREEN).getMethod("\u037f",Function0.class,boolean.class,type("androidx.compose.ui.\u091a"),Modifier.class,Composer.class,int.class,int.class);
        fullscreenButton=type(FULLSCREEN).getMethod("\u037f",Function0.class,boolean.class,Modifier.class,boolean.class,Composer.class,int.class,int.class);
        captionGroup=type(CAPTION).getDeclaredMethod("\u037f",type(CAPTION_PARAMETERS.get(0)),
                androidx.compose.foundation.layout.BoxScope.class,androidx.compose.ui.window.WindowState.class,boolean.class,
                androidx.compose.ui.window.FrameWindowScope.class,MutableState.class,MutableState.class,MutableState.class,Composer.class,int.class);
        captionGroup.setAccessible(true);
        coverContent=type(COVER).getDeclaredMethod("\u037f",type("com.xuncorp.pisces.PiscesMediaItem"),Composer.class,int.class);
        coverContent.setAccessible(true);
        floatCover=type(FLOAT_COVER).getMethod("\u037f",boolean.class,boolean.class,Composer.class,int.class,int.class);
        Class<?> controller=type("com.xuncorp.voxzen.service.PlaybackController");
        getPlaybackSpeed=controller.getMethod("getPlaybackSpeed");
        playOrPause=controller.getMethod("playOrPause");
        playbackController=controller.getField("INSTANCE");
        this.transport=transport==null?LoopbackRangeServer.video(()->!closed && active.getAsBoolean()):transport;
        try {
            Class<?> content=Class.forName("io.github.yghfv.saltneri.plugin.ui.SaltVideoContent",true,getClass().getClassLoader());
            hooks.add(registrar.replaceComposable(TITLE,"\u037f",WINDOW,TITLE_PARAMETERS,50,
                    (ComposableHook)content.getConstructor(SaltVideoBindings.class).newInstance(this)));
            interceptComposable(registrar,TRANSPORT,TRANSPORT_PARAMETERS,5,"SaltVideoTransportContent");
            interceptComposable(registrar,SCREEN,SCREEN_PARAMETERS,4,"SaltVideoScreenContent");
            interceptComposable(registrar,FULLSCREEN,FULLSCREEN_PARAMETERS,4,"SaltVideoCaptionContent");
            interceptComposable(registrar,CAPTION,CAPTION_PARAMETERS.subList(0,8),8,"SaltVideoWindowCaptionContent");
            interceptComposable(registrar,COVER,COVER_PARAMETERS.subList(0,1),1,"SaltVideoCoverContent");
            interceptComposable(registrar,FLOAT_COVER,FLOAT_COVER_PARAMETERS,2,"SaltVideoFloatCoverContent");
        } catch(Exception | LinkageError error) {close();throw error;}
        worker.scheduleWithFixedDelay(this::tick,0,100,TimeUnit.MILLISECONDS);
    }
    private void interceptComposable(HookRegistrar registrar,String owner,List<String> parameters,int composerIndex,
            String content) throws Exception {
        var hook=(ComposableHook)Class.forName("io.github.yghfv.saltneri.plugin.ui."+content,true,getClass().getClassLoader())
                .getConstructor(SaltVideoBindings.class).newInstance(this);
        interceptComposable(registrar,owner,parameters,composerIndex,hook);
    }
    private void interceptComposable(HookRegistrar registrar,String owner,List<String> parameters,int composerIndex,
            ComposableHook content) throws Exception {
        var signature=new ArrayList<>(parameters);signature.add(Composer.class.getName());signature.add("int");
        if(!owner.equals(CAPTION) && !owner.equals(COVER))signature.add("int");
        Method draw=content.getClass().getMethod("Content",UiHookCall.class,Composer.class,int.class);
        hooks.add(registrar.hookMethod(owner,"\u037f",signature,new MethodHook() {
            @Override public void before(MethodHookParam param) {
                if(closed || nativeInvocation.get().contains(owner))return;
                Object[] args=param.getArgs();
                // A nested original composable can restart itself without its wrapper.
                // Keep that restart group intact; visibility belongs to the outer Content.
                if(!owner.equals(CAPTION) && (((Number)args[composerIndex+1]).intValue()&1)!=0)return;
                nativeInvocation.get().add(owner);
                try {
                    var call=new UiHookCall() {
                        public Object getThisObject(){return param.getThisObject();}
                        public List<Object> getArguments(){return Arrays.asList(Arrays.copyOf(args,composerIndex));}
                        public Object getArgument(String name){return name.equals("defaultMask") && args.length>composerIndex+2?args[composerIndex+2]:null;}
                    };
                    draw.invoke(content,call,args[composerIndex],0);
                    param.setResult(owner.equals(CAPTION)?kotlin.Unit.INSTANCE:null);
                } catch(InvocationTargetException failure) {
                    if(failure.getCause() instanceof Error error)throw error;
                    if(failure.getCause() instanceof RuntimeException error)throw error;
                    throw new IllegalStateException("Native video content failed",failure.getCause());
                } catch(ReflectiveOperationException failure) {throw new IllegalStateException("Native video content unavailable",failure);}
                finally {nativeInvocation.get().remove(owner);}
            }
        }));
    }
    private Class<?> type(String name) throws ClassNotFoundException {return Class.forName(name,false,host);}
    public View view() {return view.getValue();}
    public boolean controlsVisible() {return controlsVisible.getValue();}
    public long activity() {return activity.getValue();}
    public void showControls() {controlsVisible.setValue(true);}
    public void hideControls() {controlsVisible.setValue(false);}
    public void pointerActivity() {
        if(closed || !expanded.getValue() || !view().requested())return;
        showControls();activity.setValue(activity.getValue()+1);
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
        var observer=new java.awt.event.MouseAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent event) {pointerActivity();}
            @Override public void mouseDragged(java.awt.event.MouseEvent event) {pointerActivity();}
            @Override public void mouseEntered(java.awt.event.MouseEvent event) {pointerActivity();}
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
    public Object createTransportState() {
        try {Object state=transportState.newInstance();activateTransport(state);return state;}
        catch(ReflectiveOperationException failure) {throw new IllegalStateException("Video transport state unavailable",failure);}
    }
    public void activateTransport(Object state) {
        try {transportActive.invoke(state,true);}
        catch(ReflectiveOperationException failure) {throw new IllegalStateException("Video transport visibility unavailable",failure);}
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
    public void transportControls(UiHookCall call,Modifier modifier,Object state,Composer composer) {
        try {invokeNative(transportControls,call.getArgument(0),modifier,call.getArgument(2),call.getArgument(3),state,composer,0,defaults(call,0)&~18);}
        catch(ReflectiveOperationException failure) {throw new IllegalStateException("Video transport unavailable",failure);}
    }
    public void originalScreen(UiHookCall call,Composer composer) {
        try {invokeNative(playerScreen,call.getArgument(0),call.getArgument(1),call.getArgument(2),
                call.getArgument(3),composer,0,defaults(call,call.getArgument(3)==null?8:0));}
        catch(ReflectiveOperationException failure) {throw new IllegalStateException("Native player screen unavailable",failure);}
    }
    public void originalWindowControls(UiHookCall call,Composer composer) {
        try {
            invokeNative(fullscreenButton,call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),composer,0,defaults(call,call.getArgument(2)==null?12:0));
        } catch(ReflectiveOperationException failure) {throw new IllegalStateException("Native window caption unavailable",failure);}
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
    public void originalCaptionGroup(UiHookCall call,Composer composer) {
        Object[] args=Arrays.copyOf(call.getArguments().toArray(),10);args[8]=composer;args[9]=0;
        boolean invoking=nativeInvocation.get().contains(CAPTION);nativeInvocation.get().add(CAPTION);
        try{captionGroup.invoke(null,args);}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("Native video caption unavailable",failure);}
        finally{if(!invoking)nativeInvocation.get().remove(CAPTION);}
    }
    public void originalCover(UiHookCall call,Composer composer) {
        try{invokeNative(coverContent,call.getArgument(0),composer,0);}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("Native player cover unavailable",failure);}
    }
    public void originalFloatCover(UiHookCall call,Composer composer) {
        try{invokeNative(floatCover,call.getArgument(0),call.getArgument(1),composer,0,defaults(call,0));}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("Native floating cover unavailable",failure);}
    }
    @SuppressWarnings("unchecked")
    public void clearCaptionHitRegions(Object[] args) {
        // These are the native minimize, maximize and close regions captured by Windows.
        for(int index=5;index<=7;index++)
            ((MutableState<Object>)args[index]).setValue(androidx.compose.ui.geometry.Rect.Companion.getZero());
    }
    public void originalTransport(UiHookCall call,Composer composer) {
        try {invokeNative(transportControls,call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),
                call.getArgument(4),composer,0,defaults(call,call.getArgument(4)==null?16:0));}
        catch(ReflectiveOperationException failure) {throw new IllegalStateException("Native transport unavailable",failure);}
    }
    private Object invokeNative(Method method,Object... args) throws ReflectiveOperationException {
        String owner=method.getDeclaringClass().getName();
        boolean invoking=nativeInvocation.get().contains(owner);nativeInvocation.get().add(owner);
        try{return method.invoke(null,args);}
        finally{if(!invoking)nativeInvocation.get().remove(owner);}
    }
    private int defaults(UiHookCall call,int fallback) {
        return call.getArgument("defaultMask") instanceof Number mask?mask.intValue():fallback;
    }
    public synchronized SaltVideoSurface surface(Object renderer,java.awt.Window window,Runnable redraw) {
        if(closed || player!=renderer)return null;
        long ticket=generation;
        try {
            var surface=new SaltVideoSurface(renderer,window,redraw,error->surfaceFailed(renderer,ticket));
            surfaces.add(surface);return surface;
        } catch(Exception | LinkageError failure) {surfaceFailed(renderer,ticket);return null;}
    }
    public synchronized void disposeSurface(SaltVideoSurface surface) {
        if(surface==null)return;surfaces.remove(surface);surface.close();
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
            if(videoMode) {videoMode=false;generation++;cancelRequest();releasePlayer();publish(false,false,"");return;}
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
        long ticket=++generation;cancelRequest();publish(true,true,"正在加载视频");
        var selected=source;var credentials=account;
        pending=new RequestScope(()->valid(ticket,credentials),30000);
        var request=pending;
        resolver.execute(()->{
            try {
                String scope=credentials==null?"anonymous-video":credentials.accountScope();
                var lease=requests.resolve(selected,credentials,scope,request);
                lease=lease.bindTo(scope,()->valid(ticket,credentials));
                URI local=transport.register(lease);
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
                && (entry==null || accounts.apply(entry.target().platform())==credentials)
                && (credentials==null || credentials.isActive()) && selection.equals(currentId());
    }
    private String currentId() {
        try {Object item=current.getValue();return item==null?"":(String)itemId.invoke(item);}
        catch(ReflectiveOperationException ignored) {return "";}
    }
    private synchronized void startPlayer(long ticket,AccountCredentials credentials,URI local) {
        if(!valid(ticket,credentials)){transport.revoke(local);return;}
        Object created=null;
        try {
            releasePlayer();
            created=playerConstructor.newInstance(this,EmptyCoroutineContext.INSTANCE);
            Object port=handleGetter.invoke(created);
            // Salt's bundled player defaults to AV1 only; permit FFmpeg's AVC and local MV decoders.
            for(var option:Map.of("vd","lavc:*","aid","no","mute","yes","pause","yes",
                    "keepaspect","yes","video-unscaled","no").entrySet())
                if(!Boolean.TRUE.equals(property.invoke(port,option.getKey(),option.getValue())))
                    throw new IllegalStateException("Video option rejected");
            double position=position()/1000d;
            if(!Boolean.TRUE.equals(command.invoke(port,(Object)new String[]{"loadfile",local.toString(),"replace","-1","start="+position})))
                throw new IllegalStateException("Video stream rejected");
            player=created;handle=port;stream=local;paused=null;speed=1;videoSpeed=1;loaded=false;lastSeek=0;driftSince=0;
            clock=new VideoAudioClock();
            loadDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            publish(true,true,"正在加载视频");
        } catch(Exception | LinkageError failed) {
            if(created instanceof AutoCloseable closeable)try{closeable.close();}catch(Exception ignored){}
            transport.revoke(local);failed(ticket);
        }
    }
    private synchronized void failed(long ticket) {
        if(generation!=ticket || closed)return;
        videoMode=false;
        releasePlayer();publish(false,false,"");
        SaltPageContentBinding.onUiThread(()->{if(!closed)toast.accept("视频加载失败，请稍后重试");});
    }
    private long position() {
        try {return ((Number)progressPosition.invoke(progress.getValue())).longValue();}
        catch(ReflectiveOperationException | NullPointerException failure) {return 0;}
    }
    private synchronized void tick() {
        try {
            if(closed)return;
            if(!active.getAsBoolean()){close();return;}
            Object item=current.getValue();String id=item==null?"":(String)itemId.invoke(item);
            boolean changedAccount=entry!=null && accounts.apply(entry.target().platform())!=account;
            if(!id.equals(selection) || changedAccount) {
                if(changedAccount || id.isEmpty())videoMode=false;
                generation++;cancelRequest();releasePlayer();selection=id;entry=null;source=null;account=null;
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
            if(player!=null) {
                long now=System.nanoTime();
                if(!loaded) {
                    if(((Number)integer.invoke(handle,"width")).intValue()>0) {
                        loaded=true;publish(true,false,"");
                    } else {
                        if(now>loadDeadline)failed(generation);
                        return;
                    }
                }
                if(Boolean.TRUE.equals(bool.invoke(handle,"idle-active"))) {failed(generation);return;}
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
            }
        } catch(Exception | LinkageError failure) {if(player!=null)failed(generation);}
    }
    private void publish(boolean requested,boolean loading,String message) {
        View next=new View(selection,source!=null || hasNativeVideo(videoState.getValue()),requested,loading,message,player);
        long ticket=generation;
        SaltPageContentBinding.onUiThread(()->{
            synchronized(SaltVideoBindings.this) {if(!closed && generation==ticket)view.setValue(next);}
        });
    }
    private void cancelRequest() {var request=pending;pending=null;if(request!=null)request.close();}
    private void releasePlayer() {
        surfaces.forEach(SaltVideoSurface::close);surfaces.clear();
        Object retired=player;player=null;handle=null;
        URI local=stream;stream=null;paused=null;loaded=false;
        if(local!=null && "http".equals(local.getScheme()))transport.revoke(local);
        if(retired instanceof AutoCloseable closeable)SaltPageContentBinding.onUiThread(()->{
            try{closeable.close();}catch(Exception ignored){}
        });
    }
    @Override public synchronized void close() {
        if(closed)return;closed=true;generation++;cancelRequest();releasePlayer();
        detachWindowObserver();
        hooks.forEach(HookHandle::close);hooks.clear();worker.shutdownNow();resolver.shutdownNow();transport.close();
    }
}
