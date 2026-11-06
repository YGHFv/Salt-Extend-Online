package io.github.yghfv.saltneri.plugin.bilibili;

import java.net.URI;
import java.util.Locale;

/** Direct identities only. Short-link expansion would need a separate strict redirect policy. */
public record BiliTarget(String id, int page) {
    public BiliTarget {
        if (id == null || !(id.matches("BV[0-9A-Za-z]{10}") || id.matches("av[1-9][0-9]{0,18}"))
                || page < 1 || page > 10000) throw invalid();
        if (id.startsWith("av")) { try { Long.parseLong(id.substring(2)); } catch (NumberFormatException e) { throw invalid(); } }
    }
    public boolean isBvid() { return id.startsWith("BV"); }
    public static BiliTarget parse(String input) {
        if (input == null || input.length() > 4096) throw invalid();
        String text = input.trim();
        if (text.matches("BV[0-9A-Za-z]{10}|av[1-9][0-9]{0,18}")) return new BiliTarget(text, 1);
        try {
            URI uri = URI.create(text);
            String host = uri.getHost();
            if (host == null || !java.util.Set.of("www.bilibili.com", "bilibili.com", "m.bilibili.com").contains(host.toLowerCase(Locale.ROOT))
                    || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getPort() != -1 || uri.getRawUserInfo() != null
                    || !uri.getRawPath().matches("/video/(BV[0-9A-Za-z]{10}|av[1-9][0-9]{0,18})/?")) throw invalid();
            String id = uri.getRawPath().split("/")[2];
            int page = 1; boolean found = false;
            if (uri.getRawQuery() != null) {
                for (String pair : uri.getRawQuery().split("&", -1)) {
                    String[] parts = pair.split("=", 2);
                    String key = java.net.URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8);
                    if (key.equals("p")) {
                        if (found || parts.length != 2 || !parts[1].matches("[1-9][0-9]{0,4}")) throw invalid();
                        page = Integer.parseInt(parts[1]); found = true;
                    }
                }
            }
            return new BiliTarget(id, page);
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Use a direct BV/av ID or Bilibili video URL with p"); }
}
