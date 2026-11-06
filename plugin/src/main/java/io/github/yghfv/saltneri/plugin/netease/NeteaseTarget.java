package io.github.yghfv.saltneri.plugin.netease;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Exact song identity, never a CDN, playlist, short link or unchecked browser URL. */
public record NeteaseTarget(long songId) {
    public NeteaseTarget { if (songId <= 0) throw invalid(); }
    public static NeteaseTarget parse(String input) {
        if (input == null || input.length() > 4096) throw invalid();
        String text = input.trim(); if (text.matches("[1-9][0-9]{0,18}")) return id(text);
        try {
            URI uri = URI.create(text);
            if (!Set.of("http", "https").contains(uri.getScheme()) || !"music.163.com".equals(uri.getHost())
                    || uri.getPort() != -1 || uri.getRawUserInfo() != null) throw invalid();
            URI route = uri;
            if (uri.getRawFragment() != null) {
                if (!Set.of("", "/").contains(uri.getPath()) || uri.getRawQuery() != null) throw invalid();
                route = URI.create(uri.getRawFragment());
                if (route.getRawAuthority() != null || route.isAbsolute()) throw invalid();
            }
            if (!Set.of("/song", "/song/").contains(route.getPath()) || route.getRawFragment() != null || route.getRawQuery() == null) throw invalid();
            String value = null;
            for (String pair : route.getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                if (URLDecoder.decode(parts[0], StandardCharsets.UTF_8).equals("id")) {
                    if (value != null || parts.length != 2) throw invalid();
                    value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                }
            }
            return id(value);
        } catch (IllegalArgumentException | NullPointerException failure) { throw invalid(); }
    }
    private static NeteaseTarget id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try { return new NeteaseTarget(Long.parseLong(value)); } catch (NumberFormatException failure) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Use a positive NetEase song ID or official song URL"); }
}
