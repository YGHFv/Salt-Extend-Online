package io.github.yghfv.saltneri.plugin.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/** DNS-pinned, isolated HTTP/1.1 over verified TLS. Never consults global cookies, auth or proxies. */
public final class PinnedHttpsConnection extends HttpURLConnection {
    @FunctionalInterface interface Resolver { InetAddress[] resolve(String host) throws IOException; }
    interface Sockets {
        Socket create() throws IOException;
        Socket secure(Socket socket, String host) throws IOException;
    }
    private final URI target;
    private final Resolver resolver;
    private final Sockets sockets;
    private final Map<String, String> requestHeaders = new LinkedHashMap<>();
    private final Map<String, String> responseHeaders = new LinkedHashMap<>();
    private final Map<String, List<String>> responseValues = new LinkedHashMap<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Socket socket;
    private InputStream body;
    private String statusLine;
    private byte[] formBody;

    /** POST is deliberately unavailable to media pull requests or arbitrary targets. */
    public static PinnedHttpsConnection neteaseFormPost(URI target, byte[] form) throws IOException {
        if (!"music.163.com".equals(target.getHost()) || !Set.of("/weapi/login/qrcode/unikey",
                "/weapi/login/qrcode/client/login", "/weapi/w/nuser/account/get").contains(target.getPath())
                || form.length < 1 || form.length > 32768) throw new IOException("Invalid login POST target or body");
        PinnedHttpsConnection connection = new PinnedHttpsConnection(target);
        connection.loginForm(form);
        return connection;
    }

    /** Song resolution has its own fixed EAPI endpoint; this never enables arbitrary POST. */
    public static PinnedHttpsConnection neteaseSongPost(URI target, byte[] form) throws IOException {
        if (!"interface.music.163.com".equals(target.getHost())
                || !"/eapi/song/enhance/player/url/v1".equals(target.getPath()) || target.getRawQuery() != null) {
            throw new IOException("Invalid song POST target");
        }
        PinnedHttpsConnection connection = new PinnedHttpsConnection(target);
        connection.loginForm(form);
        return connection;
    }
    public static PinnedHttpsConnection neteaseCatalogPost(URI target, byte[] form) throws IOException {
        if (!"music.163.com".equals(target.getHost()) || !Set.of("/weapi/personalized/playlist", "/weapi/user/playlist",
                "/api/v6/playlist/detail", "/weapi/cloudsearch/get/web").contains(target.getPath())) throw new IOException("Invalid catalog POST target");
        PinnedHttpsConnection connection = new PinnedHttpsConnection(target); connection.loginForm(form); return connection;
    }
    public static PinnedHttpsConnection neteaseLyricPost(URI target, byte[] form) throws IOException {
        if (!"interface.music.163.com".equals(target.getHost()) || !"/eapi/song/lyric/v1".equals(target.getPath())
                || target.getRawQuery() != null) throw new IOException("Invalid lyric POST target");
        PinnedHttpsConnection connection = new PinnedHttpsConnection(target); connection.loginForm(form); return connection;
    }
    void loginForm(byte[] form) throws IOException {
        if (form.length < 1 || form.length > 32768) throw new IOException("Invalid login POST body");
        for (byte value : form) if (value < 32 || value > 126) throw new IOException("Invalid login POST encoding");
        formBody = form.clone(); setRequestMethod("POST");
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
    }

    public PinnedHttpsConnection(URI target) throws IOException {
        this(target, InetAddress::getAllByName, tlsSockets());
    }

    PinnedHttpsConnection(URI target, Resolver resolver, Sockets sockets) throws IOException {
        super(target.toURL());
        if (!"https".equals(target.getScheme()) || target.getHost() == null
                || !target.getHost().matches("[A-Za-z0-9.-]+")
                || (target.getPort() != -1 && target.getPort() != 443)
                || target.getRawUserInfo() != null || target.getRawFragment() != null) {
            throw new IOException("Invalid HTTPS target");
        }
        this.target = target; this.resolver = resolver; this.sockets = sockets;
        setConnectTimeout(8000); setReadTimeout(8000);
    }

    @Override public synchronized void setRequestProperty(String key, String value) {
        if (connected) throw new IllegalStateException("Already connected");
        String lower = key.toLowerCase(Locale.ROOT);
        if (!lower.matches("[!#$%&'*+.^_`|~a-z0-9-]+") || List.of("host", "connection", "content-length", "transfer-encoding", "expect").contains(lower)
                || value == null || value.length() > 8192 || value.chars().anyMatch(c -> c < 32 || c > 126)) {
            throw new IllegalArgumentException("Invalid HTTPS request header");
        }
        requestHeaders.put(lower, value);
    }
    @Override public synchronized String getRequestProperty(String name) { return requestHeaders.get(name.toLowerCase(Locale.ROOT)); }
    @Override public void addRequestProperty(String key, String value) { setRequestProperty(key, value); }

    @Override public synchronized void connect() throws IOException {
        if (connected) return;
        requireOpen();
        if (getDoOutput() || (formBody == null ? (!getRequestMethod().equals("GET") && !getRequestMethod().equals("HEAD"))
                : !getRequestMethod().equals("POST"))) throw new IOException("Only pull requests or dedicated login POST supported");
        InetAddress[] addresses = resolver.resolve(target.getHost());
        if (addresses.length == 0) throw new IOException("No HTTPS address");
        for (InetAddress address : addresses) if (!isPublic(address)) throw new IOException("Private upstream address rejected");
        IOException last = null;
        for (int index = 0; index < Math.min(3, addresses.length); index++) {
            requireOpen();
            Socket candidate = sockets.create();
            socket = candidate;
            boolean sending = false;
            try {
                requireOpen();
                candidate.setSoTimeout(getReadTimeout());
                // Pass the already-validated InetAddress, never the hostname, to the actual TCP connect.
                candidate.connect(new InetSocketAddress(addresses[index], 443), getConnectTimeout());
                candidate = sockets.secure(candidate, target.getHost());
                socket = candidate;
                requireOpen();
                String path = target.getRawPath();
                if (path == null || path.isEmpty()) path = "/";
                if (target.getRawQuery() != null) path += "?" + target.getRawQuery();
                if (path.length() > 16384 || path.chars().anyMatch(c -> c <= 32 || c >= 127)) throw new IOException("Invalid HTTPS request path");
                StringBuilder request = new StringBuilder(getRequestMethod() + " " + path + " HTTP/1.1\r\nHost: "
                        + target.getHost() + "\r\nConnection: close\r\n");
                requestHeaders.forEach((key, value) -> request.append(key).append(": ").append(value).append("\r\n"));
                if (formBody != null) request.append("Content-Length: ").append(formBody.length).append("\r\n");
                if (request.length() > 32768) throw new IOException("HTTPS request headers too large");
                sending = true;
                candidate.getOutputStream().write(request.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
                if (formBody != null) candidate.getOutputStream().write(formBody);
                InputStream input = new BufferedInputStream(candidate.getInputStream());
                int[] budget = {0};
                for (int interim = 0; ; interim++) {
                    String status = line(input, budget);
                    if (!status.matches("HTTP/1\\.[01] [1-5][0-9]{2}( .*)?")) throw new IOException("Invalid HTTPS status");
                    responseCode = Integer.parseInt(status.substring(9, 12));
                    statusLine = status;
                    responseHeaders.clear();
                    responseValues.clear();
                    readHeaders(input, budget, responseHeaders, responseValues);
                    if (responseCode >= 200) break;
                    if (responseCode == 101 || interim >= 3) throw new IOException("Unsupported HTTP protocol transition");
                }
                String transfer = responseHeaders.get("transfer-encoding");
                String length = responseHeaders.get("content-length");
                if (transfer != null && (length != null || !transfer.equalsIgnoreCase("chunked"))) throw new IOException("Ambiguous HTTPS body framing");
                if (getRequestMethod().equals("HEAD") || responseCode == 204 || responseCode == 304) body = InputStream.nullInputStream();
                else if (transfer != null) body = new Chunks(input);
                else if (length != null) body = new Limited(input, positiveLength(length));
                else body = input;
                connected = true;
                return;
            } catch (IOException failure) {
                try { candidate.close(); } catch (IOException ignored) { }
                last = failure;
                // Only connection/handshake failures may try another already-validated address.
                if (sending || cancelled.get()) throw failure;
            }
        }
        throw last == null ? new IOException("HTTPS connection failed") : last;
    }

    @Override public int getResponseCode() throws IOException { connect(); return responseCode; }
    @Override public InputStream getInputStream() throws IOException {
        connect();
        if (responseCode >= 400) throw new IOException("HTTPS status " + responseCode);
        return body;
    }
    @Override public String getHeaderField(String name) {
        try { connect(); return name == null ? statusLine : responseHeaders.get(name.toLowerCase(Locale.ROOT)); }
        catch (IOException failure) { return null; }
    }
    @Override public long getContentLengthLong() {
        String value = getHeaderField("Content-Length");
        try { return value == null ? -1 : positiveLength(value); } catch (IOException failure) { return -1; }
    }
    @Override public Map<String, List<String>> getHeaderFields() {
        try {
            connect(); Map<String, List<String>> copy = new LinkedHashMap<>();
            responseValues.forEach((key, values) -> copy.put(key, List.copyOf(values)));
            return java.util.Collections.unmodifiableMap(copy);
        } catch (IOException failure) { return Map.of(); }
    }
    @Override public void disconnect() {
        cancelled.set(true);
        Socket current = socket;
        if (current != null) try { current.close(); } catch (IOException ignored) { }
    }
    @Override public boolean usingProxy() { return false; }
    private void requireOpen() throws IOException { if (cancelled.get()) throw new IOException("HTTPS request cancelled"); }

    public static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        if (bytes.length == 16) {
            // Permit global unicast only, not mapped, NAT64, Teredo, 6to4 or special-use ranges.
            if ((bytes[0] & 0xe0) != 0x20) return false;
            int second = bytes[1] & 255, third = bytes[2] & 255, fourth = bytes[3] & 255;
            if (bytes[0] == 0x20 && second == 2) return false;
            return !(bytes[0] == 0x20 && second == 1 && (third <= 1 || (third == 0x0d && fourth == 0xb8)))
                    && !(bytes[0] == 0x3f && second == 0xff && third < 0x10);
        }
        int first = bytes[0] & 255, second = bytes[1] & 255;
        int third = bytes[2] & 255;
        return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
                && !(first == 192 && ((second == 0 && (third == 0 || third == 2)) || (second == 88 && third == 99)))
                && !(first == 203 && second == 0 && third == 113);
    }
    static SSLParameters parameters(String host) {
        SSLParameters parameters = new SSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setServerNames(List.of(new SNIHostName(host)));
        return parameters;
    }
    private static SSLContext sharedTls;
    static synchronized SSLContext tlsContext() throws IOException {
        if (sharedTls != null) return sharedTls;
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, null, null);
            // Plugin-local trust/session cache, not JVM global HTTP state. Each request still pins DNS and verifies its host.
            context.getClientSessionContext().setSessionCacheSize(64);
            context.getClientSessionContext().setSessionTimeout(300);
            sharedTls = context; return context;
        } catch (GeneralSecurityException failure) { throw new IOException("Verified TLS unavailable"); }
    }
    private static Sockets tlsSockets() throws IOException {
        SSLContext context = tlsContext();
        return new Sockets() {
            @Override public Socket create() { return new Socket(Proxy.NO_PROXY); }
            @Override public Socket secure(Socket socket, String host) throws IOException {
                // Layering supplies the certificate's DNS peer name without another DNS lookup.
                SSLSocket tls = (SSLSocket)context.getSocketFactory().createSocket(socket, host, 443, true);
                tls.setSoTimeout(socket.getSoTimeout());
                tls.setSSLParameters(parameters(host));
                tls.startHandshake();
                return tls;
            }
        };
    }
    private static long positiveLength(String text) throws IOException {
        if (!text.matches("[0-9]{1,19}")) throw new IOException("Invalid HTTPS length");
        try { return Long.parseLong(text); } catch (NumberFormatException failure) { throw new IOException("HTTPS length overflow"); }
    }
    private static String line(InputStream input, int[] budget) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (;;) {
            int value = input.read();
            if (value < 0 || ++budget[0] > 65536) throw new IOException("Truncated or oversized HTTPS headers");
            if (value == '\r') {
                if (input.read() != '\n' || ++budget[0] > 65536) throw new IOException("Invalid HTTPS line ending");
                return result.toString(StandardCharsets.US_ASCII);
            }
            if ((value < 32 && value != '\t') || value > 126 || result.size() >= 16384) throw new IOException("Invalid HTTPS header");
            result.write(value);
        }
    }
    private static void readHeaders(InputStream input, int[] budget, Map<String, String> headers) throws IOException {
        readHeaders(input, budget, headers, null);
    }
    private static void readHeaders(InputStream input, int[] budget, Map<String, String> headers,
                                    Map<String, List<String>> values) throws IOException {
        String next;
        int count = 0;
        while (!(next = line(input, budget)).isEmpty()) {
            int colon = next.indexOf(':');
            if (colon < 1 || headers.size() >= 256 || ++count > 512) throw new IOException("Invalid HTTPS headers");
            String key = next.substring(0, colon).toLowerCase(Locale.ROOT);
            if (!key.matches("[!#$%&'*+.^_`|~a-z0-9-]+")) throw new IOException("Invalid HTTPS header name");
            String previous = headers.putIfAbsent(key, next.substring(colon + 1).trim());
            if (values != null) values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(next.substring(colon + 1).trim());
            if (previous != null && List.of("content-length", "content-range", "transfer-encoding", "location", "content-encoding", "x-refresh-token").contains(key)) {
                throw new IOException("Duplicate HTTPS framing header");
            }
        }
    }
    private static class Limited extends InputStream {
        final InputStream source;
        long remaining;
        Limited(InputStream source, long length) { this.source = source; remaining = length; }
        @Override public int read() throws IOException {
            byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            if (remaining == 0) return -1;
            int count = source.read(bytes, offset, (int)Math.min(length, remaining));
            if (count < 0) throw new IOException("Truncated HTTPS body");
            remaining -= count; return count;
        }
        @Override public void close() throws IOException { source.close(); }
    }
    private static final class Chunks extends InputStream {
        final InputStream source;
        long remaining;
        boolean first = true, done;
        final int[] framingBudget = {0};
        Chunks(InputStream source) { this.source = source; }
        @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            if (done) return -1;
            if (remaining == 0) {
                if (!first && (source.read() != '\r' || source.read() != '\n')) throw new IOException("Invalid HTTPS chunk separator");
                first = false;
                String size = line(source, framingBudget).split(";", 2)[0];
                if (!size.matches("[0-9a-fA-F]{1,8}")) throw new IOException("Invalid HTTPS chunk size");
                remaining = Long.parseLong(size, 16);
                if (remaining == 0) {
                    Map<String, String> trailers = new LinkedHashMap<>();
                    readHeaders(source, framingBudget, trailers);
                    if (trailers.keySet().stream().anyMatch(List.of("content-length", "transfer-encoding", "content-range", "content-encoding", "location")::contains)) {
                        throw new IOException("HTTPS framing trailer rejected");
                    }
                    done = true; return -1;
                }
            }
            int count = source.read(bytes, offset, (int)Math.min(length, remaining));
            if (count < 0) throw new IOException("Truncated HTTPS chunk");
            remaining -= count; return count;
        }
        @Override public void close() throws IOException { source.close(); }
    }
}
