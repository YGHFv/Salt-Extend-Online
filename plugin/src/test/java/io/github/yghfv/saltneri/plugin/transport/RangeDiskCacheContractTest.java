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
