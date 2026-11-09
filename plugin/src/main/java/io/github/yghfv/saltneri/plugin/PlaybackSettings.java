package io.github.yghfv.saltneri.plugin;

import io.github.yghfv.saltneri.plugin.bilibili.BiliAudioSelector;
import io.github.yghfv.saltneri.plugin.netease.NeteaseClient;
import java.util.Set;

/** Immutable settings snapshot; callers capture it once per resolution. */
public record PlaybackSettings(BiliAudioSelector.Quality bili, NeteaseClient.Quality netease, long cacheBytes, io.github.yghfv.saltneri.plugin.ui.CoverMode coverMode, boolean neteaseAutoSourceSwitch, io.github.yghfv.saltneri.plugin.qqmusic.QqMusicClient.Quality qqmusic, boolean qqmusicAutoSourceSwitch,String kugou) {
    public PlaybackSettings(BiliAudioSelector.Quality bili,NeteaseClient.Quality netease,long cacheBytes,io.github.yghfv.saltneri.plugin.ui.CoverMode coverMode,boolean auto,io.github.yghfv.saltneri.plugin.qqmusic.QqMusicClient.Quality qqmusic,boolean qqauto) {this(bili,netease,cacheBytes,coverMode,auto,qqmusic,qqauto,"128");}
    public PlaybackSettings {if(!Set.of("128","320","flac").contains(kugou))kugou="128";}
    public PlaybackSettings(BiliAudioSelector.Quality bili,NeteaseClient.Quality netease,long cacheBytes,io.github.yghfv.saltneri.plugin.ui.CoverMode coverMode,boolean neteaseAutoSourceSwitch,io.github.yghfv.saltneri.plugin.qqmusic.QqMusicClient.Quality qqmusic) {this(bili,netease,cacheBytes,coverMode,neteaseAutoSourceSwitch,qqmusic,false);}
    public PlaybackSettings(BiliAudioSelector.Quality bili,NeteaseClient.Quality netease,long cacheBytes,io.github.yghfv.saltneri.plugin.ui.CoverMode coverMode,boolean neteaseAutoSourceSwitch) {this(bili,netease,cacheBytes,coverMode,neteaseAutoSourceSwitch,io.github.yghfv.saltneri.plugin.qqmusic.QqMusicClient.Quality.STANDARD);}
    public PlaybackSettings(BiliAudioSelector.Quality bili,NeteaseClient.Quality netease,long cacheBytes,io.github.yghfv.saltneri.plugin.ui.CoverMode coverMode) {this(bili,netease,cacheBytes,coverMode,false);}
    public PlaybackSettings(BiliAudioSelector.Quality bili,NeteaseClient.Quality netease,long cacheBytes) { this(bili,netease,cacheBytes,io.github.yghfv.saltneri.plugin.ui.CoverMode.FIT); }
    public static final long MIB=1024L*1024;
    public static final PlaybackSettings DEFAULT=new PlaybackSettings(BiliAudioSelector.Quality.HIGH,NeteaseClient.Quality.STANDARD,2048*MIB);
    public static PlaybackSettings parse(String bili,String netease,String cacheMiB) {
        return parse(bili,netease,cacheMiB,"fit");
    }
    public static PlaybackSettings parse(String bili,String netease,String cacheMiB,String cover) {
        NeteaseClient.Quality quality;
        try { quality=NeteaseClient.Quality.fromKey(netease); } catch(RuntimeException error) { quality=DEFAULT.netease(); }
        long limit=DEFAULT.cacheBytes();
        if(cacheMiB!=null && Set.of("0","512","1024","2048","4096","8192").contains(cacheMiB)) limit=Long.parseLong(cacheMiB)*MIB;
        return new PlaybackSettings(BiliAudioSelector.Quality.fromKey(bili),quality,limit,io.github.yghfv.saltneri.plugin.ui.CoverMode.parse(cover));
    }
    public String qualityKey() { return bili.name()+"/"+netease.key()+"/"+coverMode.name()+"/auto="+neteaseAutoSourceSwitch+"/qq="+qqmusic.key()+"/qqauto="+qqmusicAutoSourceSwitch+"/kg="+kugou; }
}
