package io.github.yghfv.saltneri.plugin.bilibili;

/* Bilibili protocol mapping adapted from pinned NeriPlayer. Copyright (C) 2025-2025
 * NeriPlayer developers. GPL-3.0-or-later; see THIRD_PARTY_NOTICES.md.
 * Explicit account revisions, authorization gates and bounded DNS-pinned transport are plugin-specific. */

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.host.Salt1193PlaybackSession.Metadata;
import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import io.github.yghfv.saltneri.plugin.net.JsonResponse;
import io.github.yghfv.saltneri.plugin.net.PinnedHttpsConnection;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.net.MediaRangeProbe;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import io.github.yghfv.saltneri.plugin.auth.AccountCredentials;
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Anonymous by default, or bound to one explicitly verified account revision. No paid/preview bypass. */
public class BiliClient {
    @FunctionalInterface interface Opener { HttpURLConnection open(URI uri) throws IOException; }
    private static final String API = "https://api.bilibili.com";
    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/132.0.0.0 Safari/537.36",
            "Referer", "https://www.bilibili.com/");
    private final Clock clock;
    private final Opener opener;
    private final AccountCredentials credentials;
    private String mixin;
    private Instant cachedAt;

    public static final class Resolved {
        private final TrackKey key;
        private final Metadata metadata;
        private final URI cover;
        private final long durationMillis;
        private final BiliAudioSelector.Audio audio;
        private final MediaLease lease;
        private final Instant expiresAt;
        private Resolved(TrackKey key, Metadata metadata, URI cover, long durationMillis,
                         BiliAudioSelector.Audio audio, MediaLease lease, Instant expiresAt) {
            this.key = key; this.metadata = metadata; this.cover = cover; this.durationMillis = durationMillis;
            this.audio = audio; this.lease = lease; this.expiresAt = expiresAt;
        }
        public TrackKey key() { return key; }
        public Metadata metadata() { return metadata; }
        /** Unfetched candidate, not evidence of a decoded or published host cover. */
        public URI coverCandidate() { return cover; }
        public long durationMillis() { return durationMillis; }
        public int bitrateKbps() { return audio.bitrateKbps(); }
        public String mimeType() { return audio.mimeType(); }
        public String codec() { return audio.codec(); }
        public MediaLease lease() { return lease; }
        public Instant expiresAt() { return expiresAt; }
        @Override public String toString() { return "BiliResolved[" + key.stableId() + ", " + bitrateKbps() + " kbps, URLs redacted]"; }
    }
    /** Only safe numeric categories, never a platform message or signed URL. */
    public static final class PlatformRejected extends IOException {
        public PlatformRejected(long code) { super("Bilibili rejected request (code " + code + ")"); }
    }
    public BiliClient() { this(null); }
    public BiliClient(AccountCredentials credentials) { this(Clock.systemUTC(), PinnedHttpsConnection::new, credentials); }
    BiliClient(Clock clock, Opener opener) { this(clock, opener, null); }
    BiliClient(Clock clock, Opener opener, AccountCredentials credentials) {
        this.clock = Objects.requireNonNull(clock); this.opener = Objects.requireNonNull(opener); this.credentials = credentials;
        if (credentials != null && credentials.platform() != AccountPlatform.BILIBILI) throw new IllegalArgumentException("Bilibili account required");
    }
    public record VideoPage(BiliTarget target,String title,String author,long durationMillis) { }
    public record VideoResolved(TrackKey key, Metadata metadata, long durationMillis, MediaLease lease) {
        @Override public String toString() { return "BiliVideo[" + key.stableId() + ", URL redacted]"; }
    }
    private record Resolution(TrackKey key, Metadata metadata, URI cover, long durationMillis,
                              BiliAudioSelector.Audio audio, MediaLease lease) { }
    public List<VideoPage> pages(String bvid,RequestScope scope) throws IOException {
        BiliTarget target=BiliTarget.parse(bvid);
        var view=successful(json(BiliWbi.sign(API+"/x/web-interface/wbi/view",Map.of("bvid",target.id()),key(scope),now()),scope));
        if(!bvid.equals(BoundedJson.string(view.get("bvid"))))throw invalid();
        if(view.get("state")!=null && BoundedJson.integer(view.get("state"))<0)throw new PlatformRejected(-1);
        if(flag(view.get("is_upower_exclusive")) || flag(view.get("is_upower_play")))throw new PlatformRejected(-1);
        if(view.get("rights")!=null) for(String gate:List.of("pay","is_stein_gate","is_upower_exclusive"))
            if(flag(BoundedJson.object(view.get("rights")).get(gate)))throw new PlatformRejected(-1);
        String title=label(view.get("title")),owner=label(BoundedJson.object(view.get("owner")).get("name"));
        var rows=BoundedJson.array(view.get("pages"));if(rows.size()>10000)throw invalid();
        List<VideoPage> pages=new ArrayList<>();
        for(Object row:rows) {
            var page=BoundedJson.object(row);
            long duration=positive(page.get("duration")),number=positive(page.get("page"));
            positive(page.get("cid"));
            if(duration>7*24*3600 || number>10000)throw invalid();
            pages.add(new VideoPage(new BiliTarget(bvid,(int)number),label(page.get("part"))+" "+title,owner,duration*1000));
        }
        check(scope);return List.copyOf(pages);
    }

    public Resolved resolve(BiliTarget target, BiliAudioSelector.Quality quality, String accountScope, RequestScope scope) throws IOException {
        var resolved=resolveMedia(target,quality,accountScope,scope,false);
        return new Resolved(resolved.key(),resolved.metadata(),resolved.cover(),resolved.durationMillis(),
                resolved.audio(),resolved.lease(),resolved.lease().expiresAt());
    }
    /** Recheck the exact page and permissions; request video only after the user selects video mode. */
    public VideoResolved resolveVideo(BiliTarget target,String accountScope,RequestScope scope) throws IOException {
        return resolveVideo(target,io.github.yghfv.saltneri.plugin.video.VideoResolution.DEFAULT,accountScope,scope);
    }
    public VideoResolved resolveVideo(BiliTarget target,io.github.yghfv.saltneri.plugin.video.VideoResolution resolution,String accountScope,RequestScope scope) throws IOException {
        var resolved=resolveMedia(target,BiliAudioSelector.Quality.HIGH,accountScope,scope,true,resolution);
        return new VideoResolved(resolved.key(),resolved.metadata(),resolved.durationMillis(),resolved.lease());
    }
    private Resolution resolveMedia(BiliTarget target,BiliAudioSelector.Quality quality,String accountScope,
                                    RequestScope scope,boolean video) throws IOException {
        return resolveMedia(target,quality,accountScope,scope,video,io.github.yghfv.saltneri.plugin.video.VideoResolution.DEFAULT);
    }
    private Resolution resolveMedia(BiliTarget target,BiliAudioSelector.Quality quality,String accountScope,
                                    RequestScope scope,boolean video,io.github.yghfv.saltneri.plugin.video.VideoResolution resolution) throws IOException {
        Objects.requireNonNull(target); Objects.requireNonNull(quality); Objects.requireNonNull(scope);
        if (accountScope == null || accountScope.isBlank()) throw new IllegalArgumentException("Missing operation scope");
        if (credentials != null && (!credentials.isActive() || !credentials.accountScope().equals(accountScope))) throw new java.util.concurrent.CancellationException("Account revision changed");
        check(scope); String key = key(scope);
        Map<String, String> identity = target.isBvid() ? Map.of("bvid", target.id()) : Map.of("aid", target.id().substring(2));
        Map<String, Object> view = successful(json(BiliWbi.sign(API + "/x/web-interface/wbi/view", identity, key, now()), scope));
        long aid = positive(view.get("aid")); String bvid = BoundedJson.string(view.get("bvid"));
        if ((target.isBvid() && !target.id().equals(bvid)) || (!target.isBvid() && aid != Long.parseLong(target.id().substring(2)))) throw invalid();
        if (view.get("state") != null && BoundedJson.integer(view.get("state")) < 0) throw new PlatformRejected(-1);
        if (flag(view.get("is_upower_exclusive")) || flag(view.get("is_upower_play"))) throw new PlatformRejected(-1);
        if (view.get("rights") != null) {
            Map<String, Object> rights = BoundedJson.object(view.get("rights"));
            for (String gate : List.of("pay", "is_stein_gate", "is_upower_exclusive")) if (flag(rights.get(gate))) throw new PlatformRejected(-1);
        }
        List<Object> pages = BoundedJson.array(view.get("pages"));
        if (pages.size() > 10000) throw invalid();
        Map<String, Object> page = null;
        for (Object item : pages) {
            Map<String, Object> candidate = BoundedJson.object(item);
            if (BoundedJson.integer(candidate.get("page")) == target.page()) {
                if (page != null) throw invalid(); page = candidate;
            }
        }
        if (page == null) throw new IOException("Requested Bilibili page unavailable");
        long cid = positive(page.get("cid"));
        TrackKey track;
        try { track = TrackKey.bilibili(bvid, cid); } catch (IllegalArgumentException e) { throw invalid(); }
        long duration = positive(page.get("duration"));
        if (duration > 7 * 24 * 3600) throw invalid();
        long expectedMillis = duration * 1000;
        String title = label(view.get("title"));
        if (pages.size() > 1) title += " / " + label(page.get("part"));
        String owner = label(BoundedJson.object(view.get("owner")).get("name"));
        Metadata metadata = new Metadata(title, owner, "Bilibili", owner);
        URI cover = cover(view.get("pic"));
        // DASH + Dolby + FLAC. No try_look, gaia avoidance, session or credentials.
        Map<String, String> parameters = new java.util.HashMap<>(Map.of("bvid", bvid, "cid", Long.toString(cid), "fnval", video ? "16" : "1104",
                "fnver", "0", "fourk", video && resolution.height()>=1440 ? "1" : "0", "otype", "json", "platform", "pc"));
        if(video)parameters.put("qn",Integer.toString(resolution.biliQuality()));
        Map<String, Object> play = successful(json(BiliWbi.sign(API + "/x/player/wbi/playurl", parameters, key, now()), scope));
        if (flag(play.get("is_preview")) || flag(play.get("is_trial"))) throw new PlatformRejected(-1);
        if (play.get("bvid") != null && !bvid.equals(BoundedJson.string(play.get("bvid")))) throw invalid();
        if (play.get("cid") != null && cid != BoundedJson.integer(play.get("cid"))) throw invalid();
        long millis = positive(play.get("timelength"));
        if (Math.abs(millis - expectedMillis) > Math.max(2000, expectedMillis / 100)) throw new IOException("Incomplete or mismatched Bilibili duration");
        BiliAudioSelector.Audio selected = video ? null : BiliAudioSelector.select(audio(play), quality);
        if (!video && selected == null) throw new IOException("No permitted Bilibili DASH audio; progressive fallback unsupported");
        List<URI> candidates=video ? videoCandidates(play,resolution.height()) : selected.candidates();
        for (URI candidate : candidates) {
            check(scope);
            try {
                MediaRangeProbe.Result probe;
                try { probe = MediaRangeProbe.probe(MediaLease.Platform.BILIBILI, candidate, HEADERS, 0,
                        clock.instant().plusSeconds(1800), clock, opener::open, scope, () -> check(scope)); }
                catch (MediaRangeProbe.Rejected denied) {
                    if(video && (denied.status()==404 || denied.status()==410))continue;
                    throw new PlatformRejected(denied.status());
                }
                check(scope);
                MediaLease lease = new MediaLease(MediaLease.Platform.BILIBILI, probe.uri(), HEADERS,
                        accountScope, probe.expiresAt(), probe.length());
                return new Resolution(track, metadata, cover, millis, selected, lease);
            } catch (PlatformRejected denied) { throw denied; }
            catch (IOException failure) { check(scope); /* Try only server-supplied backups of the selected audio. */ }
        }
        throw new IOException("Bilibili media range probe failed");
    }
    private static List<URI> videoCandidates(Map<String,Object> play,int preferredHeight) throws IOException {
        var dash=BoundedJson.object(play.get("dash"));
        var rows=BoundedJson.array(dash.get("video"));
        if(rows.size()>128)throw invalid();
        record VideoRow(Map<String,Object> data,long height,long bandwidth,int codec) { }
        var permitted=new ArrayList<VideoRow>();
        for(Object value:rows) {
            var row=BoundedJson.object(value);
            String mime=BoundedJson.string(row.getOrDefault("mime_type",row.get("mimeType")));
            String codec=BoundedJson.string(row.get("codecs"));
            long height=positive(row.get("height")),bandwidth=positive(row.get("bandwidth"));
            // Portrait 1080p is 1080x1920: compare the short edge, not portrait height.
            if(row.get("width")!=null)height=Math.min(height,positive(row.get("width")));
            int rank=codec.startsWith("avc1.")?0:codec.startsWith("hev1.") || codec.startsWith("hvc1.")?1:codec.startsWith("av01.")?2:-1;
            if(!mime.equals("video/mp4") || rank<0 || height>preferredHeight)continue;
            permitted.add(new VideoRow(row,height,bandwidth,rank));
        }
        permitted.sort(java.util.Comparator.comparingLong(VideoRow::height).reversed()
                .thenComparingInt(VideoRow::codec).thenComparing(java.util.Comparator.comparingLong(VideoRow::bandwidth).reversed()));
        var urls=new ArrayList<URI>();
        for(var item:permitted) {
            var row=item.data();
            var candidates=new ArrayList<Object>();candidates.add(row.getOrDefault("base_url",row.get("baseUrl")));
            Object backups=row.getOrDefault("backup_url",row.get("backupUrl"));
            if(backups!=null) {var list=BoundedJson.array(backups);if(list.size()>8)throw invalid();candidates.addAll(list);}
            // MCDN/nonstandard ports can precede a valid official HTTPS backup.
            // Reject the individual URL, then continue within the same authorized response.
            var usable=new ArrayList<URI>();
            for(Object url:candidates)try {URI parsed=videoUri(url);if(!usable.contains(parsed))usable.add(parsed);}
                catch(IOException unavailable) { }
            // Prefer ordinary signed CDN mirrors to MCDN. Keep the server's full URL/signature.
            usable.sort(java.util.Comparator.comparingInt(uri->uri.getHost().startsWith("upos-")?0:1));
            for(var uri:usable)if(!urls.contains(uri))urls.add(uri);
        }
        if(urls.isEmpty())throw new IOException("No permitted Bilibili video stream");
        return List.copyOf(urls);
    }
    private static URI videoUri(Object value) throws IOException {
        try {
            String text=BoundedJson.string(value);if(text.length()>8192)throw invalid();
            URI uri=URI.create(text.replaceFirst("^http:","https:"));
            MediaLease.requireTarget(MediaLease.Platform.BILIBILI,uri);return uri;
        } catch(IllegalArgumentException failure) {throw invalid();}
    }
    private void check(RequestScope scope) {
        scope.check();
        if (credentials != null && !credentials.isActive()) throw new java.util.concurrent.CancellationException("Account revision revoked");
    }
    private long now() { return clock.instant().getEpochSecond(); }
    private synchronized String key(RequestScope scope) throws IOException {
        Instant now = clock.instant();
        if (mixin != null && !now.isBefore(cachedAt) && now.isBefore(cachedAt.plusSeconds(600))) return mixin;
        Map<String, Object> nav = json(URI.create(API + "/x/web-interface/nav"), scope);
        long code = BoundedJson.integer(nav.get("code"));
        if (code != 0 && (credentials != null || code != -101)) throw new PlatformRejected(code);
        if (credentials != null && !Boolean.TRUE.equals(BoundedJson.object(nav.get("data")).get("isLogin"))) throw new IOException("Bilibili account session expired");
        if (credentials != null && !credentials.userId().equals(Long.toString(BoundedJson.integer(BoundedJson.object(nav.get("data")).get("mid"))))) throw new IOException("Bilibili account identity changed");
        Map<String, Object> images = BoundedJson.object(BoundedJson.object(nav.get("data")).get("wbi_img"));
        try {
            String fresh = BiliWbi.mixin(BoundedJson.string(images.get("img_url")), BoundedJson.string(images.get("sub_url")));
            check(scope); mixin = fresh; cachedAt = clock.instant(); return fresh;
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }
    private Map<String, Object> json(URI uri, RequestScope scope) throws IOException {
        if (!"https".equals(uri.getScheme()) || !"api.bilibili.com".equals(uri.getHost())) throw invalid();
        check(scope); HttpURLConnection connection = opener.open(uri); scope.attach(connection);
        try {
            configure(connection);
            if (credentials != null) connection.setRequestProperty("Cookie", credentials.header(uri));
            int status = connection.getResponseCode(); check(scope);
            if (status != 200) throw new PlatformRejected(status);
            Map<String, Object> result = JsonResponse.read(connection, scope, false); check(scope); return result;
        } finally { scope.release(connection); }
    }
    private static void configure(HttpURLConnection connection) throws IOException {
        connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
        connection.setConnectTimeout(8000); connection.setReadTimeout(8000); connection.setRequestMethod("GET");
        HEADERS.forEach(connection::setRequestProperty); connection.setRequestProperty("Accept-Encoding", "identity");
    }
    private static Map<String, Object> successful(Map<String, Object> root) throws IOException {
        long code = BoundedJson.integer(root.get("code")); if (code != 0) throw new PlatformRejected(code);
        return BoundedJson.object(root.get("data"));
    }
    private static long positive(Object value) throws IOException { long number = BoundedJson.integer(value); if (number <= 0) throw invalid(); return number; }
    private static boolean flag(Object value) throws IOException {
        if (value == null) return false;
        return value instanceof Boolean bool ? bool : BoundedJson.integer(value) != 0;
    }
    private static String label(Object value) throws IOException {
        String text = BoundedJson.string(value);
        if (text.isBlank() || text.length() > 4096 || text.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid(); return text;
    }
    private static List<BiliAudioSelector.Audio> audio(Map<String, Object> play) throws IOException {
        if (play.get("dash") == null) return List.of();
        Map<String, Object> dash = BoundedJson.object(play.get("dash"));
        List<BiliAudioSelector.Audio> result = new ArrayList<>();
        addAudio(result, dash.get("audio"), null, "audio/mp4");
        if (dash.get("dolby") != null) addAudio(result, BoundedJson.object(dash.get("dolby")).get("audio"), "dolby", "audio/eac3");
        if (dash.get("flac") != null) addAudio(result, BoundedJson.object(dash.get("flac")).get("audio"), "hires", "audio/flac");
        return result;
    }
    private static void addAudio(List<BiliAudioSelector.Audio> result, Object value, String tag, String defaultMime) throws IOException {
        if (value == null) return;
        List<Object> items = value instanceof List<?> ? BoundedJson.array(value) : List.of(value);
        if (items.size() > 64 || result.size() + items.size() > 128) throw invalid();
        for (Object item : items) {
            Map<String, Object> fields = BoundedJson.object(item);
            Object primary = alias(fields, "baseUrl", "base_url"); if (primary == null) continue;
            List<String> urls = new ArrayList<>(); urls.add(BoundedJson.string(primary));
            Object backups = alias(fields, "backupUrl", "backup_url");
            if (backups != null) {
                List<Object> list = BoundedJson.array(backups); if (list.size() > 8) throw invalid();
                for (Object backup : list) urls.add(BoundedJson.string(backup));
            }
            List<URI> candidates = BiliAudioSelector.candidates(urls); if (candidates.isEmpty()) continue;
            long id = positive(fields.get("id")); if (id > Integer.MAX_VALUE) throw invalid();
            long bandwidth = fields.get("bandwidth") == null ? 0 : BoundedJson.integer(fields.get("bandwidth"));
            if (bandwidth < 0 || bandwidth > Integer.MAX_VALUE * 1000L) throw invalid();
            Object mimeField = alias(fields, "mimeType", "mime_type");
            String mime = mimeField == null ? defaultMime : BoundedJson.string(mimeField);
            if (mime.isBlank()) mime = defaultMime;
            if (!mime.toLowerCase(Locale.ROOT).matches("audio/[a-z0-9.+-]{1,64}")) continue;
            String codec = fields.get("codecs") == null ? "" : BoundedJson.string(fields.get("codecs"));
            if (codec.length() > 128 || codec.chars().anyMatch(c -> c < 32 || c > 126)) throw invalid();
            result.add(new BiliAudioSelector.Audio((int)id, mime, codec, (int)(bandwidth / 1000), tag, candidates));
        }
    }
    private static Object alias(Map<String, Object> values, String primary, String fallback) { Object value = values.get(primary); return value == null ? values.get(fallback) : value; }
    private static URI cover(Object value) {
        if (!(value instanceof String text)) return null;
        try {
            if (text.startsWith("//")) text = "https:" + text;
            URI uri = URI.create(text); String host = uri.getHost();
            if (host == null || !(BiliAudioSelector.under(host, "hdslb.com") || BiliAudioSelector.under(host, "biliimg.com"))
                    || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getRawFragment() != null) return null;
            if ("http".equalsIgnoreCase(uri.getScheme())) uri = URI.create("https" + text.substring(4));
            return "https".equalsIgnoreCase(uri.getScheme()) ? uri : null;
        } catch (IllegalArgumentException failure) { return null; }
    }
    private static IOException invalid() { return new IOException("Invalid Bilibili platform response"); }
}
