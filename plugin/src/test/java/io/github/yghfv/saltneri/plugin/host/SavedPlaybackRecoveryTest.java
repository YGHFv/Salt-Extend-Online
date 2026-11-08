package io.github.yghfv.saltneri.plugin.host;

import com.xuncorp.spw.workshop.api.hook.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Native method-hook ABI, recording commands; no host singleton, audio or account access. */
public final class SavedPlaybackRecoveryTest {
    private static int checks;
    private static void check(boolean value,String message) {checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args) throws Throwable {
        ClassLoader loader=SavedPlaybackRecoveryTest.class.getClassLoader();
        SavedPlaybackRecovery.nativeHost(loader,Salt1193Probe.inspect(loader,"1.19.3","Steam"));
        var calls=new ArrayList<String>();var hook=new AtomicReference<MethodHook>();var active=new AtomicBoolean(true);
        Object service=new Object();var selected=new AtomicReference<>(new SavedPlaybackRecovery.Selection(service,"saved","owned","Idle"));
        var host=new SavedPlaybackRecovery.Host() {
            public SavedPlaybackRecovery.Selection selection(Object owner) {return selected.get();}
            public void prepare(Object owner) {check(owner==service,"Foreign prepare");calls.add("prepare");}
        };
        var unhooks=new AtomicInteger();
        try(var recovery=new SavedPlaybackRecovery(host,(type,method,params,callback)->{
            check(type.equals(Salt1193EngineLink.SERVICE) && method.equals("play") && params.isEmpty(),"Wrong recovery ABI");
            hook.set(callback);return new HookHandle(){public void unhook(){unhooks.incrementAndGet();}};
        },active::get,(id,path)->id.startsWith("saved") && path.equals("owned"),()->calls.add("arm"))) {
            check(calls.isEmpty(),"Startup played or prepared");
            hook.get().before(new EngineLinkContractTest.Call(new Object(),null,null));check(calls.isEmpty(),"Foreign service intercepted");
            for(String state:List.of("Ready","Buffering","Ended")) {
                selected.set(new SavedPlaybackRecovery.Selection(service,"saved","owned",state));
                hook.get().before(new EngineLinkContractTest.Call(service,null,null));check(calls.isEmpty(),"Non-idle stream reloaded");
            }
            selected.set(new SavedPlaybackRecovery.Selection(service,"local","owned","Idle"));
            hook.get().before(new EngineLinkContractTest.Call(service,null,null));check(calls.isEmpty(),"Local song modified");
            selected.set(new SavedPlaybackRecovery.Selection(service,"saved","foreign","Idle"));
            hook.get().before(new EngineLinkContractTest.Call(service,null,null));check(calls.isEmpty(),"Foreign path accepted");
            selected.set(new SavedPlaybackRecovery.Selection(service,"saved","owned","Idle"));
            hook.get().before(new EngineLinkContractTest.Call(service,null,null));calls.add("original-play");
            check(calls.equals(List.of("arm","prepare","original-play")),"Recovery did not prepare before native Play");
            hook.get().before(new EngineLinkContractTest.Call(service,null,null));check(calls.size()==3,"Repeated click caused prepare storm");
            active.set(false);selected.set(new SavedPlaybackRecovery.Selection(service,"saved-other","owned","Idle"));
            hook.get().before(new EngineLinkContractTest.Call(service,null,null));check(calls.size()==3,"Permission bypass");
        }
        active.set(true);hook.get().before(new EngineLinkContractTest.Call(service,null,null));
        check(unhooks.get()==1 && calls.size()==3,"Closed recovery ran");
        var changedHook=new AtomicReference<MethodHook>();
        try(var recovery=new SavedPlaybackRecovery(host,(type,method,params,callback)->{changedHook.set(callback);return new HookHandle(){public void unhook(){}};},
                ()->true,(id,path)->true,()->selected.set(new SavedPlaybackRecovery.Selection(service,"new","owned","Idle")))) {
            changedHook.get().before(new EngineLinkContractTest.Call(service,null,null));check(calls.size()==3,"Setup-time track change reloaded a different song");
        }
        System.out.println("PASS: "+checks+" owned unloaded native Play recovery, ordering, cooldown, revocation and navigation checks; recording commands only");
    }
}
