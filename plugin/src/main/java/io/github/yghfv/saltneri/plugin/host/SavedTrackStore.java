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
    public record Entry(String id, Target target, String title, String artist, long duration, PlaybackCoverFile cover) {
        public String trackId() { return "saltneri:saved:" + id; }
        public String path() { return cover.path(); }
    }
    private final Path root;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean closed;
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
        if(entries.containsKey(id)) return entries.get(id);
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
    private static String text(String value) {
        if(value==null || value.length()>4096 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid saved metadata");
        return value;
    }
    private static Target canonical(Target target) {
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
