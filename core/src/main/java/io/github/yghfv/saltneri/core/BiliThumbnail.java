// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from NeriPlayer BiliImageUrl.kt, revision fd7fdb757821.
// Copyright (C) NeriPlayer developers. See THIRD_PARTY_NOTICES.md.
package io.github.yghfv.saltneri.core;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

public final class BiliThumbnail {
    private BiliThumbnail() {}

    public static String build(String imageUrl, int width, int height) {
        Objects.requireNonNull(imageUrl, "imageUrl");
        if (imageUrl.isBlank() || width <= 0 || height <= 0) return imageUrl;
        String normalized = imageUrl.trim();
        if (normalized.startsWith("//")) normalized = "https:" + normalized;
        String host;
        try {
            host = URI.create(normalized).getHost();
        } catch (IllegalArgumentException invalid) {
            return imageUrl;
        }
        if (host == null) return imageUrl;
        host = host.toLowerCase(Locale.ROOT);
        if (!host.endsWith(".hdslb.com") && !host.endsWith(".biliimg.com")) return imageUrl;

        int suffix = normalized.length();
        for (char delimiter : new char[]{'?', '#'}) {
            int index = normalized.indexOf(delimiter);
            if (index >= 0) suffix = Math.min(suffix, index);
        }
        String source = normalized.substring(0, suffix);
        int operation = source.lastIndexOf('@');
        if (operation > source.lastIndexOf('/')) source = source.substring(0, operation);
        return source + "@" + width + "w_" + height + "h_1c.webp" + normalized.substring(suffix);
    }
}
