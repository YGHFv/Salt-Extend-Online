package io.github.yghfv.saltneri.plugin.host;

import com.xuncorp.spw.workshop.api.hook.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Recording native commands only; no playback singleton or audio device is initialized. */
public final class AutomaticPlaybackRecoveryTest {
    private static int checks;
    private static void check(boolean ok,String detail){checks++;if(!ok)throw new AssertionError(detail);}
    public static void main(String[] args)throws Exception {
        var profile=Salt1193Probe.inspect(AutomaticPlaybackRecoveryTest.class.getClassLoader(),"1.19.3","Steam");
        AutomaticPlaybackRecovery.nativeHost(AutomaticPlaybackRecoveryTest.class.getClassLoader(),profile);
        var active=new AtomicBoolean(true);
        var selection=new AtomicReference<>(selected("a","Idle",true,List.of("b","c")));
        var calls=new ArrayList<String>();var messages=new ArrayList<String>();var hooks=new HashMap<String,MethodHook>();
        var unhooks=new AtomicInteger();var installs=new AtomicInteger();
        var host=new AutomaticPlaybackRecovery.Host(){
            public AutomaticPlaybackRecovery.Selection selection(){return selection.get();}
            public void retry(){calls.add("retry");}
            public void next(){calls.add("next");selection.set(selected("b","Idle",true,List.of("c")));}
        };
        try(var recovery=new AutomaticPlaybackRecovery(host,(type,name,parameters,callback)->{
            hooks.put(type+"."+name,callback);
            installs.incrementAndGet();
            return new HookHandle(){public void unhook(){unhooks.incrementAndGet();}};
        },active::get,(id,path)->path.equals("owned"),messages::add)) {
            check(calls.isEmpty(),"Construction changed playback");
            fail(recovery,"a","AUDIO_RESOLUTION");check(calls.equals(List.of("retry")),"First retry missing");
            fail(recovery,"a","AUDIO_RESOLUTION");check(calls.equals(List.of("retry","retry")),"Second retry missing");
            fail(recovery,"a","AUDIO_RESOLUTION");check(calls.equals(List.of("retry","retry","next")) && messages.size()==1,"Failed track not skipped");
            check(!messages.getFirst().contains("http"),"Toast leaked upstream URL");
            recovery.failed("b",recovery.started("b"),"AUDIO_RESOLUTION");
            hooks.get("com.xuncorp.voxzen.service.PlaybackController.pause").before(new EngineLinkContractTest.Call(null,null,null));
            Thread.sleep(850);check(calls.size()==3,"Explicit pause lost to recovery");
            fail(recovery,"b","AUDIO_RESOLUTION");check(calls.size()==3,"New failed attempt after pause restarted playback");
            long old=recovery.started("b");
            hooks.get(Salt1193EngineLink.PLAYER+".\u079a").before(new EngineLinkContractTest.Call(null,null,null));
            recovery.failed("b",old,"AUDIO_RESOLUTION");Thread.sleep(850);check(calls.size()==3,"Cancelled in-flight error resurrected playback");
            selection.set(selected("b","Ready",false,List.of("c")));
            fail(recovery,"b","AUDIO_RESOLUTION");check(calls.size()==3,"Ready song reloaded");
            recovery.succeeded("b",recovery.started("b"));selection.set(selected("b","Idle",true,List.of("c")));
            active.set(false);fail(recovery,"b","AUDIO_RESOLUTION");check(calls.size()==3,"Permission loss ignored");
            active.set(true);
            hooks.get("com.xuncorp.voxzen.service.PlaybackController.play").before(new EngineLinkContractTest.Call(null,null,null));
            selection.set(new AutomaticPlaybackRecovery.Selection("local","foreign","Idle",true,new NativePlaylistOrder.Plan("local","Circle",List.of("c"))));
            fail(recovery,"local","AUDIO_RESOLUTION");check(calls.size()==3,"Foreign song recovered");
            selection.set(selected("b","Idle",true,List.of("c")));
            hooks.get("com.xuncorp.voxzen.service.PlaybackController.play").before(new EngineLinkContractTest.Call(null,null,null));
            fail(recovery,"b","REJECTED");check(calls.size()==4 && calls.getLast().equals("next"),"Platform rejection retried");
            recovery.succeeded("b",recovery.started("b"));selection.set(selected("b","Idle",true,List.of()));
            fail(recovery,"b","REJECTED");check(calls.size()==4 && messages.size()==3,"Endless/end-of-queue skip");
        }
        check(unhooks.get()==installs.get(),"Hooks leaked");
        System.out.println("PASS: "+checks+" automatic retry/skip, native ABI, explicit pause/stop, cancellation, revocation, local isolation and end-of-queue checks");
    }
    private static AutomaticPlaybackRecovery.Selection selected(String id,String state,boolean error,List<String> next) {
        return new AutomaticPlaybackRecovery.Selection(id,"owned",state,error,new NativePlaylistOrder.Plan(id,"Random",next));
    }
    private static void fail(AutomaticPlaybackRecovery recovery,String id,String reason)throws Exception{
        recovery.failed(id,recovery.started(id),reason);Thread.sleep(900);recovery.tick();
    }
}
