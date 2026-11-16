package io.github.yghfv.saltneri.plugin.host;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.*;

/** Retry native installation after host startup settles; never install without a current permission. */
public final class DeferredNativeActivation implements AutoCloseable {
    public enum Phase { PENDING, WAITING_PERMISSION, INSTALLING, READY, FAILED, CLOSED }
    public enum Failure { NONE, PERMISSION, REFLECTION, LINKAGE, IO, RUNTIME }
    public record Snapshot(Phase phase, Failure failure, int attempts) {}
    @FunctionalInterface public interface Installer { void install() throws Exception; }
    private final Object lifecycle;
    private final BooleanSupplier active, permitted, installed;
    private final Installer install;
    private final Consumer<Snapshot> changed;
    private final ScheduledExecutorService worker;
    private final int limit;
    private final long timeoutNanos;
    private final long started=System.nanoTime();
    private volatile Snapshot snapshot = new Snapshot(Phase.PENDING, Failure.NONE, 0);
    private boolean closed;
    private int errors;

    public DeferredNativeActivation(Object lifecycle, BooleanSupplier active, BooleanSupplier permitted,
                                    BooleanSupplier installed, Installer install, Consumer<Snapshot> changed) {
        this(lifecycle,active,permitted,installed,install,changed,600,100);
    }
    DeferredNativeActivation(Object lifecycle, BooleanSupplier active, BooleanSupplier permitted,
                             BooleanSupplier installed, Installer install, Consumer<Snapshot> changed,
                             int limit, long delayMillis) {
        if(limit<1 || delayMillis<1)throw new IllegalArgumentException("Invalid activation retry budget");
        this.lifecycle=Objects.requireNonNull(lifecycle); this.active=Objects.requireNonNull(active);
        this.permitted=Objects.requireNonNull(permitted); this.installed=Objects.requireNonNull(installed);
        this.install=Objects.requireNonNull(install); this.changed=Objects.requireNonNull(changed); this.limit=limit;
        timeoutNanos=TimeUnit.MILLISECONDS.toNanos(Math.multiplyExact((long)limit,delayMillis));
        worker=Executors.newSingleThreadScheduledExecutor(task->{
            Thread thread=new Thread(task,"salt-extend-native-activation");thread.setDaemon(true);return thread;
        });
        worker.scheduleWithFixedDelay(this::run,0,delayMillis,TimeUnit.MILLISECONDS);
    }
    private void publish(Phase phase,Failure failure,int attempts) {
        snapshot=new Snapshot(phase,failure,attempts);changed.accept(snapshot);
    }
    private boolean exhausted(int attempts) {return attempts>=limit || System.nanoTime()-started>=timeoutNanos;}
    private void run() {
        synchronized(lifecycle) {
            if(closed)return;
            int attempts=snapshot.attempts()+1;
            try {
                if(!active.getAsBoolean()) {close();return;}
                if(!permitted.getAsBoolean()) {
                    if(exhausted(attempts))worker.shutdown();
                    publish(Phase.WAITING_PERMISSION,Failure.PERMISSION,attempts);
                    return;
                }
                if(!installed.getAsBoolean()) {
                    publish(Phase.INSTALLING,Failure.NONE,attempts);
                    install.install();
                }
                if(!active.getAsBoolean()) {close();return;}
                if(!permitted.getAsBoolean()) {
                    if(exhausted(attempts))worker.shutdown();
                    publish(Phase.WAITING_PERMISSION,Failure.PERMISSION,attempts);
                    return;
                }
                if(!installed.getAsBoolean())throw new IllegalStateException("Native UI not installed");
                publish(Phase.READY,Failure.NONE,attempts);worker.shutdown();
            } catch(Exception | LinkageError failure) {
                Failure category=failure instanceof SecurityException?Failure.PERMISSION:
                        failure instanceof ReflectiveOperationException?Failure.REFLECTION:
                        failure instanceof LinkageError?Failure.LINKAGE:
                        failure instanceof java.io.IOException?Failure.IO:Failure.RUNTIME;
                boolean exhausted=exhausted(attempts) || ++errors>=3;
                publish(exhausted?Phase.FAILED:Phase.PENDING,category,attempts);
                if(exhausted)worker.shutdown();
            }
        }
    }
    public Snapshot snapshot() {return snapshot;}
    public boolean finished() {return worker.isShutdown();}
    @Override public void close() {
        synchronized(lifecycle) {
            if(closed)return;
            closed=true;worker.shutdownNow();
            snapshot=new Snapshot(Phase.CLOSED,snapshot.failure(),snapshot.attempts());
        }
    }
}
