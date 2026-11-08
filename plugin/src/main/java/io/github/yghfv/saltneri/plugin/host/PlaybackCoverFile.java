package io.github.yghfv.saltneri.plugin.host;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** A metadata-only FLAC for Salt's existing artwork reader. Audio always comes from the stream map. */
public final class PlaybackCoverFile implements AutoCloseable {
    public static final int MAX_PNG_BYTES = 5 * 1024 * 1024;
    private static final ConcurrentHashMap<String,PlaybackCoverFile> reserved = new ConcurrentHashMap<>();
    private final Path directory, file;
    private boolean closed, materialized;
    private boolean persistent;
    private PlaybackCoverFile(Path directory, Path file) { this.directory = directory; this.file = file; }
    public String path() { return file.toString(); }
    /** Reserve stable queue identity without downloading artwork or creating one file/directory per row. */
    public static PlaybackCoverFile reserve() throws IOException {
        Path parent = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        Path directory = parent.resolve("saltneri-cover-" + UUID.randomUUID());
        PlaybackCoverFile cover = new PlaybackCoverFile(directory, directory.resolve(UUID.randomUUID() + ".flac"));
        if(reserved.putIfAbsent(cover.path(),cover)!=null) throw new IOException("Artwork reservation collision");
        return cover;
    }
    public static PlaybackCoverFile create(byte[] png) throws IOException {
        picture(png);
        PlaybackCoverFile cover=reserve();
        try { cover.replace(png); return cover; }
        catch(IOException | RuntimeException failure) { cover.close(); throw failure; }
    }
    static PlaybackCoverFile persistent(Path root, String id) throws IOException {
        if (!id.matches("[a-f0-9]{64}")) throw new IOException("Invalid saved artwork ID");
        Files.createDirectories(root);
        if (!root.toRealPath().equals(root)) throw new IOException("Saved artwork directory changed");
        PlaybackCoverFile cover = new PlaybackCoverFile(root, root.resolve(id + ".flac"));
        cover.persistent = true; cover.materialized = true;
        if (Files.exists(cover.file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(cover.file, LinkOption.NOFOLLOW_LINKS) || !cover.file.toRealPath().equals(cover.file))
                throw new IOException("Invalid saved artwork path");
        } else cover.replace(null);
        PlaybackCoverFile existing = reserved.putIfAbsent(cover.path(), cover);
        return existing == null ? cover : existing;
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
        if(closed) throw new IOException("Artwork retired");
        byte[] picture=png==null?null:picture(png);
        if(!materialized) {
            if(!directory.getParent().toRealPath().equals(directory.getParent())) throw new IOException("Artwork parent changed");
            Files.createDirectory(directory);
            materialized=true;
        }
        if(!directory.toRealPath().equals(directory) || Files.exists(file,LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().equals(file))) throw new IOException("Artwork retired");
        Path next=directory.resolve(UUID.randomUUID()+".tmp");
        try {
            write(next,picture);
            Files.move(next,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(next); }
    }
    public synchronized void clear() {
        if(persistent) return;
        if(materialized && !closed) try { replace(null); } catch(IOException ignored) { /* A host file handle may defer image eviction. */ }
    }
    static boolean isPath(String value) {
        PlaybackCoverFile reservation=reserved.get(value);
        if(reservation!=null) synchronized(reservation) {
            if(!reservation.closed && !reservation.materialized) return true;
            if(!reservation.closed && reservation.persistent) try {
                return Files.isRegularFile(reservation.file,LinkOption.NOFOLLOW_LINKS) && reservation.file.toRealPath().equals(reservation.file);
            } catch(IOException ignored) { return false; }
        }
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
        reserved.remove(path(),this);
        if(persistent) return;
        if(!materialized) return;
        try {
            // Only these exact owned paths are removed; never recurse or follow a replaced directory.
            if (!directory.toRealPath().equals(directory)) return;
            Files.deleteIfExists(file); Files.deleteIfExists(directory);
        } catch (IOException ignored) { /* Host readers may hold a Windows handle; no broad cleanup. */ }
    }
}
