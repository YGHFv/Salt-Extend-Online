package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.JsonResponse;
import io.github.yghfv.saltneri.plugin.net.PinnedHttpsConnection;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/** Isolated login HTTP. Redirects, risk retries and unsupported encodings are never followed. */
public final class AuthHttp {
    @FunctionalInterface public interface Opener { HttpURLConnection open(URI uri, byte[] form) throws IOException; }
    public record Response(Map<String, Object> json, String refreshToken) {
        @Override public String toString() { return "AuthResponse[redacted]"; }
    }
    private final Opener opener;
    public AuthHttp() { this((uri, form) -> form == null ? new PinnedHttpsConnection(uri) : PinnedHttpsConnection.neteaseFormPost(uri, form)); }
    public AuthHttp(Opener opener) { this.opener = opener; }
    public Response exchange(URI uri, byte[] form, ScopedCookies cookies, Map<String, String> extras, RequestScope scope) throws IOException {
        boolean bili = "passport.bilibili.com".equals(uri.getHost()) && Set.of("/x/passport-login/web/qrcode/generate", "/x/passport-login/web/qrcode/poll").contains(uri.getPath());
        boolean nav = "api.bilibili.com".equals(uri.getHost()) && "/x/web-interface/nav".equals(uri.getPath());
        boolean netease = "music.163.com".equals(uri.getHost()) && Set.of("/weapi/login/qrcode/unikey", "/weapi/login/qrcode/client/login", "/weapi/w/nuser/account/get").contains(uri.getPath());
        if (!"https".equals(uri.getScheme()) || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || !(bili || nav || netease) || (form == null) == netease) throw new IOException("Invalid login API target");
        scope.check(); HttpURLConnection connection = opener.open(uri, form); scope.attach(connection);
        try {
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false); connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
            connection.setRequestMethod(form == null ? "GET" : "POST");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/132.0.0.0 Safari/537.36");
            connection.setRequestProperty("Referer", netease ? "https://music.163.com/" : "https://passport.bilibili.com/login");
            connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("Accept-Encoding", "identity");
            if (netease) { connection.setRequestProperty("Origin", "https://music.163.com"); connection.setRequestProperty("x-os", "web"); }
            String cookie = cookies.header(uri); if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
            for (var header : extras.entrySet()) {
                if (!Set.of("x-loginmethod", "x-login-chain-id").contains(header.getKey().toLowerCase(Locale.ROOT))) throw new IOException("Invalid login API header");
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            int status = connection.getResponseCode(); scope.check();
            if (status != 200) throw new IOException("Login HTTP rejected (status " + status + ")");
            Map<String, Object> json = JsonResponse.read(connection, scope, true);
            cookies.accept(uri, JsonResponse.setCookies(connection));
            String refresh = connection.getHeaderField("x-refresh-token");
            if (refresh != null && (refresh.length() > 4096 || refresh.chars().anyMatch(c -> c < 33 || c > 126))) throw new IOException("Invalid login refresh token");
            return new Response(json, refresh == null ? "" : refresh);
        } finally { scope.release(connection); }
    }
}
