package io.github.yghfv.saltneri.plugin.transport;

import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class RangeDiskCacheContractTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if(!ok) throw new AssertionError("Range cache "+checks); }
    public static void main(String[] args) throws Exception {
        Object owner=new Object(),other=new Object(); var a=new ByteRange(0,3); var b=new ByteRange(4,7); var c=new ByteRange(8,11);
        byte[] bytes={1,2,3,4}; Path directory;
        try(var cache=new RangeDiskCache()) {
            check(cache.limitBytes()==2L*1024*1024*1024 && cache.usedBytes()==0); cache.limit(8);
            var field=RangeDiskCache.class.getDeclaredField("directory"); field.setAccessible(true); check(field.get(cache)==null);
            cache.put(owner,a,bytes,cache.revision(),()->true); directory=(Path)field.get(cache);
            check(cache.usedBytes()==4 && Arrays.equals(cache.get(owner,a),bytes));
            check(cache.get(other,a)==null && cache.get(owner,b)==null);
            check(Arrays.equals(cache.get(owner,new ByteRange(1,2)),new byte[]{2,3}));
            check(cache.get(other,new ByteRange(1,2))==null && cache.get(owner,new ByteRange(1,4))==null);
            cache.put(owner,b,bytes,cache.revision(),()->true); cache.get(owner,a);
            cache.put(owner,c,bytes,cache.revision(),()->true);
            check(cache.usedBytes()==8 && cache.get(owner,b)==null && cache.get(owner,a)!=null);
            try(var files=Files.list(directory)) { check(files.mapToLong(p->{try{return Files.size(p);}catch(Exception e){throw new RuntimeException(e);}}).sum()<=8); }
            cache.limit(4); check(cache.usedBytes()==4); cache.clear(); check(cache.usedBytes()==0);
            long previous=cache.revision(); cache.clear(); cache.put(owner,a,bytes,previous,()->true); check(cache.usedBytes()==0);
            cache.put(owner,a,bytes,cache.revision(),()->false); check(cache.usedBytes()==0);
            cache.put(owner,a,new byte[5],cache.revision(),()->true); check(cache.usedBytes()==0);
            cache.limit(8); cache.put(owner,a,bytes,cache.revision(),()->true); cache.put(other,a,bytes,cache.revision(),()->true);
            cache.revoke(owner); check(cache.usedBytes()==4 && cache.get(owner,a)==null && cache.get(other,a)!=null);
            cache.limit(0); check(cache.usedBytes()==0); cache.put(owner,a,bytes,cache.revision(),()->true); check(cache.usedBytes()==0);
            cache.limit(32);
            try(var workers=Executors.newFixedThreadPool(4)) {
                List<Future<?>> pending=new ArrayList<>();
                for(int i=0;i<40;i++) { int offset=i*4; pending.add(workers.submit(()->cache.put(owner,new ByteRange(offset,offset+3),bytes,cache.revision(),()->true))); }
                for(var future:pending) future.get(3,TimeUnit.SECONDS);
            }
            check(cache.usedBytes()<=32);
        }
        check(!Files.exists(directory));
        indexedRanges();
        readAhead();
        var count=new AtomicInteger(); Clock clock=Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"),ZoneOffset.UTC);
        try(var server=new LoopbackRangeServer(clock,uri->{count.incrementAndGet();return new TransportContractTest.FakeConnection(uri,100);})) {
            server.cacheLimit(20);
            var lease=new MediaLease(MediaLease.Platform.BILIBILI,URI.create("https://test.bilivideo.com/audio"),Map.of(),"owner",clock.instant().plusSeconds(60),100);
            URI local=server.register(lease);
            check(TransportContractTest.call(local,"GET","Range: bytes=0-9\r\n","").code()==206);
            long deadline=System.nanoTime()+3_000_000_000L;
            while(server.cacheBytes()!=10 && System.nanoTime()<deadline) Thread.sleep(5);
            check(server.cacheBytes()==10 && count.get()==1);
            check(TransportContractTest.call(local,"GET","Range: bytes=0-9\r\n","").body()[9]==9 && count.get()==1);
            server.clearCache(); check(server.cacheBytes()==0);
            check(TransportContractTest.call(local,"GET","Range: bytes=0-9\r\n","").code()==206 && count.get()==2);
            server.cacheLimit(0); check(server.cacheBytes()==0);
            check(TransportContractTest.call(local,"GET","Range: bytes=0-9\r\n","").code()==206 && count.get()==3);
            server.revoke(local); check(TransportContractTest.call(local,"GET","Range: bytes=0-9\r\n","").code()==404 && count.get()==3);
        }
        System.out.println("PASS: "+checks+" disk quota/LRU/clear/concurrency/lease isolation and real loopback cache-hit checks; no external traffic");
    }
    private static void await(java.util.function.BooleanSupplier done) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!done.getAsBoolean() && System.nanoTime()<end) Thread.sleep(10);
        check(done.getAsBoolean());
    }
    private static void readAhead() throws Exception {
        int chunk=512*1024,total=chunk*12;Clock clock=Clock.systemUTC();
        var ranges=new CopyOnWriteArrayList<String>(); var calls=new AtomicInteger();
        try(var server=new LoopbackRangeServer(clock,uri->{
            calls.incrementAndGet();
            return new TransportContractTest.FakeConnection(uri,total) {
                @Override public java.io.InputStream getInputStream() {
                    ranges.add(getRequestProperty("Range"));return super.getInputStream();
                }
            };
        })) {
            server.cacheLimit(chunk*16);
            URI local=server.register(new MediaLease(MediaLease.Platform.NETEASE,URI.create("https://m7.music.126.net/ahead"),
                    Map.of(),"fixture",clock.instant().plusSeconds(60),total));
            try(var scope=new io.github.yghfv.saltneri.plugin.net.RequestScope(()->true)) {server.prefetch(local,scope);}
            check(calls.get()==1 && server.cacheBytes()==chunk);
            Thread.sleep(150);check(calls.get()==1); // Prefix prefetch does not crawl the song.
            check(TransportContractTest.call(local,"GET","Range: bytes=0-"+(chunk-1)+"\r\n","").body().length==chunk);
            await(()->server.cacheBytes()==chunk*3);check(calls.get()==3);
            Thread.sleep(150);check(calls.get()==3 && ranges.size()==3);
            check(TransportContractTest.call(local,"GET","Range: bytes="+chunk+"-"+(2*chunk-1)+"\r\n","").body()[17]==17);
            await(()->server.cacheBytes()==chunk*4);check(calls.get()==4);
            // Seek replaces the old window and warms only its two neighbors.
            check(TransportContractTest.call(local,"GET","Range: bytes="+(7*chunk)+"-"+(8*chunk-1)+"\r\n","").code()==206);
            await(()->server.cacheBytes()==chunk*7);check(calls.get()==7);
            check(!ranges.contains("bytes="+(4*chunk)+"-"+(5*chunk-1)));
            server.cacheLimit(0);int before=calls.get();
            check(TransportContractTest.call(local,"GET","Range: bytes=0-"+(chunk-1)+"\r\n","").code()==206);
            Thread.sleep(150);check(calls.get()==before+1 && server.cacheBytes()==0);
            server.revoke(local);
            check(TransportContractTest.call(local,"GET","Range: bytes=0-"+(chunk-1)+"\r\n","").code()==404);
        }
        for(int mode=0;mode<3;mode++) cancelledReadAhead(clock,chunk,total,mode);
        coalescedReadAhead(clock,chunk);
    }
    private static void cancelledReadAhead(Clock clock,int chunk,int total,int mode) throws Exception {
        var reading=new CountDownLatch(1);var cancelled=new CountDownLatch(1);var calls=new AtomicInteger();
        try(var server=new LoopbackRangeServer(clock,uri->{
            calls.incrementAndGet();
            return new TransportContractTest.FakeConnection(uri,total) {
                @Override public java.io.InputStream getInputStream() {
                    if(range().first()!=chunk) return super.getInputStream();
                    return new java.io.InputStream() {
                        public int read() throws java.io.IOException {
                            reading.countDown();
                            try {if(!cancelled.await(4,TimeUnit.SECONDS))throw new java.io.IOException("Fixture stalled");}
                            catch(InterruptedException stopped){Thread.currentThread().interrupt();}
                            throw new java.io.IOException("Fixture revoked");
                        }
                    };
                }
                @Override public void disconnect(){super.disconnect();if(range().first()==chunk)cancelled.countDown();}
            };
        })) {
            server.cacheLimit(chunk*16);
            URI local=server.register(new MediaLease(MediaLease.Platform.NETEASE,URI.create("https://m7.music.126.net/cancel"),
                    Map.of(),"fixture",clock.instant().plusSeconds(60),total));
            check(TransportContractTest.call(local,"GET","Range: bytes=0-"+(chunk-1)+"\r\n","").code()==206);
            check(reading.await(3,TimeUnit.SECONDS));
            if(mode==0) server.revoke(local);
            else if(mode==1) server.clearCache();
            else {
                check(TransportContractTest.call(local,"GET","Range: bytes="+(7*chunk)+"-"+(8*chunk-1)+"\r\n","").code()==206);
                await(()->server.cacheBytes()==4L*chunk);
            }
            check(cancelled.await(1,TimeUnit.SECONDS));
            Thread.sleep(100);check(server.cacheBytes()==(mode==2?4L*chunk:0) && calls.get()==(mode==2?5:2));
        }
    }
    private static void coalescedReadAhead(Clock clock,int chunk) throws Exception {
        var reading=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        try(var server=new LoopbackRangeServer(clock,uri->{
            calls.incrementAndGet();
            return new TransportContractTest.FakeConnection(uri,3L*chunk) {
                @Override public java.io.InputStream getInputStream() {
                    if(range().first()==chunk) {
                        reading.countDown();
                        try {check(release.await(2,TimeUnit.SECONDS));}
                        catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new AssertionError(stopped);}
                    }
                    return super.getInputStream();
                }
            };
        });var worker=Executors.newSingleThreadExecutor()) {
            server.cacheLimit(8L*chunk);
            URI local=server.register(new MediaLease(MediaLease.Platform.NETEASE,URI.create("https://m7.music.126.net/coalesce"),
                    Map.of(),"fixture",clock.instant().plusSeconds(60),3L*chunk));
            check(TransportContractTest.call(local,"GET","Range: bytes=0-"+(chunk-1)+"\r\n","").code()==206);
            check(reading.await(2,TimeUnit.SECONDS));
            var pending=worker.submit(()->TransportContractTest.call(local,"GET","Range: bytes="+chunk+"-"+(2*chunk-1)+"\r\n",""));
            Thread.sleep(100);check(calls.get()==2);
            release.countDown();
            check(pending.get(3,TimeUnit.SECONDS).body().length==chunk);
            await(()->server.cacheBytes()==3L*chunk);check(calls.get()==3);
        } finally {release.countDown();}
    }
    private static void indexedRanges() {
        // Equal values still represent different live leases; never share or revoke their bytes.
        Object owner=new String("lease"),other=new String("lease");
        try(var cache=new RangeDiskCache()) {
            cache.limit(32);
            cache.put(owner,new ByteRange(0,7),new byte[]{1,2,3,4,5,6,7,8},cache.revision(),()->true);
            check(cache.get(other,new ByteRange(0,7))==null);
            cache.put(other,new ByteRange(0,3),new byte[]{9,9,9,9},cache.revision(),()->true);
            cache.put(owner,new ByteRange(4,5),new byte[]{5,6},cache.revision(),()->true);
            check(Arrays.equals(cache.get(owner,new ByteRange(4,7)),new byte[]{5,6,7,8}));
            cache.put(owner,new ByteRange(0,1),new byte[]{1,2},cache.revision(),()->true);
            check(Arrays.equals(cache.get(owner,new ByteRange(1,6)),new byte[]{2,3,4,5,6,7}));
            cache.revoke(owner);
            check(cache.usedBytes()==4 && cache.get(owner,new ByteRange(1,2))==null);
            check(Arrays.equals(cache.get(other,new ByteRange(0,3)),new byte[]{9,9,9,9}));
            cache.clear();
            check(cache.get(other,new ByteRange(1,2))==null && cache.usedBytes()==0);
            cache.put(owner,new ByteRange(100,103),new byte[]{1,2,3,4},cache.revision(),()->true);
            cache.put(other,new ByteRange(200,203),new byte[]{9,9,9,9},cache.revision(),()->true);
            check(cache.get(owner,new ByteRange(101,102))!=null);
            cache.limit(4);
            check(cache.get(other,new ByteRange(201,202))==null && cache.get(owner,new ByteRange(101,102))!=null);
        }
    }
}
