package io.github.yghfv.saltneri.plugin.net;

import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared one-byte CDN validation. Bilibili redirects stay within its explicit HTTPS CDN policy. */
public final class MediaRangeProbe {
    @FunctionalInterface public interface Opener { HttpURLConnection open(URI uri) throws IOException; }
    public record Result(URI uri, long length, Instant expiresAt) {
        @Override public String toString() { return "MediaProbe[length=" + length + ", URL redacted]"; }
    }
    public static final class Rejected extends IOException {
        private final int status;
        public Rejected(int status) { super("Media HTTP rejected (status " + status + ")"); this.status = status; }
        public int status() { return status; }
    }
    private MediaRangeProbe() { }
    public static Result probe(MediaLease.Platform platform, URI initial, Map<String, String> headers,
                               long expectedLength, Instant expiresAt, Clock clock, Opener opener,
                               RequestScope scope, Runnable check) throws IOException {
        for (var header : headers.entrySet()) {
            if (!List.of("user-agent", "referer").contains(header.getKey().toLowerCase(Locale.ROOT))) throw invalid();
        }
        URI uri = initial; Instant expiry = expiresAt;
        for (int redirect = 0; redirect <= 3; redirect++) {
            check.run(); requireTarget(platform, uri); expiry = deadline(uri, expiry);
            if (!expiry.isAfter(clock.instant())) throw invalid();
            HttpURLConnection connection = opener.open(uri); scope.attach(connection);
            try {
                connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
                connection.setConnectTimeout(8000); connection.setReadTimeout(8000); connection.setRequestMethod("GET");
                headers.forEach(connection::setRequestProperty);
                connection.setRequestProperty("Accept-Encoding", "identity"); connection.setRequestProperty("Range", "bytes=0-0");
                int status = connection.getResponseCode(); check.run();
                if (List.of(403, 412, 429).contains(status)
                        || platform == MediaLease.Platform.QQMUSIC && status >= 400 && status <= 599) throw new Rejected(status);
                if (List.of(301, 302, 303, 307, 308).contains(status)) {
                    String location = connection.getHeaderField("Location"); URI next;
                    try { next = location == null ? null : uri.resolve(location); }
                    catch (IllegalArgumentException failure) { throw invalid(); }
                    try { MediaLease.requireRedirect(platform, uri, next); }
                    catch (IllegalArgumentException | NullPointerException rejected) { throw invalid(); }
                    uri = next; continue;
                }
                String encoding = connection.getHeaderField("Content-Encoding"), range = connection.getHeaderField("Content-Range");
                if ((encoding != null && !encoding.equalsIgnoreCase("identity")) || status != 206
                        || connection.getContentLengthLong() != 1 || range == null || !range.matches("bytes 0-0/[1-9][0-9]{0,9}")) throw invalid();
                long length = Long.parseLong(range.substring(10));
                if (length > 2L * 1024 * 1024 * 1024 || (expectedLength > 0 && length != expectedLength)) throw invalid();
                try (InputStream input = connection.getInputStream()) { check.run(); if (input.read() < 0) throw invalid(); }
                check.run(); if (!expiry.isAfter(clock.instant())) throw invalid();
                return new Result(uri, length, expiry);
            } finally { scope.release(connection); }
        }
        throw invalid();
    }
    private static void requireTarget(MediaLease.Platform platform, URI uri) throws IOException {
        try { MediaLease.requireTarget(platform, uri); }
        catch (IllegalArgumentException | NullPointerException failure) { throw invalid(); }
    }
    private static Instant deadline(URI uri, Instant maximum) throws IOException {
        if (uri.getRawQuery() == null) return maximum;
        boolean seen = false;
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            try {
                if (URLDecoder.decode(parts[0], StandardCharsets.UTF_8).equals("deadline")) {
                    if (seen || parts.length != 2 || !parts[1].matches("[1-9][0-9]{0,10}")) throw invalid();
                    seen = true; Instant deadline = Instant.ofEpochSecond(Long.parseLong(parts[1]));
                    if (deadline.isBefore(maximum)) maximum = deadline;
                }
            } catch (IllegalArgumentException failure) { throw invalid(); }
        }
        return maximum;
    }
    private static IOException invalid() { return new IOException("Invalid media range response or target"); }
}
