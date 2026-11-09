package io.github.yghfv.saltneri.core;

import java.util.Objects;
import java.util.regex.Pattern;

/** A logical identity, never an expiring CDN URL or a Salt database ID. */
public record TrackKey(Platform platform, String remoteId, long cid) {
    private static final Pattern BVID = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Pattern SONG_ID = Pattern.compile("[1-9][0-9]*");

    public enum Platform { NETEASE, BILIBILI, QQMUSIC, KUGOU }

    public TrackKey {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(remoteId, "remoteId");
        if(platform==Platform.KUGOU && (!remoteId.matches("[A-F0-9]{32}") || cid!=0))throw new IllegalArgumentException("Kugou requires an audio hash");
        if (platform == Platform.QQMUSIC && (!remoteId.matches("[A-Za-z0-9]{14}") || cid != 0)) {
            throw new IllegalArgumentException("QQ Music requires a song MID and no cid");
        }
        if (platform == Platform.NETEASE && (!SONG_ID.matcher(remoteId).matches() || cid != 0)) {
            throw new IllegalArgumentException("NetEase requires a positive song ID and no cid");
        }
        if (platform == Platform.BILIBILI && (!BVID.matcher(remoteId).matches() || cid <= 0)) {
            throw new IllegalArgumentException("Bilibili requires a BVID and resolved positive cid");
        }
    }

    public static TrackKey netease(String songId) {
        return new TrackKey(Platform.NETEASE, songId, 0);
    }

    public static TrackKey bilibili(String bvid, long cid) {
        return new TrackKey(Platform.BILIBILI, bvid, cid);
    }

    public static TrackKey qqmusic(String mid) { return new TrackKey(Platform.QQMUSIC, mid, 0); }
    public static TrackKey kugou(String hash) {return new TrackKey(Platform.KUGOU,hash.toUpperCase(java.util.Locale.ROOT),0);}

    public String stableId() {
        return switch (platform) {
            case NETEASE -> "netease:song:" + remoteId;
            case BILIBILI -> "bilibili:video:" + remoteId + ":cid:" + cid;
            case QQMUSIC -> "qqmusic:song:" + remoteId;
            case KUGOU -> "kugou:song:" + remoteId;
        };
    }
}
