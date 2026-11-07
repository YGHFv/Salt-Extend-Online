package io.github.yghfv.saltneri.plugin.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline socket fixtures: no platform, host queue, native engine or TLS exceptions. */
public final class PinnedHttpsContractTest {
    private static int checks;
    private static final URI TARGET = URI.create("https://api.bilibili.com/test?x=1");
    private static final InetAddress PUBLIC;
    static { try { PUBLIC = InetAddress.getByAddress(new byte[]{8, 8, 8, 8}); } catch (IOException e) { throw new AssertionError(e); } }
    @FunctionalInterface interface Action { void run() throws Exception; }
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
    private static void fails(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected rejection"); }
        catch (IOException | IllegalArgumentException | CancellationException expected) { checks++; }
    }
    private static final class FixtureSocket extends Socket {
        final InputStream input;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        SocketAddress address;
        volatile boolean closed;
        FixtureSocket(String response) { input = new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII)); }
        @Override public void connect(SocketAddress target, int timeout) { address = target; }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void close() { closed = true; }
    }
    private static PinnedHttpsConnection fixture(FixtureSocket socket) throws IOException {
        return new PinnedHttpsConnection(TARGET, host -> new InetAddress[]{PUBLIC}, new PinnedHttpsConnection.Sockets() {
            @Override public Socket create() { return socket; }
            @Override public Socket secure(Socket raw, String host) { check(host.equals("api.bilibili.com")); return raw; }
        });
    }
    private static PinnedHttpsConnection fixture(String response) throws IOException { return fixture(new FixtureSocket(response)); }
    public static void main(String[] args) throws Exception {
        var context=PinnedHttpsConnection.tlsContext();
        check(context==PinnedHttpsConnection.tlsContext());
        check(context.getClientSessionContext().getSessionCacheSize()==64);
        check(context.getClientSessionContext().getSessionTimeout()==300);
        for (String address : List.of("0.0.0.0", "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1",
                "169.254.169.254", "100.64.1.1", "100.127.0.1", "198.18.1.1", "192.0.2.1", "198.51.100.1",
                "203.0.113.1", "192.0.0.8", "192.88.99.1", "224.0.0.1", "255.255.255.255", "::1", "::",
                "fc00::1", "fe80::1", "ff02::1", "::ffff:127.0.0.1", "64:ff9b::a00:1", "2002:a00:1::1", "2001::1", "2001:db8::1", "3fff::1")) {
            check(!PinnedHttpsConnection.isPublic(InetAddress.getByName(address)));
        }
        for (String address : List.of("8.8.8.8", "1.1.1.1", "100.63.0.1", "100.128.0.1", "2606:4700:4700::1111", "2001:4860:4860::8888")) {
            check(PinnedHttpsConnection.isPublic(InetAddress.getByName(address)));
        }
        check(PinnedHttpsConnection.parameters("api.bilibili.com").getEndpointIdentificationAlgorithm().equals("HTTPS"));
        check(PinnedHttpsConnection.parameters("api.bilibili.com").getServerNames().size() == 1);
        for (String uri : List.of("http://api.bilibili.com/", "https://x:8443/", "https://u:p@x/", "https://x/#f", "https://[::1]/")) {
            fails(() -> new PinnedHttpsConnection(URI.create(uri)));
        }
        FixtureSocket socket = new FixtureSocket("HTTP/1.1 206 Partial Content\r\nContent-Length: 1\r\nContent-Range: bytes 0-0/99\r\n\r\nxignored");
        AtomicInteger dns = new AtomicInteger();
        PinnedHttpsConnection pinned = new PinnedHttpsConnection(TARGET, host -> { dns.incrementAndGet(); return new InetAddress[]{PUBLIC}; },
                new PinnedHttpsConnection.Sockets() {
                    public Socket create() { return socket; }
                    public Socket secure(Socket raw, String host) { return raw; }
                });
        CookieHandler oldCookies = CookieHandler.getDefault(); Authenticator oldAuth = Authenticator.getDefault();
        ProxySelector oldProxy = ProxySelector.getDefault();
        try {
            CookieHandler.setDefault(new CookieManager() { @Override public java.util.Map<String, List<String>> get(URI u, java.util.Map<String,List<String>> h) { throw new AssertionError("Global cookies consulted"); } });
            Authenticator.setDefault(new Authenticator() { @Override protected java.net.PasswordAuthentication getPasswordAuthentication() { throw new AssertionError("Global auth consulted"); } });
            ProxySelector.setDefault(new ProxySelector() {
                public List<Proxy> select(URI u) { throw new AssertionError("Global proxy consulted"); }
                public void connectFailed(URI u, SocketAddress a, IOException e) { throw new AssertionError(); }
            });
            pinned.setRequestProperty("Range", "bytes=0-0");
            pinned.setRequestProperty("Accept-Encoding", "identity");
            check(pinned.getResponseCode() == 206);
            check(pinned.getHeaderField("Content-Range").equals("bytes 0-0/99"));
            check(pinned.getContentLengthLong() == 1);
            check(pinned.getInputStream().read() == 'x'); check(pinned.getInputStream().read() == -1);
            check(dns.get() == 1); check(((InetSocketAddress)socket.address).getAddress().equals(PUBLIC));
            check(!pinned.usingProxy());
            String request = socket.output.toString(StandardCharsets.US_ASCII);
            check(request.contains("Host: api.bilibili.com\r\n")); check(!request.toLowerCase().contains("cookie"));
            check(request.startsWith("GET /test?x=1 HTTP/1.1\r\n"));
        } finally { pinned.disconnect(); CookieHandler.setDefault(oldCookies); Authenticator.setDefault(oldAuth); ProxySelector.setDefault(oldProxy); }
        check(socket.closed);
        PinnedHttpsConnection badHeader = fixture("HTTP/1.1 200 OK\r\n\r\n");
        for (String key : List.of("Host", "Connection", "Content-Length", "Transfer-Encoding", "Expect", "x\r\ny")) fails(() -> badHeader.setRequestProperty(key, "x"));
        fails(() -> badHeader.setRequestProperty("x", "y\r\nz"));
        fails(() -> badHeader.setRequestProperty("x", "x".repeat(8193)));
        badHeader.setRequestMethod("POST"); fails(badHeader::connect);
        for (String headers : List.of("Content-Length: 1\r\nContent-Length: 1\r\n", "Content-Length: -1\r\n",
                "Content-Length: 9223372036854775808\r\n", "Transfer-Encoding: gzip\r\n", "Transfer-Encoding: chunked\r\nContent-Length: 1\r\n",
                "Content-Range: bytes 0-0/1\r\nContent-Range: bytes 0-0/1\r\n", "Content-Encoding: identity\r\nContent-Encoding: identity\r\n",
                "Location: /x\r\nLocation: /y\r\n", "Bad Header: x\r\n", " folded: x\r\n")) {
            fails(() -> fixture("HTTP/1.1 200 OK\r\n" + headers + "\r\nx").connect());
        }
        for (String response : List.of("HTTP/2 200 OK\r\n\r\n", "HTTP/1.1 200 OK\n\n", "HTTP/1.1 101 Switching\r\n\r\n",
                "HTTP/1.1 200 OK\r\nX: " + "x".repeat(16385) + "\r\n\r\n")) fails(() -> fixture(response).connect());
        check(fixture("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n").getResponseCode() == 200);
        PinnedHttpsConnection chunks = fixture("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2;ext=x\r\nab\r\n1\r\nc\r\n0\r\nX-Info: ok\r\n\r\n");
        check(new String(chunks.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).equals("abc"));
        for (String body : List.of("z\r\n", "1\r\n", "1\r\nx!!0\r\n\r\n", "0\r\nContent-Length: 0\r\n\r\n", "0\r\n")) {
            fails(() -> fixture("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" + body).getInputStream().readAllBytes());
        }
        fails(() -> fixture("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nx").getInputStream().readAllBytes());
        PinnedHttpsConnection head = fixture("HTTP/1.1 200 OK\r\nContent-Length: 999\r\n\r\n");
        head.setRequestMethod("HEAD"); check(head.getContentLengthLong() == 999); check(head.getInputStream().read() == -1);
        check(fixture("HTTP/1.1 204 No Content\r\n\r\n").getInputStream().read() == -1);
        check(fixture("HTTP/1.1 200 OK\r\nX_Bili_Trace_Id: fixture\r\nContent-Length: 0\r\n\r\n").getHeaderField("x_bili_trace_id").equals("fixture"));
        PinnedHttpsConnection cookies = fixture("HTTP/1.1 200 OK\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\nContent-Length: 0\r\n\r\n");
        check(cookies.getHeaderFields().get("set-cookie").equals(List.of("a=1", "b=2")));
        fails(() -> fixture("HTTP/1.1 200 OK\r\nx-refresh-token: a\r\nx-refresh-token: b\r\n\r\n").connect());
        FixtureSocket postSocket = new FixtureSocket("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        PinnedHttpsConnection post = fixture(postSocket); post.loginForm("params=x&encSecKey=y".getBytes(StandardCharsets.US_ASCII)); post.connect();
        check(postSocket.output.toString(StandardCharsets.US_ASCII).startsWith("POST /test?x=1 HTTP/1.1\r\n"));
        check(postSocket.output.toString(StandardCharsets.US_ASCII).contains("Content-Length: 20\r\n"));
        fails(() -> PinnedHttpsConnection.neteaseFormPost(URI.create("https://evil/login"), new byte[]{42}));
        fails(() -> PinnedHttpsConnection.neteaseFormPost(URI.create("https://music.163.com/arbitrary"), new byte[]{42}));
        PinnedHttpsConnection songPost = PinnedHttpsConnection.neteaseSongPost(URI.create("https://interface.music.163.com/eapi/song/enhance/player/url/v1"), "params=ABCDEF".getBytes(StandardCharsets.US_ASCII));
        check(songPost.getRequestMethod().equals("POST")); songPost.disconnect();
        for (String url : List.of("https://music.163.com/eapi/song/enhance/player/url/v1", "https://interface.music.163.com/eapi/arbitrary",
                "http://interface.music.163.com/eapi/song/enhance/player/url/v1", "https://interface.music.163.com/eapi/song/enhance/player/url/v1?x=1",
                "https://interface.music.163.com:444/eapi/song/enhance/player/url/v1", "https://u@interface.music.163.com/eapi/song/enhance/player/url/v1")) {
            fails(() -> PinnedHttpsConnection.neteaseSongPost(URI.create(url), new byte[]{42}));
        }
        fails(() -> PinnedHttpsConnection.neteaseSongPost(URI.create("https://interface.music.163.com/eapi/song/enhance/player/url/v1"), new byte[32769]));
        fails(() -> PinnedHttpsConnection.neteaseSongPost(URI.create("https://interface.music.163.com/eapi/song/enhance/player/url/v1"), new byte[]{10}));
        AtomicInteger created = new AtomicInteger();
        PinnedHttpsConnection.Sockets noConnect = new PinnedHttpsConnection.Sockets() {
            public Socket create() { created.incrementAndGet(); return new FixtureSocket(""); }
            public Socket secure(Socket raw, String host) { return raw; }
        };
        fails(() -> new PinnedHttpsConnection(TARGET, h -> new InetAddress[]{PUBLIC, InetAddress.getLoopbackAddress()}, noConnect).connect());
        check(created.get() == 0);
        PinnedHttpsConnection stopped = fixture(""); stopped.disconnect(); fails(stopped::connect);
        CountDownLatch connecting = new CountDownLatch(1), released = new CountDownLatch(1);
        Socket blocking = new Socket() {
            public void connect(SocketAddress a, int t) throws IOException {
                connecting.countDown(); try { released.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new IOException("Closed");
            }
            public void close() { released.countDown(); }
        };
        PinnedHttpsConnection cancellable = new PinnedHttpsConnection(TARGET, h -> new InetAddress[]{PUBLIC}, new PinnedHttpsConnection.Sockets() {
            public Socket create() { return blocking; } public Socket secure(Socket raw, String host) { return raw; }
        });
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread worker = new Thread(() -> { try { cancellable.connect(); } catch (IOException expected) { cancelled.set(true); } });
        worker.start(); check(connecting.await(2, TimeUnit.SECONDS)); cancellable.disconnect(); worker.join(2000); check(!worker.isAlive() && cancelled.get());
        AtomicBoolean allowed = new AtomicBoolean(true);
        try (RequestScope scope = new RequestScope(allowed::get)) {
            FixtureSocket pending = new FixtureSocket("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
            PinnedHttpsConnection request = fixture(pending); scope.attach(request); request.connect(); allowed.set(false);
            fails(scope::check);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!pending.closed && System.nanoTime() < until) Thread.sleep(10);
            check(pending.closed);
        }
        RequestScope closed = new RequestScope(() -> true); closed.close();
        PinnedHttpsConnection orphan = fixture(""); fails(() -> closed.attach(orphan)); fails(orphan::connect);
        try (RequestScope deadline = new RequestScope(() -> true, 50)) {
            FixtureSocket expiring = new FixtureSocket("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
            PinnedHttpsConnection request = fixture(expiring); deadline.attach(request); request.connect();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!expiring.closed && System.nanoTime() < until) Thread.sleep(10);
            check(expiring.closed); fails(deadline::check);
        }
        Thread.currentThread().interrupt();
        try (RequestScope scope = new RequestScope(() -> true)) { fails(scope::check); }
        finally { Thread.interrupted(); }
        System.out.println("PASS: " + checks + " pinned HTTPS / request scope checks (offline)");
    }
}
