package io.github.yghfv.saltneri.plugin.host;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.library.Catalog;
import io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.Target;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Durable logical targets only: never cookies, account identities or expiring media URLs. */
public final class SavedTrackStore implements AutoCloseable {
    public static final class Entry {
        private final String id,title;
        private final Target target;
        private volatile String artist;
        private final long duration;
        private final PlaybackCoverFile cover;
        public Entry(String id,Target target,String title,String artist,long duration,PlaybackCoverFile cover) {
            this.id=id;this.target=target;this.title=title;this.artist=artist;this.duration=duration;this.cover=cover;
        }
        public String id(){return id;}
        public Target target(){return target;}
        public String title(){return title;}
        public String artist(){return artist;}
        public long duration(){return duration;}
        public PlaybackCoverFile cover(){return cover;}
        public String trackId() { return "saltneri:saved:" + id; }
        public String path() { return cover.path(); }
    }
    private final Path root;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean closed;
    private record Record(String id,Target target,String title,String artist,long duration) { }
    public SavedTrackStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        root = directory.toRealPath();
        try(var files = Files.newDirectoryStream(root, "*.track")) {
            int count=0;
            for(Path file:files) {
                if(++count>10000) throw new IOException("Saved track limit exceeded");
                if(!file.getFileName().toString().matches("[a-f0-9]{64}\\.track") || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                        || !file.toRealPath().equals(file) || Files.size(file)>32768) continue;
                try(var in=new DataInputStream(Files.newInputStream(file))) {
                    if(in.readInt()!=0x53454f31) continue;
                    Target target=canonical(new Target(AccountPlatform.valueOf(in.readUTF()),in.readUTF()));
                    String id=id(target), title=text(in.readUTF()), artist=text(in.readUTF()); long duration=in.readLong();
                    if(!file.getFileName().toString().equals(id+".track") || duration<0 || duration>86400000 || in.read()!=-1) continue;
                    entries.put(id,new Entry(id,target,title,artist,duration,PlaybackCoverFile.persistent(root,id)));
                } catch(IOException | IllegalArgumentException ignored) { /* Isolate a damaged entry; retain other playlists. */ }
            }
        }
        // Immutable, atomic batches avoid thousands of small file writes before the first song.
        try(var files=Files.newDirectoryStream(root,"*.tracks")) {
            int filesSeen=0;
            for(Path file:files) {
                if(++filesSeen>10000) throw new IOException("Saved batch limit exceeded");
                if(!file.getFileName().toString().matches("[a-f0-9-]{36}\\.tracks") || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                        || !file.toRealPath().equals(file) || Files.size(file)>(32768L+12)*10000+8) continue;
                var batch=new LinkedHashMap<String,Record>();
                try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                    if(in.readInt()!=0x53454f32) continue;
                    int count=in.readInt(); if(count<1 || count>10000) throw new IOException("Saved record limit exceeded");
                    for(int i=0;i<count;i++) {
                        int length=in.readInt(); if(length<1 || length>32768) throw new IOException("Invalid saved record length");
                        byte[] bytes=in.readNBytes(length); if(bytes.length!=length) throw new EOFException();
                        var crc=new java.util.zip.CRC32();crc.update(bytes);
                        if(in.readLong()!=crc.getValue()) throw new IOException("Invalid saved record checksum");
                        try(var record=new DataInputStream(new ByteArrayInputStream(bytes))) {
                            Target target=canonical(new Target(AccountPlatform.valueOf(record.readUTF()),record.readUTF()));
                            var value=new Record(id(target),target,text(record.readUTF()),text(record.readUTF()),record.readLong());
                            if(value.duration()<0 || value.duration()>86400000 || record.read()!=-1 || batch.put(value.id(),value)!=null)
                                throw new IOException("Invalid saved record");
                        }
                    }
                    if(in.read()!=-1 || entries.size()+batch.keySet().stream().filter(id->!entries.containsKey(id)).count()>10000)
                        throw new IOException("Invalid saved batch");
                } catch(IOException | IllegalArgumentException ignored) { continue; }
                for(var value:batch.values()) {
                    Entry existing=entries.get(value.id());
                    if(existing==null) entries.put(value.id(),entry(value));
                    else if(existing.artist().isBlank() && !value.artist().isBlank()) existing.artist=value.artist();
                }
            }
        }
    }
    public synchronized List<Entry> entries() { return List.copyOf(entries.values()); }
    public synchronized boolean owns(String trackId,String path) {
        if(closed || trackId==null || !trackId.startsWith("saltneri:saved:")) return false;
        Entry entry=entries.get(trackId.substring("saltneri:saved:".length()));
        return entry!=null && entry.path().equals(path);
    }
    public synchronized Entry save(Target input, Catalog.Item row) throws IOException {
        if(closed) throw new IOException("Saved tracks closed");
        if(row.kind()!=Catalog.Kind.TRACK || !row.enabled()) throw new IllegalArgumentException("Unplayable row");
        Target target=canonical(input); String id=id(target);
        if(entries.containsKey(id)) {
            Entry existing=entries.get(id);
            if(existing.artist().isBlank() && !text(row.subtitle()).isBlank())
                saveAll(target.platform(),List.of(new Catalog.Item(Catalog.Kind.TRACK,target.id(),existing.title(),row.subtitle(),existing.duration(),true)),()->true);
            return existing;
        }
        if(entries.size()>=10000) throw new IOException("Saved track limit reached");
        String title=text(row.title()), artist=text(row.subtitle()); long duration=Math.max(0,Math.min(86400000,row.count()));
        Path file=root.resolve(id+".track"), next=root.resolve(UUID.randomUUID()+".tmp");
        if(!root.toRealPath().equals(root)) throw new IOException("Saved directory changed");
        PlaybackCoverFile cover=PlaybackCoverFile.persistent(root,id);
        try {
            try(var out=new DataOutputStream(Files.newOutputStream(next,StandardOpenOption.CREATE_NEW))) {
                out.writeInt(0x53454f31); out.writeUTF(target.platform().name()); out.writeUTF(target.id());
                out.writeUTF(title); out.writeUTF(artist); out.writeLong(duration);
            }
            Files.move(next,file,StandardCopyOption.ATOMIC_MOVE);
            Entry entry=new Entry(id,target,title,artist,duration,cover); entries.put(id,entry); return entry;
        } finally { Files.deleteIfExists(next); }
    }
    /** Publish durable identities as one transaction, before a native queue can reference them. */
    public synchronized List<Entry> saveAll(AccountPlatform platform,List<Catalog.Item> rows,java.util.function.BooleanSupplier active) throws IOException {
        if(closed) throw new IOException("Saved tracks closed");
        if(rows.isEmpty() || rows.size()>10000) throw new IllegalArgumentException("Invalid playlist size");
        var ids=new ArrayList<String>(rows.size());var pending=new LinkedHashMap<String,Record>();
        for(var row:rows) {
            check(active);
            if(row.kind()!=Catalog.Kind.TRACK || !row.enabled()) throw new IllegalArgumentException("Unplayable row");
            Target target=canonical(new Target(platform,row.target()));String id=id(target);ids.add(id);
            Entry existing=entries.get(id);
            if(existing==null && !pending.containsKey(id)) pending.put(id,new Record(id,target,text(row.title()),text(row.subtitle()),Math.max(0,Math.min(86400000,row.count()))));
            else if(existing!=null && existing.artist().isBlank() && !text(row.subtitle()).isBlank())
                pending.putIfAbsent(id,new Record(id,target,existing.title(),row.subtitle(),existing.duration()));
        }
        if(entries.size()+pending.keySet().stream().filter(id->!entries.containsKey(id)).count()>10000) throw new IOException("Saved track limit reached");
        if(!pending.isEmpty()) {
            if(!root.toRealPath().equals(root)) throw new IOException("Saved directory changed");
            Path next=root.resolve(UUID.randomUUID()+".tmp"),file=root.resolve(UUID.randomUUID()+".tracks");
            var added=new LinkedHashMap<String,Entry>();
            boolean committed=false;
            try {
                try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(next,StandardOpenOption.CREATE_NEW)))) {
                    out.writeInt(0x53454f32);out.writeInt(pending.size());
                    for(var value:pending.values()) {
                        check(active);
                        var buffer=new ByteArrayOutputStream();
                        try(var record=new DataOutputStream(buffer)) {
                            record.writeUTF(value.target().platform().name());record.writeUTF(value.target().id());
                            record.writeUTF(value.title());record.writeUTF(value.artist());record.writeLong(value.duration());
                        }
                        byte[] bytes=buffer.toByteArray();if(bytes.length>32768) throw new IOException("Saved metadata too large");
                        var crc=new java.util.zip.CRC32();crc.update(bytes);
                        out.writeInt(bytes.length);out.write(bytes);out.writeLong(crc.getValue());
                        if(!entries.containsKey(value.id())) added.put(value.id(),entry(value));
                    }
                }
                check(active);Files.move(next,file,StandardCopyOption.ATOMIC_MOVE);
                entries.putAll(added);
                for(var value:pending.values()) if(entries.get(value.id()).artist().isBlank()) entries.get(value.id()).artist=value.artist();
                committed=true;
            } finally {
                if(!committed) added.values().forEach(e->e.cover().close());
                Files.deleteIfExists(next);
            }
        }
        check(active);return ids.stream().map(entries::get).toList();
    }
    private Entry entry(Record value) throws IOException {
        return new Entry(value.id(),value.target(),value.title(),value.artist(),value.duration(),PlaybackCoverFile.reservePersistent(root,value.id()));
    }
    private static void check(java.util.function.BooleanSupplier active) {
        if(!active.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Playlist retired");
    }
    private static String text(String value) {
        if(value==null || value.length()>4096 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid saved metadata");
        return value;
    }
    private static Target canonical(Target target) {
        if(target.platform()==AccountPlatform.KUGOU)return new Target(target.platform(),io.github.yghfv.saltneri.plugin.kugou.KugouHttp.hash(target.id()));
        if(target.platform()==AccountPlatform.APPLEMUSIC)return new Target(target.platform(),io.github.yghfv.saltneri.core.TrackKey.appleMusic(target.id()).remoteId());
        if(target.platform()==AccountPlatform.QQMUSIC) return new Target(target.platform(),io.github.yghfv.saltneri.plugin.qqmusic.QqMusicTarget.parse(target.id()).mid());
        if(target.platform()==AccountPlatform.NETEASE) {
            long id=Long.parseLong(target.id()); if(id<=0) throw new IllegalArgumentException("Invalid song ID");
            return new Target(target.platform(),Long.toString(id));
        }
        var bili=io.github.yghfv.saltneri.plugin.bilibili.BiliTarget.parse(target.id());
        return new Target(AccountPlatform.BILIBILI,bili.page()==1?bili.id():"https://www.bilibili.com/video/"+bili.id()+"?p="+bili.page());
    }
    static boolean matches(Entry entry,Target target) {return target!=null && entry.target().equals(canonical(target));}
    private static String id(Target target) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((target.platform().name()+":"+target.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    @Override public synchronized void close() { closed=true; entries.values().forEach(e->e.cover().close()); entries.clear(); }
}
