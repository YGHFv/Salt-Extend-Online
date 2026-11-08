package io.github.yghfv.saltneri.plugin.transport;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Per-runtime, lease-scoped range cache. Only complete validated ranges, never URLs/cookies, reach disk. */
public final class RangeDiskCache implements AutoCloseable {
    public static final long DEFAULT_LIMIT=2L*1024*1024*1024;
    private static final long MAX_RANGE_BYTES=ByteRange.MAX_REQUEST_BYTES;
    private record Key(Object owner,ByteRange range) {
        @Override public boolean equals(Object value) { return value instanceof Key key && owner==key.owner && range.equals(key.range); }
        @Override public int hashCode() { return 31*System.identityHashCode(owner)+range.hashCode(); }
    }
    private record FileEntry(Path path,int bytes) { }
    private final LinkedHashMap<Key,FileEntry> files=new LinkedHashMap<>(16,0.75f,true);
    private final Map<Object,NavigableMap<Long,Set<Key>>> ranges=new IdentityHashMap<>();
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
    synchronized boolean contains(Object owner,ByteRange range) {
        if(closed || limit==0) return false;
        if(files.containsKey(new Key(owner,range))) return true;
        var index=ranges.get(owner);
        if(index==null)return false;
        for(var keys:index.subMap(Math.max(0,range.last()-MAX_RANGE_BYTES+1),true,range.first(),true).values())
            for(Key key:keys)if(key.range().last()>=range.last())return true;
        return false;
    }
    synchronized byte[] get(Object owner,ByteRange range) {
        if(closed || limit==0 || !safeDirectory()) return null;
        Key key=new Key(owner,range); FileEntry entry=files.get(key);
        if(entry==null) {
            // A prefetched prefix can satisfy the smaller ranges Salt reads from that same lease.
            var index=ranges.get(owner);
            if(index==null || range.length()>MAX_RANGE_BYTES) return null;
            // Only same-lease starts within one maximum-sized block can contain this request.
            search: for(var keys:index.subMap(Math.max(0,range.last()-MAX_RANGE_BYTES+1),true,range.first(),true).descendingMap().values())
                for(Key candidate:keys) if(candidate.range().last()>=range.last()) { key=candidate; break search; }
            entry=files.get(key); if(entry==null) return null;
        }
        try(var input=Files.newInputStream(entry.path(),LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(entry.bytes()+1);
            if(bytes.length==entry.bytes()) return key.range().equals(range)?bytes:
                    Arrays.copyOfRange(bytes,(int)(range.first()-key.range().first()),(int)(range.last()-key.range().first()+1));
        } catch(IOException ignored) { }
        remove(key,entry); return null;
    }
    synchronized void put(Object owner,ByteRange range,byte[] bytes,long captured,BooleanSupplier valid) {
        if(closed || captured!=revision || !valid.getAsBoolean() || bytes.length!=range.length()
                || bytes.length<1 || bytes.length>MAX_RANGE_BYTES || bytes.length>limit) return;
        Key key=new Key(owner,range); if(files.containsKey(key)) return;
        try {
            if(directory==null) directory=Files.createDirectory(Path.of(System.getProperty("java.io.tmpdir")).toRealPath().resolve("saltneri-audio-cache-"+UUID.randomUUID()));
            if(!safeDirectory() || !trim(bytes.length) || !valid.getAsBoolean()) return;
            var entry=new FileEntry(directory.resolve(UUID.randomUUID()+".range"),bytes.length);
            // Account reserved bytes before writing; failed deletion must never pretend disk space was freed.
            files.put(key,entry); used+=entry.bytes();
            ranges.computeIfAbsent(owner,ignored->new TreeMap<>()).computeIfAbsent(range.first(),ignored->new LinkedHashSet<>()).add(key);
            try { Files.write(entry.path(),bytes,StandardOpenOption.CREATE_NEW); }
            catch(IOException error) { remove(key,entry); }
        } catch(IOException ignored) { /* Cache failures must not interrupt a valid audio stream. */ }
    }
    private boolean trim(long incoming) {
        while(!files.isEmpty() && (used+incoming>limit || incoming>0 && files.size()>=8192)) {
            var entry=files.entrySet().iterator().next();
            if(!remove(entry.getKey(),entry.getValue())) return false;
        }
        return used+incoming<=limit;
    }
    private boolean remove(Key key,FileEntry entry) {
        if(!safeDirectory()) return false;
        try {
            Files.deleteIfExists(entry.path());
            if(files.remove(key,entry)) {
                used-=entry.bytes();
                var index=ranges.get(key.owner()); var keys=index.get(key.range().first());
                keys.remove(key); if(keys.isEmpty()) index.remove(key.range().first());
                if(index.isEmpty()) ranges.remove(key.owner());
            }
            return true;
        } catch(IOException error) { return false; }
    }
    synchronized void revoke(Object owner) {
        var index=ranges.get(owner); if(index==null) return;
        var keys=index.values().stream().flatMap(Set::stream).toList();
        for(Key key:keys) remove(key,files.get(key));
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
