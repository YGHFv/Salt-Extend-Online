package io.github.yghfv.saltneri.plugin.host;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.kugou.*;
import io.github.yghfv.saltneri.plugin.library.*;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.transport.*;
import com.sun.jna.*;
import java.net.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

/** Explicit plugin-owned login + real CDN + Salt's real native decoder, with no audible output. */
public final class KugouPlaybackLiveProbe {
    public interface Bass extends Library {
        boolean BASS_Init(int device,int frequency,int flags,Pointer window,Pointer cls);
        int BASS_ChannelGetData(int handle,Pointer buffer,int length);
        boolean BASS_ChannelSetPosition(int handle,long position,int mode);
        long BASS_ChannelGetLength(int handle,int mode);
        double BASS_ChannelBytes2Seconds(int handle,long bytes);
        boolean BASS_StreamFree(int handle);
        boolean BASS_Free();
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=3 || !args[0].equals("--explicit-kugou-playback"))
            throw new IllegalArgumentException("Explicit mode, plugin data directory and Salt BASS library required");
        var account=new EncryptedAccountStore(Path.of(args[1])).load(AccountPlatform.KUGOU);
        Bass bass=Native.load(args[2],Bass.class);
        if(!bass.BASS_Init(0,48000,0,null,null))throw new AssertionError("BASS no-sound initialization failed");
        int verified=0;
        try(var request=new RequestScope(account::isActive,300000);
            var transport=new LoopbackRangeServer();var pcm=new Memory(65536)) {
            var catalog=new KugouCatalog(account,Clock.systemUTC(),new KugouHttp());
            var candidates=new ArrayList<Catalog.Item>();
            for(String keyword:List.of("If I were a Bird","unjust life"))
                candidates.addAll(catalog.load(new Catalog.Query(AccountPlatform.KUGOU,Catalog.View.SEARCH,0,1,0,keyword),request).items());
            var visited=new HashSet<String>();
            for(var item:candidates) {
                if(verified==3)break;
                if(!visited.add(item.target()))continue;
                long start=System.nanoTime();
                KugouClient.Resolved audio;
                try {audio=new KugouClient(account).resolve(item.target(),"128",account.accountScope(),request);}
                catch(java.io.IOException failure) {
                    String stage=failure.getMessage();
                    System.out.println("SKIP: "+(stage!=null && stage.matches("KUGOU/[A-Z0-9_]{1,64}")?stage:failure.getClass().getSimpleName()));
                    continue;
                }
                long resolveMillis=(System.nanoTime()-start)/1000000;
                URI uri=transport.register(audio.lease());
                var streams=new ScopedNativeStreams(KugouPlaybackLiveProbe.class.getClassLoader());
                try {
                    transport.onRevoke(streams::revoke);
                    Object raw=streams.create(uri,0x200000);kotlin.ResultKt.throwOnFailure(raw);
                    int handle=(int)raw;
                    if(handle==0)throw new AssertionError("Native Kugou stream creation failed");
                    try {
                        int count=bass.BASS_ChannelGetData(handle,pcm,65536);
                        requireAudio(count,pcm,bass,handle);
                        long length=bass.BASS_ChannelGetLength(handle,0);
                        if(length<65536 || length>2L*1024*1024*1024)throw new AssertionError("Invalid decoded length");
                        if(verified==0) {
                            long period=(long)(bass.BASS_ChannelBytes2Seconds(handle,65536)*1000000000L);
                            if(period<1000000 || period>1000000000L)throw new AssertionError("Invalid native frame duration");
                            long next=System.nanoTime(),worst=0;
                            for(int frame=0;frame<40;frame++) {
                                long before=System.nanoTime();
                                requireAudio(bass.BASS_ChannelGetData(handle,pcm,65536),pcm,bass,handle);
                                worst=Math.max(worst,(System.nanoTime()-before)/1000000);
                                next+=period;
                                long remaining=next-System.nanoTime();
                                if(remaining>0)java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
                            }
                            System.out.println("PASS: paced real Kugou native playback; frames=40; worstReadMs="+worst);
                        }
                        for(long seek:new long[]{length/2/4*4,0}) {
                            if(!bass.BASS_ChannelSetPosition(handle,seek,0))throw new AssertionError("Native Kugou seek failed");
                            requireAudio(bass.BASS_ChannelGetData(handle,pcm,65536),pcm,bass,handle);
                        }
                        if(!bass.BASS_ChannelSetPosition(handle,0,0))throw new AssertionError("Native Kugou full decode reset failed");
                        long decoded=0;
                        while((count=bass.BASS_ChannelGetData(handle,pcm,65536))>0) {
                            request.check();decoded+=count;
                            if(decoded>length+65536)throw new AssertionError("Native decode exceeded full-track bounds");
                        }
                        if(count!=-1 || decoded<length-65536)throw new AssertionError("Native Kugou stream failed before complete EOF");
                        System.out.println("PASS: Kugou track "+(++verified)+"; resolveMs="+resolveMillis+
                                "; mediaBytes="+audio.lease().length()+"; decodedBytes="+decoded+
                                "; native non-silence, mid-track seek, start seek and complete EOF");
                    } finally {if(!bass.BASS_StreamFree(handle))throw new AssertionError("Native stream release failed");}
                } finally {transport.revoke(uri);streams.close();}
            }
            if(verified<2)throw new AssertionError("At least two real Kugou tracks must decode");
        } finally {account.retire();bass.BASS_Free();}
        System.out.println("PASS: real Kugou API/CDN -> loopback -> Salt native decoder; no signed URLs or credentials printed");
    }
    private static void requireAudio(int count,Memory pcm,Bass bass,int handle) {
        // Real music can start with digital silence; inspect bounded consecutive decoded frames.
        for(int frame=0;frame<32;frame++) {
            if(count<=0)throw new AssertionError("Native Kugou PCM decode failed");
            for(byte value:pcm.getByteArray(0,count))if(value!=0)return;
            count=bass.BASS_ChannelGetData(handle,pcm,65536);
        }
        throw new AssertionError("Native Kugou PCM is silent");
    }
}
