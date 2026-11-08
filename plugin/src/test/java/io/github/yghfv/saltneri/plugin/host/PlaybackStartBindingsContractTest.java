package io.github.yghfv.saltneri.plugin.host;

import com.xuncorp.spw.workshop.api.hook.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Reflection audit never resolves the playback singleton or opens an audio device. */
public final class PlaybackStartBindingsContractTest {
    private static int checks;
    private static void check(boolean ok) {checks++;if(!ok)throw new AssertionError("Start bindings "+checks);}
    private static final class Hooks extends HookRegistrar {
        int installed,closed;
        public HookHandle hookMethod(String c,String m,List<String> types,MethodHook callback) {
            try {
                var host=Class.forName(c,false,getClass().getClassLoader());
                Class<?>[] args=types.stream().map(t->{
                    try {return switch(t) {case "int"->int.class;case "boolean"->boolean.class;case "long"->long.class;default->Class.forName(t,false,getClass().getClassLoader());};}
                    catch(ClassNotFoundException failure){throw new AssertionError(failure);}
                }).toArray(Class<?>[]::new);
                host.getDeclaredMethod(m,args);
            } catch(ReflectiveOperationException failure) {throw new AssertionError(failure);}
            installed++;return new HookHandle() {boolean done;public void unhook(){if(!done){closed++;done=true;}}public void close(){unhook();}};
        }
        public HookHandle afterComposable(String c,String m,String i,List<String> t,int p,ComposableHook h) {throw new AssertionError();}
        public HookHandle replaceComposable(String c,String m,String i,List<String> t,int p,ComposableHook h) {throw new AssertionError();}
        public <S> HookHandle appendContent(String c,String m,String i,String a,Class<S>s,List<String>t,int p,ContentHook<S>h) {throw new AssertionError();}
    }
    public static void main(String[] args) throws Exception {
        var host=PlaybackStartBindingsContractTest.class.getClassLoader();
        var profile=Salt1193Probe.inspect(host,"1.19.3","Steam");var hooks=new Hooks();var allowed=new AtomicBoolean(true);
        Object item=profile.mediaFactory().create("fixture","Song","Artist","","","saltneri://fixture");
        var calls=new AtomicInteger();
        try(var binding=new SaltPlaybackStartBindings(host,profile,hooks,allowed::get)) {
            check(hooks.installed==16);
            binding.submit(item,calls::incrementAndGet);check(calls.get()==1);
            allowed.set(false);
            try {binding.submit(item,calls::incrementAndGet);throw new AssertionError();}
            catch(SecurityException expected){check(calls.get()==1);}
            binding.cancel();
        }
        check(hooks.closed==hooks.installed);
        System.out.println("PASS: "+checks+" exact native loaded/control/seek hook signatures, submission authorization and cleanup checks; no native engine");
    }
}
