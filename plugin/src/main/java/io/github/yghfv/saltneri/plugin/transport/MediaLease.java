package io.github.yghfv.saltneri.plugin.transport;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** An already-authorized, fixed media resource, never a user-supplied proxy target. */
public final class MediaLease {
    public enum Platform { BILIBILI, NETEASE, QQMUSIC, KUGOU, APPLEMUSIC }
    private static final Set<String> HEADERS = Set.of("user-agent", "referer", "cookie");
    // These official audio edges return HTTP URLs and do not present certificates for their hostnames.
    private static final Set<String> KUGOU_HTTP_EDGES = Set.of("fs.youthandroid.kugou.com", "fs.youthandroid2.kugou.com");
    private final Platform platform;
    private final URI uri;
    private final Map<String, String> headers;
    private final String accountScope;
    private final Instant expiresAt;
    private final long length;
    private final java.util.function.BooleanSupplier active;
    private final DecodedAudioSource decoded;

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
                    || !safeHeader(entry.getValue()) || "http".equalsIgnoreCase(uri.getScheme()) && entry.getKey().equalsIgnoreCase("cookie")) {
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
        this.decoded=null;
    }
    private MediaLease(DecodedAudioSource source,String scope,Instant expiry,java.util.function.BooleanSupplier active) {
        platform=Platform.APPLEMUSIC;uri=null;headers=Map.of();
        decoded=Objects.requireNonNull(source);accountScope=Objects.requireNonNull(scope);
        expiresAt=Objects.requireNonNull(expiry);this.active=Objects.requireNonNull(active);
        length=source.length();
        if(scope.isBlank() || length<44 || length>2L*1024*1024*1024)throw new IllegalArgumentException("Invalid decoded audio bounds");
    }
    public static MediaLease appleMusic(DecodedAudioSource source,String scope,Instant expiry,java.util.function.BooleanSupplier active) {
        return new MediaLease(source,scope,expiry,active);
    }
    DecodedAudioSource decoded() {return decoded;}
    public void release() {if(decoded!=null)decoded.close();}
    /** A fallback keeps its CDN policy/cookies but is owned by the original logical account. */
    public MediaLease bindTo(String scope,java.util.function.BooleanSupplier sourceActive) {
        if(decoded!=null)return new MediaLease(decoded,scope,expiresAt,()->isActive() && sourceActive.getAsBoolean());
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
            case BILIBILI -> under(host, "bilivideo.com") || under(host, "bilivideo.cn")
                    || host.toLowerCase(Locale.ROOT).matches("[a-f0-9]{32}\\.v\\.smtcdns\\.com");
            case NETEASE -> under(host, "music.126.net");
            case QQMUSIC -> under(host, "qqmusic.qq.com") || under(host, "music.tc.qq.com")
                    || host.equalsIgnoreCase("aqqmusic.tc.qq.com");
            case KUGOU -> under(host,"kugou.com") || under(host,"kgimg.com");
            case APPLEMUSIC -> false; // MusicKit owns DRM and HTTPS; only the typed PCM port is registered.
        };
        boolean plainKugou = platform == Platform.KUGOU && "http".equalsIgnoreCase(target.getScheme())
                && host != null && KUGOU_HTTP_EDGES.contains(host.toLowerCase(Locale.ROOT));
        int port = plainKugou ? 80 : 443;
        if (!allowed || !plainKugou && !"https".equalsIgnoreCase(target.getScheme())
                || (target.getPort() != -1 && target.getPort() != port)
                || target.getRawUserInfo() != null || target.getRawFragment() != null) {
            throw new IllegalArgumentException("Media target outside platform transport policy");
        }
    }

    public static void requireRedirect(Platform platform, URI from, URI to) {
        requireTarget(platform, from);
        requireTarget(platform, to);
        if ("https".equalsIgnoreCase(from.getScheme()) && !"https".equalsIgnoreCase(to.getScheme()))
            throw new IllegalArgumentException("Media HTTPS downgrade rejected");
        if (platform != Platform.BILIBILI && !from.getHost().equalsIgnoreCase(to.getHost()))
            throw new IllegalArgumentException("Cross-host media redirect rejected");
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
