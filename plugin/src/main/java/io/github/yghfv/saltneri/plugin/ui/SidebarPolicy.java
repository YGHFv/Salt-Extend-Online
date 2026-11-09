package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.MutableState;
import androidx.compose.runtime.SnapshotStateKt;
import java.util.*;

/** One observable policy for the native sidebar, caption shortcuts and settings. */
public final class SidebarPolicy {
    public static final List<String> BUTTONS=List.of("Track","Genre","Album","Artist","Folder","MusicLibrary",
            "SoundEffects","Settings","NewPlaylist","ImportPlaylist","BILIBILI","NETEASE","QQMUSIC","APPLEMUSIC","KUGOU");
    private record Settings(boolean optimized,boolean hidden,boolean hideToggle,Set<String> invisible) { }
    private final MutableState<Settings> settings=SnapshotStateKt.mutableStateOf(
            new Settings(false,false,false,Set.of()),SnapshotStateKt.structuralEqualityPolicy());
    public void update(boolean optimized,boolean hidden,Set<String> invisible) {
        update(optimized,hidden,false,invisible);
    }
    public void update(boolean optimized,boolean hidden,boolean hideToggle,Set<String> invisible) {
        if(!BUTTONS.containsAll(invisible)) throw new IllegalArgumentException("Unknown sidebar button");
        settings.setValue(new Settings(optimized,hidden,hideToggle,Set.copyOf(invisible)));
    }
    public boolean optimized() {return settings.getValue().optimized();}
    public boolean hidden() {var s=settings.getValue();return s.optimized() && s.hidden();}
    public boolean hideToggle() {return settings.getValue().hideToggle();}
    public boolean visible(String button) {return "Settings".equals(button) || !settings.getValue().invisible().contains(button);}
}
