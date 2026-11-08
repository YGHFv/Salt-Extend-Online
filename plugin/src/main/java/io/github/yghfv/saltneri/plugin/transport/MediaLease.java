package io.github.yghfv.saltneri.plugin.transport;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** An already-authorized, fixed media resource, never a user-supplied proxy target. */
public final class MediaLease {
    public enum Platform { BILIBILI, NETEASE }
    private static final Set<String> HEADERS = Set.of("user-agent", "referer", "cookie");
    private final Platform platform;
    private final URI uri;
    private final Map<String, String> headers;
    private final String accountScope;
    private final Instant expiresAt;
    private final long length;
    private final java.util.function.BooleanSupplier active;

    public MediaLease(Platform platform, URI uri, Map<String, String> headers,
                      String accountScope, Instant expiresAt, long length) {
        this(platform,uri,headers,accountScope,expiresAt,length,()->true);
    }
    private MediaLease(Platform platform, URI uri, Map<String, String> headers,
                      String accountScope, Instant expiresAt, long length,java.util.function.BooleanSupplier active) {
        this.platform = Objects.requireNonNull(platform);
        requireTarget(uri);
        this.uri = uri;
        this.headers = Map.copyOf(headers);
        for (var entry : this.headers.entrySet()) {
            if (!HEADERS.contains(entry.getKey().toLowerCase(Locale.ROOT))
                    || !safeHeader(entry.getValue())) {
                throw new IllegalArgumentException("Unsupported upstream header");
            }
        }
        if (this.headers.keySet().stream().map(s -> s.toLowerCase(Locale.ROOT)).distinct().count()
                != this.headers.size()) throw new IllegalArgumentException("Duplicate upstream header");
        this.accountScope = Objects.requireNonNull(accountScope);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        if (accountScope.isBlank() || length < 1 || length > 2L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid media bounds or scope");
        }
        this.length = length;
        this.active=Objects.requireNonNull(active);
    }
    /** A fallback keeps its CDN policy/cookies but is owned by the original logical account. */
    public MediaLease bindTo(String scope,java.util.function.BooleanSupplier sourceActive) {
        return new MediaLease(platform,uri,headers,scope,expiresAt,length,()->isActive() && sourceActive.getAsBoolean());
    }
    public boolean isActive() {try {return active.getAsBoolean();} catch(RuntimeException failure) {return false;}}

    public void requireTarget(URI target) {
        requireTarget(platform, target);
    }

    public static void requireTarget(Platform platform, URI target) {
        Objects.requireNonNull(platform);
        Objects.requireNonNull(target);
        String host = target.getHost();
        boolean allowed = host != null && switch (platform) {
            case BILIBILI -> under(host, "bilivideo.com") || under(host, "bilivideo.cn");
            case NETEASE -> under(host, "music.126.net");
        };
        if (!allowed || !"https".equalsIgnoreCase(target.getScheme())
                || (target.getPort() != -1 && target.getPort() != 443)
                || target.getRawUserInfo() != null || target.getRawFragment() != null) {
            throw new IllegalArgumentException("Media target outside platform HTTPS policy");
        }
    }

    static boolean safeHeader(String text) {
        return text != null && !text.isEmpty() && text.length() <= 8192
                && text.chars().allMatch(c -> c >= 32 && c < 127);
    }

    private static boolean under(String host, String domain) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals(domain) || lower.endsWith("." + domain);
    }

    URI uri() { return uri; }
    Map<String, String> headers() { return headers; }
    String accountScope() { return accountScope; }
    public Instant expiresAt() { return expiresAt; }
    public long length() { return length; }
    public Platform platform() { return platform; }
    public boolean belongsTo(String scope) { return accountScope.equals(scope); }
    public String scope() { return accountScope; }

    @Override
    public String toString() { return "MediaLease[" + platform + ", credentials redacted]"; }
}
