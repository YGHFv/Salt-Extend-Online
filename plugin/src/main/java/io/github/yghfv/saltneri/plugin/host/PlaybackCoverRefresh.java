package io.github.yghfv.saltneri.plugin.host;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Recheck the actual playing identity on the UI thread, not a queued metadata update. */
public final class PlaybackCoverRefresh {
    public record Selection(String id,String path) { }
    public interface Host {
        Selection selection() throws ReflectiveOperationException;
        void refresh() throws ReflectiveOperationException;
    }
    private final Host host;
    private final Executor ui;
    public PlaybackCoverRefresh(Host host,Executor ui) {this.host=host;this.ui=ui;}
    public void changed(String id,String path,BooleanSupplier valid) {
        Selection expected=new Selection(id,path);
        ui.execute(()->{
            try {
                if(valid.getAsBoolean() && expected.equals(host.selection()) && valid.getAsBoolean())host.refresh();
            } catch(ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
        });
    }
}
