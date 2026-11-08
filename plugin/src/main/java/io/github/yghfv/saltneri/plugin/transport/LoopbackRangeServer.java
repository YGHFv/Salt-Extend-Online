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
    private final RangeDiskCache cache=new RangeDiskCache();
    public void cacheLimit(long bytes) { cache.limit(bytes); cancelReadAhead(); }
    public long cacheBytes() { return cache.usedBytes(); }
    public void clearCache() { cache.clear(); cancelReadAhead(); }
    @FunctionalInterface
    interface ConnectionOpener { HttpURLConnection open(URI uri) throws IOException; }

    private static final int TIMEOUT_MILLIS = 8000;
    private static final long HOST_CHUNK_BYTES = 512L * 1024;
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
        final java.util.concurrent.locks.ReentrantLock downloadLock = new java.util.concurrent.locks.ReentrantLock(true);
        Entry(MediaLease lease) { this.lease = lease; fixture = null; fixtureExpiry = null; }
        Entry(byte[] fixture, Instant expiresAt) { lease = null; this.fixture = fixture; fixtureExpiry = expiresAt; }
        long length() { return lease == null ? fixture.length : lease.length(); }
        Instant expiresAt() { return lease == null ? fixtureExpiry : lease.expiresAt(); }
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
            if (closed) { connection.disconnect(); throw new IOException("Request cancelled"); }
            upstream = connection;
        }
        synchronized void detach(HttpURLConnection connection) {
            if (upstream == connection) { upstream = null; connection.disconnect(); }
        }
        synchronized void check() throws IOException { if(closed) throw new IOException("Request cancelled"); }
        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if(socket!=null) try { socket.close(); } catch (IOException ignored) { }
            HttpURLConnection connection = upstream;
            upstream = null;
            if (connection != null) connection.disconnect();
        }
    }

    public LoopbackRangeServer() throws IOException {
        this(() -> true);
    }

    public LoopbackRangeServer(BooleanSupplier authorized) throws IOException {
        this(Clock.systemUTC(), LoopbackRangeServer::openHttps, authorized);
        cache.limit(RangeDiskCache.DEFAULT_LIMIT);
    }

    LoopbackRangeServer(Clock clock, ConnectionOpener opener) throws IOException {
        this(clock, opener, () -> true);
    }

    LoopbackRangeServer(Clock clock, ConnectionOpener opener, BooleanSupplier authorized) throws IOException {
        // Injected transport fixtures opt in explicitly when testing the disk cache.
        cache.limit(0);
        this.clock = Objects.requireNonNull(clock);
        this.opener = Objects.requireNonNull(opener);
        this.authorized = Objects.requireNonNull(authorized);
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
    /** Warm only a small verified prefix through the same private range/cache path, never the audio device. */
    public void prefetch(URI uri,io.github.yghfv.saltneri.plugin.net.RequestScope request) throws IOException {
        if(cache.limitBytes()==0) return;
        if(!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort()!=listener.getLocalPort()
                || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                || uri.getRawPath()==null || !uri.getRawPath().matches("/media/[0-9a-f]{64}")) throw new IOException("Foreign prefetch");
        Entry entry=leases.get(uri.getRawPath().substring(7)); if(entry==null) return;
        // Salt 1.19.3 reads aligned 512 KiB chunks; a shorter prefix would miss its very first read.
        ensureValid(entry); request.check(); long count=Math.min(entry.length(),512*1024);
        HttpURLConnection connection=(HttpURLConnection)uri.toURL().openConnection(java.net.Proxy.NO_PROXY);
        request.attach(connection);
        try {
            connection.setConnectTimeout(2000); connection.setReadTimeout(2000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Range","bytes=0-"+(count-1)); connection.setUseCaches(false);
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

    private void revoke(String token, Entry entry) {
        if (!entry.revoked.compareAndSet(false, true)) return;
        try { onRevoke.accept(URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/media/" + token)); }
        finally {
            leases.remove(token, entry);
            requests.forEach(request -> { if (request.entry == entry) request.close(); });
            cache.revoke(entry);
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
                    deadline = deadlines.schedule(request::close, 12, TimeUnit.SECONDS);
                    ScheduledFuture<?> timeout = deadline;
                    workers.execute(() -> {
                        try { serve(request); }
                        finally { request.close(); requests.remove(request); timeout.cancel(false); }
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

    private void fetch(Request request, Entry entry, ByteRange range, boolean foreground) throws IOException {
        ReadAheadPlan previous=readAheadPlan;
        if(foreground && previous!=null && (previous.entry()!=entry || range.first()<previous.first()
                || range.first()>=previous.first()+READ_AHEAD_CHUNKS*HOST_CHUNK_BYTES)) {
            readAheadPlan=null;
            requests.forEach(r->{if(r.speculative!=null) r.close();});
        }
        long revision=cache.revision();
        ensureValid(entry); request.check();
        byte[] data=cache.get(entry,range);
        // Coalesce host/prefix/read-ahead requests for the same lease; no duplicate CDN downloads.
        // Cached foreground bytes must not wait behind another slow speculative chunk.
        if(data==null) {
            entry.downloadLock.lock();
            try {
                ensureValid(entry); request.check();
                data=cache.get(entry,range);
                if(data==null) {
                    data=download(request,entry,range);
                    cache.put(entry,range,data,revision,()->valid(entry));
                }
            } finally { entry.downloadLock.unlock(); }
        }
        ensureValid(entry); request.check();
        // Publish only complete verified ranges; the host buffers the same aligned chunk.
        reply(request,206,Map.of("Content-Length",Long.toString(range.length()),"Content-Range",range.contentHeader(entry.length()),"Accept-Ranges","bytes"));
        request.socket.getOutputStream().write(data); request.socket.getOutputStream().flush();
        if(foreground) scheduleReadAhead(entry,range);
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
        if(cache.limitBytes()<HOST_CHUNK_BYTES || range.first()%HOST_CHUNK_BYTES!=0
                || range.length()!=Math.min(HOST_CHUNK_BYTES,entry.length()-range.first())) return;
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
                && cache.limitBytes()>=HOST_CHUNK_BYTES && valid(plan.entry());
    }

    private boolean aheadRangeValid(ReadAheadPlan original,ByteRange range) {
        ReadAheadPlan current=readAheadPlan;
        return current!=null && range!=null && current.entry()==original.entry()
                && current.cacheRevision()==original.cacheRevision() && cache.revision()==current.cacheRevision()
                && range.first()>=current.first() && range.first()<current.first()+READ_AHEAD_CHUNKS*HOST_CHUNK_BYTES
                && cache.limitBytes()>=HOST_CHUNK_BYTES && valid(current.entry());
    }

    private void readAhead() {
        ReadAheadPlan plan=readAheadPlan;
        try {
            if(plan==null) return;
            Entry entry=plan.entry();
            for(int i=0;i<READ_AHEAD_CHUNKS && aheadValid(plan);i++) {
                long first=plan.first()+i*HOST_CHUNK_BYTES;
                if(first>=entry.length()) break;
                ByteRange range=new ByteRange(first,Math.min(entry.length(),first+HOST_CHUNK_BYTES)-1);
                Request request=new Request(null); request.entry=entry; request.speculative=plan; request.speculativeRange=range;
                requests.add(request);
                ScheduledFuture<?> deadline=deadlines.schedule(request::close,2500,TimeUnit.MILLISECONDS);
                try {
                    entry.downloadLock.lock();
                    try {
                        if(!aheadValid(plan)) return;
                        request.check();
                        if(cache.get(entry,range)==null) {
                            // Speculation is single-attempt and uses its own worker, not host HTTP slots.
                            byte[] data=readRange(request,entry,range);
                            request.check();
                            // Advancing one chunk retains overlapping in-flight work instead of downloading twice.
                            cache.put(entry,range,data,plan.cacheRevision(),()->aheadRangeValid(plan,range));
                        }
                    } finally { entry.downloadLock.unlock(); }
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
                entry.lease.headers().forEach(connection::setRequestProperty);
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Range", range.requestHeader());
                int code = connection.getResponseCode();
                ensureValid(entry);
                if (List.of(301, 302, 303, 307, 308).contains(code)) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new InvalidRange();
                    URI target = uri.resolve(location);
                    entry.lease.requireTarget(target);
                    // Credentials are never forwarded across hosts, even to another platform CDN.
                    if (!uri.getHost().equalsIgnoreCase(target.getHost())) throw new InvalidRange();
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

    private static HttpURLConnection openHttps(URI uri) throws IOException {
        return new PinnedHttpsConnection(uri);
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
        cache.close();
    }
}
