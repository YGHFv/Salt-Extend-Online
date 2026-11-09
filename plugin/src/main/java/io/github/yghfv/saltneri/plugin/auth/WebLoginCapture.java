package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.util.*;

/** Verify only stable, changed cookie snapshots while the owned official window stays open. */
final class WebLoginCapture implements AutoCloseable {
    @FunctionalInterface interface Verifier {
        AccountCredentials verify(byte[] snapshot, RequestScope request) throws IOException;
    }
    private byte[] previous;
    private final Set<String> attempted = new HashSet<>();
    private AuthFailure rejected;
    AccountCredentials observe(List<Object> cookies, String userAgent, boolean ready, Verifier verifier, RequestScope request) throws IOException {
        request.check();
        byte[] snapshot = BoundedJson.encode(Map.of("cookies", cookies, "userAgent", userAgent));
        boolean stable = ready && previous != null && Arrays.equals(previous, snapshot);
        if (previous != null) Arrays.fill(previous, (byte)0);
        previous = snapshot;
        if (!stable || attempted.size() >= 3 || !attempted.add(fingerprint(cookies))) return null;
        AccountCredentials account;
        byte[] candidate = snapshot.clone();
        try { account = verifier.verify(candidate, request); }
        catch (AuthFailure failure) {
            // A partial/declined candidate is not a completed login. Never retry it unchanged.
            if (!Set.of(AuthFailure.Reason.API, AuthFailure.Reason.CREDENTIAL, AuthFailure.Reason.IDENTITY,
                    AuthFailure.Reason.COOKIE).contains(failure.reason())) throw failure;
            rejected = failure; return null;
        } finally { Arrays.fill(candidate, (byte)0); }
        try {
            request.check();
            if (account == null || !account.isActive()) throw new IOException("Unverified web login");
            return account;
        } catch (IOException | RuntimeException failure) {
            if (account != null) account.retire();
            throw failure;
        }
    }
    AuthFailure rejected() { return rejected; }
    private static String fingerprint(List<Object> cookies) throws IOException {
        var credentials = new ArrayList<Object>();
        for (Object row : cookies) {
            var cookie = BoundedJson.object(row);
            if (Set.of("MUSIC_U", "__csrf", "qqmusic_key", "qm_keyst", "musickey", "qqmusic_uin", "uin",
                    "wxuin", "tmeLoginType", "login_type").contains(cookie.get("name")))
                credentials.add(List.of(cookie.get("name"), cookie.get("value"), cookie.get("domain"), cookie.get("path")));
        }
        byte[] plain = BoundedJson.encode(credentials);
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(plain)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException("Web snapshot unavailable"); }
        finally { Arrays.fill(plain, (byte)0); }
    }
    @Override public void close() {
        if (previous != null) Arrays.fill(previous, (byte)0);
        previous = null; attempted.clear(); rejected = null;
    }
}
