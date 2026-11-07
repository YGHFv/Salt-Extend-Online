package io.github.yghfv.saltneri.plugin.host;

import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.netease.NeteaseLyricsText;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Exact, revocable native-item bindings. No title search, global account, disk cache or eager HTTP. */
public final class OnlineLyrics {
    @FunctionalInterface public interface Source { String load(RequestScope request) throws IOException; }
    private static final Map<String,Binding> ITEMS=new ConcurrentHashMap<>();
    private record Result(String text) { }
    private static final LinkedHashMap<Binding,Result> CACHE=new LinkedHashMap<>(16,0.75f,true);
    private OnlineLyrics() { }
    public static Binding bind(String id,String path,BooleanSupplier active,Source source) {
        if(!id.startsWith("saltneri:") || source==null) throw new IllegalArgumentException("Invalid lyric binding");
        Binding binding=new Binding(id,path,active,source);
        if(ITEMS.putIfAbsent(id,binding)!=null) throw new IllegalArgumentException("Duplicate lyric binding");
        return binding;
    }
    public static String load(String id,String path) {
        if(id==null || path==null) return null;
        Binding binding=ITEMS.get(id);
        return binding==null ? null : binding.load(path);
    }
    public static final class Binding implements AutoCloseable {
        private final String id,originalPath;
        private final BooleanSupplier active;
        private final Source source;
        private final Object loadLock=new Object();
        private volatile String currentPath;
        private volatile boolean closed;
        private volatile RequestScope request;
        private Binding(String id,String path,BooleanSupplier active,Source source) {
            this.id=id; this.originalPath=path; this.currentPath=path; this.active=active; this.source=source;
        }
        public void path(String path) { currentPath=Objects.requireNonNull(path); }
        private boolean valid() {
            try { return !closed && ITEMS.get(id)==this && active.getAsBoolean(); }
            catch(RuntimeException error) { return false; }
        }
        private String load(String path) {
            if(!path.equals(originalPath) && !path.equals(currentPath)) return null;
            synchronized(loadLock) {
                if(!valid()) { close(); return null; }
                Result hit; synchronized(CACHE) { hit=CACHE.get(this); }
                if(hit!=null) return valid()?hit.text():null;
                try(RequestScope scope=new RequestScope(this::valid,6000)) {
                    request=scope; scope.check(); String text=source.load(scope); scope.check();
                    if(text!=null && (text.length()>NeteaseLyricsText.MAX_CHARS || text.isBlank())) text=null;
                    synchronized(CACHE) {
                        if(closed) return null;
                        CACHE.put(this,new Result(text));
                        while(CACHE.size()>32) CACHE.remove(CACHE.keySet().iterator().next());
                    }
                    return valid()?text:null;
                } catch(IOException | RuntimeException error) { return null; }
                finally { request=null; }
            }
        }
        @Override public void close() {
            closed=true; ITEMS.remove(id,this);
            RequestScope pending=request; if(pending!=null) pending.close();
            synchronized(CACHE) { CACHE.remove(this); }
        }
    }
}
