package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Optional, explicit plugin-owned persistence. Never falls back to cleartext on DPAPI failure. */
public final class EncryptedAccountStore {
    public interface Protector { byte[] protect(byte[] plain) throws IOException; byte[] unprotect(byte[] cipher) throws IOException; }
    @FunctionalInterface public interface IoAction { void run() throws IOException; }
    @FunctionalInterface public interface Promotion { void publish(IoAction action) throws IOException; }
    private static final byte[] MAGIC = {'S','N','A','U','T','H',1};
    private final Path parent;
    private final Protector protector;
    private final Clock clock;
    public EncryptedAccountStore(Path pluginDataRoot) { this(pluginDataRoot, new WindowsDpapi(), Clock.systemUTC()); }
    public EncryptedAccountStore(Path pluginDataRoot, Protector protector, Clock clock) { parent = pluginDataRoot.toAbsolutePath().normalize(); this.protector = protector; this.clock = clock; }
    private Path directory(boolean create) throws IOException {
        Path actualParent = parent.toRealPath(); Path root = actualParent.resolve("saltneri-auth");
        if (create && !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            try { Files.createDirectory(root); } catch (java.nio.file.FileAlreadyExistsException concurrent) { }
        }
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !root.toRealPath().equals(root))) throw new IOException("Unsafe account storage directory");
        return root;
    }
    private Path target(AccountPlatform platform, boolean create) throws IOException {
        Path file = directory(create).resolve(platform.name().toLowerCase(java.util.Locale.ROOT) + ".dpapi");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().equals(file))) throw new IOException("Unsafe account storage file"); return file;
    }
    public synchronized void save(AccountCredentials account, BooleanSupplier stillCurrent) throws IOException {
        save(account, stillCurrent, action -> { if (!stillCurrent.getAsBoolean()) throw new IOException("Account save cancelled"); action.run(); });
    }
    public synchronized void save(AccountCredentials account, BooleanSupplier stillCurrent, Promotion promotion) throws IOException {
        if (!stillCurrent.getAsBoolean() || !account.isActive()) throw new IOException("Account save cancelled");
        List<Object> cookies = new ArrayList<>();
        for (var entry : account.cookieSnapshot()) {
            cookies.add(Map.of("name", entry.name(), "value", entry.value(), "domain", entry.domain(), "path", entry.path(),
                    "hostOnly", entry.hostOnly(), "secure", entry.secure(), "expiry", entry.expiry().getEpochSecond()));
        }
        byte[] plain = BoundedJson.encode(Map.of("version", 1, "platform", account.platform().name(), "userId", account.userId(), "cookies", cookies));
        byte[] sealed;
        try { if (plain.length > 60000) throw new IOException("Account snapshot too large"); sealed = protector.protect(plain); }
        finally { Arrays.fill(plain, (byte)0); }
        if (sealed.length > 65536 || !stillCurrent.getAsBoolean() || !account.isActive()) throw new IOException("Account save cancelled");
        Path file = target(account.platform(), true); Path temporary = Files.createTempFile(file.getParent(), ".auth-", ".tmp");
        try {
            byte[] content = new byte[MAGIC.length + sealed.length]; System.arraycopy(MAGIC, 0, content, 0, MAGIC.length); System.arraycopy(sealed, 0, content, MAGIC.length, sealed.length);
            Files.write(temporary, content);
            if (!stillCurrent.getAsBoolean() || !account.isActive()) throw new IOException("Account save cancelled");
            // Same-directory atomic replacement never truncates a pre-existing hard link or follows a link.
            promotion.publish(() -> {
                if (!stillCurrent.getAsBoolean() || !account.isActive()) throw new IOException("Account save cancelled");
                Files.move(temporary, target(account.platform(), true), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(marker(account.platform(), true));
            });
        } finally { Files.deleteIfExists(temporary); }
    }
    public synchronized AccountCredentials load(AccountPlatform platform) throws IOException {
        if (Files.exists(marker(platform, false), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Account was explicitly signed out");
        Path file = target(platform, false); if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("No saved account for this platform");
        if (Files.size(file) > 65536 + MAGIC.length) throw new IOException("Invalid saved account");
        byte[] content;
        try (var input = Files.newInputStream(file)) { content = input.readNBytes(65536 + MAGIC.length + 1); }
        if (content.length <= MAGIC.length || content.length > 65536 + MAGIC.length || !Arrays.equals(Arrays.copyOf(content, MAGIC.length), MAGIC)) throw new IOException("Invalid saved account");
        byte[] plain = protector.unprotect(Arrays.copyOfRange(content, MAGIC.length, content.length));
        try {
            if (plain.length > 60000) throw new IOException("Invalid saved account");
            var root = BoundedJson.object(BoundedJson.parse(plain));
            if (BoundedJson.integer(root.get("version")) != 1 || !platform.name().equals(BoundedJson.string(root.get("platform")))) throw new IOException("Saved account platform/version mismatch");
            List<ScopedCookies.Entry> entries = new ArrayList<>();
            var cookies = BoundedJson.array(root.get("cookies")); if (cookies.size() > 64) throw new IOException("Invalid saved account");
            for (Object value : cookies) {
                var entry = BoundedJson.object(value);
                if (!(entry.get("hostOnly") instanceof Boolean hostOnly) || !(entry.get("secure") instanceof Boolean secure)) throw new IOException("Invalid saved account");
                entries.add(new ScopedCookies.Entry(BoundedJson.string(entry.get("name")), BoundedJson.string(entry.get("value")), BoundedJson.string(entry.get("domain")),
                        BoundedJson.string(entry.get("path")), hostOnly, secure, Instant.ofEpochSecond(BoundedJson.integer(entry.get("expiry")))));
            }
            return new AccountCredentials(platform, BoundedJson.string(root.get("userId")), entries, clock);
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) { throw new IOException("Invalid saved account"); }
        finally { Arrays.fill(plain, (byte)0); }
    }
    public synchronized boolean hasSaved(AccountPlatform platform) throws IOException {
        return !Files.exists(marker(platform, false), LinkOption.NOFOLLOW_LINKS)
                && Files.exists(target(platform, false), LinkOption.NOFOLLOW_LINKS);
    }
    public synchronized void delete(AccountPlatform platform) throws IOException { Files.deleteIfExists(target(platform, false)); }
    private Path marker(AccountPlatform platform, boolean create) throws IOException {
        Path marker = directory(create).resolve(platform.name().toLowerCase(java.util.Locale.ROOT) + ".signed-out");
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS) && (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || !marker.toRealPath().equals(marker))) throw new IOException("Unsafe account sign-out marker");
        return marker;
    }
    /** Nonsecret tombstone survives disable/shutdown even if queued encrypted-file deletion cannot run. */
    public void markLoggedOut(AccountPlatform platform) throws IOException {
        Path marker = marker(platform, true); Path temporary = Files.createTempFile(marker.getParent(), ".logout-", ".tmp");
        try { Files.move(temporary, marker(platform, true), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
    }
}
