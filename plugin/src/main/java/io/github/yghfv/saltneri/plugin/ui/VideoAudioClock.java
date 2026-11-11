package io.github.yghfv.saltneri.plugin.ui;

/** Estimates time between Salt progress samples without repeatedly seeking to a stale sample. */
final class VideoAudioClock {
    record Sample(double seconds,boolean discontinuity) {}
    private long position=-1,observed;
    private boolean playing;
    private double rate=1;
    Sample sample(long milliseconds,boolean running,double speed,long now) {
        milliseconds=Math.max(0,milliseconds);
        boolean jump=false;
        if(position<0) {position=milliseconds;observed=now;}
        else if(milliseconds!=position) {
            double expected=position+(playing?Math.min(1.2,(now-observed)/1e9)*rate*1000:0);
            jump=milliseconds<position-150 || Math.abs(milliseconds-expected)>750;
            position=milliseconds;observed=now;
        } else if(playing!=running || rate!=speed) {
            // Pause/speed changes start a fresh estimate at the current host sample.
            observed=now;
        }
        playing=running;rate=speed;
        return new Sample(position/1000d+(running?Math.min(1.2,(now-observed)/1e9)*speed:0),jump);
    }
}
