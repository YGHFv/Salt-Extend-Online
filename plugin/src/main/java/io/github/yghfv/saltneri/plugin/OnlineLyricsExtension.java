package io.github.yghfv.saltneri.plugin;

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint;
import io.github.yghfv.saltneri.plugin.host.OnlineLyrics;

/** PF4J registration is explicit in META-INF/extensions.idx for both build paths. */
@org.pf4j.Extension
public final class OnlineLyricsExtension implements PlaybackExtensionPoint {
    @Override public String onBeforeLoadLyrics(MediaItem item) {
        return item==null ? null : OnlineLyrics.load(item.getId(),item.getPath());
    }
}
