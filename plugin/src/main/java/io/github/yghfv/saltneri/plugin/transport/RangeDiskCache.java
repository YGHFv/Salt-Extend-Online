package io.github.yghfv.saltneri.plugin.transport;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Per-runtime, lease-scoped range cache. Only complete validated ranges, never URLs/cookies, reach disk. */
public final class RangeDiskCache implements AutoCloseable {
    public static final long DEFAULT_LIMIT=2L*1024*1024*1024;
    private record Key(Object owner,ByteRange range) { }
    private record FileEntry(Path path,int bytes) { }
    private final LinkedHashMap<Key,FileEntry> files=new LinkedHashMap<>(16,0.75f,true);
    private Path directory;
    private long limit=DEFAULT_LIMIT,used,revision;
    private boolean closed;
    public synchronized long usedBytes() { return used; }
    public synchronized long limitBytes() { return limit; }
    synchronized long revision() { return revision; }
    public synchronized void limit(long bytes) {
        if(bytes<0 || bytes>8L*1024*1024*1024) throw new IllegalArgumentException("Invalid cache budget");
        limit=bytes; revision++; trim(0);
    }
    private boolean safeDirectory() {
        try { return directory!=null && directory.equals(directory.toRealPath()); } catch(IOException error) { return false; }
    }
    synchronized byte[] get(Object owner,ByteRange range) {
        if(closed || limit==0 || !safeDirectory()) return null;
        Key key=new Key(owner,range); FileEntry entry=files.get(key); if(entry==null) return null;
        try(var input=Files.newInputStream(entry.path(),LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(entry.bytes()+1);
            if(bytes.length==entry.bytes()) return bytes;
        } catch(IOException ignored) { }
        remove(key,entry); return null;
    }
    synchronized void put(Object owner,ByteRange range,byte[] bytes,long captured,BooleanSupplier valid) {
        if(closed || captured!=revision || !valid.getAsBoolean() || bytes.length!=range.length()
                || bytes.length<1 || bytes.length>4*1024*1024 || bytes.length>limit) return;
        Key key=new Key(owner,range); if(files.containsKey(key)) return;
        try {
            if(directory==null) directory=Files.createDirectory(Path.of(System.getProperty("java.io.tmpdir")).toRealPath().resolve("saltneri-audio-cache-"+UUID.randomUUID()));
            if(!safeDirectory() || !trim(bytes.length) || !valid.getAsBoolean()) return;
            var entry=new FileEntry(directory.resolve(UUID.randomUUID()+".range"),bytes.length);
            // Account reserved bytes before writing; failed deletion must never pretend disk space was freed.
            files.put(key,entry); used+=entry.bytes();
            try { Files.write(entry.path(),bytes,StandardOpenOption.CREATE_NEW); }
            catch(IOException error) { remove(key,entry); }
        } catch(IOException ignored) { /* Cache failures must not interrupt a valid audio stream. */ }
    }
    private boolean trim(long incoming) {
        for(var entry:List.copyOf(files.entrySet())) {
            if(used+incoming<=limit && files.size()<8192) return true;
            if(!remove(entry.getKey(),entry.getValue())) return false;
        }
        return used+incoming<=limit;
    }
    private boolean remove(Key key,FileEntry entry) {
        if(!safeDirectory()) return false;
        try {
            Files.deleteIfExists(entry.path());
            if(files.remove(key,entry)) used-=entry.bytes();
            return true;
        } catch(IOException error) { return false; }
    }
    synchronized void revoke(Object owner) {
        for(var entry:List.copyOf(files.entrySet())) if(entry.getKey().owner()==owner) remove(entry.getKey(),entry.getValue());
    }
    public synchronized void clear() {
        revision++;
        for(var entry:List.copyOf(files.entrySet())) remove(entry.getKey(),entry.getValue());
    }
    @Override public synchronized void close() {
        closed=true; clear();
        try { if(safeDirectory() && files.isEmpty()) Files.deleteIfExists(directory); } catch(IOException ignored) { }
    }
}
