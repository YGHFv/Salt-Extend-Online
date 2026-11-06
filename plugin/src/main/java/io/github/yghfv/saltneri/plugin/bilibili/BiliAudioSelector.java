package io.github.yghfv.saltneri.plugin.bilibili;

/* Audio quality and CDN ordering adapted from NeriPlayer, Copyright (C) 2025-2025
 * NeriPlayer developers. GPL-3.0-or-later; see THIRD_PARTY_NOTICES.md. */

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public final class BiliAudioSelector {
    public enum Quality {
        DOLBY(0), HIRES(1000), LOSSLESS(500), HIGH(180), MEDIUM(120), LOW(60);
        final int minimum;
        Quality(int minimum) { this.minimum = minimum; }
        public static Quality fromKey(String key) {
            try { return valueOf(key.trim().toUpperCase(Locale.ROOT)); } catch (RuntimeException failure) { return HIGH; }
        }
    }
    public record Audio(int id, String mimeType, String codec, int bitrateKbps, String tag, List<URI> candidates) {
        public Audio { candidates = List.copyOf(candidates); }
        @Override public String toString() { return "BiliAudio[" + id + ", " + bitrateKbps + " kbps, URLs redacted]"; }
    }
    private BiliAudioSelector() { }
    public static List<URI> candidates(List<String> urls) {
        return urls.stream().map(BiliAudioSelector::normalize).filter(java.util.Objects::nonNull).distinct()
                .sorted(Comparator.comparingInt((URI uri) -> uri.getHost().toLowerCase(Locale.ROOT).startsWith("upos-") ? 3 : 2).reversed()).toList();
    }
    static URI normalize(String text) {
        try {
            String cleaned = text.trim(); if (cleaned.startsWith("//")) cleaned = "https:" + cleaned;
            URI uri = URI.create(cleaned); String host = uri.getHost();
            if (host == null || !(under(host, "bilivideo.com") || under(host, "bilivideo.cn"))
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.toASCIIString().length() > 16384) return null;
            if ("http".equalsIgnoreCase(uri.getScheme()) && (uri.getPort() == -1 || uri.getPort() == 80)) {
                uri = URI.create("https://" + host + (uri.getRawPath() == null ? "" : uri.getRawPath())
                        + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
            }
            if (!"https".equalsIgnoreCase(uri.getScheme()) || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
            return uri;
        } catch (IllegalArgumentException failure) { return null; }
    }
    static boolean under(String host, String domain) { String lower = host.toLowerCase(Locale.ROOT); return lower.equals(domain) || lower.endsWith("." + domain); }
    public static Audio select(List<Audio> available, Quality preference) {
        Comparator<Audio> bitrate = Comparator.comparingInt(Audio::bitrateKbps).reversed();
        List<Audio> regular = available.stream().filter(a -> !a.candidates().isEmpty() && tag(a).isEmpty()).sorted(bitrate).toList();
        List<Audio> tagged = available.stream().filter(a -> !a.candidates().isEmpty() && !tag(a).isEmpty()).sorted(bitrate).toList();
        LinkedHashMap<URI, Audio> dedup = new LinkedHashMap<>();
        List<Audio> joined = new ArrayList<>(regular); joined.addAll(tagged);
        for (Audio audio : joined) if (!audio.candidates().isEmpty()) dedup.putIfAbsent(audio.candidates().getFirst(), audio);
        List<Audio> sorted = List.copyOf(dedup.values());
        for (int index = preference.ordinal(); index < Quality.values().length; index++) {
            Quality quality = Quality.values()[index];
            Audio hit = sorted.stream().filter(a -> switch (quality) {
                case DOLBY -> tag(a).equals("dolby");
                case HIRES -> tag(a).equals("hires");
                case LOSSLESS -> lossless(a);
                default -> false;
            }).findFirst().orElse(null);
            if (hit != null) return hit;
            if (quality.ordinal() >= Quality.LOSSLESS.ordinal()) {
                int upper = Quality.values()[index - 1].minimum;
                hit = regular.stream().filter(a -> a.bitrateKbps() >= quality.minimum && a.bitrateKbps() < upper).findFirst().orElse(null);
                if (hit != null) return hit;
            }
        }
        return sorted.isEmpty() ? null : sorted.getFirst();
    }
    private static String tag(Audio audio) { return audio.tag() == null ? "" : audio.tag().trim().toLowerCase(Locale.ROOT); }
    private static boolean lossless(Audio audio) {
        String mime = audio.mimeType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return tag(audio).equals("hires") || tag(audio).equals("lossless") || mime.equals("audio/flac") || mime.equals("audio/x-flac");
    }
}
