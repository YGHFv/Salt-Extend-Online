package io.github.yghfv.saltneri.plugin.auth;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** A verified account revision. Old revisions are invalid after replacement, logout or disable. */
public final class AccountCredentials {
    private final AccountPlatform platform;
    private final String userId;
    private final String scope;
    private final ScopedCookies cookies;
    private final AtomicBoolean active = new AtomicBoolean(true);
    public AccountCredentials(AccountPlatform platform, String userId, List<ScopedCookies.Entry> entries, Clock clock) throws IOException {
        if (userId == null || !userId.matches("[1-9][0-9]{0,18}")) throw new IOException("Invalid verified account identity");
        try { Long.parseLong(userId); } catch (NumberFormatException error) { throw new IOException("Invalid verified account identity"); }
        this.platform = platform; this.userId = userId; scope = platform.name().toLowerCase(java.util.Locale.ROOT) + ":account:" + userId + ":" + UUID.randomUUID();
        cookies = new ScopedCookies(platform, clock); cookies.restore(entries);
        if (cookies.value(accountUri(), platform.loginCookie()).isEmpty()) throw new IOException("Confirmed login has no usable account credential");
    }
    private URI accountUri() { return URI.create(platform == AccountPlatform.BILIBILI ? "https://api.bilibili.com/" : "https://music.163.com/"); }
    public AccountPlatform platform() { return platform; }
    public String userId() { return userId; }
    public String accountScope() { return scope; }
    public boolean isActive() {
        if (!active.get()) return false;
        try { if (cookies.value(accountUri(), platform.loginCookie()).isEmpty()) { retire(); return false; } }
        catch (IOException impossible) { retire(); return false; } return active.get();
    }
    public String header(URI target) throws IOException {
        if (!isActive()) throw new IOException("Account session revoked or expired"); return cookies.header(target);
    }
    public List<ScopedCookies.Entry> cookieSnapshot() throws IOException { if (!isActive()) throw new IOException("Account session revoked or expired"); return cookies.snapshot(); }
    public void retire() { active.set(false); cookies.clear(); }
    @Override public String toString() { return "AccountCredentials[" + platform + ", secrets redacted]"; }
}
