package io.github.yghfv.saltneri.plugin.host;

import com.xuncorp.spw.workshop.api.hook.*;
import java.util.*;
import java.util.function.*;

/** Retry an unloaded owned track only in response to native Play, never at startup. */
public final class SavedPlaybackRecovery implements AutoCloseable {
    public record Selection(Object service,String id,String path,String state) { }
    public interface Host {
        Selection selection(Object service) throws Exception;
        void prepare(Object service) throws Exception;
    }
    private final Host host;
    private final BooleanSupplier active;
    private final BiPredicate<String,String> owned;
    private final Runnable arm;
    private final HookHandle hook;
    private volatile boolean closed;
    private long lastAttempt;
    private String lastId;
    public SavedPlaybackRecovery(Host host,Salt1193EngineLink.HookInstaller hooks,BooleanSupplier active,
                                 BiPredicate<String,String> owned,Runnable arm) {
        this.host=host;this.active=active;this.owned=owned;this.arm=arm;
        hook=hooks.install(Salt1193EngineLink.SERVICE,"play",List.of(),new MethodHook() {
            @Override public void before(MethodHookParam call) {recover(call.getThisObject());}
        });
    }
    private void recover(Object service) {
        if(closed || !active.getAsBoolean()) return;
        try {
            Selection selected=host.selection(service);
            if(!eligible(selected,service)) return;
            long now=System.nanoTime();
            synchronized(this) {
                if(selected.id().equals(lastId) && now-lastAttempt<1_500_000_000L) return;
                lastId=selected.id();lastAttempt=now;
            }
            arm.run();
            // Recheck after setup: a queued navigation/track change must not reload another item.
            Selection current=host.selection(service);
            if(active.getAsBoolean() && !closed && eligible(current,service) && current.id().equals(selected.id()) && current.path().equals(selected.path()))
                host.prepare(service);
        } catch(Exception | LinkageError ignored) { /* Original Play still executes; no auto-retry or queue replacement. */ }
    }
    private boolean eligible(Selection s,Object service) {
        return s!=null && s.service()==service && s.state().equals("Idle") && owned.test(s.id(),s.path());
    }
    public static Host nativeHost(ClassLoader loader,Salt1193Probe.Result profile) throws ReflectiveOperationException {
        var access=Salt1193EngineLink.nativeHost(loader,profile);
        Class<?> player=Class.forName(Salt1193EngineLink.PLAYER,false,loader);
        var current=player.getMethod("\u0788");var state=player.getMethod("\u078a");
        var prepare=Class.forName(Salt1193EngineLink.SERVICE,false,loader).getMethod("prepare");
        return new Host() {
            public Selection selection(Object service) throws Exception {
                if(service!=access.service()) return null;
                Object nativePlayer=access.player(service), item=current.invoke(nativePlayer);
                if(item==null) return null;String[] fields=profile.mediaFactory().read(item);
                return new Selection(service,fields[0],fields[5],((Enum<?>)state.invoke(nativePlayer)).name());
            }
            public void prepare(Object service) throws Exception {prepare.invoke(service);}
        };
    }
    @Override public void close() {closed=true;hook.close();}
}
