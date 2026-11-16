package io.github.yghfv.saltneri.plugin.transport;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.Target;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.video.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Virtual long media, a controlled slow edge, and real player-facing loopback sockets. */
public final class VideoLoadingContractTest {
    private static final long MIB=1024L*1024, TOTAL=1024*MIB+31;
    private static int checks;
    private static void check(boolean value,String message) {
        checks++;if(!value)throw new AssertionError(message);
    }
    private static MediaLease lease(String id) {
        return new MediaLease(MediaLease.Platform.BILIBILI,URI.create("https://test.bilivideo.com/"+id+".mp4"),
                Map.of(),"anonymous-video",Instant.now().plusSeconds(60),TOTAL);
    }
    private static byte value(long position) {return (byte)(position*31);}
    private static final class Edge extends HttpURLConnection {
        final List<ByteRange> reads;
        final int delay;
        Edge(URI uri,List<ByteRange> reads,int delay) throws IOException {
            super(uri.toURL());this.reads=reads;this.delay=delay;
        }
        ByteRange range() {return ByteRange.parse(getRequestProperty("Range"),TOTAL);}
        public void connect() {}
        public void disconnect() {}
        public boolean usingProxy() {return false;}
        public int getResponseCode() {reads.add(range());return 206;}
        public long getContentLengthLong() {return range().length();}
        public String getHeaderField(String name) {return name.equals("Content-Range")?range().contentHeader(TOTAL):null;}
        public InputStream getInputStream() {
            ByteRange range=range();
            return new InputStream() {
                long offset;
                public int read() {return offset<range.length()?value(range.first()+offset++)&255:-1;}
                public int read(byte[] bytes,int first,int count) throws IOException {
                    if(offset>=range.length())return -1;
                    if(delay>0)try {Thread.sleep(delay);}catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IOException(e);}
                    int size=(int)Math.min(Math.min(count,64*1024),range.length()-offset);
                    for(int i=0;i<size;i++)bytes[first+i]=value(range.first()+offset+i);
                    offset+=size;return size;
                }
            };
        }
    }
    private static byte[] read(URI uri,String range) throws Exception {
        var connection=(HttpURLConnection)uri.toURL().openConnection(Proxy.NO_PROXY);
        connection.setRequestProperty("Range",range);connection.setReadTimeout(5000);
        try {
            check(connection.getResponseCode()==206,"Valid video range rejected");
            try(var input=connection.getInputStream()) {return input.readAllBytes();}
        } finally {connection.disconnect();}
    }
    private static void contents(byte[] bytes,long first,int size) {
        check(bytes.length==size,"Wrong video range length");
        for(int i=0;i<size;i++)if(bytes[i]!=value(first+i))throw new AssertionError("Video seek changed bytes");
        checks++;
    }
    private static void coldProbes() throws Exception {
        var reads=new CopyOnWriteArrayList<ByteRange>();
        try(var server=new LoopbackRangeServer(Clock.systemUTC(),uri->new Edge(uri,reads,0),()->true,true)) {
            server.cacheLimit(16*MIB);
            URI local=server.register(lease("probe"));
            contents(read(local,"bytes=0-31"),0,32);
            check(reads.equals(List.of(new ByteRange(0,31))),"Cold MP4 header expanded to a full cache block");
            contents(read(local,"bytes=-17"),TOTAL-17,17);
            check(reads.getLast().equals(new ByteRange(TOTAL-17,TOTAL-1)),"Cold MP4 index tail overdownloaded");
            long first=2*MIB-7;
            contents(read(local,"bytes="+first+"-"+(first+30)),first,31);
            check(reads.get(reads.size()-2).equals(new ByteRange(first,2*MIB-1))
                    && reads.getLast().equals(new ByteRange(2*MIB,first+30)),"Short seek fetched unrelated bytes");
            int before=reads.size();
            contents(read(local,"bytes=0-31"),0,32);
            check(reads.size()==before,"A repeated header missed its cache");
            try(var request=new RequestScope(()->true)) {server.prefetchVideoStart(local,request);}
            before=reads.size();
            contents(read(local,"bytes=1000-1031"),1000,32);
            check(reads.size()==before,"A warm prefix did not satisfy a smaller player request");
            server.clearCache();check(server.cacheBytes()==0,"Cache clear retained video data");
            server.cacheLimit(0);reads.clear();
            contents(read(local,"bytes=-17"),TOTAL-17,17);
            check(reads.getFirst().length()==17,"Uncached tail regressed");
            server.revoke(local);check(!server.isAvailable(local),"Revocation retained long-video lease");
        }
    }
    private static void slowStartup() throws Exception {
        var reads=new CopyOnWriteArrayList<ByteRange>();
        try(var server=new LoopbackRangeServer(Clock.systemUTC(),uri->new Edge(uri,reads,20),()->true,true)) {
            server.cacheLimit(16*MIB);
            URI local=server.register(lease("slow"));
            var connection=(HttpURLConnection)local.toURL().openConnection(Proxy.NO_PROXY);
            // An open read represents a long video; consume only its first byte.
            connection.setRequestProperty("Range","bytes=0-");connection.setReadTimeout(5000);
            long started=System.nanoTime(),elapsed;
            try {
                check(connection.getResponseCode()==206,"Long video open request rejected");
                check(connection.getInputStream().read()==(value(0)&255),"Wrong first video byte");
                elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                check(reads.getFirst().equals(new ByteRange(0,512*1024-1)),
                        "Cache-enabled first byte waited for a 2 MiB body");
                check(elapsed<2000,"Slow edge did not deliver the bounded first read");
            } finally {connection.disconnect();}
            System.out.println("Slow edge: first byte after "+elapsed+"ms; cold startup fetch 512 KiB (previously 2 MiB)");
        }
    }
    private static void fairPrecache() throws Exception {
        var reads=new CopyOnWriteArrayList<ByteRange>();
        var calls=new CopyOnWriteArrayList<Target>();
        var targets=List.of("a","b","c","d","e").stream().map(id->new Target(AccountPlatform.BILIBILI,id)).toList();
        var enabled=new AtomicBoolean();
        try(var server=new LoopbackRangeServer(Clock.systemUTC(),uri->new Edge(uri,reads,0),()->true,true)) {
            // Each virtual video fits the cache: the previous whole-file prefetch would monopolize it.
            server.cacheLimit(2L*1024*MIB);
            try(var cache=new VideoPrecache(()->targets,()->VideoResolution.P720,p->null,()->true,enabled::get,
                    server,(target,resolution,account,request)->{request.check();calls.add(target);return lease(target.id());})) {
                enabled.set(true);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while((calls.size()<4 || server.cacheBytes()!=4*(2*MIB+31)) && System.nanoTime()<deadline)Thread.sleep(20);
                check(calls.equals(targets.subList(0,4)),"Long current video blocked next-three resolution");
                check(server.cacheBytes()==4*(2*MIB+31),"Precache downloaded whole long videos");
                check(reads.size()==8 && reads.stream().allMatch(r->r.first()==0 || r.last()==TOTAL-1),
                        "Speculation downloaded video bodies beyond startup/index ranges");
                try(var request=new RequestScope(()->true)) {
                    URI current=cache.resolve(targets.getFirst(),request);
                    check(!server.isFullyCached(current),"Long video was fully downloaded in background");
                    check(calls.size()==4,"Playback failed to reuse warm long-video lease");
                }
                enabled.set(false);cache.cancel();server.clearCache();
                check(server.cacheBytes()==0,"Cancel/clear retained speculative data");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        coldProbes();slowStartup();fairPrecache();
        System.out.println("PASS: "+checks+" long-video startup, exact header/tail, seek, cache and fair precache checks");
    }
}
