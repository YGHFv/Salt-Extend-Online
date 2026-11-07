package io.github.yghfv.saltneri.plugin.host;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;

/** A metadata-only FLAC for Salt's existing artwork reader. Audio always comes from the stream map. */
public final class PlaybackCoverFile implements AutoCloseable {
    public static final int MAX_PNG_BYTES = 5 * 1024 * 1024;
    private final Path directory, file;
    private boolean closed;
    private PlaybackCoverFile(Path directory, Path file) { this.directory = directory; this.file = file; }
    public String path() { return file.toString(); }
    public static PlaybackCoverFile create(byte[] png) throws IOException {
        byte[] picture = picture(png);
        Path parent = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        Path directory = Files.createDirectory(parent.resolve("saltneri-cover-" + UUID.randomUUID()));
        Path file = directory.resolve(UUID.randomUUID() + ".flac");
        try { write(file,picture); }
        catch (IOException failure) { Files.deleteIfExists(file); Files.deleteIfExists(directory); throw failure; }
        return new PlaybackCoverFile(directory, file);
    }
    private static byte[] picture(byte[] png) throws IOException {
        if (png == null || png.length < 24 || png.length > MAX_PNG_BYTES
                || !java.util.Arrays.equals(java.util.Arrays.copyOf(png, 8), new byte[]{(byte)137,80,78,71,13,10,26,10})) {
            throw new IOException("Invalid normalized playback cover");
        }
        int width = java.nio.ByteBuffer.wrap(png, 16, 4).getInt(), height = java.nio.ByteBuffer.wrap(png, 20, 4).getInt();
        if (width < 1 || width > 1024 || height < 1 || height > 1024) throw new IOException("Invalid playback cover dimensions");
        try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            out.writeInt(3); out.writeInt(9); out.writeBytes("image/png"); out.writeInt(0);
            out.writeInt(width); out.writeInt(height); out.writeInt(32); out.writeInt(0); out.writeInt(png.length); out.write(png);
            return bytes.toByteArray();
        }
    }
    private static void write(Path file,byte[] picture) throws IOException {
        try (var out = new DataOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW))) {
            out.writeBytes("fLaC"); out.writeInt(picture==null?0x80000022:34);
            out.writeShort(4096); out.writeShort(4096); out.write(new byte[6]);
            out.writeLong((44100L << 44) | (1L << 41) | (15L << 36)); out.write(new byte[16]);
            if(picture!=null) { out.writeInt(0x86000000 | picture.length); out.write(picture); }
        }
    }
    /** Evict image bytes, not the native queue's path. Replace atomically so readers see a complete file. */
    public synchronized void replace(byte[] png) throws IOException {
        if(closed || !directory.toRealPath().equals(directory) || !file.toRealPath().equals(file)) throw new IOException("Artwork retired");
        byte[] picture=png==null?null:picture(png);
        Path next=directory.resolve(UUID.randomUUID()+".tmp");
        try {
            write(next,picture);
            Files.move(next,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(next); }
    }
    public void clear() {
        try { replace(null); } catch(IOException ignored) { /* A host file handle may defer image eviction. */ }
    }
    static boolean isPath(String value) {
        try {
            Path path = Path.of(value);
            return path.isAbsolute() && path.equals(path.normalize()) && path.getFileName().toString().matches("[0-9a-f-]{36}\\.flac")
                    && path.getParent().getFileName().toString().matches("saltneri-cover-[0-9a-f-]{36}")
                    && path.getParent().getParent().equals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath())
                    && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath().equals(path);
        } catch (IOException | RuntimeException failure) { return false; }
    }
    @Override public synchronized void close() {
        closed=true;
        try {
            // Only these exact owned paths are removed; never recurse or follow a replaced directory.
            if (!directory.toRealPath().equals(directory)) return;
            Files.deleteIfExists(file); Files.deleteIfExists(directory);
        } catch (IOException ignored) { /* Host readers may hold a Windows handle; no broad cleanup. */ }
    }
}
