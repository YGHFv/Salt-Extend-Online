package io.github.yghfv.saltneri.plugin.video;

import java.util.*;

/** Maximum preferred vertical resolution; never silently upgrade beyond the user's choice. */
public enum VideoResolution {
    P2160(2160), P1440(1440), P1080(1080), P720(720), P480(480), P360(360);
    public static final VideoResolution DEFAULT = P1080;
    private final int height;
    VideoResolution(int height) { this.height = height; }
    public int height() { return height; }
    public String key() { return Integer.toString(height); }
    public List<VideoResolution> fallback() {
        return Arrays.stream(values()).filter(value -> value.height <= height).toList();
    }
    public static VideoResolution parse(String key) {
        return Arrays.stream(values()).filter(value -> value.key().equals(key)).findFirst().orElse(DEFAULT);
    }
    public int biliQuality() {
        // Bilibili has no separate 1440 tier: request up to 4K, then cap the returned dimensions.
        return switch (this) { case P2160, P1440 -> 120; case P1080 -> 80;
            case P720 -> 64; case P480 -> 32; case P360 -> 16; };
    }
}
