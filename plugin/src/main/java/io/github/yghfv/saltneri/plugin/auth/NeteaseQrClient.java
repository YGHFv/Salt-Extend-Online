package io.github.yghfv.saltneri.plugin.auth;

/* QR WEAPI/state/refresh-token mapping adapted from pinned NeriPlayer NeteaseQrLoginClient.
 * GPL-3.0-or-later; official device context is acquired in a fresh isolated Edge profile. */

import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

public final class NeteaseQrClient implements QrAuthClient {
    private static final String BASE = "https://music.163.com";
    private final Clock clock;
    private final AuthHttp http;
    private final ScopedCookies cookies;
    private final NeteaseDeviceSnapshot.Provider devices;
    private volatile NeteaseDeviceSnapshot device;
    private Session activeSession;
    private RequestScope activeRequest;
    private boolean closed, started;
    public NeteaseQrClient() { this(Clock.systemUTC(), new AuthHttp()); }
    public NeteaseQrClient(Clock clock, AuthHttp http) { this(clock, http, NeteaseDeviceSnapshot::load); }
    NeteaseQrClient(Clock clock, AuthHttp http, NeteaseDeviceSnapshot.Provider devices) {
        this.clock = clock; this.http = http; this.devices = devices; cookies = new ScopedCookies(AccountPlatform.NETEASE, clock);
    }
    @Override public Session create(RequestScope request) throws IOException {
        beginRequest(request);
        try { return createSession(request); }
        catch (IOException error) { close(); throw AuthFailure.wrap(AuthFailure.Stage.NET_CREATE, AuthFailure.Reason.RESPONSE, error); }
        finally { endRequest(); }
    }
    private Session createSession(RequestScope request) throws IOException {
        synchronized (this) { if (started) throw invalid(); started = true; }
        cookies.clear();
        NeteaseDeviceSnapshot prepared;
        try { prepared = devices.load(request); if (prepared == null) throw invalid(); prepared.validate(clock); }
        catch (IOException error) {
            if(error instanceof AuthFailure safe)throw safe.atStage(AuthFailure.Stage.NET_DEVICE);
            throw AuthFailure.wrap(AuthFailure.Stage.NET_DEVICE,AuthFailure.Reason.DEVICE,error);
        }
        synchronized (this) { checkOpen(request); device = prepared; cookies.restore(prepared.cookies()); }
        Map<String, Object> root = post("/weapi/login/qrcode/unikey", Map.of("type", 1, "noCheckToken", true), Map.of(), request).json();
        requireCode(root, 200, AuthFailure.Stage.NET_CREATE); String key = BoundedJson.string(root.get("unikey"));
        if (!key.matches("[a-zA-Z0-9_-]{16,256}")) throw invalid();
        String chain = "v1_" + prepared.deviceId() + "_web_login_" + clock.millis();
        URI url = URI.create(BASE + "/st/platform/scanlogin?codekey=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&chainId=" + URLEncoder.encode(chain, StandardCharsets.UTF_8) + "&hdw_device=web&hdw_appid=web&hitExp=1");
        synchronized (this) {
            checkOpen(request); activeSession = new Session(key, url, chain, clock.instant().plusSeconds(180)); return activeSession;
        }
    }
    @Override public Poll poll(Session session, RequestScope request) throws IOException {
        beginRequest(request);
        try { return pollSession(session, request); }
        catch (IOException error) { close(); throw AuthFailure.wrap(AuthFailure.Stage.NET_POLL, AuthFailure.Reason.RESPONSE, error); }
        finally { endRequest(); }
    }
    private Poll pollSession(Session session, RequestScope request) throws IOException {
        NeteaseDeviceSnapshot context;
        synchronized (this) {
            if (session == null || session != activeSession || device == null) throw invalid(); context = device;
        }
        if (!clock.instant().isBefore(session.expiresAt())) { clearSession(); return new Poll(State.EXPIRED, null); }
        AuthHttp.Response response = post("/weapi/login/qrcode/client/login", pollPayload(session, context),
                Map.of("x-loginmethod", "QrCode", "x-login-chain-id", session.chainId()), request);
        long code = BoundedJson.integer(response.json().get("code"));
        if (code == 801) return new Poll(State.WAITING_SCAN, null);
        if (code == 802) return new Poll(State.WAITING_CONFIRMATION, null);
        if (code == 800) { clearSession(); return new Poll(State.EXPIRED, null); }
        if (code != 803) throw AuthFailure.code(AuthFailure.Stage.NET_POLL, AuthFailure.Reason.API, code);
        if (!response.refreshToken().isEmpty()) cookies.seedNeteaseRefresh(response.refreshToken());
        AccountCredentials verified = verifyJar(request);
        clearSession(); return new Poll(State.CONFIRMED, verified);
    }
    static Map<String, Object> pollPayload(Session session, NeteaseDeviceSnapshot context) {
        return Map.of("type", 1, "noCheckToken", true, "key", session.key(), "ydDeviceToken", context.token());
    }
    private AccountCredentials verifyJar(RequestScope request) throws IOException {
        try { return verifiedJar(request); }
        catch (IOException error) { throw AuthFailure.wrap(AuthFailure.Stage.NET_VERIFY, AuthFailure.Reason.RESPONSE, error); }
    }
    public AccountCredentials verifyWebCookies(java.util.List<ScopedCookies.Entry> entries, RequestScope request) throws IOException {
        beginRequest(request);
        try { clearSession(); cookies.restore(entries); return verifyJar(request); }
        finally { endRequest(); }
    }
    private AccountCredentials verifiedJar(RequestScope request) throws IOException {
        URI uri = URI.create(BASE + "/weapi/w/nuser/account/get");
        if (cookies.value(uri, "MUSIC_U").isEmpty()) throw AuthFailure.of(AuthFailure.Stage.NET_VERIFY, AuthFailure.Reason.CREDENTIAL);
        Map<String, Object> payload = new LinkedHashMap<>(); payload.put("noCheckToken", true);
        String csrf = cookies.value(uri, "__csrf"); if (!csrf.isEmpty()) payload.put("csrf_token", csrf);
        Map<String, Object> root = post("/weapi/w/nuser/account/get", payload, Map.of(), request).json(); requireCode(root, 200, AuthFailure.Stage.NET_VERIFY);
        long uid = 0;
        if (root.get("account") != null) uid = BoundedJson.integer(BoundedJson.object(root.get("account")).get("id"));
        if (root.get("profile") != null && BoundedJson.object(root.get("profile")).get("userId") != null) {
            long profileId = BoundedJson.integer(BoundedJson.object(root.get("profile")).get("userId"));
            if (uid != 0 && uid != profileId) throw AuthFailure.of(AuthFailure.Stage.NET_VERIFY, AuthFailure.Reason.IDENTITY); uid = profileId;
        }
        if (uid <= 0) throw AuthFailure.of(AuthFailure.Stage.NET_VERIFY, AuthFailure.Reason.IDENTITY);
        Map<String, Object> profile = root.get("profile") == null ? Map.of() : BoundedJson.object(root.get("profile"));
        synchronized (this) {
            checkOpen(request); return new AccountCredentials(AccountPlatform.NETEASE, Long.toString(uid), cookies.snapshot(), clock,
                    AccountCredentials.Profile.from(AccountPlatform.NETEASE, profile.get("nickname"), profile.get("avatarUrl")));
        }
    }
    private AuthHttp.Response post(String path, Map<String, Object> payload, Map<String, String> headers, RequestScope request) throws IOException {
        String query = payload.containsKey("csrf_token") ? "?csrf_token=" + URLEncoder.encode((String)payload.get("csrf_token"), StandardCharsets.UTF_8) : "";
        NeteaseDeviceSnapshot context = device;
        var response = http.exchange(URI.create(BASE + path + query), NeteaseWeapi.form(payload), cookies, headers,
                context == null ? AuthHttp.DEFAULT_USER_AGENT : context.userAgent(), request);
        synchronized (this) { checkOpen(request); }
        return response;
    }
    @Override public AccountCredentials verify(AccountCredentials saved, RequestScope request) throws IOException {
        beginRequest(request);
        try { clearSession(); return verifySaved(saved, request); }
        catch (IOException error) { throw AuthFailure.wrap(AuthFailure.Stage.NET_VERIFY, AuthFailure.Reason.RESPONSE, error); }
        finally { endRequest(); }
    }
    private AccountCredentials verifySaved(AccountCredentials saved, RequestScope request) throws IOException {
        if (saved.platform() != AccountPlatform.NETEASE) throw invalid(); cookies.restore(saved.cookieSnapshot());
        AccountCredentials verified = verifyJar(request);
        if (!verified.userId().equals(saved.userId())) { verified.retire(); throw AuthFailure.of(AuthFailure.Stage.NET_VERIFY, AuthFailure.Reason.IDENTITY); } return verified;
    }
    private static void requireCode(Map<String, Object> root, long expected, AuthFailure.Stage stage) throws IOException { long code = BoundedJson.integer(root.get("code")); if (code != expected) throw AuthFailure.code(stage, AuthFailure.Reason.API, code); }
    private static IOException invalid() { return new IOException("Invalid NetEase login response"); }
    private synchronized void beginRequest(RequestScope request) throws IOException {
        checkOpen(request); if (activeRequest != null) throw invalid(); activeRequest = request;
    }
    private synchronized void checkOpen(RequestScope request) throws IOException { request.check(); if (closed) throw invalid(); }
    private synchronized void endRequest() { activeRequest = null; if (closed) clearSession(); }
    private synchronized void clearSession() { activeSession = null; device = null; cookies.clear(); }
    @Override public synchronized void close() { closed = true; if (activeRequest != null) activeRequest.close(); clearSession(); }
}
