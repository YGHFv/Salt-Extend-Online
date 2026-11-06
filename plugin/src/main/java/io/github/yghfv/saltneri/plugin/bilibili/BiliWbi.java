package io.github.yghfv.saltneri.plugin.bilibili;

/* WBI policy adapted from NeriPlayer, Copyright (C) 2025-2025 NeriPlayer developers.
 * GPL-3.0-or-later. Pinned sources and license details: THIRD_PARTY_NOTICES.md. */

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

public final class BiliWbi {
    private static final int[] MIXIN = {
        46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,
        27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,
        37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,
        22,25,54,21,56,62,6,63,57,20,34,52,59,11,36,44
    };
    private BiliWbi() { }
    public static String mixin(String imageUrl, String subUrl) {
        String raw = key(imageUrl) + key(subUrl); StringBuilder result = new StringBuilder();
        for (int index : MIXIN) if (index < raw.length() && result.length() < 32) result.append(raw.charAt(index));
        if (result.isEmpty()) throw new IllegalArgumentException("Invalid WBI key");
        return result.toString();
    }
    private static String key(String url) {
        URI uri = URI.create(url); String path = uri.getPath();
        if (path == null) throw new IllegalArgumentException("Invalid WBI key");
        String key = path.substring(path.lastIndexOf('/') + 1).split("\\.", 2)[0];
        if (!key.matches("[a-zA-Z0-9]{1,64}")) throw new IllegalArgumentException("Invalid WBI key");
        return key;
    }
    public static URI sign(String endpoint, Map<String, String> values, String mixin, long epochSeconds) {
        if (epochSeconds < 0 || mixin == null || !mixin.matches("[a-zA-Z0-9]{1,32}")) throw new IllegalArgumentException("Invalid WBI inputs");
        TreeMap<String, String> sorted = new TreeMap<>();
        values.forEach((key, value) -> {
            if (!key.matches("[a-zA-Z0-9_]+") || key.equals("w_rid") || key.equals("wts")) throw new IllegalArgumentException("Invalid WBI parameter");
            sorted.put(key, value.replaceAll("[!'()*]", ""));
        });
        sorted.put("wts", Long.toString(epochSeconds));
        String query = sorted.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest((query + mixin).getBytes(StandardCharsets.UTF_8)));
            return URI.create(endpoint + "?" + query + "&w_rid=" + digest);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("WBI digest unavailable"); }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
}
