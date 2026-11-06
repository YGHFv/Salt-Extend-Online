package io.github.yghfv.saltneri.plugin.auth;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** A verified account revision. Old revisions are invalid after replacement, logout or disable. */
public final class AccountCredentials {
    public record Profile(String name, URI avatar) {
        public static Profile from(AccountPlatform platform, Object name, Object avatar) {
            String label = name instanceof String text && text.length() <= 128 && text.chars().noneMatch(Character::isISOControl) ? text : "";
            URI image = avatar instanceof String text ? io.github.yghfv.saltneri.plugin.library.CoverSource.parse(platform, text) : null;
            return new Profile(label, image);
        }
        @Override public String toString() { return "AccountProfile[redacted]"; }
    }
    private final AccountPlatform platform;
    private final String userId;
    private final String scope;
    private final ScopedCookies cookies;
    private final Profile profile;
    private final String revision = UUID.randomUUID().toString();
    private final AtomicBoolean active = new AtomicBoolean(true);
    public AccountCredentials(AccountPlatform platform, String userId, List<ScopedCookies.Entry> entries, Clock clock) throws IOException {
        this(platform, userId, entries, clock, new Profile("", null));
    }
    public AccountCredentials(AccountPlatform platform, String userId, List<ScopedCookies.Entry> entries, Clock clock, Profile profile) throws IOException {
        if (userId == null || !userId.matches("[1-9][0-9]{0,18}")) throw new IOException("Invalid verified account identity");
        try { Long.parseLong(userId); } catch (NumberFormatException error) { throw new IOException("Invalid verified account identity"); }
        this.platform = platform; this.userId = userId; scope = platform.name().toLowerCase(java.util.Locale.ROOT) + ":account:" + userId + ":" + UUID.randomUUID();
        this.profile = Profile.from(platform, profile.name(), profile.avatar() == null ? null : profile.avatar().toString());
        cookies = new ScopedCookies(platform, clock); cookies.restore(entries);
        if (cookies.value(accountUri(), platform.loginCookie()).isEmpty()) throw new IOException("Confirmed login has no usable account credential");
    }
    private URI accountUri() { return URI.create(platform == AccountPlatform.BILIBILI ? "https://api.bilibili.com/" : "https://music.163.com/"); }
    public AccountPlatform platform() { return platform; }
    public String userId() { return userId; }
    public Profile profile() { return profile; }
    public String revision() { return revision; }
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
