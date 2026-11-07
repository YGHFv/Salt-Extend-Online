package io.github.yghfv.saltneri.plugin.transport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real loopback sockets, but no external requests, accounts or platform data. */
public final class TransportContractTest {
    public static LoopbackRangeServer offlineServer(Clock clock,java.util.function.BooleanSupplier active) throws IOException {
        return new LoopbackRangeServer(clock,uri->{throw new AssertionError("Unexpected network");},active);
    }
    private static int checks;
    private static final URI TARGET = URI.create("https://test.bilivideo.com/audio?signature=secret");
    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");

    public static void main(String[] args) throws Exception {
        ranges();
        leases();
        requests();
        rejection();
        lifetime();
        diagnostic();
        cancellation();
        System.out.println("PASS: " + checks + " transport contract checks (offline fake upstream, real loopback HTTP)");
    }

    private static void ranges() {
        eq(new ByteRange(0, 0), ByteRange.parse("bytes=0-0", 100));
        eq(new ByteRange(80, 99), ByteRange.parse("bytes=80-", 100));
        eq(new ByteRange(90, 99), ByteRange.parse("bytes=-10", 100));
        eq(new ByteRange(0, 99), ByteRange.parse("bytes=-101", 100));
        eq(new ByteRange(98, 99), ByteRange.parse("bytes=98-1000", 100));
        eq(4L * 1024 * 1024, ByteRange.parse("bytes=0-4194303", 5L * 1024 * 1024).length());
        eq("bytes 0-0/100", ByteRange.parse("bytes=0-0", 100).contentHeader(100));
        for (String invalid : Arrays.asList(null, "", "bytes=-", "bytes=-0", "bytes=2-1",
                "bytes=0-0,2-3", "bytes=+1-2", "bytes=0-9223372036854775808", "bytes=0-4194304")) {
            fails(IllegalArgumentException.class, () -> ByteRange.parse(invalid, 5L * 1024 * 1024));
        }
        fails(IllegalArgumentException.class, () -> ByteRange.parse("bytes=100-", 100));
        fails(IllegalArgumentException.class, () -> ByteRange.parse("bytes=0-0", 0));
    }

    private static void leases() {
        MediaLease valid = lease("a", NOW.plusSeconds(60), 100);
        check(!valid.toString().contains("secret"), "Lease diagnostics leak credentials");
        check(valid.belongsTo("a"), "Account scope mismatch");
        valid.requireTarget(URI.create("https://test.bilivideo.cn/audio"));
        checks++;
        for (String target : List.of("http://test.bilivideo.com/a", "https://bilivideo.com.evil.test/a",
                "https://evilbilivideo.com/a", "https://127.0.0.1/a", "https://test.bilivideo.com:444/a",
                "https://user@test.bilivideo.com/a", "https://test.bilivideo.com/a#secret")) {
            fails(IllegalArgumentException.class, () -> valid.requireTarget(URI.create(target)));
        }
        fails(IllegalArgumentException.class, () -> new MediaLease(MediaLease.Platform.NETEASE, TARGET,
                Map.of(), "a", NOW, 1));
        for (Map<String, String> headers : List.of(Map.of("Cookie", "x\r\nHost: evil"),
                Map.of("Host", "evil"), Map.of("Range", "bytes=0-"), Map.of("Cookie", "a", "cookie", "b"))) {
            fails(IllegalArgumentException.class, () -> new MediaLease(MediaLease.Platform.BILIBILI, TARGET,
                    headers, "a", NOW, 1));
        }
        fails(IllegalArgumentException.class, () -> lease("", NOW, 1));
        fails(IllegalArgumentException.class, () -> lease("a", NOW, 0));
        fails(IllegalArgumentException.class, () -> lease("a", NOW, 2L * 1024 * 1024 * 1024 + 1));
    }

    private static void requests() throws Exception {
        List<FakeConnection> opened = new CopyOnWriteArrayList<>();
        try (LoopbackRangeServer server = server(uri -> {
            FakeConnection connection = new FakeConnection(uri, 100);
            opened.add(connection);
            return connection;
        })) {
            URI local = server.register(lease("a", NOW.plusSeconds(60), 100));
            check(local.getRawPath().matches("/media/[0-9a-f]{64}"), "Capability token bounds");
            Response head = call(local, "HEAD", "", "");
            eq(200, head.code());
            eq("100", head.header("Content-Length"));
            eq(0, head.body().length);
            eq(0, opened.size());
            Response response = call(local, "GET", "Range: bytes=10-19\r\nCookie: local-secret\r\n", "");
            eq(206, response.code());
            eq("bytes 10-19/100", response.header("Content-Range"));
            eq(10, response.body().length);
            for (int index = 0; index < 10; index++) eq((byte)(10 + index), response.body()[index]);
            FakeConnection upstream = opened.getFirst();
            eq("platform-secret", upstream.getRequestProperty("Cookie"));
            eq("bytes=10-19", upstream.getRequestProperty("Range"));
            eq("identity", upstream.getRequestProperty("Accept-Encoding"));
            check(!upstream.getInstanceFollowRedirects(), "Automatic redirects enabled");
            eq(416, call(local, "GET", "", "").code());
            eq("bytes */100", call(local, "GET", "Range: bytes=100-\r\n", "").header("Content-Range"));
            eq(405, call(local, "POST", "", "").code());
            eq(403, call(local, "GET", "Origin: https://evil.test\r\nRange: bytes=0-0\r\n", "").code());
            eq(403, call(local, "GET", "Range: bytes=0-0\r\n", "evil.test").code());
            eq(403, call(local, "GET", "Content-Length: 1\r\nRange: bytes=0-0\r\n", "").code());
            eq(403, call(local, "GET", "Transfer-Encoding: chunked\r\n", "").code());
            eq(400, call(local, "GET", "Range: bytes=0-0\r\nRange: bytes=0-1\r\n", "").code());
            eq(400, call(local, "GET", "X-Long: " + "x".repeat(8200) + "\r\n", "").code());
            eq(404, call(URI.create(local + "?url=https://evil.test"), "GET", "Range: bytes=0-0\r\n", "").code());
            eq(1, opened.size());
            server.revoke(URI.create(local + "?wrong"));
            eq(200, call(local, "HEAD", "", "").code());
            server.revoke(local);
            eq(404, call(local, "HEAD", "", "").code());
        }
    }

    private static void rejection() throws Exception {
        for (int scenario = 0; scenario < 8; scenario++) {
            int selected = scenario;
            List<FakeConnection> opened = new CopyOnWriteArrayList<>();
            try (LoopbackRangeServer server = server(uri -> {
                FakeConnection connection = new FakeConnection(uri, 100);
                switch (selected) {
                    case 0 -> connection.code = 200;
                    case 1 -> connection.contentRange = "bytes 0-1/100";
                    case 2 -> connection.declaredLength = 2;
                    case 3 -> connection.encoding = "gzip";
                    case 4 -> { connection.code = 302; connection.location = "https://evil.test/audio"; }
                    case 5 -> { connection.code = 302; connection.location = "https://other.bilivideo.com/audio"; }
                    case 6 -> { connection.code = 302; connection.location = "/loop"; }
                    case 7 -> { connection.code = 302; connection.location = "http://test.bilivideo.com/a"; }
                    default -> throw new AssertionError();
                }
                opened.add(connection);
                return connection;
            })) {
                URI local = server.register(lease("a", NOW.plusSeconds(60), 100));
                eq(502, call(local, "GET", "Range: bytes=0-0\r\n", "").code());
                eq(selected == 6 ? 4 : 1, opened.size());
                check(opened.stream().allMatch(c -> c.disconnected.get()), "Upstream not disconnected");
            }
        }
        List<URI> targets = new CopyOnWriteArrayList<>();
        try (LoopbackRangeServer server = server(uri -> {
            targets.add(uri);
            FakeConnection connection = new FakeConnection(uri, 100);
            if (targets.size() == 1) { connection.code = 307; connection.location = "/redirected"; }
            return connection;
        })) {
            URI local = server.register(lease("a", NOW.plusSeconds(60), 100));
            eq(206, call(local, "GET", "Range: bytes=0-0\r\n", "").code());
            eq(2, targets.size());
            eq("/redirected", targets.getLast().getPath());
        }
        try (LoopbackRangeServer server = server(uri -> {
            FakeConnection connection = new FakeConnection(uri, 100);
            connection.truncate = true;
            return connection;
        })) {
            URI local = server.register(lease("a", NOW.plusSeconds(60), 100));
            Response truncated = call(local, "GET", "Range: bytes=0-9\r\n", "");
            eq(206, truncated.code());
            eq("10", truncated.header("Content-Length"));
            eq(0, truncated.body().length);
        }
    }

    private static void lifetime() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicBoolean grant = new AtomicBoolean(true);
        try (LoopbackRangeServer server = new LoopbackRangeServer(clock, uri -> new FakeConnection(uri, 100), grant::get)) {
            URI a = server.register(lease("a", NOW.plusSeconds(60), 100));
            URI b = server.register(lease("b", NOW.plusSeconds(60), 100));
            server.revokeAccount("a");
            eq(404, call(a, "HEAD", "", "").code());
            eq(200, call(b, "HEAD", "", "").code());
            clock.now = NOW.plusSeconds(60);
            eq(404, call(b, "HEAD", "", "").code());
            fails(IllegalArgumentException.class, () -> server.register(lease("a", clock.instant(), 100)));
            fails(IllegalArgumentException.class, () -> server.register(lease("a", clock.instant().plusSeconds(1801), 100)));
            for (int index = 0; index < 64; index++) server.register(lease("a", clock.instant().plusSeconds(60), 100));
            fails(IllegalStateException.class, () -> server.register(lease("a", clock.instant().plusSeconds(60), 100)));
            grant.set(false);
            fails(SecurityException.class, () -> server.register(lease("a", clock.instant().plusSeconds(60), 100)));
        }
        LoopbackRangeServer stopped = server(uri -> new FakeConnection(uri, 100));
        stopped.close();
        stopped.close();
        fails(IllegalStateException.class, () -> stopped.register(lease("a", NOW.plusSeconds(60), 100)));
    }

    private static void cancellation() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        try (LoopbackRangeServer server = server(uri -> new FakeConnection(uri, 100) {
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    @Override public int read() throws IOException {
                        reading.countDown();
                        try {
                            if (!cancelled.await(5, TimeUnit.SECONDS)) throw new IOException("Test timeout");
                        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                        throw new IOException("Cancelled fake upstream");
                    }
                };
            }
            @Override public void disconnect() { super.disconnect(); cancelled.countDown(); }
        })) {
            URI local = server.register(lease("a", NOW.plusSeconds(60), 100));
            try (Socket socket = new Socket(local.getHost(), local.getPort())) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(request(local, "GET", "Range: bytes=0-0\r\n", "").getBytes(StandardCharsets.US_ASCII));
                check(reading.await(3, TimeUnit.SECONDS), "Upstream body did not start");
                server.revokeAccount("a");
                check(cancelled.await(1, TimeUnit.SECONDS), "Revocation did not cancel upstream");
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                check(response.endsWith("\r\n\r\n"), "Cancelled range published body bytes");
            }
        }
    }

    private static void diagnostic() throws Exception {
        AtomicBoolean outbound = new AtomicBoolean(false);
        try (LoopbackRangeServer server = server(uri -> {
            outbound.set(true); throw new AssertionError("Diagnostic made outbound request");
        })) {
            URI tone = server.registerDiagnosticTone();
            byte[] wave = DiagnosticTone.wave();
            Response head = call(tone, "HEAD", "", "");
            eq(200, head.code());
            eq(Integer.toString(wave.length), head.header("Content-Length"));
            Response header = call(tone, "GET", "Range: bytes=0-43\r\n", "");
            eq(206, header.code());
            check(Arrays.equals(Arrays.copyOf(wave, 44), header.body()), "Diagnostic WAV header changed");
            Response tail = call(tone, "GET", "Range: bytes=-100\r\n", "");
            eq(100, tail.body().length);
            check(Arrays.equals(Arrays.copyOfRange(wave, wave.length - 100, wave.length), tail.body()), "Diagnostic tail changed");
            eq(416, call(tone, "GET", "", "").code());
            eq(404, call(URI.create(tone + "?file=C:/private"), "GET", "Range: bytes=0-0\r\n", "").code());
            server.revokeAccount("unrelated-platform-account");
            eq(200, call(tone, "HEAD", "", "").code());
            server.revoke(tone);
            eq(404, call(tone, "HEAD", "", "").code());
            check(!outbound.get(), "Local fixture used remote opener");
        }
    }

    static LoopbackRangeServer server(LoopbackRangeServer.ConnectionOpener opener) throws IOException {
        return new LoopbackRangeServer(Clock.fixed(NOW, ZoneOffset.UTC), opener);
    }

    static MediaLease lease(String scope, Instant expiry, long length) {
        return new MediaLease(MediaLease.Platform.BILIBILI, TARGET,
                Map.of("Cookie", "platform-secret", "Referer", "https://www.bilibili.com/"), scope, expiry, length);
    }

    static class FakeConnection extends HttpURLConnection {
        final long total;
        final AtomicBoolean disconnected = new AtomicBoolean();
        int code = 206;
        String contentRange;
        long declaredLength = -1;
        String encoding;
        String location;
        boolean truncate;
        FakeConnection(URI uri, long total) throws IOException { super(uri.toURL()); this.total = total; }
        ByteRange range() { return ByteRange.parse(getRequestProperty("Range"), total); }
        @Override public int getResponseCode() { return code; }
        @Override public long getContentLengthLong() { return declaredLength < 0 ? range().length() : declaredLength; }
        @Override public String getHeaderField(String name) {
            return switch (name) {
                case "Content-Range" -> contentRange == null ? range().contentHeader(total) : contentRange;
                case "Content-Encoding" -> encoding;
                case "Location" -> location;
                default -> null;
            };
        }
        @Override public InputStream getInputStream() {
            if (truncate) return new ByteArrayInputStream(new byte[0]);
            ByteRange range = range();
            byte[] body = new byte[(int)range.length()];
            for (int index = 0; index < body.length; index++) body[index] = (byte)(range.first() + index);
            return new ByteArrayInputStream(body);
        }
        @Override public void disconnect() { disconnected.set(true); }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }

    static Response call(URI local, String method, String headers, String host) throws IOException {
        try (Socket socket = new Socket(local.getHost(), local.getPort())) {
            socket.setSoTimeout(4000);
            socket.getOutputStream().write(request(local, method, headers, host).getBytes(StandardCharsets.US_ASCII));
            byte[] bytes = socket.getInputStream().readAllBytes();
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            int split = text.indexOf("\r\n\r\n");
            if (split < 0) throw new IOException("Missing HTTP response headers");
            return new Response(Integer.parseInt(text.split(" ")[1]), text.substring(0, split),
                    Arrays.copyOfRange(bytes, split + 4, bytes.length));
        }
    }

    private static String request(URI local, String method, String headers, String host) {
        return method + " " + local.getRawPath() + (local.getRawQuery() == null ? "" : "?" + local.getRawQuery())
                + " HTTP/1.1\r\nHost: " + (host.isEmpty() ? local.getHost() + ":" + local.getPort() : host)
                + "\r\n" + headers + "\r\n";
    }

    record Response(int code, String headers, byte[] body) {
        String header(String name) {
            return headers.lines().filter(s -> s.startsWith(name + ": ")).map(s -> s.substring(name.length() + 2))
                    .findFirst().orElse(null);
        }
    }

    private static final class MutableClock extends Clock {
        volatile Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void fails(Class<? extends Throwable> expected, Action action) {
        try { action.run(); }
        catch (Throwable failure) {
            if (expected.isInstance(failure)) { checks++; return; }
            throw new AssertionError("Wrong failure: " + failure.getClass().getSimpleName(), failure);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static void eq(Object expected, Object actual) {
        check(java.util.Objects.equals(expected, actual), "Expected " + expected + ", got " + actual);
    }
}
