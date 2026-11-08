package io.github.yghfv.saltneri.plugin.host;

import java.util.concurrent.atomic.*;

public final class PlaybackStartGateContractTest {
    private static int checks;
    private static void check(boolean value) {checks++;if(!value)throw new AssertionError("Start gate "+checks);}
    public static void main(String[] args) {
        var clock=new AtomicLong();var allowed=new AtomicBoolean(true);
        var gate=new PlaybackStartGate(clock::get);
        check(!gate.loaded("song",allowed::get));
        gate.arm("song");check(!gate.loaded("previous-song",allowed::get));
        check(gate.loaded("song",allowed::get));check(!gate.loaded("song",allowed::get));
        gate.arm("song");gate.cancel();check(!gate.loaded("song",allowed::get));
        gate.arm("song");gate.arm("other");check(!gate.loaded("song",allowed::get));check(gate.loaded("other",allowed::get));
        gate.arm("song");allowed.set(false);check(!gate.loaded("song",allowed::get));
        allowed.set(true);check(!gate.loaded("song",allowed::get));
        gate.arm("song");clock.addAndGet(30_000_000_001L);check(!gate.loaded("song",allowed::get));
        clock.set(0);check(!gate.loaded("song",allowed::get));
        System.out.println("PASS: "+checks+" load-complete one-shot/identity/cancel/revoke/timeout checks; no audio engine");
    }
}
