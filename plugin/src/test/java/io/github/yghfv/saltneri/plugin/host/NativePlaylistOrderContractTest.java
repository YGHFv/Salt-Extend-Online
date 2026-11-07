package io.github.yghfv.saltneri.plugin.host;

import java.util.*;
import kotlin.Unit;
import kotlin.coroutines.*;
import kotlin.jvm.functions.*;

/** Actual native queue model only; no PlaybackController/service, engine or configuration initialized. */
public final class NativePlaylistOrderContractTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if(!ok) throw new AssertionError("Native order "+checks); }
    public static void main(String[] args) throws Exception {
        var profile=Salt1193Probe.inspect(NativePlaylistOrderContractTest.class.getClassLoader(),"1.19.3","Steam"); check(profile.matched());
        Class<?> queueClass=Class.forName("com.xuncorp.spc.core.queue.PlaybackQueue");
        Class<?> modeClass=Class.forName("com.xuncorp.spc.core.queue.PlaybackQueue$Mode");
        var random=new Random(4); Set<Integer> firsts=new HashSet<>();
        List<Object> items=new ArrayList<>();
        for(int i=0;i<12;i++) items.add(profile.mediaFactory().create("saltneri:fixture:"+i,"Song","Artist","","","fixture:"+i));
        Continuation<Unit> done=new Continuation<>() {
            public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }
            public void resumeWith(Object value) { throw new AssertionError("Unexpected suspended model operation"); }
        };
        Function2<Object,Object,Boolean> same=(a,b)->a.equals(b);
        for(Object mode:modeClass.getEnumConstants()) {
            String name=((Enum<?>)mode).name();
            Object queue=queueClass.getConstructor(Function2.class,Function1.class).newInstance(same,null);
            queueClass.getMethod("\u037f",List.class,int.class,Continuation.class).invoke(queue,items,0,done);
            queueClass.getMethod("\u037f",modeClass,Continuation.class).invoke(queue,mode,done);
            for(int attempt=0;attempt<8;attempt++) {
                int index=NativePlaylistOrder.firstIndex(name,items.size(),random::nextInt);
                if(name.equals("Random")) firsts.add(index); else check(index==0);
                queueClass.getMethod("\u037f",List.class,int.class,Continuation.class).invoke(queue,items,index,done);
                Object state=((kotlinx.coroutines.flow.StateFlow<?>)queueClass.getMethod("\u037f").invoke(queue)).getValue();
                var plan=NativePlaylistOrder.read(state);
                check(plan.mode().equals(name) && plan.current().equals("saltneri:fixture:"+index));
                check(plan.next().size()==(name.equals("RepeatOne")?0:3));
                List<?> order=(List<?>)state.getClass().getMethod(name.equals("Random")?"\u052a":"\u0528").invoke(state);
                for(int i=0;i<plan.next().size();i++) {
                    Object item=order.get(i+1).getClass().getMethod("\u0528").invoke(order.get(i+1));
                    check(plan.next().get(i).equals(profile.mediaFactory().read(item)[0]));
                }
                check(NativePlaylistOrder.mode(state).equals(name));
            }
            queueClass.getMethod("\u037f",List.class,int.class,Continuation.class).invoke(queue,items,11,done);
            Object state=((kotlinx.coroutines.flow.StateFlow<?>)queueClass.getMethod("\u037f").invoke(queue)).getValue();
            var plan=NativePlaylistOrder.read(state);
            if(name.equals("Circle")) check(plan.next().equals(List.of("saltneri:fixture:0","saltneri:fixture:1","saltneri:fixture:2")));
            if(name.equals("CircleEnd") || name.equals("RepeatOne")) check(plan.next().isEmpty());
        }
        check(firsts.size()>1);
        System.out.println("PASS: "+checks+" real Salt native queue mode preservation/random first index/effective next-three order checks; no audio or settings writes");
    }
}
