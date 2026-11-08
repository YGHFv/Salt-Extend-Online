package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;

/** One fresh official-page device context, never a saved browser/account context. */
record NeteaseDeviceSnapshot(String token, String deviceId, String userAgent, List<ScopedCookies.Entry> cookies) {
    @FunctionalInterface interface Provider { NeteaseDeviceSnapshot load(RequestScope request) throws IOException; }
    NeteaseDeviceSnapshot { cookies = List.copyOf(cookies); }
    static NeteaseDeviceSnapshot load(RequestScope request) throws IOException {
        byte[] output = null;
        try {
            output = WindowsNeteaseWebLogin.runHelper(request, "Invoke-NeteaseDeviceSnapshot", 55000);
            request.check(); return read(output, Clock.systemUTC());
        } catch (IOException error) {
            throw AuthFailure.of(AuthFailure.Stage.NET_DEVICE, AuthFailure.Reason.DEVICE);
        } finally { if (output != null) Arrays.fill(output, (byte)0); }
    }
    static NeteaseDeviceSnapshot read(byte[] output, Clock clock) throws IOException {
        try {
            if (output.length > 65536) throw unavailable();
            var root = BoundedJson.object(BoundedJson.parse(output));
            if (root.get("error") != null) throw unavailable();
            var snapshot = new NeteaseDeviceSnapshot(BoundedJson.string(root.get("token")), BoundedJson.string(root.get("deviceId")),
                    BoundedJson.string(root.get("userAgent")), WindowsNeteaseWebLogin.readCookies(output, clock, false));
            snapshot.validate(clock); return snapshot;
        } catch (IOException | IllegalArgumentException error) { throw unavailable(); }
    }
    void validate(Clock clock) throws IOException {
        // Keep the token inside the bounded WEAPI form; do not accept empty/forged fallback contexts.
        if (token == null || !token.matches("[!-~]{1,4096}") || deviceId == null || !deviceId.matches("[a-zA-Z0-9_-]{1,128}")
                || !AuthHttp.validUserAgent(userAgent)) throw unavailable();
        var jar = new ScopedCookies(AccountPlatform.NETEASE, clock);
        try {
            jar.restore(cookies);
            if (cookies.stream().anyMatch(e -> e.name().equals("MUSIC_U"))
                    || !deviceId.equals(jar.value(URI.create("https://music.163.com/"), "sDeviceId"))) throw unavailable();
            jar.header(URI.create("https://music.163.com/"));
        } finally { jar.clear(); }
    }
    private static AuthFailure unavailable() { return AuthFailure.of(AuthFailure.Stage.NET_DEVICE, AuthFailure.Reason.DEVICE); }
    @Override public String toString() { return "NeteaseDeviceSnapshot[redacted]"; }
}
