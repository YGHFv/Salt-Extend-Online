package io.github.yghfv.saltneri.plugin.host;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** A single explicit start intent; load completion cannot override a later user action. */
public final class PlaybackStartGate {
    private final LongSupplier clock;
    private String expected;
    private long deadline;
    public PlaybackStartGate() {this(System::nanoTime);}
    public PlaybackStartGate(LongSupplier clock) {this.clock=clock;}
    public synchronized void arm(String identity) {expected=identity;deadline=clock.getAsLong()+30_000_000_000L;}
    public synchronized void cancel() {expected=null;}
    public synchronized boolean loaded(String identity,BooleanSupplier authorized) {
        if(expected==null)return false;
        if(clock.getAsLong()>deadline || !authorized.getAsBoolean()) {cancel();return false;}
        if(!expected.equals(identity))return false;
        cancel();return true;
    }
}
