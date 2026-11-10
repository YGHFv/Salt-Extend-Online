package io.github.yghfv.saltneri.plugin.host;

import io.github.yghfv.saltneri.plugin.transport.LoopbackRangeServer;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import io.github.yghfv.saltneri.plugin.applemusic.AppleMusicPcmSource;
import com.sun.jna.*;
import java.time.Instant;

/** Explicit no-sound BASS decode of the same progressive PCM used for MusicKit. */
public final class AppleMusicBassProbe {
    public interface Bass extends Library {
        boolean BASS_Init(int device,int rate,int flags,Pointer window,Pointer cls);
        int BASS_ChannelGetData(int handle,Pointer buffer,int length);
        boolean BASS_ChannelSetPosition(int handle,long position,int mode);
        boolean BASS_StreamFree(int handle);
        boolean BASS_Free();
    }
    public static void verify(io.github.yghfv.saltneri.plugin.applemusic.AppleMusicPcmSource.Decoder decoder,String library) throws Exception {
        Bass bass=Native.load(library,Bass.class);
        if(!bass.BASS_Init(0,48000,0,null,null))throw new AssertionError("BASS no-sound initialization failed");
        var streams=new ScopedNativeStreams(AppleMusicBassProbe.class.getClassLoader());
        try(var transport=new LoopbackRangeServer();var pcm=new Memory(65536)) {
            var source=new AppleMusicPcmSource(16000,()->true,()->decoder);
            var uri=transport.register(MediaLease.appleMusic(source,"apple:fixture",Instant.now().plusSeconds(60),()->true));
            transport.onRevoke(streams::revoke);
            Object raw=streams.create(uri,0x200000);kotlin.ResultKt.throwOnFailure(raw);int handle=(int)raw;
            if(handle==0)throw new AssertionError("Native WAV stream creation failed");
            try {
                for(long position:new long[]{0,7L*48000*4,48000*4}) {
                    if(!bass.BASS_ChannelSetPosition(handle,position,0))throw new AssertionError("BASS seek failed");
                    int count=bass.BASS_ChannelGetData(handle,pcm,65536);
                    if(count<=0)throw new AssertionError("Native PCM decode failed");
                    boolean audible=false;for(byte value:pcm.getByteArray(0,count))if(value!=0){audible=true;break;}
                    if(!audible)throw new AssertionError("Native decoder produced silence");
                }
                if(!bass.BASS_ChannelSetPosition(handle,15L*48000*4,0))throw new AssertionError("BASS tail seek failed");
                long tail=0;int count;
                while((count=bass.BASS_ChannelGetData(handle,pcm,65536))>0)tail+=count;
                if(tail==0 || count!=-1)throw new AssertionError("Progressive browser PCM did not reach EOF");
                transport.revoke(uri);
                System.out.println("PASS: actual BASS decoding of progressive browser PCM, non-silence, random seek, complete EOF and release");
            } finally {if(!bass.BASS_StreamFree(handle))throw new AssertionError("BASS stream free failed");}
        } finally {streams.close();bass.BASS_Free();}
    }
}
