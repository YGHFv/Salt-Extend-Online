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
    private final Method getPlaybackSpeed;
    private final Field playbackController;
    private final Method captionWidth;
    private final LoopbackRangeServer transport;
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
    private volatile boolean expanded;

    public SaltVideoBindings(ClassLoader host,HookRegistrar registrar,BooleanSupplier active,
            BiFunction<String,String,SavedTrackStore.Entry> tracks,Function<AccountPlatform,AccountCredentials> accounts,
            Consumer<String> toast) throws Exception {
        this.host=host;this.active=active;this.tracks=tracks;this.accounts=accounts;this.toast=toast;
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
        Class<?> controller=type("com.xuncorp.voxzen.service.PlaybackController");
        getPlaybackSpeed=controller.getMethod("getPlaybackSpeed");
        playbackController=controller.getField("INSTANCE");
        transport=LoopbackRangeServer.video(()->!closed && active.getAsBoolean());
        try {
            Class<?> content=Class.forName("io.github.yghfv.saltneri.plugin.ui.SaltVideoContent",true,getClass().getClassLoader());
            hooks.add(registrar.afterComposable(TITLE,"\u037f",WINDOW,TITLE_PARAMETERS,50,
                    (ComposableHook)content.getConstructor(SaltVideoBindings.class).newInstance(this)));
        } catch(Exception | LinkageError error) {close();throw error;}
        worker.scheduleWithFixedDelay(this::tick,0,100,TimeUnit.MILLISECONDS);
    }
    private Class<?> type(String name) throws ClassNotFoundException {return Class.forName(name,false,host);}
    public View view() {return view.getValue();}
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
            return expanded=(boolean)state.getClass().getMethod("\u0528").invoke(state);
        } catch(ReflectiveOperationException failure) {return expanded=false;}
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
            if(view().requested()) {generation++;cancelRequest();releasePlayer();publish(false,false,"");return;}
            if(source==null) {
                if(hasNativeVideo(videoState.getValue()))toggleNativeVideo();
                return;
            }
            long ticket=++generation;cancelRequest();publish(true,true,"正在加载视频");
            var selected=source;var credentials=account;
            pending=new RequestScope(()->valid(ticket,credentials),30000);
            var request=pending;
            resolver.execute(()->{
                try {
                    String scope=credentials==null?"anonymous-video":credentials.accountScope();
                    var lease=new MusicVideoClient(credentials).resolve(selected,scope,request);
                    lease=lease.bindTo(scope,()->valid(ticket,credentials));
                    URI local=transport.register(lease);
                    SaltPageContentBinding.onUiThread(()->startPlayer(ticket,credentials,local));
                } catch(Exception | LinkageError failed) {failed(ticket);}
                finally {request.close();}
            });
        }
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
                    toast.accept("此 MV 的来源暂不支持视频播放");return;
                }
                long ticket=++generation;publish(true,true,"正在加载视频");
                var credentials=account;
                SaltPageContentBinding.onUiThread(()->startPlayer(ticket,credentials,uri));
            } catch(Exception failed) {toast.accept("MV 来源无法读取");}
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
                generation++;cancelRequest();releasePlayer();selection=id;entry=null;source=null;account=null;
                if(item!=null)entry=tracks.apply(id,(String)itemPath.invoke(item));
                publish(false,false,"");
                if(entry!=null) {
                    var target=entry.target();account=accounts.apply(target.platform());var credentials=account;
                    long ticket=generation;
                    if(credentials!=null && !credentials.isActive())return;
                    if(target.platform()==AccountPlatform.BILIBILI) {source=new MusicVideoClient.Source(target,target.id());publish(false,false,"");}
                    else if(target.platform()==AccountPlatform.NETEASE || target.platform()==AccountPlatform.QQMUSIC) {
                        pending=new RequestScope(()->valid(ticket,credentials));var request=pending;
                        resolver.execute(()->{
                            try {
                                var result=new MusicVideoClient(credentials).discover(target,request);
                                synchronized(SaltVideoBindings.this) {if(valid(ticket,credentials)){source=result;publish(false,false,"");}}
                            } catch(Exception ignored) {}
                            finally {request.close();}
                        });
                    }
                }
            }
            if(account!=null && (!account.isActive() || accounts.apply(account.platform())!=account)) {
                generation++;cancelRequest();releasePlayer();source=null;publish(false,false,"");return;
            }
            boolean available=hasNativeVideo(videoState.getValue());
            if(available!=nativeAvailable) {nativeAvailable=available;var state=view();publish(state.requested(),state.loading(),state.message());}
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
                boolean pause=!expanded || !playing;
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
        hooks.forEach(HookHandle::close);hooks.clear();worker.shutdownNow();resolver.shutdownNow();transport.close();
    }
}
