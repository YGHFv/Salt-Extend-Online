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
    @FunctionalInterface public interface Source { URI resolve(RequestScope request) throws IOException; }
    public static final int LIST_EDGE = 480;
    static final int PARALLEL_LOADS = 3;
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
        worker = new ThreadPoolExecutor(PARALLEL_LOADS, PARALLEL_LOADS, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(20), r -> {
            Thread thread = new Thread(r, "salt-neri-cover"); thread.setDaemon(true); return thread;
        });
    }
    public byte[] request(String token, URI source, BooleanSupplier valid) {
        return requestSource(token, scope -> source, valid);
    }
    public synchronized byte[] requestSource(String token, Source source, BooleanSupplier valid) {
        return requestSource(token, source, null, valid);
    }
    public synchronized byte[] requestSource(String token, Source source, Source fallback, BooleanSupplier valid) {
        entries.entrySet().removeIf(row -> { if (!row.getValue().active()) { row.getValue().cancel(); return true; } return false; });
        worker.purge();
        if (closed || source == null || !valid.getAsBoolean()) return null;
        Entry entry = entries.get(token);
        if (entry != null) return entry.png;
        if (entries.size() >= MAX_ENTRIES) return null;
        Entry next = new Entry(valid); entries.put(token, next);
        try { next.future = worker.submit(() -> {
            try (RequestScope scope = new RequestScope(() -> !closed && next.active())) {
                next.request = scope; scope.check(); URI uri = source.resolve(scope); scope.check();
                if (uri == null) return;
                byte[] png;
                try { png = load(uri, scope); }
                catch (IOException unavailable) {
                    scope.check(); URI alternate = fallback == null ? null : fallback.resolve(scope); scope.check();
                    if (alternate == null || alternate.equals(uri)) throw unavailable;
                    png = load(alternate, scope);
                }
                scope.check();
                synchronized (CoverLoader.this) { if (!closed && entries.get(token) == next && next.active()) next.png = png; }
            } catch (IOException | RuntimeException ignored) { /* A failed image is a placeholder, never an audio failure/retry. */ }
            finally { next.request = null; }
        }); } catch (RejectedExecutionException ignored) { next.cancel(); entries.remove(token); }
        return null;
    }
    public byte[] load(URI source, RequestScope scope) throws IOException {
        return load(source, scope, LIST_EDGE, 4 * MAX_BYTES);
    }
    public byte[] loadPlayback(URI source, RequestScope scope) throws IOException {
        URI original = CoverSource.original(source);
        if (original == null) throw invalid();
        return load(original, scope, 1024, 8 * MAX_BYTES);
    }
    public byte[] loadPlayback(URI source,RequestScope scope,CoverMode mode) throws IOException {
        byte[] png=loadPlayback(source,scope);
        if(mode==CoverMode.FIT) return png;
        BufferedImage image=ImageIO.read(new ByteArrayInputStream(png));
        int edge=Math.min(image.getWidth(),image.getHeight());
        var crop=image.getSubimage(mode.left(image.getWidth(),image.getHeight()),(image.getHeight()-edge)/2,edge,edge);
        var bytes=new ByteArrayOutputStream(); ImageIO.write(crop,"png",bytes); scope.check(); return bytes.toByteArray();
    }
    private byte[] load(URI source, RequestScope scope, int edge, int maxBytes) throws IOException {
        source = CoverSource.original(source);
        if (source == null) throw invalid();
        scope.check(); HttpURLConnection connection = opener.open(source); scope.attach(connection);
        byte[] encoded;
        try {
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false); connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
            connection.setRequestMethod("GET"); connection.setRequestProperty("Accept", "image/jpeg,image/jpg,image/png,image/webp");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 SaltExtendOnline/0.7.8");
            connection.setRequestProperty("Referer", source.getHost().endsWith(".music.126.net") ? "https://music.163.com/" : "https://www.bilibili.com/");
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > maxBytes) throw invalid();
            String type = connection.getHeaderField("Content-Type"), encoding = connection.getHeaderField("Content-Encoding");
            // NetEase's image CDN labels real JPEGs image/jpg; the bounded decoder still validates the bytes.
            if (type == null || !Set.of("image/jpeg", "image/jpg", "image/png", "image/webp").contains(type.split(";",2)[0].trim().toLowerCase(Locale.ROOT))
                    || encoding != null && !encoding.equalsIgnoreCase("identity")) throw invalid();
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (;;) { scope.check(); int count = input.read(buffer, 0, Math.min(buffer.length, maxBytes - bytes.size() + 1));
                    if (count < 0) break; if (bytes.size() + count > maxBytes) throw invalid(); bytes.write(buffer, 0, count); }
                encoded = bytes.toByteArray();
            }
        } finally { scope.release(connection); }
        scope.check();
        if (encoded.length >= 12 && encoded[0] == 'R' && encoded[1] == 'I' && encoded[2] == 'F' && encoded[3] == 'F'
                && encoded[8] == 'W' && encoded[9] == 'E' && encoded[10] == 'B' && encoded[11] == 'P') return webp(encoded, edge, scope);
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))) {
            var readers = ImageIO.getImageReaders(input); if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                if (!Set.of("png", "jpeg").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw invalid();
                reader.setInput(input, true, true); int width = reader.getWidth(0), height = reader.getHeight(0);
                dimensions(width, height);
                double scale = Math.min(1.0, (double)edge / Math.max(width, height));
                int outWidth = Math.max(1, (int)Math.round(width * scale)), outHeight = Math.max(1, (int)Math.round(height * scale));
                var param = reader.getDefaultReadParam(); int sample = Math.max(1, Math.max(width, height) / (edge * 2)); param.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage image = reader.read(0, param); scope.check();
                BufferedImage thumbnail = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_ARGB);
                var graphics = thumbnail.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(image, 0, 0, outWidth, outHeight, null);
                } finally { graphics.dispose(); image.flush(); }
                try (ByteArrayOutputStream png = new ByteArrayOutputStream()) {
                    if (!ImageIO.write(thumbnail, "png", png)) throw invalid(); scope.check(); return png.toByteArray();
                } finally { thumbnail.flush(); }
            } finally { reader.dispose(); }
        }
    }
    private static void dimensions(int width, int height) throws IOException {
        if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long)width * height > 16000000) throw invalid();
    }
    private static byte[] webp(byte[] encoded, int edge, RequestScope scope) throws IOException {
        // ImageIO has no bundled WebP reader; reuse Salt's Skia only after checking codec dimensions.
        try (var data = org.jetbrains.skia.Data.Companion.makeFromBytes(encoded, 0, encoded.length);
             var codec = org.jetbrains.skia.Codec.Companion.makeFromData(data)) {
            int width = codec.getWidth(), height = codec.getHeight(); dimensions(width, height); scope.check();
            double scale = Math.min(1.0, (double)edge / Math.max(width, height));
            int outWidth = Math.max(1, (int)Math.round(width * scale)), outHeight = Math.max(1, (int)Math.round(height * scale));
            try (var bitmap = codec.readPixels(); var image = org.jetbrains.skia.Image.Companion.makeFromBitmap(bitmap);
                 var surface = org.jetbrains.skia.Surface.Companion.makeRasterN32Premul(outWidth, outHeight)) {
                surface.getCanvas().drawImageRect(image, org.jetbrains.skia.Rect.Companion.makeWH(outWidth, outHeight));
                scope.check();
                try (var result = surface.makeImageSnapshot(); var png = result.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG, 100, 0)) {
                    if (png == null) throw invalid(); return png.getBytes();
                }
            }
        } catch (RuntimeException | LinkageError failure) { throw invalid(); }
    }
    private static IOException invalid() { return new IOException("Cover unavailable"); }
    public synchronized void clear() { entries.values().forEach(Entry::cancel); entries.clear(); worker.purge(); }
    @Override public synchronized void close() { closed = true; clear(); worker.shutdownNow(); }
}
