package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import java.net.URI;
import java.util.Set;

/** Public image hosts only. Audio URLs, arbitrary hosts and credential-bearing queries are excluded. */
public final class CoverSource {
    private CoverSource() { }
    /** Remove only known CDN thumbnail transforms; never pass through arbitrary query parameters. */
    public static URI original(URI source) {
        if (source == null) return null;
        String value = source.toString();
        if (source.getRawQuery() != null && source.getRawQuery().matches("param=[0-9]{1,4}y[0-9]{1,4}")
                && source.getHost() != null && source.getHost().endsWith(".music.126.net")) value = value.substring(0, value.indexOf('?'));
        URI bili = parse(AccountPlatform.BILIBILI, value);
        if (bili != null) {
            String path = bili.getRawPath(); int at = path.lastIndexOf('@');
            return at > path.lastIndexOf('/') ? URI.create("https://" + bili.getHost() + path.substring(0, at)) : bili;
        }
        return parse(AccountPlatform.NETEASE, value);
    }
    public static URI parse(AccountPlatform platform, String value) {
        if (value == null || value.isEmpty() || value.length() > 2048) return null;
        try {
            URI uri = URI.create(value.startsWith("//") ? "https:" + value : value);
            if (uri.getScheme() == null || uri.getHost() == null || !Set.of("https", "http").contains(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getRawQuery() != null || uri.getPort() != -1 || uri.getRawPath() == null || uri.getRawPath().isEmpty()
                    || !uri.getRawPath().matches("/[A-Za-z0-9/_.@%=-]+")) return null;
            boolean allowed = platform == AccountPlatform.BILIBILI
                    ? Set.of("i0.hdslb.com", "i1.hdslb.com", "i2.hdslb.com").contains(uri.getHost())
                    : Set.of("p1.music.126.net", "p2.music.126.net", "p3.music.126.net", "p4.music.126.net").contains(uri.getHost());
            return allowed ? URI.create("https://" + uri.getHost() + uri.getRawPath()) : null;
        } catch (IllegalArgumentException error) { return null; }
    }
}
