package io.github.yghfv.saltneri.plugin.auth;

/* QR endpoints/state mapping adapted from pinned NeriPlayer BiliQrLoginClient.
 * GPL-3.0-or-later; original NeriPlayer developers' notices: THIRD_PARTY_NOTICES.md. */

import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;

public final class BiliQrClient implements QrAuthClient {
    private static final String BASE = "https://passport.bilibili.com/x/passport-login/web/qrcode/";
    private static final URI NAV = URI.create("https://api.bilibili.com/x/web-interface/nav");
    private final Clock clock;
    private final AuthHttp http;
    private final ScopedCookies cookies;
    public BiliQrClient() { this(Clock.systemUTC(), new AuthHttp()); }
    public BiliQrClient(Clock clock, AuthHttp http) { this.clock = clock; this.http = http; cookies = new ScopedCookies(AccountPlatform.BILIBILI, clock); }
    @Override public Session create(RequestScope request) throws IOException {
        cookies.clear(); Map<String, Object> root = http.exchange(URI.create(BASE + "generate"), null, cookies, Map.of(), request).json();
        Map<String, Object> data = success(root); String key = BoundedJson.string(data.get("qrcode_key"));
        URI qr;
        try { qr = URI.create(BoundedJson.string(data.get("url"))); } catch (IllegalArgumentException e) { throw invalid(); }
        boolean officialScan = ("passport.bilibili.com".equals(qr.getHost()) && "/h5-app/passport/login/scan".equals(qr.getPath()))
                || ("account.bilibili.com".equals(qr.getHost()) && "/h5/account-h5/auth/scan-web".equals(qr.getPath()));
        if (!key.matches("[a-zA-Z0-9_-]{16,256}") || !"https".equals(qr.getScheme()) || !officialScan
                || qr.getRawUserInfo() != null || qr.getRawFragment() != null
                || qr.getPort() != -1 || qr.toASCIIString().length() > 2048) throw invalid();
        boolean matched = false;
        if (qr.getRawQuery() != null) for (String part : qr.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            try {
                if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals("qrcode_key")) {
                    if (matched || pair.length != 2 || !URLDecoder.decode(pair[1], StandardCharsets.UTF_8).equals(key)) throw invalid(); matched = true;
                }
            } catch (IllegalArgumentException e) { throw invalid(); }
        }
        if (!matched) throw invalid(); return new Session(key, qr, "", clock.instant().plusSeconds(180));
    }
    @Override public Poll poll(Session session, RequestScope request) throws IOException {
        if (!clock.instant().isBefore(session.expiresAt())) return new Poll(State.EXPIRED, null);
        if (!session.key().matches("[a-zA-Z0-9_-]{16,256}")) throw invalid();
        Map<String, Object> data = success(http.exchange(URI.create(BASE + "poll?qrcode_key=" + URLEncoder.encode(session.key(), StandardCharsets.UTF_8)), null, cookies, Map.of(), request).json());
        long code = BoundedJson.integer(data.get("code"));
        if (code < Integer.MIN_VALUE || code > Integer.MAX_VALUE) throw invalid();
        return switch ((int)code) {
            case 86101 -> new Poll(State.WAITING_SCAN, null);
            case 86090 -> new Poll(State.WAITING_CONFIRMATION, null);
            case 86038 -> new Poll(State.EXPIRED, null);
            case 0 -> new Poll(State.CONFIRMED, verifyJar(request));
            default -> throw new IOException("Bilibili QR rejected (code " + code + ")");
        };
    }
    private AccountCredentials verifyJar(RequestScope request) throws IOException {
        if (cookies.value(NAV, "SESSDATA").isEmpty()) throw new IOException("Bilibili confirmation has no login credential");
        Map<String, Object> data = success(http.exchange(NAV, null, cookies, Map.of(), request).json());
        if (!Boolean.TRUE.equals(data.get("isLogin"))) throw new IOException("Bilibili account verification rejected");
        long mid = BoundedJson.integer(data.get("mid")); if (mid <= 0) throw invalid();
        String cookieId = cookies.value(NAV, "DedeUserID");
        if (!cookieId.isEmpty() && !cookieId.equals(Long.toString(mid))) throw new IOException("Bilibili account identity mismatch");
        request.check(); return new AccountCredentials(AccountPlatform.BILIBILI, Long.toString(mid), cookies.snapshot(), clock);
    }
    @Override public AccountCredentials verify(AccountCredentials saved, RequestScope request) throws IOException {
        if (saved.platform() != AccountPlatform.BILIBILI) throw invalid();
        cookies.restore(saved.cookieSnapshot()); AccountCredentials verified = verifyJar(request);
        if (!verified.userId().equals(saved.userId())) { verified.retire(); throw new IOException("Saved Bilibili identity mismatch"); }
        return verified;
    }
    private static Map<String, Object> success(Map<String, Object> root) throws IOException {
        long code = BoundedJson.integer(root.get("code")); if (code != 0) throw new IOException("Bilibili login rejected (code " + code + ")"); return BoundedJson.object(root.get("data"));
    }
    private static IOException invalid() { return new IOException("Invalid Bilibili login response"); }
    @Override public void close() { cookies.clear(); }
}
