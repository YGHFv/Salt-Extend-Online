package io.github.yghfv.saltneri.plugin.host;

import com.sun.jna.Memory;
import com.xuncorp.voxzen.cast.BassFileProcs;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Media Foundation must not receive a prematurely revoked, advertised-as-longer user file. */
final class SealedMediaFile implements AutoCloseable {
    static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
    private static long reserved;
    private static final ScheduledThreadPoolExecutor cleanup = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "salt-neri-sealed-cleanup");
        thread.setDaemon(true);
        return thread;
    });
    static {
        cleanup.setKeepAliveTime(10, TimeUnit.SECONDS);
        cleanup.allowCoreThreadTimeOut(true);
        cleanup.setRemoveOnCancelPolicy(true);
    }

    final Path path;
    private final long length;
    private boolean closed;
    private boolean released;

    private SealedMediaFile(Path path, long length) { this.path = path; this.length = length; }

    static boolean needsSealing(byte[] header) {
        if (header.length >= 8) {
            String box = new String(header, 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (switch (box) {
                case "ftyp", "styp", "moov", "moof", "mdat", "free", "skip", "wide", "uuid" -> true;
                default -> false;
            }) return true;
        }
        if (header.length >= 2 && (header[0] & 255) == 255 && (header[1] & 246) == 240) return true;
        return header.length >= 4 && (header[0] & 255) == 0x30 && (header[1] & 255) == 0x26
                && (header[2] & 255) == 0xb2 && (header[3] & 255) == 0x75;
    }

    static SealedMediaFile prepare(BassFileProcs procs, BooleanSupplier active) throws IOException {
        check(active);
        long length = procs.length.invoke(null);
        try (Memory buffer = new Memory(64 * 1024)) {
            int wanted = (int)Math.min(16, length);
            if (wanted < 1) throw new IOException("Empty online media");
            int count = procs.read.invoke(buffer, wanted, null);
            if (count != wanted) throw new IOException("Incomplete online media header");
            byte[] header = buffer.getByteArray(0, wanted);
            if (!procs.seek.invoke(0, null)) throw new IOException("Online media rewind failed");
            check(active);
            if (!needsSealing(header)) return null;
            reserve(length);
            SealedMediaFile file = null;
            try {
                file = new SealedMediaFile(Files.createTempFile("saltneri-sealed-", ".media"), length);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                try (OutputStream output = Files.newOutputStream(file.path)) {
                    long remaining = length;
                    while (remaining > 0) {
                        check(active);
                        if (System.nanoTime() >= deadline) throw new IOException("Online media staging timed out");
                        wanted = (int)Math.min(buffer.size(), remaining);
                        count = procs.read.invoke(buffer, wanted, null);
                        check(active);
                        if (count <= 0 || count > wanted) throw new IOException("Incomplete online media");
                        output.write(buffer.getByteArray(0, count));
                        remaining -= count;
                    }
                }
                check(active);
                if (Files.size(file.path) != length) throw new IOException("Online media size mismatch");
                return file;
            } catch (IOException | RuntimeException | Error failure) {
                if (file != null) file.close(); else release(length);
                throw failure;
            }
        }
    }

    private static void check(BooleanSupplier active) {
        if (Thread.currentThread().isInterrupted() || !active.getAsBoolean())
            throw new CancellationException("Online media staging cancelled");
    }
    private static synchronized void reserve(long length) throws IOException {
        if (length < 1 || length > MAX_FILE_BYTES || length > MAX_TOTAL_BYTES - reserved)
            throw new IOException("Online media staging budget exceeded");
        reserved += length;
    }
    private static synchronized void release(long length) { reserved -= length; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        remove(0);
    }
    private synchronized void remove(int attempt) {
        if (released) return;
        try {
            Files.deleteIfExists(path);
            released = true;
            release(length);
        } catch (IOException failure) {
            // A FREE sync can run just before Windows releases the native file handle.
            if (attempt < 10) cleanup.schedule(() -> remove(attempt + 1), 1, TimeUnit.SECONDS);
        }
    }
}
