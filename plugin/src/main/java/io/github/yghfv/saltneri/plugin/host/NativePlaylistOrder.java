package io.github.yghfv.saltneri.plugin.host;

import java.util.*;
import java.util.function.IntUnaryOperator;

/** Read Salt's effective queue snapshot; never shuffle a second list behind the native controls. */
public final class NativePlaylistOrder {
    public record Plan(String current,String mode,List<String> next) {
        public static final Plan EMPTY=new Plan("","",List.of());
        public Plan { next=List.copyOf(next); if(next.size()>3) throw new IllegalArgumentException("Prefetch window too large"); }
    }
    private NativePlaylistOrder() { }
    public static int firstIndex(String mode,int size,IntUnaryOperator random) {
        if(size<1) throw new IllegalArgumentException("Empty playlist");
        return mode.equals("Random")?Objects.checkIndex(random.applyAsInt(size),size):0;
    }
    public static String mode(Object state) throws ReflectiveOperationException {
        return ((Enum<?>)state.getClass().getMethod("\u037f").invoke(state)).name();
    }
    public static Plan read(Object state) throws ReflectiveOperationException {
        String mode=mode(state); boolean random=mode.equals("Random");
        List<?> order=(List<?>)state.getClass().getMethod(random?"\u052a":"\u0528").invoke(state);
        int index=(int)state.getClass().getMethod(random?"\u052b":"\u0529").invoke(state);
        if(index<0 || index>=order.size()) return Plan.EMPTY;
        String current=id(order.get(index)); List<String> next=new ArrayList<>();
        if(!mode.equals("RepeatOne")) for(int offset=1;offset<order.size() && next.size()<3;offset++) {
            int at=index+offset;
            // Random reshuffles at its end: don't predict an order Salt hasn't generated yet.
            if(at>=order.size() && !mode.equals("Circle")) break;
            next.add(id(order.get(at%order.size())));
        }
        return new Plan(current,mode,next);
    }
    private static String id(Object entry) throws ReflectiveOperationException {
        Object item=entry.getClass().getMethod("\u0528").invoke(entry);
        return (String)item.getClass().getMethod("\u037f").invoke(item);
    }
}
