package io.github.yghfv.saltneri.plugin.auth;

import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Private, platform-bounded cookie jar. Never consults CookieHandler or flattens Set-Cookie. */
public final class ScopedCookies {
    public record Entry(String name, String value, String domain, String path, boolean hostOnly, boolean secure, Instant expiry) {
        @Override public String toString() { return "Cookie[redacted]"; }
    }
    private final AccountPlatform platform;
    private final Clock clock;
    private final List<Entry> entries = new ArrayList<>();
    public ScopedCookies(AccountPlatform platform, Clock clock) { this.platform = platform; this.clock = clock; }
    public synchronized void accept(URI source, List<String> headers) throws IOException {
        requireSource(source); if (headers.size() > 64) throw invalid();
        List<Entry> updates = new ArrayList<>();
        for (String header : headers) {
            if (header == null || header.length() > 8192 || header.chars().anyMatch(c -> c < 32 || c > 126)) throw invalid();
            try {
                List<HttpCookie> parsed = HttpCookie.parse(header); if (parsed.size() != 1) throw invalid();
                HttpCookie cookie = parsed.getFirst(); String name = cookie.getName(), value = cookie.getValue();
                if (!safeName(name) || !safeValue(value)) throw invalid();
                boolean hostOnly = cookie.getDomain() == null;
                String domain = hostOnly ? source.getHost() : cookie.getDomain().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
                if (!platform.allows(domain) || !matchesHost(source.getHost(), domain, hostOnly)) throw invalid();
                String path = cookie.getPath();
                if (path == null || !path.startsWith("/")) { String request = source.getPath().isEmpty() ? "/" : source.getPath(); path = request.substring(0, Math.max(1, request.lastIndexOf('/'))); }
                if (path.length() > 1024 || path.chars().anyMatch(c -> c < 32 || c > 126)) throw invalid();
                if ((name.startsWith("__Secure-") && !cookie.getSecure()) || (name.startsWith("__Host-") && (!cookie.getSecure() || !hostOnly || !path.equals("/")))) throw invalid();
                long maxAge = cookie.getMaxAge();
                Instant expiry = clock.instant().plusSeconds(maxAge < 0 ? 30 * 86400L : Math.min(maxAge, 30 * 86400L));
                if (value.isEmpty() || maxAge == 0) expiry = clock.instant();
                updates.add(new Entry(name, value, domain, path, hostOnly, cookie.getSecure(), expiry));
            } catch (IllegalArgumentException error) { throw invalid(); }
        }
        for (Entry fresh : updates) {
            entries.removeIf(old -> old.name().equals(fresh.name()) && old.domain().equals(fresh.domain()) && old.path().equals(fresh.path()));
            if (fresh.expiry().isAfter(clock.instant()) && !fresh.value().isEmpty()) entries.add(fresh);
        }
        prune(); if (entries.size() > 64) { entries.clear(); throw invalid(); }
    }
    /** Only a confirmed NetEase refresh-token response can seed this credential. */
    public synchronized void seedNeteaseRefresh(String token) throws IOException {
        if (platform != AccountPlatform.NETEASE || !safeValue(token) || token.isBlank()) throw invalid();
        if (value(URI.create("https://music.163.com/"), "MUSIC_U").isEmpty()) {
            // NeriPlayer seeds confirmed refresh credentials as domain cookies, also covering the audio API.
            accept(URI.create("https://music.163.com/"), List.of("MUSIC_U=" + token + "; Domain=music.163.com; Path=/; Secure; Max-Age=2592000"));
        }
    }
    public synchronized String header(URI target) throws IOException {
        requireSource(target); prune(); String path = target.getPath().isEmpty() ? "/" : target.getPath();
        String header = entries.stream().filter(e -> matchesHost(target.getHost(), e.domain(), e.hostOnly()) && matchesPath(path, e.path()))
                .sorted(Comparator.comparingInt((Entry entry) -> entry.path().length()).reversed())
                .map(e -> e.name() + "=" + e.value()).collect(java.util.stream.Collectors.joining("; "));
        if (header.length() > 8192) throw invalid(); return header;
    }
    public synchronized String value(URI target, String name) throws IOException {
        requireSource(target); prune(); String path = target.getPath().isEmpty() ? "/" : target.getPath();
        return entries.stream().filter(e -> e.name().equals(name) && matchesHost(target.getHost(), e.domain(), e.hostOnly()) && matchesPath(path, e.path()))
                .sorted(Comparator.comparingInt((Entry e) -> e.path().length()).reversed()).map(Entry::value).findFirst().orElse("");
    }
    public synchronized List<Entry> snapshot() { prune(); return List.copyOf(entries); }
    public synchronized void restore(List<Entry> saved) throws IOException {
        if (saved.size() > 64) throw invalid();
        for (Entry entry : saved) {
            if (!safeName(entry.name()) || !safeValue(entry.value()) || !platform.allows(entry.domain())
                    || !entry.path().startsWith("/") || entry.path().length() > 1024 || entry.path().chars().anyMatch(c -> c < 32 || c > 126)
                    || entry.expiry().isAfter(clock.instant().plusSeconds(30 * 86400L))
                    || (entry.name().startsWith("__Secure-") && !entry.secure())
                    || (entry.name().startsWith("__Host-") && (!entry.secure() || !entry.hostOnly() || !entry.path().equals("/")))) throw invalid();
        }
        entries.clear(); entries.addAll(saved); prune();
    }
    public synchronized void clear() { entries.clear(); }
    private void prune() { entries.removeIf(e -> !e.expiry().isAfter(clock.instant())); }
    private void requireSource(URI uri) throws IOException {
        if (!"https".equals(uri.getScheme()) || !platform.allows(uri.getHost()) || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) throw invalid();
    }
    private static boolean matchesHost(String host, String domain, boolean hostOnly) { String lower = host.toLowerCase(Locale.ROOT); return lower.equals(domain) || (!hostOnly && lower.endsWith("." + domain)); }
    private static boolean matchesPath(String request, String path) { return request.equals(path) || (request.startsWith(path) && (path.endsWith("/") || request.charAt(path.length()) == '/')); }
    private static boolean safeName(String text) { return text != null && text.matches("[!#$%&'*+.^_`|~a-zA-Z0-9-]{1,128}"); }
    private static boolean safeValue(String text) { return text != null && text.length() <= 4096 && text.chars().allMatch(c -> c >= 33 && c <= 126 && c != ';' && c != ',' && c != '"' && c != '\\'); }
    private static IOException invalid() { return new IOException("Invalid platform cookie response"); }
    @Override public String toString() { return "ScopedCookies[" + platform + ", redacted]"; }
}
