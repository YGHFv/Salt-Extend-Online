package io.github.yghfv.saltneri.plugin.host;

import com.xuncorp.spw.workshop.api.hook.*;
import java.util.*;
import java.util.function.*;

/** Recover only a failed owned stream attempt; explicit player commands invalidate its ticket. */
public final class AutomaticPlaybackRecovery implements AutoCloseable {
    public record Selection(String id,String path,String state,boolean error,NativePlaylistOrder.Plan plan) { }
    public interface Host {
        Selection selection() throws Exception;
        void retry() throws Exception;
        void next() throws Exception;
    }
    private record Failure(String id,long ticket,String reason,long at) { }
    private final Host host;
    private final BooleanSupplier active;
    private final BiPredicate<String,String> owned;
    private final Consumer<String> toast;
    private final List<HookHandle> hooks=new ArrayList<>();
    private final java.util.concurrent.ScheduledExecutorService worker;
    private final ThreadLocal<Boolean> internal=ThreadLocal.withInitial(()->false);
    private long generation;
    private Failure pending;
    private String failedId;
    private int retries,skips;
    private boolean enabled=true;
    private volatile boolean closed;
    public AutomaticPlaybackRecovery(Host host,Salt1193EngineLink.HookInstaller installer,BooleanSupplier active,
                                     BiPredicate<String,String> owned,Consumer<String> toast) {
        this.host=host;this.active=active;this.owned=owned;this.toast=toast;
        try {
            MethodHook cancel=new MethodHook() {
                @Override public void before(MethodHookParam call) {if(!internal.get()) cancel(false);}
            };
            MethodHook start=new MethodHook() {
                @Override public void before(MethodHookParam call) {if(!internal.get()) cancel(true);}
            };
            String controller="com.xuncorp.voxzen.service.PlaybackController";
            for(String name:List.of("pause","stop","releaseAudioEngine","clearPlaybackQueue"))
                hooks.add(installer.install(controller,name,List.of(),cancel));
            for(String name:List.of("previous","next","play"))
                hooks.add(installer.install(controller,name,List.of(),start));
            hooks.add(installer.install(controller,"setPlaybackQueue",List.of("java.util.List","int"),start));
            hooks.add(installer.install(controller,"addToPlay",List.of("com.xuncorp.pisces.PiscesMediaItem"),start));
            hooks.add(installer.install(controller,"playMusicAt",List.of("int","boolean"),start));
            hooks.add(installer.install(controller,"seekTo",List.of("long"),cancel));
            hooks.add(installer.install(controller,"seekToQueued",List.of("long"),cancel));
            for(String name:List.of("\u0799","\u079a","\u079b","\u079c"))
                hooks.add(installer.install(Salt1193EngineLink.PLAYER,name,List.of(),cancel));
            for(String name:List.of("\u0798","\u079d","\u079e"))
                hooks.add(installer.install(Salt1193EngineLink.PLAYER,name,List.of(),start));
            hooks.add(installer.install(Salt1193EngineLink.PLAYER,"\u0528",List.of("long"),cancel));
            hooks.add(installer.install(Salt1193EngineLink.PLAYER,"\u0528",List.of("com.xuncorp.pisces.PiscesMediaItem"),start));
        } catch(RuntimeException | LinkageError failure) {
            hooks.forEach(HookHandle::close);throw failure;
        }
        worker=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{
            Thread t=new Thread(r,"salt-online-playback-recovery");t.setDaemon(true);return t;
        });
        worker.scheduleWithFixedDelay(this::tick,250,250,java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    public synchronized long started(String id) {return enabled?generation:-1;}
    public synchronized void failed(String id,long ticket,String reason) {
        if(!closed && enabled && ticket==generation) pending=new Failure(id,ticket,reason,System.nanoTime());
    }
    public synchronized void succeeded(String id,long ticket) {
        if(ticket!=generation)return;
        pending=null;failedId=null;retries=0;skips=0;
    }
    private synchronized void cancel(boolean enable) {generation++;enabled=enable;pending=null;failedId=null;retries=0;skips=0;}
    void tick() {
        try {
            Failure failure;
            synchronized(this) {failure=pending;}
            if(closed || failure==null || !active.getAsBoolean()) return;
            Selection selected=host.selection();
            if(selected==null || !selected.id().equals(failure.id()) || !owned.test(selected.id(),selected.path())) return;
            if(!selected.state().equals("Idle") || !selected.error()) return;
            if(System.nanoTime()-failure.at()<750_000_000L) return;
            synchronized(this) {
                if(closed || pending!=failure || generation!=failure.ticket() || !active.getAsBoolean()) return;
                Selection current=host.selection();
                if(!selected.equals(current)) return;
                pending=null;
                if(!failure.id().equals(failedId)) {failedId=failure.id();retries=0;}
                internal.set(true);
                try {
                    if(++retries<=2 && !failure.reason().startsWith("REJECTED")) {
                        host.retry();
                    } else if(skips<5 && !selected.plan().next().isEmpty()
                            && !selected.plan().next().getFirst().equals(selected.id())) {
                        skips++;toast.accept("\u6b4c\u66f2\u64ad\u653e\u5931\u8d25\uff0c\u5df2\u8df3\u8fc7 ["+failure.reason()+"]");
                        host.next();
                    } else {
                        toast.accept("\u64ad\u653e\u5931\u8d25\uff0c\u5df2\u505c\u6b62\u81ea\u52a8\u91cd\u8bd5 ["+failure.reason()+"]");
                    }
                } finally {internal.remove();}
            }
        } catch(Exception | LinkageError ignored) { /* Never let a recovery worker mutate an unknown native state. */ }
    }
    public static Host nativeHost(ClassLoader loader,Salt1193Probe.Result profile) throws ReflectiveOperationException {
        var access=Salt1193EngineLink.nativeHost(loader,profile);
        var type=Class.forName(Salt1193EngineLink.PLAYER,false,loader);
        var current=type.getMethod("\u0788");var state=type.getMethod("\u078a");var error=type.getMethod("\u078b");
        var next=type.getMethod("\u079e");
        var service=Class.forName(Salt1193EngineLink.SERVICE,false,loader);
        var prepare=type.getMethod("\u037f",long.class);var play=type.getMethod("\u0798");var queue=service.getMethod("getPlaybackQueueState");
        return new Host() {
            public Selection selection() throws Exception {
                Object s=access.service(),p=access.player(s),item=current.invoke(p);
                if(item==null) return null;String[] fields=profile.mediaFactory().read(item);
                var snapshot=((kotlinx.coroutines.flow.StateFlow<?>)queue.invoke(s)).getValue();
                return new Selection(fields[0],fields[5],((Enum<?>)state.invoke(p)).name(),error.invoke(p)!=null,NativePlaylistOrder.read(snapshot));
            }
            public void retry() throws Exception {Object p=access.player(access.service());prepare.invoke(p,0L);play.invoke(p);}
            public void next() throws Exception {next.invoke(access.player(access.service()));}
        };
    }
    @Override public synchronized void close() {
        if(closed)return;closed=true;cancel(false);worker.shutdownNow();hooks.forEach(HookHandle::close);hooks.clear();
    }
}
