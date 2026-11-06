package io.github.yghfv.saltneri.plugin.auth;

/* QR WEAPI/state/refresh-token mapping adapted from pinned NeriPlayer NeteaseQrLoginClient.
 * GPL-3.0-or-later; Android device/WebView acquisition is intentionally not ported. */

import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class NeteaseQrClient implements QrAuthClient {
    private static final String BASE = "https://music.163.com";
    private final Clock clock;
    private final AuthHttp http;
    private final ScopedCookies cookies;
    public NeteaseQrClient() { this(Clock.systemUTC(), new AuthHttp()); }
    public NeteaseQrClient(Clock clock, AuthHttp http) { this.clock = clock; this.http = http; cookies = new ScopedCookies(AccountPlatform.NETEASE, clock); }
    @Override public Session create(RequestScope request) throws IOException {
        cookies.clear(); Map<String, Object> root = post("/weapi/login/qrcode/unikey", Map.of("type", 1, "noCheckToken", true), Map.of(), request).json();
        requireCode(root, 200); String key = BoundedJson.string(root.get("unikey"));
        if (!key.matches("[a-zA-Z0-9_-]{16,256}")) throw invalid();
        String chain = "v1_unknown-" + UUID.randomUUID().toString().replace("-", "") + "_web_login_" + clock.millis();
        URI url = URI.create(BASE + "/st/platform/scanlogin?codekey=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&chainId=" + chain + "&hdw_device=web&hdw_appid=web&hitExp=1");
        return new Session(key, url, chain, clock.instant().plusSeconds(180));
    }
    @Override public Poll poll(Session session, RequestScope request) throws IOException {
        if (!clock.instant().isBefore(session.expiresAt())) return new Poll(State.EXPIRED, null);
        if (!session.key().matches("[a-zA-Z0-9_-]{16,256}") || !session.chainId().matches("[a-zA-Z0-9_-]{1,256}")) throw invalid();
        AuthHttp.Response response = post("/weapi/login/qrcode/client/login", Map.of("type", 1, "noCheckToken", true,
                "key", session.key(), "ydDeviceToken", ""), Map.of("x-loginmethod", "QrCode", "x-login-chain-id", session.chainId()), request);
        long code = BoundedJson.integer(response.json().get("code"));
        if (code == 801) return new Poll(State.WAITING_SCAN, null);
        if (code == 802) return new Poll(State.WAITING_CONFIRMATION, null);
        if (code == 800) return new Poll(State.EXPIRED, null);
        if (code != 803) throw new IOException("NetEase QR rejected (code " + code + ")");
        if (!response.refreshToken().isEmpty()) cookies.seedNeteaseRefresh(response.refreshToken());
        return new Poll(State.CONFIRMED, verifyJar(request));
    }
    private AccountCredentials verifyJar(RequestScope request) throws IOException {
        URI uri = URI.create(BASE + "/weapi/w/nuser/account/get");
        if (cookies.value(uri, "MUSIC_U").isEmpty()) throw new IOException("NetEase confirmation has no login credential");
        Map<String, Object> payload = new LinkedHashMap<>(); payload.put("noCheckToken", true);
        String csrf = cookies.value(uri, "__csrf"); if (!csrf.isEmpty()) payload.put("csrf_token", csrf);
        Map<String, Object> root = post("/weapi/w/nuser/account/get", payload, Map.of(), request).json(); requireCode(root, 200);
        long uid = 0;
        if (root.get("account") != null) uid = BoundedJson.integer(BoundedJson.object(root.get("account")).get("id"));
        if (root.get("profile") != null) {
            long profileId = BoundedJson.integer(BoundedJson.object(root.get("profile")).get("userId"));
            if (uid != 0 && uid != profileId) throw new IOException("NetEase account identity mismatch"); uid = profileId;
        }
        if (uid <= 0) throw new IOException("NetEase account verification rejected");
        request.check(); return new AccountCredentials(AccountPlatform.NETEASE, Long.toString(uid), cookies.snapshot(), clock);
    }
    private AuthHttp.Response post(String path, Map<String, Object> payload, Map<String, String> headers, RequestScope request) throws IOException {
        String query = payload.containsKey("csrf_token") ? "?csrf_token=" + URLEncoder.encode((String)payload.get("csrf_token"), StandardCharsets.UTF_8) : "";
        return http.exchange(URI.create(BASE + path + query), NeteaseWeapi.form(payload), cookies, headers, request);
    }
    @Override public AccountCredentials verify(AccountCredentials saved, RequestScope request) throws IOException {
        if (saved.platform() != AccountPlatform.NETEASE) throw invalid(); cookies.restore(saved.cookieSnapshot());
        AccountCredentials verified = verifyJar(request);
        if (!verified.userId().equals(saved.userId())) { verified.retire(); throw new IOException("Saved NetEase identity mismatch"); } return verified;
    }
    private static void requireCode(Map<String, Object> root, long expected) throws IOException { long code = BoundedJson.integer(root.get("code")); if (code != expected) throw new IOException("NetEase login rejected (code " + code + ")"); }
    private static IOException invalid() { return new IOException("Invalid NetEase login response"); }
    @Override public void close() { cookies.clear(); }
}
