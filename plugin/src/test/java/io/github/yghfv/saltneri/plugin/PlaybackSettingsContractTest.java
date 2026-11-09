package io.github.yghfv.saltneri.plugin;

import io.github.yghfv.saltneri.plugin.bilibili.BiliAudioSelector;
import io.github.yghfv.saltneri.plugin.netease.NeteaseClient;

public final class PlaybackSettingsContractTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if(!ok) throw new AssertionError("Settings "+checks); }
    public static void main(String[] args) {
        for(String mode:java.util.List.of("fit","center","left","right"))
            check(PlaybackSettings.parse("high","standard","2048",mode).coverMode().name().equals(mode.toUpperCase(java.util.Locale.ROOT)));
        check(PlaybackSettings.parse("high","standard","2048","invalid").coverMode()==io.github.yghfv.saltneri.plugin.ui.CoverMode.FIT);
        check(PlaybackSettings.DEFAULT.cacheBytes()==2L*1024*1024*1024);
        check(!PlaybackSettings.DEFAULT.neteaseAutoSourceSwitch());
        check(!PlaybackSettings.DEFAULT.qqmusicAutoSourceSwitch());
        for(var q:io.github.yghfv.saltneri.plugin.qqmusic.QqMusicClient.Quality.values()) {
            check(io.github.yghfv.saltneri.plugin.qqmusic.QqMusicClient.Quality.fromKey(q.key())==q);
            var qq=new PlaybackSettings(PlaybackSettings.DEFAULT.bili(),PlaybackSettings.DEFAULT.netease(),PlaybackSettings.DEFAULT.cacheBytes(),PlaybackSettings.DEFAULT.coverMode(),false,q,true);
            check(!qq.qualityKey().equals(PlaybackSettings.DEFAULT.qualityKey()));
        }
        var original=PlaybackSettings.DEFAULT;
        var automatic=new PlaybackSettings(original.bili(),original.netease(),original.cacheBytes(),original.coverMode(),true);
        check(!automatic.qualityKey().equals(original.qualityKey()));
        check(PlaybackSettings.parse(null,null,null).equals(PlaybackSettings.DEFAULT));
        for(String bad:new String[]{"-1","2147483648","999999999999999999","2g",""})
            check(PlaybackSettings.parse("bad","bad",bad).equals(PlaybackSettings.DEFAULT));
        for(String mb:new String[]{"0","512","1024","2048","4096","8192"})
            check(PlaybackSettings.parse("high","standard",mb).cacheBytes()==Long.parseLong(mb)*PlaybackSettings.MIB);
        for(var bili:BiliAudioSelector.Quality.values()) for(var net:NeteaseClient.Quality.values()) {
            var settings=PlaybackSettings.parse(bili.name().toLowerCase(java.util.Locale.ROOT),net.key(),"2048");
            check(settings.bili()==bili && settings.netease()==net);
            check(settings.qualityKey().equals(PlaybackSettings.parse(bili.name(),net.key(),"0").qualityKey()));
        }
        System.out.println("PASS: "+checks+" quality keys/cache sizes/defaults/invalid preference checks; offline");
    }
}
