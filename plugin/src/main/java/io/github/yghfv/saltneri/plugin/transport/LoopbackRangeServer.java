package io.github.yghfv.saltneri.plugin.transport;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.yghfv.saltneri.plugin.net.PinnedHttpsConnection;

/** Private pull-only transport using java.base, available in Salt's trimmed runtime. */
public final class LoopbackRangeServer implements AutoCloseable {
    public Instant now() { return clock.instant(); }
    private final java.util.concurrent.ExecutorService cacheIo=java.util.concurrent.Executors.newSingleThreadExecutor(
            r->daemon(r,"salt-cache-maintenance"));
    private boolean deferCacheIo(Runnable operation) {
        // Keep this transport runnable with java.base in trimmed hosts/offline tests.
        try {
            Class<?> queue=Class.forName("java.awt.EventQueue");
            if(Boolean.TRUE.equals(queue.getMethod("isDispatchThread").invoke(null))) {
                try {cacheIo.execute(operation);}catch(RejectedExecutionException stopped) { }
                return true;
            }
        } catch(ReflectiveOperationException | LinkageError ignored) { }
        return false;
    }
    private final RangeDiskCache cache=new RangeDiskCache();
    public void cacheLimit(long bytes) {
        if(bytes<0 || bytes>8L*1024*1024*1024)throw new IllegalArgumentException("Invalid cache budget");
        if(deferCacheIo(()->cacheLimit(bytes)))return;
        // Cache has its own monitor. Do not hold the transport monitor across disk IO:
        // registration/revoke/stop on AWT must stay independent of this background wait.
        if(closed.get() || cache.limitBytes()==bytes) return;
        cache.limit(bytes); cancelReadAhead();
    }
    public long cacheBytes() { return cache.usedBytes(); }
    public long cacheLimitBytes() { return cache.limitBytes(); }
    public long cacheRevision() {return cache.revision();}
    public void clearCache() {
        if(deferCacheIo(this::clearCache))return;
        cache.clear(); cancelReadAhead();
    }
    /** Native WAV open probes the tail. Restore a continuous producer before handing it to playback. */
    public void prepareForPlayback(URI uri) throws IOException {
        if(!"http".equals(uri.getScheme()) || uri.getPort()!=listener.getLocalPort() || !"127.0.0.1".equals(uri.getHost())
                || uri.getRawQuery()!=null || uri.getRawFragment()!=null || uri.getRawUserInfo()!=null
                || uri.getRawPath()==null || !uri.getRawPath().matches("/media/[0-9a-f]{64}"))throw new IOException("Foreign media");
        Entry entry=leases.get(uri.getPath().substring("/media/".length()));
        if(entry==null || entry.lease==null || entry.lease.decoded()==null)return;
        for(long first=HOST_CHUNK_BYTES;first<Math.min(entry.length(),3*HOST_CHUNK_BYTES);first+=HOST_CHUNK_BYTES) {
            ensureValid(entry);
            entry.lease.decoded().read(new ByteRange(first,Math.min(entry.length(),first+HOST_CHUNK_BYTES)-1),()->valid(entry));
        }
    }
    @FunctionalInterface
    interface ConnectionOpener { HttpURLConnection open(URI uri) throws IOException; }

    private static final int TIMEOUT_MILLIS = 8000;
    private static final long HOST_CHUNK_BYTES = 512L * 1024;
    private static final long VIDEO_CHUNK_BYTES = 2L * 1024 * 1024;
    private static final int READ_AHEAD_CHUNKS = 2;
    private final ServerSocket listener;
    private final Thread acceptThread;
    private final ThreadPoolExecutor workers;
    private final ThreadPoolExecutor readAheadWorker;
    private final AtomicBoolean readAheadRunning = new AtomicBoolean();
    private volatile ReadAheadPlan readAheadPlan;
    private record ReadAheadPlan(Entry entry, long first, long cacheRevision) { }
    private final ScheduledExecutorService deadlines;
    private final Clock clock;
    private final ConnectionOpener opener;
    private final BooleanSupplier authorized;
    private final boolean streamingVideo;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> leases = new ConcurrentHashMap<>();
    private final Set<Request> requests = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile java.util.function.Consumer<URI> onRevoke = uri -> { };

    /** Fast notification only: observers must not wait for a native callback to drain. */
    public void onRevoke(java.util.function.Consumer<URI> observer) { onRevoke = Objects.requireNonNull(observer); }

    private static final class Entry {
        final MediaLease lease;
        final byte[] fixture;
        final Instant fixtureExpiry;
        final AtomicBoolean revoked = new AtomicBoolean();
        final Map<ByteRange,RangeDownload> downloads = new ConcurrentHashMap<>();
        Entry(MediaLease lease) { this.lease = lease; fixture = null; fixtureExpiry = null; }
        Entry(byte[] fixture, Instant expiresAt) { lease = null; this.fixture = fixture; fixtureExpiry = expiresAt; }
        long length() { return lease == null ? fixture.length : lease.length(); }
        Instant expiresAt() { return lease == null ? fixtureExpiry : lease.expiresAt(); }
    }

    private static final class RangeDownload {
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock(true);
        int users; // Updated only inside downloads.compute.
    }

    private static RangeDownload retainDownload(Entry entry,ByteRange range) {
        return entry.downloads.compute(range,(key,current)->{
            if(current==null) current=new RangeDownload();
            current.users++;
            return current;
        });
    }

    private static void releaseDownload(Entry entry,ByteRange range,RangeDownload download,boolean locked) {
        if(locked) download.lock.unlock();
        entry.downloads.compute(range,(key,current)->{
            if(current!=download) throw new IllegalStateException("Download ownership changed");
            return --current.users==0?null:current;
        });
    }

    private static boolean lockDownload(Request request,RangeDownload download,boolean foreground) throws IOException {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(200);
        try {
            do {
                request.check();
                if(download.lock.tryLock(50,TimeUnit.MILLISECONDS)) return true;
                // A stalled speculative copy must not hold up the audio callback indefinitely.
                // Fast copies still coalesce; after this grace period the host can fetch its own copy.
                if(foreground && System.nanoTime()>=deadline) return false;
            } while(true);
        } catch(InterruptedException stopped) {
            Thread.currentThread().interrupt(); throw new IOException("Download interrupted",stopped);
        }
    }

    private static final class Request implements AutoCloseable {
        final Socket socket;
        volatile Entry entry;
        volatile HttpURLConnection upstream;
        volatile ReadAheadPlan speculative;
        volatile ByteRange speculativeRange;
        boolean replied;
        private boolean closed;
        Request(Socket socket) { this.socket = socket; }
        synchronized void attach(HttpURLConnection connection) throws IOException {
            if (closed) { io.github.yghfv.saltneri.plugin.net.RequestScope.cancelConnection(connection); throw new IOException("Request cancelled"); }
            upstream = connection;
        }
        void detach(HttpURLConnection connection) {
            boolean release;
            synchronized(this) {release=upstream==connection;if(release)upstream=null;}
            if(release)connection.disconnect();
        }
        synchronized void check() throws IOException { if(closed) throw new IOException("Request cancelled"); }
        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if(socket!=null) try { socket.close(); } catch (IOException ignored) { }
            HttpURLConnection connection = upstream;
            upstream = null;
            if (connection != null) io.github.yghfv.saltneri.plugin.net.RequestScope.cancelConnection(connection);
        }
    }

    public LoopbackRangeServer() throws IOException {
        this(() -> true);
    }

    public LoopbackRangeServer(BooleanSupplier authorized) throws IOException {
        this(Clock.systemUTC(), LoopbackRangeServer::openMedia, authorized, false);
        cache.limit(RangeDiskCache.DEFAULT_LIMIT);
    }

    /** MPV uses open-ended HTTP reads. Keep each upstream request bounded and video off the audio cache. */
    public static LoopbackRangeServer video(BooleanSupplier authorized) throws IOException {
        var server=new LoopbackRangeServer(Clock.systemUTC(), LoopbackRangeServer::openMedia, authorized, true);
        server.cacheLimit(RangeDiskCache.DEFAULT_LIMIT);
        return server;
    }
    private long cacheChunkBytes() { return streamingVideo ? VIDEO_CHUNK_BYTES : HOST_CHUNK_BYTES; }
    LoopbackRangeServer(Clock clock, ConnectionOpener opener) throws IOException {
        this(clock, opener, () -> true);
    }

    LoopbackRangeServer(Clock clock, ConnectionOpener opener, BooleanSupplier authorized) throws IOException {
        this(clock,opener,authorized,false);
    }

    LoopbackRangeServer(Clock clock, ConnectionOpener opener, BooleanSupplier authorized, boolean streamingVideo) throws IOException {
        // Injected transport fixtures opt in explicitly when testing the disk cache.
        cache.limit(0);
        this.clock = Objects.requireNonNull(clock);
        this.opener = Objects.requireNonNull(opener);
        this.authorized = Objects.requireNonNull(authorized);
        this.streamingVideo = streamingVideo;
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16);
        workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
                runnable -> daemon(runnable, "salt-neri-range-worker"));
        readAheadWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
                runnable -> daemon(runnable, "salt-neri-range-ahead"));
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1,
                runnable -> daemon(runnable, "salt-neri-range-deadline"));
        timer.setRemoveOnCancelPolicy(true);
        deadlines = timer;
        deadlines.scheduleWithFixedDelay(this::expire, 1, 1, TimeUnit.SECONDS);
        acceptThread = daemon(this::accept, "salt-neri-range-listener");
        acceptThread.start();
    }

    /** Caller supplies a resolved platform lease, not an arbitrary URL from an HTTP request. */
    public synchronized URI register(MediaLease lease) {
        if (closed.get()) throw new IllegalStateException("Transport stopped");
        if (!permitted()) throw new SecurityException("Transport permission revoked");
        if(!lease.isActive())throw new SecurityException("Media source account revoked");
        Duration lifetime = Duration.between(clock.instant(), lease.expiresAt());
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("Lease must expire within 30 minutes");
        }
        expire();
        if (leases.size() >= 64) throw new IllegalStateException("Too many active media leases");
        return registerEntry(new Entry(lease));
    }

    /** Explicit diagnostic only. This does not weaken the remote MediaLease platform allowlist. */
    public synchronized URI registerDiagnosticTone() {
        if (closed.get()) throw new IllegalStateException("Transport stopped");
        if (!permitted()) throw new SecurityException("Transport permission revoked");
        expire();
        if (leases.size() >= 64) throw new IllegalStateException("Too many active media leases");
        return registerEntry(new Entry(DiagnosticTone.wave(), clock.instant().plusSeconds(60)));
    }

    private URI registerEntry(Entry entry) {
        byte[] bytes = new byte[32];
        String token;
        do { random.nextBytes(bytes); token = HexFormat.of().formatHex(bytes); }
        while (leases.putIfAbsent(token, entry) != null);
        return URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/media/" + token);
    }

    public synchronized void revokeAccount(String accountScope) {
        leases.forEach((token, entry) -> {
            if (entry.lease != null && entry.lease.accountScope().equals(accountScope)) revoke(token, entry);
        });
    }
    /** Cache a complete track in native-aligned blocks, bounded by the configured disk budget. */
    public void prefetch(URI uri,io.github.yghfv.saltneri.plugin.net.RequestScope request) throws IOException {
        if(cache.limitBytes()==0) return;
        if(!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort()!=listener.getLocalPort()
                || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                || uri.getRawPath()==null || !uri.getRawPath().matches("/media/[0-9a-f]{64}")) throw new IOException("Foreign prefetch");
        Entry entry=leases.get(uri.getRawPath().substring(7)); if(entry==null) return;
        if(entry.lease!=null && entry.lease.decoded()!=null)return;
        ensureValid(entry); request.check();
        if(entry.length()>cache.limitBytes()) throw new IOException("Track exceeds audio cache budget");
        long revision=cache.revision();
        long chunk=cacheChunkBytes();
        for(long first=0;first<entry.length();first+=chunk) {
            request.check(); ensureValid(entry);
            if(revision!=cache.revision()) throw new IOException("Audio cache settings changed");
            ByteRange range=new ByteRange(first,Math.min(entry.length()-1,first+chunk-1));
            if(cache.get(entry,range)==null) prefetchRange(uri,range,request);
        }
        for(long first=0;first<entry.length();first+=chunk)
            if(cache.get(entry,new ByteRange(first,Math.min(entry.length()-1,first+chunk-1)))==null)
                throw new IOException("Full track not retained in audio cache");
    }
    /** MP4 headers can live at either end. Warm only two bounded blocks before handing off to mpv. */
    public void prefetchVideoStart(URI uri,io.github.yghfv.saltneri.plugin.net.RequestScope request) throws IOException {
        if(!streamingVideo || cache.limitBytes()<VIDEO_CHUNK_BYTES || !isAvailable(uri))return;
        Entry entry=leases.get(uri.getRawPath().substring(7));
        if(entry==null)return;
        ByteRange prefix=new ByteRange(0,Math.min(entry.length(),VIDEO_CHUNK_BYTES)-1);
        if(!cache.contains(entry,prefix))prefetchRange(uri,prefix,request);
        long first=(entry.length()-1)/VIDEO_CHUNK_BYTES*VIDEO_CHUNK_BYTES;
        ByteRange tail=new ByteRange(first,entry.length()-1);
        if(!cache.contains(entry,tail))prefetchRange(uri,tail,request);
    }
    private void prefetchRange(URI uri,ByteRange range,io.github.yghfv.saltneri.plugin.net.RequestScope request) throws IOException {
        long count=range.length();
        HttpURLConnection connection=(HttpURLConnection)uri.toURL().openConnection(java.net.Proxy.NO_PROXY);
        request.attach(connection);
        try {
            connection.setConnectTimeout(2000); connection.setReadTimeout(streamingVideo?10000:2000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Range",range.requestHeader()); connection.setUseCaches(false);
            connection.setRequestProperty("X-Salt-Prefetch","1");
            if(connection.getResponseCode()!=206 || connection.getContentLengthLong()!=count) throw new IOException("Invalid prefetch range");
            try(InputStream input=connection.getInputStream()) {
                byte[] buffer=new byte[16384]; long remaining=count;
                while(remaining>0) { request.check(); int n=input.read(buffer,0,(int)Math.min(remaining,buffer.length)); if(n<0) throw new IOException("Incomplete prefetch"); remaining-=n; }
            }
            request.check();
        } finally { request.release(connection); }
    }

    public synchronized void revoke(URI localUri) {
        if (!"http".equals(localUri.getScheme()) || !"127.0.0.1".equals(localUri.getHost())
                || localUri.getPort() != listener.getLocalPort() || localUri.getRawUserInfo() != null
                || localUri.getRawQuery() != null || localUri.getRawFragment() != null
                || localUri.getRawPath() == null || !localUri.getRawPath().matches("/media/[0-9a-f]{64}")) return;
        String token = localUri.getRawPath().substring(7);
        Entry entry = leases.get(token);
        if (entry != null) revoke(token, entry);
    }

    public boolean isAvailable(URI uri) {
        if (uri == null || !"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() != listener.getLocalPort() || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawPath() == null
                || !uri.getRawPath().matches("/media/[0-9a-f]{64}")) return false;
        Entry entry = leases.get(uri.getRawPath().substring(7));
        return entry != null && valid(entry);
    }

    public boolean isFullyCached(URI uri) {
        if(!isAvailable(uri)) return false;
        Entry entry=leases.get(uri.getRawPath().substring(7));
        if(entry==null) return false;
        long chunk=cacheChunkBytes();
        for(long first=0;first<entry.length();first+=chunk)
            if(!cache.contains(entry,new ByteRange(first,Math.min(entry.length()-1,first+chunk-1)))) return false;
        return true;
    }

    private void revoke(String token, Entry entry) {
        if (!entry.revoked.compareAndSet(false, true)) return;
        try { onRevoke.accept(URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/media/" + token)); }
        finally {
            leases.remove(token, entry);
            requests.forEach(request -> { if (request.entry == entry) request.close(); });
            if(!deferCacheIo(()->cache.revoke(entry)))cache.revoke(entry);
            if(entry.lease!=null)entry.lease.release();
        }
    }

    private void expire() {
        leases.forEach((token, entry) -> { if (!valid(entry)) revoke(token, entry); });
    }

    private void accept() {
        while (!closed.get()) {
            try {
                Request request = new Request(listener.accept());
                requests.add(request);
                if (closed.get()) { request.close(); requests.remove(request); break; }
                request.socket.setSoTimeout(TIMEOUT_MILLIS);
                ScheduledFuture<?> deadline = null;
                try {
                    if(!streamingVideo)deadline = deadlines.schedule(request::close, 12, TimeUnit.SECONDS);
                    ScheduledFuture<?> timeout = deadline;
                    workers.execute(() -> {
                        try { serve(request); }
                        finally { request.close(); requests.remove(request); if(timeout!=null)timeout.cancel(false); }
                    });
                } catch (RejectedExecutionException failure) {
                    if (deadline != null) deadline.cancel(false);
                    request.close(); requests.remove(request);
                }
            } catch (SocketException failure) {
                if (!closed.get()) close();
            } catch (IOException failure) {
                if (!closed.get()) close();
            }
        }
    }

    private void serve(Request request) {
        try {
            if (!request.socket.getInetAddress().isLoopbackAddress()) { reply(request, 403, Map.of()); return; }
            InputStream input = new BufferedInputStream(request.socket.getInputStream());
            int[] budget = {0};
            String[] start = line(input, budget).split(" ", -1);
            if (start.length != 3 || !start[2].equals("HTTP/1.1")) { reply(request, 400, Map.of()); return; }
            Map<String, String> headers = new HashMap<>();
            String next;
            while (!(next = line(input, budget)).isEmpty()) {
                int colon = next.indexOf(':');
                if (colon <= 0 || headers.size() >= 32) { reply(request, 400, Map.of()); return; }
                String key = next.substring(0, colon).toLowerCase(Locale.ROOT);
                if (!key.matches("[a-z0-9-]+") || headers.putIfAbsent(key, next.substring(colon + 1).trim()) != null) {
                    reply(request, 400, Map.of()); return;
                }
            }
            if (!headers.getOrDefault("host", "").equals("127.0.0.1:" + listener.getLocalPort())
                    || headers.containsKey("origin") || headers.containsKey("transfer-encoding")
                    || headers.containsKey("expect") || !headers.getOrDefault("content-length", "0").equals("0")) {
                reply(request, 403, Map.of()); return;
            }
            if (!start[0].equals("GET") && !start[0].equals("HEAD")) {
                reply(request, 405, Map.of("Allow", "GET, HEAD")); return;
            }
            if (!start[1].matches("/media/[0-9a-f]{64}")) { reply(request, 404, Map.of()); return; }
            Entry entry = leases.get(start[1].substring(7));
            request.entry = entry;
            if (entry == null || !valid(entry)) { reply(request, 404, Map.of()); return; }
            // Only the registered lease supplies upstream credentials; never local HTTP headers.
            long total = entry.length();
            if (start[0].equals("HEAD")) {
                reply(request, 200, Map.of("Content-Length", Long.toString(total), "Accept-Ranges", "bytes")); return;
            }
            if(streamingVideo) {
                streamVideo(request,entry,headers.get("range"),!headers.containsKey("x-salt-prefetch"));return;
            }
            ByteRange range;
            try { range = ByteRange.parse(headers.get("range"), total); }
            catch (IllegalArgumentException failure) {
                reply(request, 416, Map.of("Content-Range", "bytes */" + total)); return;
            }
            if (entry.fixture != null) {
                ensureValid(entry);
                reply(request, 206, Map.of("Content-Length", Long.toString(range.length()),
                        "Content-Range", range.contentHeader(total), "Accept-Ranges", "bytes"));
                ensureValid(entry);
                request.socket.getOutputStream().write(entry.fixture, (int)range.first(), (int)range.length());
            } else fetch(request, entry, range, !headers.containsKey("x-salt-prefetch"));
        } catch (IOException | IllegalArgumentException failure) {
            if (!request.replied) {
                try { reply(request, request.entry == null ? 400 : 502, Map.of()); } catch (IOException ignored) { }
            }
        }
    }

    private void streamVideo(Request request,Entry entry,String header,boolean foreground) throws IOException {
        long total=entry.length(),first=0,last=total-1;
        try {
            if(header!=null) {
                if(header.length()>80 || !header.matches("bytes=[0-9]*-[0-9]*"))throw new IllegalArgumentException();
                String[] parts=header.substring(6).split("-",-1);
                if(parts[0].isEmpty()) {
                    long suffix=Long.parseLong(parts[1]);if(suffix<1)throw new IllegalArgumentException();
                    first=Math.max(0,total-suffix);
                } else {
                    first=Long.parseLong(parts[0]);
                    if(!parts[1].isEmpty())last=Math.min(Long.parseLong(parts[1]),total-1);
                }
                if(first>=total || last<first)throw new IllegalArgumentException();
            }
        } catch(IllegalArgumentException invalid) {
            reply(request,416,Map.of("Content-Range","bytes */"+total));return;
        }
        ensureValid(entry);request.check();
        var headers=new HashMap<String,String>();
        headers.put("Content-Length",Long.toString(last-first+1));headers.put("Accept-Ranges","bytes");
        if(header!=null)headers.put("Content-Range","bytes "+first+"-"+last+"/"+total);
        // Validate the first chunk before accepting the native player's request.
        boolean cached=cache.limitBytes()>=VIDEO_CHUNK_BYTES;
        ByteRange range=videoRange(first,last,total,cached,HOST_CHUNK_BYTES);
        byte[] data=cached?fetchData(request,entry,range,foreground):download(request,entry,range);
        reply(request,header==null?200:206,headers);
        for(;;) {
            ensureValid(entry);request.check();
            int offset=(int)(first-range.first()),count=(int)(Math.min(last,range.last())-first+1);
            request.socket.getOutputStream().write(data,offset,count);request.socket.getOutputStream().flush();
            if(cached && foreground)scheduleReadAhead(entry,range);
            first=range.last()+1;if(first>last)break;
            // Preserve a short startup read; sustained video needs fewer CDN round trips than audio.
            range=videoRange(first,last,total,cached,VIDEO_CHUNK_BYTES);
            data=cached?fetchData(request,entry,range,foreground):download(request,entry,range);
        }
    }
    private static ByteRange videoRange(long first,long last,long total,boolean cached,long maximum) {
        if(!cached)return new ByteRange(first,Math.min(last,first+maximum-1));
        long aligned=first/VIDEO_CHUNK_BYTES*VIDEO_CHUNK_BYTES;
        return new ByteRange(aligned,Math.min(total,aligned+VIDEO_CHUNK_BYTES)-1);
    }

    private void fetch(Request request, Entry entry, ByteRange range, boolean foreground) throws IOException {
        if(entry.lease.decoded()!=null) {
            // Decrypted PCM stays in the bounded live source, never the persistent CDN cache.
            byte[] data=entry.lease.decoded().read(range,()->valid(entry));
            ensureValid(entry);request.check();
            if(data.length!=range.length())throw new IOException("Incomplete decoded audio range");
            reply(request,206,Map.of("Content-Length",Long.toString(data.length),"Content-Range",range.contentHeader(entry.length()),"Accept-Ranges","bytes"));
            request.socket.getOutputStream().write(data);return;
        }
        byte[] data=fetchData(request,entry,range,foreground);
        ensureValid(entry); request.check();
        reply(request,206,Map.of("Content-Length",Long.toString(range.length()),"Content-Range",range.contentHeader(entry.length()),"Accept-Ranges","bytes"));
        request.socket.getOutputStream().write(data); request.socket.getOutputStream().flush();
        if(foreground) scheduleReadAhead(entry,range);
    }

    private byte[] fetchData(Request request,Entry entry,ByteRange range,boolean foreground) throws IOException {
        ReadAheadPlan previous=readAheadPlan;
        if(foreground && previous!=null && (previous.entry()!=entry || range.first()<previous.first()
                || range.first()>=previous.first()+READ_AHEAD_CHUNKS*cacheChunkBytes())) {
            readAheadPlan=null;
            requests.forEach(r->{if(r.speculative!=null) r.close();});
        }
        long revision=cache.revision();
        ensureValid(entry); request.check();
        byte[] data=cache.get(entry,range);
        // Coordinate only identical ranges. A slow future chunk must not block a different host read.
        if(data==null) {
            RangeDownload download=retainDownload(entry,range);
            boolean locked=false;
            try {
                locked=lockDownload(request,download,foreground);
                ensureValid(entry); request.check();
                data=cache.get(entry,range);
                if(data==null) {
                    data=download(request,entry,range);
                    cache.put(entry,range,data,revision,()->valid(entry));
                }
            } finally { releaseDownload(entry,range,download,locked); }
        }
        ensureValid(entry); request.check();
        return data;
    }

    private byte[] download(Request request, Entry entry, ByteRange range) throws IOException {
        byte[] data=null;
        for(int attempt=0;attempt<3;attempt++) {
            ensureValid(entry); request.check();
            try { data=readRange(request,entry,range); break; }
            catch(InvalidRange rejected) { throw rejected; }
            catch(IOException transientFailure) {
                ensureValid(entry); request.check();
                if(attempt==2) throw transientFailure;
            }
        }
        ensureValid(entry); request.check(); return data;
    }

    private void scheduleReadAhead(Entry entry,ByteRange range) {
        long chunk=cacheChunkBytes();
        if(cache.limitBytes()<chunk || range.first()%chunk!=0
                || range.length()!=Math.min(chunk,entry.length()-range.first())) return;
        // Only an actual host read advances the window. Prefix prefetch cannot chain to a whole song.
        ReadAheadPlan plan=new ReadAheadPlan(entry,range.last()+1,cache.revision());
        readAheadPlan=plan;
        requests.forEach(r->{if(r.speculative!=null && !aheadRangeValid(r.speculative,r.speculativeRange)) r.close();});
        startReadAhead();
    }

    private void cancelReadAhead() {
        readAheadPlan=null;
        requests.forEach(r->{if(r.speculative!=null) r.close();});
    }

    private void startReadAhead() {
        if(closed.get() || !readAheadRunning.compareAndSet(false,true)) return;
        try { readAheadWorker.execute(this::readAhead); }
        catch(RejectedExecutionException stopped) { readAheadRunning.set(false); }
    }

    private boolean aheadValid(ReadAheadPlan plan) {
        return readAheadPlan==plan && cache.revision()==plan.cacheRevision()
                && cache.limitBytes()>=cacheChunkBytes() && valid(plan.entry());
    }

    private boolean aheadRangeValid(ReadAheadPlan original,ByteRange range) {
        ReadAheadPlan current=readAheadPlan;
        return current!=null && range!=null && current.entry()==original.entry()
                && current.cacheRevision()==original.cacheRevision() && cache.revision()==current.cacheRevision()
                && range.first()>=current.first() && range.first()<current.first()+READ_AHEAD_CHUNKS*cacheChunkBytes()
                && cache.limitBytes()>=cacheChunkBytes() && valid(current.entry());
    }

    private void readAhead() {
        ReadAheadPlan plan=readAheadPlan;
        try {
            if(plan==null) return;
            Entry entry=plan.entry();
            long chunk=cacheChunkBytes();
            for(int i=0;i<READ_AHEAD_CHUNKS && aheadValid(plan);i++) {
                long first=plan.first()+i*chunk;
                if(first>=entry.length()) break;
                ByteRange range=new ByteRange(first,Math.min(entry.length(),first+chunk)-1);
                Request request=new Request(null); request.entry=entry; request.speculative=plan; request.speculativeRange=range;
                requests.add(request);
                ScheduledFuture<?> deadline=deadlines.schedule(request::close,2500,TimeUnit.MILLISECONDS);
                try {
                    RangeDownload download=retainDownload(entry,range);
                    boolean locked=false;
                    try {
                        locked=lockDownload(request,download,false);
                        if(!aheadValid(plan)) return;
                        request.check();
                        if(cache.get(entry,range)==null) {
                            // Speculation is single-attempt and uses its own worker, not host HTTP slots.
                            byte[] data=readRange(request,entry,range);
                            request.check();
                            // Advancing one chunk retains overlapping in-flight work instead of downloading twice.
                            cache.put(entry,range,data,plan.cacheRevision(),()->aheadRangeValid(plan,range));
                        }
                    } finally { releaseDownload(entry,range,download,locked); }
                } finally { deadline.cancel(false); request.close(); requests.remove(request); }
            }
        } catch(IOException | RuntimeException ignored) { /* A speculative failure never fails playback. */ }
        finally {
            readAheadRunning.set(false);
            if(readAheadPlan!=plan && !closed.get()) startReadAhead();
        }
    }

    private static final class InvalidRange extends IOException {
        InvalidRange() { super("Upstream range rejected"); }
    }
    private byte[] readRange(Request request,Entry entry,ByteRange range) throws IOException {
        URI uri = entry.lease.uri();
        for (int redirect = 0; redirect <= 3; redirect++) {
            ensureValid(entry);
            entry.lease.requireTarget(uri);
            HttpURLConnection connection = opener.open(uri);
            request.attach(connection);
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(request.speculative==null?TIMEOUT_MILLIS:2000);
                connection.setReadTimeout(request.speculative==null?TIMEOUT_MILLIS:2000);
                connection.setUseCaches(false);
                connection.setRequestMethod("GET");
                // No account cookie crosses a CDN redirect, including an approved Bilibili edge.
                boolean originalHost=uri.getHost().equalsIgnoreCase(entry.lease.uri().getHost());
                entry.lease.headers().forEach((name,value)->{
                    if(!name.equalsIgnoreCase("cookie") || originalHost)
                        connection.setRequestProperty(name,value);
                });
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Range", range.requestHeader());
                int code = connection.getResponseCode();
                ensureValid(entry);
                if (List.of(301, 302, 303, 307, 308).contains(code)) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new InvalidRange();
                    URI target = uri.resolve(location);
                    try { MediaLease.requireRedirect(entry.lease.platform(),uri,target); }
                    catch(IllegalArgumentException rejected) { throw new InvalidRange(); }
                    uri = target;
                    continue;
                }
                if(code==500 || code==502 || code==503 || code==504) throw new IOException("Temporary media upstream failure");
                String encoding = connection.getHeaderField("Content-Encoding");
                if (code != 206 || !range.contentHeader(entry.lease.length()).equals(connection.getHeaderField("Content-Range"))
                        || connection.getContentLengthLong() != range.length()
                        || (encoding != null && !encoding.equalsIgnoreCase("identity"))) {
                    throw new InvalidRange();
                }
                try (InputStream body = connection.getInputStream()) {
                    byte[] result=new byte[Math.toIntExact(range.length())]; int offset=0;
                    while (offset < result.length) {
                        ensureValid(entry); request.check();
                        int read = body.read(result,offset,result.length-offset);
                        if (read < 0) throw new IOException("Truncated media range");
                        if (read == 0) continue;
                        ensureValid(entry);
                        offset += read;
                    }
                    // Never read past the declared range or buffer an entire track.
                    return result;
                }
            } finally { request.detach(connection); }
        }
        throw new InvalidRange();
    }

    private boolean valid(Entry entry) {
        return !closed.get() && !entry.revoked.get() && permitted()
                && clock.instant().isBefore(entry.expiresAt()) && (entry.lease==null || entry.lease.isActive());
    }

    private boolean permitted() {
        try { return authorized.getAsBoolean(); }
        catch (RuntimeException failure) { return false; }
    }

    private void ensureValid(Entry entry) throws IOException {
        if (!valid(entry)) throw new IOException("Media lease expired or revoked");
    }

    private static String line(InputStream input, int[] budget) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (;;) {
            int value = input.read();
            if (value < 0 || ++budget[0] > 16 * 1024) throw new IOException("Invalid HTTP header bounds");
            if (value == '\r') {
                if (input.read() != '\n' || ++budget[0] > 16 * 1024) throw new IOException("Invalid HTTP line ending");
                return result.toString(StandardCharsets.US_ASCII);
            }
            if (value < 32 || value >= 127 || result.size() >= 8192) throw new IOException("Invalid HTTP header");
            result.write(value);
        }
    }

    private static void reply(Request request, int code, Map<String, String> headers) throws IOException {
        StringBuilder text = new StringBuilder("HTTP/1.1 " + code + " " + switch (code) {
            case 200 -> "OK"; case 206 -> "Partial Content"; case 400 -> "Bad Request";
            case 403 -> "Forbidden"; case 404 -> "Not Found"; case 405 -> "Method Not Allowed";
            case 416 -> "Range Not Satisfiable"; default -> "Bad Gateway";
        } + "\r\nConnection: close\r\nCache-Control: no-store\r\nContent-Type: application/octet-stream\r\n");
        headers.forEach((key, value) -> text.append(key).append(": ").append(value).append("\r\n"));
        if (!headers.containsKey("Content-Length")) text.append("Content-Length: 0\r\n");
        request.replied = true;
        request.socket.getOutputStream().write(text.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static HttpURLConnection openMedia(URI uri) throws IOException {
        return PinnedHttpsConnection.kugouMedia(uri);
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread;
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        leases.forEach(this::revoke);
        try { listener.close(); } catch (IOException ignored) { }
        requests.forEach(Request::close);
        // Queued workers removed by shutdownNow never run their per-request finally block.
        requests.clear();
        workers.shutdownNow();
        readAheadPlan=null;
        readAheadWorker.shutdownNow();
        deadlines.shutdownNow();
        if(!deferCacheIo(cache::close))cache.close();
        cacheIo.shutdown();
    }
}
