package io.github.yghfv.saltneri.plugin.host;

import com.xuncorp.spw.workshop.api.hook.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Reasserts one explicit start at onLoaded, after obsolete control commands are drained. */
final class SaltPlaybackStartBindings implements AutoCloseable {
    private static final String CONTROLLER="com.xuncorp.voxzen.service.PlaybackController";
    private final PlaybackStartGate gate=new PlaybackStartGate();
    private final ThreadLocal<Boolean> submitting=ThreadLocal.withInitial(()->false);
    private final Salt1193EngineLink.HostAccess host;
    private final BooleanSupplier authorized;
    private final Method current,identity,play;
    private final List<HookHandle> handles=new ArrayList<>();
    SaltPlaybackStartBindings(ClassLoader loader,Salt1193Probe.Result profile,HookRegistrar hooks,BooleanSupplier authorized) throws ReflectiveOperationException {
        this.authorized=authorized;
        host=Salt1193EngineLink.nativeHost(loader,profile);
        Class<?> player=Class.forName(Salt1193EngineLink.PLAYER,false,loader);
        current=player.getMethod("\u0788");play=player.getMethod("\u0798");
        identity=Class.forName("com.xuncorp.pisces.PiscesMediaItem",false,loader).getMethod("\u037f");
        player.getDeclaredMethod("\u0795");
        try {
            handles.add(hooks.hookMethod(Salt1193EngineLink.PLAYER,"\u0795",List.of(),new MethodHook() {
                @Override public void after(MethodHookParam call) {
                    if(call.getThrowable()!=null)return;
                    try {
                        Object player=call.getThisObject();
                        if(player!=host.player(host.service()))return;
                        Object item=current.invoke(player);
                        if(item!=null && gate.loaded(id(item),authorized)) play.invoke(player);
                    } catch(ReflectiveOperationException | RuntimeException failure) {gate.cancel();}
                }
            }));
            MethodHook cancel=new MethodHook() {
                @Override public void before(MethodHookParam call) {if(!submitting.get())gate.cancel();}
            };
            for(String action:List.of("pause","stop","previous","next","play","releaseAudioEngine","clearPlaybackQueue"))
                handles.add(hooks.hookMethod(CONTROLLER,action,List.of(),cancel));
            handles.add(hooks.hookMethod(CONTROLLER,"setPlaybackQueue",List.of("java.util.List","int"),cancel));
            handles.add(hooks.hookMethod(CONTROLLER,"addToPlay",List.of("com.xuncorp.pisces.PiscesMediaItem"),cancel));
            handles.add(hooks.hookMethod(CONTROLLER,"playMusicAt",List.of("int","boolean"),cancel));
            handles.add(hooks.hookMethod(CONTROLLER,"seekTo",List.of("long"),cancel));
            handles.add(hooks.hookMethod(CONTROLLER,"seekToQueued",List.of("long"),cancel));
            // A queued native pause/stop also wins if it reaches the player after submission.
            for(String action:List.of("\u0799","\u079a"))
                handles.add(hooks.hookMethod(Salt1193EngineLink.PLAYER,action,List.of(),cancel));
            handles.add(hooks.hookMethod(Salt1193EngineLink.PLAYER,"\u0528",List.of("long"),cancel));
        } catch(RuntimeException | LinkageError failure) {close();throw failure;}
    }
    private String id(Object item) throws ReflectiveOperationException {return (String)identity.invoke(item);}
    interface Submission {void run() throws ReflectiveOperationException;}
    void submit(Object item,Submission submission) throws ReflectiveOperationException {
        if(!authorized.getAsBoolean())throw new SecurityException("Queue permission revoked");
        gate.arm(id(item));submitting.set(true);
        try {submission.run();}
        catch(ReflectiveOperationException | RuntimeException failure) {gate.cancel();throw failure;}
        finally {submitting.remove();}
    }
    void cancel() {gate.cancel();}
    @Override public void close() {gate.cancel();for(int i=handles.size()-1;i>=0;i--)handles.get(i).close();handles.clear();}
}
