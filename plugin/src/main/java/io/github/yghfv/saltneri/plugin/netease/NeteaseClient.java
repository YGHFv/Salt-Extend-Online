package io.github.yghfv.saltneri.plugin.netease;

/* Song API parameters/field mapping adapted from pinned NeriPlayer NeteaseClient,
 * NeteaseSongMapping and NeteasePlaybackResponseParser. GPL-3.0-or-later.
 * Account fences, strict full-track gates and DNS-pinned transport are plugin-specific. */

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.auth.AccountCredentials;
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.auth.AuthHttp;
import io.github.yghfv.saltneri.plugin.auth.NeteaseQrClient;
import io.github.yghfv.saltneri.plugin.host.Salt1193PlaybackSession.Metadata;
import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import io.github.yghfv.saltneri.plugin.net.JsonResponse;
import io.github.yghfv.saltneri.plugin.net.MediaRangeProbe;
import io.github.yghfv.saltneri.plugin.net.PinnedHttpsConnection;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Resolve-only full tracks. Paid tracks require login and a server-authorized full response, never previews. */
public final class NeteaseClient {
    @FunctionalInterface interface Opener { HttpURLConnection open(URI uri, byte[] form) throws IOException; }
    public enum Quality {
        STANDARD("standard"), HIGH("exhigh");
        private final String key;
        Quality(String key) { this.key = key; }
        public String key() { return key; }
        public static Quality fromKey(String key) {
            for (Quality quality : values()) if (quality.key.equals(key)) return quality;
            throw new IllegalArgumentException("Unsupported NetEase quality");
        }
    }
    public record Resolved(TrackKey key, Metadata metadata, URI coverCandidate, long durationMillis,
                           int bitrateKbps, String level, String mimeType, MediaLease lease, Instant expiresAt) {
        @Override public String toString() { return "NeteaseResolved[" + key.stableId() + ", URLs redacted]"; }
    }
    public static final class PlatformRejected extends IOException {
        public PlatformRejected(long code) { super("NetEase rejected request (code " + code + ")"); }
    }
    private static final URI AUDIO_API = URI.create("https://interface.music.163.com" + NeteaseEapi.PATH);
    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/132.0.0.0 Safari/537.36",
            "Referer", "https://music.163.com/");
    private final AccountCredentials credentials;
    private final Opener opener;
    private final Clock clock;
    public NeteaseClient() { this(null); }
    public NeteaseClient(AccountCredentials credentials) {
        this(Clock.systemUTC(), NeteaseClient::openPinned, credentials);
    }
    static HttpURLConnection openPinned(URI uri, byte[] form) throws IOException {
        if (form == null) return new PinnedHttpsConnection(uri);
        if (AUDIO_API.equals(uri)) return PinnedHttpsConnection.neteaseSongPost(uri, form);
        if ("music.163.com".equals(uri.getHost()) && "/weapi/w/nuser/account/get".equals(uri.getPath())) {
            return PinnedHttpsConnection.neteaseFormPost(uri, form);
        }
        throw new IOException("Invalid NetEase resolver POST target");
    }
    NeteaseClient(Clock clock, Opener opener, AccountCredentials credentials) {
        this.clock = Objects.requireNonNull(clock); this.opener = Objects.requireNonNull(opener); this.credentials = credentials;
        if (credentials != null && credentials.platform() != AccountPlatform.NETEASE) throw new IllegalArgumentException("NetEase account required");
    }
    public Resolved resolve(NeteaseTarget target, Quality quality, String accountScope, RequestScope scope) throws IOException {
        Objects.requireNonNull(target); Objects.requireNonNull(quality); Objects.requireNonNull(scope);
        if (accountScope == null || accountScope.isBlank()) throw new IllegalArgumentException("Missing operation scope");
        if (credentials != null && !credentials.accountScope().equals(accountScope)) throw new CancellationException("Account revision changed");
        check(scope);
        AccountCredentials session = null;
        try {
        if (credentials != null) {
            // Recheck the original verified identity; do not silently resolve anonymously when it expires.
            try (NeteaseQrClient client = new NeteaseQrClient(clock, new AuthHttp(opener::open))) {
                session = client.verify(credentials, scope);
            }
            boolean hasLogin = java.util.Arrays.stream(session.header(AUDIO_API).split(";\\s*"))
                    .anyMatch(cookie -> cookie.startsWith("MUSIC_U=") && cookie.length() > 8);
            if (!hasLogin) throw new IOException("NetEase account cookie does not cover the audio API host");
        }
        Map<String, Object> detail = successful(json(URI.create("https://music.163.com/api/song/detail?ids=%5B" + target.songId() + "%5D"), null, scope, session));
        Map<String, Object> song = exact(detail.get("songs"), target.songId());
        fee(song.get("fee")); if (numberOrZero(song.get("status")) < 0 || numberOrZero(song.get("st")) < 0) throw new PlatformRejected(-1);
        if (detail.get("privileges") != null) {
            Map<String, Object> privilege = exact(detail.get("privileges"), target.songId());
            fee(privilege.get("fee"));
            if (numberOrZero(privilege.get("st")) < 0 || (privilege.get("pl") != null && BoundedJson.integer(privilege.get("pl")) <= 0)) throw new PlatformRejected(-1);
        }
        String title = label(song.get("name"), false);
        Object artistField = alias(song, "ar", "artists"); List<String> artists = new ArrayList<>();
        if (artistField != null) {
            List<Object> entries = BoundedJson.array(artistField); if (entries.size() > 64) throw invalid();
            for (Object entry : entries) artists.add(label(BoundedJson.object(entry).get("name"), false));
        }
        String artist = String.join(" / ", artists);
        Object albumField = alias(song, "al", "album"); Map<String, Object> album = albumField == null ? Map.of() : BoundedJson.object(albumField);
        Metadata metadata = new Metadata(title, artist, label(album.get("name"), true), artist);
        long duration = positive(alias(song, "dt", "duration")); if (duration > 7L * 24 * 3600 * 1000) throw invalid();
        URI cover = cover(album.get("picUrl"));
        Map<String, Object> parameters = new LinkedHashMap<>(); parameters.put("ids", "[" + target.songId() + "]");
        parameters.put("level", quality.key()); parameters.put("encodeType", "flac");
        Map<String, Object> data = exact(successful(json(AUDIO_API, NeteaseEapi.form(parameters), scope, session)).get("data"), target.songId());
        long dataCode = BoundedJson.integer(data.get("code")); if (dataCode != 200) throw new PlatformRejected(dataCode);
        fee(data.get("fee"));
        if (data.get("freeTrialInfo") != null || flag(data.get("isPreview")) || flag(data.get("isTrial"))) throw new PlatformRejected(-1);
        if (data.get("freeTrialPrivilege") != null) {
            Map<String, Object> privilege = BoundedJson.object(data.get("freeTrialPrivilege"));
            if (numberOrZero(privilege.get("cannotListenReason")) != 0 || flag(privilege.get("listenType"))) throw new PlatformRejected(-1);
        }
        long audioDuration = positive(data.get("time"));
        if (Math.abs(audioDuration - duration) > Math.max(2000, duration / 100)) throw new IOException("Incomplete or mismatched NetEase duration");
        long size = positive(data.get("size")); if (size > 2L * 1024 * 1024 * 1024) throw invalid();
        long bitrate = positive(alias(data, "br", "bitrate")); if (bitrate > 100000000) throw invalid();
        int kbps = (int)(bitrate >= 10000 ? bitrate / 1000 : bitrate);
        String level = label(data.get("level"), false);
        if (!(level.equals("standard") || quality == Quality.HIGH && level.equals("exhigh"))) throw new PlatformRejected(-1);
        String mime = switch (label(data.get("type"), false).toLowerCase(Locale.ROOT)) {
            case "mp3" -> "audio/mpeg"; case "flac" -> "audio/flac"; case "m4a" -> "audio/mp4"; case "aac" -> "audio/aac";
            default -> throw invalid();
        };
        URI media = media(data.get("url")); long seconds = positive(data.get("expi"));
        Instant expiry = clock.instant().plusSeconds(Math.min(300, seconds)); check(scope);
        MediaRangeProbe.Result probe;
        try { probe = MediaRangeProbe.probe(MediaLease.Platform.NETEASE, media, HEADERS, size, expiry, clock,
                uri -> opener.open(uri, null), scope, () -> check(scope)); }
        catch (MediaRangeProbe.Rejected denied) { throw new PlatformRejected(denied.status()); }
        check(scope);
        MediaLease lease = new MediaLease(MediaLease.Platform.NETEASE, probe.uri(), HEADERS, accountScope, probe.expiresAt(), probe.length());
        return new Resolved(TrackKey.netease(Long.toString(target.songId())), metadata, cover, audioDuration, kbps, level, mime, lease, probe.expiresAt());
        } finally { if (session != null) session.retire(); }
    }
    private Map<String, Object> json(URI uri, byte[] form, RequestScope scope, AccountCredentials session) throws IOException {
        boolean detail = "music.163.com".equals(uri.getHost()) && "/api/song/detail".equals(uri.getPath()) && form == null;
        boolean audio = AUDIO_API.equals(uri) && form != null;
        if (!(detail || audio) || !"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null) throw invalid();
        check(scope); HttpURLConnection connection = opener.open(uri, form); scope.attach(connection);
        try {
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false); connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
            connection.setRequestMethod(form == null ? "GET" : "POST"); HEADERS.forEach(connection::setRequestProperty);
            connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("Accept-Encoding", "identity");
            if (session != null) connection.setRequestProperty("Cookie", session.header(uri));
            int status = connection.getResponseCode(); check(scope); if (status != 200) throw new PlatformRejected(status);
            Map<String, Object> result = JsonResponse.read(connection, scope, true); check(scope); return result;
        } finally { scope.release(connection); }
    }
    private void check(RequestScope scope) {
        scope.check(); if (credentials != null && !credentials.isActive()) throw new CancellationException("NetEase account revision revoked");
    }
    private static Map<String, Object> successful(Map<String, Object> root) throws IOException {
        long code = BoundedJson.integer(root.get("code")); if (code != 200) throw new PlatformRejected(code); return root;
    }
    private static Map<String, Object> exact(Object value, long id) throws IOException {
        List<Object> entries = value instanceof Map<?, ?> ? List.of(value) : BoundedJson.array(value);
        if (entries.size() != 1) throw invalid(); Map<String, Object> entry = BoundedJson.object(entries.getFirst());
        if (BoundedJson.integer(entry.get("id")) != id) throw invalid(); return entry;
    }
    private static Object alias(Map<String, Object> data, String primary, String fallback) { return data.get(primary) == null ? data.get(fallback) : data.get(primary); }
    private static long positive(Object value) throws IOException { long n = BoundedJson.integer(value); if (n <= 0) throw invalid(); return n; }
    private static long numberOrZero(Object value) throws IOException { return value == null ? 0 : BoundedJson.integer(value); }
    private static boolean flag(Object value) throws IOException { return value instanceof Boolean b ? b : numberOrZero(value) != 0; }
    private void fee(Object value) throws IOException {
        long fee = BoundedJson.integer(value);
        if (fee != 0 && fee != 8 && !(credentials != null && (fee == 1 || fee == 4))) throw new PlatformRejected(-1);
    }
    private static String label(Object value, boolean optional) throws IOException {
        if (value == null && optional) return ""; String text = BoundedJson.string(value);
        if ((!optional && text.isBlank()) || text.length() > 4096 || text.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid(); return text;
    }
    private static URI media(Object value) throws IOException {
        String text = BoundedJson.string(value); if (text.length() > 8192) throw invalid();
        try {
            URI uri = URI.create(text); if ("http".equals(uri.getScheme())) uri = URI.create("https" + text.substring(4));
            MediaLease.requireTarget(MediaLease.Platform.NETEASE, uri); return uri;
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }
    private static URI cover(Object value) {
        if (!(value instanceof String text) || text.length() > 8192) return null;
        try {
            URI uri = URI.create(text); if ("http".equals(uri.getScheme())) uri = URI.create("https" + text.substring(4));
            MediaLease.requireTarget(MediaLease.Platform.NETEASE, uri); return uri;
        } catch (IllegalArgumentException failure) { return null; }
    }
    private static IOException invalid() { return new IOException("Invalid NetEase song response"); }
}
