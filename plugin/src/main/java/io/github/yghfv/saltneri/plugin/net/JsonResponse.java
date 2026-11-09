package io.github.yghfv.saltneri.plugin.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** One bounded JSON reader for platform and login APIs; callers enforce endpoint and status policy. */
public final class JsonResponse {
    private JsonResponse() { }
    public static Map<String, Object> read(HttpURLConnection connection, RequestScope scope, boolean allowGzip) throws IOException {
        return read(connection,scope,allowGzip,false);
    }
    public static Map<String,Object> read(HttpURLConnection connection,RequestScope scope,boolean allowGzip,boolean neteaseRecommendations) throws IOException {
        String type = connection.getHeaderField("Content-Type"), encoding = connection.getHeaderField("Content-Encoding");
        if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")
                || connection.getContentLengthLong() > BoundedJson.MAX_BYTES) throw invalid();
        if (encoding != null && !encoding.equalsIgnoreCase("identity") && !(allowGzip && encoding.equalsIgnoreCase("gzip"))) throw invalid();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream raw = connection.getInputStream(); InputStream input = "gzip".equalsIgnoreCase(encoding) ? new GZIPInputStream(raw) : raw) {
            byte[] buffer = new byte[8192];
            for (;;) {
                scope.check(); int count = input.read(buffer, 0, Math.min(buffer.length, BoundedJson.MAX_BYTES - bytes.size() + 1));
                if (count < 0) break;
                if (count + bytes.size() > BoundedJson.MAX_BYTES) throw invalid();
                bytes.write(buffer, 0, count);
            }
        }
        scope.check(); return BoundedJson.object(neteaseRecommendations?BoundedJson.parseNeteaseRecommendations(bytes.toByteArray()):BoundedJson.parse(bytes.toByteArray()));
    }
    public static List<String> setCookies(HttpURLConnection connection) {
        return connection.getHeaderFields().entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase("Set-Cookie"))
                .flatMap(entry -> entry.getValue().stream()).toList();
    }
    private static IOException invalid() { return new IOException("Invalid or oversized platform JSON response"); }
}
