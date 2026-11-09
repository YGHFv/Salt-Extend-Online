package io.github.yghfv.saltneri.plugin.qqmusic;

public record QqMusicTarget(String mid) {
    public QqMusicTarget {
        if(mid==null || !mid.matches("[A-Za-z0-9]{14}")) throw new IllegalArgumentException("Invalid QQ song MID");
    }
    public static QqMusicTarget parse(String mid) { return new QqMusicTarget(mid); }
}
