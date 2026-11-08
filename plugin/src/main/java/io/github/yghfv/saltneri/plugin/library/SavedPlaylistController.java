package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.host.SaltPlaylistGateway.Choice;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Asynchronous native DB access. Opening a menu never resolves audio or changes a queue. */
public final class SavedPlaylistController implements AutoCloseable {
    public interface Gateway {
        List<Choice> choices() throws Exception;
        void add(OnlinePlaybackController.Target target,Catalog.Item row,Choice choice,BooleanSupplier valid) throws Exception;
    }
    public record Snapshot(List<Choice> choices,boolean loading,String status) { }
    private final Gateway gateway;
    private final BooleanSupplier active;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"salt-online-saved-playlists");t.setDaemon(true);return t;});
    private volatile Snapshot state=new Snapshot(List.of(),false,"");
    private volatile boolean closed;
    public SavedPlaylistController(Gateway gateway,BooleanSupplier active) {this.gateway=gateway;this.active=active;}
    public Snapshot snapshot() {return state;}
    private boolean valid() {return !closed && active.getAsBoolean();}
    public synchronized void load() {
        if(!valid() || state.loading()) return;
        state=new Snapshot(List.of(),true,"");
        worker.submit(()->{
            try { var choices=gateway.choices(); if(valid()) state=new Snapshot(List.copyOf(choices),false,""); }
            catch(Exception failure) {if(valid()) state=new Snapshot(List.of(),false,"\u6b4c\u5355\u8bfb\u53d6\u5931\u8d25\uff0c\u8bf7\u91cd\u65b0\u6253\u5f00\u83dc\u5355");}
        });
    }
    public synchronized void add(OnlinePlaybackController.Target target,Catalog.Item row,String id,BooleanSupplier selectionValid) {
        if(!valid() || state.loading()) throw new IllegalStateException("Playlist operation unavailable");
        Choice choice=state.choices().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(()->new IllegalStateException("Stale playlist"));
        state=new Snapshot(state.choices(),true,"\u6b63\u5728\u6dfb\u52a0\u5230\u6b4c\u5355");
        worker.submit(()->{
            try {
                BooleanSupplier valid=()->valid() && selectionValid.getAsBoolean();
                if(!valid.getAsBoolean()) throw new CancellationException();
                gateway.add(target,row,choice,valid);
                if(valid()) state=new Snapshot(state.choices(),false,"\u5df2\u6dfb\u52a0\u5230\u6b4c\u5355");
            } catch(Exception failure) {if(valid()) state=new Snapshot(state.choices(),false,"\u672a\u6dfb\u52a0\u5230\u6b4c\u5355\uff0c\u8bf7\u68c0\u67e5\u6b4c\u5355\u548c\u6388\u6743\u540e\u91cd\u8bd5");}
        });
    }
    @Override public synchronized void close() {closed=true;worker.shutdownNow();state=new Snapshot(List.of(),false,"");}
}
