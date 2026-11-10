package io.github.yghfv.saltneri.plugin.applemusic;

import io.github.yghfv.saltneri.plugin.transport.*;
import java.io.IOException;
import java.nio.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Pull-driven, seekable PCM16 WAV. The browser is started only when Salt opens the stream. */
public final class AppleMusicPcmSource implements DecodedAudioSource {
    public static final int RATE=48000, FRAME_BYTES=4, HEADER_BYTES=44;
    public interface Decoder extends AutoCloseable {
        void seek(long frame) throws IOException;
        byte[] pull(int maximumBytes) throws IOException;
        void pause() throws IOException;
        @Override void close();
    }
    @FunctionalInterface public interface Factory {Decoder open() throws IOException;}
    private final Factory factory;
    private final BooleanSupplier account;
    private final long frames;
    private final byte[] header;
    private final LinkedHashMap<Long,byte[]> blocks=new LinkedHashMap<>(32,0.75f,true);
    private Decoder decoder;
    private long cursor=-1;
    private byte[] pending=new byte[0];
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicBoolean closing=new java.util.concurrent.atomic.AtomicBoolean();
    public AppleMusicPcmSource(long durationMillis,BooleanSupplier account,Factory factory) {
        if(durationMillis<1 || durationMillis>7200000)throw new IllegalArgumentException("Invalid Apple audio duration");
        frames=durationMillis*RATE/1000;this.account=account;this.factory=factory;
        header=waveHeader(frames*FRAME_BYTES);
    }
    public static byte[] waveHeader(long bytes) {
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46464952).putInt(Math.toIntExact(36+bytes)).putInt(0x45564157)
            .putInt(0x20746d66).putInt(16).putShort((short)1).putShort((short)2)
            .putInt(RATE).putInt(RATE*FRAME_BYTES).putShort((short)FRAME_BYTES).putShort((short)16)
            .putInt(0x61746164).putInt(Math.toIntExact(bytes)).array();
    }
    public long length() {return HEADER_BYTES+frames*FRAME_BYTES;}
    public synchronized void prepare() throws IOException {
        check(()->true);openDecoder();
    }
    private void openDecoder() throws IOException {
        if(decoder!=null)return;
        Decoder created=factory.open();
        if(closed) {created.close();throw AppleMusicHttp.failure("STREAM_REVOKED");}
        decoder=created;
    }
    private void check(BooleanSupplier active) throws IOException {
        if(closed || !account.getAsBoolean() || !active.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw AppleMusicHttp.failure("STREAM_REVOKED");
    }
    @Override public synchronized byte[] read(ByteRange range,BooleanSupplier active) throws IOException {
        check(active);
        if(range.last()>=length() || range.length()>512*1024)throw AppleMusicHttp.failure("RANGE_BOUNDS");
        byte[] cached=blocks.get(range.first());
        if(cached!=null && cached.length==range.length())return cached.clone();
        byte[] result=new byte[Math.toIntExact(range.length())];int offset=0;
        if(range.first()<HEADER_BYTES) {
            offset=(int)Math.min(range.length(),HEADER_BYTES-range.first());
            System.arraycopy(header,(int)range.first(),result,0,offset);
        }
        if(offset<result.length) {
            long first=range.first()+offset-HEADER_BYTES,frame=first/FRAME_BYTES;
            int skip=(int)(first%FRAME_BYTES);
            openDecoder();
            check(active);
            if(cursor!=first) {decoder.seek(frame);cursor=frame*FRAME_BYTES;pending=new byte[0];}
            else skip=0;
            try {
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(9);
                while(offset<result.length) {
                    check(active);
                    if(System.nanoTime()>=deadline)throw AppleMusicHttp.failure("PCM_TIMEOUT");
                    int wanted=Math.min(65536,((result.length-offset+skip+3)/4)*4);
                    byte[] pcm=pending.length>0?pending:decoder.pull(wanted);
                    pending=new byte[0];
                    if(pcm.length==0) {try{Thread.sleep(15);}catch(InterruptedException cancelled){Thread.currentThread().interrupt();check(active);}continue;}
                    if(pcm.length>65536)throw AppleMusicHttp.failure("PCM_BOUNDS");
                    int n=Math.min(result.length-offset,pcm.length-skip);
                    System.arraycopy(pcm,skip,result,offset,n);offset+=n;
                    int consumed=n+skip;cursor+=consumed;skip=0;
                    if(consumed<pcm.length)pending=Arrays.copyOfRange(pcm,consumed,pcm.length);
                }
            } catch(IOException | RuntimeException failure) {cursor=-1;pending=new byte[0];throw failure;}
            finally {decoder.pause();}
        }
        check(active);
        // At most 16 MiB, in memory only; no persistent decrypted Apple audio.
        if(result.length>=16384) {
            blocks.put(range.first(),result.clone());
            while(blocks.values().stream().mapToLong(b->b.length).sum()>16L*1024*1024)blocks.remove(blocks.keySet().iterator().next());
        }
        return result;
    }
    @Override public void close() {
        if(!closing.compareAndSet(false,true))return;
        closed=true;
        Decoder current=decoder;if(current!=null)current.close();
        synchronized(this) {
            if(decoder!=null && decoder!=current)decoder.close();
            blocks.values().forEach(b->Arrays.fill(b,(byte)0));blocks.clear();Arrays.fill(pending,(byte)0);
        }
    }
}
