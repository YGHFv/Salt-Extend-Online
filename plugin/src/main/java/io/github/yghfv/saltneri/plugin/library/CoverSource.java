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
        URI bili = parse(AccountPlatform.BILIBILI, source.toString());
        return bili != null ? bili : parse(AccountPlatform.NETEASE, source.toString());
    }
    public static URI parse(AccountPlatform platform, String value) {
        if (value == null || value.isEmpty() || value.length() > 2048) return null;
        try {
            value = value.trim();
            URI uri = URI.create(value.startsWith("//") ? "https:" + value : value);
            if (uri.getScheme() == null || uri.getHost() == null || !Set.of("https", "http").contains(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getRawQuery() != null && !(platform == AccountPlatform.NETEASE && uri.getRawQuery().matches("param=[0-9]{1,4}y[0-9]{1,4}"))
                    || uri.getPort() != -1 || uri.getRawPath() == null || uri.getRawPath().isEmpty()
                    || !uri.getRawPath().matches("/[A-Za-z0-9/_.@%=-]+")) return null;
            boolean allowed = platform == AccountPlatform.BILIBILI
                    ? Set.of("i0.hdslb.com", "i1.hdslb.com", "i2.hdslb.com", "i3.hdslb.com", "i0.biliimg.com", "i1.biliimg.com", "i2.biliimg.com").contains(uri.getHost())
                    : Set.of("p1.music.126.net", "p2.music.126.net", "p3.music.126.net", "p4.music.126.net").contains(uri.getHost());
            String path = uri.getRawPath();
            int at = path.lastIndexOf('@');
            if (platform == AccountPlatform.BILIBILI && at > path.lastIndexOf('/')) path = path.substring(0, at);
            return allowed ? URI.create("https://" + uri.getHost() + path) : null;
        } catch (IllegalArgumentException error) { return null; }
    }
}
