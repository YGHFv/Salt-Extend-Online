package io.github.yghfv.saltneri.plugin.ui;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.library.CoverSource;
import io.github.yghfv.saltneri.plugin.net.PinnedHttpsConnection;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Separate list/playback image budgets: isolated HTTPS, no cookies or shared disk cache. */
public final class CoverLoader implements AutoCloseable {
    @FunctionalInterface public interface Opener { HttpURLConnection open(URI uri) throws IOException; }
    private static final int MAX_BYTES = 1048576, MAX_ENTRIES = 20;
    private final Opener opener;
    private final ThreadPoolExecutor worker;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private volatile boolean closed;
    private static final class Entry {
        final BooleanSupplier valid;
        volatile byte[] png;
        volatile RequestScope request;
        Future<?> future;
        volatile boolean retired;
        Entry(BooleanSupplier valid) { this.valid = valid; }
        boolean active() { try { return !retired && valid.getAsBoolean(); } catch (RuntimeException error) { return false; } }
        void cancel() { retired = true; png = null; if (request != null) request.close(); if (future != null) future.cancel(true); }
    }
    public CoverLoader() { this(PinnedHttpsConnection::new); }
    public CoverLoader(Opener opener) {
        this.opener = opener;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(20), r -> {
            Thread thread = new Thread(r, "salt-neri-cover"); thread.setDaemon(true); return thread;
        });
    }
    public synchronized byte[] request(String token, URI source, BooleanSupplier valid) {
        entries.entrySet().removeIf(row -> { if (!row.getValue().active()) { row.getValue().cancel(); return true; } return false; });
        worker.purge();
        if (closed || source == null || !valid.getAsBoolean()) return null;
        Entry entry = entries.get(token);
        if (entry != null) return entry.png;
        if (entries.size() >= MAX_ENTRIES) return null;
        Entry next = new Entry(valid); entries.put(token, next);
        try { next.future = worker.submit(() -> {
            try (RequestScope scope = new RequestScope(() -> !closed && next.active())) {
                next.request = scope; scope.check(); byte[] png = load(source, scope); scope.check();
                synchronized (CoverLoader.this) { if (!closed && entries.get(token) == next && next.active()) next.png = png; }
            } catch (IOException | RuntimeException ignored) { /* A failed image is a placeholder, never an audio failure/retry. */ }
            finally { next.request = null; }
        }); } catch (RejectedExecutionException ignored) { next.cancel(); entries.remove(token); }
        return null;
    }
    public byte[] load(URI source, RequestScope scope) throws IOException {
        return load(source, scope, 96, MAX_BYTES);
    }
    public byte[] loadPlayback(URI source, RequestScope scope) throws IOException {
        URI original = CoverSource.original(source);
        if (original == null) throw invalid();
        return load(original, scope, 1024, 8 * MAX_BYTES);
    }
    private byte[] load(URI source, RequestScope scope, int edge, int maxBytes) throws IOException {
        if (!source.equals(CoverSource.parse(AccountPlatform.BILIBILI, source.toString()))
                && !source.equals(CoverSource.parse(AccountPlatform.NETEASE, source.toString()))) throw invalid();
        scope.check(); HttpURLConnection connection = opener.open(source); scope.attach(connection);
        byte[] encoded;
        try {
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false); connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
            connection.setRequestMethod("GET"); connection.setRequestProperty("Accept", "image/jpeg,image/png");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 SaltExtendOnline/0.7.7");
            connection.setRequestProperty("Referer", source.getHost().endsWith("hdslb.com") ? "https://www.bilibili.com/" : "https://music.163.com/");
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > maxBytes) throw invalid();
            String type = connection.getHeaderField("Content-Type"), encoding = connection.getHeaderField("Content-Encoding");
            if (type == null || !Set.of("image/jpeg", "image/png").contains(type.split(";",2)[0].trim().toLowerCase(Locale.ROOT))
                    || encoding != null && !encoding.equalsIgnoreCase("identity")) throw invalid();
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (;;) { scope.check(); int count = input.read(buffer, 0, Math.min(buffer.length, maxBytes - bytes.size() + 1));
                    if (count < 0) break; if (bytes.size() + count > maxBytes) throw invalid(); bytes.write(buffer, 0, count); }
                encoded = bytes.toByteArray();
            }
        } finally { scope.release(connection); }
        scope.check();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))) {
            var readers = ImageIO.getImageReaders(input); if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                if (!Set.of("png", "jpeg").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw invalid();
                reader.setInput(input, true, true); int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long)width * height > 16000000) throw invalid();
                int outputEdge = edge == 96 ? edge : Math.min(edge, Math.min(width, height));
                var param = reader.getDefaultReadParam(); int sample = Math.max(1, Math.min(width, height) / (outputEdge * 2)); param.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage image = reader.read(0, param); scope.check();
                BufferedImage thumbnail = new BufferedImage(outputEdge, outputEdge, BufferedImage.TYPE_INT_ARGB);
                var graphics = thumbnail.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    int side = Math.min(image.getWidth(), image.getHeight()), x = (image.getWidth() - side) / 2, y = (image.getHeight() - side) / 2;
                    graphics.drawImage(image, 0, 0, outputEdge, outputEdge, x, y, x + side, y + side, null);
                } finally { graphics.dispose(); image.flush(); }
                try (ByteArrayOutputStream png = new ByteArrayOutputStream()) {
                    if (!ImageIO.write(thumbnail, "png", png)) throw invalid(); scope.check(); return png.toByteArray();
                } finally { thumbnail.flush(); }
            } finally { reader.dispose(); }
        }
    }
    private static IOException invalid() { return new IOException("Cover unavailable"); }
    public synchronized void clear() { entries.values().forEach(Entry::cancel); entries.clear(); worker.purge(); }
    @Override public synchronized void close() { closed = true; clear(); worker.shutdownNow(); }
}
