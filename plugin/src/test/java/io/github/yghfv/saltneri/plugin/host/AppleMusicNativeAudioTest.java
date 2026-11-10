package io.github.yghfv.saltneri.plugin.host;

import io.github.yghfv.saltneri.plugin.applemusic.AppleMusicPcmSource;
import io.github.yghfv.saltneri.plugin.transport.*;
import java.io.IOException;
import java.nio.*;
import java.time.Instant;
import java.util.concurrent.atomic.*;

/** PCM port -> real localhost ranges -> Salt HttpRangeSource; synthetic samples, no account. */
public final class AppleMusicNativeAudioTest {
    private static void check(boolean value,String reason) {if(!value)throw new AssertionError(reason);}
    public static void main(String[] args) throws Exception {
        AtomicBoolean active=new AtomicBoolean(true);AtomicInteger opens=new AtomicInteger(),closes=new AtomicInteger(),seeks=new AtomicInteger(),pauses=new AtomicInteger();
        AppleMusicPcmSource source=new AppleMusicPcmSource(16000,active::get,()->{
            opens.incrementAndGet();
            return new AppleMusicPcmSource.Decoder() {
                long frame;
                public void seek(long target) {frame=target;seeks.incrementAndGet();}
                public byte[] pull(int maximum) {
                    var data=ByteBuffer.allocate(maximum).order(ByteOrder.LITTLE_ENDIAN);
                    while(data.hasRemaining()) {short sample=(short)(frame++%30000+1);data.putShort(sample).putShort(sample);}
                    return data.array();
                }
                public void pause() {pauses.incrementAndGet();}
                public void close() {closes.incrementAndGet();}
            };
        });
        check(opens.get()==0,"Preparation started browser");
        try(var transport=new LoopbackRangeServer()) {
            var lease=MediaLease.appleMusic(source,"apple:test",Instant.now().plusSeconds(60),active::get);
            var uri=transport.register(lease);
            try(var host=HostRangeReader.open(uri)) {
                check(host.length()==source.length(),"Native duration length mismatch");
                byte[] header=new byte[44];check(host.read(header)==44,"WAV header incomplete");
                check(new String(header,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF"),"Wrong PCM container");
                for(long frame:new long[]{0,48000*7,48000,48000*15}) {
                    check(host.seek(44+frame*4),"Native seek failed");
                    byte[] pcm=new byte[256];check(host.read(pcm)==pcm.length,"Native PCM incomplete");
                    var samples=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
                    for(int i=0;i<64;i++) {
                        short expected=(short)((frame+i)%30000+1);
                        check(samples.getShort()==expected && samples.getShort()==expected,"Wrong stereo PCM after seek");
                    }
                }
                check(opens.get()==1 && seeks.get()>1,"Decoder lifecycle mismatch");
            }
            // Odd byte ranges and pause/resume preserve the exact byte position.
            byte[] a=source.read(new ByteRange(45,107),()->true);
            byte[] b=source.read(new ByteRange(108,172),()->true);
            byte[] both=source.read(new ByteRange(45,172),()->true);
            byte[] joined=new byte[128];System.arraycopy(a,0,joined,0,a.length);System.arraycopy(b,0,joined,a.length,b.length);
            check(java.util.Arrays.equals(joined,both),"Unaligned range lost PCM bytes");
            byte[] oddA=source.read(new ByteRange(45,108),()->true);
            byte[] oddB=source.read(new ByteRange(109,172),()->true);
            System.arraycopy(oddA,0,joined,0,oddA.length);System.arraycopy(oddB,0,joined,oddA.length,oddB.length);
            check(java.util.Arrays.equals(joined,both),"Consecutive unaligned frame skipped bytes");
            check(pauses.get()==0,"Range boundaries paused the continuous PCM producer");
            check(transport.cacheBytes()==0,"Decrypted Apple PCM entered disk cache");
            transport.revoke(uri);
            check(closes.get()==1 && !transport.isAvailable(uri),"Revoke did not close browser");
            try {source.read(new ByteRange(0,43),()->true);throw new AssertionError("Revoked PCM served");}
            catch(IOException expected) {}
        }
        // Apple media can never turn the CDN transport into a localhost/arbitrary URL proxy.
        try {new MediaLease(MediaLease.Platform.APPLEMUSIC,java.net.URI.create("https://music.apple.com/audio"),java.util.Map.of(),"a",Instant.now().plusSeconds(60),100);
            throw new AssertionError("Apple URL lease accepted");}catch(IllegalArgumentException expected) {}
        System.out.println("PASS: Apple PCM through Salt reader, sample identity, stereo, random/unaligned seeks, memory-only cache and revocation");
    }
}
